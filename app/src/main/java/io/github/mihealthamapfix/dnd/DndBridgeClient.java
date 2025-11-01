package io.github.mihealthamapfix.dnd;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Build;
import android.util.Log;

import java.util.concurrent.atomic.AtomicReference;

import io.github.mihealthamapfix.IDndBridgeService;

/** Lazy binder client used from inside the hooked app process. */
public final class DndBridgeClient {
    private static final String TAG = "AmapFix-DND";
    private static final AtomicReference<IDndBridgeService> sSvc = new AtomicReference<>();

    private DndBridgeClient() {}

    public static boolean isEligible() {
        return Build.VERSION.SDK_INT >= 35;
    }

    public static void bindIfNeeded(Context ctx) {
        if (!isEligible()) return;
        if (sSvc.get() != null) return;
        Intent i = new Intent();
        i.setComponent(new ComponentName("io.github.mihealthamapfix", "io.github.mihealthamapfix.dnd.DndBridgeService"));
        try {
            ctx.getApplicationContext().bindService(i, new ServiceConnection() {
                @Override public void onServiceConnected(ComponentName name, IBinder service) {
                    sSvc.set(IDndBridgeService.Stub.asInterface(service));
                    Log.i(TAG, "Bridge connected");
                }
                @Override public void onServiceDisconnected(ComponentName name) {
                    sSvc.set(null);
                    Log.i(TAG, "Bridge disconnected");
                }
            }, Context.BIND_AUTO_CREATE);
        } catch (Throwable t) {
            Log.w(TAG, "bind failed", t);
        }
    }

    public static boolean isActive() {
        IDndBridgeService s = sSvc.get();
        if (s == null) return false;
        try { return s.isActive(); } catch (Throwable t) { return false; }
    }

    public static void setZen(int zen) {
        IDndBridgeService s = sSvc.get();
        if (s == null) return;
        try { s.setZenMode(zen); } catch (Throwable ignored) {}
    }
}
