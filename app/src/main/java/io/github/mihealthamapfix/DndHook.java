package io.github.mihealthamapfix;

import android.app.NotificationManager;
import android.os.Build;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import static io.github.mihealthamapfix.util.L.s;

/**
 * All DND-related hooks for MiHealth on Android 15+ (SDK 35).
 *
 * 1) Forces isSupportZenMode() → true      (恢复勿扰同步开关)
 * 2) Forces isNotificationPolicyAccessGranted() → true  (通过权限检查)
 * 3) Intercepts setInterruptionFilter() → executes DND via root shell
 */
public final class DndHook {
    private static final String TAG = "AmapFix-DND";
    private static volatile boolean sWarned = false;

    public static void install(ClassLoader cl) {
        XposedBridge.log(TAG + ": install() " + s("开始，SDK=" + Build.VERSION.SDK_INT,
                "starting, SDK=" + Build.VERSION.SDK_INT));

        if (Build.VERSION.SDK_INT < 35) {
            XposedBridge.log(TAG + ": " + s("SDK < 35，跳过 DND 补丁", "SDK < 35, skipping DND hooks"));
            return;
        }

        hookIsSupportZenMode(cl);
        hookIsNotificationPolicyAccessGranted();
        hookSetInterruptionFilter();

        XposedBridge.log(TAG + ": " + s("全部 DND Hook 已安装", "All DND hooks installed"));
    }

