package com.mobilepreview.overlay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PixelFormat;
import android.graphics.drawable.Icon;
import android.hardware.input.InputManager;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.mobilepreview.viewer.CutoutOverlay;

import java.io.InputStream;

/**
 * Keeps a full-screen, touch-through window on top of every app: the simulated cutout of the
 * chosen phone, the danger zone and an optional PNG. Controlled from the notification.
 */
public class OverlayService extends Service {

    static final String ACTION_SHOW = "show";
    static final String ACTION_STOP = "stop";
    static final String ACTION_NEXT = "next";
    static final String ACTION_PREV = "prev";
    static final String ACTION_REFRESH = "refresh";

    private static final String CHANNEL = "overlay";
    private static final int NOTIFICATION_ID = 1;

    static volatile boolean running;

    private WindowManager wm;
    private SharedPreferences prefs;
    private FrameLayout root;
    private ImageView png;
    private CutoutOverlay cutouts;
    private String loadedPng = "";

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        prefs = Prefs.get(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null || intent.getAction() == null ? ACTION_SHOW : intent.getAction();
        if (action.equals(ACTION_STOP) || !Settings.canDrawOverlays(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startForegroundCompat(buildNotification()); // must happen quickly after startForegroundService
        ensureWindow();

        switch (action) {
            case ACTION_NEXT:
            case ACTION_PREV:
                cutouts.setIndex(cutouts.index() + (action.equals(ACTION_NEXT) ? 1 : -1));
                prefs.edit().putString(Prefs.PROFILE, cutouts.current().id).apply();
                break;
            default:
                break;
        }
        apply();
        running = true;
        startForegroundCompat(buildNotification());
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (root != null) {
            try { wm.removeView(root); } catch (Exception ignored) { }
            root = null;
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------------ window

    private void ensureWindow() {
        if (root != null) return;
        root = new FrameLayout(this);
        png = new ImageView(this);
        root.addView(png, new FrameLayout.LayoutParams(-1, -1));
        cutouts = new CutoutOverlay(this);
        root.addView(cutouts, new FrameLayout.LayoutParams(-1, -1));

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        // Cover the whole panel, including the real camera cutout and the system bars.
        if (Build.VERSION.SDK_INT >= 30) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            lp.setFitInsetsTypes(0);
        } else if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        // Android 12+ only lets touches through an overlay that is at most this opaque.
        if (Build.VERSION.SDK_INT >= 31) {
            float max = ((InputManager) getSystemService(INPUT_SERVICE)).getMaximumObscuringOpacityForTouch();
            lp.alpha = Math.min(max, 0.8f);
        }
        lp.setTitle("PS Overlay");
        wm.addView(root, lp);
    }

    private void apply() {
        cutouts.selectById(prefs.getString(Prefs.PROFILE, "none"));
        cutouts.setDanger(prefs.getBoolean(Prefs.DANGER, true));
        cutouts.setCorners(prefs.getBoolean(Prefs.CORNERS, true));

        String uri = prefs.getString(Prefs.PNG_URI, "");
        if (!uri.equals(loadedPng)) {
            png.setImageBitmap(uri.isEmpty() ? null : loadPng(Uri.parse(uri)));
            loadedPng = uri;
        }
        png.setVisibility(prefs.getBoolean(Prefs.PNG_ON, true) ? android.view.View.VISIBLE : android.view.View.GONE);
        png.setAlpha(prefs.getInt(Prefs.PNG_ALPHA, 60) / 100f);
        png.setScaleType(prefs.getBoolean(Prefs.PNG_STRETCH, true)
                ? ImageView.ScaleType.FIT_XY : ImageView.ScaleType.FIT_CENTER);
    }

    private Bitmap loadPng(Uri uri) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, o);
            }
            DisplayMetrics dm = getResources().getDisplayMetrics();
            int limit = 2 * Math.max(dm.widthPixels, dm.heightPixels);
            o.inSampleSize = 1;
            while (Math.max(o.outWidth, o.outHeight) / o.inSampleSize > limit) o.inSampleSize *= 2;
            o.inJustDecodeBounds = false;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                return BitmapFactory.decodeStream(in, null, o);
            }
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------- notification

    private void startForegroundCompat(Notification n) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    private Notification buildNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, getString(R.string.overlay_channel), NotificationManager.IMPORTANCE_LOW));
        }
        String title = cutouts == null ? "PS Overlay" : cutouts.current().title();
        boolean danger = prefs.getBoolean(Prefs.DANGER, true);
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(getString(danger ? R.string.danger_shown : R.string.danger_hidden))
                .setOngoing(true)
                .setContentIntent(PendingIntent.getActivity(this, 0,
                        new Intent(this, OverlayActivity.class), PendingIntent.FLAG_IMMUTABLE))
                // Android shows at most three buttons; the rest lives in the app.
                .addAction(action(getString(R.string.previous_cutout), ACTION_PREV, 1))
                .addAction(action(getString(R.string.next_cutout), ACTION_NEXT, 2))
                .addAction(action(getString(R.string.stop), ACTION_STOP, 3));
        return b.build();
    }

    private Notification.Action action(String label, String act, int code) {
        PendingIntent pi = PendingIntent.getService(this, code,
                new Intent(this, OverlayService.class).setAction(act), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat), label, pi).build();
    }
}
