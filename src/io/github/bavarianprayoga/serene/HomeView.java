package io.github.bavarianprayoga.serene;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ComposeShader;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.KeyEvent;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The whole launcher on one Canvas, in the web build's 1920x1080 coordinates.
 * Layout numbers come from the WebView build's computed styles with the 50A20's look:
 * Photo background, List layout, icons and names, Geist, icon controls, Glass focus, Soft material.
 */
final class HomeView extends View {
    static final int W = 1920, H = 1080, CAP = 9, COLS = 5;
    private static final String FULL = "Home holds nine apps. Remove one first.", LAST = "Home needs one app";
    private static final String[] UTIL_NAMES = {"Screensaver", "All apps", "Settings"};
    private static final String[] UTIL_ICONS = {"art", "apps", "settings"};
    private static final String[][] MODES = {{"fixed", "Fixed"}, {"open", "Each time Home opens"}, {"5", "Every 5 minutes"}, {"15", "Every 15 minutes"}, {"60", "Every hour"}};
    private static final int SHEET = 0xff0f1416, LIGHT = 0xfff3f3ef, DARK_TEXT = 0xff141618, PANEL_INK = 0xfff6f1e7;
    private static final int MUTE = 0xc2f6f1e7, FAINT = 0x94f6f1e7, FOCUS_VALUE = 0xad1d1a16;
    private static final float GEIST = 1.01408f;

    // Scenes
    static final int HOME = 0, SETTINGS = 1, BACKGROUND = 2, WALLPAPER = 3, ROTATION = 4, APPS = 5, EDITOR = 6, MENU = 7;

    private static final class Scene {
        final int id;
        int i, zone, top, part, h;          // generic focus fields
        String app;                         // MENU / lifted app
        String[][] actions;
        String title;
        List<String> liftOrder;
        int liftFrom = -1;
        Scene(int id) { this.id = id; }
    }

    private final Assets assets;
    private final SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ArrayList<Scene> stack = new ArrayList<>();
    private Apps catalog;
    private final Map<String, String> knownNames = new HashMap<>();

    // Persistent state
    private final ArrayList<String> home = new ArrayList<>();
    private final ArrayList<String> recent = new ArrayList<>();
    private String wallId, mode;
    private long rotatedAt;
    private final ArrayList<List<String>[]> undo = new ArrayList<>();
    private String note;

    // Home focus
    private int sel, zone, util, lastUtil, scrollFirst, settingsFocus;

    // Photo state
    private Assets.Wall wall, shownWall;
    private Look look, oldLook, previewLook;
    private Bitmap panel;
    private String previewId;
    private long photoFadeStart;

    // Drawing helpers
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint bmp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF(), full = new RectF(0, 0, W, H);
    private final Path path = new Path();
    private Shader topMask, bottomMask;
    private final Map<String, Mask> masks = new HashMap<>();

    // Animation: named values eased toward targets
    private static final class Tween { float from, to, value; long start; int dur; boolean ease; }
    private final Map<String, Tween> tweens = new HashMap<>();
    private boolean animating;
    private long now;

    // Plate crossfade (the web build fades the old plate out and the new one in)
    private final RectF[] plates = {new RectF(), new RectF()};
    private final String[] plateKeys = {"", ""};
    private int plateCur;
    private final RectF[] lenses = {new RectF(), new RectF()};
    private final String[] lensKeys = {"", ""};
    private int lensCur;

    // Remote OK hold
    private Runnable holdTimer;
    private boolean okDown, backDown;

    // Clock & network
    private String clockText = "";
    private int netLevel = -1; // -1 hide (ethernet), 0..3 Wi-Fi bars; -2 offline
    private final Runnable tick = new Runnable() {
        @Override public void run() { updateClock(); updateNetwork(); invalidate(); scheduleTick(); }
    };
    private final Runnable rotateTimer = this::rotateIfDue;
    private boolean visible;

    HomeView(Context context) {
        super(context);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setLayerType(LAYER_TYPE_HARDWARE, null);
        assets = new Assets(context);
        prefs = context.getSharedPreferences("serene", Context.MODE_PRIVATE);
        load();
        stack.add(new Scene(HOME));
        setWall(wallId, false);
        refreshCatalog();
    }

    // ================================================================ persistence

    private void load() {
        if (!prefs.contains("home")) {
            JSONObject b = assets.bootstrap;
            JSONArray h = b.optJSONArray("home");
            if (h != null) for (int i = 0; i < h.length(); i++) home.add(h.optString(i));
            JSONObject names = b.optJSONObject("names");
            if (names != null) for (java.util.Iterator<String> it = names.keys(); it.hasNext(); ) { String k = it.next(); knownNames.put(k, names.optString(k)); }
            wallId = b.optString("wall", "alpine");
            mode = b.optString("mode", "fixed");
            save();
            return;
        }
        split(prefs.getString("home", ""), home);
        split(prefs.getString("recent", ""), recent);
        wallId = prefs.getString("wall", "alpine");
        mode = prefs.getString("mode", "fixed");
        rotatedAt = prefs.getLong("rotatedAt", 0);
        try {
            JSONObject names = new JSONObject(prefs.getString("names", "{}"));
            for (java.util.Iterator<String> it = names.keys(); it.hasNext(); ) { String k = it.next(); knownNames.put(k, names.optString(k)); }
        } catch (Exception ignored) { }
    }

    private static void split(String s, List<String> out) { out.clear(); for (String p : s.split(",")) if (!p.isEmpty()) out.add(p); }

    private void save() {
        JSONObject names = new JSONObject();
        try { for (String id : home) if (knownNames.containsKey(id)) names.put(id, knownNames.get(id)); } catch (Exception ignored) { }
        prefs.edit().putString("home", TextUtils.join(",", home)).putString("recent", TextUtils.join(",", recent))
                .putString("wall", wallId).putString("mode", mode).putLong("rotatedAt", rotatedAt)
                .putString("names", names.toString()).apply();
    }

    // ================================================================ catalogue

    void refreshCatalog() {
        final Context ctx = getContext();
        io.execute(() -> {
            final Apps fresh = Apps.load(ctx, assets);
            ui.post(() -> {
                catalog = fresh;
                for (Apps.App a : fresh.alpha) knownNames.put(a.pkg, a.name);
                if (home.isEmpty() && !prefs.getBoolean("seeded", false)) home.addAll(fresh.firstRun(5));
                prefs.edit().putBoolean("seeded", true).apply();
                save();
                clampFocus();
                invalidate();
            });
        });
    }

    /** Home apps that are installed. Uninstalled ones keep their place and reappear there when reinstalled. */
    private List<String> visibleHome() {
        if (catalog == null) {
            ArrayList<String> v = new ArrayList<>();
            for (String id : home) if (knownNames.containsKey(id)) v.add(id);
            return v;
        }
        ArrayList<String> v = new ArrayList<>();
        for (String id : home) if (catalog.byPkg.containsKey(id)) v.add(id);
        return v;
    }

    private String name(String id) {
        Apps.App a = catalog == null ? null : catalog.byPkg.get(id);
        if (a != null) return a.name;
        String n = knownNames.get(id);
        return n == null ? id : n;
    }

    private Apps.App app(String id) { return catalog == null ? null : catalog.byPkg.get(id); }

    private List<String> alpha() {
        ArrayList<String> out = new ArrayList<>();
        if (catalog != null) for (Apps.App a : catalog.alpha) out.add(a.pkg);
        return out;
    }

    private boolean onHome(String id) { return visibleHome().contains(id); }

    private List<String> otherRecent() {
        ArrayList<String> out = new ArrayList<>();
        for (String id : recent) if (!onHome(id) && app(id) != null) out.add(id);
        return out;
    }

    private List<String> otherRest() {
        List<String> rec = otherRecent();
        ArrayList<String> out = new ArrayList<>();
        for (String id : alpha()) if (!onHome(id) && !rec.contains(id)) out.add(id);
        return out;
    }

    private List<String> otherList() { List<String> o = otherRecent(); o.addAll(otherRest()); return o; }

    private void clampFocus() {
        int n = visibleHome().size();
        sel = clamp(sel, 0, Math.max(0, n - 1));
    }

    // ================================================================ home list editing (shared with the editor)

    @SuppressWarnings("unchecked")
    private List<String>[] snap() { return new List[]{new ArrayList<>(home), new ArrayList<>(recent)}; }

    /** Rewrites the visible order while keeping hidden (uninstalled) entries at their stored positions. */
    private void setVisible(List<String> order) {
        List<String> vis = visibleHome();
        ArrayList<String> out = new ArrayList<>();
        int k = 0;
        for (String id : home) {
            if (vis.contains(id)) { if (k < order.size()) out.add(order.get(k++)); }
            else out.add(id);
        }
        while (k < order.size()) out.add(order.get(k++));
        home.clear();
        home.addAll(out);
    }