    /**
     * Hook ZenUtilsKt.isSupportZenMode() → true.
     * 恢复设备设置中被隐藏的勿扰模式同步开关。
     */
    private static void hookIsSupportZenMode(ClassLoader cl) {
        String className = "com.xiaomi.fitness.devicesettings.utils.ZenUtilsKt";
        try {
            Class<?> zenUtilsKt = cl.loadClass(className);
            XposedBridge.log(TAG + ": " + s("已找到类 " + className, "Found class " + className));

            int hooked = 0;
            for (java.lang.reflect.Method m : zenUtilsKt.getDeclaredMethods()) {
                String name = m.getName();
                // Also try common obfuscated patterns & related methods
                if (name.contains("isSupportZenMode")
                        || name.contains("isSupportZenModeSync")
                        || name.contains("isSupportZenRuleSync")) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            param.setResult(true);
                        }
                    });
                    hooked++;
                    XposedBridge.log(TAG + ": " + s(
                            "已 Hook " + name + " (参数=" + m.getParameterCount() + ") → true",
                            "Hooked " + name + " (params=" + m.getParameterCount() + ") → true"));
                }
            }

            if (hooked == 0) {
                // Log all methods for diagnostics
                XposedBridge.log(TAG + ": " + s(
                        "未找到 isSupportZenMode 方法！该类的全部方法：",
                        "No isSupportZenMode found! All methods in this class:"));
                for (java.lang.reflect.Method m : zenUtilsKt.getDeclaredMethods()) {
                    XposedBridge.log(TAG + ":   " + m.getName()
                            + " (params=" + m.getParameterCount()
                            + ", ret=" + m.getReturnType().getSimpleName() + ")");
                }
            }
        } catch (ClassNotFoundException e) {
            XposedBridge.log(TAG + ": " + s(
                    "类 " + className + " 未找到！该类可能已被混淆。尝试扫描全部类...",
                    "Class " + className + " not found! Possibly obfuscated. Scanning..."));
            scanForZenModeMethod(cl);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": " + s("Hook isSupportZenMode 异常: ",
                    "isSupportZenMode hook error: ") + t);
        }
    }

    /**
     * If the class name is obfuscated, try to find the method by scanning
     * related known packages for boolean-returning static methods.
     */
    private static void scanForZenModeMethod(ClassLoader cl) {
        // Try alternative class names that might exist
        String[] candidates = {
                "com.xiaomi.fitness.devicesettings.utils.ZenUtilsKt",
                "com.xiaomi.fitness.devicesettings.utils.ZenUtils",
                "com.xiaomi.wearable.devicesettings.utils.ZenUtilsKt",
                "com.xiaomi.wearable.devicesettings.utils.ZenUtils",
        };
        for (String name : candidates) {
            try {
                Class<?> c = cl.loadClass(name);
                XposedBridge.log(TAG + ": " + s("扫描发现类: ", "Scan found class: ") + name);
                for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                    XposedBridge.log(TAG + ":   " + m.getName()
                            + " ret=" + m.getReturnType().getSimpleName());
                    // Hook any boolean method that might control zen mode
                    if (m.getReturnType() == boolean.class
                            && java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                        String mName = m.getName().toLowerCase();
                        if (mName.contains("zen") || mName.contains("support") || mName.contains("dnd")) {
                            XposedBridge.hookMethod(m, new XC_MethodHook() {
                                @Override
                                protected void beforeHookedMethod(MethodHookParam param) {
                                    param.setResult(true);
                                }
                            });
                            XposedBridge.log(TAG + ": " + s("已扫描并 Hook: ",
                                    "Scan-hooked: ") + name + "." + m.getName() + " → true");
                        }
                    }
                }
            } catch (ClassNotFoundException ignored) {}
            catch (Throwable t) {
                XposedBridge.log(TAG + ": " + s("扫描异常: ", "Scan error: ") + t);
            }
        }
    }

    /**
     * Hook NotificationManager.isNotificationPolicyAccessGranted() → true.
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
                                XposedBridge.log(TAG + ": isNotificationPolicyAccessGranted → true");
                            }
                        }
                    }
            );
            XposedBridge.log(TAG + ": " + s(
                    "已 Hook isNotificationPolicyAccessGranted",
                    "Hooked isNotificationPolicyAccessGranted"));
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": " + s("Hook isNotificationPolicyAccessGranted 失败: ",
                    "Failed to hook isNotificationPolicyAccessGranted: ") + t);
        }
    }

    /**
     * Hook NotificationManager.setInterruptionFilter() → execute DND via root.
     * root 命令直接在 Hook 进程内执行，无需跨进程桥接。
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
                            int filter = (int) param.args[0];
                            int zen = filterToZen(filter);
                            if (zen < 0) return;

                            // Execute DND change via root shell directly in this process
                            boolean ok = setZenViaRoot(zen);
                            if (ok) {
                                param.setResult(null); // skip original
                                XposedBridge.log(TAG + ": " + s(
                                        "已通过 root 设置 DND: zen=" + zen,
                                        "Set DND via root: zen=" + zen));
                            } else {
                                // Let the original method try (may fail silently on SDK 35)
                                XposedBridge.log(TAG + ": " + s(
                                        "root 执行失败，回退到原始方法",
                                        "Root failed, falling back to original method"));
                            }
                        }
                    }
            );
            XposedBridge.log(TAG + ": " + s(
                    "已 Hook setInterruptionFilter",
                    "Hooked setInterruptionFilter"));
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": " + s("Hook setInterruptionFilter 失败: ",
                    "Failed to hook setInterruptionFilter: ") + t);
        }
    }

    /**
     * Execute DND change via root shell directly.
     * Runs 'cmd notification set_zen_mode <mode>' or 'settings put global zen_mode <zen>'.
     */
    private static boolean setZenViaRoot(int zen) {
        try {
            String cmdName = zenToCmd(zen);
            // Try the notification command first
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c",
                    "cmd notification set_zen_mode " + cmdName});
            int exit = p.waitFor();
            if (exit == 0) return true;

            // Fallback to settings command
            Process p2 = Runtime.getRuntime().exec(new String[]{"su", "-c",
                    "settings put global zen_mode " + zen});
            int exit2 = p2.waitFor();
            return exit2 == 0;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": " + s("root 命令执行异常: ",
                    "Root command error: ") + t);
            return false;
        }
    }

    /** Map NotificationManager interruption filter to zen_mode int. */
    private static int filterToZen(int filter) {
        switch (filter) {
            case NotificationManager.INTERRUPTION_FILTER_ALL: return 0;
            case NotificationManager.INTERRUPTION_FILTER_PRIORITY: return 1;
            case NotificationManager.INTERRUPTION_FILTER_NONE: return 2;
            case NotificationManager.INTERRUPTION_FILTER_ALARMS: return 3;
            default: return -1;
        }
    }

    /** Map zen int to cmd string. */
    private static String zenToCmd(int zen) {
        switch (zen) {
            case 0: return "off";
            case 1: return "priority";
            case 2: return "none";
            case 3: return "alarms";
            default: return "off";
        }
    }
}
