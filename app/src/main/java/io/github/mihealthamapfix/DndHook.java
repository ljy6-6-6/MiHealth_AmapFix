package io.github.mihealthamapfix;

import android.app.NotificationManager;
import android.os.Build;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import static io.github.mihealthamapfix.util.L.s;

/**
 * All DND-related hooks for MiHealth on Android 15+ (SDK 35).
 *
 * Strategy:
 * 1. Find and hook isAboveAndroid15() → false   (the version gate, if it exists as a method)
 * 2. Hook handleDeviceSettingDND to spoof SDK_INT=34 during its execution
 *    (covers the case where the check is inlined)
 * 3. Hook isNotificationPolicyAccessGranted() → true
 * 4. Hook setInterruptionFilter() → execute DND via root
 */
public final class DndHook {
    private static final String TAG = "AmapFix-DND";

    public static void install(ClassLoader cl) {
        XposedBridge.log(TAG + ": install() " + s("开始，SDK=" + Build.VERSION.SDK_INT,
                "starting, SDK=" + Build.VERSION.SDK_INT));

        if (Build.VERSION.SDK_INT < 35) {
            XposedBridge.log(TAG + ": " + s("SDK < 35，跳过 DND 补丁", "SDK < 35, skipping DND hooks"));
            return;
        }

        hookZenModeChecks(cl);
        hookIsNotificationPolicyAccessGranted();
        hookSetInterruptionFilter();

        XposedBridge.log(TAG + ": " + s("全部 DND Hook 已安装", "All DND hooks installed"));
    }

    // ─── 核心：寻找并绕过 SDK 版本检查 ────────────────────────────────

    /**
     * Multi-strategy approach to bypass the isSupportZenMode / isAboveAndroid15 check.
     * The Kotlin compiler may have inlined isSupportZenMode, so:
     *  1) Scan relevant classes for isAboveAndroid15 / version-gate methods → hook to return false
     *  2) Hook handleDeviceSettingDND to temporarily set SDK_INT=34 while it runs
     */
    private static void hookZenModeChecks(ClassLoader cl) {
        boolean foundVersionGate = scanAndHookVersionGates(cl);

        // Always hook handleDeviceSettingDND to spoof SDK_INT — covers inlined checks
        hookHandleDeviceSettingDND(cl);

        if (!foundVersionGate) {
            XposedBridge.log(TAG + ": " + s(
                    "未找到独立的版本检查方法，依靠 SDK_INT 暂时改写来绕过内联检查",
                    "No standalone version-gate found, relying on SDK_INT spoofing for inlined checks"));
        }
    }

