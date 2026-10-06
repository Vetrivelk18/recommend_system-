"""
train_two_tower.py - retrain the two-tower model with in-batch negatives.

    python3 train_two_tower.py

Expects X_user.npy, X_item.npy and y_true.npy in the working directory, in the
same layout the original notebook produced: X_item is [N, 4, 10] holding one
positive and three sampled negatives, y_true indexes the positive.

Two changes from the original run:

  1. Temperature on the logits. Both towers l2_normalize, so dot products sit in
     [-1, 1]. Softmax over that range has almost no gradient, and the cheapest way
     for the model to look confident is to drive every item to the extremes of a
     single axis - which is what happened: the previous checkpoint spans about 1.65
     effective dimensions out of 128, putting Banana and Bag of Organic Bananas at
     near-opposite poles.

  2. In-batch negatives instead of the three sampled ones. At batch 512 every
     example is scored against 511 negatives that are products other real users
     bought, rather than uniform draws that almost always come from a different
     department and make the task trivial.
"""

import numpy as np
import tensorflow as tf
from tensorflow import keras
from tensorflow.keras import layers

BATCH_SIZE = 512        # also the negative count per example, minus one
TEMPERATURE = 0.05
EMBEDDING_DIM = 128
EPOCHS = 10


class UserTower(keras.layers.Layer):
    """
    Input layout (9 cols):
      0 distinct_products      5 most_common_department  (categorical)
      1 reorder_tendency       6 fraction_organic
      2 dow_mode               7 avg_days_between
      3 hour_mode              8 avg_basket_size
      4 most_common_aisle      (categorical)
    """

    def __init__(self, num_aisles, num_departments, embedding_dim=128, **kwargs):
        super().__init__(**kwargs)

        # Every weighted layer is named. Keras's auto-names (dense, dense_1, ...)
        # are positional and do not survive a reload into a freshly built model -
        # the weights bind to nothing and load_model reports "expected 2 variables,
        # received 0". Naming them makes the checkpoint self-describing.
        self.aisle_embedding = layers.Embedding(num_aisles + 1, 8, name="aisle_embedding")
        self.dept_embedding = layers.Embedding(num_departments + 1, 4, name="dept_embedding")

        self.dense1 = layers.Dense(256, activation="relu", name="dense1")
        self.dropout1 = layers.Dropout(0.2)
        self.dense2 = layers.Dense(128, activation="relu", name="dense2")
        self.dropout2 = layers.Dropout(0.2)
        self.embedding = layers.Dense(embedding_dim, name="embedding")

    def call(self, features, training=False):
        aisle_id = tf.cast(features[..., 4], tf.int32)
        dept_id = tf.cast(features[..., 5], tf.int32)

        continuous = tf.concat(
            [features[..., 0:4], features[..., 6:]],
            axis=-1
        )

        x = tf.concat(
            [
                self.aisle_embedding(aisle_id),
                self.dept_embedding(dept_id),
                continuous
            ],
            axis=-1
        )

        x = self.dense1(x)
        x = self.dropout1(x, training=training)
        x = self.dense2(x)
        x = self.dropout2(x, training=training)
        x = self.embedding(x)
        return tf.nn.l2_normalize(x, axis=-1)


class ItemTower(keras.layers.Layer):
    """
    Input layout (10 cols):
      0 product_id     (categorical)   5 is_organic
      1 aisle_id       (categorical)   6 avg_cart_pos
      2 department_id  (categorical)   7 product_reorder_rate
      3 total_purchases                8 item_dow_mode
      4 avg_days_since_prior_order     9 item_hour_mode
    """

    def __init__(self, num_products, num_aisles, num_departments, embedding_dim=128, **kwargs):
        super().__init__(**kwargs)

        self.product_embedding = layers.Embedding(num_products + 1, 32, name="product_embedding")
        self.aisle_embedding = layers.Embedding(num_aisles + 1, 8, name="aisle_embedding")
        self.department_embedding = layers.Embedding(num_departments + 1, 4, name="department_embedding")

        self.dense1 = layers.Dense(256, activation="relu", name="dense1")
        self.dropout1 = layers.Dropout(0.2)
        self.dense2 = layers.Dense(128, activation="relu", name="dense2")
        self.dropout2 = layers.Dropout(0.2)
        self.embedding = layers.Dense(embedding_dim, name="embedding")

    def call(self, item_features, training=False):
        product_id = tf.cast(item_features[..., 0], tf.int32)
        aisle_id = tf.cast(item_features[..., 1], tf.int32)
        department_id = tf.cast(item_features[..., 2], tf.int32)

        continuous = tf.cast(item_features[..., 3:], tf.float32)

        x = tf.concat(
            [
                self.product_embedding(product_id),
                self.aisle_embedding(aisle_id),
                self.department_embedding(department_id),
                continuous
            ],
            axis=-1
        )

        x = self.dense1(x)
        x = self.dropout1(x, training=training)
        x = self.dense2(x)
        x = self.dropout2(x, training=training)
        x = self.embedding(x)
        return tf.nn.l2_normalize(x, axis=-1)


