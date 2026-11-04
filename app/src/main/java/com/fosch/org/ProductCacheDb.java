package com.fosch.org;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Market-isolated SQLite cache manager.
 * Each market is stored in fosch_products_<ISO>.db. A small INT database contains
 * records explicitly tagged as world/international and is consulted only after
 * the selected market has no match. The former combined cache is migrated lazily
 * into the corresponding market file without deleting the original database.
 */
public final class ProductCacheDb {
    private static final String LEGACY_DB_NAME = "fosch_product_cache.db";
    private static final int DB_VERSION = 2;
    private static final String TABLE = "product_market_cache";
    private final Context context;
    private final Map<String, MarketDatabase> databases = new HashMap<>();
    private final Set<String> migratedMarkets = new HashSet<>();
    private LegacyDatabase legacyDatabase;

    public ProductCacheDb(Context context) { this.context = context.getApplicationContext(); }

    private static String normaliseMarket(String market) {
        String value = market == null ? "" : market.trim().toUpperCase(Locale.ROOT);
        if ("INT".equals(value)) return value;
        return value.matches("^[A-Z]{2}$") ? value : "";
    }

    private synchronized MarketDatabase database(String market) {
        String code = normaliseMarket(market);
        if (code.isEmpty()) return null;
        MarketDatabase db = databases.get(code);
        if (db == null) {
            db = new MarketDatabase(context, "fosch_products_" + code + ".db");
            databases.put(code, db);
        }
        return db;
    }

    private synchronized LegacyDatabase legacyDatabase() {
        if (legacyDatabase == null) legacyDatabase = new LegacyDatabase(context);
        return legacyDatabase;
    }

