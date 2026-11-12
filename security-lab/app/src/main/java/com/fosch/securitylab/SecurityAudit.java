package com.fosch.securitylab;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;

import org.json.JSONArray;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class SecurityAudit {
    public static final String FOSCH_PACKAGE = "com.fosch.org";
    private static final String KNOWN_PRODUCTION_HOST = "mqgvagftvuybietazbdc.supabase.co";
    private static final int MAX_RESPONSE_BYTES = 8192;

    private SecurityAudit() { }

    public static final class Check {
        public final String category;
        public final String title;
        public final String status;
        public final String severity;
        public final String details;

        public Check(String category, String title, String status, String severity, String details) {
            this.category = category;
            this.title = title;
            this.status = status;
            this.severity = severity;
            this.details = details;
        }
    }

    public static final class BackendConfig {
        public final String url;
        public final String publishableKey;

        BackendConfig(String url, String publishableKey) {
            this.url = url;
            this.publishableKey = publishableKey;
        }
    }

    private static void add(List<Check> out, String category, String title,
                            String status, String severity, String details) {
        out.add(new Check(category, title, status, severity, details));
    }

    public static List<Check> inspectInstalled(Context context, File baselineApk) {
        List<Check> out = new ArrayList<>();
        PackageManager pm = context.getPackageManager();
        PackageInfo info;

        try {
            int flags = PackageManager.GET_ACTIVITIES | PackageManager.GET_SERVICES
                    | PackageManager.GET_RECEIVERS | PackageManager.GET_PROVIDERS
                    | PackageManager.GET_PERMISSIONS | signingFlag();
            info = pm.getPackageInfo(FOSCH_PACKAGE, flags);
        } catch (PackageManager.NameNotFoundException e) {
            add(out, "Target", "FOSCH installation", "SKIP", "INFO",
                    "FOSCH (" + FOSCH_PACKAGE + ") is not installed. Install the main app first.");
            return out;
        } catch (Exception e) {
            add(out, "Target", "FOSCH package inspection", "ERROR", "LOW",
                    safeMessage(e));
            return out;
        }

        ApplicationInfo app = info.applicationInfo;
        String version = info.versionName == null ? "unknown" : info.versionName;
        add(out, "Target", "FOSCH detected", "PASS", "INFO",
                "Installed version " + version + " (versionCode " + info.versionCode + ").");

        boolean debuggable = (app.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        add(out, "Modding", "Release build flag",
                debuggable ? "FAIL" : "PASS",
                debuggable ? "HIGH" : "INFO",
                debuggable
                        ? "The installed package is marked debuggable. Verify that this is an intentional development build."
                        : "The installed package is not marked debuggable.");

        boolean cleartext = (app.flags & ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC) != 0;
        add(out, "Transport", "Cleartext network traffic",
                cleartext ? "FAIL" : "PASS",
                cleartext ? "HIGH" : "INFO",
                cleartext
                        ? "Android reports that cleartext traffic is permitted. Review the manifest and network security configuration."
                        : "Android does not report cleartext traffic as enabled for FOSCH.");

        boolean backup = (app.flags & ApplicationInfo.FLAG_ALLOW_BACKUP) != 0;
        add(out, "Local data", "Android backup setting",
                backup ? "WARN" : "PASS",
                backup ? "MEDIUM" : "INFO",
                backup
                        ? "Backup is enabled. Confirm that authentication tokens and other sensitive WebView/app data are excluded by backup rules."
                        : "The package does not allow Android backup.");

        File installedApk = new File(app.sourceDir == null ? "" : app.sourceDir);
        String installedHash = installedApk.isFile() ? sha256(installedApk) : "";
        if (!installedHash.isEmpty()) {
            add(out, "Modding", "Installed APK SHA-256", "INFO", "INFO",
                    installedHash + " (base APK only).");
        } else {
            add(out, "Modding", "Installed APK SHA-256", "WARN", "LOW",
                    "The installed base APK could not be read.");
        }

        String installedSigner = signerFingerprint(info);
        if (!installedSigner.isEmpty()) {
            add(out, "Modding", "Signing certificate", "INFO", "INFO",
                    "SHA-256: " + installedSigner);
        } else {
            add(out, "Modding", "Signing certificate", "WARN", "MEDIUM",
                    "The platform did not return a signing certificate for this package.");
        }

        inspectBaseline(context, info, installedApk, baselineApk, out);
        inspectExportedComponents(info, out);
        inspectDeviceSignals(out);
        inspectApkContents(installedApk, out);
        return out;
    }

    private static void inspectBaseline(Context context, PackageInfo installed,
                                        File installedApk, File baselineApk, List<Check> out) {
        if (baselineApk == null || !baselineApk.isFile()) {
            add(out, "Modding", "Trusted APK comparison", "SKIP", "INFO",
                    "Import a known-good FOSCH APK from an official release to enable signature and binary comparison.");
            return;
        }

        PackageManager pm = context.getPackageManager();
        PackageInfo baseline;
        try {
            baseline = pm.getPackageArchiveInfo(baselineApk.getAbsolutePath(), signingFlag());
        } catch (Exception e) {
            baseline = null;
        }

        if (baseline == null) {
            add(out, "Modding", "Trusted APK baseline", "FAIL", "HIGH",
                    "The selected baseline could not be parsed as a signed Android APK.");
            return;
        }

        if (!FOSCH_PACKAGE.equals(baseline.packageName)) {
            add(out, "Modding", "Trusted APK baseline", "FAIL", "HIGH",
                    "The imported APK package is " + baseline.packageName + ", not " + FOSCH_PACKAGE + ".");
            return;
        }

        String expectedSigner = signerFingerprint(baseline);
        String currentSigner = signerFingerprint(installed);
        if (expectedSigner.isEmpty() || currentSigner.isEmpty()) {
            add(out, "Modding", "Signature comparison", "INCONCLUSIVE", "MEDIUM",
                    "A signing certificate could not be read from both APKs.");
        } else {
            boolean sameSigner = expectedSigner.equals(currentSigner);
            add(out, "Modding", "Signature comparison",
                    sameSigner ? "PASS" : "FAIL",
                    sameSigner ? "INFO" : "CRITICAL",
                    sameSigner
                            ? "The installed FOSCH package and imported baseline have the same signing certificate."
                            : "The installed FOSCH signing certificate does not match the imported known-good APK.");
        }

        String baselineHash = sha256(baselineApk);
        String currentHash = installedApk.isFile() ? sha256(installedApk) : "";
        if (!baselineHash.isEmpty() && !currentHash.isEmpty()) {
            boolean sameBytes = baselineHash.equals(currentHash);
            add(out, "Modding", "Base APK binary comparison",
                    sameBytes ? "PASS" : "WARN",
                    sameBytes ? "INFO" : "LOW",
                    sameBytes
                            ? "The installed base APK is byte-for-byte identical to the imported baseline."
                            : "The APK bytes differ. This can be normal when FOSCH has been updated or the baseline came from a different build; review version and signer before calling it tampering.");
        } else {
            add(out, "Modding", "Base APK binary comparison", "INCONCLUSIVE", "LOW",
                    "The binary hash could not be calculated for both APKs.");
        }
    }

    private static void inspectExportedComponents(PackageInfo info, List<Check> out) {
        List<String> exposed = new ArrayList<>();
        collectActivities(info.activities, exposed);
        collectServices(info.services, exposed);
        collectReceivers(info.receivers, exposed);
        collectProviders(info.providers, exposed);
        if (exposed.isEmpty()) {
            add(out, "Android surface", "Exported components", "PASS", "INFO",
                    "No exported components were reported by PackageManager.");
        } else {
            StringBuilder detail = new StringBuilder();
            int limit = Math.min(exposed.size(), 8);
            for (int i = 0; i < limit; i++) {
                if (i > 0) detail.append("\n");
                detail.append("• ").append(exposed.get(i));
            }
            if (exposed.size() > limit) detail.append("\n…and ").append(exposed.size() - limit).append(" more");
            add(out, "Android surface", "Exported components to review", "WARN", "LOW",
                    exposed.size() + " exported component(s) found. Exported does not automatically mean vulnerable; verify each intent filter and permission.\n" + detail);
        }
    }

    private static void collectActivities(ActivityInfo[] items, List<String> out) {
        if (items == null) return;
        for (ActivityInfo item : items) {
            if (item.exported) out.add("Activity: " + item.name + permissionLabel(item.permission));
        }
    }

    private static void collectServices(ServiceInfo[] items, List<String> out) {
        if (items == null) return;
        for (ServiceInfo item : items) {
            if (item.exported) out.add("Service: " + item.name + permissionLabel(item.permission));
        }
    }

    private static void collectReceivers(ActivityInfo[] items, List<String> out) {
        if (items == null) return;
        for (ActivityInfo item : items) {
            if (item.exported) out.add("Receiver: " + item.name + permissionLabel(item.permission));
        }
    }

    private static void collectProviders(ProviderInfo[] items, List<String> out) {
        if (items == null) return;
        for (ProviderInfo item : items) {
            if (item.exported) out.add("Provider: " + item.name + permissionLabel(item.readPermission));
        }
    }

    private static String permissionLabel(String permission) {
        return permission == null || permission.isEmpty()
                ? " (no component permission)" : " (permission: " + permission + ")";
    }

    private static void inspectDeviceSignals(List<Check> out) {
        String[] indicators = {
                "/system/bin/su", "/system/xbin/su", "/sbin/su",
                "/data/adb/magisk", "/data/adb/modules",
                "/system/framework/XposedBridge.jar",
                "/system/lib/libsubstrate.so", "/system/lib64/libsubstrate.so",
                "/data/local/tmp/frida-server", "/data/local/tmp/re.frida.server"
        };
        List<String> found = new ArrayList<>();
        for (String path : indicators) {
            try {
                if (new File(path).exists()) found.add(path);
            } catch (Exception ignored) { }
        }
        boolean testKeys = Build.TAGS != null && Build.TAGS.contains("test-keys");
        if (found.isEmpty() && !testKeys) {
            add(out, "Runtime", "Root / hooking indicators", "INFO", "INFO",
                    "No common filesystem indicators were found. This heuristic cannot prove that the device is unrooted or that runtime instrumentation is absent.");
        } else {
            String detail = found.isEmpty() ? "Build uses test-keys." : String.join("\n", found);
            if (testKeys) detail += "\nBuild tags contain test-keys.";
            add(out, "Runtime", "Root / hooking indicators", "WARN", "MEDIUM",
                    "Potential device-level indicators were found. This is not proof of compromise and may produce false positives.\n" + detail);
        }
    }

    private static void inspectApkContents(File apk, List<Check> out) {
        if (!apk.isFile()) return;
        boolean hasDex = false;
        boolean hasAssets = false;
        try (ZipFile zip = new ZipFile(apk)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (name.matches("classes(\\d*)?\\.dex")) hasDex = true;
                if ("assets/supabase.js".equals(name)) hasAssets = true;
            }
            add(out, "Modding", "APK structure", hasDex ? "PASS" : "WARN",
                    hasDex ? "INFO" : "MEDIUM",
                    hasDex
                            ? "The installed APK contains Dalvik bytecode."
                            : "No classes.dex entry was found in the base APK. This is unusual for the FOSCH Android package.");
            add(out, "Configuration", "Embedded backend configuration",
                    hasAssets ? "PASS" : "WARN",
                    hasAssets ? "INFO" : "LOW",
                    hasAssets
                            ? "The FOSCH WebView backend configuration asset is present and can be inspected without reading app-private data."
                            : "assets/supabase.js was not found in the base APK.");
        } catch (Exception e) {
            add(out, "Modding", "APK structure", "WARN", "LOW",
                    "Could not inspect the installed APK ZIP structure: " + safeMessage(e));
        }
    }

    public static BackendConfig readBackendConfig(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(FOSCH_PACKAGE, 0);
            File apk = new File(info.applicationInfo.sourceDir);
            try (ZipFile zip = new ZipFile(apk)) {
                ZipEntry entry = zip.getEntry("assets/supabase.js");
                if (entry == null || entry.getSize() > 512 * 1024) return null;
                StringBuilder source = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8))) {
                    String line;
                    int count = 0;
                    while ((line = reader.readLine()) != null && count++ < 2000) {
                        source.append(line).append('\n');
                    }
                }
                Matcher urlMatch = Pattern.compile("SUPABASE_URL\\s*=\\s*[\"']([^\"']+)[\"']").matcher(source);
                Matcher keyMatch = Pattern.compile("SUPABASE_PUBLISHABLE_KEY\\s*=\\s*[\"']([^\"']+)[\"']").matcher(source);
                if (!urlMatch.find() || !keyMatch.find()) return null;
                String base = urlMatch.group(1);
                String key = keyMatch.group(1);
                Uri parsed = Uri.parse(base);
                if (!"https".equalsIgnoreCase(parsed.getScheme()) ||
                        parsed.getHost() == null ||
                        !parsed.getHost().toLowerCase(Locale.ROOT).endsWith(".supabase.co") ||
                        parsed.getUserInfo() != null || !parsed.getPath().isEmpty()) return null;
                return new BackendConfig(base, key);
            }
        } catch (Exception e) {
            return null;
        }
    }

    public static List<Check> runReadOnlyBackendAudit(Context context) {
        List<Check> out = new ArrayList<>();
        BackendConfig config = readBackendConfig(context);
        if (config == null) {
            add(out, "API security", "Backend configuration", "SKIP", "INFO",
                    "Could not safely extract an HTTPS Supabase project URL and publishable key from the installed FOSCH APK.");
            return out;
        }

        add(out, "API security", "Backend target", "INFO", "INFO",
                "Read-only checks will target " + Uri.parse(config.url).getHost() + ". The publishable key is public by design and is not included in this report.");

        ApiResponse read = request(config, "/rest/v1/product_submissions?select=id&limit=0");
        if (read.status == 401 || read.status == 403) {
            add(out, "Database access", "Anonymous submission-table read", "PASS", "INFO",
                    "The anonymous read request was rejected with HTTP " + read.status + ". No records were read.");
            add(out, "SQL injection", "Read-only filter injection canary", "PASS", "INFO",
                    "The endpoint denied the request before the filter was evaluated (HTTP " + read.status + ").");
            return out;
        }
        if (read.status == 200) {
            add(out, "Database access", "Anonymous submission-table read", "FAIL", "HIGH",
                    "The public API accepted a read-only request to product_submissions (HTTP 200). Review grants and RLS policies. The request used limit=0 and did not retrieve rows.");
        } else if (read.status == 404) {
            add(out, "Database access", "Anonymous submission-table read", "INCONCLUSIVE", "LOW",
                    "The endpoint returned HTTP 404. Confirm that the expected table and API schema are deployed.");
        } else {
            add(out, "Database access", "Anonymous submission-table read", "INCONCLUSIVE", "LOW",
                    "The endpoint returned HTTP " + read.status + ". No conclusion about RLS can be drawn from this response.");
        }

        try {
            String payload = "security-lab-' OR '1'='1";
            String encoded = URLEncoder.encode(payload, "UTF-8");
            ApiResponse injection = request(config,
                    "/rest/v1/product_submissions?select=id&client_record_id=eq." + encoded + "&limit=1");
            if (injection.status == 400 || injection.status == 401 || injection.status == 403) {
                add(out, "SQL injection", "Read-only filter injection canary", "PASS", "INFO",
                        "The crafted filter was rejected (HTTP " + injection.status + "). This is a narrow parameter-handling check, not a complete SQL-injection audit.");
            } else if (injection.status == 200) {
                String body = injection.body == null ? "" : injection.body.trim();
                if (body.startsWith("[") && body.endsWith("]")) {
                    JSONArray rows = new JSONArray(body);
                    if (rows.length() == 0) {
                        add(out, "SQL injection", "Read-only filter injection canary", "PASS", "INFO",
                                "The crafted value was handled as a filter value and returned no rows. This does not prove every SQL path is safe.");
                    } else {
                        add(out, "SQL injection", "Read-only filter injection canary", "FAIL", "HIGH",
                                "The crafted read-only filter returned one or more rows. Review query construction and RLS immediately; row contents were not saved.");
                    }
                } else {
                    add(out, "SQL injection", "Read-only filter injection canary", "INCONCLUSIVE", "LOW",
                            "HTTP 200 returned an unexpected response shape. Response body was discarded.");
                }
            } else {
                add(out, "SQL injection", "Read-only filter injection canary", "INCONCLUSIVE", "LOW",
                        "The endpoint returned HTTP " + injection.status + ". This probe cannot determine parameter handling.");
            }
        } catch (Exception e) {
            add(out, "SQL injection", "Read-only filter injection canary", "ERROR", "LOW",
                    safeMessage(e));
        }
        return out;
    }

    public static List<Check> runStagingRateLimitProbe(String rawUrl) {
        List<Check> out = new ArrayList<>();
        Uri uri = Uri.parse(rawUrl == null ? "" : rawUrl.trim());
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!"https".equalsIgnoreCase(uri.getScheme()) ||
                !host.endsWith(".supabase.co") ||
                host.equals(KNOWN_PRODUCTION_HOST) ||
                uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null ||
                (uri.getPath() != null && !uri.getPath().isEmpty() && !"/".equals(uri.getPath()))) {
            add(out, "Rate limiting", "Staging target validation", "FAIL", "MEDIUM",
                    "Target rejected. Supply only the HTTPS base URL of an authorised non-production Supabase staging project.");
            return out;
        }

        add(out, "Rate limiting", "Low-impact staging probe", "INFO", "INFO",
                "Sending five sequential read-only GET requests, at least 1.1 seconds apart, to " + host + ". No concurrent requests or writes will be sent.");

        int seen429 = 0;
        List<Integer> codes = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            int code = requestWithoutKey(rawUrl.trim() + "/rest/v1/");
            codes.add(code);
            if (code == 429) seen429++;
            if (i < 4) {
                try { Thread.sleep(1100L); }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    add(out, "Rate limiting", "Staging request sequence", "ERROR", "LOW",
                            "Probe interrupted. No further requests were sent.");
                    return out;
                }
            }
        }
        if (seen429 > 0) {
            add(out, "Rate limiting", "Rate-limit response observed", "PASS", "INFO",
                    "HTTP 429 was observed during the five-request sequence. Codes: " + codes + ".");
        } else {
            add(out, "Rate limiting", "Rate-limit response observed", "INCONCLUSIVE", "LOW",
                    "No HTTP 429 was observed. Five low-rate requests are not enough to assess DoS resistance or establish a safe capacity limit. Codes: " + codes + ".");
        }
        return out;
    }

    private static ApiResponse request(BackendConfig config, String path) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(config.url + path);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "FOSCH-Security-Lab/0.1.0");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("apikey", config.publishableKey);
            connection.setRequestProperty("Authorization", "Bearer " + config.publishableKey);
            int status = connection.getResponseCode();
            return new ApiResponse(status, readLimited(status >= 200 && status < 400
                    ? connection.getInputStream() : connection.getErrorStream()));
        } catch (Exception e) {
            return new ApiResponse(0, "");
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static int requestWithoutKey(String urlText) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(urlText).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(4000);
            connection.setReadTimeout(4000);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "FOSCH-Security-Lab/0.1.0");
            return connection.getResponseCode();
        } catch (Exception e) {
            return 0;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String readLimited(InputStream input) {
        if (input == null) return "";
        try (InputStream stream = input) {
            byte[] buffer = new byte[1024];
            StringBuilder out = new StringBuilder();
            int total = 0;
            int read;
            while ((read = stream.read(buffer)) != -1 && total < MAX_RESPONSE_BYTES) {
                int allowed = Math.min(read, MAX_RESPONSE_BYTES - total);
                out.append(new String(buffer, 0, allowed, StandardCharsets.UTF_8));
                total += allowed;
            }
            return out.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static final class ApiResponse {
        final int status;
        final String body;
        ApiResponse(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    private static int signingFlag() {
        return Build.VERSION.SDK_INT >= 28
                ? PackageManager.GET_SIGNING_CERTIFICATES
                : PackageManager.GET_SIGNATURES;
    }

    private static String signerFingerprint(PackageInfo info) {
        try {
            android.content.pm.Signature[] signatures;
            if (Build.VERSION.SDK_INT >= 28 && info.signingInfo != null) {
                signatures = info.signingInfo.getApkContentsSigners();
            } else {
                signatures = info.signatures;
            }
            if (signatures == null || signatures.length == 0) return "";
            return hex(MessageDigest.getInstance("SHA-256").digest(signatures[0].toByteArray()));
        } catch (Exception e) {
            return "";
        }
    }

    private static String sha256(File file) {
        if (file == null || !file.isFile()) return "";
        try (InputStream input = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
            return hex(digest.digest());
        } catch (Exception e) {
            return "";
        }
    }

    private static String hex(byte[] data) {
        StringBuilder out = new StringBuilder(data.length * 2);
        for (byte value : data) out.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return out.toString();
    }

    private static String safeMessage(Exception e) {
        String value = e.getMessage();
        if (value == null || value.trim().isEmpty()) return e.getClass().getSimpleName();
        return value.length() > 240 ? value.substring(0, 240) : value;
    }
}
