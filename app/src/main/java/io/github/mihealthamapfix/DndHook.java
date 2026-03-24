package io.github.mihealthamapfix;

import android.app.Application;
import android.app.NotificationManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import io.github.mihealthamapfix.dnd.DndEnv;
import io.github.mihealthamapfix.dnd.DndRoute;
import io.github.mihealthamapfix.dnd.DndStartupRestore;
import io.github.mihealthamapfix.dnd.DndUtils;
import io.github.mihealthamapfix.dnd.RootEngine;
import io.github.mihealthamapfix.util.AppCtx;

import static io.github.mihealthamapfix.util.L.s;

/**
 * DND hooks for Mi Fitness on Android 15+.
 */
public final class DndHook {
    private static final String TAG = "AmapFix-DND";
    private static final String FITNESS_APP_CLASS = "com.xiaomi.fitness.FitnessApp";
    private static final String ZEN_UTILS_CLASS =
            "com.xiaomi.fitness.devicesettings.utils.ZenUtils";
    private static final String ZEN_MODE_SETTING_FRAGMENT_CLASS =
            "com.xiaomi.fitness.devicesettings.common.zenmode.ZenModeSettingFragment";
    private static final String ZEN_MODE_VIEW_MODEL_CLASS =
            "com.xiaomi.fitness.devicesettings.common.zenmode.ZenModeViewModel";
    private static final String LIFECYCLE_OWNER_CLASS = "androidx.lifecycle.LifecycleOwner";
    private static final int VERIFY_ATTEMPTS = 20;
    private static final long VERIFY_SLEEP_MS = 100L;
    private static final int MAX_STACK_FRAMES = 8;

    private static volatile boolean sInstalled;
    private static volatile boolean sGateBypassLogged;
    private static volatile boolean sPolicyBypassLogged;

    private DndHook() {
    }

    public static synchronized void install(ClassLoader cl) {
        if (sInstalled) {
            XposedBridge.log(TAG + ": " + s(
                    "install() 已跳过，本进程已经安装",
                    "install() skipped, already installed in this process"));
            return;
        }

        XposedBridge.log(TAG + ": " + s(
                "install() 开始，SDK=" + Build.VERSION.SDK_INT,
                "install() starting, SDK=" + Build.VERSION.SDK_INT));

        if (Build.VERSION.SDK_INT < 35) {
            XposedBridge.log(TAG + ": " + s(
                    "SDK < 35，跳过 DND Hook",
                    "SDK < 35, skipping DND hooks"));
            return;
        }

        logEnvironmentSummary(true, "install()");
        hookZenModeSupportGate(cl);
        hookIsNotificationPolicyAccessGranted();
        hookSetInterruptionFilter();
        hookFitnessAppOnCreate(cl);
        hookZenModeSettingDiagnostics(cl);

        sInstalled = true;
        XposedBridge.log(TAG + ": " + s(
                "全部 DND Hook 已安装",
                "All DND hooks installed"));
    }

    private static void logEnvironmentSummary(boolean forceRefresh, String prefix) {
        DndEnv.Snapshot snapshot = DndEnv.getSnapshot(forceRefresh);
        XposedBridge.log(TAG + ": " + prefix + " " + snapshot.summary());
    }

    private static void hookZenModeSupportGate(ClassLoader cl) {
        try {
            Class<?> zenUtils = cl.loadClass(ZEN_UTILS_CLASS);
            boolean hooked = false;
            for (Method method : zenUtils.getDeclaredMethods()) {
                if (!isPreciseZenSupportMethod(method)) continue;
                final Method target = method;
                XposedBridge.hookMethod(target, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        DndEnv.Snapshot snapshot = DndEnv.getSnapshot(false);
                        if (snapshot.route() == DndRoute.HOST_ROOT) {
                            param.setResult(true);
                            if (!sGateBypassLogged) {
                                sGateBypassLogged = true;
                                XposedBridge.log(TAG + ": " + s(
                                        "已按稳定 ROOT 路由放开勿扰功能入口，路由=",
                                        "DND feature gate bypassed by stable ROOT route, route=")
                                        + snapshot.route().routeLabel()
                                        + s("，状态=", ", status=") + snapshot.status());
                            }
                        }
                    }
                });
                hooked = true;
                XposedBridge.log(TAG + ": " + s(
                        "已精确 Hook 入口检查 ",
                        "Hooked precise gate ")
                        + target.getDeclaringClass().getName() + "." + target.getName());
            }

