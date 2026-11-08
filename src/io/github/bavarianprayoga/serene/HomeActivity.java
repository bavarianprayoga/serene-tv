package io.github.bavarianprayoga.serene;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;

/** Home. Android keeps owning the Home key, power and dreams; this only draws and launches. */
public final class HomeActivity extends Activity {
    private HomeView home;
    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { if (home != null) home.refreshCatalog(); }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        home = new HomeView(this);
        setContentView(home);
        home.requestFocus();
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addAction(Intent.ACTION_PACKAGE_REPLACED);
        f.addDataScheme("package");
        registerReceiver(packages, f);
    }

    @Override protected void onResume() { super.onResume(); home.resumed(); }
    @Override protected void onPause() { home.paused(); super.onPause(); }
    @Override protected void onDestroy() { unregisterReceiver(packages); home.destroy(); super.onDestroy(); }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (Intent.ACTION_MAIN.equals(intent.getAction()) && intent.hasCategory(Intent.CATEGORY_HOME)) home.homePressed();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.KEYCODE_HOME) return super.dispatchKeyEvent(e);
        if (home.key(e)) return true;
        return super.dispatchKeyEvent(e);
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        home.trim(level >= TRIM_MEMORY_UI_HIDDEN);
    }

    /** Back on Home stays on Home. */
    @Override public void onBackPressed() { }
}
