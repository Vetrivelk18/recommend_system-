"""
Top-N product recommendations for one user, via the two-tower model.

    python3 recommend.py 206210 --model two_tower.keras

Both serving paths end in the same lookup - the item tower never runs here. It ran
offline to populate products.behavioral_emb; at serve time this is one forward pass
through the user tower and one index scan:

    history?  yes -> user_features row ---.
              no  -> users.preferences ---+-> 9 feats -> user tower -> L2 vec
                                                                        |
                          ORDER BY behavioral_emb <=> vec LIMIT n <-----'

Both towers L2-normalise their output, so cosine distance (<=>) is monotonic in the
dot product the model was trained to score with, and ranks identically. If either
side ever stops being unit-norm, this operator silently starts ranking by something
the model was not trained on - switch to <#> rather than leaving it.
"""

import argparse
import importlib
import json
import os
import pathlib
import sys

import features

DEFAULT_HOST = "aws-0-ap-northeast-1.pooler.supabase.com"
DEFAULT_PORT = 5432
DEFAULT_DB = "postgres"
DEFAULT_USER = "postgres.tmsequiejrnxqfswicpl"
DEFAULT_MODEL = os.path.expanduser("~/Downloads/two_tower.weights.h5")


def connect():
    """
    Reuses the app's SUPABASE_DB_PASSWORD rather than introducing a second secret.
    An unset password reaches Postgres as an empty string and comes back as
    "password authentication failed", which reads like a wrong password rather than
    a missing variable - so it is checked here instead.
    """
    try:
        import psycopg2
    except ImportError:
        sys.exit("psycopg2 is not installed: pip install psycopg2-binary")

    password = os.environ.get("SUPABASE_DB_PASSWORD")
    if not password:
        sys.exit("SUPABASE_DB_PASSWORD is not set")

    return psycopg2.connect(
        host=os.environ.get("SUPABASE_DB_HOST", DEFAULT_HOST),
        port=int(os.environ.get("SUPABASE_DB_PORT", DEFAULT_PORT)),
        dbname=os.environ.get("SUPABASE_DB_NAME", DEFAULT_DB),
        user=os.environ.get("SUPABASE_DB_USER", DEFAULT_USER),
        password=password,
    )


def _resolve_layer(model, group):
    """"layers\\item_tower\\dense1" -> model.item_tower.dense1. The "layers" segment is
    an artefact of how the towers were tracked, not an attribute."""
    obj = model
    for part in (x for x in group.split("\\") if x != "layers"):
        obj = getattr(obj, part, None)
        if obj is None:
            return None
    return obj


def _user_tower_from_weights(model_path, module):
    """
    Loader for a bare .weights.h5 - the current training script saves weights only,
    with no config.json beside them, so the constructor arguments are recovered from
    the saved shapes themselves:

        user_tower/aisle_embedding  (num_aisles + 1, 8)
        user_tower/dept_embedding   (num_departments + 1, 4)
        user_tower/embedding        (128, embedding_dim)

    Only the user tower is built. The item tower never runs at serve time - it ran
    offline to populate products.behavioral_emb - so its weights are skipped, which
    also means this keeps working with checkpoints whose item tower differs.

    Group names are flat and contain literal backslashes ("user_tower\\dense1")
    rather than forming a hierarchy, so they are addressed by their literal names.
    """
    import h5py

    with h5py.File(model_path, "r") as handle:
        def var(layer, index=0):
            return handle[f"user_tower\\{layer}"]["vars"][str(index)][()]

        num_aisles = var("aisle_embedding").shape[0] - 1
        num_departments = var("dept_embedding").shape[0] - 1
        embedding_dim = var("embedding").shape[1]

        tower = module.UserTower(
            num_aisles, num_departments, embedding_dim, name="user_tower"
        )
        # Build before assigning: layers created in __init__ have no variables until
        # they are called, and set_weights on an unbuilt layer raises.
        tower(tf_zeros(len(features.USER_COLS)))

        for layer in ("aisle_embedding", "dept_embedding", "dense1", "dense2", "embedding"):
            group = handle[f"user_tower\\{layer}"]["vars"]
            weights = [group[k][()] for k in sorted(group, key=int)]
            current = getattr(tower, layer).get_weights()
            if len(weights) != len(current):
                sys.exit(f"{layer}: checkpoint has {len(weights)} tensors, layer wants {len(current)}")
            for w, c in zip(weights, current):
                if w.shape != c.shape:
                    sys.exit(f"{layer}: checkpoint {w.shape} vs layer {c.shape}")
            getattr(tower, layer).set_weights(weights)

    return tower


