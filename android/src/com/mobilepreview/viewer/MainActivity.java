package com.mobilepreview.viewer;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int PORT = 8765;
    private static final int MAX_BODY = 80 * 1024 * 1024;
    private static final long MENU_AUTO_HIDE_MS = 10000;
    private static final int ACCENT = 0xFF2F80ED;
    private static final int DANGER = 0xFFD63B3B;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    private ImageView imageView;
    private CutoutOverlay overlay;
    private TextView waiting;     // address card, shown until the first picture arrives
    private TextView toast;       // cutout name when flipping with the menu hidden
    private LinearLayout menu;
    private TextView menuInfo, cutTitle, cutSubtitle;
    private TextView fitWidthBtn, fitHeightBtn, dangerBtn, cornersBtn;
    private HorizontalScrollView chipScroll;
    private final List<TextView> chips = new ArrayList<>();

    private ServerSocket serverSocket;
    private volatile boolean running;
    private String lastFrameTime;
    private DisplayManager.DisplayListener displayListener;

    private Bitmap bitmap;
    private final Matrix matrix = new Matrix();
    private ScaleGestureDetector scaleDetector;
    private GestureDetector gestureDetector;
    private boolean userZoomed;
    private boolean fitByHeight;

    private final Runnable hideMenu = () -> setMenuVisible(false);
    private final Runnable hideToast = () -> toast.animate().alpha(0f).setDuration(250).start();

    // ---------------------------------------------------------------- UI

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getPreferences(MODE_PRIVATE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 28) {
            // Draw under this phone's own camera cutout too, so the picture really goes edge to edge.
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = Build.VERSION.SDK_INT >= 30
                    ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        imageView = new ImageView(this);
        imageView.setScaleType(ImageView.ScaleType.MATRIX);
        root.addView(imageView, new FrameLayout.LayoutParams(-1, -1));

        overlay = new CutoutOverlay(this);
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));

        waiting = label(15, 0xFFDDDDDD);
        waiting.setGravity(Gravity.CENTER);
        waiting.setPadding(dp(14), dp(14), dp(14), dp(14));
        waiting.setBackground(rounded(0xD0202020, dp(14)));
        FrameLayout.LayoutParams wlp = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
        wlp.setMargins(dp(24), dp(24), dp(24), dp(24));
        root.addView(waiting, wlp);

        toast = label(14, 0xFFFFFFFF);
        toast.setPadding(dp(14), dp(8), dp(14), dp(8));
        toast.setBackground(rounded(0xD0202020, dp(16)));
        toast.setAlpha(0f);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        tlp.bottomMargin = dp(36);
        root.addView(toast, tlp);

        menu = buildMenu();
        root.addView(menu, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));

        setContentView(root);

        fitByHeight = prefs.getBoolean("fitByHeight", false);
        overlay.selectById(prefs.getString("profile", "none"));
        overlay.setDanger(prefs.getBoolean("danger", false));
        overlay.setCorners(prefs.getBoolean("corners", true));
        layoutMenu();
        setMenuVisible(false);

        setupGestures();
        hideSystemBars();
        watchRotation();
        startServer();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        layoutMenu();
        imageView.post(this::fitToScreen);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (bitmap == null) showWaiting();
    }

    @Override
    protected void onDestroy() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) { }
        if (displayListener != null) {
            ((DisplayManager) getSystemService(DISPLAY_SERVICE)).unregisterDisplayListener(displayListener);
        }
        super.onDestroy();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Volume buttons flip through cutouts without opening the menu.
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            stepProfile(1);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            stepProfile(-1);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN && menu.getVisibility() == View.VISIBLE) {
            scheduleMenuHide();
        }
        return super.dispatchTouchEvent(ev);
    }

    @SuppressWarnings("deprecation")
    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    /** The cutout overlay depends on which side the phone's top edge is on. */
    private void watchRotation() {
        displayListener = new DisplayManager.DisplayListener() {
            @Override public void onDisplayAdded(int displayId) { }
            @Override public void onDisplayRemoved(int displayId) { }
            @Override public void onDisplayChanged(int displayId) { overlay.invalidate(); }
        };
        ((DisplayManager) getSystemService(DISPLAY_SERVICE)).registerDisplayListener(displayListener, ui);
    }

    private void showWaiting() {
        waiting.setText(getString(R.string.waiting, addresses("\n")));
        waiting.setVisibility(View.VISIBLE);
    }

    private String addresses(String separator) {
        List<String> ips = localAddresses();
        if (ips.isEmpty()) return getString(R.string.no_network);
        StringBuilder sb = new StringBuilder();
        for (String ip : ips) {
            if (sb.length() > 0) sb.append(separator);
            sb.append(ip).append(':').append(PORT);
        }
        return sb.toString();
    }

    // -------------------------------------------------------------- menu

    private LinearLayout buildMenu() {
        LinearLayout m = new LinearLayout(this);
        m.setOrientation(LinearLayout.VERTICAL);
        m.setPadding(dp(12), dp(10), dp(12), dp(12));
        m.setBackground(rounded(0xEB161616, dp(18)));
        m.setClickable(true); // taps on the menu must not reach the picture

        menuInfo = label(12, 0xFF8A8A8A);
        m.addView(menuInfo);

        LinearLayout cutRow = row();
        TextView prev = pill("‹", v -> stepProfile(-1));
        TextView next = pill("›", v -> stepProfile(1));
        prev.setTextSize(22);
        next.setTextSize(22);
        paint(prev, false, ACCENT);
        paint(next, false, ACCENT);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        cutTitle = label(16, 0xFFFFFFFF);
        cutTitle.setTypeface(Typeface.DEFAULT_BOLD);
        cutTitle.setGravity(Gravity.CENTER);
        cutSubtitle = label(12, 0xFF9A9A9A);
        cutSubtitle.setGravity(Gravity.CENTER);
        titles.addView(cutTitle);
        titles.addView(cutSubtitle);
        cutRow.addView(prev);
        cutRow.addView(titles, new LinearLayout.LayoutParams(0, -2, 1f));
        cutRow.addView(next);
        m.addView(cutRow, rowParams());

        chipScroll = new HorizontalScrollView(this);
        chipScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chipRow = new LinearLayout(this);
        List<CutoutOverlay.Profile> list = overlay.profiles();
        for (int i = 0; i < list.size(); i++) {
            final int idx = i;
            TextView chip = pill(list.get(i).shortName, v -> selectProfile(idx, false));
            chip.setTextSize(13);
            chip.setPadding(dp(12), dp(6), dp(12), dp(6));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = dp(6);
            chipRow.addView(chip, lp);
            chips.add(chip);
        }
        chipScroll.addView(chipRow);
        m.addView(chipScroll, rowParams());

        LinearLayout fitRow = row();
        TextView fitLabel = label(13, 0xFF9A9A9A);
        fitLabel.setText(getString(R.string.fit));
        fitLabel.setPadding(0, 0, dp(4), 0);
        fitWidthBtn = pill(getString(R.string.fit_width), v -> setFit(false));
        fitHeightBtn = pill(getString(R.string.fit_height), v -> setFit(true));
        fitRow.addView(fitLabel);
        fitRow.addView(fitWidthBtn, gap());
        fitRow.addView(fitHeightBtn, gap());
        m.addView(fitRow, rowParams());

        LinearLayout toggles = row();
        dangerBtn = pill(getString(R.string.danger_zone), v -> {
            overlay.setDanger(!overlay.danger());
            savePrefs();
            refreshMenu();
        });
        cornersBtn = pill(getString(R.string.screen_corners), v -> {
            overlay.setCorners(!overlay.corners());
            savePrefs();
            refreshMenu();
        });
        toggles.addView(dangerBtn);
        toggles.addView(cornersBtn, gap());
        m.addView(toggles, rowParams());

        TextView hint = label(11, 0xFF7A7A7A);
        hint.setText(getString(R.string.gesture_hint));
        m.addView(hint, rowParams());
        return m;
    }

    private void layoutMenu() {
        int screenW = getResources().getDisplayMetrics().widthPixels;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) menu.getLayoutParams();
        lp.width = Math.min(screenW - dp(20), dp(520));
        lp.bottomMargin = dp(16);
        menu.setLayoutParams(lp);
    }

    private void refreshMenu() {
        CutoutOverlay.Profile p = overlay.current();
        int n = overlay.profiles().size();
        cutTitle.setText(p.kind);
        cutSubtitle.setText((p.empty ? "" : p.device + (p.approx ? " ≈" : "") + " · ")
                + getString(R.string.profile_count, overlay.index() + 1, n));
        for (int i = 0; i < chips.size(); i++) paint(chips.get(i), i == overlay.index(), ACCENT);
        paint(fitWidthBtn, !fitByHeight, ACCENT);
        paint(fitHeightBtn, fitByHeight, ACCENT);
        paint(dangerBtn, overlay.danger(), DANGER);
        paint(cornersBtn, overlay.corners(), ACCENT);
        menuInfo.setText(getString(R.string.plugin_address, addresses(", "))
                + (lastFrameTime == null ? "" : getString(R.string.frame_time, lastFrameTime)));
    }

    private void setMenuVisible(boolean visible) {
        ui.removeCallbacks(hideMenu);
        menu.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (visible) {
            refreshMenu();
            scrollChipIntoView();
            toast.setAlpha(0f);
            scheduleMenuHide();
        }
    }

    private void scheduleMenuHide() {
        ui.removeCallbacks(hideMenu);
        ui.postDelayed(hideMenu, MENU_AUTO_HIDE_MS);
    }

    private void scrollChipIntoView() {
        chipScroll.post(() -> {
            TextView chip = chips.get(overlay.index());
            chipScroll.smoothScrollTo(Math.max(0, chip.getLeft() - dp(48)), 0);
        });
    }

    private void stepProfile(int delta) {
        selectProfile(overlay.index() + delta, true);
    }

    private void selectProfile(int i, boolean announce) {
        overlay.setIndex(i);
        savePrefs();
        refreshMenu();
        scrollChipIntoView();
        if (announce && menu.getVisibility() != View.VISIBLE) {
            toast.setText(overlay.current().title());
            toast.animate().cancel();
            toast.setAlpha(1f);
            ui.removeCallbacks(hideToast);
            ui.postDelayed(hideToast, 1500);
        }
    }

    private void setFit(boolean byHeight) {
        fitByHeight = byHeight;
        savePrefs();
        refreshMenu();
        fitToScreen();
    }

    private void savePrefs() {
        prefs.edit()
                .putBoolean("fitByHeight", fitByHeight)
                .putString("profile", overlay.current().id)
                .putBoolean("danger", overlay.danger())
                .putBoolean("corners", overlay.corners())
                .apply();
    }

    private TextView label(int sp, int color) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private TextView pill(String text, View.OnClickListener onClick) {
        TextView b = label(14, 0xFFEEEEEE);
        b.setText(text);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(14), dp(8), dp(14), dp(8));
        b.setOnClickListener(onClick);
        return b;
    }

    private void paint(TextView b, boolean on, int onColor) {
        b.setBackground(rounded(on ? onColor : 0x26FFFFFF, dp(18)));
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    private LinearLayout.LayoutParams rowParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(10);
        return lp;
    }

    private LinearLayout.LayoutParams gap() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = dp(8);
        return lp;
    }

    private static GradientDrawable rounded(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ---------------------------------------------------------- gestures

    private void setupGestures() {
        scaleDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                if (bitmap == null) return false;
                matrix.postScale(d.getScaleFactor(), d.getScaleFactor(), d.getFocusX(), d.getFocusY());
                userZoomed = true;
                clampAndApply();
                return true;
            }
        });
        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (bitmap == null) return false;
                matrix.postTranslate(-dx, -dy);
                userZoomed = true;
                clampAndApply();
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                float w = imageView.getWidth();
                if (e.getX() < w * 0.25f) {
                    stepProfile(-1);
                } else if (e.getX() > w * 0.75f) {
                    stepProfile(1);
                } else {
                    setMenuVisible(menu.getVisibility() != View.VISIBLE);
                }
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (bitmap == null) return false;
                float[] v = new float[9];
                matrix.getValues(v);
                float fit = fitScale();
                if (Math.abs(v[Matrix.MSCALE_X] - fit) < 0.01f) {
                    // fit -> 1:1 around the tapped point
                    float k = 1f / v[Matrix.MSCALE_X];
                    matrix.postScale(k, k, e.getX(), e.getY());
                    userZoomed = true;
                    clampAndApply();
                } else {
                    fitToScreen();
                }
                return true;
            }
        });
        imageView.setOnTouchListener((v, event) -> {
            scaleDetector.onTouchEvent(event);
            gestureDetector.onTouchEvent(event);
            return true;
        });
    }

    private float fitScale() {
        float vw = imageView.getWidth(), vh = imageView.getHeight();
        if (bitmap == null || vw == 0 || vh == 0) return 1f;
        return fitByHeight ? vh / bitmap.getHeight() : vw / bitmap.getWidth();
    }

    private void fitToScreen() {
        if (bitmap == null) return;
        float s = fitScale();
        matrix.reset();
        matrix.postScale(s, s);
        matrix.postTranslate((imageView.getWidth() - bitmap.getWidth() * s) / 2f,
                (imageView.getHeight() - bitmap.getHeight() * s) / 2f);
        userZoomed = false;
        imageView.setImageMatrix(matrix);
    }

    private void clampAndApply() {
        float[] v = new float[9];
        matrix.getValues(v);
        float fit = fitScale();
        float scale = v[Matrix.MSCALE_X];
        float min = fit, max = Math.max(fit, 1f) * 8f;
        if (scale < min || scale > max) {
            float k = (scale < min ? min : max) / scale;
            matrix.postScale(k, k, imageView.getWidth() / 2f, imageView.getHeight() / 2f);
        }
        RectF r = new RectF(0, 0, bitmap.getWidth(), bitmap.getHeight());
        matrix.mapRect(r);
        float vw = imageView.getWidth(), vh = imageView.getHeight();
        float tx = 0, ty = 0;
        if (r.width() <= vw) tx = (vw - r.width()) / 2f - r.left;
        else if (r.left > 0) tx = -r.left;
        else if (r.right < vw) tx = vw - r.right;
        if (r.height() <= vh) ty = (vh - r.height()) / 2f - r.top;
        else if (r.top > 0) ty = -r.top;
        else if (r.bottom < vh) ty = vh - r.bottom;
        matrix.postTranslate(tx, ty);
        imageView.setImageMatrix(matrix);
    }

    private void showFrame(Bitmap next) {
        boolean sameSize = bitmap != null
                && bitmap.getWidth() == next.getWidth()
                && bitmap.getHeight() == next.getHeight();
        bitmap = next;
        imageView.setImageBitmap(next);
        // Keep the user's zoom while retouching the same document; refit when the image changes size.
        if (sameSize && userZoomed) imageView.setImageMatrix(matrix);
        else fitToScreen();
        waiting.setVisibility(View.GONE);
        lastFrameTime = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        if (menu.getVisibility() == View.VISIBLE) refreshMenu();
    }

    // ------------------------------------------------------------- server

    private void startServer() {
        running = true;
        Thread t = new Thread(() -> {
            try {
                serverSocket = new ServerSocket();
                serverSocket.setReuseAddress(true);
                serverSocket.bind(new InetSocketAddress(PORT));
                while (running) {
                    Socket s = serverSocket.accept();
                    new Thread(() -> handle(s), "client").start();
                }
            } catch (IOException e) {
                if (running) ui.post(() -> {
                    waiting.setText(getString(R.string.server_error, e.getMessage()));
                    waiting.setVisibility(View.VISIBLE);
                });
            }
        }, "server");
        t.setDaemon(true);
        t.start();
    }

    private void handle(Socket s) {
        try (Socket socket = s) {
            socket.setSoTimeout(15000);
            InputStream in = new BufferedInputStream(socket.getInputStream(), 64 * 1024);
            OutputStream out = socket.getOutputStream();

            String requestLine = readLine(in);
            if (requestLine == null) return;
            int contentLength = 0;
            String wsKey = null;
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int c = line.indexOf(':');
                if (c <= 0) continue;
                String name = line.substring(0, c).trim();
                String value = line.substring(c + 1).trim();
                if (name.equalsIgnoreCase("Content-Length")) contentLength = Integer.parseInt(value);
                if (name.equalsIgnoreCase("Sec-WebSocket-Key")) wsKey = value;
            }
            String[] parts = requestLine.split(" ");
            String method = parts[0];
            String path = parts.length > 1 ? parts[1] : "/";

            // Photoshop only lets plugins make plain-http requests to the computer itself;
            // WebSocket is allowed to go anywhere, so the plugin sends frames over it.
            if (wsKey != null) {
                serveWebSocket(socket, in, out, wsKey);
                return;
            }

            if (method.equals("OPTIONS")) {
                respond(out, 204, "");
                return;
            }
            if (method.equals("POST") && path.startsWith("/frame")) {
                if (contentLength <= 0 || contentLength > MAX_BODY) {
                    respond(out, 400, "bad length");
                    return;
                }
                byte[] body = readFully(in, contentLength);
                Bitmap bmp = decode(body);
                if (bmp == null) {
                    respond(out, 400, "bad image");
                    return;
                }
                ui.post(() -> showFrame(bmp));
                respond(out, 200, "ok");
                return;
            }
            respond(out, 200, "PS Preview is running");
        } catch (Exception ignored) {
        }
    }

    private void serveWebSocket(Socket socket, InputStream in, OutputStream out, String key) throws Exception {
        String accept = Base64.encodeToString(MessageDigest.getInstance("SHA-1").digest(
                (key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)), Base64.NO_WRAP);
        out.write(("HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();
        socket.setSoTimeout(0); // the plugin keeps the connection open between edits

        ByteArrayOutputStream message = new ByteArrayOutputStream();
        while (running) {
            int b0 = in.read();
            int b1 = in.read();
            if (b0 < 0 || b1 < 0) return;
            boolean fin = (b0 & 0x80) != 0;
            int opcode = b0 & 0x0F;
            long len = b1 & 0x7F;
            if (len == 126) {
                len = ((long) readByte(in) << 8) | readByte(in);
            } else if (len == 127) {
                len = 0;
                for (int i = 0; i < 8; i++) len = (len << 8) | readByte(in);
            }
            if (len > MAX_BODY || message.size() + len > MAX_BODY) return;
            byte[] mask = (b1 & 0x80) != 0 ? readFully(in, 4) : null;
            byte[] payload = readFully(in, (int) len);
            if (mask != null) {
                for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
            }

            if (opcode == 8) { // close
                sendWsFrame(out, 8, new byte[0]);
                return;
            }
            if (opcode == 9) { // ping
                sendWsFrame(out, 10, payload);
                continue;
            }
            if (opcode == 10) continue; // pong
            message.write(payload);
            if (!fin) continue;

            Bitmap bmp = decode(message.toByteArray());
            message.reset();
            if (bmp != null) ui.post(() -> showFrame(bmp));
            sendWsFrame(out, 1, (bmp != null ? "ok" : "bad image").getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void sendWsFrame(OutputStream out, int opcode, byte[] payload) throws IOException {
        ByteArrayOutputStream f = new ByteArrayOutputStream();
        f.write(0x80 | opcode);
        if (payload.length < 126) {
            f.write(payload.length);
        } else {
            f.write(126);
            f.write(payload.length >> 8);
            f.write(payload.length & 0xFF);
        }
        f.write(payload);
        out.write(f.toByteArray());
        out.flush();
    }

    private static int readByte(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) throw new IOException("unexpected end of stream");
        return b;
    }

    private static Bitmap decode(byte[] body) {
        byte[] jpeg = body;
        if (body.length < 2 || (body[0] & 0xFF) != 0xFF || (body[1] & 0xFF) != 0xD8) {
            String text = new String(body, StandardCharsets.US_ASCII).trim();
            int comma = text.indexOf(',');
            if (comma >= 0 && comma < 100) text = text.substring(comma + 1);
            try {
                jpeg = Base64.decode(text, Base64.DEFAULT);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
    }

    private static void respond(OutputStream out, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        String status = code == 200 ? "OK" : code == 204 ? "No Content" : "Bad Request";
        String head = "HTTP/1.1 " + code + " " + status + "\r\n"
                + "Content-Type: text/plain; charset=utf-8\r\n"
                + "Content-Length: " + b.length + "\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n"
                + "Access-Control-Allow-Headers: Content-Type\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(b);
        out.flush();
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') buf.write(c);
            if (buf.size() > 8192) throw new IOException("header too long");
        }
        if (c == -1 && buf.size() == 0) return null;
        return buf.toString("ISO-8859-1");
    }

    private static byte[] readFully(InputStream in, int length) throws IOException {
        byte[] data = new byte[length];
        int off = 0;
        while (off < length) {
            int n = in.read(data, off, length - off);
            if (n < 0) throw new IOException("unexpected end of stream");
            off += n;
        }
        return data;
    }

    private static List<String> localAddresses() {
        List<String> result = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    // Not only Wi-Fi (192.168/10/172.16): Tailscale hands out 100.64.0.0/10,
                    // ZeroTier any range it likes. Skip only link-local junk.
                    if (a instanceof Inet4Address && !a.isLinkLocalAddress() && !a.isLoopbackAddress()) {
                        if (a.isSiteLocalAddress()) result.add(0, a.getHostAddress());
                        else result.add(a.getHostAddress());
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return result;
    }
}