    private boolean homeRemove(String id) {
        List<String> v = visibleHome();
        if (!v.contains(id)) return false;
        if (v.size() <= 1) { note = LAST; return false; }
        undo.add(snap());
        home.remove(id);
        recent.remove(id);
        recent.add(0, id);
        while (recent.size() > 5) recent.remove(recent.size() - 1);
        save();
        return true;
    }

    private boolean homeAdd(String id) {
        if (visibleHome().size() >= CAP) { note = FULL; return false; }
        undo.add(snap());
        home.remove(id);
        List<String> v = visibleHome();
        v.add(id);
        setVisible(v);
        recent.remove(id);
        save();
        return true;
    }

    // ================================================================ wallpaper

    private void setWall(String id, boolean animate) {
        Assets.Wall next = assets.wall(id);
        wallId = next.id;
        wall = next;
        if (shownWall == null) shownWall = next;
        final String target = next.id;
        final android.content.res.AssetManager am = getContext().getAssets();
        io.execute(() -> {
            final Look l = Look.bake(assets, am, target, 1);
            final Bitmap pn = assets.image("wallpapers/" + target + "-panel.jpg", Bitmap.Config.HARDWARE);
            ui.post(() -> {
                if (!target.equals(wallId)) return;
                oldLook = animate && look != null ? look : null;
                look = l;
                panel = pn;
                shownWall = l.wall;
                photoFadeStart = SystemClock.uptimeMillis();
                invalidate();
            });
        });
    }

    /** The picker previews the focused photo at half size, which is plenty for a 922px pane. */
    private void loadPreview(final String id) {
        if (id.equals(previewId)) return;
        previewId = id;
        final android.content.res.AssetManager am = getContext().getAssets();
        io.execute(() -> {
            if (!id.equals(previewId)) return;
            final Look l = id.equals(wallId) ? null : Look.bake(assets, am, id, 2);
            ui.post(() -> { if (id.equals(previewId)) { previewLook = l; invalidate(); } });
        });
    }

    private void dropPreview() { previewLook = null; previewId = null; }

    void resumed() {
        visible = true;
        if ("open".equals(mode) && top().id == HOME) advanceWall();
        updateClock();
        updateNetwork();
        scheduleTick();
        scheduleRotation();
        invalidate();
    }

    void paused() {
        visible = false;
        ui.removeCallbacks(tick);
        ui.removeCallbacks(rotateTimer);
        cancelHold();
    }

    void destroy() { io.shutdownNow(); ui.removeCallbacksAndMessages(null); }

    private void advanceWall() {
        int i = (assets.wallIndex(wallId) + 1) % assets.walls.size();
        rotatedAt = System.currentTimeMillis();
        setWall(assets.walls.get(i).id, true);
        save();
    }

    private int modeMinutes() {
        switch (mode) { case "5": return 5; case "15": return 15; case "60": return 60; default: return 0; }
    }

    private void scheduleRotation() {
        ui.removeCallbacks(rotateTimer);
        int min = modeMinutes();
        if (min == 0 || !visible) return;
        long due = rotatedAt + min * 60000L - System.currentTimeMillis();
        ui.postDelayed(rotateTimer, Math.max(1000, due));
    }

    private void rotateIfDue() {
        int min = modeMinutes();
        if (min == 0 || !visible) return;
        // Timed changes pause while Settings or another panel is open.
        if (top().id == HOME && System.currentTimeMillis() - rotatedAt >= min * 60000L - 500) advanceWall();
        scheduleRotation();
    }

    // ================================================================ clock & network

    private void scheduleTick() {
        ui.removeCallbacks(tick);
        if (!visible) return;
        long ms = System.currentTimeMillis();
        ui.postDelayed(tick, 60000 - ms % 60000 + 50);
    }

    private void updateClock() {
        Calendar c = Calendar.getInstance();
        boolean h24 = DateFormat.is24HourFormat(getContext());
        int h = c.get(Calendar.HOUR_OF_DAY), m = c.get(Calendar.MINUTE);
        if (!h24) { h = h % 12; if (h == 0) h = 12; }
        clockText = String.format(Locale.ROOT, h24 ? "%02d:%02d" : "%d:%02d", h, m);
    }