def tf_zeros(width):
    """Imported lazily so the module stays importable without tensorflow."""
    import tensorflow as tf
    return tf.zeros([1, width])


def _load_from_weights(model_path, module):
    """
    Fallback for the checkpoint this project actually has, which load_model cannot
    read for two independent reasons: it is an unzipped directory rather than a
    .keras archive, and it was written on Windows, so its HDF5 groups are FLAT names
    containing literal backslashes ("user_tower\\dense1") instead of a nested
    hierarchy. Keras looks up "layers/user_tower/dense1", finds nothing, and reports
    "expected 2 variables, but received 0".

    So the model is rebuilt from config.json and each group is bound by its literal
    name. This depends on the name= arguments in model.py matching exactly.
    """
    import h5py
    import tensorflow as tf

    root = pathlib.Path(model_path)
    config = json.loads((root / "config.json").read_text())
    model = getattr(module, config["class_name"])(**config["config"])

    # Build with the shapes the archive recorded so every layer exists before
    # assignment. Batch dim is forced to 1; only the feature dims matter.
    shapes = config["build_config"]["input_shape"]
    model({k: tf.zeros([1] + v[1:]) for k, v in shapes.items()}, training=False)

    bound = 0
    with h5py.File(root / "model.weights.h5", "r") as handle:
        for group in handle:
            if group.startswith(("optimizer", "metrics")) or "\\" not in group:
                continue
            layer = _resolve_layer(model, group)
            if layer is None:
                continue
            variables = handle[group]["vars"]
            weights = [variables[k][()] for k in sorted(variables, key=int)]
            if not weights:
                continue
            layer.set_weights(weights)
            bound += sum(w.size for w in weights)

    # A silent partial bind is the failure mode worth guarding: the model still
    # returns a plausible 128-d vector with some layers left at their init values.
    if bound != model.count_params():
        sys.exit(f"bound {bound} of {model.count_params()} parameters - layer names disagree")
    return model


def load_user_tower(model_path, module_name):
    """
    The archive stores a subclassed TwoTowerModel, so every class involved has to be
    importable before load_model can rebuild it. Whatever module holds them is
    imported here and its Layer/Model subclasses handed over as custom_objects.

    load_model is tried first so a correctly-saved archive takes the normal path;
    _load_from_weights handles the Windows-written directory we have today.
    """
    from tensorflow import keras

    module = importlib.import_module(module_name)
    custom = {
        name: obj for name, obj in vars(module).items()
        if isinstance(obj, type) and issubclass(obj, (keras.layers.Layer, keras.Model))
    }

    # A bare .weights.h5 has no archive structure for load_model to read at all, so
    # it is dispatched before the attempt rather than through the exception path.
    if os.path.isfile(model_path) and model_path.endswith(".weights.h5"):
        return _user_tower_from_weights(model_path, module)

    try:
        model = keras.models.load_model(model_path, custom_objects=custom, compile=False)
    except Exception:
        model = _load_from_weights(model_path, module)

    for attr in vars(model).values():
        if type(attr).__name__ == "UserTower":
            return attr
    for layer in getattr(model, "layers", []):
        if type(layer).__name__ == "UserTower":
            return layer
    sys.exit(f"no UserTower found on the loaded model (got {type(model).__name__})")


