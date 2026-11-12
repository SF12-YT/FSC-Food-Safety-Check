package com.fosch.org;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.ClipData;
import android.content.pm.PackageManager;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.database.Cursor;
import android.provider.OpenableColumns;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.ByteArrayOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;

public class MainActivity extends Activity {
    private static final int CAMERA_PERMISSION_REQUEST = 842;
    private static final int SCANNER_REQUEST = 843;
    private static final int PHOTO_CAPTURE_REQUEST = 844;
    private static final int ADMIN_IMAGE_PICK_REQUEST = 845;
    private static final int NO_CAMERA_ACTION = 0;
    private static final int CAMERA_ACTION_SCAN = 1;
    private static final int CAMERA_ACTION_PHOTO = 2;
    private int pendingCameraAction = NO_CAMERA_ACTION;
    private long lastHomeBackPress = 0L;
    private static final String TAG = "FOSCHScanner";
    public static final String EXTRA_WIDGET_SEARCH_QUERY = "com.fosch.org.EXTRA_WIDGET_SEARCH_QUERY";
    private WebView view;
    private Uri cameraPhotoUri;
    private File cameraPhotoFile;
    private ProductCacheDb productCacheDb;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback connectivityCallback;
    private boolean webPageReady = false;
    private boolean tileScanPending = false;
    private boolean widgetSearchPending = false;
    private String pendingWidgetSearchQuery = "";
    private MediaPlayer successfulScanPlayer;
    private boolean successfulScanSoundPrepared = false;
    private boolean successfulScanSoundPending = false;
    private static final int MAX_ADMIN_IMAGE_BYTES = 5 * 1024 * 1024;
    private static final String SUCCESSFUL_SCAN_SOUND_URL = "https://socketmobile-assets-dbhnethkfecvgchq.a03.azurefd.net/sitefinity-assets/docs/default-source/sounds/successful-scan.mp3";