    private void updateNetwork() {
        try {
            ConnectivityManager cm = getContext().getSystemService(ConnectivityManager.class);
            NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
            boolean online = caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
            WifiInfo wifi = getContext().getApplicationContext().getSystemService(WifiManager.class).getConnectionInfo();
            if (wifi != null && wifi.getSupplicantState() == SupplicantState.COMPLETED) netLevel = online ? WifiManager.calculateSignalLevel(wifi.getRssi(), 4) : -2;
            else if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) netLevel = -1;
            else netLevel = -2;
        } catch (Exception e) { netLevel = -1; }
    }

    // ================================================================ keys

    boolean key(KeyEvent e) {
        int code = e.getKeyCode();
        String k = null;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: k = "up"; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: k = "down"; break;
            case KeyEvent.KEYCODE_DPAD_LEFT: k = "left"; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: k = "right"; break;
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER: case KeyEvent.KEYCODE_BUTTON_A: k = "ok"; break;
            case KeyEvent.KEYCODE_BACK: case KeyEvent.KEYCODE_ESCAPE: k = "back"; break;
            default: return false;
        }
        if (k.equals("ok")) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                if (e.getRepeatCount() > 0 || okDown) return true;
                okDown = true;
                if (holdTarget()) {
                    cancelHold();
                    holdTimer = () -> { holdTimer = null; press("hold"); };
                    ui.postDelayed(holdTimer, 550);
                    return true;
                }
                press("ok");
            } else if (e.getAction() == KeyEvent.ACTION_UP) {
                okDown = false;
                if (holdTimer != null) { cancelHold(); press("ok"); }
            }
            return true;
        }
        if (k.equals("back")) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) { if (e.getRepeatCount() == 0 && !backDown) { backDown = true; press("back"); } }
            else if (e.getAction() == KeyEvent.ACTION_UP) backDown = false;
            return true;
        }
        if (e.getAction() == KeyEvent.ACTION_DOWN) press(k);
        return true;
    }

    private void cancelHold() { if (holdTimer != null) ui.removeCallbacks(holdTimer); holdTimer = null; }

    private boolean holdTarget() {
        Scene t = top();
        if (t.id == HOME) return zone == 0 && !visibleHome().isEmpty();
        return t.id == APPS && t.zone == 1 && !alpha().isEmpty();
    }

    /** Home pressed while Serene is already in front: close panels and show Home. */
    void homePressed() {
        cancelHold();
        while (stack.size() > 1) pop();
        zone = 0;
        releasePanels();
        invalidate();
    }

    private Scene top() { return stack.get(stack.size() - 1); }

    private void push(Scene s) { stack.add(s); fade(); }

    private void pop() {
        if (stack.size() > 1) {
            Scene s = stack.remove(stack.size() - 1);
            if (s.id == WALLPAPER) dropPreview();
            if (top().id == HOME) releasePanels();
            fade();
        }
    }

    /** Panel-only images (thumbnails, picker preview) are dropped as soon as Home is back. */
    private void releasePanels() { thumbs.clear(); dropPreview(); }

    void trim(boolean hidden) {
        releasePanels();
        if (hidden && catalog != null) for (Apps.App a : catalog.alpha) if (!visibleHome().contains(a.pkg)) { a.icon = null; }
    }

    private void fade() { tween("scene", 0, 0, false); tween("scene", 1, 180, true); }

    private void press(String k) {
        Scene t = top();
        switch (t.id) {
            case HOME: homeKey(k); break;
            case SETTINGS: settingsKey(t, k); break;
            case BACKGROUND: backgroundKey(t, k); break;
            case WALLPAPER: wallpaperKey(t, k); break;
            case ROTATION: rotationKey(t, k); break;
            case APPS: appsKey(t, k); break;
            case EDITOR: editorKey(t, k); break;
            case MENU: menuKey(t, k); break;
        }
        invalidate();
    }

    private void homeKey(String k) {
        List<String> h = visibleHome();
        clampFocus();
        if (zone == 1) {
            if (k.equals("up") && util > 0) util--;
            else if (k.equals("down") && util < 2) util++;
            else if (k.equals("left") || k.equals("back")) zone = 0;
            else if (k.equals("ok")) { lastUtil = util; utilAction(util); return; }
            lastUtil = util;
            return;
        }
        if (k.equals("up") && sel > 0) sel--;
        else if (k.equals("right")) { zone = 1; util = lastUtil; }
        else if (k.equals("down") && sel < h.size() - 1) sel++;
        else if (k.equals("ok")) { if (!h.isEmpty()) launch(h.get(sel)); else openEditor(); }
        else if (k.equals("hold") && !h.isEmpty()) openMenu(h.get(sel));
        // Back on Home stays on Home.
    }

    private void utilAction(int u) {
        if (u == 0) startScreensaver();
        else if (u == 1) openApps();
        else { Scene st = new Scene(SETTINGS); st.i = settingsFocus; push(st); }
    }

    private void openApps() { Scene s = new Scene(APPS); s.zone = 1; push(s); refreshCatalog(); }

    private void openEditor() {
        Scene s = new Scene(EDITOR);
        s.zone = visibleHome().isEmpty() ? 2 : 1; // 0 head, 1 home, 2 other
        s.i = clamp(sel, 0, Math.max(0, visibleHome().size() - 1));
        s.h = 1;
        undo.clear();
        note = null;
        push(s);
        refreshCatalog();
    }

    private void openMenu(String id) {
        Scene s = new Scene(MENU);
        s.app = id;
        s.title = name(id);
        s.actions = new String[][]{{"open", "Open"}, {"home", onHome(id) ? "Remove from Home" : "Add to Home"}, {"info", "App info"}};
        push(s);
    }

    private void message(String title, String line) {
        Scene s = new Scene(MENU);
        s.title = title;
        s.actions = new String[][]{{"back", "Back"}};
        s.app = null;
        s.liftOrder = new ArrayList<>();
        s.liftOrder.add(line);
        push(s);
    }

    private void menuKey(Scene t, String k) {
        int n = t.actions.length;
        if (k.equals("left")) t.i = Math.max(0, t.i - 1);
        else if (k.equals("right")) t.i = Math.min(n - 1, t.i + 1);
        else if (k.equals("back")) pop();
        else if (k.equals("ok")) {
            String a = t.actions[t.i][0];
            pop();
            if (t.app == null) return;
            if (a.equals("open")) launch(t.app);
            else if (a.equals("info")) start(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + t.app)), "App info");
            else if (a.equals("home")) {
                boolean ok = onHome(t.app) ? homeRemove(t.app) : homeAdd(t.app);
                undo.clear();
                if (!ok && note != null) { message(name(t.app), note); note = null; }
                clampFocus();
            }
        }
    }

    private void settingsKey(Scene t, String k) {
        if (k.equals("up") && t.i > 0) t.i--;
        else if (k.equals("down") && t.i < 2) t.i++;
        else if (k.equals("back") || k.equals("left")) pop();
        else if (k.equals("ok") || k.equals("right")) {
            if (t.i == 0) { Scene s = new Scene(BACKGROUND); s.i = 0; push(s); }
            else if (t.i == 1) openEditor();
            else start(new Intent(Settings.ACTION_SETTINGS), "TV settings");
        }
        settingsFocus = t.i;
    }

    /** Rows: -1 back link, 0 wallpaper, 1 rotation. */
    private void backgroundKey(Scene t, String k) {
        if (k.equals("up")) t.i = Math.max(-1, t.i - 1);
        else if (k.equals("down")) t.i = Math.min(1, t.i + 1);
        else if (k.equals("back") || k.equals("left")) pop();
        else if (k.equals("ok") || k.equals("right")) {
            if (t.i == -1) pop();
            else if (t.i == 0) openWallpaper();
            else { Scene s = new Scene(ROTATION); s.i = modeIndex(); push(s); }
        }
    }

    private int modeIndex() { for (int i = 0; i < MODES.length; i++) if (MODES[i][0].equals(mode)) return i; return 0; }

    private void openWallpaper() {
        Scene s = new Scene(WALLPAPER);
        s.i = assets.wallIndex(wallId);
        s.top = clamp(s.i - 2, 0, Math.max(0, assets.walls.size() - 6));
        loadPreview(assets.walls.get(s.i).id);
        push(s);
    }

    private void wallpaperKey(Scene t, String k) {
        int n = assets.walls.size();
        if (k.equals("up") && t.i > 0) t.i--;
        else if (k.equals("down") && t.i < n - 1) t.i++;
        else if (k.equals("back") || k.equals("left")) { pop(); return; }
        else if (k.equals("ok")) {
            String id = assets.walls.get(t.i).id;
            if (!id.equals(wallId)) { rotatedAt = System.currentTimeMillis(); setWall(id, false); save(); }
            return;
        }
        if (t.i < t.top + 1) t.top = Math.max(0, t.i - 1);
        if (t.i > t.top + 4) t.top = Math.min(n - 6, t.i - 4);
        loadPreview(assets.walls.get(t.i).id);
    }

    private void rotationKey(Scene t, String k) {
        if (k.equals("up") && t.i > 0) t.i--;
        else if (k.equals("down") && t.i < MODES.length - 1) t.i++;
        else if (k.equals("back") || k.equals("left")) pop();
        else if (k.equals("ok")) { mode = MODES[t.i][0]; rotatedAt = System.currentTimeMillis(); save(); scheduleRotation(); }
    }

    /** zone 0 head (Apps on Home), 1 grid. */
    private void appsKey(Scene t, String k) {
        List<String> items = alpha();
        int n = items.size(), col = t.i % COLS, row = t.i / COLS;
        if (k.equals("back")) { pop(); return; }
        if (t.zone == 0) {
            if (k.equals("down") && n > 0) t.zone = 1;
            else if (k.equals("ok")) openEditor();
            return;
        }
        if (k.equals("left") && col > 0) t.i--;
        else if (k.equals("right") && col < COLS - 1 && t.i < n - 1) t.i++;
        else if (k.equals("down") && row < (n + COLS - 1) / COLS - 1) t.i = Math.min(t.i + COLS, n - 1);
        else if (k.equals("up")) { if (row > 0) t.i -= COLS; else t.zone = 0; }
        else if (k.equals("ok") && n > 0) launch(items.get(t.i));
        else if (k.equals("hold") && n > 0) openMenu(items.get(t.i));
        int r0 = t.i / COLS;
        if (r0 < t.top) t.top = r0;
        if (r0 > t.top + 2) t.top = r0 - 2;
    }

    /** zone 0 head (h: 0 undo, 1 done), 1 home (part 0 name, 1 remove), 2 other (top = j). */
    private void editorKey(Scene t, String k) {
        List<String> H = t.liftOrder != null ? t.liftOrder : visibleHome();
        List<String> O = otherList();
        int nh = H.size(), no = O.size();
        if (t.liftOrder != null) {
            int d = k.equals("up") ? -1 : k.equals("down") ? 1 : 0;
            if (d != 0 && t.i + d >= 0 && t.i + d < nh) { String tmp = H.get(t.i + d); H.set(t.i + d, H.get(t.i)); H.set(t.i, tmp); t.i += d; }
            else if (k.equals("ok")) {
                note = null;
                if (!H.equals(visibleHome())) { undo.add(snap()); setVisible(H); save(); note = name(t.app) + " moved to position " + (t.i + 1) + "."; }
                t.liftOrder = null;
            } else if (k.equals("back")) { t.i = t.liftFrom; t.liftOrder = null; }
            return;
        }
        if (k.equals("back")) { closeEditor(); return; }
        if (t.zone == 0) {
            if (k.equals("left") && t.h > 0) t.h--;
            else if (k.equals("right") && t.h < 1) t.h++;
            else if (k.equals("down")) { if (nh > 0) t.zone = 1; else if (no > 0) t.zone = 2; }
            else if (k.equals("ok")) { note = null; if (t.h == 0) undoLast(); else closeEditor(); }
        } else if (t.zone == 1) {
            if (k.equals("up")) { if (t.i > 0) t.i--; else t.zone = 0; }
            else if (k.equals("down")) { if (t.i < nh - 1) t.i++; }
            else if (k.equals("right")) { if (t.part == 0) t.part = 1; else if (no > 0) { t.zone = 2; t.top = clamp(t.top, 0, no - 1); } }
            else if (k.equals("left")) { if (t.part == 1) t.part = 0; }
            else if (k.equals("ok")) {
                note = null;
                if (t.part == 0) { t.liftOrder = new ArrayList<>(H); t.liftFrom = t.i; t.app = H.get(t.i); }
                else {
                    String id = H.get(t.i);
                    if (homeRemove(id)) { t.zone = 2; t.top = otherList().indexOf(id); note = name(id) + " is in Recently removed. OK puts it back."; }
                }
            }
        } else {
            if (k.equals("up")) { if (t.top > 0) t.top--; else t.zone = 0; }
            else if (k.equals("down")) { if (t.top < no - 1) t.top++; }
            else if (k.equals("left")) { if (nh > 0) { t.zone = 1; t.part = 0; t.i = clamp(t.i, 0, nh - 1); } }
            else if (k.equals("ok") && no > 0) {
                note = null;
                String id = O.get(t.top);
                if (homeAdd(id)) {
                    t.zone = 1; t.part = 1; t.i = visibleHome().indexOf(id);
                    t.top = clamp(t.top, 0, Math.max(0, otherList().size() - 1));
                    note = name(id) + " is on Home, position " + (t.i + 1) + ".";
                }
            }
        }
        int nh2 = visibleHome().size(), no2 = otherList().size();
        t.i = clamp(t.i, 0, Math.max(0, nh2 - 1));
        t.top = clamp(t.top, 0, Math.max(0, no2 - 1));
        if (t.zone == 1 && nh2 == 0) t.zone = no2 > 0 ? 2 : 0;
        if (t.zone == 2 && no2 == 0) t.zone = nh2 > 0 ? 1 : 0;
    }

    private void undoLast() {
        if (undo.isEmpty()) { note = "Nothing to undo"; return; }
        List<String>[] s = undo.remove(undo.size() - 1);
        home.clear(); home.addAll(s[0]);
        recent.clear(); recent.addAll(s[1]);
        save();
        note = "Undone.";
    }

    private void closeEditor() { undo.clear(); note = null; pop(); clampFocus(); }

    // ================================================================ device actions

    private void launch(String pkg) {
        PackageManager pm = getContext().getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) { message(name(pkg), "This app is not installed."); return; }
        start(i, name(pkg));
    }

    private void startScreensaver() {
        Intent dream = new Intent(Intent.ACTION_MAIN).setComponent(new ComponentName("com.android.systemui", "com.android.systemui.Somnambulator"));
        try { getContext().startActivity(dream.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (Exception e) { start(new Intent(Settings.ACTION_DREAM_SETTINGS), "Screensaver"); }
    }

    private void start(Intent intent, String what) {
        try { getContext().startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (ActivityNotFoundException | SecurityException e) { message(what, "The TV could not open this screen."); }
    }

    // ================================================================ animation

    /** Eases a named value toward target; dur 0 jumps. Returns the current value. */
    private float tween(String key, float target, int dur, boolean ease) {
        Tween t = tweens.get(key);
        if (t == null) { t = new Tween(); t.from = t.to = t.value = target; tweens.put(key, t); return target; }
        if (t.to != target) { t.from = t.value; t.to = target; t.start = now == 0 ? SystemClock.uptimeMillis() : now; t.dur = dur; t.ease = ease; }
        if (t.dur <= 0) { t.value = t.to; return t.value; }
        float p = Math.min(1f, (now - t.start) / (float) t.dur);
        if (p < 1) animating = true;
        t.value = t.from + (t.to - t.from) * (t.ease ? ease(p) : p);
        return t.value;
    }

    /** cubic-bezier(.2,.7,.2,1), the web build's --ease. */
    private static float ease(float x) {
        float lo = 0, hi = 1, t = x;
        for (int i = 0; i < 14; i++) {
            t = (lo + hi) / 2;
            float bx = bez(t, .2f, .2f);
            if (bx < x) lo = t; else hi = t;
        }
        return bez(t, .7f, 1f);
    }

    private static float bez(float t, float p1, float p2) {
        float u = 1 - t;
        return 3 * u * u * t * p1 + 3 * u * t * t * p2 + t * t * t;
    }

    // ================================================================ drawing

    @Override protected void onDraw(Canvas c) {
        now = SystemClock.uptimeMillis();
        animating = false;
        c.save();
        c.scale(getWidth() / (float) W, getHeight() / (float) H);
        float sf = tween("scene", 1, 180, true);
        if (sf < 1 && stack.size() >= 1) {
            // Fade the newest scene in over what is beneath it.
            if (stack.size() > 1) drawScene(c, stack.get(stack.size() - 2)); else drawScene(c, top());
            int s = c.saveLayerAlpha(full, (int) (sf * 255));
            drawScene(c, top());
            c.restoreToCount(s);
        } else drawScene(c, top());
        c.restore();
        if (animating) postInvalidateOnAnimation();
    }

    private void drawScene(Canvas c, Scene t) {
        switch (t.id) {
            case HOME: drawHome(c, true); break;
            case SETTINGS: drawSettings(c, t); break;
            case BACKGROUND: drawBackground(c, t); break;
            case WALLPAPER: drawWallpaper(c, t); break;
            case ROTATION: drawRotation(c, t); break;
            case APPS: drawApps(c, t); break;
            case EDITOR: drawEditor(c, t); break;
            case MENU: drawMenu(c, t); break;
        }
    }

    // ---------- photo, glass and the list's soft edges ----------

    private void buildMasks() {
        if (topMask != null) return;
        float[] a = {0, .0156f, .0625f, .1406f, .25f, .3906f, .5625f, .7656f, 1};
        int[] up = new int[9], down = new int[9];
        float[] pos = new float[9];
        for (int i = 0; i < 9; i++) { pos[i] = i / 8f; up[i] = (int) (a[i] * 255) << 24; down[i] = (int) (a[8 - i] * 255) << 24; }
        topMask = new LinearGradient(0, 48, 0, 212, up, pos, Shader.TileMode.CLAMP);
        bottomMask = new LinearGradient(0, 900, 0, 1056, down, pos, Shader.TileMode.CLAMP);
    }

    private void backdrop(Canvas c, Look l) {
        c.drawColor(0xff0d0d0d);
        if (l != null && l.background != null) c.drawBitmap(l.background, null, full, bmp);
    }

    /** A paint's own alpha still scales its shader, so shader fills always start from opaque. */
    private void shade(Shader s) { fill.setColor(0xff000000); fill.setShader(s); }

    /** Glass: the baked blurred photo through a rounded window. lens = the variant without the lower scrim. */
    private void glass(Canvas c, RectF rect, float radius, float alpha, boolean lens) {
        Look l = look;
        if (alpha <= 0.003f || l == null) return;
        BitmapShader sh = lens ? l.lensShader : l.glassShader;
        if (sh == null) return;
        shade(sh);
        fill.setAlpha((int) (alpha * 255 + .5f));
        c.drawRoundRect(rect, radius, radius, fill);
        fill.setShader(null);
    }

    // ---------- text ----------

    private Paint font(String family, int weight, float size, float spacing, int color) {
        boolean geist = family.equals("geist");
        text.setTypeface(assets.face(family, weight));
        float ts = geist ? size * GEIST : size;
        text.setTextSize(ts);
        text.setLetterSpacing(spacing / ts);
        text.setColor(color);
        text.setTextAlign(Paint.Align.LEFT);
        text.clearShadowLayer();
        text.setFontFeatureSettings(null);
        return text;
    }

    /** CSS line box: the fonts share ascent 1.066em and descent 0.3em. */
    private static float baseline(float top, float lineHeight, float size) { return top + (lineHeight - 1.366f * size) / 2f + 1.066f * size; }

    private void draw(Canvas c, String s, float x, float top, float lh, float size) { c.drawText(s, x, baseline(top, lh, size), text); }

    /** Pre-blurred shadow of one label: CSS text-shadows combined into one ALPHA_8 mask. */
    private static final class Mask { Bitmap bitmap; float dx, dy; int color; }

    /**
     * CSS text-shadow list {dy, blur, argb...} under the current text paint, then the text itself.
     * Every shadow in this design shares one colour, so the stacked shadows reduce to one alpha mask
     * that is blurred once and reused on every frame.
     */
    private void shadowed(Canvas c, String s, float x, float y, float[] shadows) {
        int alpha = text.getAlpha();
        String key = s + "|" + text.getTextSize() + "|" + text.getLetterSpacing() + "|" + text.getTypeface().hashCode() + "|" + java.util.Arrays.hashCode(shadows) + "|" + text.getTextAlign() + "|" + text.getFontFeatureSettings();
        Mask m = masks.get(key);
        if (m == null) {
            m = makeMask(s, shadows);
            if (masks.size() > 64) masks.clear();
            masks.put(key, m);
        }
        if (m.bitmap != null) {
            bmp.setColor(m.color | 0xff000000);
            bmp.setAlpha(alpha);
            c.drawBitmap(m.bitmap, x + m.dx, y + m.dy, bmp);
            bmp.setAlpha(255);
        }
        c.drawText(s, x, y, text);
    }

    private Mask makeMask(String s, float[] shadows) {
        Mask m = new Mask();
        Rect b = new Rect();
        text.getTextBounds(s, 0, s.length(), b);
        float maxBlur = 0, maxDy = 0;
        for (int i = 0; i < shadows.length; i += 4) { maxBlur = Math.max(maxBlur, shadows[i + 1]); maxDy = Math.max(maxDy, shadows[i]); }
        int pad = (int) Math.ceil(maxBlur * 1.6f + maxDy + 4);
        Paint.Align align = text.getTextAlign();
        float tw = text.measureText(s), start = align == Paint.Align.CENTER ? -tw / 2 : align == Paint.Align.RIGHT ? -tw : 0;
        int left = (int) Math.floor(start + Math.min(0, b.left)) - pad, top = b.top - pad;
        int w = (int) Math.ceil(Math.max(tw, b.right) - Math.min(0, b.left)) + 2 * pad + 2, h = b.height() + 2 * pad;
        Bitmap glyph = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8);
        Canvas gc = new Canvas(glyph);
        int keep = text.getColor();
        text.setColor(0xffffffff);
        gc.drawText(s, -left, -top, text);
        text.setColor(keep);
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8);
        Canvas oc = new Canvas(out);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        for (int i = 0; i < shadows.length; i += 4) {
            float blur = shadows[i + 1], radius = Math.max(.1f, (blur / 2f - .5f) / .57735f);
            p.setMaskFilter(new android.graphics.BlurMaskFilter(radius, android.graphics.BlurMaskFilter.Blur.NORMAL));
            p.setColor(0xff000000);
            p.setAlpha((int) (shadows[i + 2] * 255 + .5f));
            oc.drawBitmap(glyph, 0, shadows[i], p);
            m.color = (int) shadows[i + 3];
        }
        glyph.recycle();
        m.bitmap = out;
        m.dx = left;
        m.dy = top;
        return m;
    }

    /** CSS text-shadow lists as {dy, blur, alpha, rgb} tuples. */
    private static final int WARM = 0x0e0a06;
    private static final float[] SH_REST = {1, 2, .32f, WARM, 3, 14, .24f, WARM};
    private static final float[] SH_FOCUS = {1, 2, .22f, WARM};
    private static final float[] SH_CLOCK = {1, 2, .4f, WARM, 2, 8, .24f, WARM};
    private static final float[] SH_CLOCK_DARK = {1, 2, .28f, 0xf8fcff};

    private float width(String s) { return text.measureText(s); }

    private String ellipsize(String s, float max) {
        if (width(s) <= max) return s;
        int n = s.length();
        while (n > 1 && width(s.substring(0, n) + "…") > max) n--;
        return s.substring(0, n) + "…";
    }

    // ---------- marks ----------

    /** Monochrome mark (or monogram / colour icon) centred in a w x h box, as the web build's appMark. */
    private void mark(Canvas c, String id, float x, float y, float w, float h, float size, int ink, float alpha) {
        Apps.App a = app(id);
        Bitmap m = null;
        Assets.Metrics mt = null;
        if (a != null) {
            if (a.brand != null) { m = assets.brand(a.brand); mt = a.metrics; }
            else if (a.mark != null) { m = a.mark; mt = a.metrics; }
        }
        int al = (int) (alpha * 255);
        if (m != null && mt != null) {
            float weight = clamp((float) Math.pow(0.45 / mt.fill, 0.3), .84f, 1.25f);
            float k = Math.min(Math.min(w / mt.w, h / mt.h), size * weight / (float) Math.sqrt(mt.w * mt.h)), s = mt.n * k;
            float left = x + w / 2 - (mt.x + mt.w / 2f) * k, top = y + h / 2 - (mt.y + mt.h / 2f) * k;
            bmp.setColor(ink);
            bmp.setAlpha(Math.min(al, Color.alpha(ink)));
            r.set(left, top, left + s, top + s);
            c.drawBitmap(m, null, r, bmp);
            bmp.setAlpha(255);
            return;
        }
        if (a != null && a.icon != null) {
            float s = Math.min(w, h);
            r.set(x + (w - s) / 2, y + (h - s) / 2, x + (w + s) / 2, y + (h + s) / 2);
            bmp.setAlpha(al);
            c.drawBitmap(a.icon, null, r, bmp);
            bmp.setAlpha(255);
            return;
        }
        String letters = a != null ? a.monogram : Apps.monogram(name(id));
        float fs = Math.round(size * .65f), box = 1.65f * fs, bx = x + (w - box) / 2, by = y + (h - box) / 2;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(2);
        stroke.setColor(ink);
        stroke.setAlpha((int) (.38f * al));
        r.set(bx + 1, by + 1, bx + box - 1, by + box - 1);
        c.drawRoundRect(r, .4f * fs - 1, .4f * fs - 1, stroke);
        font("manrope", 600, fs, 0, ink);
        text.setAlpha(al);
        text.setTextAlign(Paint.Align.CENTER);
        c.drawText(letters, bx + box / 2, baseline(by, box, fs), text);
        text.setTextAlign(Paint.Align.LEFT);
    }

    private static final class Color { static int alpha(int c) { return c >>> 24; } }

    // ---------- Home ----------

    private float labelSize(String name) {
        font("geist", 300, 88, 0, 0);
        float w = width(name) - .88f * Math.max(0, name.length() - 1);
        return w > 992 ? 76 : 88;
    }

    private float labelWidth(String name, float size) {
        font("geist", 300, size, 0, 0);
        return Math.min(992, width(name) - size * .01f * Math.max(0, name.length() - 1));
    }

    private void drawHome(Canvas c, boolean live) {
        drawHomeAt(c, live, live && top().id == HOME);
    }

    /** focused: Home owns remote focus (otherwise the selection plate is shown at half strength). */
    private void drawHomeAt(Canvas c, boolean live, boolean focused) {
        Assets.Wall w = shownWall;
        float pf = oldLook != null ? Math.min(1f, (now - photoFadeStart) / 400f) : 1f;
        if (oldLook != null && pf < 1 && live) {
            backdrop(c, oldLook);
            if (look != null && look.background != null) { bmp.setAlpha((int) (pf * 255)); c.drawBitmap(look.background, null, full, bmp); bmp.setAlpha(255); }
            animating = true;
        } else {
            if (live) oldLook = null;
            backdrop(c, look);
        }
        if (look == null) return;
        List<String> h = visibleHome();
        int n = h.size(), anchor = clamp(sel, 0, Math.max(0, n - 1));
        boolean rowFocus = focused && zone == 0, remembered = focused && zone == 1 || !focused;
        scrollFirst = clamp(scrollFirst, anchor - 3, anchor - 1);
        scrollFirst = clamp(scrollFirst, 0, Math.max(0, n - 5));
        float off = live ? tween("listOff", scrollFirst * 144, 180, true) : scrollFirst * 144;

        // Plate (glass), crossfading between positions like the web build's two plates.
        if (n > 0) {
            String id = h.get(anchor);
            float size = labelSize(name(id));
            float pw = clamp((float) Math.ceil(labelWidth(name(id), size) + 184), 360, 1176);
            float py = 197 + (anchor - scrollFirst) * 144;
            float target = rowFocus ? 1 : remembered ? .5f : 0;
            if (!live) {
                r.set(112, py, 112 + pw, py + 134);
                glass(c, r, 38, target, false);
            } else {
                String key = id + "|" + pw + "|" + py;
                if (!key.equals(plateKeys[plateCur])) {
                    plateCur = 1 - plateCur;
                    plateKeys[plateCur] = key;
                    plates[plateCur].set(112, py, 112 + pw, py + 134);
                    tweens.remove("plate" + plateCur);
                    tween("plate" + plateCur, 0, 0, false);
                }
                for (int i = 0; i < 2; i++) glass(c, plates[i], 38, tween("plate" + i, i == plateCur ? target : 0, 160, false), false);
            }
        }

        // Rows: clipped to the list, with the soft top and bottom edges applied only where a row reaches them.
        buildMasks();
        float firstY = 196 - off, lastY = 196 + (n - 1) * 144 - off + 144;
        c.save();
        c.clipRect(112, 212, 1312, 900);
        drawRows(c, h, anchor, rowFocus, remembered, off, live, w);
        c.restore();
        if (n > 0 && firstY < 212) band(c, 48, 212, topMask, h, anchor, rowFocus, remembered, off, live, w);
        if (n > 0 && lastY > 900) band(c, 900, 1056, bottomMask, h, anchor, rowFocus, remembered, off, live, w);

        drawClock(c, w);
        drawUtils(c, w, live, focused);
    }

    private void band(Canvas c, float top, float bottom, Shader mask, List<String> h, int anchor, boolean rowFocus, boolean remembered, float off, boolean live, Assets.Wall w) {
        int layer = c.saveLayer(112, top, 1312, bottom, null);
        drawRows(c, h, anchor, rowFocus, remembered, off, live, w);
        shade(mask);
        fill.setXfermode(DST_IN);
        c.drawRect(112, top, 1312, bottom, fill);
        fill.setXfermode(null);
        fill.setShader(null);
        c.restoreToCount(layer);
    }

    private static final android.graphics.PorterDuffXfermode DST_IN = new android.graphics.PorterDuffXfermode(PorterDuff.Mode.DST_IN);

    private void drawRows(Canvas c, List<String> h, int anchor, boolean rowFocus, boolean remembered, float off, boolean live, Assets.Wall w) {
        for (int i = 0; i < h.size(); i++) {
            float y = 196 + i * 144 - off;
            if (y > 1056 || y + 144 < 48) continue;
            String id = h.get(i);
            boolean focus = rowFocus && i == anchor, keep = i == anchor && (rowFocus || remembered);
            float ga = live ? tween("row" + id, keep ? 1 : .5f, 160, false) : (keep ? 1 : .5f);
            mark(c, id, 140, y + 32, 84, 64, 54, w.ink, ga);
            String nm = name(id);
            float size = labelSize(nm);
            font("geist", 300, size, -size * .01f, w.ink);
            nm = ellipsize(nm, 992);
            text.setAlpha((int) (ga * 255 + .5f));
            float top = y + (size == 76 ? 10.36f : 9.68f);
            shadowed(c, nm, 240, baseline(top, 104, size), focus ? SH_FOCUS : SH_REST);
        }
    }

    private void drawClock(Canvas c, Assets.Wall w) {
        font("geist", 300, 48, -.48f, w.clockInk);
        text.setFontFeatureSettings("'tnum'");
        float tw = width(clockText), tx = 1780 - tw;
        shadowed(c, clockText, tx, baseline(112, 64, 48), w.clockDark ? SH_CLOCK_DARK : SH_CLOCK);
        if (netLevel == -1) return;
        // Wi-Fi: viewBox 1.2 4 21.6 17.2 drawn 42.24 x 33.6, .36em to the left of the time.
        float iw = 42.24f, k = iw / 21.6f, ix = tx - 17.28f - iw, iy = 128.4f;
        c.save();
        c.translate(ix, iy);
        c.scale(k, k);
        c.translate(-1.2f, -4f);
        int lit = netLevel < 0 ? 0 : clamp(netLevel, 0, 3);
        int base = w.clockInk;
        int alpha = netLevel == -2 ? 153 : 255;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setStrokeWidth(1.8f);
        for (int i = 0; i < 3; i++) {
            stroke.setColor(base);
            stroke.setAlpha((int) (alpha * (i < lit ? 1 : .3f)));
            stroke.setShadowLayer(1.2f, 0, .5f, 0x38000000);
            c.drawPath(Icons.get("net" + i), stroke);
        }
        stroke.clearShadowLayer();
        fill.setColor(base);
        fill.setAlpha(alpha);
        c.drawCircle(12, 19.2f, 1.3f, fill);
        if (netLevel == -2) { stroke.setAlpha(alpha); c.drawLine(4, 4, 20, 20, stroke); }
        c.restore();
    }

    private void drawUtils(Canvas c, Assets.Wall w, boolean live, boolean focused) {
        boolean awake = focused && zone == 1;
        // Lens and label behind the focused control.
        if (live) {
            font("geist", 400, 28, 0, w.utilInk);
            float lw = width(UTIL_NAMES[util]), lx = 1730 - lw, uy = 776 + util * 64;
            String key = util + "";
            if (!key.equals(lensKeys[lensCur])) {
                lensCur = 1 - lensCur;
                lensKeys[lensCur] = key;
                lenses[lensCur].set(lx - 24, uy + 2, 1800, uy + 62);
                tweens.remove("lens" + lensCur);
                tween("lens" + lensCur, 0, 0, false);
            }
            for (int i = 0; i < 2; i++) glass(c, lenses[i], 30, tween("lens" + i, awake && i == lensCur ? 1 : 0, 120, false), true);
            float la = tween("ulabel", awake ? 1 : 0, 160, false);
            if (la > 0.01f) {
                font("geist", 400, 28, 0, w.utilInk);
                text.setAlpha((int) (la * 255));
                shadowed(c, UTIL_NAMES[util], lx, baseline(uy + 13, 36, 28), SH_CLOCK);
            }
        }
        for (int i = 0; i < 3; i++) {
            float target = !awake ? w.uRest : i == util ? 1 : w.uPeer;
            float a = live ? tween("u" + i, target, 160, false) : target;
            float y = 776 + i * 64, size = i == 2 ? 34 : 36, ix = i == 2 ? 1749 : 1748, iy = y + (i == 2 ? 15 : 14);
            int s = c.saveLayerAlpha(ix - 4, iy - 4, ix + size + 4, iy + size + 4, (int) (a * 255));
            stroke.setColor(((int) (w.halo * 255 + .5f) << 24) | 0x0e0a06);
            Icons.draw(c, UTIL_ICONS[i], ix, iy + .5f * size / 24f, size, 3.2f, stroke);
            stroke.setColor(w.utilInk);
            Icons.draw(c, UTIL_ICONS[i], ix, iy, size, 1.8f, stroke);
            c.restoreToCount(s);
        }
    }

    // ---------- sheets ----------

    private void sheet(Canvas c, Assets.Wall w, Bitmap panelImage) {
        c.drawColor(SHEET);
        if (panelImage != null) c.drawBitmap(panelImage, null, full, bmp);
        fill.setColor(0x6b080c0e);
        c.drawRect(full, fill);
    }

    private void title(Canvas c, String s, float top) {
        font("manrope", 500, 56, -1.6f, PANEL_INK);
        draw(c, s, 140, top, top == 72 ? 64 : 68, 56);
    }

    private void backLink(Canvas c, String label, boolean focus) {
        font("manrope", 500, 28, -.2f, focus ? DARK_TEXT : MUTE);
        float tw = width(label);
        if (focus) { r.set(124, 60, 172 + tw + 16, 112); fill.setColor(LIGHT); c.drawRoundRect(r, 16, 16, fill); }
        stroke.setColor(focus ? DARK_TEXT : MUTE);
        Icons.draw(c, "left", 140, 74, 24, 1.6f, stroke);
        draw(c, label, 172, 68, 36, 28);
    }

    private void hint(Canvas c, String s) {
        float y = 968;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(1);
        stroke.setColor(MUTE);
        r.set(140.5f, 969.5f, 190.5f, 1002.5f);
        c.drawRoundRect(r, 16.5f, 16.5f, stroke);
        font("manrope", 600, 21, 0, MUTE);
        text.setTextAlign(Paint.Align.CENTER);
        draw(c, "OK", 165.5f, 972, 28, 21);
        text.setTextAlign(Paint.Align.LEFT);
        font("manrope", 400, 26, 0, MUTE);
        draw(c, s, 207, y, 36, 26);
    }

    /** The light focus pill used by Settings rows and buttons. */
    private void lightRow(Canvas c, RectF rect, float radius) { fill.setColor(LIGHT); c.drawRoundRect(rect, radius, radius, fill); }

    /** The translucent "plate" focus of grids and editor rows: 170deg white gradient and an inset top edge. */
    private void plate(Canvas c, RectF rect, float radius, float a0, float a1, float a2) {
        double th = Math.toRadians(170);
        float vx = (float) Math.sin(th), vy = (float) -Math.cos(th);
        float len = Math.abs(rect.width() * vx) + Math.abs(rect.height() * vy);
        float cx = rect.centerX(), cy = rect.centerY();
        shade(new LinearGradient(cx - vx * len / 2, cy - vy * len / 2, cx + vx * len / 2, cy + vy * len / 2,
                new int[]{white(a0), white(a1), white(a2)}, new float[]{0, .58f, 1}, Shader.TileMode.CLAMP));
        c.drawRoundRect(rect, radius, radius, fill);
        fill.setShader(null);
        int s = c.save();
        path.reset();
        path.addRoundRect(rect, radius, radius, Path.Direction.CW);
        c.clipPath(path);
        Path inner = new Path();
        RectF in = new RectF(rect.left, rect.top + 1, rect.right, rect.bottom + 1);
        inner.addRoundRect(in, radius, radius, Path.Direction.CW);
        Path edge = new Path(path);
        edge.op(inner, Path.Op.DIFFERENCE);
        fill.setColor(white(.34f));
        c.drawPath(edge, fill);
        c.restoreToCount(s);
    }

    private static int white(float a) { return ((int) (a * 255 + .5f) << 24) | 0xffffff; }

    private void preview(Canvas c, String wallOverride) {
        // A live miniature of Home at 0.48 scale in a 922x518 window with 32px corners.
        c.save();
        r.set(858, 288, 1780, 806);
        path.reset();
        path.addRoundRect(r, 32, 32, Path.Direction.CW);
        c.clipPath(path);
        c.translate(858, 288);
        c.scale(.48f, .48f);
        Assets.Wall keepWall = shownWall;
        Look keepLook = look;
        if (wallOverride != null && !wallOverride.equals(shownWall.id) && previewLook != null) {
            shownWall = previewLook.wall;
            look = previewLook;
        }
        drawHomeAt(c, false, false);
        shownWall = keepWall;
        look = keepLook;
        c.restore();
    }

    private void drawSettings(Canvas c, Scene t) {
        sheet(c, shownWall, panel);
        title(c, "Settings", 72);
        String[][] rows = {{"Background", shownWall.name}, {"Apps on Home", visibleHome().size() + (visibleHome().size() == 1 ? " app" : " apps")}, {"TV settings", "Picture, sound and network"}};
        for (int i = 0; i < rows.length; i++) {
            float y = 212 + i * 120;
            boolean f = i == t.i;
            if (f) { r.set(112, y, 1168, y + 112); lightRow(c, r, 20); }
            font("manrope", 500, 40, -.6f, f ? DARK_TEXT : PANEL_INK);
            draw(c, rows[i][0], 140, y + 13, 48, 40);
            font("manrope", 400, 26, 0, f ? FOCUS_VALUE : MUTE);
            draw(c, rows[i][1], 140, y + 65, 34, 26);
            stroke.setColor(f ? DARK_TEXT : PANEL_INK);
            Icons.draw(c, "right", 1106, y + 41, 30, 1.6f, stroke);
        }
        hint(c, "Open selected item");
    }

    private void choiceRow(Canvas c, float y, float h, boolean focus, Bitmap thumb, float tx, float tw, float th, String label, float lx, float size, boolean tick, boolean forward) {
        if (focus) { r.set(140, y, 780, y + h); lightRow(c, r, 18); }
        if (thumb != null) {
            int s = c.save();
            r.set(tx, y + (h - th) / 2, tx + tw, y + (h + th) / 2);
            path.reset();
            path.addRoundRect(r, 8, 8, Path.Direction.CW);
            c.clipPath(path);
            c.drawBitmap(thumb, null, r, bmp);
            c.restoreToCount(s);
        }
        font("manrope", 500, size, -.5f, focus ? DARK_TEXT : PANEL_INK);
        float top = y + (h - 48) / 2;
        draw(c, label, lx, top, 48, size);
        if (tick) {
            stroke.setColor(focus ? DARK_TEXT : PANEL_INK);
            Icons.draw(c, "check", lx + width(label) + 16, top + 10, 30, 2.2f, stroke);
        }
        if (forward) {
            stroke.setColor(focus ? DARK_TEXT : PANEL_INK);
            Icons.draw(c, "right", 728, y + (h - 28) / 2, 28, 1.6f, stroke);
        }
    }

    private final Map<String, Bitmap> thumbs = new HashMap<>();

    private Bitmap thumb(String id) {
        Bitmap b = thumbs.get(id);
        if (b == null) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 2; // 256x144, plenty for 96x54 and 80x50 swatches
            o.inPreferredConfig = Bitmap.Config.HARDWARE;
            try (java.io.InputStream in = getContext().getAssets().open("wallpapers/" + id + "-thumb.jpg")) { b = BitmapFactory.decodeStream(in, null, o); } catch (Exception ignored) { }
            if (b != null) thumbs.put(id, b);
        }
        return b;
    }

    private void drawBackground(Canvas c, Scene t) {
        sheet(c, shownWall, panel);
        backLink(c, "Settings", t.i == -1);
        title(c, "Background", 132);
        font("manrope", 500, 28, -.2f, MUTE);
        draw(c, "Wallpaper", 140, 244, 36, 28);
        choiceRow(c, 292, 78, t.i == 0, thumb(wallId), 164, 80, 50, shownWall.name, 268, 38, false, true);
        font("manrope", 500, 28, -.2f, MUTE);
        draw(c, "Rotation", 140, 402, 36, 28);
        choiceRow(c, 450, 78, t.i == 1, null, 0, 0, 0, MODES[modeIndex()][1], 268, 38, false, true);
        preview(c, null);
        hint(c, "Apply selected setting");

    }

    private void drawWallpaper(Canvas c, Scene t) {
        String candidate = assets.walls.get(t.i).id;
        Bitmap pan = panel;
        sheet(c, shownWall, pan);
        backLink(c, "Background", false);
        title(c, "Wallpaper", 132);
        int n = assets.walls.size();
        float off = tween("wallTop", t.top * 92, 180, true);
        int s = c.save();
        c.clipRect(140, 260, 780, 812);
        for (int i = 0; i < n; i++) {
            float y = 260 + i * 92 - off;
            if (y > 812 || y + 84 < 260) continue;
            Assets.Wall w = assets.walls.get(i);
            choiceRow(c, y, 84, i == t.i, thumb(w.id), 164, 96, 54, w.name, 284, 36, w.id.equals(wallId), false);
        }
        c.restoreToCount(s);
        stroke.setColor(PANEL_INK);
        if (t.top > 0) { int l = c.saveLayerAlpha(440, 228, 480, 256, 140); Icons.draw(c, "up", 446, 228, 28, 1.6f, stroke); c.restoreToCount(l); }
        if (t.top + 6 < n) { int l = c.saveLayerAlpha(440, 820, 480, 848, 140); Icons.draw(c, "down", 446, 820, 28, 1.6f, stroke); c.restoreToCount(l); }
        preview(c, candidate);
        Assets.Wall cw = assets.walls.get(t.i);
        font("manrope", 400, 32, 0, PANEL_INK);
        draw(c, cw.name, 858, 842, 42, 32);
        float nw = width(cw.name);
        font("manrope", 400, 26, 0, MUTE);
        draw(c, (t.i + 1) + " of " + n, 858 + nw + 24, 847, 36, 26);
        draw(c, candidate.equals(wallId) ? "Applied" : (previewLook == null || !candidate.equals(previewLook.wall.id) ? "Loading " + cw.name + "…" : "Preview"), 858, 888, 36, 26);

    }

    private void drawRotation(Canvas c, Scene t) {
        sheet(c, shownWall, panel);
        backLink(c, "Background", false);
        title(c, "Rotation", 132);
        for (int i = 0; i < MODES.length; i++) {
            float y = 260 + i * 92;
            choiceRow(c, y, 88, i == t.i, null, 0, 0, 0, MODES[i][1], 164, 38, MODES[i][0].equals(mode), false);
        }
        font("manrope", 400, 26, 0, MUTE);
        draw(c, "Timed changes pause while Settings or another", 140, 731, 36, 26);
        draw(c, "panel is open.", 140, 767, 36, 26);
        preview(c, null);
        hint(c, "Apply selected setting");
    }

    private void headButton(Canvas c, float x, float w, String icon, String label, boolean focus, boolean idle) {
        r.set(x, 72, x + w, 136);
        if (focus) lightRow(c, r, 32);
        int col = focus ? DARK_TEXT : idle ? FAINT : MUTE;
        stroke.setColor(col);
        Icons.draw(c, icon, x + 24, 89, 30, 1.6f, stroke);
        font("manrope", 500, 30, -.3f, col);
        draw(c, label, x + 66, 86, 36, 30);
    }

    private void drawApps(Canvas c, Scene t) {
        sheet(c, shownWall, panel);
        title(c, "All apps", 72);
        headButton(c, 1515, 293, "home", "Apps on Home", t.zone == 0, false);
        List<String> items = alpha();
        int n = items.size(), rows = (n + COLS - 1) / COLS;
        float off = tween("gridTop", t.top * 240, 180, true);
        int s = c.save();
        c.clipRect(112, 232, 1808, 952);
        List<String> h = visibleHome();
        for (int i = 0; i < n; i++) {
            float x = 112 + (i % COLS) * 344, y = 232 + (i / COLS) * 240 - off;
            if (y > 952 || y + 216 < 232) continue;
            String id = items.get(i);
            boolean f = t.zone == 1 && i == t.i;
            r.set(x, y, x + 320, y + 216);
            if (f) plate(c, r, 32, .3f, .16f, .22f);
            int g = f ? -1 : c.saveLayerAlpha(x, y, x + 320, y + 216, 128);
            if (f) { c.save(); c.translate(0, -3); c.scale(1.04f, 1.04f, x + 160, y + 66); }
            mark(c, id, x + 100, y + 30, 120, 72, 58, PANEL_INK, f ? 1 : .88f);
            if (f) c.restore();
            font("manrope", 500, 34, -.6f, PANEL_INK);
            String label = ellipsize(name(id), 296);
            text.setTextAlign(Paint.Align.CENTER);
            draw(c, label, x + 160, y + 114, 44, 34);
            text.setTextAlign(Paint.Align.LEFT);
            if (g >= 0) c.restoreToCount(g);
            if (h.contains(id)) {
                int sa = f ? 255 : 128;
                int col = f ? PANEL_INK : MUTE;
                font("manrope", 500, 28, 0, col);
                float tw = width("On Home"), total = 26 + 8 + tw, sx = x + 160 - total / 2;
                int l = c.saveLayerAlpha(x, y + 160, x + 320, y + 204, sa);
                fill.setColor(col);
                c.drawCircle(sx + 13, y + 164 + 18, 10 * 26 / 24f, fill);
                stroke.setColor(0xff0d0d0d);
                Icons.draw(c, "tick", sx, y + 169, 26, 2f, stroke);
                font("manrope", 500, 28, 0, col);
                draw(c, "On Home", sx + 34, y + 164, 36, 28);
                c.restoreToCount(l);
            }
        }
        c.restoreToCount(s);
        if (t.top + 3 < rows) { stroke.setColor(PANEL_INK); Icons.draw(c, "down", 944, 980, 32, 1.6f, stroke); }
        if (t.top > 0) { stroke.setColor(PANEL_INK); Icons.draw(c, "up", 944, 192, 32, 1.6f, stroke); }
        if (catalog == null) { font("manrope", 500, 30, -.2f, MUTE); draw(c, "Loading installed apps…", 140, 148, 40, 30); }
    }

    private void drawEditor(Canvas c, Scene t) {
        sheet(c, shownWall, panel);
        title(c, "Apps on Home", 72);
        List<String> H = t.liftOrder != null ? t.liftOrder : visibleHome();
        String sub = t.liftOrder != null ? "Moving " + name(t.app) + ". OK places it, Back puts it back."
                : t.zone == 0 && t.h == 0 ? (undo.isEmpty() ? "Nothing to undo" : "Undo last change")
                : t.zone == 1 && t.part == 1 && !H.isEmpty() ? (H.size() <= 1 ? LAST : name(H.get(t.i)) + " stays installed.")
                : note != null ? note : catalog == null ? "Loading installed apps…" : "";
        if (!sub.isEmpty()) { font("manrope", 500, 30, -.2f, MUTE); draw(c, sub, 140, 148, 40, 30); }
        headButton(c, 1458, 170, "undo", "Undo", t.zone == 0 && t.h == 0 && t.liftOrder == null, undo.isEmpty());
        headButton(c, 1640, 168, "check", "Done", t.zone == 0 && t.h == 1 && t.liftOrder == null, false);
        font("manrope", 600, 30, -.2f, FAINT);
        draw(c, "On Home", 140, 216, 40, 30);
        float lw = width("On Home");
        font("manrope", 500, 24, 0, FAINT);
        draw(c, visibleHome().size() + " of " + CAP, 140 + lw + 14, 216, 40, 24);
        font("manrope", 600, 30, -.2f, FAINT);
        draw(c, "Not on Home", 1068, 216, 40, 30);

        for (int i = 0; i < H.size(); i++) {
            float y = 264 + i * 80 + (i >= 5 ? 24 : 0);
            String id = H.get(i);
            boolean here = t.zone == 1 && i == t.i, lifted = t.liftOrder != null && i == t.i;
            float dx = lifted ? 16 : 0;
            r.set(112 + dx, y, 680 + dx, y + 72);
            if (lifted) plate(c, r, 24, .44f, .27f, .34f);
            else if (here && t.part == 0) plate(c, r, 24, .3f, .16f, .22f);
            font("manrope", 500, 28, -.5f, here ? PANEL_INK : MUTE);
            text.setTextAlign(Paint.Align.CENTER);
            draw(c, String.valueOf(i + 1), 146 + dx, y + 19, 33.6f, 28);
            text.setTextAlign(Paint.Align.LEFT);
            mark(c, id, 184 + dx, y + 14, 56, 44, 36, PANEL_INK, 1);
            font("manrope", 500, 34, -.5f, PANEL_INK);
            draw(c, ellipsize(name(id), 340), 260 + dx, y + 16, 40.8f, 34);
            if (lifted) { stroke.setColor(PANEL_INK); Icons.draw(c, "updown", 620 + dx, y + 18, 36, 1.8f, stroke); }
            if (here && t.liftOrder == null) {
                r.set(688, y, 992, y + 72);
                boolean rf = t.part == 1;
                if (rf) lightRow(c, r, 24);
                String label = H.size() <= 1 ? LAST : "Remove from Home";
                font("manrope", 500, 30, -.3f, rf ? DARK_TEXT : MUTE);
                draw(c, label, 707, y + 18, 36, 30);
            }
        }
        if (H.size() > 5) {
            font("manrope", 400, 20, .1f, FAINT);
            draw(c, "Then scroll", 140, 264 + 5 * 80, 24, 20);
            float tw = width("Then scroll");
            fill.setColor(0x1ff6f1e7);
            c.drawRect(140 + tw + 16, 264 + 5 * 80 + 12, 964, 264 + 5 * 80 + 13, fill);
        }

        List<String> rec = otherRecent(), rest = otherRest();
        ArrayList<Object[]> entries = new ArrayList<>();
        float y = 0;
        if (!rec.isEmpty()) { entries.add(new Object[]{"Recently removed", y, -1}); y += 56; }
        int j = 0;
        float[] ys = new float[rec.size() + rest.size()];
        for (String id : rec) { ys[j] = y; entries.add(new Object[]{id, y, j++}); y += 80; }
        if (!rec.isEmpty() && !rest.isEmpty()) { entries.add(new Object[]{"Other installed apps", y, -1}); y += 56; }
        for (String id : rest) { ys[j] = y; entries.add(new Object[]{id, y, j++}); y += 80; }
        float focusY = ys.length == 0 ? 0 : ys[clamp(t.top, 0, ys.length - 1)];
        float scroll = clamp(focusY - 752 + 72 + 80, 0, Math.max(0, y - 752));
        if (t.zone != 2) scroll = 0;
        float so = tween("edScroll", scroll, 180, true);
        int s = c.save();
        c.clipRect(1040, 264, 1808, 1016);
        boolean full = visibleHome().size() >= CAP;
        for (Object[] e : entries) {
            float ey = 264 + (float) e[1] - so;
            int idx = (int) e[2];
            if (ey > 1016 || ey + 80 < 264) continue;
            if (idx < 0) { font("manrope", 600, 28, 0, FAINT); draw(c, (String) e[0], 1068, ey, 60, 28); continue; }
            String id = (String) e[0];
            boolean f = t.zone == 2 && idx == t.top;
            r.set(1040, ey, 1808, ey + 72);
            if (f) plate(c, r, 24, .3f, .16f, .22f);
            mark(c, id, 1064, ey + 14, 56, 44, 36, PANEL_INK, 1);
            font("manrope", 500, 34, -.5f, PANEL_INK);
            draw(c, ellipsize(name(id), 420), 1140, ey + 16, 40.8f, 34);
            if (f) {
                font("manrope", 600, 34, -.5f, full ? MUTE : PANEL_INK);
                String verb = full ? "Home is full" : "Add to Home";
                text.setTextAlign(Paint.Align.RIGHT);
                draw(c, verb, 1792, ey + 16, 40.8f, 34);
                text.setTextAlign(Paint.Align.LEFT);
            }
        }
        c.restoreToCount(s);
    }

    private void drawMenu(Canvas c, Scene t) {
        sheet(c, shownWall, panel);
        font("manrope", 500, 72, -2f, PANEL_INK);
        draw(c, t.title == null ? "" : t.title, 140, 300, 84, 72);
        float actsTop = 460;
        if (t.app == null && t.liftOrder != null && !t.liftOrder.isEmpty()) {
            font("manrope", 400, 34, 0, MUTE);
            draw(c, t.liftOrder.get(0), 140, 392, 44, 34);
            actsTop = 500;
        }
        float x = 112;
        for (int i = 0; i < t.actions.length; i++) {
            String label = t.actions[i][1];
            font("manrope", 500, 30, -.3f, i == t.i ? DARK_TEXT : MUTE);
            float w = width(label) + 48;
            r.set(x, actsTop, x + w, actsTop + 64);
            if (i == t.i) { lightRow(c, r, 32); font("manrope", 500, 30, -.3f, DARK_TEXT); }
            draw(c, label, x + 24, actsTop + 14, 36, 30);
            x += w + 12;
        }
    }

    // ================================================================ util

    static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
    static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
}
