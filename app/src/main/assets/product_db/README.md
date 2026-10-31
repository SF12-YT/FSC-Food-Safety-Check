# Market-specific product data files

FOSCH loads only the file for the currently selected market (`markets/<ISO-3166-1-alpha-2>.js`). Each file exposes a market-scoped `products` array; the initial files are empty because FOSCH does not yet operate a shared product submission/admin database. They are kept separate so a future reviewed dataset can be added per market without scanning a combined cross-country file.

The runtime also keeps local cached products and local product suggestion indexes in country-keyed namespaces. Product records must remain market-scoped; do not fall back to another market when no local record matches. Sizes are stored as the label's original quantity/unit text and are never converted or rounded.
