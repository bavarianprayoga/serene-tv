package io.github.bavarianprayoga.serene;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.text.format.DateFormat;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Screensaver: the current wallpaper, a light shade and the time, moving corner every five minutes. */
public final class Dream extends DreamService {
    private Face face;

    @Override public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        face = new Face(this);
        setContentView(face);
    }

    // Android may detach the window before or after onDreamingStopped, so the face can already be gone.
    @Override public void onDreamingStarted() { super.onDreamingStarted(); if (face != null) face.start(); }
    @Override public void onDreamingStopped() { if (face != null) face.stop(); super.onDreamingStopped(); }
    @Override public void onDetachedFromWindow() { if (face != null) face.release(); face = null; super.onDetachedFromWindow(); }

    static final class Face extends View {
        private final Assets assets;
        private Bitmap photo;
        private final Paint bmp = new Paint(Paint.FILTER_BITMAP_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG), shade = new Paint();
        private final Handler ui = new Handler(Looper.getMainLooper());
        private final Runnable tick = new Runnable() { @Override public void run() { invalidate(); schedule(); } };

        Face(Context c) {
            super(c);
            assets = new Assets(c);
            SharedPreferences p = c.getSharedPreferences("serene", Context.MODE_PRIVATE);
            String wall = p.getString("wall", assets.bootstrap.optString("wall", "alpine"));
            photo = assets.image("wallpapers/" + assets.wall(wall).id + ".jpg", Bitmap.Config.HARDWARE);
            shade.setColor(0x2e000000);
            text.setColor(0xffffffff);
            text.setTypeface(assets.face("geist", 400));
        }

        void start() { schedule(); invalidate(); }
        void stop() { ui.removeCallbacks(tick); }
        void release() { stop(); photo = null; }
        private void schedule() { ui.removeCallbacks(tick); ui.postDelayed(tick, 60000 - System.currentTimeMillis() % 60000 + 50); }

        @Override protected void onDraw(Canvas c) {
            c.save();
            c.scale(getWidth() / 1920f, getHeight() / 1080f);
            c.drawColor(0xff171717);
            if (photo != null) { c.drawBitmap(photo, null, new RectF(0, 0, 1920, 1080), bmp); c.drawRect(0, 0, 1920, 1080, shade); }
            Date now = new Date();
            boolean h24 = DateFormat.is24HourFormat(getContext());
            String time = new SimpleDateFormat(h24 ? "HH:mm" : "hh:mm a", Locale.getDefault()).format(now);
            String date = new SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(now);
            int corner = (int) (now.getTime() / 300000 % 4);
            boolean right = corner % 2 == 1, bottom = corner >= 2;
            float timeSize = 86.4f, dateSize = 30.72f, blockH = 103.68f + 6.48f + 46.08f;
            float top = bottom ? 1080 - 64.8f - blockH : 64.8f;
            text.setTextAlign(right ? Paint.Align.RIGHT : Paint.Align.LEFT);
            float x = right ? 1920 - 96 : 96;
            text.setShadowLayer(9.5f, 0, 2, 0x80000000);
            text.setTextSize(timeSize * 1.01408f);
            c.drawText(time, x, top + (103.68f - 1.366f * timeSize) / 2 + 1.066f * timeSize, text);
            text.setTextSize(dateSize * 1.01408f);
            float dt = top + 103.68f + 6.48f;
            c.drawText(date, x, dt + (46.08f - 1.366f * dateSize) / 2 + 1.066f * dateSize, text);
            c.restore();
        }
    }
}