def fetch_user_features(cur, user_id):
    """The model-ready row, SELECTed in USER_COLS order. None when the user is cold."""
    cur.execute(
        f"SELECT {', '.join(features.USER_COLS)} FROM user_features WHERE user_id = %s",
        (user_id,),
    )
    return cur.fetchone()


def fetch_preferences(cur, user_id):
    cur.execute("SELECT preferences FROM users WHERE user_id = %s", (user_id,))
    row = cur.fetchone()
    if row is None:
        sys.exit(f"no such user: {user_id}")
    if row[0] is None:
        sys.exit(f"user {user_id} has neither history nor onboarding preferences")
    return row[0]


def resolve_ids(cur, preferences):
    """
    Resolved independently and not cross-checked, matching PreferenceResolver:
    most_common_aisle and most_common_department are independent modes, so a modal
    aisle outside the modal department is normal rather than a data error.
    """
    cur.execute("SELECT aisle_id FROM aisle WHERE aisle = %s", (preferences["AISLE"],))
    aisle = cur.fetchone()
    if aisle is None:
        sys.exit(f"unknown aisle: {preferences['AISLE']!r}")

    cur.execute(
        "SELECT department_id FROM department WHERE department = %s",
        (preferences["DEPARTMENT"],),
    )
    department = cur.fetchone()
    if department is None:
        sys.exit(f"unknown department: {preferences['DEPARTMENT']!r}")

    return aisle[0], department[0]


def build_vector(cur, user_id, path):
    if path in ("auto", "history"):
        row = fetch_user_features(cur, user_id)
        if row is not None:
            return features.existing_vector(row), "history"
        if path == "history":
            sys.exit(f"user {user_id} has no user_features row")

    preferences = fetch_preferences(cur, user_id)
    aisle_id, department_id = resolve_ids(cur, preferences)
    return features.signup_vector(preferences, aisle_id, department_id), "signup"


def recommend(cur, vector, limit):
    """
    in_stock is filtered here for the same reason the other three rails filter it -
    an out-of-stock recommendation is worse than one fewer recommendation.
    """
    literal = "[" + ",".join(repr(float(v)) for v in vector[0]) + "]"
    cur.execute(
        """
        SELECT product_id, product
        FROM products
        WHERE behavioral_emb IS NOT NULL
          AND in_stock
        ORDER BY behavioral_emb <=> %s::vector
        LIMIT %s
        """,
        (literal, limit),
    )
    return cur.fetchall()


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[1])
    parser.add_argument("user_id", type=int)
    parser.add_argument("--model", default=DEFAULT_MODEL, help="path to the .keras archive")
    parser.add_argument("--model-module", default="model", help="module holding the tower classes")
    parser.add_argument("--limit", type=int, default=5)
    parser.add_argument(
        "--path", choices=["auto", "history", "signup"], default="auto",
        help="auto picks history when a user_features row exists, else signup",
    )
    parser.add_argument("--show-vector", action="store_true")
    args = parser.parse_args()

    with connect() as conn:
        with conn.cursor() as cur:
            vector, path = build_vector(cur, args.user_id, args.path)

            tower = load_user_tower(args.model, args.model_module)
            # training=False keeps the two Dropout(0.2) layers inert. Left on, the
            # same user gets a different vector every call - which surfaces as
            # unstable rankings rather than as anything pointing at dropout.
            embedding = tower(vector, training=False).numpy()

            if args.show_vector:
                for name, value in zip(features.USER_COLS, vector[0]):
                    print(f"  {name:<24} {value}")

            rows = recommend(cur, embedding, args.limit)

    print(f"user {args.user_id} ({path} path)")
    for product_id, product in rows:
        print(f"  {product_id:>6}  {product}")


if __name__ == "__main__":
    main()
