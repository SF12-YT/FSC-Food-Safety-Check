package com.fosch.org;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Floating query-entry dialog launched only when the widget's field is tapped. */
public final class FOSCHWidgetSearchActivity extends Activity {
    public static final String EXTRA_INITIAL_QUERY = "com.fosch.org.EXTRA_WIDGET_INITIAL_QUERY";
    private EditText searchInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        String preference = getSharedPreferences("fosch-appearance", MODE_PRIVATE)
                .getString("theme_preference", "system");
        boolean systemDark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        boolean dark = "dark".equals(preference) || ("system".equals(preference) && systemDark);
        setTheme(dark ? android.R.style.Theme_Material_Dialog_Alert : R.style.FOSCHWidgetSearchDialogTheme);
        super.onCreate(savedInstanceState);
        setFinishOnTouchOutside(false);

        int surface = dark ? Color.rgb(24, 31, 35) : Color.WHITE;
        int text = dark ? Color.rgb(241, 247, 250) : Color.rgb(23, 49, 58);
        int muted = dark ? Color.rgb(164, 183, 193) : Color.rgb(96, 114, 123);
        int accent = dark ? Color.rgb(139, 185, 236) : Color.rgb(0, 125, 158);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(20), dp(22), dp(12));
        content.setBackground(new ColorDrawable(surface));

        TextView title = new TextView(this);
        title.setText("Search FOSCH");
        title.setTextColor(text);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        content.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Search E-numbers and additive names.");
        subtitle.setTextColor(muted);
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        subtitleParams.topMargin = dp(5);
        content.addView(subtitle, subtitleParams);

        searchInput = new EditText(this);
        searchInput.setSingleLine(true);
        searchInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        searchInput.setTextColor(text);
        searchInput.setHintTextColor(muted);
        searchInput.setHint("E621 or citric acid");
        searchInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_AUTO_CORRECT);
        searchInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        String initial = getIntent().getStringExtra(EXTRA_INITIAL_QUERY);
        if (initial == null || initial.trim().isEmpty()) initial = FOSCHSearchWidgetProvider.savedQuery(this);
        searchInput.setText(initial);
        searchInput.setSelection(searchInput.getText().length());
        searchInput.setPadding(dp(10), dp(10), dp(10), dp(10));
        searchInput.setBackgroundTintList(android.content.res.ColorStateList.valueOf(accent));
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        inputParams.topMargin = dp(18);
        content.addView(searchInput, inputParams);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams buttonsParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        buttonsParams.topMargin = dp(12);

        Button cancel = new Button(this);
        cancel.setText("Cancel");
        cancel.setAllCaps(false);
        cancel.setTextColor(muted);
        cancel.setOnClickListener(v -> finish());
        buttons.addView(cancel);

        Button search = new Button(this);
        search.setText("Search");
        search.setAllCaps(false);
        search.setTextColor(Color.WHITE);
        search.setBackgroundTintList(android.content.res.ColorStateList.valueOf(accent));
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        searchParams.leftMargin = dp(8);
        buttons.addView(search, searchParams);
        search.setOnClickListener(v -> submitSearch());
        searchInput.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                submitSearch();
                return true;
            }
            return false;
        });
        content.addView(buttons, buttonsParams);
        setContentView(content);

        Window window = getWindow();
        if (window != null) {
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                    | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            WindowManager.LayoutParams params = window.getAttributes();
            params.width = Math.min(dp(360), getResources().getDisplayMetrics().widthPixels - dp(40));
            params.height = WindowManager.LayoutParams.WRAP_CONTENT;
            window.setAttributes(params);
        }
        searchInput.requestFocus();
        searchInput.post(() -> {
            InputMethodManager keyboard = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (keyboard != null) keyboard.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT);
        });
    }

    private void submitSearch() {
        String query = searchInput.getText() == null ? "" : searchInput.getText().toString().trim();
        if (query.isEmpty()) {
            searchInput.setError("Enter an E-number or additive name");
            searchInput.requestFocus();
            return;
        }
        getSharedPreferences(FOSCHSearchWidgetProvider.PREFERENCES, MODE_PRIVATE)
                .edit().putString(FOSCHSearchWidgetProvider.KEY_LAST_QUERY, query).apply();
        FOSCHSearchWidgetProvider.updateAll(this);

        Intent openApp = new Intent(this, MainActivity.class);
        openApp.putExtra(MainActivity.EXTRA_WIDGET_SEARCH_QUERY, query);
        openApp.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(openApp);
        finish();
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
