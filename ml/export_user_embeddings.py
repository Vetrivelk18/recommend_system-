"""
Precomputes every user's 128-d user-tower vector into the user_embeddings table.

    export SUPABASE_DB_PASSWORD=...
    python3 export_user_embeddings.py --limit 500 --dry-run
    python3 export_user_embeddings.py

Exists because the Spring app cannot run the model - the user tower needs TensorFlow,
so Java has no way to turn a user_features row into the 128-d vector that
products.behavioral_emb is searched with. Materialising the vectors turns that forward
pass into a plain SELECT on the Java side.

Deliberately bulk, not row-by-row. recommend.build_vector issues one SELECT per user,
which is right for serving one request and catastrophic here: 206k round trips to a
database in ap-northeast-1 is hours of latency alone. This reads the whole table once,
runs the tower on batched tensors, and writes with execute_values.

Both serving paths are covered, matching recommend.build_vector's branch:

    history  a user_features row exists -> its 9 columns, fed straight through
    cold     onboarding preferences     -> features.signup_vector

so users who have only completed onboarding get a vector too. A user with neither is
skipped and counted - that case is real, not an error.

Staleness is fine by construction: the consumer is a batch job that fires ~15 minutes
after a session ends, and a user's feature row does not move within a session. Rerun
whenever user_features changes.
"""

import argparse
import sys

import numpy as np

import features
import recommend
from recommend import connect

DDL = """
CREATE TABLE IF NOT EXISTS user_embeddings (
    user_id    integer PRIMARY KEY,
    emb        vector(128) NOT NULL,
    path       text        NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now()
)
"""

INSERT = """
INSERT INTO user_embeddings (user_id, emb, path)
VALUES %s
ON CONFLICT (user_id) DO UPDATE
SET emb = EXCLUDED.emb, path = EXCLUDED.path, updated_at = now()
"""


def load_history(cur, limit):
    """Every user_features row at once, in USER_COLS order - one query, not 206k."""
    cur.execute(
        f"SELECT user_id, {', '.join(features.USER_COLS)} FROM user_features ORDER BY user_id"
        + (" LIMIT %s" if limit else ""),
        (limit,) if limit else (),
    )
    rows = np.array(cur.fetchall(), dtype=np.float32)
    if rows.size == 0:
        return np.empty(0, dtype=np.int64), np.empty((0, len(features.USER_COLS)), dtype=np.float32)
    return rows[:, 0].astype(np.int64), rows[:, 1:]


def load_cold(cur, known):
    """
    Users with onboarding preferences but no user_features row. Handled separately
    because signup_vector fills four slots from normal_stats medians and resolves the
    aisle and department names to ids - none of which is a column read.
    """
    cur.execute("SELECT user_id FROM users WHERE preferences IS NOT NULL ORDER BY user_id")
    ids, vectors = [], []
    for (user_id,) in cur.fetchall():
        if user_id in known:
            continue
        preferences = recommend.fetch_preferences(cur, user_id)
        aisle_id, department_id = recommend.resolve_ids(cur, preferences)
        ids.append(user_id)
        vectors.append(features.signup_vector(preferences, aisle_id, department_id)[0])
    return np.array(ids, dtype=np.int64), np.array(vectors, dtype=np.float32) if vectors else np.empty((0, 9), np.float32)


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[1])
    parser.add_argument("--model", default=recommend.DEFAULT_MODEL)
    parser.add_argument("--model-module", default="model")
    parser.add_argument("--batch-size", type=int, default=4096, help="rows per INSERT")
    parser.add_argument("--limit", type=int, default=None)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    from psycopg2.extras import execute_values

    tower = recommend.load_user_tower(args.model, args.model_module)

    with connect() as conn:
        with conn.cursor() as cur:
            cur.execute(DDL)
        conn.commit()

        with conn.cursor() as cur:
            hist_ids, hist_feats = load_history(cur, args.limit)
            cold_ids, cold_feats = load_cold(cur, set(hist_ids.tolist()))

    print(f"{len(hist_ids):,} history users, {len(cold_ids)} cold users", flush=True)

    user_ids = np.concatenate([hist_ids, cold_ids]) if len(cold_ids) else hist_ids
    feats = np.concatenate([hist_feats, cold_feats]) if len(cold_ids) else hist_feats
    paths = ["history"] * len(hist_ids) + ["cold"] * len(cold_ids)

    with connect() as conn:
        written = 0
        for start in range(0, len(user_ids), args.batch_size):
            end = start + args.batch_size
            # training=False keeps the two Dropout layers inert. Left on, the same user
            # gets a different vector every run - unstable rankings with nothing
            # pointing at dropout as the cause.
            emb = tower(feats[start:end], training=False).numpy()

            payload = [
                (int(uid), "[" + ",".join(repr(float(v)) for v in vec) + "]", path)
                for uid, vec, path in zip(user_ids[start:end], emb, paths[start:end])
            ]

            with conn.cursor() as cur:
                if not args.dry_run:
                    execute_values(cur, INSERT, payload, template="(%s, %s::vector, %s)")
            conn.rollback() if args.dry_run else conn.commit()

            written += len(payload)
            print(f"  {written:>7,} / {len(user_ids):,}", flush=True)

    print(f"\n{written:,} embedded{' (rolled back)' if args.dry_run else ''}")


if __name__ == "__main__":
    main()
