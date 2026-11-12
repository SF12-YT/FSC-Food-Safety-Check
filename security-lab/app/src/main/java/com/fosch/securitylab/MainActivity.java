package com.fosch.securitylab;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int PICK_BASELINE = 4101;
    private static final int EXPORT_REPORT = 4102;
    private static final int COLOR_BACKGROUND = Color.rgb(11, 20, 34);
    private static final int COLOR_CARD = Color.rgb(20, 35, 54);
    private static final int COLOR_CARD_ALT = Color.rgb(26, 45, 68);
    private static final int COLOR_TEXT = Color.rgb(239, 246, 253);
    private static final int COLOR_MUTED = Color.rgb(157, 178, 201);
    private static final int COLOR_BLUE = Color.rgb(85, 169, 242);
    private static final int COLOR_GREEN = Color.rgb(70, 205, 148);
    private static final int COLOR_AMBER = Color.rgb(244, 185, 82);
    private static final int COLOR_RED = Color.rgb(247, 102, 112);

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private LinearLayout content;
    private LinearLayout resultsContainer;
    private LinearLayout historyContainer;
    private TextView targetStatus;
    private TextView baselineStatus;
    private TextView activityStatus;
    private final List<SecurityAudit.Check> visibleChecks = new ArrayList<>();
    private JSONArray history = new JSONArray();
    private File baselineFile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(COLOR_BACKGROUND);
        getWindow().setNavigationBarColor(COLOR_BACKGROUND);
        getWindow().getDecorView().setSystemUiVisibility(0);

        baselineFile = new File(getFilesDir(), "fosch-known-good-baseline.apk");
        history = loadHistory();
        buildScreen();
        refreshTargetStatus();
        refreshBaselineStatus();
        renderHistory();
    }

    private void buildScreen() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(COLOR_BACKGROUND);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(22), dp(18), dp(28));
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView eyebrow = text("FOSCH / DEFENSIVE TESTING", 11, COLOR_BLUE, true);
        eyebrow.setLetterSpacing(0.12f);
        content.addView(eyebrow);

        TextView title = text("Security Lab", 32, COLOR_TEXT, true);
        title.setPadding(0, dp(5), 0, 0);
        content.addView(title);

        TextView intro = text("An independent companion for inspecting FOSCH, checking its backend boundaries and recording tamper tests.", 14, COLOR_MUTED, false);
        intro.setPadding(0, dp(5), 0, dp(18));
        content.addView(intro);

        LinearLayout targetCard = card();
        targetStatus = text("Checking for FOSCH…", 15, COLOR_TEXT, true);
        targetCard.addView(targetStatus);
        TextView packageLabel = text("Target package: " + SecurityAudit.FOSCH_PACKAGE, 12, COLOR_MUTED, false);
        packageLabel.setPadding(0, dp(6), 0, 0);
        targetCard.addView(packageLabel);
        Button open = actionButton("Open FOSCH", false);
        open.setOnClickListener(v -> openFosch());
        targetCard.addView(open, matchWrap());
        content.addView(targetCard, matchWrap());

        activityStatus = text("Ready · no tests have been run", 11, COLOR_MUTED, false);
        activityStatus.setPadding(dp(3), dp(8), dp(3), dp(2));
        content.addView(activityStatus);

        addSection("01  ·  LOCAL APP & APK");
        addActionCard("Run local integrity audit",
                "Inspect install metadata, signing certificate, APK structure, exported components, backup and transport flags, plus device-level indicators.",
                "RUN LOCAL AUDIT", false, v -> runLocalAudit());
        addActionCard("Import known-good FOSCH APK",
                "Select an official FOSCH APK you trust. Security Lab stores it privately inside its own app sandbox and uses it as the modding/tamper comparison baseline.",
                "IMPORT BASELINE APK", true, v -> pickBaseline());

        LinearLayout baselineCard = card();
        baselineStatus = text("No trusted baseline imported.", 13, COLOR_MUTED, false);
        baselineCard.addView(baselineStatus);
        content.addView(baselineCard, matchWrap());

        addSection("02  ·  BACKEND & API");
        addActionCard("Run read-only API checks",
                "Extract the public Supabase configuration from the installed FOSCH APK. Probe anonymous read access and a harmless SQL-filter canary. No writes or deletes.",
                "RUN API AUDIT", false, v -> runBackendAudit());
        addActionCard("Advanced staging security suite",
                "Probe anonymous database reads, admin RPC authorization, private evidence-bucket listing, malformed submission validation and a bounded 24-request concurrency burst. Uses your staging URL and public anon/publishable key.",
                "CONFIGURE STAGING SUITE", true, v -> showStagingDialog());

        LinearLayout safetyCard = card();
        TextView safetyTitle = text("Staging suite · bounded active tests", 14, COLOR_GREEN, true);
        safetyCard.addView(safetyTitle);
        TextView safetyBody = text("The suite sends active negative-test requests only to the explicitly entered Supabase staging project. It does not upload/delete files, extract records, lock accounts or run an unbounded flood. The known FOSCH production project is blocked.", 12, COLOR_MUTED, false);
        safetyBody.setPadding(0, dp(6), 0, 0);
        safetyCard.addView(safetyBody);
        content.addView(safetyCard, matchWrap());

        addSection("03  ·  RESULTS");
        TextView resultsHint = text("Latest checks", 12, COLOR_MUTED, false);
        resultsHint.setPadding(0, 0, 0, dp(8));
        content.addView(resultsHint);
        resultsContainer = new LinearLayout(this);
        resultsContainer.setOrientation(LinearLayout.VERTICAL);
        content.addView(resultsContainer, matchWrap());

        Button export = actionButton("EXPORT JSON REPORT", false);
        export.setOnClickListener(v -> exportReport());
        LinearLayout.LayoutParams exportParams = matchWrap();
        exportParams.topMargin = dp(10);
        content.addView(export, exportParams);

        addSection("TEST HISTORY");
        historyContainer = new LinearLayout(this);
        historyContainer.setOrientation(LinearLayout.VERTICAL);
        content.addView(historyContainer, matchWrap());

        TextView footer = text("FOSCH Security Lab · 0.2.0 · Findings are evidence, not a guarantee of security.", 11, COLOR_MUTED, false);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, dp(22), 0, 0);
        content.addView(footer);

        setContentView(scroll);
    }

    private void addSection(String name) {
        TextView label = text(name, 12, COLOR_BLUE, true);
        label.setPadding(0, dp(23), 0, dp(10));
        content.addView(label);
    }

    private void addActionCard(String title, String description, String buttonLabel,
                               boolean secondary, View.OnClickListener listener) {
        LinearLayout box = card();
        TextView heading = text(title, 16, COLOR_TEXT, true);
        box.addView(heading);
        TextView body = text(description, 12, COLOR_MUTED, false);
        body.setPadding(0, dp(7), 0, dp(12));
        box.addView(body);
        Button button = actionButton(buttonLabel, secondary);
        button.setOnClickListener(listener);
        box.addView(button, matchWrap());
        LinearLayout.LayoutParams params = matchWrap();
        params.bottomMargin = dp(10);
        content.addView(box, params);
    }

    private LinearLayout card() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(16), dp(16), dp(16));
        box.setBackground(round(COLOR_CARD, 19));
        return box;
    }

    private Button actionButton(String label, boolean secondary) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(12);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setTextColor(secondary ? COLOR_TEXT : Color.rgb(8, 24, 42));
        button.setPadding(dp(12), dp(8), dp(12), dp(8));
        button.setBackground(round(secondary ? COLOR_CARD_ALT : COLOR_BLUE, 13));
        return button;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setLineSpacing(dp(2), 1.0f);
        return view;
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void refreshTargetStatus() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(SecurityAudit.FOSCH_PACKAGE, 0);
            String version = info.versionName == null ? "unknown" : info.versionName;
            targetStatus.setText("FOSCH detected  ·  v" + version);
            targetStatus.setTextColor(COLOR_GREEN);
        } catch (PackageManager.NameNotFoundException e) {
            targetStatus.setText("FOSCH is not installed");
            targetStatus.setTextColor(COLOR_AMBER);
        } catch (Exception e) {
            targetStatus.setText("FOSCH status unavailable");
            targetStatus.setTextColor(COLOR_AMBER);
        }
    }

    private void refreshBaselineStatus() {
        if (baselineFile != null && baselineFile.isFile()) {
            baselineStatus.setText("Known-good baseline imported · " + formatBytes(baselineFile.length()));
            baselineStatus.setTextColor(COLOR_GREEN);
        } else {
            baselineStatus.setText("No trusted baseline imported. Signature and APK-byte comparisons will be skipped.");
            baselineStatus.setTextColor(COLOR_MUTED);
        }
    }

    private void openFosch() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(SecurityAudit.FOSCH_PACKAGE);
        if (launch == null) {
            Toast.makeText(this, "Install FOSCH first.", Toast.LENGTH_SHORT).show();
            return;
        }
        startActivity(launch);
    }

    private void runLocalAudit() {
        setBusy("Running local inspection…");
        executor.execute(() -> {
            List<SecurityAudit.Check> checks = SecurityAudit.inspectInstalled(
                    getApplicationContext(), baselineFile);
            runOnUiThread(() -> {
                appendChecks(checks);
                setIdle("Local audit complete · " + checks.size() + " checks");
                refreshTargetStatus();
            });
        });
    }

    private void runBackendAudit() {
        setBusy("Running read-only API checks…");
        executor.execute(() -> {
            List<SecurityAudit.Check> checks =
                    SecurityAudit.runReadOnlyBackendAudit(getApplicationContext());
            runOnUiThread(() -> {
                appendChecks(checks);
                setIdle("API audit complete · " + checks.size() + " checks");
            });
        });
    }

    private void showStagingDialog() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(4), dp(4), dp(4), dp(4));

        TextView warning = text("Authorise only a staging Supabase project you own. The URL must be the project base URL. Enter the public anon/publishable key only — never a service_role or secret key. The key is used in memory and is not saved or exported. The known FOSCH production project is blocked. The suite includes active negative tests and a capped 4-worker / 24-request burst.", 12, COLOR_MUTED, false);
        form.addView(warning);

        EditText url = new EditText(this);
        url.setSingleLine(true);
        url.setTextColor(COLOR_TEXT);
        url.setHintTextColor(COLOR_MUTED);
        url.setHint("https://your-project-ref.supabase.co");
        url.setText("https://");
        LinearLayout.LayoutParams fieldParams = matchWrap();
        fieldParams.topMargin = dp(12);
        form.addView(url, fieldParams);

        EditText key = new EditText(this);
        key.setSingleLine(true);
        key.setTextColor(COLOR_TEXT);
        key.setHintTextColor(COLOR_MUTED);
        key.setHint("Supabase publishable / anon key");
        LinearLayout.LayoutParams keyParams = matchWrap();
        keyParams.topMargin = dp(8);
        form.addView(key, keyParams);

        EditText confirmation = new EditText(this);
        confirmation.setSingleLine(true);
        confirmation.setTextColor(COLOR_TEXT);
        confirmation.setHintTextColor(COLOR_MUTED);
        confirmation.setHint("Type: I OWN THIS STAGING TARGET");
        LinearLayout.LayoutParams confirmParams = matchWrap();
        confirmParams.topMargin = dp(8);
        form.addView(confirmation, confirmParams);

        new AlertDialog.Builder(this)
                .setTitle("Authorised staging security suite")
                .setView(form)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("RUN ADVANCED SUITE", (dialog, which) -> {
                    String phrase = confirmation.getText().toString().trim();
                    if (!"I OWN THIS STAGING TARGET".equals(phrase)) {
                        Toast.makeText(this, "Authorisation phrase did not match. No requests sent.", Toast.LENGTH_LONG).show();
                        return;
                    }
                    String target = url.getText().toString().trim();
                    String publicKey = key.getText().toString().trim();
                    setBusy("Running staging security suite…");
                    executor.execute(() -> {
                        List<SecurityAudit.Check> checks =
                                SecurityAudit.runStagingSecuritySuite(target, publicKey);
                        runOnUiThread(() -> {
                            appendChecks(checks);
                            setIdle("Staging security suite complete · " + checks.size() + " checks");
                        });
                    });
                })
                .show();
    }

    private void pickBaseline() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/vnd.android.package-archive");
        try {
            startActivityForResult(intent, PICK_BASELINE);
        } catch (Exception e) {
            Toast.makeText(this, "No file picker is available.", Toast.LENGTH_SHORT).show();
        }
    }

    private void importBaseline(Uri uri) {
        setBusy("Importing APK baseline…");
        executor.execute(() -> {
            File temp = new File(getFilesDir(), "fosch-known-good-baseline.tmp");
            File target = new File(getFilesDir(), "fosch-known-good-baseline.apk");
            String error = null;
            try (InputStream input = getContentResolver().openInputStream(uri);
                 OutputStream output = new FileOutputStream(temp)) {
                if (input == null) throw new IllegalStateException("The selected APK could not be opened.");
                byte[] buffer = new byte[16 * 1024];
                long total = 0;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    total += read;
                    if (total > 150L * 1024L * 1024L) {
                        throw new IllegalArgumentException("APK is larger than the 150 MB import limit.");
                    }
                    output.write(buffer, 0, read);
                }
                output.flush();
            } catch (Exception e) {
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }

            if (error == null) {
                try {
                    PackageInfo info = getPackageManager().getPackageArchiveInfo(
                            temp.getAbsolutePath(), (Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES));
                    if (info == null || !SecurityAudit.FOSCH_PACKAGE.equals(info.packageName)) {
                        error = "That APK is not the FOSCH package (" + SecurityAudit.FOSCH_PACKAGE + ").";
                    } else if (!hasArchiveSigner(info)) {
                        error = "The selected APK does not expose a readable signing certificate.";
                    }
                } catch (Exception e) {
                    error = "Could not verify the APK: " + (e.getMessage() == null ? "unknown error" : e.getMessage());
                }
            }

            if (error == null) {
                if (target.exists()) target.delete();
                if (!temp.renameTo(target)) {
                    error = "Could not save the baseline inside Security Lab's private storage.";
                }
            }
            if (error != null) temp.delete();

            final String result = error;
            runOnUiThread(() -> {
                refreshBaselineStatus();
                if (result == null) {
                    appendChecks(singleCheck("Modding", "Known-good APK imported", "PASS", "INFO",
                            "FOSCH package identity and signing certificate parsed successfully. This APK is now the local comparison baseline."));
                    setIdle("Baseline ready");
                } else {
                    appendChecks(singleCheck("Modding", "APK baseline import", "FAIL", "MEDIUM", result));
                    setIdle("Baseline import failed");
                }
            });
        });
    }

    private boolean hasArchiveSigner(PackageInfo info) {
        if (Build.VERSION.SDK_INT >= 28) {
            return info.signingInfo != null && info.signingInfo.getApkContentsSigners() != null
                    && info.signingInfo.getApkContentsSigners().length > 0;
        }
        return info.signatures != null && info.signatures.length > 0;
    }

    private void exportReport() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, "fosch-security-lab-report.json");
        try {
            startActivityForResult(intent, EXPORT_REPORT);
        } catch (Exception e) {
            Toast.makeText(this, "No document provider is available.", Toast.LENGTH_SHORT).show();
        }
    }

    private void writeReport(Uri uri) {
        String report = buildReport();
        try (OutputStream output = getContentResolver().openOutputStream(uri)) {
            if (output == null) throw new IllegalStateException("Could not open the destination.");
            output.write(report.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.flush();
            Toast.makeText(this, "JSON report exported.", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Report export failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == PICK_BASELINE) importBaseline(uri);
        else if (requestCode == EXPORT_REPORT) writeReport(uri);
    }

    private List<SecurityAudit.Check> singleCheck(String category, String title,
                                                   String status, String severity, String details) {
        List<SecurityAudit.Check> list = new ArrayList<>();
        list.add(new SecurityAudit.Check(category, title, status, severity, details));
        return list;
    }

    private void appendChecks(List<SecurityAudit.Check> checks) {
        if (checks == null || checks.isEmpty()) return;
        visibleChecks.clear();
        visibleChecks.addAll(checks);
        for (SecurityAudit.Check check : checks) {
            JSONObject item = new JSONObject();
            try {
                item.put("timestamp", System.currentTimeMillis());
                item.put("category", check.category);
                item.put("title", check.title);
                item.put("status", check.status);
                item.put("severity", check.severity);
                item.put("details", check.details);
                history.put(item);
            } catch (Exception ignored) { }
        }
        if (history.length() > 250) {
            JSONArray trimmed = new JSONArray();
            int start = history.length() - 250;
            for (int i = start; i < history.length(); i++) {
                JSONObject item = history.optJSONObject(i);
                if (item != null) trimmed.put(item);
            }
            history = trimmed;
        }
        getSharedPreferences("security-lab", MODE_PRIVATE).edit()
                .putString("history", history.toString()).apply();
        renderResults();
        renderHistory();
    }

    private JSONArray loadHistory() {
        try {
            return new JSONArray(getSharedPreferences("security-lab", MODE_PRIVATE)
                    .getString("history", "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private void renderResults() {
        resultsContainer.removeAllViews();
        if (visibleChecks.isEmpty()) {
            resultsContainer.addView(text("No checks run yet.", 12, COLOR_MUTED, false));
            return;
        }
        for (SecurityAudit.Check check : visibleChecks) {
            resultsContainer.addView(resultCard(check), matchWrap());
        }
    }

    private void renderHistory() {
        historyContainer.removeAllViews();
        int start = Math.max(0, history.length() - 8);
        for (int i = history.length() - 1; i >= start; i--) {
            JSONObject item = history.optJSONObject(i);
            if (item == null) continue;
            String title = item.optString("title", "Test");
            String status = item.optString("status", "INFO");
            String date = new SimpleDateFormat("dd MMM HH:mm", Locale.getDefault())
                    .format(new Date(item.optLong("timestamp", 0)));
            TextView line = text(date + "  ·  " + status + "  ·  " + title, 11,
                    statusColor(status), false);
            line.setPadding(dp(2), dp(5), dp(2), dp(5));
            historyContainer.addView(line);
        }
        if (history.length() == 0) {
            historyContainer.addView(text("No saved test history yet.", 11, COLOR_MUTED, false));
        }
    }

    private View resultCard(SecurityAudit.Check check) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(13), dp(12), dp(13), dp(12));
        box.setBackground(round(COLOR_CARD_ALT, 14));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = text(check.title, 13, COLOR_TEXT, true);
        header.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView badge = text(check.status, 10, statusColor(check.status), true);
        header.addView(badge);
        box.addView(header);

        TextView meta = text(check.category + "  ·  " + check.severity, 10, COLOR_MUTED, false);
        meta.setPadding(0, dp(4), 0, dp(5));
        box.addView(meta);

        TextView details = text(check.details, 11, COLOR_MUTED, false);
        box.addView(details);
        LinearLayout.LayoutParams params = matchWrap();
        params.bottomMargin = dp(7);
        box.setLayoutParams(params);
        return box;
    }

    private int statusColor(String status) {
        if ("PASS".equals(status)) return COLOR_GREEN;
        if ("FAIL".equals(status) || "ERROR".equals(status)) return COLOR_RED;
        if ("WARN".equals(status) || "INCONCLUSIVE".equals(status)) return COLOR_AMBER;
        return COLOR_BLUE;
    }

    private void setBusy(String message) {
        if (activityStatus != null) activityStatus.setText(message);
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void setIdle(String message) {
        if (activityStatus != null) activityStatus.setText(message);
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private String buildReport() {
        JSONObject root = new JSONObject();
        try {
            root.put("application", "FOSCH Security Lab");
            root.put("version", "0.1.0");
            root.put("targetPackage", SecurityAudit.FOSCH_PACKAGE);
            root.put("generatedAt", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
                    .format(new Date()));
            root.put("history", history);
            root.put("notice", "Read-only/low-impact defensive checks. A PASS is limited to the described test and is not proof of complete security.");
        } catch (Exception ignored) { }
        return root.toString();
    }

    private String formatBytes(long bytes) {
        if (bytes >= 1024L * 1024L) return String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format(Locale.getDefault(), "%.0f KB", bytes / 1024.0);
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
