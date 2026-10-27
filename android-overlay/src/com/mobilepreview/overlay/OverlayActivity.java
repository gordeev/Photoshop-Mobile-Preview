package com.mobilepreview.overlay;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import com.mobilepreview.viewer.CutoutOverlay;

import java.util.List;

/** Settings screen: start/stop the overlay, pick the cutout, danger zone and a PNG. */
public class OverlayActivity extends Activity {

    private static final int PICK_PNG = 1;
    private static final int ACCENT = 0xFF2F80ED;
    private static final int DANGER = 0xFFD63B3B;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private List<CutoutOverlay.Profile> profiles;

    private TextView permissionBtn, toggleBtn, cutTitle, cutSubtitle, dangerBtn, cornersBtn;
    private TextView pngName, pngClearBtn, pngShowBtn, pngModeBtn, alphaLabel;
    private SeekBar alphaBar;
    private LinearLayout pngControls;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = Prefs.get(this);
        profiles = new CutoutOverlay(this).profiles();
        setContentView(buildUi());
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 0);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    // ------------------------------------------------------------------ UI

    private View buildUi() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), dp(28), dp(20), dp(28));

        TextView title = text("PS Overlay", 26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(title);
        TextView sub = text(getString(R.string.overlay_subtitle), 14);
        sub.setTextColor(0xFFAAAAAA);
        col.addView(sub);

        permissionBtn = button(getString(R.string.overlay_permission), v -> startActivity(
                new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))));
        col.addView(permissionBtn, block(20));

        toggleBtn = button("", v -> toggleOverlay());
        toggleBtn.setTextSize(18);
        toggleBtn.setPadding(dp(16), dp(16), dp(16), dp(16));
        col.addView(toggleBtn, block(12));

        col.addView(header(getString(R.string.cutout)));
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(button("‹", v -> stepProfile(-1)), new LinearLayout.LayoutParams(dp(56), dp(56)));
        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        names.setGravity(Gravity.CENTER);
        cutTitle = text("", 17);
        cutTitle.setGravity(Gravity.CENTER);
        cutSubtitle = text("", 13);
        cutSubtitle.setGravity(Gravity.CENTER);
        cutSubtitle.setTextColor(0xFFAAAAAA);
        names.addView(cutTitle);
        names.addView(cutSubtitle);
        row.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(button("›", v -> stepProfile(1)), new LinearLayout.LayoutParams(dp(56), dp(56)));
        col.addView(row, block(8));

        LinearLayout toggles = new LinearLayout(this);
        dangerBtn = button(getString(R.string.danger_zone), v -> flip(Prefs.DANGER, true));
        cornersBtn = button(getString(R.string.screen_corners), v -> flip(Prefs.CORNERS, true));
        toggles.addView(dangerBtn, weighted(0));
        toggles.addView(cornersBtn, weighted(dp(8)));
        col.addView(toggles, block(10));

        col.addView(header(getString(R.string.png_overlay)));
        col.addView(button(getString(R.string.pick_png), v -> pickPng()), block(8));
        pngName = text("", 13);
        pngName.setTextColor(0xFFAAAAAA);
        col.addView(pngName, block(6));

        pngControls = new LinearLayout(this);
        pngControls.setOrientation(LinearLayout.VERTICAL);
        LinearLayout pngRow = new LinearLayout(this);
        pngShowBtn = button(getString(R.string.show_png), v -> flip(Prefs.PNG_ON, true));
        pngModeBtn = button("", v -> flip(Prefs.PNG_STRETCH, true));
        pngClearBtn = button(getString(R.string.remove_png), v -> clearPng());
        pngRow.addView(pngShowBtn, weighted(0));
        pngRow.addView(pngModeBtn, weighted(dp(8)));
        pngRow.addView(pngClearBtn, weighted(dp(8)));
        pngControls.addView(pngRow, block(8));
        alphaLabel = text("", 14);
        pngControls.addView(alphaLabel, block(12));
        alphaBar = new SeekBar(this);
        alphaBar.setMax(95);
        alphaBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (!fromUser) return;
                prefs.edit().putInt(Prefs.PNG_ALPHA, p + 5).apply();
                alphaLabel.setText(getString(R.string.opacity, p + 5));
                pushToService();
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        pngControls.addView(alphaBar, block(4));
        col.addView(pngControls);

        TextView hint = text(getString(R.string.overlay_hint), 13);
        hint.setTextColor(0xFF888888);
        col.addView(hint, block(24));

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF111111);
        scroll.addView(col);
        return scroll;
    }

    private void refresh() {
        boolean allowed = Settings.canDrawOverlays(this);
        permissionBtn.setVisibility(allowed ? View.GONE : View.VISIBLE);
        toggleBtn.setEnabled(allowed);
        toggleBtn.setText(OverlayService.running ? getString(R.string.stop_overlay) : getString(R.string.start_overlay));
        paint(toggleBtn, allowed, OverlayService.running ? DANGER : ACCENT);

        int i = profileIndex();
        CutoutOverlay.Profile p = profiles.get(i);
        cutTitle.setText(p.shortName);
        cutSubtitle.setText(p.kind + (p.empty ? "" : " · " + p.device + (p.approx ? " ≈" : ""))
                + "  ·  " + getString(R.string.profile_count, i + 1, profiles.size()));
        paint(dangerBtn, prefs.getBoolean(Prefs.DANGER, true), DANGER);
        paint(cornersBtn, prefs.getBoolean(Prefs.CORNERS, true), ACCENT);

        boolean hasPng = !prefs.getString(Prefs.PNG_URI, "").isEmpty();
        pngName.setText(hasPng ? prefs.getString(Prefs.PNG_NAME, getString(R.string.png_selected)) : getString(R.string.png_not_selected));
        pngControls.setVisibility(hasPng ? View.VISIBLE : View.GONE);
        paint(pngShowBtn, prefs.getBoolean(Prefs.PNG_ON, true), ACCENT);
        boolean stretch = prefs.getBoolean(Prefs.PNG_STRETCH, true);
        pngModeBtn.setText(stretch ? getString(R.string.stretch) : getString(R.string.fit));
        paint(pngModeBtn, false, ACCENT);
        paint(pngClearBtn, false, ACCENT);
        int alpha = prefs.getInt(Prefs.PNG_ALPHA, 60);
        alphaBar.setProgress(alpha - 5);
        alphaLabel.setText(getString(R.string.opacity, alpha));
    }

    // ------------------------------------------------------------- actions

    private void toggleOverlay() {
        if (OverlayService.running) {
            startService(new Intent(this, OverlayService.class).setAction(OverlayService.ACTION_STOP));
        } else {
            startForegroundService(new Intent(this, OverlayService.class).setAction(OverlayService.ACTION_SHOW));
        }
        ui.postDelayed(this::refresh, 300);
    }

    private int profileIndex() {
        String id = prefs.getString(Prefs.PROFILE, "none");
        for (int i = 0; i < profiles.size(); i++) {
            if (profiles.get(i).id.equals(id)) return i;
        }
        return 0;
    }

    private void stepProfile(int delta) {
        int n = profiles.size();
        int i = ((profileIndex() + delta) % n + n) % n;
        prefs.edit().putString(Prefs.PROFILE, profiles.get(i).id).apply();
        changed();
    }

    private void flip(String key, boolean def) {
        prefs.edit().putBoolean(key, !prefs.getBoolean(key, def)).apply();
        changed();
    }

    private void pickPng() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        startActivityForResult(i, PICK_PNG);
    }

    private void clearPng() {
        prefs.edit().remove(Prefs.PNG_URI).remove(Prefs.PNG_NAME).apply();
        changed();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != PICK_PNG || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) { }
        prefs.edit()
                .putString(Prefs.PNG_URI, uri.toString())
                .putString(Prefs.PNG_NAME, displayName(uri))
                .putBoolean(Prefs.PNG_ON, true)
                .apply();
        changed();
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) { }
        return uri.getLastPathSegment();
    }

    private void changed() {
        refresh();
        pushToService();
    }

    private void pushToService() {
        if (OverlayService.running) {
            startService(new Intent(this, OverlayService.class).setAction(OverlayService.ACTION_REFRESH));
        }
    }

    // ------------------------------------------------------------- helpers

    private TextView text(String s, int sp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(Color.WHITE);
        return t;
    }

    private TextView header(String s) {
        TextView t = text(s.toUpperCase(), 12);
        t.setTextColor(0xFF888888);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(28), 0, 0);
        return t;
    }

    private TextView button(String s, View.OnClickListener l) {
        TextView b = text(s, 15);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(12), dp(12), dp(12), dp(12));
        b.setOnClickListener(l);
        paint(b, false, ACCENT);
        return b;
    }

    private void paint(TextView b, boolean on, int color) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(on ? color : 0xFF2A2A2A);
        b.setBackground(bg);
    }

    private LinearLayout.LayoutParams block(int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(topDp);
        return lp;
    }

    private LinearLayout.LayoutParams weighted(int leftMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
        lp.leftMargin = leftMargin;
        return lp;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
