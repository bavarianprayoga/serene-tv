package io.github.bavarianprayoga.serene;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ComposeShader;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;

import java.io.InputStream;

/**
 * One wallpaper ready to draw: the photo with every Home overlay baked in, and the two glass
 * textures (the blurred material with the same overlays and veil; the lens variant without the
 * lower scrim). Baking once per wallpaper keeps each frame to a handful of bitmap draws.
 * All three end up as HARDWARE bitmaps, so their pixels live once, on the GPU.
 */
final class Look {
    static final int W = 1920, H = 1080;
    final Assets.Wall wall;
    final Bitmap background, glass, lens;
    final BitmapShader glassShader, lensShader;

    private Look(Assets.Wall wall, Bitmap background, Bitmap glass, Bitmap lens) {
        this.wall = wall;
        this.background = background;
        this.glass = glass;
        this.lens = lens;
        glassShader = shader(glass);
        lensShader = shader(lens);
    }

    private static BitmapShader shader(Bitmap b) {
        if (b == null) return null;
        BitmapShader s = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        Matrix m = new Matrix();
        m.setScale(W / (float) b.getWidth(), H / (float) b.getHeight());
        s.setLocalMatrix(m);
        return s;
    }

    /** Runs off the main thread. sample 2 gives a half-size photo for the picker preview. */
    static Look bake(Assets assets, android.content.res.AssetManager am, String id, int sample) {
        Assets.Wall w = assets.wall(id);
        Bitmap bg = decode(am, "wallpapers/" + w.id + ".jpg", sample);
        if (bg != null) {
            Canvas c = new Canvas(bg);
            c.scale(bg.getWidth() / (float) W, bg.getHeight() / (float) H);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
            p.setShader(shade(w));
            c.drawRect(0, 0, W, 300, p);
            overlays(c, w, p, true);
        }
        Bitmap glass = material(am, w, true), lens = material(am, w, false);
        return new Look(w, hardware(bg), hardware(glass), hardware(lens));
    }

    private static Bitmap material(android.content.res.AssetManager am, Assets.Wall w, boolean scrim) {
        Bitmap m = decode(am, "materials/" + w.id + ".jpg", 1);
        if (m == null) return null;
        Canvas c = new Canvas(m);
        c.scale(m.getWidth() / (float) W, m.getHeight() / (float) H);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        overlays(c, w, p, scrim);
        p.setShader(null);
        p.setColor((int) (w.veil * 255 + .5f) << 24 | 0x0c0a08);
        c.drawRect(0, 0, W, H, p);
        return m;
    }

    private static Bitmap decode(android.content.res.AssetManager am, String path, int sample) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inMutable = true;
        o.inSampleSize = sample;
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        try (InputStream in = am.open(path)) { return BitmapFactory.decodeStream(in, null, o); } catch (Exception e) { return null; }
    }

    private static Bitmap hardware(Bitmap b) {
        if (b == null) return null;
        Bitmap h = b.copy(Bitmap.Config.HARDWARE, false);
        if (h == null) return b;
        b.recycle();
        return h;
    }

    // ---------- the web build's photo overlays, in 1920x1080 coordinates ----------

    static int dark(float a) { return ((int) (a * 255 + .5f) << 24) | 0x0a0806; }

    private static int tint(float a) { return ((int) (a * 255 + .5f) << 24) | 0x040a0c; }

    /** Top shade: two strengths that follow the photo's dim group. */
    private static Shader shade(Assets.Wall w) {
        boolean strong = w.dim[0] > .3f;
        return new LinearGradient(0, 0, 0, 300, new int[]{tint(strong ? .42f : .32f), tint(strong ? .2f : .14f), tint(0)},
                new float[]{0, (strong ? 160 : 150) / 300f, 1}, Shader.TileMode.CLAMP);
    }

    /** ph-dim, ph-scrim (optional), ph-col with its vertical mask, and the two corner vignettes. */
    static void overlays(Canvas c, Assets.Wall w, Paint p, boolean scrim) {
        RectF full = new RectF(0, 0, W, H);
        p.setColor(0xff000000);
        p.setShader(new LinearGradient(0, 0, W, 0, new int[]{dark(w.dim[0]), dark(w.dim[1]), dark(w.dim[2])}, new float[]{0, .5f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(full, p);
        if (scrim) {
            p.setShader(new LinearGradient(0, 600, 0, 1080, new int[]{dark(0), dark(.26f), dark(.4f)}, new float[]{0, .55f, 1}, Shader.TileMode.CLAMP));
            c.drawRect(0, 600, W, H, p);
        }
        Shader colour = new LinearGradient(0, 0, 1040, 0, new int[]{dark(w.col), dark(w.col), dark(0)}, new float[]{0, 600f / 1040, 1}, Shader.TileMode.CLAMP);
        Shader mask = new LinearGradient(0, 160, 0, 980, new int[]{0, 0xff000000, 0xff000000, 0}, new float[]{0, 120f / 820, 700f / 820, 1}, Shader.TileMode.CLAMP);
        p.setShader(new ComposeShader(colour, mask, PorterDuff.Mode.DST_IN));
        c.drawRect(0, 160, 1040, 980, p);
        ellipse(c, p, 1500, 104, new RadialGradient(0, 0, 900, new int[]{dark(w.utTr[0]), dark(w.utTr[1]), dark(0)}, new float[]{0, .55f, 1}, Shader.TileMode.CLAMP));
        ellipse(c, p, 1560, 878, new RadialGradient(0, 0, 900, new int[]{dark(w.utBr[0]), dark(w.utBr[1]), dark(0)}, new float[]{0, .55f, 1}, Shader.TileMode.CLAMP));
        p.setShader(null);
    }

    /** radial-gradient(900px 260px at cx cy, ...): a circle squashed to the ellipse. */
    private static void ellipse(Canvas c, Paint p, float cx, float cy, Shader s) {
        p.setShader(s);
        c.save();
        c.translate(cx, cy);
        c.scale(1, 260f / 900f);
        c.drawCircle(0, 0, 900, p);
        c.restore();
    }
}
