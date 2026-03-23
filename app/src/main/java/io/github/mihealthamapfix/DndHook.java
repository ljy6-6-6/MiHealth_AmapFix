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

import static io.github.mihealthamapfix.util.L.s;

/**
 * All DND-related hooks for MiHealth on Android 15+ (SDK 35).
 *
 * 1) Forces isSupportZenMode() → true      (恢复勿扰同步开关)
 * 2) Forces isNotificationPolicyAccessGranted() → true  (通过权限检查)
 * 3) Intercepts setInterruptionFilter() → routes through root/Shizuku bridge
 * 4) Hooks Application.onCreate() to eagerly bind the bridge service
 */
public final class DndHook {
    private static final String TAG = "AmapFix-DND";
    private static volatile boolean sWarned = false;

    public static void install(ClassLoader cl) {
        if (Build.VERSION.SDK_INT < 35) {
            Log.i(TAG, s("SDK < 35，无需 DND 补丁", "SDK < 35, DND hooks not needed"));
            return;
        }

        hookIsSupportZenMode(cl);
        hookIsNotificationPolicyAccessGranted();
        hookSetInterruptionFilter();
        hookAppOnCreate(cl);

        Log.i(TAG, s("已安装全部 DND Hook (SDK=" + Build.VERSION.SDK_INT + ")",
                "All DND hooks installed (SDK=" + Build.VERSION.SDK_INT + ")"));
    }

    /**
     * Hook ZenUtilsKt.isSupportZenMode() → true.
     * 恢复设备设置中被隐藏的勿扰模式同步开关。
     */
    private static void hookIsSupportZenMode(ClassLoader cl) {
        try {
            Class<?> zenUtilsKt = cl.loadClass(
                    "com.xiaomi.fitness.devicesettings.utils.ZenUtilsKt");

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
                    Log.i(TAG, s("已 Hook " + m.getName(),
                            "Hooked " + m.getName()));
                }
            }

            if (hooked == 0) {
                Log.w(TAG, s("未找到 isSupportZenMode 方法",
                        "No isSupportZenMode methods found on ZenUtilsKt"));
            }
        } catch (Throwable t) {
            Log.w(TAG, s("Hook isSupportZenMode 失败", "Failed to hook isSupportZenMode"), t);
        }
    }

    /**
     * Hook NotificationManager.isNotificationPolicyAccessGranted() → true.
     * 让 MediaSyncManager / NotifyService 认为已获得勿扰策略权限。
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
            Log.w(TAG, s("Hook isNotificationPolicyAccessGranted 失败",
                    "Failed to hook isNotificationPolicyAccessGranted"), t);
        }
    }

    /**
     * Hook NotificationManager.setInterruptionFilter() → DND Bridge.
     * 将实际的勿扰操作通过 root/Shizuku 桥接执行。
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
                                param.setResult(null);
                                Log.i(TAG, s("已通过桥接设置 DND: zen=" + zen,
                                        "Routed DND via bridge: zen=" + zen));
                            } else {
                                if (!sWarned) {
                                    sWarned = true;
                                    Log.w(TAG, s("桥接未激活，DND 操作可能失败",
                                            "Bridge not active; DND call may fail silently"));
                                }
                            }
                        }
                    }
            );
        } catch (Throwable t) {
            Log.w(TAG, s("Hook setInterruptionFilter 失败",
                    "Failed to hook setInterruptionFilter"), t);
        }
    }

    /**
     * Hook Application.onCreate() 提前绑定桥接服务。
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
                                Log.i(TAG, s("已提前绑定 DND 桥接服务",
                                        "Eagerly started DND bridge bind"));
                            } catch (Throwable t) {
                                Log.w(TAG, s("提前绑定桥接服务失败",
                                        "Eager bridge bind failed"), t);
                            }
                        }
                    }
            );
        } catch (Throwable t) {
            Log.w(TAG, s("Hook Application.onCreate 失败",
                    "Failed to hook Application.onCreate"), t);
        }
    }
}
