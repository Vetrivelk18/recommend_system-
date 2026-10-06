"""
Class definitions the saved archive needs in order to deserialise.

model_config.json names TwoTowerModel with registered_name "TwoTowerModel", so
keras.models.load_model reconstructs it by calling
TwoTowerModel.from_config({num_products, num_aisles, num_departments, embedding_dim}),
which builds both towers itself. That means all three classes have to be importable
before the load, and their weights are then restored onto the layers those
constructors created.

UserTower below is the serving-critical one and is reproduced verbatim from
training. ItemTower and TwoTowerModel are NOT here - paste them in beside it, or
point recommend.py at your training module with --model-module. Nothing is stubbed
deliberately: a stand-in with a different layer layout would load the wrong weights
onto the wrong layers and still produce a 128-d vector.
"""

import tensorflow as tf
from tensorflow import keras
from tensorflow.keras import layers


@keras.saving.register_keras_serializable()
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

        self.num_aisles = num_aisles
        self.num_departments = num_departments
        self.embedding_dim = embedding_dim

        # Every weighted layer is named explicitly. The checkpoint stores weights
        # under these names, and Keras's auto-generated names (dense, dense_1, ...)
        # bind to nothing - load_weights then raises rather than loading silently.
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

    def get_config(self):
        config = super().get_config()
        config.update({
            "num_aisles": self.num_aisles,
            "num_departments": self.num_departments,
            "embedding_dim": self.embedding_dim,
        })
        return config

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
        # **kwargs so TwoTowerModel can pass name="item_tower"; the checkpoint nests
        # this tower's weights under that name.
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
    def __init__(self, num_products, num_aisles, num_departments, embedding_dim=128):
        super().__init__()

        self.embedding_dim = embedding_dim

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