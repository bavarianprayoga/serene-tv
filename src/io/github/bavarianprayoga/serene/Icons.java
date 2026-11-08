package io.github.bavarianprayoga.serene;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import java.util.HashMap;
import java.util.Map;

/** The web build's 24x24 line icons, parsed from the same SVG path data and stroked natively. */
final class Icons {
    private static final Map<String, Path> CACHE = new HashMap<>();
    private static final String[][] SVG = {
            {"art", "R3.5,4.5,17,15,2|C9,9.8,1.5|Pm4.5 17.5 4.5-4.5 3 3 2.5-2.5 5 5"},
            {"apps", "R4,4,6.5,6.5,1.6|R13.5,4,6.5,6.5,1.6|R4,13.5,6.5,6.5,1.6|R13.5,13.5,6.5,6.5,1.6"},
            {"settings", "Pm9 3-.6 2.4-2 1.2L4 6l-2 3.5L3.8 11v2L2 14.5 4 18l2.4-.6 2 1.2L9 21h6l.6-2.4 2-1.2 2.4.6 2-3.5-1.8-1.5v-2L22 9.5 20 6l-2.4.6-2-1.2L15 3Z|C12,12,3.2"},
            {"home", "PM4 11 12 4.5l8 6.5v8a1 1 0 0 1-1 1h-4.5v-6h-5v6H5a1 1 0 0 1-1-1Z"},
            {"undo", "PM9 6 5 10l4 4|PM5 10h9.5a4.5 4.5 0 0 1 0 9H11"},
            {"check", "Pm5.5 12.5 4 4 9-9"},
            {"left", "Pm14.5 5-7 7 7 7"},
            {"right", "Pm9.5 5 7 7-7 7"},
            {"up", "Pm5 15.5 7-7 7 7"},
            {"down", "Pm5 8.5 7 7 7-7"},
            {"updown", "Pm7.5 9.5 4.5-4.5 4.5 4.5M7.5 14.5l4.5 4.5 4.5-4.5"},
            {"tv", "R3,4.5,18,12,2|PM8.5 20h7"},
            {"warn", "PM12 4 2.8 19.5h18.4Z|PM12 10v4.5M12 17.3v.1"},
            {"net0", "PM8.6 15.6a4.8 4.8 0 0 1 6.8 0"},
            {"net1", "PM5.4 12.4a9.3 9.3 0 0 1 13.2 0"},
            {"net2", "PM2.2 9.2a13.8 13.8 0 0 1 19.6 0"},
            {"tick", "Pm7.6 12.4 3 3 5.8-6.2"},
    };
    private static final Map<String, String> SRC = new HashMap<>();
    static { for (String[] s : SVG) SRC.put(s[0], s[1]); }

    static Path get(String name) {
        Path p = CACHE.get(name);
        if (p == null) { p = build(SRC.get(name)); CACHE.put(name, p); }
        return p;
    }

    /** Strokes an icon so its 24-unit box fills (x, y, size, size). Stroke width is in icon units. */
    static void draw(Canvas c, String name, float x, float y, float size, float stroke, Paint paint) {
        c.save();
        c.translate(x, y);
        float k = size / 24f;
        c.scale(k, k);
        Paint.Style style = paint.getStyle();
        float width = paint.getStrokeWidth();
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(stroke);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        c.drawPath(get(name), paint);
        paint.setStyle(style);
        paint.setStrokeWidth(width);
        c.restore();
    }

    private static Path build(String spec) {
        Path out = new Path();
        for (String part : spec.split("\\|")) {
            char kind = part.charAt(0);
            String body = part.substring(1);
            if (kind == 'R') {
                float[] v = nums(body);
                out.addRoundRect(new RectF(v[0], v[1], v[0] + v[2], v[1] + v[3]), v[4], v[4], Path.Direction.CW);
            } else if (kind == 'C') {
                float[] v = nums(body);
                out.addCircle(v[0], v[1], v[2], Path.Direction.CW);
            } else {
                parse(body, out);
            }
        }
        return out;
    }

    private static float[] nums(String s) {
        String[] p = s.split(",");
        float[] v = new float[p.length];
        for (int i = 0; i < p.length; i++) v[i] = Float.parseFloat(p[i]);
        return v;
    }

    // ---------- minimal SVG path data: M L H V Z A, absolute and relative ----------

    private static int pos;
    private static String src;

    private static void parse(String d, Path path) {
        src = d; pos = 0;
        float x = 0, y = 0, sx = 0, sy = 0;
        char cmd = 0;
        while (true) {
            skip();
            if (pos >= src.length()) break;
            char ch = src.charAt(pos);
            if (Character.isLetter(ch)) { cmd = ch; pos++; }
            else if (cmd == 'M') cmd = 'L';
            else if (cmd == 'm') cmd = 'l';
            boolean rel = Character.isLowerCase(cmd);
            switch (Character.toUpperCase(cmd)) {
                case 'M': {
                    float nx = num(), ny = num();
                    x = rel ? x + nx : nx; y = rel ? y + ny : ny;
                    path.moveTo(x, y); sx = x; sy = y;
                    break;
                }
                case 'L': {
                    float nx = num(), ny = num();
                    x = rel ? x + nx : nx; y = rel ? y + ny : ny;
                    path.lineTo(x, y);
                    break;
                }
                case 'H': { float nx = num(); x = rel ? x + nx : nx; path.lineTo(x, y); break; }
                case 'V': { float ny = num(); y = rel ? y + ny : ny; path.lineTo(x, y); break; }
                case 'Z': { path.close(); x = sx; y = sy; break; }
                case 'A': {
                    float rx = num(), ry = num(); num();
                    int large = (int) num(), sweep = (int) num();
                    float nx = num(), ny = num();
                    if (rel) { nx += x; ny += y; }
                    arc(path, x, y, nx, ny, rx, ry, large == 1, sweep == 1);
                    x = nx; y = ny;
                    break;
                }
                default: pos = src.length();
            }
        }
    }

    private static void skip() { while (pos < src.length() && (src.charAt(pos) == ' ' || src.charAt(pos) == ',')) pos++; }

    private static float num() {
        skip();
        int start = pos;
        if (pos < src.length() && (src.charAt(pos) == '-' || src.charAt(pos) == '+')) pos++;
        boolean dot = false;
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (Character.isDigit(c)) pos++;
            else if (c == '.' && !dot) { dot = true; pos++; }
            else break;
        }
        return Float.parseFloat(src.substring(start, pos));
    }

    /** Circular SVG arc (rx == ry in every icon) via centre parameterisation. */
    private static void arc(Path p, float x1, float y1, float x2, float y2, float rx, float ry, boolean large, boolean sweep) {
        double r = rx, dx = (x1 - x2) / 2.0, dy = (y1 - y2) / 2.0, d2 = dx * dx + dy * dy;
        if (d2 > r * r) r = Math.sqrt(d2);
        double f = Math.sqrt(Math.max(0, (r * r - d2) / d2)) * (large == sweep ? -1 : 1);
        double cx = (x1 + x2) / 2.0 + f * dy, cy = (y1 + y2) / 2.0 - f * dx;
        double a1 = Math.toDegrees(Math.atan2(y1 - cy, x1 - cx)), a2 = Math.toDegrees(Math.atan2(y2 - cy, x2 - cx));
        double delta = a2 - a1;
        if (sweep && delta < 0) delta += 360;
        if (!sweep && delta > 0) delta -= 360;
        p.arcTo(new RectF((float) (cx - r), (float) (cy - r), (float) (cx + r), (float) (cy + r)), (float) a1, (float) delta, false);
    }
}
