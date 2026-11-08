package io.github.bavarianprayoga.serene;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Bundled data exported from the web build: wallpapers, per-photo theme, fonts and brand marks. */
final class Assets {
    /** One bundled photo and the overlay/ink values the web build computed for it. */
    static final class Wall {
        String id, name;
        float[] dim = new float[3];
        float col, veil, uRest, uPeer, halo;
        float[] utTr = new float[2], utBr = new float[2];
        int ink, clockInk, utilInk;
        boolean clockDark;
    }

    /** Visible ink box of a 240px brand mark, as FrameIconMetrics. */
    static final class Metrics {
        final int n, x, y, w, h;
        final float fill;
        Metrics(int n, int x, int y, int w, int h, float fill) { this.n = n; this.x = x; this.y = y; this.w = w; this.h = h; this.fill = fill; }
    }

    final List<Wall> walls = new ArrayList<>();
    final Map<String, Metrics> brandMetrics = new HashMap<>();
    private final Map<String, Bitmap> brandMarks = new HashMap<>();
    private final Map<String, Typeface> faces = new HashMap<>();
    private final AssetManager am;
    JSONObject bootstrap;

    Assets(Context context) {
        am = context.getAssets();
        try {
            JSONArray list = new JSONArray(text("wallpapers.json"));
            JSONObject theme = new JSONObject(text("theme.json"));
            for (int i = 0; i < list.length(); i++) {
                JSONObject src = list.getJSONObject(i), t = theme.getJSONObject(src.getString("id"));
                Wall w = new Wall();
                w.id = src.getString("id");
                w.name = src.getString("name");
                floats(t.getJSONArray("dim"), w.dim);
                floats(t.getJSONArray("utTr"), w.utTr);
                floats(t.getJSONArray("utBr"), w.utBr);
                w.col = (float) t.getDouble("col");
                w.veil = (float) t.getDouble("veil");
                w.uRest = (float) t.getDouble("uRest");
                w.uPeer = (float) t.getDouble("uPeer");
                w.ink = Color.parseColor(t.getString("ink"));
                w.clockInk = Color.parseColor(t.getString("clockInk"));
                w.utilInk = Color.parseColor(t.getString("utilInk"));
                w.clockDark = t.getBoolean("clockDark");
                w.halo = (float) t.getDouble("halo");
                walls.add(w);
            }
            JSONObject m = new JSONObject(text("brands.json"));
            for (java.util.Iterator<String> it = m.keys(); it.hasNext(); ) {
                String k = it.next();
                JSONObject o = m.getJSONObject(k);
                brandMetrics.put(k, new Metrics(o.getInt("n"), o.getInt("x"), o.getInt("y"), o.getInt("w"), o.getInt("h"), (float) o.getDouble("fill")));
            }
            try { bootstrap = new JSONObject(text("bootstrap.json")); } catch (Exception none) { bootstrap = new JSONObject(); }
        } catch (Exception e) {
            throw new IllegalStateException("Bundled Serene data is unreadable; rebuild the APK.", e);
        }
    }

    Wall wall(String id) {
        for (Wall w : walls) if (w.id.equals(id)) return w;
        return walls.get(walls.size() - 1);
    }

    int wallIndex(String id) {
        for (int i = 0; i < walls.size(); i++) if (walls.get(i).id.equals(id)) return i;
        return walls.size() - 1;
    }

    /** Variable fonts at an exact weight. Geist is drawn 1.408% larger, as the web build's size-adjust. */
    Typeface face(String family, int weight) {
        String key = family + weight;
        Typeface t = faces.get(key);
        if (t == null) {
            t = new Typeface.Builder(am, "fonts/" + family + ".ttf").setFontVariationSettings("'wght' " + weight).build();
            faces.put(key, t);
        }
        return t;
    }

    /** Brand marks are coverage-only (ALPHA_8) and tinted with the current ink when drawn. */
    Bitmap brand(String id) {
        if (brandMarks.containsKey(id)) return brandMarks.get(id);
        Bitmap b = null;
        if (brandMetrics.containsKey(id)) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.ALPHA_8;
            try (InputStream in = am.open("brands/" + id + ".png")) { b = BitmapFactory.decodeStream(in, null, o); } catch (Exception ignored) { }
            if (b != null && b.getConfig() != Bitmap.Config.ALPHA_8) b = b.extractAlpha();
        }
        brandMarks.put(id, b);
        return b;
    }

    /** sample > 1 decodes at a fraction of the stored size. */
    Bitmap image(String path, Bitmap.Config config) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = config;
        try (InputStream in = am.open(path)) { return BitmapFactory.decodeStream(in, null, o); } catch (Exception e) { return null; }
    }

    String text(String path) throws Exception {
        try (InputStream in = am.open(path); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static void floats(JSONArray a, float[] out) throws Exception {
        for (int i = 0; i < out.length && i < a.length(); i++) out[i] = (float) a.getDouble(i);
    }
}
