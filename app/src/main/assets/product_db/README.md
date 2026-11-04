# Market-specific product data files and local SQLite caches

FOSCH loads only the selected market asset (`markets/<ISO-3166-1-alpha-2>.js`) plus the international fallback asset (`markets/INT.js`). Regional products always take priority; the international asset is consulted only after a regional match is absent. The initial curated asset arrays remain empty until a reviewed catalogue is populated.

The Android product cache is stored in separate SQLite files: `fosch_products_<ISO>.db` for each selected market and `fosch_products_INT.db` for entries whose Open Food Facts country tags explicitly include `en:world`, `en:international` or `en:global`. The selected market cache is queried first; only then is the INT cache queried. The former combined `fosch_product_cache.db` is lazily copied into the corresponding regional file and retained as a compatibility backup.

The public product catalogue is still not a shared approved-submission API. Product submissions remain subject to administrator review; this local cache is populated by successful market-matched product lookups. Sizes are stored as the label's original quantity/unit text and are never converted or rounded.
