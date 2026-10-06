"""
Populates products.text_emb from the product names in products.csv, locally.

    export SUPABASE_DB_PASSWORD=...
    python3 embed_products.py --limit 20 --dry-run    # rehearsal, writes nothing
    python3 embed_products.py                          # the full ~49.7k

Runs BAAI/bge-small-en-v1.5 through onnxruntime via fastembed. No torch and no
tensorflow in this path, which is what makes it work where the Windows torch build
does not, and no API quota, which is what makes it finish in minutes rather than the
eight-plus hours Gemini's free tier metered it to.

bge-small is natively 384-d, matching the column, so nothing is truncated here.

text_emb is free-standing: the item tower takes 10 numeric features per item and has
no slot for text, so nothing here can perturb behavioral_emb or the trained model.

Resumable by construction - the driving SELECT takes only rows where text_emb IS
NULL and every batch commits on its own, so an interrupted run is continued by
rerunning the same command.
"""

import argparse
import csv
import math
import sys
import time

from recommend import connect

MODEL_NAME = "BAAI/bge-small-en-v1.5"
EXPECTED_DIM = 384
DEFAULT_CSV = "/Users/vetrivelpandian/Downloads/products.csv"


def load_names(path):
    """{product_id: product_name}. The CSV quotes names containing commas, so the
    csv module is doing real work here - splitting on ',' corrupts ~3,200 rows."""
    with open(path, newline="", encoding="utf-8") as handle:
        return {int(row["product_id"]): row["product_name"] for row in csv.DictReader(handle)}


def fetch_pending(cur, limit):
    """
    in_stock is deliberately not filtered. Stock is a serve-time concern and it
    changes; a product that comes back in stock should already have its embedding
    rather than needing a second pass to find it.
    """
    cur.execute(
        "SELECT product_id FROM products WHERE text_emb IS NULL ORDER BY product_id LIMIT %s",
        (limit,),
    )
    return [row[0] for row in cur.fetchall()]


def normalise(values):
    """
    bge-small already returns unit vectors, so this is a no-op in practice. It stays
    because cosine (<=>) hides a non-unit vector while inner product (<#>) would not,
    and the guarantee is cheaper to keep than to re-derive later.
    """
    norm = math.sqrt(sum(v * v for v in values))
    return [v / norm for v in values]


def embed_pairs(model, pairs):
    """(product_id, vector), pairing by position exactly once, under a guard."""
    vectors = list(model.embed([text for _, text in pairs]))
    if len(vectors) != len(pairs):
        sys.exit(f"got {len(vectors)} embeddings for {len(pairs)} inputs - aborting unwritten")
    if len(vectors[0]) != EXPECTED_DIM:
        sys.exit(f"expected {EXPECTED_DIM}-d, got {len(vectors[0])}-d")
    return [(pid, normalise(vec)) for (pid, _), vec in zip(pairs, vectors)]


def write_batch(cur, embedded):
    from psycopg2.extras import execute_values

    execute_values(
        cur,
        """
        UPDATE products AS p
        SET text_emb = v.emb::vector
        FROM (VALUES %s) AS v(product_id, emb)
        WHERE p.product_id = v.product_id::int
        """,
        [(pid, "[" + ",".join(repr(float(v)) for v in vec) + "]") for pid, vec in embedded],
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[1])
    parser.add_argument("--csv", default=DEFAULT_CSV)
    parser.add_argument("--batch-size", type=int, default=512)
    parser.add_argument("--limit", type=int, default=None, help="stop after N products")
    parser.add_argument("--dry-run", action="store_true", help="embed but roll back")
    args = parser.parse_args()

    from fastembed import TextEmbedding

    model = TextEmbedding(model_name=MODEL_NAME)
    names = load_names(args.csv)
    print(f"{len(names)} names from {args.csv}", flush=True)

    started = time.monotonic()
    done = 0
    missing = 0
    with connect() as conn:
        while args.limit is None or done < args.limit:
            size = args.batch_size
            if args.limit is not None:
                size = min(size, args.limit - done)

            with conn.cursor() as cur:
                ids = fetch_pending(cur, size)
                if not ids:
                    break

                pairs = [(pid, names[pid]) for pid in ids if pid in names]
                missing += len(ids) - len(pairs)
                if pairs:
                    write_batch(cur, embed_pairs(model, pairs))

            conn.rollback() if args.dry_run else conn.commit()
            done += len(ids)
            rate = done / max(time.monotonic() - started, 1e-9)
            print(f"  {done:>6} / {len(names)}   {rate:6.0f}/s", flush=True)

            # A rolled-back batch leaves text_emb NULL, so the same ids come back
            # on the next pass - a dry run has to stop itself or it never ends.
            if args.dry_run or len(ids) < size:
                break

    elapsed = time.monotonic() - started
    print(f"\n{done} product(s) {'checked' if args.dry_run else 'embedded'} in {elapsed:.0f}s")
    if missing:
        print(f"{missing} product_id(s) in the table were absent from the CSV - left NULL")


if __name__ == "__main__":
    main()
