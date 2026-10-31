package com.fosch.org;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONObject;

/** Compact local cache. It stores product fields and source metadata, never barcode/product images. */
public class ProductCacheDb extends SQLiteOpenHelper {
    private static final String DB_NAME = "fosch_product_cache.db";
    private static final int DB_VERSION = 2;
    private static final String TABLE = "product_market_cache";

    public ProductCacheDb(Context context) { super(context, DB_NAME, null, DB_VERSION); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " (" +
            "barcode TEXT NOT NULL, country TEXT NOT NULL, product_name TEXT NOT NULL, " +
            "ingredients TEXT NOT NULL, source TEXT NOT NULL, country_tags TEXT NOT NULL, " +
            "brand TEXT NOT NULL DEFAULT '', quantity TEXT NOT NULL DEFAULT '', " +
            "updated_at INTEGER NOT NULL, PRIMARY KEY(barcode, country))");
        db.execSQL("CREATE INDEX idx_product_cache_updated ON " + TABLE + "(updated_at)");
        db.execSQL("CREATE INDEX idx_product_cache_market_updated ON " + TABLE + "(country, updated_at)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN brand TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN quantity TEXT NOT NULL DEFAULT ''");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_product_cache_market_updated ON " + TABLE + "(country, updated_at)");
        }
    }

    public JSONObject get(String barcode, String country) {
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

    public void put(String barcode, String country, String name, String ingredients, String source, String countryTags) {
        put(barcode, country, name, ingredients, source, countryTags, "", "");
    }

    public void put(String barcode, String country, String name, String ingredients, String source, String countryTags, String brand, String quantity) {
        ContentValues values = new ContentValues();
        values.put("barcode", barcode);
        values.put("country", country);
        values.put("product_name", name);
        values.put("ingredients", ingredients);
        values.put("source", source);
        values.put("country_tags", countryTags);
        values.put("brand", brand == null ? "" : brand);
        values.put("quantity", quantity == null ? "" : quantity);
        values.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** Returns a small, country-scoped list used for local autocomplete. This never searches another market. */
    public org.json.JSONArray listRecentByCountry(String country, int maxRows) {
        org.json.JSONArray result = new org.json.JSONArray();
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