    /**
     * Scan for isAboveAndroid15() and related version-check methods.
     * Returns true if at least one gate method was found and hooked.
     */
    private static boolean scanAndHookVersionGates(ClassLoader cl) {
        boolean found = false;

        // Class names to scan (common MiHealth utility packages)
        String[] classNames = {
                "com.xiaomi.fitness.devicesettings.utils.ZenUtilsKt",
                "com.xiaomi.fitness.devicesettings.utils.ZenUtils",
                "com.xiaomi.fitness.utils.SystemKt",
                "com.xiaomi.fitness.utils.SystemUtils",
                "com.xiaomi.fitness.utils.VersionUtils",
                "com.xiaomi.fitness.utils.VersionUtilsKt",
                "com.xiaomi.fitness.utils.RomUtils",
                "com.xiaomi.fitness.utils.RomUtilsKt",
                "com.xiaomi.fitness.common.utils.SystemUtils",
                "com.xiaomi.fitness.common.utils.SystemUtilsKt",
                "com.xiaomi.fitness.common.utils.VersionUtils",
                "com.xiaomi.fitness.common.utils.VersionUtilsKt",
                "com.xiaomi.fitness.common.utils.RomUtils",
                "com.xiaomi.fitness.common.utils.RomUtilsKt",
                "com.xiaomi.fitness.common.utils.AndroidVersionKt",
                "com.xiaomi.fitness.common.utils.AndroidVersion",
                "com.xiaomi.wearable.utils.SystemUtils",
                "com.xiaomi.wearable.utils.VersionUtils",
                "com.xiaomi.wearable.common.utils.SystemUtils",
                "com.mi.health.utils.SystemUtils",
        };

        // Method name patterns that indicate version gate
        String[] gatePatterns = {"isabove", "android15", "iszenmode", "issupport", "zenmodesupport"};

        for (String className : classNames) {
            try {
                Class<?> c = cl.loadClass(className);
                for (Method m : c.getDeclaredMethods()) {
                    if (m.getReturnType() != boolean.class) continue;
                    String lname = m.getName().toLowerCase();
                    for (String pattern : gatePatterns) {
                        if (lname.contains(pattern)) {
                            XposedBridge.hookMethod(m, new XC_MethodHook() {
                                @Override
                                protected void beforeHookedMethod(MethodHookParam param) {
                                    // isAboveAndroid15 → false; isSupportZenMode → true
                                    boolean val = !lname.contains("above");
                                    param.setResult(val);
                                }
                            });
                            found = true;
                            XposedBridge.log(TAG + ": " + s(
                                    "已 Hook 版本检查 " + className + "." + m.getName() + " → " + !lname.contains("above"),
                                    "Hooked version gate " + className + "." + m.getName() + " → " + !lname.contains("above")));
                        }
                    }
                }
            } catch (ClassNotFoundException ignored) {
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": " + s("扫描异常: ", "Scan error: ") + className + " → " + t);
            }
        }
        return found;
    }

    /**
     * Hook handleDeviceSettingDND: temporarily set Build.VERSION.SDK_INT to 34
     * while the method runs, so any inlined "SDK_INT >= 35" check returns false.
     * This is the nuclear option that works even when isSupportZenMode is fully inlined.
     */
    private static void hookHandleDeviceSettingDND(ClassLoader cl) {
        try {
            Class<?> zenUtilsKt = cl.loadClass(
                    "com.xiaomi.fitness.devicesettings.utils.ZenUtilsKt");

            Method target = null;
            for (Method m : zenUtilsKt.getDeclaredMethods()) {
                if ("handleDeviceSettingDND".equals(m.getName())) {
                    target = m;
                    break;
                }
            }
            if (target == null) {
                XposedBridge.log(TAG + ": " + s(
                        "handleDeviceSettingDND 未找到",
                        "handleDeviceSettingDND not found"));
                return;
            }

            XposedBridge.hookMethod(target, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Field sdkInt = Build.VERSION.class.getField("SDK_INT");
                        sdkInt.setAccessible(true);
                        // Save original and spoof
                        param.setObjectExtra("original_sdk", Build.VERSION.SDK_INT);
                        XposedHelpers.setStaticIntField(Build.VERSION.class, "SDK_INT", 34);
                        XposedBridge.log(TAG + ": handleDeviceSettingDND → " + s(
                                "已将 SDK_INT 临时改为 34", "SDK_INT temporarily spoofed to 34"));
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": " + s("SDK_INT 改写失败: ",
                                "SDK_INT spoof failed: ") + t);
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object orig = param.getObjectExtra("original_sdk");
                        int origVal = orig instanceof Integer ? (int) orig : 35;
                        XposedHelpers.setStaticIntField(Build.VERSION.class, "SDK_INT", origVal);
                    } catch (Throwable t) {
                        // Ensure SDK_INT is always restored
                        try {
                            XposedHelpers.setStaticIntField(Build.VERSION.class, "SDK_INT", 35);
                        } catch (Throwable ignored) {}
                    }
                }
            });
            XposedBridge.log(TAG + ": " + s(
                    "已 Hook handleDeviceSettingDND (SDK_INT 改写模式)",
                    "Hooked handleDeviceSettingDND (SDK_INT spoof mode)"));

            // Also hook handleDeviceSetQuietMode with same strategy
            for (Method m : zenUtilsKt.getDeclaredMethods()) {
                if ("handleDeviceSetQuietMode".equals(m.getName())) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                param.setObjectExtra("original_sdk", Build.VERSION.SDK_INT);
                                XposedHelpers.setStaticIntField(Build.VERSION.class, "SDK_INT", 34);
                            } catch (Throwable ignored) {}
                        }
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object orig = param.getObjectExtra("original_sdk");
                                int origVal = orig instanceof Integer ? (int) orig : 35;
                                XposedHelpers.setStaticIntField(Build.VERSION.class, "SDK_INT", origVal);
                            } catch (Throwable ignored) {
                                try { XposedHelpers.setStaticIntField(Build.VERSION.class, "SDK_INT", 35); }
                                catch (Throwable ignored2) {}
                            }
                        }
                    });
                    XposedBridge.log(TAG + ": " + s(
                            "已 Hook handleDeviceSetQuietMode (SDK_INT 改写模式)",
                            "Hooked handleDeviceSetQuietMode (SDK_INT spoof mode)"));
                    break;
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": " + s("Hook handleDeviceSettingDND 异常: ",
                    "handleDeviceSettingDND hook error: ") + t);
        }
    }

    // ─── 权限欺骗 ────────────────────────────────────────────────────

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

    // ─── DND 命令拦截 ─────────────────────────────────────────────────

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

                            boolean ok = setZenViaRoot(zen);
                            if (ok) {
                                param.setResult(null);
                                XposedBridge.log(TAG + ": " + s(
                                        "已通过 root 设置 DND: zen=" + zen,
                                        "Set DND via root: zen=" + zen));
                            } else {
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

    // ─── Root 命令 ────────────────────────────────────────────────────

    private static boolean setZenViaRoot(int zen) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c",
                    "cmd notification set_zen_mode " + zenToCmd(zen)});
            int exit = p.waitFor();
            if (exit == 0) return true;

            Process p2 = Runtime.getRuntime().exec(new String[]{"su", "-c",
                    "settings put global zen_mode " + zen});
            return p2.waitFor() == 0;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": " + s("root 命令异常: ", "Root cmd error: ") + t);
            return false;
        }
    }

    // ─── 工具方法 ─────────────────────────────────────────────────────

    private static int filterToZen(int filter) {
        switch (filter) {
            case NotificationManager.INTERRUPTION_FILTER_ALL: return 0;
            case NotificationManager.INTERRUPTION_FILTER_PRIORITY: return 1;
            case NotificationManager.INTERRUPTION_FILTER_NONE: return 2;
            case NotificationManager.INTERRUPTION_FILTER_ALARMS: return 3;
            default: return -1;
        }
    }

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
