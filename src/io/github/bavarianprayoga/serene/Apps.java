package io.github.bavarianprayoga.serene;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;

import java.io.File;
import java.io.FileOutputStream;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Launchable apps and their monochrome marks, as the web build's Bridge produced them. */
final class Apps {
    static final int MARK = 240, ICON = 192;
    /** Packages drawn with a bundled brand mark (device.js BRANDS). */
    private static final Map<String, String> BRANDS = new java.util.LinkedHashMap<>();
    static {
        String[] b = {"com.google.android.youtube.tv", "youtube", "com.spotify.tv.android", "spotify", "com.wbd.stream", "hbomax",
                "com.formulaone.production", "f1", "com.nordvpn.android", "nordvpn", "com.android.vending", "googleplay",
                "com.stremio.one", "stremio", "com.netflix.ninja", "netflix", "com.disney.disneyplus", "disneyplus",
                "com.amazon.amazonvideo.livingroom", "primevideo", "com.apple.atve.androidtv.appletv", "appletv"};
        for (int i = 0; i < b.length; i += 2) BRANDS.put(b[i], b[i + 1]);
    }

    static final class App {
        String pkg, name, brand, monogram;
        Bitmap mark;          // ALPHA_8 coverage, MARK x MARK, null when brand or no usable glyph
        Assets.Metrics metrics;
        Bitmap icon;          // full-colour fallback, only kept when there is no mark
    }

    private final Context context;
    private final File cache;
    final Map<String, App> byPkg = new HashMap<>();
    final List<App> alpha = new ArrayList<>();

    Apps(Context context) {
        this.context = context;
        cache = new File(context.getCacheDir(), "marks");
        cache.mkdirs();
    }

    /** Runs off the main thread; returns a fresh catalogue. */
    static Apps load(Context context, Assets assets) {
        Apps apps = new Apps(context);
        apps.scan(assets);
        return apps;
    }

