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
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

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
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
                        hasUserInfo(parsed) || (parsed.getPath() != null && !parsed.getPath().isEmpty() && !"/".equals(parsed.getPath()))) return null;
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

        ApiResponse read = request(config, "/rest/v1/product_submissions?select=id&limit=1");
        assessAnonymousRead(out, "product_submissions", read);

        ApiResponse auditRead = request(config, "/rest/v1/admin_audit_log?select=id&limit=1");
        assessAnonymousRead(out, "admin_audit_log", auditRead);

        try {
            String payload = "security-lab-" + UUID.randomUUID() + "' OR '1'='1";
            String encoded = URLEncoder.encode(payload, "UTF-8");
            ApiResponse injection = request(config,
                    "/rest/v1/product_submissions?select=id&client_record_id=eq." + encoded + "&limit=1");
            if (injection.status == 200) {
                String body = injection.body == null ? "" : injection.body.trim();
                if (body.startsWith("[") && body.endsWith("]")) {
                    JSONArray rows = new JSONArray(body);
                    if (rows.length() == 0) {
                        add(out, "Input handling", "PostgREST filter canary", "PASS", "INFO",
                                "The random SQL-looking value returned no rows. It appears to have been treated as a filter value; this is a narrow canary, not proof that every query path is injection-safe.");
                    } else {
                        add(out, "Input handling", "PostgREST filter canary", "INCONCLUSIVE", "MEDIUM",
                                "The canary returned rows. This may be an unexpected filter/query result; inspect server logs and reproduce with a controlled fixture. Row contents were discarded.");
                    }
                } else {
                    add(out, "Input handling", "PostgREST filter canary", "INCONCLUSIVE", "LOW",
                            "HTTP 200 returned an unexpected response shape. Response body was discarded.");
                }
            } else if (injection.status == 401 || injection.status == 403) {
                add(out, "Input handling", "PostgREST filter canary", "INCONCLUSIVE", "LOW",
                        "The request was rejected with HTTP " + injection.status + " before the filter behaviour could be assessed. Authentication rejection is not evidence of SQL-injection resistance.");
            } else {
                add(out, "Input handling", "PostgREST filter canary", "INCONCLUSIVE", "LOW",
                        "The endpoint returned HTTP " + injection.status + ". The response does not establish whether the filter was handled safely.");
            }
        } catch (Exception e) {
            add(out, "Input handling", "PostgREST filter canary", "ERROR", "LOW", safeMessage(e));
        }
        return out;
    }

    private static String validateStagingBase(String rawUrl) {
        Uri uri = Uri.parse(rawUrl == null ? "" : rawUrl.trim());
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        String path = uri.getPath();
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || !host.matches("^[a-z0-9]{20}\\.supabase\\.co$")
                || host.equals(KNOWN_PRODUCTION_HOST)
                || uri.getPort() != -1
                || hasUserInfo(uri)
                || uri.getQuery() != null
                || uri.getFragment() != null
                || (path != null && !path.isEmpty() && !"/".equals(path))) {
            return null;
        }
        return "https://" + host;
    }

    private static boolean isPublicSupabaseKey(String key) {
        if (key == null) return false;
        String value = key.trim();
        String lower = value.toLowerCase(Locale.ROOT);
        if (value.isEmpty() || value.length() > 4096
                || value.matches(".*\\s+.*")
                || lower.startsWith("sb_secret_")
                || lower.contains("service_role")) {
            return false;
        }
        // Legacy Supabase anon/service_role keys are JWTs; inspect the role claim
        // so a service_role key cannot be pasted into this public-key field.
        String[] jwt = value.split("\\.");
        if (jwt.length == 3) {
            try {
                byte[] payload = Base64.decode(jwt[1],
                        Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
                JSONObject claims = new JSONObject(new String(payload, StandardCharsets.UTF_8));
                if ("service_role".equalsIgnoreCase(claims.optString("role", ""))) return false;
            } catch (Exception ignored) { }
        }
        return true;
    }

    public static List<Check> runStagingSecuritySuite(String rawUrl, String rawKey) {
        List<Check> out = new ArrayList<>();
        String base = validateStagingBase(rawUrl);
        if (base == null) {
            add(out, "Target validation", "Staging project URL", "FAIL", "HIGH",
                    "Rejected. Enter only the HTTPS base URL of a Supabase project (https://<20-character-project-ref>.supabase.co). The known FOSCH production project is blocked. No requests were sent.");
            return out;
        }
        String key = rawKey == null ? "" : rawKey.trim();
        if (!isPublicSupabaseKey(key)) {
            add(out, "Target validation", "Supabase publishable key", "FAIL", "HIGH",
                    "A public publishable/anon key is required. Secret and service_role keys are rejected. No requests were sent.");
            return out;
        }

        String host = Uri.parse(base).getHost();
        add(out, "Target validation", "Explicit staging target accepted", "PASS", "INFO",
                "Target " + host + " passed HTTPS, hostname, path and production-host checks. The entered publishable/anon key is used only in memory and is not saved or included in the report.");

        ApiResponse submissions = stagingRequest(base, key, "GET",
                "/rest/v1/product_submissions?select=id&limit=1", null);
        assessAnonymousRead(out, "product_submissions", submissions);

        ApiResponse auditLog = stagingRequest(base, key, "GET",
                "/rest/v1/admin_audit_log?select=id&limit=1", null);
        assessAnonymousRead(out, "admin_audit_log", auditLog);

        String canary = "security-lab-" + UUID.randomUUID() + "' OR '1'='1";
        try {
            ApiResponse filter = stagingRequest(base, key, "GET",
                    "/rest/v1/product_submissions?select=id&client_record_id=eq."
                            + URLEncoder.encode(canary, "UTF-8") + "&limit=1", null);
            if (filter.status == 200 && isJsonArray(filter.body)) {
                JSONArray rows = new JSONArray(filter.body);
                add(out, "Input validation", "SQL-looking PostgREST filter canary",
                        rows.length() == 0 ? "PASS" : "INCONCLUSIVE",
                        rows.length() == 0 ? "INFO" : "MEDIUM",
                        rows.length() == 0
                                ? "A random SQL-looking string behaved like a literal filter and returned no rows. This is a narrow PostgREST check, not a full SQL-injection proof."
                                : "The canary returned a row. This could be a fixture collision or unexpected filter behaviour; inspect the staging logs. Row contents were discarded.");
            } else if (filter.status == 401 || filter.status == 403) {
                add(out, "Input validation", "SQL-looking PostgREST filter canary", "INCONCLUSIVE", "LOW",
                        "HTTP " + filter.status + " prevented filter evaluation. Authentication rejection is not proof of injection resistance.");
            } else {
                add(out, "Input validation", "SQL-looking PostgREST filter canary", "INCONCLUSIVE", "LOW",
                        "HTTP " + filter.status + " did not provide enough evidence to classify filter handling.");
            }
        } catch (Exception e) {
            add(out, "Input validation", "SQL-looking PostgREST filter canary", "ERROR", "LOW", safeMessage(e));
        }

        // The public submission RPC must reject malformed input before any insert can occur.
        ApiResponse invalidSubmission = stagingRequest(base, key, "POST",
                "/rest/v1/rpc/submit_fosch_product_submission",
                "{\"p_submission\":{\"id\":\"not-a-uuid\"}}");
        String invalidBody = lower(invalidSubmission.body);
        if (invalidSubmission.status == 400 && invalidBody.contains("valid submission id")) {
            add(out, "Input validation", "Malformed submission UUID", "PASS", "INFO",
                    "The public submission RPC rejected an invalid UUID before attempting an insert.");
        } else if (invalidSubmission.status == 200) {
            add(out, "Input validation", "Malformed submission UUID", "FAIL", "CRITICAL",
                    "The submission RPC accepted an invalid UUID payload. Check the deployed function immediately.");
        } else {
            add(out, "Input validation", "Malformed submission UUID", "INCONCLUSIVE", "LOW",
                    "The malformed request returned HTTP " + invalidSubmission.status + ". Expected a validation error mentioning the submission ID; confirm the staging migration and API key.");
        }

        probeAdminRpc(base, key, out, "admin_log_event",
                "/rest/v1/rpc/admin_log_event",
                "{\"p_action\":\"\",\"p_details\":{}}",
                "Empty action is rejected before any audit insert can occur.");
        String absentId = UUID.randomUUID().toString();
        probeAdminRpc(base, key, out, "admin_update_product_submission",
                "/rest/v1/rpc/admin_update_product_submission",
                "{\"p_submission_id\":\"" + absentId + "\",\"p_changes\":{},\"p_reason\":\"Security Lab unauthorised negative test\"}",
                "Uses a freshly generated UUID that is not sourced from any submission. A missing-row path cannot update a real submission.");
        probeAdminRpc(base, key, out, "admin_delete_product_submission",
                "/rest/v1/rpc/admin_delete_product_submission",
                "{\"p_submission_id\":\"" + absentId + "\",\"p_reason\":\"Security Lab unauthorised negative test\"}",
                "Uses the same freshly generated UUID; no submission contents are read or supplied.");
        probeAdminRpc(base, key, out, "admin_set_fosch_r2_public_url",
                "/rest/v1/rpc/admin_set_fosch_r2_public_url",
                "{\"p_public_url\":\"not-a-valid-url\"}",
                "Invalid URL input is used so the probe cannot change the configured public origin.");

        ApiResponse privateList = stagingRequest(base, key, "POST",
                "/storage/v1/object/list/fosch-submission-photos",
                "{\"prefix\":\"security-lab-no-such-prefix-" + UUID.randomUUID()
                        + "\",\"limit\":1,\"offset\":0}");
        String privateBody = lower(privateList.body);
        if (privateList.status == 401 || privateList.status == 403
                || (privateList.status == 400 && (privateBody.contains("row-level security")
                || privateBody.contains("permission denied")
                || privateBody.contains("not authorized")
                || privateBody.contains("unauthorized")))) {
            add(out, "Storage permissions", "Private evidence bucket listing", "PASS", "INFO",
                    "Anonymous listing of fosch-submission-photos was denied. No object was uploaded, downloaded or deleted.");
        } else if (privateList.status == 200) {
            add(out, "Storage permissions", "Private evidence bucket listing", "FAIL", "HIGH",
                    "The anonymous role was allowed to list the private evidence bucket, even though the prefix was a random non-existent probe path. Review storage.objects SELECT policies.");
        } else {
            add(out, "Storage permissions", "Private evidence bucket listing", "INCONCLUSIVE", "LOW",
                    "The storage list request returned HTTP " + privateList.status
                            + ". A missing bucket/migration or request-format issue cannot be distinguished from an access-control result.");
        }

        runBoundedBurst(base, key, out);
        return out;
    }

    private static void assessAnonymousRead(List<Check> out, String table, ApiResponse response) {
        if (response.status == 200 && isJsonArray(response.body)) {
            try {
                JSONArray rows = new JSONArray(response.body);
                if (rows.length() == 0) {
                    add(out, "Database RLS", "Anonymous read: " + table, "PASS", "INFO",
                            "The anonymous publishable-key request returned an empty array. This is consistent with the intended RLS policy; it does not validate authenticated-admin access.");
                } else {
                    add(out, "Database RLS", "Anonymous read: " + table, "FAIL", "CRITICAL",
                            "The anonymous role received " + rows.length() + " row(s) from " + table
                                    + ". Only row IDs were requested; response contents were discarded. Review table grants and RLS immediately.");
                }
            } catch (Exception e) {
                add(out, "Database RLS", "Anonymous read: " + table, "INCONCLUSIVE", "LOW",
                        "Could not parse the HTTP 200 response as a JSON array.");
            }
        } else if (response.status == 401 || response.status == 403) {
            add(out, "Database RLS", "Anonymous read: " + table, "INCONCLUSIVE", "LOW",
                    "HTTP " + response.status + " rejected the request. This may be API-key/auth rejection before RLS; it is not recorded as a PASS.");
        } else if (response.status == 404) {
            add(out, "Database RLS", "Anonymous read: " + table, "INCONCLUSIVE", "LOW",
                    "HTTP 404: table or API schema may be absent in this staging project.");
        } else {
            add(out, "Database RLS", "Anonymous read: " + table, "INCONCLUSIVE", "LOW",
                    "HTTP " + response.status + " did not establish whether anonymous row access is blocked.");
        }
    }

    private static boolean isJsonArray(String body) {
        if (body == null) return false;
        String value = body.trim();
        return value.startsWith("[") && value.endsWith("]");
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static void probeAdminRpc(String base, String key, List<Check> out,
                                      String functionName, String path, String body,
                                      String safetyNote) {
        ApiResponse response = stagingRequest(base, key, "POST", path, body);
        String text = lower(response.body);
        boolean explicitAdminDeny = text.contains("administrator access required")
                || text.contains("42501")
                || text.contains("insufficient_privilege");
        if (response.status == 401 || response.status == 403 || explicitAdminDeny) {
            add(out, "RPC authorization", "Anonymous call: " + functionName, "PASS", "INFO",
                    "The anonymous call was blocked by the API privilege boundary or the function's admin check. " + safetyNote);
        } else if (response.status == 200) {
            add(out, "RPC authorization", "Anonymous call: " + functionName, "FAIL", "CRITICAL",
                    "The admin RPC returned HTTP 200 to the anonymous role. Review EXECUTE grants and the server-side admin check immediately.");
        } else if (response.status == 400 && (text.contains("submission not found")
                || text.contains("invalid r2 public origin")
                || text.contains("action is required"))) {
            add(out, "RPC authorization", "Anonymous call: " + functionName, "FAIL", "CRITICAL",
                    "The request reached function-level validation without an explicit admin denial. No intended data change was made because the probe arguments are invalid or reference a random absent UUID. Review EXECUTE grants and SECURITY DEFINER checks. " + safetyNote);
        } else if (response.status == 404) {
            add(out, "RPC authorization", "Anonymous call: " + functionName, "INCONCLUSIVE", "LOW",
                    "HTTP 404: the RPC may not be deployed, exposed in the API schema, or callable by this role.");
        } else {
            add(out, "RPC authorization", "Anonymous call: " + functionName, "INCONCLUSIVE", "LOW",
                    "HTTP " + response.status + " did not prove that the RPC privilege boundary held. " + safetyNote);
        }
    }

    private static void runBoundedBurst(String base, String key, List<Check> out) {
        final int workers = 4;
        final int requestsPerWorker = 6;
        final AtomicBoolean stop = new AtomicBoolean(false);
        final List<Integer> codes = Collections.synchronizedList(new ArrayList<>());
        final List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        List<Future<?>> tasks = new ArrayList<>();

        add(out, "Rate limiting", "Bounded concurrency test", "INFO", "INFO",
                "Starting at most 24 read-only GET requests with no more than four concurrent workers. The sequence stops early on HTTP 429, any 5xx response or a transport failure. No writes or uploads are sent.");

        for (int worker = 0; worker < workers; worker++) {
            tasks.add(pool.submit(() -> {
                for (int i = 0; i < requestsPerWorker && !stop.get(); i++) {
                    ApiResponse response = stagingRequest(base, key, "GET", "/rest/v1/", null);
                    codes.add(response.status);
                    latencies.add(response.elapsedMs);
                    if (response.status == 429 || response.status >= 500 || response.status == 0) {
                        stop.set(true);
                    }
                    if (i + 1 < requestsPerWorker && !stop.get()) {
                        try { Thread.sleep(180L); }
                        catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            stop.set(true);
                        }
                    }
                }
            }));
        }

        pool.shutdown();
        try {
            for (Future<?> task : tasks) task.get(35, TimeUnit.SECONDS);
            if (!pool.awaitTermination(2, TimeUnit.SECONDS)) pool.shutdownNow();
        } catch (Exception e) {
            stop.set(true);
            pool.shutdownNow();
        }

        int count429 = 0;
        int count5xx = 0;
        int countTransport = 0;
        int countAuth = 0;
        long maxLatency = 0L;
        long totalLatency = 0L;
        synchronized (codes) {
            for (int code : codes) {
                if (code == 429) count429++;
                if (code >= 500) count5xx++;
                if (code == 0) countTransport++;
                if (code == 401 || code == 403) countAuth++;
            }
        }
        synchronized (latencies) {
            for (long duration : latencies) {
                totalLatency += duration;
                maxLatency = Math.max(maxLatency, duration);
            }
        }
        long averageLatency = latencies.isEmpty() ? 0L : totalLatency / latencies.size();
        String evidence = "Requests completed: " + codes.size() + " (maximum 24), HTTP 429: "
                + count429 + ", 5xx: " + count5xx + ", transport failures: " + countTransport
                + ", auth denials: " + countAuth + ", average latency: " + averageLatency
                + " ms, maximum latency: " + maxLatency + " ms. Codes: " + codes + ".";

        if (count429 > 0) {
            add(out, "Rate limiting", "Bounded concurrency response", "PASS", "INFO",
                    "The API returned HTTP 429 during the bounded burst. " + evidence);
        } else if (count5xx > 0) {
            add(out, "Availability", "Staging API under bounded burst", "WARN", "HIGH",
                    "The staging API returned one or more 5xx responses under a small 4-worker probe. The test stopped early. " + evidence);
        } else if (countTransport > 0 || countAuth == codes.size()) {
            add(out, "Rate limiting", "Bounded concurrency response", "INCONCLUSIVE", "LOW",
                    "The probe encountered transport failures or every request was rejected at authentication, so throttling behaviour could not be assessed. " + evidence);
        } else {
            add(out, "Rate limiting", "Bounded concurrency response", "INCONCLUSIVE", "LOW",
                    "No HTTP 429 was observed, but 24 requests are not a capacity or DoS-resistance test and cannot prove rate limiting is absent. " + evidence);
        }
    }

    private static ApiResponse request(BackendConfig config, String path) {
        return stagingRequest(config.url, config.publishableKey, "GET", path, null);
    }

    private static ApiResponse stagingRequest(String base, String key, String method,
                                              String path, String body) {
        HttpURLConnection connection = null;
        long start = System.currentTimeMillis();
        try {
            connection = (HttpURLConnection) new URL(base + path).openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(6000);
            connection.setReadTimeout(6000);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "FOSCH-Security-Lab/0.2.0");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("apikey", key);
            // New publishable keys are opaque API keys, not JWT bearer tokens.
            // Legacy anon JWTs still work as both apikey and Authorization.
            if (!key.startsWith("sb_publishable_")) {
                connection.setRequestProperty("Authorization", "Bearer " + key);
            }
            if (body != null) {
                byte[] payload = body.getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setFixedLengthStreamingMode(payload.length);
                try (java.io.OutputStream stream = connection.getOutputStream()) {
                    stream.write(payload);
                    stream.flush();
                }
            }
            int status = connection.getResponseCode();
            InputStream responseStream = status >= 200 && status < 400
                    ? connection.getInputStream() : connection.getErrorStream();
            return new ApiResponse(status, readLimited(responseStream),
                    Math.max(0L, System.currentTimeMillis() - start));
        } catch (Exception e) {
            return new ApiResponse(0, "", Math.max(0L, System.currentTimeMillis() - start));
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
        final long elapsedMs;
        ApiResponse(int status, String body, long elapsedMs) {
            this.status = status;
            this.body = body;
            this.elapsedMs = elapsedMs;
        }
    }

    private static boolean hasUserInfo(Uri uri) {
        String authority = uri == null ? null : uri.getEncodedAuthority();
        return authority == null || authority.contains("@");
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
