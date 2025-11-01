package io.github.mihealthamapfix;

import android.app.NotificationManager;
import android.app.Application;
import android.os.Build;
import android.util.Log;

import android.content.Context;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.AndroidAppHelper;

import io.github.mihealthamapfix.dnd.DndBridgeClient;
import io.github.mihealthamapfix.dnd.DndUtils;

/** Hooks NotificationManager#setInterruptionFilter in com.mi.health to route through our bridge on API 35+. */
public final class DndHook {
    private static final String TAG = "AmapFix-DND";
    private static volatile boolean sWarned = false;

    public static void install(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                NotificationManager.class,
                "setInterruptionFilter",
                int.class,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (Build.VERSION.SDK_INT < 35) return; // only on 35+
                        Context ctx = AndroidAppHelper.currentApplication();
                        if (ctx != null) {
                            DndBridgeClient.bindIfNeeded(ctx);
                        }
                        int zen = DndUtils.filterToZen((int) param.args[0]);
                        if (zen < 0) return;

                        if (DndBridgeClient.isActive()) {
                            DndBridgeClient.setZen(zen);
                            param.setResult(null); // skip original (which would be no-op on target 35)
                            Log.i(TAG, "Routed DND via bridge: zen=" + zen);
                        } else {
                            if (!sWarned) {
                                sWarned = true;
                                Log.i(TAG, "SDK>=35 but no su/shizuku; DND bridge disabled");
                            }
                        }
                    }
                }
            );
        } catch (Throwable t) {
            Log.w(TAG, "Failed to hook setInterruptionFilter", t);
        }
    }
}
