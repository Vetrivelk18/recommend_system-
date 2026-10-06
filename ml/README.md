# Two-tower serving

One forward pass through the user tower, one pgvector index scan. The item tower
does not run here - it ran offline to populate `products.behavioral_emb`.

    export SUPABASE_DB_PASSWORD=...
    pip install tensorflow psycopg2-binary numpy
    python3 recommend.py 206210 --model two_tower.keras --show-vector

`--path` forces `history` or `signup` instead of picking by whether the user has a
`user_features` row; `--show-vector` prints the 9 slots before the lookup, which is
the fastest way to see a feature landing somewhere it should not.

## Before the first run

`model.py` carries `UserTower` only. `load_model` rebuilds the saved `TwoTowerModel`
by calling its constructor, so `ItemTower` and `TwoTowerModel` must be importable
too - paste them into `model.py`, or point at your training module with
`--model-module`. They are not stubbed here on purpose: a stand-in with a different
layer layout loads the wrong weights onto the wrong layers and still returns a
128-d vector.

## What is normalised where

`user_features` is the model-ready table training consumed - numerics already
normalised, ids already raw - so the history path is a straight `SELECT` in
`USER_COLS` order.

`normal_stats.json` is therefore only read for its four medians, which were computed
on the normalised columns and go in as-is. Its `min`/`max` block is provenance for
building a `user_features` row from raw order history, and nothing here applies it.
Two of its keys are named differently from `USER_COLS`, mapped by meaning in
`features._medians`: `basket_size` -> `avg_basket_size`, `avg_days_since_prior_order`
-> `avg_days_between`. `total_purchases` is an item-tower stat and is unused.
