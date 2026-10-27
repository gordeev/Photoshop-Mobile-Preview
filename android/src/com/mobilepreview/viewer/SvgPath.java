package com.mobilepreview.viewer;

import android.graphics.Path;
import android.graphics.RectF;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal SVG path-data parser (M L H V C S Q T A Z, absolute and relative) —
 * the same format Android uses for config_mainBuiltInDisplayCutout.
 */
final class SvgPath {

    private static final Pattern TOKEN = Pattern.compile(
            "[MmLlHhVvCcSsQqTtAaZz]|[-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?");

    private final List<String> tokens = new ArrayList<>();
    private int pos;

    private SvgPath(String d) {
        Matcher m = TOKEN.matcher(d);
        while (m.find()) tokens.add(m.group());
    }

    static Path parse(String d) {
        Path p = new Path();
        try {
            new SvgPath(d).build(p);
        } catch (RuntimeException ignored) {
            // keep whatever was parsed before the error
        }
        return p;
    }

    private boolean hasNumber() {
        return pos < tokens.size() && !Character.isLetter(tokens.get(pos).charAt(0));
    }

    private float num() {
        return Float.parseFloat(tokens.get(pos++));
    }

    private void build(Path p) {
        float x = 0, y = 0, startX = 0, startY = 0;
        float ctrlX = 0, ctrlY = 0;      // last control point, for S / T reflection
        char cmd = 0, prevCmd = 0;
        while (pos < tokens.size()) {
            String tok = tokens.get(pos);
            if (Character.isLetter(tok.charAt(0))) {
                cmd = tok.charAt(0);
                pos++;
            } else if (cmd == 0 || cmd == 'Z' || cmd == 'z') {
                pos++;                    // stray number: skip it
                continue;
            }
            boolean rel = Character.isLowerCase(cmd);
            float ox = rel ? x : 0, oy = rel ? y : 0;
            char c = Character.toUpperCase(cmd);
            switch (c) {
                case 'M':
                    x = ox + num(); y = oy + num();
                    p.moveTo(x, y);
                    startX = x; startY = y;
                    cmd = rel ? 'l' : 'L'; // following pairs are implicit line-tos
                    break;
                case 'L':
                    x = ox + num(); y = oy + num();
                    p.lineTo(x, y);
                    break;
                case 'H':
                    x = ox + num();
                    p.lineTo(x, y);
                    break;
                case 'V':
                    y = oy + num();
                    p.lineTo(x, y);
                    break;
                case 'C': {
                    float x1 = ox + num(), y1 = oy + num();
                    ctrlX = ox + num(); ctrlY = oy + num();
                    x = ox + num(); y = oy + num();
                    p.cubicTo(x1, y1, ctrlX, ctrlY, x, y);
                    break;
                }
                case 'S': {
                    boolean smooth = prevCmd == 'C' || prevCmd == 'S';
                    float x1 = smooth ? 2 * x - ctrlX : x, y1 = smooth ? 2 * y - ctrlY : y;
                    ctrlX = ox + num(); ctrlY = oy + num();
                    x = ox + num(); y = oy + num();
                    p.cubicTo(x1, y1, ctrlX, ctrlY, x, y);
                    break;
                }
                case 'Q':
                    ctrlX = ox + num(); ctrlY = oy + num();
                    x = ox + num(); y = oy + num();
                    p.quadTo(ctrlX, ctrlY, x, y);
                    break;
                case 'T': {
                    boolean smooth = prevCmd == 'Q' || prevCmd == 'T';
                    ctrlX = smooth ? 2 * x - ctrlX : x;
                    ctrlY = smooth ? 2 * y - ctrlY : y;
                    x = ox + num(); y = oy + num();
                    p.quadTo(ctrlX, ctrlY, x, y);
                    break;
                }
                case 'A': {
                    float rx = num(), ry = num();
                    num();                // x-axis rotation: not used by any cutout
                    boolean large = num() != 0, sweep = num() != 0;
                    float nx = ox + num(), ny = oy + num();
                    arc(p, x, y, nx, ny, rx, ry, large, sweep);
                    x = nx; y = ny;
                    break;
                }
                case 'Z':
                    p.close();
                    x = startX; y = startY;
                    break;
                default:
                    pos++;
            }
            prevCmd = c;
        }
    }

    /** SVG endpoint arc -> center parameterization (SVG 1.1, appendix F.6.5). */
    private static void arc(Path p, float x1, float y1, float x2, float y2,
                            float rx, float ry, boolean large, boolean sweep) {
        if (x1 == x2 && y1 == y2) return;
        rx = Math.abs(rx);
        ry = Math.abs(ry);
        if (rx == 0 || ry == 0) {
            p.lineTo(x2, y2);
            return;
        }
        double dx = (x1 - x2) / 2.0, dy = (y1 - y2) / 2.0;
        double lambda = dx * dx / (rx * rx) + dy * dy / (ry * ry);
        if (lambda > 1) {
            rx *= Math.sqrt(lambda);
            ry *= Math.sqrt(lambda);
        }
        double rx2 = (double) rx * rx, ry2 = (double) ry * ry;
        double num = rx2 * ry2 - rx2 * dy * dy - ry2 * dx * dx;
        double den = rx2 * dy * dy + ry2 * dx * dx;
        double coef = (large == sweep ? -1 : 1) * Math.sqrt(Math.max(0, num / den));
        double cxp = coef * rx * dy / ry, cyp = -coef * ry * dx / rx;
        double cx = cxp + (x1 + x2) / 2.0, cy = cyp + (y1 + y2) / 2.0;
        double start = Math.toDegrees(Math.atan2((dy - cyp) / ry, (dx - cxp) / rx));
        double end = Math.toDegrees(Math.atan2((-dy - cyp) / ry, (-dx - cxp) / rx));
        double delta = end - start;
        if (sweep && delta < 0) delta += 360;
        if (!sweep && delta > 0) delta -= 360;
        p.arcTo(new RectF((float) (cx - rx), (float) (cy - ry), (float) (cx + rx), (float) (cy + ry)),
                (float) start, (float) delta, false);
    }
}
