package io.github.mihealthamapfix.dnd;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import io.github.mihealthamapfix.IDndBridgeService;

public class DndBridgeService extends Service {
    private static final String TAG = "AmapFix-DND";
    private DndEngine engine;
    private final IDndBridgeService.Stub binder = new IDndBridgeService.Stub() {
        @Override public void setZenMode(int mode) {
            if (!isEligible()) return; // ignore on <35
            ensureEngine();
            if (engine == null || !engine.isActive()) {
                Log.i(TAG, "setZen ignored: engine not active -> " + (engine==null?"null":engine.getStatus()));
                return;
            }
            try {
                engine.setZen(mode);
            } catch (Throwable t) {
                Log.w(TAG, "setZen error", t);
            }
        }
        @Override public int getZenMode() {
            if (!isEligible()) return -1;
            ensureEngine();
            if (engine == null || !engine.isActive()) return -1;
            return engine.getZen();
        }
        @Override public boolean isActive() {
            if (!isEligible()) return false;
            ensureEngine();
            return engine != null && engine.isActive();
        }
        @Override public String getStatus() {
            if (!isEligible()) return "sdk<35: disabled";
            return engine == null ? "engine=null" : engine.getStatus();
        }
    };

    @Override public IBinder onBind(Intent intent) {
        return binder;
    }

    private boolean isEligible() {
        return Build.VERSION.SDK_INT >= 35;
    }

    private void ensureEngine() {
        if (engine != null) return;
        // Prefer Shizuku; fallback to root
        DndEngine shizuku = null;
        try { shizuku = new ShizukuEngine(); } catch (Throwable ignored) {}
        if (shizuku != null && shizuku.isActive()) {
            engine = shizuku;
            Log.i(TAG, "Using Shizuku engine");
            return;
        }
        DndEngine root = null;
        try { root = new RootEngine(); } catch (Throwable ignored) {}
        if (root != null && root.isActive()) {
            engine = root;
            Log.i(TAG, "Using Root engine");
            return;
        }
        Log.i(TAG, "No engine active (shizuku/root missing)");
    }
}
