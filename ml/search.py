"""
Product search: semantic and lexical arms fused with Reciprocal Rank Fusion.

    export SUPABASE_DB_PASSWORD=...
    python3 search.py "organic avocado"
    python3 search.py "cheerios" --explain

Two arms, deliberately different in what they are good at:

    query ─┬─ bge-small query_embed ─▶ text_emb <=> vec   ─┐
           │                                               ├─ RRF ─▶ top n
           └─ plainto_tsquery ───────▶ name_tsv @@ query  ─┘

The semantic arm finds "guacamole" for "avocado"; the lexical arm nails exact
brand names the embedding blurs together. RRF fuses them by RANK rather than
score, which is the whole point - ts_rank and cosine distance are on
incomparable scales and normalising them against each other is guesswork.

No vector index by design: exact KNN over 49,688 rows costs ~300ms and buys
perfect recall plus clean interaction with the in_stock filter. See the search
flow notes before adding one.
"""

import argparse
import sys

from recommend import connect

MODEL_NAME = "BAAI/bge-small-en-v1.5"
RRF_K = 60


def embed_query(model, text):
    """
    query_embed and embed return byte-identical vectors for bge-small-en-v1.5 in
    this fastembed version - it does not apply BGE's query instruction prefix.
    Verified 2026-09-01, max|diff| = 0. query_embed is kept only so the intent is
    readable if a future version starts applying it.

    Queries therefore carry no prefix, matching the documents, which is bge-v1.5's
    supported no-instruction mode. Prepending "Represent this sentence for searching
    relevant passages: " is a valid alternative worth about a point of retrieval
    quality - but only on the query side, never on documents.

    Not re-normalised: fastembed already returns unit vectors and <=> is cosine,
    which is scale-invariant anyway. Add it back only if this switches to <#>.
    """
    values = next(iter(model.query_embed([text])))
    return "[" + ",".join(repr(float(v)) for v in values) + "]"


SQL = """
-- row_number() is evaluated BEFORE ORDER BY and LIMIT, so numbering in the same
-- SELECT that sorts yields scan-order positions rather than ranks, and the sort
-- then keeps those arbitrary numbers. Both arms order and limit in a subquery and
-- number the result instead. Getting this wrong is silent: RRF still returns rows,
-- it just weights one arm into irrelevance.
WITH sem AS (
    SELECT product_id, row_number() OVER () AS rank
    FROM (
        SELECT product_id
        FROM products
        WHERE text_emb IS NOT NULL AND in_stock
        ORDER BY text_emb <=> %(vec)s::vector
        LIMIT %(candidates)s
    ) ordered
),
lex AS (
    SELECT product_id, row_number() OVER () AS rank
    FROM (
        SELECT product_id
        FROM products, plainto_tsquery('english', %(q)s) AS tsq
        WHERE name_tsv @@ tsq AND in_stock
        ORDER BY ts_rank_cd(name_tsv, tsq) DESC
        LIMIT %(candidates)s
    ) ordered
)
SELECT p.product_id, p.product,
       sem.rank AS sem_rank,
       lex.rank AS lex_rank,
       COALESCE(1.0 / (%(k)s + sem.rank), 0)
     + COALESCE(1.0 / (%(k)s + lex.rank), 0) AS rrf
FROM products p
LEFT JOIN sem ON sem.product_id = p.product_id
LEFT JOIN lex ON lex.product_id = p.product_id
WHERE sem.rank IS NOT NULL OR lex.rank IS NOT NULL
ORDER BY rrf DESC, p.product_id
LIMIT %(limit)s
"""


def search(cur, model, text, limit, candidates):
    # in_stock is filtered inside each arm rather than after the fusion. Filtering
    # afterwards fuses ranks over rows that are then dropped, and quietly returns
    # fewer than `limit` results.
    cur.execute(SQL, {
        "vec": embed_query(model, text),
        "q": text,
        "candidates": candidates,
        "k": RRF_K,
        "limit": limit,
    })
    return cur.fetchall()


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[1])
    parser.add_argument("query")
    parser.add_argument("--limit", type=int, default=10)
    parser.add_argument(
        "--candidates", type=int, default=50,
        help="rows pulled per arm before fusing; fusing top-10s wastes most of RRF",
    )
    parser.add_argument("--explain", action="store_true", help="show each arm's rank")
    args = parser.parse_args()

    from fastembed import TextEmbedding

    model = TextEmbedding(model_name=MODEL_NAME)

    with connect() as conn:
        with conn.cursor() as cur:
            rows = search(cur, model, args.query, args.limit, args.candidates)

    print(f"\n{args.query!r}\n")
    if args.explain:
        print(f"  {'sem':>4} {'lex':>4}  {'rrf':>8}  product")
        for pid, name, sem, lex, rrf in rows:
            s = str(sem) if sem else "-"
            l = str(lex) if lex else "-"
            print(f"  {s:>4} {l:>4}  {rrf:8.5f}  {name}")
    else:
        for pid, name, _, _, _ in rows:
            print(f"  {pid:>6}  {name}")


if __name__ == "__main__":
    main()