            if (!hooked) {
                XposedBridge.log(TAG + ": " + s(
                        "未找到精确的 Zen 入口检查方法",
                        "Precise Zen support gate not found"));
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": " + s(
                    "Hook 精确入口检查失败：",
                    "Failed to hook precise Zen support gate: ") + t);
        }
    }

    private static boolean isPreciseZenSupportMethod(Method method) {
        if (method.getReturnType() != boolean.class) return false;
        String name = method.getName();
        return "isSupportZenMode".equals(name) || "isSupportZenMode$default".equals(name);
    }

    private static void hookIsNotificationPolicyAccessGranted() {
        try {
            XposedHelpers.findAndHookMethod(
                    NotificationManager.class,
                    "isNotificationPolicyAccessGranted",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            DndEnv.Snapshot snapshot = DndEnv.getSnapshot(false);
                            if (Boolean.FALSE.equals(param.getResult())
                                    && snapshot.route() == DndRoute.HOST_ROOT) {
                                param.setResult(true);
                                if (!sPolicyBypassLogged) {
                                    sPolicyBypassLogged = true;
                                    XposedBridge.log(TAG + ": " + s(
                                            "已按稳定 ROOT 路由放行通知策略权限检查，路由=",
                                            "Notification policy access check bypassed by stable ROOT route, route=")
                                            + snapshot.route().routeLabel()
                                            + s("，状态=", ", status=") + snapshot.status());
                                }
                            }
                        }
                    }
            );
            XposedBridge.log(TAG + ": " + s(
                    "已 Hook isNotificationPolicyAccessGranted",
                    "Hooked isNotificationPolicyAccessGranted"));
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": " + s(
                    "Hook isNotificationPolicyAccessGranted 失败：",
                    "Failed to hook isNotificationPolicyAccessGranted: ") + t);
        }
    }

    private static void hookSetInterruptionFilter() {
        try {
            XposedHelpers.findAndHookMethod(
                    NotificationManager.class,
                    "setInterruptionFilter",
                    int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            int filter = (Integer) param.args[0];
                            int zen = DndUtils.filterToZen(filter);
                            if (zen < 0) return;

                            DndEnv.Snapshot snapshot = DndEnv.getSnapshot(false);
                            if (snapshot.route() != DndRoute.HOST_ROOT) {
                                XposedBridge.log(TAG + ": " + s(
                                        "当前没有可用的稳定 ROOT 路由，回退到系统原始写入",
                                        "No stable ROOT route is available; falling back to the original system DND write"));
                                return;
                            }

                            FailureContext failureContext = captureFailureContext(filter, zen);
                            if (trySetZenViaStableRoot(filter, zen, snapshot)) {
                                param.setResult(null);
                                return;
                            }

                            XposedBridge.log(TAG + ": " + s(
                                    "当前 DND 路由未能接管写入，回退到原始方法，路由=",
                                    "The current DND route could not intercept the write; falling back to the original method. Route=")
                                    + snapshot.route().routeLabel()
                                    + s("，状态=", ", status=") + snapshot.status());
                            XposedBridge.log(TAG + ": " + failureContext.summary());
                        }
                    }
            );
            XposedBridge.log(TAG + ": " + s(
                    "已 Hook setInterruptionFilter",
                    "Hooked setInterruptionFilter"));
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": " + s(
                    "Hook setInterruptionFilter 失败：",
                    "Failed to hook setInterruptionFilter: ") + t);
        }
    }

    private static boolean trySetZenViaStableRoot(int filter, int zen, DndEnv.Snapshot snapshot) {
        RootEngine engine = new RootEngine();
        if (!engine.isActive()) {
            XposedBridge.log(TAG + ": " + s(
                    "稳定 ROOT 路由未就绪，路由=",
                    "Stable ROOT route is not active, route=")
                    + snapshot.route().routeLabel()
                    + s("，状态=", ", status=") + engine.getStatus());
            return false;
        }

        try {
            engine.setZen(zen);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": " + s(
                    "稳定 ROOT 写入失败，路由=",
                    "Stable ROOT write failed, route=")
                    + snapshot.route().routeLabel()
                    + s("，状态=", ", status=") + engine.getStatus()
                    + s("，路径=", ", path=") + engine.getLastWritePath()
                    + s("，摘要=", ", summary=") + engine.getLastWriteSummary()
                    + s("，异常=", ", error=") + t.getClass().getSimpleName());
            return false;
        }

        VerificationResult verification = waitForRootZen(engine, zen);
        if (!verification.success) {
            XposedBridge.log(TAG + ": " + s(
                    "稳定 ROOT 校验失败，期望 zen=",
                    "Stable ROOT verification failed, expected zen=")
                    + zen
                    + s("，路由=", ", route=") + snapshot.route().routeLabel()
                    + s("，状态=", ", status=") + engine.getStatus()
                    + s("，路径=", ", path=") + engine.getLastWritePath()
                    + s("，摘要=", ", summary=") + engine.getLastWriteSummary()
                    + s("，读回=", ", readback=") + verification.detail);
            return false;
        }

        XposedBridge.log(TAG + ": " + s(
                "已通过稳定 ROOT 路由设置勿扰，backend=",
                "DND set via stable ROOT backend=")
                + snapshot.route().routeLabel()
                + s("，filter=", ", filter=") + filter
                + s("，zen=", ", zen=") + zen
                + s("，路径=", ", path=") + engine.getLastWritePath()
                + s("，摘要=", ", summary=") + engine.getLastWriteSummary()
                + s("，读回=", ", readback=") + verification.detail
                + s("，状态=", ", status=") + engine.getStatus());
        return true;
    }

    private static VerificationResult waitForRootZen(RootEngine engine, int expected) {
        RootEngine.Readback readback = null;
        for (int i = 0; i < VERIFY_ATTEMPTS; i++) {
            readback = engine.readCurrentState();
            if (readback.runtimeZen() == expected) {
                return new VerificationResult(true, describeRootReadback(expected, readback));
            }
            sleepForVerification();
        }
        readback = engine.readCurrentState();
        return new VerificationResult(readback.runtimeZen() == expected, describeRootReadback(expected, readback));
    }

    private static void sleepForVerification() {
        try {
            Thread.sleep(VERIFY_SLEEP_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String describeRootReadback(int expected, RootEngine.Readback readback) {
        return "expectedZen=" + expected + ", " + readback.summary();
    }

    private static FailureContext captureFailureContext(int filter, int zen) {
        String threadName = Thread.currentThread().getName();
        String stack = buildFilteredStack();
        String summary = s(
                "失败诊断：filter=",
                "Failure diagnostics: filter=")
                + filter
                + s("，zen=", ", zen=") + zen
                + s("，线程=", ", thread=") + threadName
                + s("，触发栈=", ", stack=") + stack;
        return new FailureContext(summary);
    }

    private static String buildFilteredStack() {
        StackTraceElement[] stack = new Throwable().getStackTrace();
        StringBuilder sb = new StringBuilder();
        int added = 0;
        for (StackTraceElement element : stack) {
            String className = element.getClassName();
            if (!isRelevantFrame(className)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(" <- ");
            }
            sb.append(className)
                    .append(".")
                    .append(element.getMethodName())
                    .append(":")
                    .append(element.getLineNumber());
            added++;
            if (added >= MAX_STACK_FRAMES) {
                break;
            }
        }
        if (added == 0) {
            for (int i = 2; i < Math.min(stack.length, 7); i++) {
                StackTraceElement element = stack[i];
                if (sb.length() > 0) {
                    sb.append(" <- ");
                }
                sb.append(element.getClassName())
                        .append(".")
                        .append(element.getMethodName())
                        .append(":")
                        .append(element.getLineNumber());
            }
        }
        return sb.toString();
    }

    private static boolean isRelevantFrame(String className) {
        return className.startsWith("com.xiaomi.")
                || className.startsWith("com.mi.")
                || className.startsWith("io.github.mihealthamapfix.")
                || className.startsWith("de.robv.android.xposed.")
                || className.startsWith("android.app.NotificationManager");
    }

    private static void hookFitnessAppOnCreate(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                    FITNESS_APP_CLASS,
                    cl,
                    "onCreate",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Application app = param.thisObject instanceof Application
                                    ? (Application) param.thisObject
                                    : AppCtx.app();
                            handleFitnessAppCreated(app);
                        }
                    });
            XposedBridge.log(TAG + ": " + s(
                    "已 Hook FitnessApp.onCreate",
                    "Hooked FitnessApp.onCreate"));
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": " + s(
                    "Hook FitnessApp.onCreate 失败：",
                    "Failed to hook FitnessApp.onCreate: ") + t);
        }
    }

    private static void handleFitnessAppCreated(Application app) {
        String processName = AppCtx.currentProcessName();
        boolean isMainProcess = AppCtx.isMainProcess(app);
        XposedBridge.log(TAG + ": " + s(
                "命中 FitnessApp.onCreate，进程=",
                "FitnessApp.onCreate hit, process=") + safeProcessName(processName)
                + s("，主进程=", ", mainProcess=") + isMainProcess);

        if (app == null) {
            XposedBridge.log(TAG + ": " + s(
                    "启动恢复已跳过：Application 为空",
                    "Startup restore skipped: Application is null"));
            return;
        }
        if (!isMainProcess) {
            XposedBridge.log(TAG + ": " + s(
                    "启动恢复已跳过：当前不是主进程",
                    "Startup restore skipped: current process is not the main process"));
            return;
        }

        logEnvironmentSummary(true, "onCreate");
        DndEnv.Snapshot snapshot = DndEnv.getSnapshot(false);
        if (!snapshot.canUsePrivilegedDnd()) {
            XposedBridge.log(TAG + ": " + s(
                    "启动恢复已跳过：当前没有可用 DND 路由",
                    "Startup restore skipped: no DND route is available"));
            return;
        }

        DndStartupRestore.schedule(app);
    }

    private static void hookZenModeSettingDiagnostics(ClassLoader cl) {
        hookZenModeSettingPage(cl);
        hookZenModeViewModelRequest(cl);
        hookZenModeObserverCallback(cl);
    }

    private static void hookZenModeSettingPage(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                    ZEN_MODE_SETTING_FRAGMENT_CLASS,
                    cl,
                    "onViewCreated",
                    View.class,
                    Bundle.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            XposedBridge.log(TAG + ": " + s(
                                    "已进入勿扰同步设置页，",
                                    "Entered the DND sync settings page, ")
                                    + DndEnv.getSnapshot(false).summary());
                        }
                    });
            XposedBridge.log(TAG + ": " + s(
                    "已 Hook ZenModeSettingFragment.onViewCreated",
                    "Hooked ZenModeSettingFragment.onViewCreated"));
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": " + s(
                    "Hook ZenModeSettingFragment.onViewCreated 失败：",
                    "Failed to hook ZenModeSettingFragment.onViewCreated: ") + t);
        }
    }

    private static void hookZenModeViewModelRequest(ClassLoader cl) {
        try {
            Class<?> ownerClass = cl.loadClass(LIFECYCLE_OWNER_CLASS);
            XposedHelpers.findAndHookMethod(
                    ZEN_MODE_VIEW_MODEL_CLASS,
                    cl,
                    "getZenModeSyncWithPhone",
                    ownerClass,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            XposedBridge.log(TAG + ": " + s(
                                    "已触发 ZenModeViewModel.getZenModeSyncWithPhone(LifecycleOwner)",
                                    "ZenModeViewModel.getZenModeSyncWithPhone(LifecycleOwner) was triggered"));
                        }
                    });
            XposedBridge.log(TAG + ": " + s(
                    "已 Hook ZenModeViewModel.getZenModeSyncWithPhone(LifecycleOwner)",
                    "Hooked ZenModeViewModel.getZenModeSyncWithPhone(LifecycleOwner)"));
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": " + s(
                    "Hook ZenModeViewModel.getZenModeSyncWithPhone(LifecycleOwner) 失败：",
                    "Failed to hook ZenModeViewModel.getZenModeSyncWithPhone(LifecycleOwner): ") + t);
        }
    }

    private static void hookZenModeObserverCallback(ClassLoader cl) {
        try {
            Class<?> fragmentClass = cl.loadClass(ZEN_MODE_SETTING_FRAGMENT_CLASS);
            boolean hooked = false;
            for (Method method : fragmentClass.getDeclaredMethods()) {
                final Method target = method;
                Class<?>[] parameterTypes = method.getParameterTypes();
                if (!Modifier.isStatic(target.getModifiers())) continue;
                if (parameterTypes.length != 2) continue;
                if (parameterTypes[0] != fragmentClass) continue;
                if (parameterTypes[1] != Boolean.class && parameterTypes[1] != boolean.class) continue;
                XposedBridge.hookMethod(target, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object value = param.args[1];
                        XposedBridge.log(TAG + ": " + s(
                                "设置页 observer 回调结果=",
                                "Settings page observer callback result=")
                                + String.valueOf(value)
                                + s("，回调=", ", callback=") + target.getName());
                    }
                });
                hooked = true;
                XposedBridge.log(TAG + ": " + s(
                        "已 Hook 设置页 observer 回调 ",
                        "Hooked settings page observer callback ")
                        + target.getName());
            }
            if (!hooked) {
                XposedBridge.log(TAG + ": " + s(
                        "未找到设置页 observer 回调路径",
                        "Settings page observer callback path was not found"));
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": " + s(
                    "Hook 设置页 observer 回调失败：",
                    "Failed to hook settings page observer callback: ") + t);
        }
    }

    private static String safeProcessName(String processName) {
        return processName == null || processName.isEmpty()
                ? s("<未知>", "<unknown>")
                : processName;
    }

    private static final class VerificationResult {
        private final boolean success;
        private final String detail;

        private VerificationResult(boolean success, String detail) {
            this.success = success;
            this.detail = detail;
        }
    }

    private static final class FailureContext {
        private final String summary;

        private FailureContext(String summary) {
            this.summary = summary;
        }

        private String summary() {
            return summary;
        }
    }
}