    private void migrateLegacyMarket(String market, MarketDatabase target) {
        if (target == null || "INT".equals(market) || migratedMarkets.contains(market)) return;
        migratedMarkets.add(market);
        File oldFile = context.getDatabasePath(LEGACY_DB_NAME);
        if (!oldFile.exists()) return;
        try (Cursor cursor = legacyDatabase().getReadableDatabase().query(TABLE,
            new String[]{"barcode", "country", "product_name", "ingredients", "source", "country_tags", "brand", "quantity", "updated_at"},
            "country=?", new String[]{market}, null, null, null)) {
            SQLiteDatabase targetDb = target.getWritableDatabase();
            targetDb.beginTransaction();
            try {
                while (cursor.moveToNext()) {
                    ContentValues values = new ContentValues();
                    values.put("barcode", cursor.getString(0));
                    values.put("country", cursor.getString(1));
                    values.put("product_name", cursor.getString(2));
                    values.put("ingredients", cursor.getString(3));
                    values.put("source", cursor.getString(4));
                    values.put("country_tags", cursor.getString(5));
                    values.put("brand", cursor.getString(6));
                    values.put("quantity", cursor.getString(7));
                    values.put("updated_at", cursor.getLong(8));
                    targetDb.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE);
                }
                targetDb.setTransactionSuccessful();
            } finally {
                targetDb.endTransaction();
            }
        } catch (Exception ignored) {
            migratedMarkets.remove(market);
        }
    }

    public synchronized JSONObject get(String barcode, String country) {
        String market = normaliseMarket(country);
        if (market.isEmpty() || barcode == null || barcode.trim().isEmpty()) return null;
        MarketDatabase regional = database(market);
        migrateLegacyMarket(market, regional);
        JSONObject result = regional == null ? null : regional.get(barcode, market);
        if (result != null) {
            try { result.put("catalogMarket", market); result.put("marketFallback", false); } catch (Exception ignored) { }
            return result;
        }
        MarketDatabase international = database("INT");
        JSONObject global = international == null ? null : international.get(barcode, "INT");
        if (global != null) {
            try {
                global.put("country", market);
                global.put("catalogMarket", "INT");
                global.put("marketFallback", true);
                global.put("source", global.optString("source", "International product cache") + " · international fallback");
            } catch (Exception ignored) { }
        }
        return global;
    }

    public synchronized void put(String barcode, String country, String name, String ingredients,
                                  String source, String countryTags) {
        put(barcode, country, name, ingredients, source, countryTags, "", "");
    }

    public synchronized void put(String barcode, String country, String name, String ingredients,
                                  String source, String countryTags, String brand, String quantity) {
        String market = normaliseMarket(country);
        if (market.isEmpty() || barcode == null || barcode.trim().isEmpty()) return;
        if (isInternationalCountryTags(countryTags)) {
            MarketDatabase international = database("INT");
            if (international != null) international.put(barcode, "INT", name, ingredients,
                (source == null ? "" : source) + " · international listing", countryTags, brand, quantity);
            return;
        }
        MarketDatabase regional = database(market);
        migrateLegacyMarket(market, regional);
        if (regional != null) regional.put(barcode, market, name, ingredients, source, countryTags, brand, quantity);
    }

    public static boolean isInternationalCountryTags(String countryTags) {
        String value = countryTags == null ? "" : countryTags.toLowerCase(Locale.ROOT);
        return value.contains("\"en:world\"") || value.contains("\"en:international\"") ||
            value.contains("\"en:global\"");
    }

    /** Regional entries are returned first; explicit international entries follow as fallback suggestions. */
    public synchronized JSONArray listRecentByCountry(String country, int maxRows) {
        JSONArray result = new JSONArray();
        String market = normaliseMarket(country);
        if (market.isEmpty()) return result;
        int limit = Math.max(1, Math.min(maxRows, 500));
        Set<String> seen = new HashSet<>();
        MarketDatabase regional = database(market);
        migrateLegacyMarket(market, regional);
        appendRecent(result, seen, regional == null ? null : regional.listRecentByCountry(market, limit), market, false, limit);
        if (result.length() < limit) {
            MarketDatabase international = database("INT");
            appendRecent(result, seen, international == null ? null : international.listRecentByCountry("INT", limit - result.length()),
                market, true, limit);
        }
        return result;
    }

    private void appendRecent(JSONArray destination, Set<String> seen, JSONArray source,
                              String selectedMarket, boolean international, int limit) {
        if (source == null) return;
        for (int i = 0; i < source.length() && destination.length() < limit; i++) {
            JSONObject item = source.optJSONObject(i);
            if (item == null) continue;
            String barcode = item.optString("barcode", "");
            if (!barcode.isEmpty() && !seen.add(barcode)) continue;
            try {
                item.put("country", selectedMarket);
                item.put("catalogMarket", international ? "INT" : selectedMarket);
                item.put("marketFallback", international);
                if (international) item.put("source", item.optString("source", "International product cache") + " · international fallback");
            } catch (Exception ignored) { }
            destination.put(item);
        }
    }

    public synchronized void close() {
        for (MarketDatabase db : databases.values()) {
            try { db.close(); } catch (Exception ignored) { }
        }
        databases.clear();
        if (legacyDatabase != null) {
            try { legacyDatabase.close(); } catch (Exception ignored) { }
            legacyDatabase = null;
        }
    }

    private static void createSchema(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " (" +
            "barcode TEXT NOT NULL, country TEXT NOT NULL, product_name TEXT NOT NULL, " +
            "ingredients TEXT NOT NULL, source TEXT NOT NULL, country_tags TEXT NOT NULL, " +
            "brand TEXT NOT NULL DEFAULT '', quantity TEXT NOT NULL DEFAULT '', " +
            "updated_at INTEGER NOT NULL, PRIMARY KEY(barcode, country))");
        db.execSQL("CREATE INDEX idx_product_cache_updated ON " + TABLE + "(updated_at)");
        db.execSQL("CREATE INDEX idx_product_cache_market_updated ON " + TABLE + "(country, updated_at)");
    }

    private static class MarketDatabase extends SQLiteOpenHelper {
        MarketDatabase(Context context, String fileName) { super(context, fileName, null, DB_VERSION); }
        @Override public void onCreate(SQLiteDatabase db) { createSchema(db); }
        @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            if (oldVersion < 2) {
                db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN brand TEXT NOT NULL DEFAULT ''");
                db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN quantity TEXT NOT NULL DEFAULT ''");
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_product_cache_market_updated ON " + TABLE + "(country, updated_at)");
            }
        }

        JSONObject get(String barcode, String country) {
            try (SQLiteDatabase db = getReadableDatabase(); Cursor c = db.query(TABLE,
                new String[]{"barcode", "country", "product_name", "ingredients", "source", "country_tags", "updated_at", "brand", "quantity"},
                "barcode=? AND country=?", new String[]{barcode, country}, null, null, null, "1")) {
                if (!c.moveToFirst()) return null;
                JSONObject result = new JSONObject();
                result.put("barcode", c.getString(0));
                result.put("country", c.getString(1));
                result.put("name", c.getString(2));
                result.put("ingredients", c.getString(3));
                result.put("source", c.getString(4));
                result.put("countryTags", c.getString(5));
                result.put("updatedAt", c.getLong(6));
                result.put("brand", c.getString(7));
                result.put("size", c.getString(8));
                result.put("cached", true);
                return result;
            } catch (Exception e) { return null; }
        }

        void put(String barcode, String country, String name, String ingredients, String source,
                 String countryTags, String brand, String quantity) {
            if (name == null || ingredients == null) return;
            ContentValues values = new ContentValues();
            values.put("barcode", barcode);
            values.put("country", country);
            values.put("product_name", name);
            values.put("ingredients", ingredients);
            values.put("source", source == null ? "" : source);
            values.put("country_tags", countryTags == null ? "" : countryTags);
            values.put("brand", brand == null ? "" : brand);
            values.put("quantity", quantity == null ? "" : quantity);
            values.put("updated_at", System.currentTimeMillis());
            getWritableDatabase().insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE);
        }

        JSONArray listRecentByCountry(String country, int maxRows) {
            JSONArray result = new JSONArray();
            int limit = Math.max(1, Math.min(maxRows, 500));
            try (SQLiteDatabase db = getReadableDatabase(); Cursor c = db.query(TABLE,
                new String[]{"barcode", "country", "product_name", "ingredients", "source", "country_tags", "updated_at", "brand", "quantity"},
                "country=?", new String[]{country}, null, null, "updated_at DESC", String.valueOf(limit))) {
                while (c.moveToNext()) {
                    JSONObject item = new JSONObject();
                    item.put("barcode", c.getString(0));
                    item.put("country", c.getString(1));
                    item.put("name", c.getString(2));
                    item.put("ingredients", c.getString(3));
                    item.put("source", c.getString(4));
                    item.put("countryTags", c.getString(5));
                    item.put("updatedAt", c.getLong(6));
                    item.put("brand", c.getString(7));
                    item.put("size", c.getString(8));
                    result.put(item);
                }
            } catch (Exception ignored) { }
            return result;
        }
    }

    /** Compatibility source for the former combined cache; it is not deleted after migration. */
    private static class LegacyDatabase extends SQLiteOpenHelper {
        LegacyDatabase(Context context) { super(context, LEGACY_DB_NAME, null, DB_VERSION); }
        @Override public void onCreate(SQLiteDatabase db) { createSchema(db); }
        @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            if (oldVersion < 2) {
                db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN brand TEXT NOT NULL DEFAULT ''");
                db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN quantity TEXT NOT NULL DEFAULT ''");
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_product_cache_market_updated ON " + TABLE + "(country, updated_at)");
            }
        }
    }
}
