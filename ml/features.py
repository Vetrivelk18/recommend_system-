"""
Assembly of the 9-column user_features tensor, in one place, for both serving paths.

The two paths differ only in where the numbers come from:

  existing user  user_features row, SELECTed in USER_COLS order and fed straight
                 through - that table is the model-ready one training consumed, so
                 its numerics are already normalised and its ids are already raw.

  signup         five slots from the onboarding survey, four from the medians in
                 normal_stats.json. Those medians were computed on the normalised
                 column, so they go in as-is; normal_stats' min/max block is
                 provenance for building a user_features row from raw order history
                 and is deliberately unused here.

Slots 4 and 5 are raw ids - UserTower casts them to int32 for the embedding
lookups while the other seven stay scaled floats in the same float32 tensor.
Scaling them collapses every lookup toward row 0, which still returns products.
"""

import json
from pathlib import Path

import numpy as np

USER_COLS = [
    "distinct_products",
    "reorder_tendency",
    "dow_mode",
    "hour_mode",
    "most_common_aisle",
    "most_common_department",
    "fraction_organic",
    "avg_days_between",
    "avg_basket_size",
]

_STATS_PATH = Path(__file__).with_name("normal_stats.json")

# Instacart order_dow: 0 = Sunday. Mirrors DayPreference.
DOW = {
    "Sunday": 0, "Monday": 1, "Tuesday": 2, "Wednesday": 3,
    "Thursday": 4, "Friday": 5, "Saturday": 6,
}

# Four buckets, not 24 hours. Mirrors TimeOfDay.
HOUR = {"Morning": 9, "Afternoon": 14, "Evening": 19, "Night": 22}

# Quintile medians of user_features.fraction_organic. Mirrors OrganicPreference.
# Already 0-1, so it is not scaled - no entry for it in normal_stats.
ORGANIC = {
    "verylow": 0.0139, "low": 0.1190, "medium": 0.2687,
    "high": 0.3913, "veryhigh": 0.5408,
}

# Denominators hardcoded to match training, as the Java enums do, so the two
# serving paths cannot drift apart.
DOW_SCALE = 6.0
HOUR_SCALE = 23.0


def _medians(stats):
    """
    normal_stats names two of these differently from USER_COLS: basket_size is
    avg_basket_size, and avg_days_since_prior_order is avg_days_between. Mapped by
    meaning - the values invert to ~8.9 items and 14.5 days against their own ranges.
    """
    return {
        "distinct_products": stats["distinct_products_median"],
        "reorder_tendency": stats["reorder_tendency_median"],
        "avg_days_between": stats["avg_days_since_prior_order_median"],
        "avg_basket_size": stats["basket_size_median"],
    }


def load_medians(path=_STATS_PATH):
    with open(path) as f:
        return _medians(json.load(f))


class UnknownPreferenceError(ValueError):
    """Raised for a survey label with no mapping, matching the Java write path."""


def _lookup(table, key, field):
    if key not in table:
        raise UnknownPreferenceError(f"unknown {field}: {key!r}")
    return table[key]


def signup_vector(preferences, aisle_id, department_id, medians=None):
    """
    Build the tensor from the onboarding survey. `preferences` is the users.preferences
    JSON as stored - keys DEPARTMENT, AISLE, DOW, HOUR, ORGANIC. The two ids are
    resolved against the aisle/department tables by the caller, exactly as
    PreferenceResolver does on the write path.
    """
    if medians is None:
        medians = load_medians()

    slots = {
        "distinct_products": medians["distinct_products"],
        "reorder_tendency": medians["reorder_tendency"],
        "dow_mode": _lookup(DOW, preferences["DOW"], "day") / DOW_SCALE,
        "hour_mode": _lookup(HOUR, preferences["HOUR"], "time of day") / HOUR_SCALE,
        "most_common_aisle": float(aisle_id),
        "most_common_department": float(department_id),
        "fraction_organic": _lookup(ORGANIC, preferences["ORGANIC"], "organic preference"),
        "avg_days_between": medians["avg_days_between"],
        "avg_basket_size": medians["avg_basket_size"],
    }
    return as_tensor([slots[c] for c in USER_COLS])


def existing_vector(row):
    """
    Pass a user_features row through unchanged. `row` is the 9 values already in
    USER_COLS order - normalising them again here would halve every numeric.
    """
    if len(row) != len(USER_COLS):
        raise ValueError(f"expected {len(USER_COLS)} columns, got {len(row)}")
    return as_tensor(row)


def as_tensor(values):
    """
    float32 explicitly. Ids and scaled numerics share one tensor, so an inferred
    integer dtype would truncate fraction_organic to 0 and still return products.
    """
    return np.asarray(values, dtype=np.float32).reshape(1, len(USER_COLS))