    @Override public void onCreate(Bundle savedInstanceState) {
        // Select the window theme from the saved user preference before Activity creates its window.
        // This avoids the app drawing a light native window while a saved dark WebView is starting.
        String preCreateTheme = getSharedPreferences("fosch-appearance", MODE_PRIVATE).getString("theme_preference", "system");
        boolean preCreateDark = "dark".equals(preCreateTheme) ||
            ("system".equals(preCreateTheme) &&
             ((getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES));
        setTheme(preCreateDark ? R.style.AppThemeDark : R.style.AppTheme);
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            String savedPhotoPath = savedInstanceState.getString("fosch_camera_photo_path", "");
            if (!savedPhotoPath.isEmpty()) cameraPhotoFile = new File(savedPhotoPath);
            String savedPhotoUri = savedInstanceState.getString("fosch_camera_photo_uri", "");
            if (!savedPhotoUri.isEmpty()) cameraPhotoUri = Uri.parse(savedPhotoUri);
        }
        productCacheDb = new ProductCacheDb(this);
        // Paint the native window with the last saved appearance before creating the WebView.
        String savedThemePreference = getSharedPreferences("fosch-appearance", MODE_PRIVATE).getString("theme_preference", "system");
        boolean startupDark = "dark".equals(savedThemePreference) ||
            ("system".equals(savedThemePreference) &&
             ((getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES));
        int startupSurface = startupDark ? Color.BLACK : Color.rgb(237, 245, 249);
        getWindow().setBackgroundDrawable(new ColorDrawable(startupSurface));
        getWindow().setStatusBarColor(startupSurface);
        getWindow().setNavigationBarColor(startupSurface);
        int startupSystemUiFlags = startupDark ? 0 : (android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        getWindow().getDecorView().setSystemUiVisibility(startupSystemUiFlags);
        view = new WebView(this);
        view.setBackgroundColor(startupSurface);
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        view.addJavascriptInterface(new FOSCHBridge(), "FOSCHBridge");
        view.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView webView, String url) {
                super.onPageFinished(webView, url);
                webPageReady = true;
                launchPendingTileScanIfReady();
                launchPendingWidgetSearchIfReady();
            }
        });
        // FOSCH deliberately has no HTML file picker: product evidence must be captured with the camera.
        view.setWebChromeClient(new WebChromeClient());
        view.loadUrl("file:///android_asset/index.html");
        setContentView(view);
        prepareSuccessfulScanSound();
        registerConnectivityCallback();
        handleScanTileIntent(getIntent());
        handleWidgetSearchIntent(getIntent());
    }

    private void handleScanTileIntent(Intent intent) {
        if (intent == null || !intent.getBooleanExtra(FOSCHScanTileService.EXTRA_SCAN_FROM_TILE, false)) return;
        intent.removeExtra(FOSCHScanTileService.EXTRA_SCAN_FROM_TILE);
        tileScanPending = true;
        launchPendingTileScanIfReady();
    }

    private void launchPendingTileScanIfReady() {
        if (!tileScanPending || !webPageReady || view == null) return;
        tileScanPending = false;
        beginBarcodeScan();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleScanTileIntent(intent);
        handleWidgetSearchIntent(intent);
    }

    private void handleWidgetSearchIntent(Intent intent) {
        if (intent == null || !intent.hasExtra(EXTRA_WIDGET_SEARCH_QUERY)) return;
        pendingWidgetSearchQuery = clean(intent.getStringExtra(EXTRA_WIDGET_SEARCH_QUERY));
        intent.removeExtra(EXTRA_WIDGET_SEARCH_QUERY);
        widgetSearchPending = true;
        launchPendingWidgetSearchIfReady();
    }

    private void launchPendingWidgetSearchIfReady() {
        if (!widgetSearchPending || !webPageReady || view == null) return;
        widgetSearchPending = false;
        String query = pendingWidgetSearchQuery;
        pendingWidgetSearchQuery = "";
        view.evaluateJavascript(
                "window.onFOSCHWidgetSearch && window.onFOSCHWidgetSearch(" + JSONObject.quote(query) + ")",
                null
        );
    }

    @Override protected void onResume() {
        super.onResume();
        pushInternetStatus();
        if (view != null) {
            view.evaluateJavascript("window.onFOSCHAppForeground && window.onFOSCHAppForeground()", null);
        }
    }

    @Override public void onBackPressed() {
        if (view != null) view.evaluateJavascript("window.handleAndroidBack ? window.handleAndroidBack() : false", null);
        else super.onBackPressed();
    }

    @Override public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        int mode = newConfig.uiMode & Configuration.UI_MODE_NIGHT_MASK;
        String theme = mode == Configuration.UI_MODE_NIGHT_YES ? "dark" : "light";
        if (view != null) view.evaluateJavascript("window.onFOSCHSystemThemeChanged && window.onFOSCHSystemThemeChanged('" + theme + "')", null);
    }

    private class FOSCHBridge {
        @JavascriptInterface public String getSystemTheme() {
            int mode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            return mode == Configuration.UI_MODE_NIGHT_YES ? "dark" : "light";
        }
        // Synchronous so the UI always gets an immediate answer instead of waiting on a JS callback.
        @JavascriptInterface public boolean checkInternetConnection() {
            return hasValidatedInternet();
        }
        @JavascriptInterface public void startBarcodeScan() {
            runOnUiThread(() -> beginBarcodeScan());
        }
        @JavascriptInterface public void captureProductPhoto() {
            runOnUiThread(() -> beginProductPhotoCapture());
        }
        @JavascriptInterface public void chooseAdminImage() {
            runOnUiThread(() -> {
                if (view == null) {
                    sendPhotoFailure();
                    return;
                }
                view.evaluateJavascript(
                    "(window.FOSCHAdmin && window.FOSCHAdmin.isAuthenticated && window.FOSCHAdmin.isAuthenticated()) ? true : false",
                    isAdmin -> {
                        if ("true".equals(isAdmin)) launchAdminImagePicker();
                        else sendPhotoFailure();
                    }
                );
            });
        }

        @JavascriptInterface public void supabaseRequest(String requestId, String method, String path,
                String headersJson, String body, boolean base64Body) {
            final String cleanId = clean(requestId);
            final String cleanMethod = clean(method).toUpperCase(Locale.ROOT);
            final String cleanPath = clean(path);
            if (cleanId.isEmpty() ||
                !(cleanMethod.equals("GET") || cleanMethod.equals("POST") || cleanMethod.equals("DELETE")) ||
                !(cleanPath.startsWith("/auth/v1/") || cleanPath.startsWith("/rest/v1/") ||
                  cleanPath.startsWith("/storage/v1/") || cleanPath.startsWith("/functions/v1/")) ||
                cleanPath.contains("..") || cleanPath.contains("#")) {
                JSONObject failure = new JSONObject();
                try { failure.put("message", "Invalid Supabase request"); } catch (Exception ignored) { }
                deliverSupabaseResponse(cleanId, 0, failure.toString());
                return;
            }
            new Thread(() -> {
                HttpURLConnection connection = null;
                try {
                    URL requestUrl = new URL("https://mqgvagftvuybietazbdc.supabase.co" + cleanPath);
                    connection = (HttpURLConnection) requestUrl.openConnection();
                    connection.setRequestMethod(cleanMethod);
                    connection.setConnectTimeout(12000);
                    connection.setReadTimeout(cleanPath.startsWith("/functions/v1/") ? 60000 : 25000);
                    connection.setUseCaches(false);
                    connection.setInstanceFollowRedirects(false);
                    connection.setRequestProperty("User-Agent", "FOSCH/0.10.57 Android");
                    connection.setRequestProperty("Accept", "application/json");
                    JSONObject headers = new JSONObject(headersJson == null ? "{}" : headersJson);
                    java.util.Iterator<String> keys = headers.keys();
                    while (keys.hasNext()) {
                        String name = keys.next();
                        String value = headers.optString(name, "");
                        if (value.isEmpty() || name.indexOf(13) >= 0 || name.indexOf(10) >= 0 ||
                            value.indexOf(13) >= 0 || value.indexOf(10) >= 0) continue;
                        if (name.equalsIgnoreCase("apikey") || name.equalsIgnoreCase("Authorization") ||
                            name.equalsIgnoreCase("Content-Type") || name.equalsIgnoreCase("Prefer") ||
                            name.equalsIgnoreCase("x-upsert") || name.equalsIgnoreCase("Accept")) {
                            connection.setRequestProperty(name, value);
                        }
                    }
                    if (body != null && !body.isEmpty() && !cleanMethod.equals("GET")) {
                        connection.setDoOutput(true);
                        byte[] payload = base64Body
                            ? Base64.decode(body, Base64.DEFAULT)
                            : body.getBytes(StandardCharsets.UTF_8);
                        try (java.io.OutputStream output = connection.getOutputStream()) {
                            output.write(payload);
                            output.flush();
                        }
                    }
                    int status = connection.getResponseCode();
                    InputStream responseStream = status >= 200 && status < 400
                        ? connection.getInputStream() : connection.getErrorStream();
                    StringBuilder response = new StringBuilder();
                    if (responseStream != null) {
                        try (BufferedReader reader = new BufferedReader(new InputStreamReader(responseStream, StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = reader.readLine()) != null) response.append(line);
                        }
                    }
                    deliverSupabaseResponse(cleanId, status, response.toString());
                } catch (Exception error) {
                    Log.w(TAG, "Supabase request failed", error);
                    JSONObject failure = new JSONObject();
                    try { failure.put("message", error.getMessage() == null ? "Network request failed" : error.getMessage()); }
                    catch (Exception ignored) { }
                    deliverSupabaseResponse(cleanId, 0, failure.toString());
                } finally {
                    if (connection != null) connection.disconnect();
                }
            }, "FOSCH-Supabase").start();
        }

        @JavascriptInterface public void handleHomeBackPress() {
            runOnUiThread(() -> {
                long now = android.os.SystemClock.elapsedRealtime();
                if (lastHomeBackPress > 0L && now - lastHomeBackPress < 2000L) {
                    finish();
                } else {
                    lastHomeBackPress = now;
                    Toast.makeText(MainActivity.this, "Press back button twice to exit", Toast.LENGTH_SHORT).show();
                }
            });
        }
        @JavascriptInterface public String getThemePreference() {
            return getSharedPreferences("fosch-appearance", MODE_PRIVATE).getString("theme_preference", "");
        }
        @JavascriptInterface public void setThemePreference(String preference) {
            String p = clean(preference).toLowerCase(Locale.ROOT);
            if (!"light".equals(p) && !"dark".equals(p) && !"system".equals(p)) p = "system";
            getSharedPreferences("fosch-appearance", MODE_PRIVATE).edit().putString("theme_preference", p).apply();
        }
        @JavascriptInterface public void setTheme(String themeName) {
            runOnUiThread(() -> {
                boolean dark = "dark".equalsIgnoreCase(clean(themeName));
                int surface = dark ? Color.BLACK : Color.rgb(237, 245, 249);
                getWindow().setStatusBarColor(surface);
                getWindow().setNavigationBarColor(surface);
                int flags = dark ? 0 : (android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
                getWindow().getDecorView().setSystemUiVisibility(flags);
                if (view != null) view.setBackgroundColor(surface);
            });
        }
        @JavascriptInterface public void showToast(String message) {
            String value = clean(message);
            final String toastMessage = value.isEmpty() ? "You have logged out" : value.substring(0, Math.min(100, value.length()));
            runOnUiThread(() -> Toast.makeText(MainActivity.this, toastMessage, Toast.LENGTH_SHORT).show());
        }
        @JavascriptInterface public void openExternal(String url) {
            runOnUiThread(() -> {
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    startActivity(intent);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "No browser is available to open this link.", Toast.LENGTH_SHORT).show();
                }
            });
        }
        @JavascriptInterface public String getCachedProduct(String barcode, String country) {
            JSONObject record = productCacheDb == null ? null : productCacheDb.get(clean(barcode), clean(country));
            return record == null ? "" : record.toString();
        }
        @JavascriptInterface public String getCachedProducts(String country) {
            JSONArray records = productCacheDb == null ? new JSONArray() : productCacheDb.listRecentByCountry(clean(country), 300);
            return records.toString();
        }
        /**
         * Search products by typed name before a barcode is scanned. Results are strictly
         * filtered to the selected market and limited to small autocomplete suggestions.
         */
        @JavascriptInterface public void searchProductsByName(String query, String country, String requestId) {
            final String cleanQuery = clean(query);
            final String cleanCountry = clean(country).toUpperCase(Locale.ROOT);
            final String cleanRequestId = clean(requestId);
            if (cleanQuery.length() < 2 || countryCodeForMarket(cleanCountry).isEmpty()) {
                sendProductNameSearchResult(nameSearchResult(cleanRequestId, cleanQuery, cleanCountry, new JSONArray(), true, ""));
                return;
            }
            if (!hasValidatedInternet()) {
                sendProductNameSearchResult(nameSearchResult(cleanRequestId, cleanQuery, cleanCountry, new JSONArray(), false,
                    "Internet not connected, please try again"));
                return;
            }
            new Thread(() -> {
                HttpURLConnection connection = null;
                try {
                    String countryName = new Locale("", cleanCountry).getDisplayCountry(Locale.ENGLISH);
                    String countryTag = normalizeCountryTag(countryName);
                    String fields = "code,product_name,product_name_en,ingredients_text,ingredients_text_en,countries_tags,brands,quantity";
                    // Open Food Facts' full-text search route is currently the legacy CGI endpoint.
                    // The result is used only for suggestions, never treated as verified product data.
                    String endpoint = "https://world.openfoodfacts.org/cgi/search.pl?search_terms=" +
                        URLEncoder.encode(cleanQuery, StandardCharsets.UTF_8.name()) +
                        "&search_simple=1&action=process&json=1&page_size=12&fields=" + fields +
                        "&countries_tags_en=" + URLEncoder.encode(countryTag, StandardCharsets.UTF_8.name()) + "&lc=en";
                    connection = (HttpURLConnection) new URL(endpoint).openConnection();
                    connection.setRequestMethod("GET");
                    connection.setConnectTimeout(4500);
                    connection.setReadTimeout(5500);
                    connection.setRequestProperty("User-Agent", "FOSCH/0.10.56 (open-source Android application; product-name autocomplete)");
                    connection.setRequestProperty("Accept", "application/json");
                    int statusCode = connection.getResponseCode();
                    InputStream stream = statusCode >= 200 && statusCode < 300 ? connection.getInputStream() : connection.getErrorStream();
                    if (stream == null) throw new IOException("No response from the product database.");
                    StringBuilder body = new StringBuilder();
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) body.append(line);
                    }
                    if (statusCode < 200 || statusCode >= 300) throw new IOException("Product database returned HTTP " + statusCode + ".");
                    JSONObject payload = new JSONObject(body.toString());
                    JSONArray products = payload.optJSONArray("products");
                    JSONArray matches = new JSONArray();
                    if (products != null) {
                        for (int i = 0; i < products.length() && matches.length() < 8; i++) {
                            JSONObject product = products.optJSONObject(i);
                            if (product == null) continue;
                            JSONArray countryTags = product.optJSONArray("countries_tags");
                            if (!marketMatches(cleanCountry, countryTags)) continue;
                            String name = firstNonBlank(product.optString("product_name", ""), product.optString("product_name_en", ""));
                            if (name.isEmpty()) continue;
                            String allBrands = product.optString("brands", "");
                            String brand = allBrands.isEmpty() ? "" : allBrands.split(",")[0].trim();
                            String ingredients = firstNonBlank(product.optString("ingredients_text", ""), product.optString("ingredients_text_en", ""));
                            String size = product.optString("quantity", "").trim();
                            JSONObject item = new JSONObject();
                            item.put("name", name);
                            item.put("brand", brand);
                            item.put("sizeLabel", size);
                            item.put("barcode", product.optString("code", "").trim());
                            item.put("ingredients", ingredients);
                            item.put("country", cleanCountry);
                            item.put("countryTags", countryTags == null ? "" : countryTags.toString());
                            item.put("source", "Open Food Facts (community-contributed; suggestion only)");
                            matches.put(item);
                        }
                    }
                    String message = matches.length() == 0
                        ? "No matching product suggestions were found for this market. You can still contribute a new product."
                        : "";
                    sendProductNameSearchResult(nameSearchResult(cleanRequestId, cleanQuery, cleanCountry, matches, true, message));
                } catch (Exception e) {
                    Log.w(TAG, "Product-name autocomplete failed", e);
                    String message = isNetworkUnavailable(e)
                        ? "Internet not connected, please try again"
                        : "Online product suggestions are unavailable right now. You can still use saved suggestions or contribute manually.";
                    sendProductNameSearchResult(nameSearchResult(cleanRequestId, cleanQuery, cleanCountry, new JSONArray(), false, message));
                } finally {
                    if (connection != null) connection.disconnect();
                }
            }, "FOSCH-product-name-search").start();
        }

        @JavascriptInterface public void lookupProduct(String barcode, String country) {
            final String cleanBarcode = clean(barcode);
            final String cleanCountry = clean(country);
            if (cleanBarcode.isEmpty() || cleanCountry.isEmpty()) {
                sendProductLookupResult(notFoundResult(cleanBarcode, cleanCountry, "A barcode and product market are required."));
                return;
            }
            new Thread(() -> {
                JSONObject cached = productCacheDb == null ? null : productCacheDb.get(cleanBarcode, cleanCountry);
                if (cached != null) {
                    try {
                        cached.put("found", true);
                        cached.put("message", "Loaded from this device's product cache.");
                    } catch (Exception ignored) { }
                    sendProductLookupResult(cached);
                    return;
                }
                if (!hasValidatedInternet()) {
                    sendProductLookupResult(notFoundResult(cleanBarcode, cleanCountry, "Internet not connected, please try again"));
                    return;
                }
                HttpURLConnection connection = null;
                try {
                    String fields = "code,product_name,product_name_en,ingredients_text,ingredients_text_en,countries_tags,brands,quantity,lang";
                    String endpoint = "https://world.openfoodfacts.org/api/v3.6/product/" +
                        URLEncoder.encode(cleanBarcode, StandardCharsets.UTF_8.name()) + ".json?fields=" + fields + "&lc=en";
                    String cc = countryCodeForMarket(cleanCountry);
                    if (!cc.isEmpty()) endpoint += "&cc=" + cc;
                    connection = (HttpURLConnection) new URL(endpoint).openConnection();
                    connection.setRequestMethod("GET");
                    connection.setConnectTimeout(7000);
                    connection.setReadTimeout(9000);
                    connection.setRequestProperty("User-Agent", "FOSCH/0.10.56 (open-source Android application)");
                    connection.setRequestProperty("Accept", "application/json");
                    int statusCode = connection.getResponseCode();
                    InputStream stream = statusCode >= 200 && statusCode < 300 ? connection.getInputStream() : connection.getErrorStream();
                    if (stream == null) throw new IOException("No response from the product database.");
                    StringBuilder body = new StringBuilder();
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) body.append(line);
                    }
                    if (statusCode < 200 || statusCode >= 300) throw new IOException("Product database returned HTTP " + statusCode + ".");
                    JSONObject payload = new JSONObject(body.toString());
                    if (payload.optInt("status", 0) != 1) {
                        sendProductLookupResult(notFoundResult(cleanBarcode, cleanCountry, "Open Food Facts has no record for this barcode."));
                        return;
                    }
                    JSONObject product = payload.optJSONObject("product");
                    if (product == null) {
                        sendProductLookupResult(notFoundResult(cleanBarcode, cleanCountry, "The product database returned no product record."));
                        return;
                    }
                    JSONArray countries = product.optJSONArray("countries_tags");
                    String countryTags = countries == null ? "" : countries.toString();
                    if (!marketMatches(cleanCountry, countries)) {
                        sendProductLookupResult(notFoundResult(cleanBarcode, cleanCountry,
                            "A record exists, but its country tags do not confirm this selected market. FOSCH did not reuse another market's ingredients."));
                        return;
                    }
                    String name = firstNonBlank(product.optString("product_name", ""), product.optString("product_name_en", ""));
                    String ingredients = firstNonBlank(product.optString("ingredients_text", ""), product.optString("ingredients_text_en", ""));
                    String brand = product.optString("brands", "").split(",")[0].trim();
                    String quantity = product.optString("quantity", "").trim();
                    if (name.isEmpty() || ingredients.isEmpty()) {
                        sendProductLookupResult(notFoundResult(cleanBarcode, cleanCountry,
                            "A matching-market record exists, but it does not contain both a product name and ingredient list."));
                        return;
                    }
                    String source = "Open Food Facts (community-contributed; not independently verified by FOSCH)";
                    productCacheDb.put(cleanBarcode, cleanCountry, name, ingredients, source, countryTags, brand, quantity);
                    JSONObject result = new JSONObject();
                    result.put("found", true);
                    result.put("barcode", cleanBarcode);
                    result.put("country", cleanCountry);
                    result.put("name", name);
                    result.put("ingredients", ingredients);
                    result.put("brand", brand);
                    result.put("size", quantity);
                    result.put("source", source);
                    result.put("countryTags", countryTags);
                    result.put("cached", false);
                    result.put("message", "Loaded from Open Food Facts and cached on this device. Confirm the packaging because community data may be incomplete or outdated.");
                    sendProductLookupResult(result);
                } catch (Exception e) {
                    Log.w(TAG, "External product lookup failed", e);
                    String message = isNetworkUnavailable(e)
                        ? "Internet not connected, please try again"
                        : "No matching-market product data could be confirmed online. Please try again or add the product manually.";
                    sendProductLookupResult(notFoundResult(cleanBarcode, cleanCountry, message));
                } finally {
                    if (connection != null) connection.disconnect();
                }
            }, "FOSCH-product-lookup").start();
        }
    }

    private boolean isNetworkUnavailable(Exception error) {
        if (!hasValidatedInternet()) return true;
        Throwable current = error;
        while (current != null) {
            if (current instanceof UnknownHostException || current instanceof SocketException ||
                current instanceof SocketTimeoutException || current instanceof ConnectException ||
                current instanceof NoRouteToHostException) return true;
            String message = String.valueOf(current.getMessage() == null ? "" : current.getMessage());
            if (message.matches("(?is).*(unable to resolve host|unknownhost|name or service not known|failed to connect|network is unreachable|no route to host|connection timed out|timed out).*")) return true;
            current = current.getCause();
        }
        return false;
    }

    private boolean hasValidatedInternet() {
        try {
            ConnectivityManager manager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (manager == null) return false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Network network = manager.getActiveNetwork();
                if (network == null) return false;
                NetworkCapabilities caps = manager.getNetworkCapabilities(network);
                return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
            }
            android.net.NetworkInfo info = manager.getActiveNetworkInfo();
            return info != null && info.isConnected();
        } catch (Exception e) {
            Log.w(TAG, "Unable to determine internet connectivity", e);
            return false;
        }
    }

    private void beginBarcodeScan() {
        pendingCameraAction = CAMERA_ACTION_SCAN;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST);
            return;
        }
        launchBarcodeScanner();
    }

    private void beginProductPhotoCapture() {
        pendingCameraAction = CAMERA_ACTION_PHOTO;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST);
            return;
        }
        launchProductPhotoCapture();
    }

    private void launchBarcodeScanner() {
        try {
            startActivityForResult(new Intent(this, ScannerActivity.class), SCANNER_REQUEST);
        } catch (Exception e) {
            Log.e(TAG, "Unable to start in-app barcode scanner", e);
            Toast.makeText(this, "Scanner could not start. Check camera permission or reinstall this build.", Toast.LENGTH_LONG).show();
        }
    }

    private void launchProductPhotoCapture() {
        try {
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            cameraPhotoFile = File.createTempFile("FOSCH_" + stamp + "_", ".jpg", getCacheDir());
            Intent cameraIntent = new Intent(this, ProductCameraActivity.class);
            cameraIntent.putExtra("PHOTO_PATH", cameraPhotoFile.getAbsolutePath());
            startActivityForResult(cameraIntent, PHOTO_CAPTURE_REQUEST);
        } catch (Exception e) {
            Log.e(TAG, "In-app product photo capture failed", e);
            cleanupCameraPhoto();
            sendPhotoFailure();
            Toast.makeText(this, "Could not open FOSCH's in-app camera.", Toast.LENGTH_LONG).show();
        }
    }

    private void launchAdminImagePicker() {
        try {
            Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            picker.addCategory(Intent.CATEGORY_OPENABLE);
            picker.setType("*/*");
            picker.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {
                "image/jpeg", "image/png", "image/webp", "image/svg+xml",
                "image/gif", "image/avif", "image/heic", "image/heif",
                "image/bmp", "image/tiff"
            });
            picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(picker, ADMIN_IMAGE_PICK_REQUEST);
        } catch (Exception e) {
            Log.e(TAG, "Unable to open the admin image picker", e);
            sendPhotoFailure();
            Toast.makeText(this, "Could not open device image selection.", Toast.LENGTH_LONG).show();
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (pendingCameraAction == CAMERA_ACTION_PHOTO) launchProductPhotoCapture();
                else if (pendingCameraAction == CAMERA_ACTION_SCAN) launchBarcodeScanner();
            } else {
                Toast.makeText(this, "Camera access is needed to scan barcodes or capture product evidence.", Toast.LENGTH_LONG).show();
                sendPhotoFailure();
            }
            pendingCameraAction = NO_CAMERA_ACTION;
        }
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (cameraPhotoFile != null) outState.putString("fosch_camera_photo_path", cameraPhotoFile.getAbsolutePath());
        if (cameraPhotoUri != null) outState.putString("fosch_camera_photo_uri", cameraPhotoUri.toString());
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == SCANNER_REQUEST) {
            if (resultCode == RESULT_OK && data != null) {
                String barcode = clean(data.getStringExtra("SCAN_RESULT"));
                if (!barcode.isEmpty() && view != null) {
                    playSuccessfulScanSound();
                    view.evaluateJavascript("window.onBarcodeScanned && window.onBarcodeScanned(" + JSONObject.quote(barcode) + ")", null);
                }
            }
            return;
        }
        if (requestCode == ADMIN_IMAGE_PICK_REQUEST) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                deliverImportedPhoto(data.getData());
            } else {
                sendPhotoFailure();
            }
            return;
        }
        if (requestCode == PHOTO_CAPTURE_REQUEST) {
            if (resultCode == RESULT_OK && cameraPhotoFile != null && cameraPhotoFile.isFile() && cameraPhotoFile.length() > 0) {
                deliverCapturedPhoto(cameraPhotoFile);
            } else {
                sendPhotoFailure();
            }
            cleanupCameraPhoto();
        }
    }

    private void deliverCapturedPhoto(File photoFile) {
        Bitmap bitmap = null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(photoFile.getAbsolutePath(), bounds);
            int sample = 1;
            int maxDimension = Math.max(bounds.outWidth, bounds.outHeight);
            while (maxDimension / sample > 1200) sample *= 2;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            bitmap = BitmapFactory.decodeFile(photoFile.getAbsolutePath(), options);
            if (bitmap == null) throw new IOException("Camera photo could not be decoded.");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 74, output);
            String dataUrl = "data:image/jpeg;base64," + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP);
            if (view != null) {
                String quoted = JSONObject.quote(dataUrl);
                view.evaluateJavascript("window.onFOSCHProductPhotoCaptured && window.onFOSCHProductPhotoCaptured(" + quoted + ")", null);
            }
        } catch (Exception e) {
            Log.e(TAG, "Unable to process camera photo", e);
            sendPhotoFailure();
            Toast.makeText(this, "The photo could not be processed. Please try again.", Toast.LENGTH_LONG).show();
        } finally {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        }
    }

    private String getImportedImageName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (column >= 0) return cursor.getString(column);
            }
        } catch (Exception ignored) { }
        return "";
    }

    private void deliverImportedPhoto(Uri imageUri) {
        Bitmap bitmap = null;
        try {
            String mimeType = getContentResolver().getType(imageUri);
            String normalizedMime = mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT);
            String fileName = getImportedImageName(imageUri).toLowerCase(Locale.ROOT);
            boolean isSvg = normalizedMime.equals("image/svg+xml") || fileName.endsWith(".svg");
            if (!isSvg && !normalizedMime.isEmpty() && !normalizedMime.startsWith("image/")) {
                throw new IOException("The selected file is not an image.");
            }

            if (isSvg) {
                byte[] svgBytes;
                try (InputStream stream = getContentResolver().openInputStream(imageUri);
                     ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    if (stream == null) throw new IOException("The selected SVG could not be opened.");
                    byte[] buffer = new byte[8192];
                    int read;
                    int total = 0;
                    while ((read = stream.read(buffer)) != -1) {
                        total += read;
                        if (total > MAX_ADMIN_IMAGE_BYTES) throw new IOException("SVG files must be 5 MB or smaller.");
                        output.write(buffer, 0, read);
                    }
                    svgBytes = output.toByteArray();
                }
                String lowerSvg = new String(svgBytes, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
                if (!lowerSvg.contains("<svg") || lowerSvg.contains("<!doctype") || lowerSvg.contains("<!entity") ||
                    lowerSvg.contains("<script") || lowerSvg.contains("<foreignobject") ||
                    lowerSvg.contains("javascript:") || lowerSvg.contains("@import") ||
                    lowerSvg.matches("(?s).*\son[a-z0-9_-]+\s*=.*") ||
                    lowerSvg.matches("(?s).*(?:href|xlink:href)\s*=\s*['\"]\s*(?:https?:|//|javascript:).*") ||
                    lowerSvg.matches("(?s).*url\(\s*['\"]?\s*(?:https?:|//).*")) {
                    throw new IOException("This SVG contains unsupported active or external content. Export it as a clean SVG or PNG and try again.");
                }
                String dataUrl = "data:image/svg+xml;base64," + Base64.encodeToString(svgBytes, Base64.NO_WRAP);
                if (view != null) {
                    String quoted = JSONObject.quote(dataUrl);
                    view.evaluateJavascript("window.onFOSCHProductPhotoCaptured && window.onFOSCHProductPhotoCaptured(" + quoted + ")", null);
                }
                return;
            }

            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream stream = getContentResolver().openInputStream(imageUri)) {
                if (stream == null) throw new IOException("The selected image could not be opened.");
                BitmapFactory.decodeStream(stream, null, bounds);
            }
            int sample = 1;
            int maxDimension = Math.max(bounds.outWidth, bounds.outHeight);
            if (maxDimension <= 0) throw new IOException("The selected image format could not be decoded.");
            while (maxDimension / sample > 1200) sample *= 2;

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            try (InputStream stream = getContentResolver().openInputStream(imageUri)) {
                if (stream == null) throw new IOException("The selected image could not be opened.");
                bitmap = BitmapFactory.decodeStream(stream, null, options);
            }
            if (bitmap == null) throw new IOException("The selected image format is not supported on this device.");

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            String outputMime;
            if (bitmap.hasAlpha()) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
                outputMime = "image/png";
            } else if (normalizedMime.equals("image/webp")) {
                bitmap.compress(Bitmap.CompressFormat.WEBP, 84, output);
                outputMime = "image/webp";
            } else {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 82, output);
                outputMime = "image/jpeg";
            }
            if (output.size() > MAX_ADMIN_IMAGE_BYTES) throw new IOException("The processed image is larger than 5 MB. Choose a smaller image.");
            String dataUrl = "data:" + outputMime + ";base64," + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP);
            if (view != null) {
                String quoted = JSONObject.quote(dataUrl);
                view.evaluateJavascript("window.onFOSCHProductPhotoCaptured && window.onFOSCHProductPhotoCaptured(" + quoted + ")", null);
            }
        } catch (Exception e) {
            Log.e(TAG, "Unable to process administrator-selected image", e);
            sendPhotoFailure();
            Toast.makeText(this, "The selected image could not be processed. Choose another image and try again.", Toast.LENGTH_LONG).show();
        } finally {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        }
    }

    private void sendPhotoFailure() {
        runOnUiThread(() -> {
            if (view != null) view.evaluateJavascript("window.onFOSCHProductPhotoFailed && window.onFOSCHProductPhotoFailed()", null);
        });
    }

    private void cleanupCameraPhoto() {
        try { if (cameraPhotoFile != null && cameraPhotoFile.exists()) cameraPhotoFile.delete(); } catch (Exception ignored) { }
        cameraPhotoFile = null;
        cameraPhotoUri = null;
    }


    private void deliverSupabaseResponse(String requestId, int status, String responseBody) {
        if (view == null) return;
        String idJson = JSONObject.quote(requestId == null ? "" : requestId);
        String bodyJson = JSONObject.quote(responseBody == null ? "" : responseBody);
        runOnUiThread(() -> {
            if (view != null) view.evaluateJavascript(
                "window.onFOSCHSupabaseResponse && window.onFOSCHSupabaseResponse(" +
                idJson + "," + status + "," + bodyJson + ")", null);
        });
    }

    private void registerConnectivityCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return;
        try {
            connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (connectivityManager == null) return;
            connectivityCallback = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) { pushInternetStatus(); }
                @Override public void onLost(Network network) { pushInternetStatus(); }
                @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) { pushInternetStatus(); }
            };
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                connectivityManager.registerDefaultNetworkCallback(connectivityCallback);
            } else {
                NetworkRequest request = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build();
                connectivityManager.registerNetworkCallback(request, connectivityCallback);
            }
        } catch (Exception e) {
            Log.w(TAG, "Unable to monitor connectivity changes", e);
        }
    }

    private void pushInternetStatus() {
        runOnUiThread(() -> {
            if (view != null) view.evaluateJavascript("window.onFOSCHInternetStatus && window.onFOSCHInternetStatus(" + hasValidatedInternet() + ")", null);
        });
    }

    @Override protected void onDestroy() {
        if (connectivityManager != null && connectivityCallback != null) {
            try { connectivityManager.unregisterNetworkCallback(connectivityCallback); } catch (Exception ignored) { }
        }
        cleanupCameraPhoto();
        releaseSuccessfulScanSound();
        if (productCacheDb != null) productCacheDb.close();
        super.onDestroy();
    }

    private void prepareSuccessfulScanSound() {
        if (successfulScanPlayer != null) return;
        try {
            MediaPlayer player = new MediaPlayer();
            successfulScanPlayer = player;
            successfulScanSoundPrepared = false;
            player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build());
            player.setOnPreparedListener(prepared -> {
                if (successfulScanPlayer != prepared) { prepared.release(); return; }
                successfulScanSoundPrepared = true;
                if (successfulScanSoundPending) {
                    successfulScanSoundPending = false;
                    playSuccessfulScanSound();
                }
            });
            player.setOnErrorListener((failed, what, extra) -> {
                Log.w(TAG, "Barcode confirmation sound could not be loaded (what=" + what + ", extra=" + extra + ").");
                if (successfulScanPlayer == failed) {
                    successfulScanPlayer = null;
                    successfulScanSoundPrepared = false;
                }
                failed.release();
                return true;
            });
            player.setDataSource(SUCCESSFUL_SCAN_SOUND_URL);
            player.prepareAsync();
        } catch (Exception error) {
            Log.w(TAG, "Unable to prepare the barcode confirmation sound.", error);
            releaseSuccessfulScanSound();
        }
    }

    private void playSuccessfulScanSound() {
        MediaPlayer player = successfulScanPlayer;
        if (player == null || !successfulScanSoundPrepared) {
            successfulScanSoundPending = true;
            if (player == null) prepareSuccessfulScanSound();
            return;
        }
        try {
            if (player.isPlaying()) player.pause();
            player.seekTo(0);
            player.start();
        } catch (Exception error) {
            Log.w(TAG, "Unable to play the barcode confirmation sound.", error);
            releaseSuccessfulScanSound();
            successfulScanSoundPending = true;
            prepareSuccessfulScanSound();
        }
    }

    private void releaseSuccessfulScanSound() {
        successfulScanSoundPending = false;
        successfulScanSoundPrepared = false;
        if (successfulScanPlayer != null) {
            try { successfulScanPlayer.release(); } catch (Exception ignored) { }
            successfulScanPlayer = null;
        }
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }
    private static String firstNonBlank(String a, String b) { return a != null && !a.trim().isEmpty() ? a.trim() : (b == null ? "" : b.trim()); }

    private JSONObject notFoundResult(String barcode, String country, String message) {
        JSONObject result = new JSONObject();
        try {
            result.put("found", false);
            result.put("barcode", barcode);
            result.put("country", country);
            result.put("message", message);
        } catch (Exception ignored) { }
        return result;
    }

    private JSONObject nameSearchResult(String requestId, String query, String country, JSONArray products, boolean ok, String message) {
        JSONObject result = new JSONObject();
        try {
            result.put("requestId", requestId);
            result.put("query", query);
            result.put("country", country);
            result.put("ok", ok);
            result.put("products", products == null ? new JSONArray() : products);
            result.put("message", message == null ? "" : message);
        } catch (Exception ignored) { }
        return result;
    }

    private void sendProductNameSearchResult(JSONObject result) {
        if (result == null) return;
        String quoted = JSONObject.quote(result.toString());
        runOnUiThread(() -> {
            if (view != null) view.evaluateJavascript("window.onProductNameSearchResults && window.onProductNameSearchResults(JSON.parse(" + quoted + "))", null);
        });
    }

    private void sendProductLookupResult(JSONObject result) {
        if (result == null) return;
        String quoted = JSONObject.quote(result.toString());
        runOnUiThread(() -> {
            if (view != null) view.evaluateJavascript("window.onProductLookupResult && window.onProductLookupResult(JSON.parse(" + quoted + "))", null);
        });
    }

    private String countryCodeForMarket(String market) {
        String code = clean(market).toUpperCase(Locale.ROOT);
        if (code.length() == 2) {
            for (String iso : Locale.getISOCountries()) if (iso.equals(code)) return code.toLowerCase(Locale.ROOT);
        }
        return "";
    }

    private boolean marketMatches(String market, JSONArray countryTags) {
        if (countryTags == null || countryTags.length() == 0) return false;
        if (ProductCacheDb.isInternationalCountryTags(countryTags.toString())) return true;
        String code = clean(market).toUpperCase(Locale.ROOT);
        if (code.length() != 2) return false;
        String countryName = new Locale("", code).getDisplayCountry(Locale.ENGLISH);
        if (countryName == null || countryName.trim().isEmpty()) return false;
        String slug = normalizeCountryTag(countryName);
        Set<String> accepted = new HashSet<>();
        accepted.add("en:" + slug);
        accepted.add("en:" + code.toLowerCase(Locale.ROOT));
        // Common Open Food Facts naming variants.
        if (code.equals("US")) accepted.addAll(Arrays.asList("en:usa", "en:united-states-of-america"));
        if (code.equals("GB")) accepted.addAll(Arrays.asList("en:uk", "en:great-britain"));
        if (code.equals("KR")) accepted.addAll(Arrays.asList("en:korea", "en:republic-of-korea"));
        if (code.equals("KP")) accepted.addAll(Arrays.asList("en:north-korea", "en:democratic-people-s-republic-of-korea"));
        if (code.equals("RU")) accepted.add("en:russia");
        if (code.equals("CZ")) accepted.add("en:czech-republic");
        if (code.equals("VN")) accepted.add("en:vietnam");
        if (code.equals("LA")) accepted.add("en:laos");
        if (code.equals("IR")) accepted.add("en:iran");
        if (code.equals("BO")) accepted.add("en:bolivia");
        if (code.equals("VE")) accepted.add("en:venezuela");
        if (code.equals("PS")) accepted.add("en:palestine");
        for (int i = 0; i < countryTags.length(); i++) {
            String tag = countryTags.optString(i, "").toLowerCase(Locale.ROOT).trim();
            if (accepted.contains(tag)) return true;
        }
        return false;
    }

    private String normalizeCountryTag(String value) {
        String normalized = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
            .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
        normalized = normalized.replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        return normalized;
    }

}
