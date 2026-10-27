package com.mobilepreview.viewer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.Display;
import android.view.Surface;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Draws a simulated camera cutout (punch hole, notch, Dynamic Island...) and screen corners
 * of another phone on top of the preview, plus the "danger zone": everything any of them covers.
 *
 * Geometry comes from cutouts.json in the reference phone's own units and is scaled by the
 * ratio of screen widths, so a cutout covers the same share of the picture on any phone.
 */
public final class CutoutOverlay extends View {

    public static final class Profile {
        public final String id, shortName, kind, device;
        public final boolean approx;
        final float w, rt, rb;
        final Path path;          // in reference units, origin already moved to the top-left
        public final boolean empty;

        Profile(JSONObject o, boolean russian) {
            id = o.optString("id");
            shortName = o.optString(russian ? "short" : "short_en", o.optString("short", id));
            kind = o.optString(russian ? "kind" : "kind_en", o.optString("kind"));
            device = o.optString(russian ? "device" : "device_en", o.optString("device"));
            approx = o.optBoolean("approx");
            w = (float) o.optDouble("w", 1080);
            rt = (float) o.optDouble("rt", 0);
            rb = (float) o.optDouble("rb", 0);
            String d = o.optString("path", "");
            float originX = d.contains("@left") ? 0 : d.contains("@right") ? w : w / 2;
            d = d.replaceAll("@\\w+", "").trim();
            empty = d.isEmpty();
            Path raw = SvgPath.parse(d);
            raw.offset(originX, 0);
            // Merge sub-paths so halves of a notch don't leave a seam between them.
            path = new Path();
            path.op(raw, new Path(), Path.Op.UNION);
        }

        public String title() {
            return kind + (empty ? "" : " · " + device + (approx ? " ≈" : ""));
        }
    }

    private final List<Profile> profiles = new ArrayList<>();
    private int index;
    private boolean danger;
    private boolean corners = true;

    private final Paint black = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dangerFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dangerStroke = new Paint(Paint.ANTI_ALIAS_FLAG);

    private Path dangerPath;
    private String dangerKey;

    public CutoutOverlay(Context context) {
        super(context);
        float density = getResources().getDisplayMetrics().density;
        black.setColor(Color.BLACK);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(density);
        rim.setColor(0x33FFFFFF);
        dangerFill.setColor(0x66FF2020);
        dangerStroke.setStyle(Paint.Style.STROKE);
        dangerStroke.setStrokeWidth(1.5f * density);
        dangerStroke.setColor(0xEEFF3030);
        loadProfiles(context);
    }

    private void loadProfiles(Context context) {
        boolean russian = "ru".equals(context.getResources().getConfiguration().getLocales().get(0).getLanguage());
        try (InputStream in = context.getAssets().open("cutouts.json")) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
            JSONArray arr = new JSONArray(new String(buf.toByteArray(), StandardCharsets.UTF_8));
            for (int i = 0; i < arr.length(); i++) profiles.add(new Profile(arr.getJSONObject(i), russian));
        } catch (Exception e) {
            profiles.clear();
        }
        if (profiles.isEmpty()) {
            try {
                profiles.add(new Profile(new JSONObject("{\"id\":\"none\",\"short\":\"Без выреза\",\"short_en\":\"No cutout\",\"kind\":\"Без выреза\",\"kind_en\":\"No cutout\"}"), russian));
            } catch (Exception ignored) { }
        }
    }

    public List<Profile> profiles() { return profiles; }
    public int index() { return index; }
    public Profile current() { return profiles.get(index); }
    public boolean danger() { return danger; }
    public boolean corners() { return corners; }

    public void setIndex(int i) {
        int n = profiles.size();
        index = ((i % n) + n) % n;
        invalidate();
    }

    public void selectById(String id) {
        for (int i = 0; i < profiles.size(); i++) {
            if (profiles.get(i).id.equals(id)) {
                setIndex(i);
                return;
            }
        }
    }

    public void setDanger(boolean on) { danger = on; invalidate(); }
    public void setCorners(boolean on) { corners = on; invalidate(); }

    // ------------------------------------------------------------------ drawing

    private int rotation() {
        Display d = getDisplay();
        return d == null ? Surface.ROTATION_0 : d.getRotation();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        int rot = rotation();
        boolean sideways = rot == Surface.ROTATION_90 || rot == Surface.ROTATION_270;
        // Work in the phone's natural portrait frame: cutouts live on its top edge.
        float pw = sideways ? h : w, ph = sideways ? w : h;

        canvas.save();
        switch (rot) {
            case Surface.ROTATION_90:   // top of the phone is now on the left
                canvas.translate(0, h);
                canvas.rotate(-90);
                break;
            case Surface.ROTATION_180:
                canvas.translate(w, h);
                canvas.rotate(180);
                break;
            case Surface.ROTATION_270:  // top of the phone is now on the right
                canvas.translate(w, 0);
                canvas.rotate(90);
                break;
            default:
                break;
        }

        Profile p = current();
        if (corners) canvas.drawPath(cornerMask(p, pw, ph), black);
        if (!p.empty) {
            Path cut = scaled(p, pw);
            canvas.drawPath(cut, black);
            canvas.drawPath(cut, rim);
        }
        if (danger) {
            Path zone = dangerZone(pw, ph);
            canvas.drawPath(zone, dangerFill);
            canvas.drawPath(zone, dangerStroke);
        }
        canvas.restore();
    }

    private static Path scaled(Profile p, float screenWidth) {
        Matrix m = new Matrix();
        float s = screenWidth / p.w;
        m.setScale(s, s);
        Path out = new Path();
        p.path.transform(m, out);
        return out;
    }

    private static Path cornerMask(Profile p, float pw, float ph) {
        float s = pw / p.w;
        float t = p.rt * s, b = p.rb * s;
        Path mask = new Path();
        if (t <= 0 && b <= 0) return mask;
        mask.addRect(0, 0, pw, ph, Path.Direction.CW);
        Path screen = new Path();
        screen.addRoundRect(new RectF(0, 0, pw, ph), new float[]{t, t, t, t, b, b, b, b}, Path.Direction.CW);
        mask.op(screen, Path.Op.DIFFERENCE);
        return mask;
    }

    /** Union of every profile's cutout (and corners, when they're shown). */
    private Path dangerZone(float pw, float ph) {
        String key = pw + "x" + ph + ":" + corners;
        if (dangerPath != null && key.equals(dangerKey)) return dangerPath;
        Path zone = new Path();
        for (Profile p : profiles) {
            if (!p.empty) zone.op(scaled(p, pw), Path.Op.UNION);
            if (corners) zone.op(cornerMask(p, pw, ph), Path.Op.UNION);
        }
        dangerPath = zone;
        dangerKey = key;
        return zone;
    }
}