    private void scan(Assets assets) {
        PackageManager pm = context.getPackageManager();
        Set<String> seen = new HashSet<>();
        seen.add(context.getPackageName());
        Set<String> keep = new HashSet<>();
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(category), 0)) {
                String pkg = ri.activityInfo.packageName;
                if (!seen.add(pkg)) continue;
                App a = new App();
                a.pkg = pkg;
                a.name = ri.loadLabel(pm).toString().trim();
                a.monogram = monogram(a.name);
                a.brand = BRANDS.get(pkg);
                if (a.brand != null && assets.brandMetrics.containsKey(a.brand)) {
                    a.metrics = assets.brandMetrics.get(a.brand);
                } else {
                    a.brand = null;
                    long updated = 0;
                    try { PackageInfo info = pm.getPackageInfo(pkg, 0); updated = info.lastUpdateTime; } catch (PackageManager.NameNotFoundException ignored) { }
                    String key = pkg + "_" + updated;
                    keep.add(key + ".mark");
                    keep.add(key + ".icon");
                    if (!readCache(a, key)) {
                        Drawable d = ri.loadIcon(pm);
                        build(a, d);
                        writeCache(a, key);
                    }
                }
                byPkg.put(pkg, a);
                alpha.add(a);
            }
        }
        final Collator c = Collator.getInstance(Locale.getDefault());
        Collections.sort(alpha, (x, y) -> c.compare(x.name, y.name));
        File[] old = cache.listFiles();
        if (old != null) for (File f : old) if (!keep.contains(f.getName())) f.delete();
    }

    /** First-run Home when nothing is saved yet: installed brand apps in BRANDS order, then the rest. */
    List<String> firstRun(int max) {
        List<String> out = new ArrayList<>();
        for (String pkg : BRANDS.keySet()) if (byPkg.containsKey(pkg) && out.size() < max) out.add(pkg);
        for (App a : alpha) {
            if (out.size() >= max) break;
            if (!out.contains(a.pkg) && !a.pkg.startsWith("com.android.") && !a.pkg.startsWith("com.google.android.tv")) out.add(a.pkg);
        }
        return out;
    }

    static String monogram(String name) {
        String[] words = name.split("\\s+");
        String s = words.length == 0 || words[0].isEmpty() ? "" : words[0].substring(0, 1);
        if (words.length > 1 && !words[1].isEmpty()) s += words[1].charAt(0);
        return s.toUpperCase(Locale.ROOT);
    }

    // ---------- disk cache: mark PNG with metrics in the name-free header line ----------

    private boolean readCache(App a, String key) {
        File mark = new File(cache, key + ".mark"), icon = new File(cache, key + ".icon");
        if (!mark.exists() && !icon.exists()) return false;
        if (mark.exists()) {
            try (java.io.DataInputStream in = new java.io.DataInputStream(new java.io.FileInputStream(mark))) {
                int x = in.readInt(), y = in.readInt(), w = in.readInt(), h = in.readInt();
                float fill = in.readFloat();
                byte[] px = new byte[MARK * MARK];
                in.readFully(px);
                Bitmap b = Bitmap.createBitmap(MARK, MARK, Bitmap.Config.ALPHA_8);
                b.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(px));
                a.mark = b;
                a.metrics = new Assets.Metrics(MARK, x, y, w, h, fill);
                return true;
            } catch (Exception e) { mark.delete(); return false; }
        }
        a.icon = BitmapFactory.decodeFile(icon.getPath());
        return a.icon != null;
    }

    private void writeCache(App a, String key) {
        try {
            if (a.mark != null) {
                try (java.io.DataOutputStream out = new java.io.DataOutputStream(new java.io.BufferedOutputStream(new FileOutputStream(new File(cache, key + ".mark"))))) {
                    out.writeInt(a.metrics.x); out.writeInt(a.metrics.y); out.writeInt(a.metrics.w); out.writeInt(a.metrics.h);
                    out.writeFloat(a.metrics.fill);
                    java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(MARK * MARK);
                    a.mark.copyPixelsToBuffer(buf);
                    out.write(buf.array());
                }
            } else if (a.icon != null) {
                try (FileOutputStream out = new FileOutputStream(new File(cache, key + ".icon"))) { a.icon.compress(Bitmap.CompressFormat.PNG, 100, out); }
            }
        } catch (Exception ignored) { }
    }

    // ---------- glyph extraction (ported from the WebView build's Bridge) ----------

    private static void build(App a, Drawable d) {
        int[] box = new int[4];
        float[] fill = new float[1];
        byte[] alphaPx = null;
        if (d instanceof AdaptiveIconDrawable) alphaPx = adaptive(((AdaptiveIconDrawable) d).getForeground(), box, fill);
        if (alphaPx == null) alphaPx = glyph(d, box, fill);
        if (alphaPx != null) {
            Bitmap b = Bitmap.createBitmap(MARK, MARK, Bitmap.Config.ALPHA_8);
            b.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(alphaPx));
            a.mark = b;
            a.metrics = new Assets.Metrics(MARK, box[0], box[1], box[2], box[3], fill[0]);
        } else {
            Bitmap b = Bitmap.createBitmap(ICON, ICON, Bitmap.Config.ARGB_8888);
            d.setBounds(0, 0, ICON, ICON);
            d.draw(new Canvas(b));
            a.icon = b;
        }
    }

    private static byte[] adaptive(Drawable fg, int[] box, float[] fill) {
        if (fg == null) return null;
        int full = MARK * 3 / 2, off = (full - MARK) / 2;
        Bitmap b = Bitmap.createBitmap(MARK, MARK, Bitmap.Config.ARGB_8888);
        fg.setBounds(-off, -off, full - off, full - off);
        fg.draw(new Canvas(b));
        int[] px = new int[MARK * MARK];
        b.getPixels(px, 0, MARK, 0, 0, MARK, MARK);
        b.recycle();
        for (int i = 0; i < px.length; i++) px[i] = Math.max(0, Math.min(255, ((px[i] >>> 24) - 48) * 255 / 160));
        return finish(px, box, fill);
    }

    private static byte[] glyph(Drawable d, int[] box, float[] fill) {
        Bitmap b = Bitmap.createBitmap(MARK, MARK, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, MARK, MARK);
        d.draw(new Canvas(b));
        int[] px = new int[MARK * MARK];
        b.getPixels(px, 0, MARK, 0, 0, MARK, MARK);
        b.recycle();
        int inset = MARK / 12, n = 0, total = 4 * (MARK - 2 * inset);
        long r = 0, g = 0, bl = 0;
        int[] ring = new int[total];
        for (int t = inset; t < MARK - inset; t++) {
            int[] at = {inset * MARK + t, (MARK - 1 - inset) * MARK + t, t * MARK + inset, t * MARK + MARK - 1 - inset};
            for (int i : at) {
                if ((px[i] >>> 24) < 250) continue;
                ring[n++] = px[i];
                r += (px[i] >> 16) & 255; g += (px[i] >> 8) & 255; bl += px[i] & 255;
            }
        }
        if (n < total / 2) return light(px, box, fill);
        int pr = (int) (r / n), pg = (int) (g / n), pb = (int) (bl / n), offCount = 0;
        for (int i = 0; i < n; i++) if (distance(ring[i], pr, pg, pb) > 24) offCount++;
        if (offCount > n / 10) return light(px, box, fill);
        for (int i = 0; i < px.length; i++) {
            px[i] = (px[i] >>> 24) < 128 ? 0 : Math.max(0, Math.min(255, (distance(px[i], pr, pg, pb) - 40) * 255 / 80));
        }
        return finish(px, box, fill);
    }

    private static byte[] light(int[] px, int[] box, float[] fill) {
        for (int i = 0; i < px.length; i++) {
            int c = px[i], r = (c >> 16) & 255, g = (c >> 8) & 255, blue = c & 255;
            int low = Math.min(r, Math.min(g, blue)), high = Math.max(r, Math.max(g, blue));
            int l = Math.max(0, Math.min(255, (low - 128) * 2 - (high - low)));
            px[i] = (c >>> 24) * l / 255;
        }
        return finish(px, box, fill);
    }

    private static int distance(int c, int r, int g, int b) {
        return Math.max(Math.abs(((c >> 16) & 255) - r), Math.max(Math.abs(((c >> 8) & 255) - g), Math.abs((c & 255) - b)));
    }

    /** px holds alphas; returns coverage bytes and the ink box, or null for a solid plate. */
    private static byte[] finish(int[] px, int[] box, float[] fillOut) {
        int left = MARK, top = MARK, right = -1, bottom = -1;
        double ink = 0;
        byte[] out = new byte[px.length];
        for (int i = 0; i < px.length; i++) {
            int a = px[i];
            out[i] = (byte) a;
            ink += a / 255.0;
            if (a > 40) {
                int x = i % MARK, y = i / MARK;
                left = Math.min(left, x); top = Math.min(top, y);
                right = Math.max(right, x); bottom = Math.max(bottom, y);
            }
        }
        if (right < left) return null;
        int w = right - left + 1, h = bottom - top + 1;
        double f = ink / (w * h);
        if (f > 0.8 || (w * h > MARK * MARK * 0.8 && f > 0.6)) return null;
        box[0] = left; box[1] = top; box[2] = w; box[3] = h;
        fillOut[0] = (float) f;
        return out;
    }
}
