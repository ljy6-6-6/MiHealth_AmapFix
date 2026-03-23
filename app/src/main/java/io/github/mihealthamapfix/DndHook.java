package io.github.mihealthamapfix;

import android.app.Application;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import io.github.mihealthamapfix.dnd.DndBridgeClient;
import io.github.mihealthamapfix.dnd.DndUtils;
import io.github.mihealthamapfix.util.AppCtx;

/**
 * All DND-related hooks for MiHealth on Android 15+ (SDK 35).
 *
 * 1) Forces isSupportZenMode() → true   (unhides the DND sync toggle)
 * 2) Forces isNotificationPolicyAccessGranted() → true   (app thinks it has DND policy access)
 * 3) Intercepts setInterruptionFilter() → routes through root/Shizuku bridge
 */
public final class DndHook {
    private static final String TAG = "AmapFix-DND";
    private static volatile boolean sWarned = false;

    public static void install(ClassLoader cl) {
        if (Build.VERSION.SDK_INT < 35) {
            Log.i(TAG, "SDK < 35, DND hooks not needed");
            return;
        }

        hookIsSupportZenMode(cl);
        hookIsNotificationPolicyAccessGranted();
        hookSetInterruptionFilter();
        hookAppOnCreate(cl);

        Log.i(TAG, "All DND hooks installed (SDK=" + Build.VERSION.SDK_INT + ")");
    }

    /**
     * Hook ZenUtilsKt.isSupportZenMode() to always return true.
     * This is a Kotlin top-level function compiled to a static method on the
     * class com.xiaomi.fitness.devicesettings.utils.ZenUtilsKt.
     */
    private static void hookIsSupportZenMode(ClassLoader cl) {
        try {
            Class<?> zenUtilsKt = cl.loadClass(
                    "com.xiaomi.fitness.devicesettings.utils.ZenUtilsKt");

            // Hook all methods named "isSupportZenMode" (including $default variant)
            int hooked = 0;
            for (java.lang.reflect.Method m : zenUtilsKt.getDeclaredMethods()) {
                if (m.getName().equals("isSupportZenMode")
                        || m.getName().equals("isSupportZenMode$default")) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            param.setResult(true);
                        }
                    });
                    hooked++;
                    Log.i(TAG, "Hooked " + m.getName() + " (params=" + m.getParameterCount() + ")");
                }
            }

            if (hooked == 0) {
                Log.w(TAG, "No isSupportZenMode methods found on ZenUtilsKt");
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to hook isSupportZenMode", t);
        }
    }

    /**
     * Hook NotificationManager.isNotificationPolicyAccessGranted() to return true.
     * This lets MiHealth's MediaSyncManager and NotifyService think the app has
     * DND policy access, so they don't block the sync pipeline.
     */
    private static void hookIsNotificationPolicyAccessGranted() {
        try {
            XposedHelpers.findAndHookMethod(
                    NotificationManager.class,
                    "isNotificationPolicyAccessGranted",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (Boolean.FALSE.equals(param.getResult())) {
                                param.setResult(true);
                                Log.d(TAG, "isNotificationPolicyAccessGranted → true");
                            }
                        }
                    }
            );
        } catch (Throwable t) {
            Log.w(TAG, "Failed to hook isNotificationPolicyAccessGranted", t);
        }
    }

    /**
     * Hook NotificationManager.setInterruptionFilter() to route DND changes
     * through our root/Shizuku bridge instead of the system API (which is
     * blocked for non-system apps targeting SDK 35).
     */
    private static void hookSetInterruptionFilter() {
        try {
            XposedHelpers.findAndHookMethod(
                    NotificationManager.class,
                    "setInterruptionFilter",
                    int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Application app = AppCtx.app();
                            Context ctx = app == null ? null : app.getApplicationContext();
                            if (ctx != null) {
                                DndBridgeClient.bindIfNeeded(ctx);
                            }
                            int zen = DndUtils.filterToZen((int) param.args[0]);
                            if (zen < 0) return;

                            if (DndBridgeClient.isActive()) {
                                DndBridgeClient.setZen(zen);
                                param.setResult(null); // skip original (no-op on target 35)
                                Log.i(TAG, "Routed DND via bridge: zen=" + zen);
                            } else {
                                if (!sWarned) {
                                    sWarned = true;
                                    Log.w(TAG, "Bridge not active; DND call may fail silently");
                                }
                            }
                        }
                    }
            );
        } catch (Throwable t) {
            Log.w(TAG, "Failed to hook setInterruptionFilter", t);
        }
    }

    /**
     * Hook Application.onCreate() to eagerly bind the DND bridge service,
     * so it's ready before the user reaches the DND settings page.
     */
    private static void hookAppOnCreate(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                    Application.class,
                    "onCreate",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Application app = (Application) param.thisObject;
                            if (app == null) return;
                            try {
                                DndBridgeClient.bindIfNeeded(app.getApplicationContext());
                                Log.i(TAG, "Eagerly started DND bridge bind");
                            } catch (Throwable t) {
                                Log.w(TAG, "Eager bridge bind failed", t);
                            }
                        }
                    }
            );
        } catch (Throwable t) {
            Log.w(TAG, "Failed to hook Application.onCreate", t);
        }
    }
}