class TwoTowerModel(keras.Model):
    """
    call() is unchanged - it still scores a [B, N, 10] candidate set, so evaluation
    and serving behave exactly as before. Only the training step is different.
    """

    def __init__(self, num_products, num_aisles, num_departments,
                 embedding_dim=128, temperature=TEMPERATURE, **kwargs):
        super().__init__(**kwargs)

        self.embedding_dim = embedding_dim
        self.temperature = temperature

        self.user_tower = UserTower(
            num_aisles, num_departments, embedding_dim, name="user_tower"
        )
        self.item_tower = ItemTower(
            num_products, num_aisles, num_departments, embedding_dim, name="item_tower"
        )

    def call(self, inputs, training=False):
        user_features = inputs["user_features"]
        item_features = inputs["item_features"]

        user_embedding = self.user_tower(user_features, training=training)

        batch_size = tf.shape(item_features)[0]
        num_items = tf.shape(item_features)[1]
        num_features = tf.shape(item_features)[2]

        items_flat = tf.reshape(
            item_features, [batch_size * num_items, num_features]
        )
        items_emb_flat = self.item_tower(items_flat, training=training)
        items_emb = tf.reshape(
            items_emb_flat, [batch_size, num_items, self.embedding_dim]
        )

        user_expanded = tf.expand_dims(user_embedding, axis=2)
        logits = tf.matmul(items_emb, user_expanded)

        return tf.squeeze(logits, axis=2)

    def _in_batch(self, user_feat, item_feat, training):
        u = self.user_tower(user_feat, training=training)
        i = self.item_tower(item_feat, training=training)

        # [B, B]: row r is user r scored against every item in the batch. The
        # diagonal is the true pair, every off-diagonal entry is a negative, so the
        # label is simply the row index.
        logits = tf.matmul(u, i, transpose_b=True) / self.temperature

        labels = tf.range(tf.shape(logits)[0])
        loss = tf.reduce_mean(
            tf.nn.sparse_softmax_cross_entropy_with_logits(labels, logits)
        )
        acc = tf.reduce_mean(tf.cast(
            tf.equal(tf.argmax(logits, 1, output_type=tf.int32), labels), tf.float32
        ))
        return loss, acc

    def train_step(self, data):
        user_feat, item_feat = data
        with tf.GradientTape() as tape:
            loss, acc = self._in_batch(user_feat, item_feat, training=True)

        grads = tape.gradient(loss, self.trainable_variables)
        self.optimizer.apply_gradients(zip(grads, self.trainable_variables))
        return {"loss": loss, "in_batch_acc": acc}

    def test_step(self, data):
        user_feat, item_feat = data
        loss, acc = self._in_batch(user_feat, item_feat, training=False)
        return {"loss": loss, "in_batch_acc": acc}


# ============================================================
# LOAD
# ============================================================

X_user = np.load("X_user.npy")
X_item = np.load("X_item.npy")
y_true = np.load("y_true.npy")

print("X_user:", X_user.shape)
print("X_item:", X_item.shape)
print("y_true:", y_true.shape)

# categorical sanity check
print("\nCategorical maxes (must NOT be 1.0):")
print("  product_id:   ", X_item[:, :, 0].max())
print("  aisle_id:     ", X_item[:, :, 1].max())
print("  department_id:", X_item[:, :, 2].max())
print("  user aisle:   ", X_user[:, 4].max())
print("  user dept:    ", X_user[:, 5].max())

# Sized from the data so this script stands alone. If X_item does not contain every
# product in the catalogue, set these explicitly instead - an embedding table
# smaller than the id space clips ids at serve time without raising.
num_products = int(X_item[:, :, 0].max()) + 1
num_aisles = int(X_item[:, :, 1].max()) + 1
num_departments = int(X_item[:, :, 2].max()) + 1
print(f"\nsized for {num_products} products, {num_aisles} aisles, {num_departments} departments")

# The three sampled negatives per row are no longer used - in-batch negatives
# replace them with two orders of magnitude more. Only the positive is kept, which
# y_true indexes out of the four candidates.
X_pos = X_item[np.arange(len(X_item)), y_true]          # [N, 10]
print("positives:", X_pos.shape,
      " distinct products:", len(np.unique(X_pos[:, 0].astype(np.int64))))


# ============================================================
# SHUFFLE THEN SPLIT
# ============================================================

perm = np.random.permutation(len(X_user))
X_user, X_pos = X_user[perm], X_pos[perm]

split = int(0.8 * len(X_user))

X_user_train, X_user_val = X_user[:split], X_user[split:]
X_pos_train, X_pos_val = X_pos[:split], X_pos[split:]


def make_ds(users, items, shuffle):
    ds = tf.data.Dataset.from_tensor_slices((users, items))
    if shuffle:
        ds = ds.shuffle(200_000, reshuffle_each_iteration=True)
    # drop_remainder: a short final batch carries fewer negatives and a different
    # loss scale, which surfaces as unexplained noise in the epoch metrics.
    return ds.batch(BATCH_SIZE, drop_remainder=True).prefetch(tf.data.AUTOTUNE)


train_ds = make_ds(X_user_train, X_pos_train, shuffle=True)
val_ds = make_ds(X_user_val, X_pos_val, shuffle=False)


# ============================================================
# TRAIN
# ============================================================

model = TwoTowerModel(
    num_products=num_products,
    num_aisles=num_aisles,
    num_departments=num_departments,
    embedding_dim=EMBEDDING_DIM,
    temperature=TEMPERATURE,
)

# No loss= or metrics=: the label is the position of the diagonal and only exists
# once a batch is assembled, so train_step computes both itself.
model.compile(optimizer=keras.optimizers.Adam(learning_rate=0.001))

history = model.fit(
    train_ds,
    validation_data=val_ds,
    epochs=EPOCHS,
)

results = model.evaluate(val_ds, return_dict=True)
print("Validation Loss:", results["loss"])
print("Validation in-batch accuracy:", results["in_batch_acc"])

# Save as a .keras FILE, and copy the file rather than a folder. The previous
# checkpoint arrived as an unzipped directory whose HDF5 groups carried literal
# backslashes from a Windows save, and load_model cannot read either quirk.
model.save("two_tower_model.keras")
print("saved two_tower_model.keras")
