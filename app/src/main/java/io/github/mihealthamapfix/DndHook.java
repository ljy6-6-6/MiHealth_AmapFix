package io.github.mihealthamapfix;

import android.app.Application;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import io.github.mihealthamapfix.dnd.DndDiag;
import io.github.mihealthamapfix.dnd.DndEnv;
import io.github.mihealthamapfix.dnd.DndReconnectRestore;
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
    private static final String ECO_CONNECTION_STATE_CHANGE_RECEIVER_CLASS =
            "com.xiaomi.fitness.connect.export.EcoConnectionStateChangeReceiver";
    private static final String ECO_DEVICE_MANAGER_CONNECT_STATE_LISTENER_CLASS =
            "com.xiaomi.fitness.eco.device.manager.remote.EcoDeviceManagerRemoteImpl$mDeviceConnectStateListener$1";
    private static final String ECO_DEVICE_CONNECT_STATE_CHANGE_ACTION =
            "com.xiaomi.eco.DEVICE_CONNECT_STATE_CHANGE";
    private static final String EXTRA_CONNECT_STATE = "state";
    private static final String EXTRA_DID = "did";
    private static final String EXTRA_MAC = "mac";
    private static final String EXTRA_CURRENT_DEVICE = "current_device";
    private static final String DEVICE_SETTINGS_SYNCER_CLASS =
            "com.xiaomi.fitness.devicesettings.DeviceSettingsSyncer";
    private static final String DEVICE_SETTINGS_SYNCER_CALLBACK_CLASS =
            "com.xiaomi.fitness.devicesettings.DeviceSettingsSyncer$syncZenModeMode$2";
    private static final String ZEN_MODE_SYNC_HELPER_CLASS =
            "com.xiaomi.fitness.devicesettings.common.zenmode.ZenModeSyncHelper";
    private static final String ZEN_MODE_SETTING_FRAGMENT_CLASS =
            "com.xiaomi.fitness.devicesettings.common.zenmode.ZenModeSettingFragment";
    private static final String ZEN_MODE_VIEW_MODEL_CLASS =
            "com.xiaomi.fitness.devicesettings.common.zenmode.ZenModeViewModel";
    private static final String DEVICE_SETTINGS_PREFERENCE_CLASS =
            "com.xiaomi.fitness.devicesettings.utils.DeviceSettingsPreference";
    private static final String LIFECYCLE_OWNER_CLASS = "androidx.lifecycle.LifecycleOwner";
    private static final String KOTLIN_CONTINUATION_CLASS = "kotlin.coroutines.Continuation";
    private static final String KOTLIN_FUNCTION1_CLASS = "kotlin.jvm.functions.Function1";
    private static final String SETTINGS_PAGE_SESSION_KEY = "amapfix.dnd.settingsPageSession";
    private static final int CONNECT_STATE_CONNECT_SUCCESS = 2;
    private static final int CONNECT_STATE_BIND_SUCCESS = 5;
    private static final int VERIFY_ATTEMPTS = 20;
    private static final long VERIFY_SLEEP_MS = 100L;
    private static final long WEAR_RECONNECT_DEBOUNCE_MS = 3000L;
    private static final int MAX_STACK_FRAMES = 8;

    private static volatile boolean sInstalled;
    private static volatile boolean sGateBypassLogged;
    private static volatile boolean sPolicyBypassLogged;
    private static volatile String sLastWearReconnectDid;
    private static volatile long sLastWearReconnectAtUptimeMs;

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
        hookEcoConnectStateReceiver(cl);
        hookNativeReconnectSync(cl);
        hookNativeReconnectDiagnostics(cl);
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

        DndStartupRestore.scheduleStartup(app);
    }

    private static void hookEcoConnectStateReceiver(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                    ECO_CONNECTION_STATE_CHANGE_RECEIVER_CLASS,
                    cl,
                    "onReceive",
                    Context.class,
                    Intent.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Intent intent = param.args != null && param.args.length > 1 && param.args[1] instanceof Intent
                                    ? (Intent) param.args[1]
                                    : null;
                            if (intent == null) {
                                return;
                            }
                            String action = intent.getAction();
                            int connectState = intent.getIntExtra(EXTRA_CONNECT_STATE, Integer.MIN_VALUE);
                            String did = intent.getStringExtra(EXTRA_DID);
                            String mac = intent.getStringExtra(EXTRA_MAC);
                            boolean currentDevice = intent.getBooleanExtra(EXTRA_CURRENT_DEVICE, false);

                            if (!ECO_DEVICE_CONNECT_STATE_CHANGE_ACTION.equals(action)) {
                                return;
                            }

                            Application app = AppCtx.app();
                            boolean isMainProcess = AppCtx.isMainProcess(app);
                            boolean reconnectSuccess =
                                    connectState == CONNECT_STATE_CONNECT_SUCCESS
                                            || connectState == CONNECT_STATE_BIND_SUCCESS;

                            if (!isMainProcess) {
                                XposedBridge.log(TAG + ": " + s(
                                        "连接广播诊断：已忽略非主进程 action=",
                                        "Reconnect broadcast diagnostic: ignored non-main-process action=")
                                        + action
                                        + s("，state=", ", state=") + connectState
                                        + s("，did=", ", did=") + safeDid(did)
                                        + s("，mac=", ", mac=") + safeDid(mac)
                                        + s("，current_device=", ", current_device=") + currentDevice
                                        + s("，进程=", ", process=") + safeProcessName(AppCtx.currentProcessName()));
                                return;
                            }

                            if (!reconnectSuccess) {
                                XposedBridge.log(TAG + ": " + s(
                                        "连接广播诊断：已忽略 action=",
                                        "Reconnect broadcast diagnostic: ignored action=")
                                        + action
                                        + s("，state=", ", state=") + connectState
                                        + s("，did=", ", did=") + safeDid(did)
                                        + s("，mac=", ", mac=") + safeDid(mac)
                                        + s("，current_device=", ", current_device=") + currentDevice);
                                return;
                            }

                            XposedBridge.log(TAG + ": " + s(
                                    "连接广播诊断：命中成功态，当前仅记录诊断，action=",
                                    "Reconnect broadcast diagnostic: success state hit; diagnostic only, action=")
                                    + action
                                    + s("，state=", ", state=") + connectState
                                    + s("，did=", ", did=") + safeDid(did)
                                    + s("，mac=", ", mac=") + safeDid(mac)
                                    + s("，current_device=", ", current_device=") + currentDevice);
                        }
                    });
            XposedBridge.log(TAG + ": " + s(
                    "已 Hook EcoConnectionStateChangeReceiver.onReceive",
                    "Hooked EcoConnectionStateChangeReceiver.onReceive"));
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": " + s(
                    "Hook EcoConnectionStateChangeReceiver.onReceive 失败：",
                    "Failed to hook EcoConnectionStateChangeReceiver.onReceive: ") + t);
        }
    }

    private static void hookNativeReconnectSync(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                    ECO_DEVICE_MANAGER_CONNECT_STATE_LISTENER_CLASS,
                    cl,
                    "onConnectSuccess",
                    String.class,
                    String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            DndEnv.Snapshot snapshot = DndEnv.getSnapshot(false);
                            if (!snapshot.canUsePrivilegedDnd()) {
                                XposedBridge.log(TAG + ": " + s(
                                        "已跳过 onConnectSuccess 诊断：当前没有可用 DND 路由",
                                        "Skipped onConnectSuccess diagnostic: no DND route is available"));
                                return;
                            }

                            String did = param.args != null && param.args.length > 0 && param.args[0] instanceof String
                                    ? (String) param.args[0]
                                    : null;
                            String mac = param.args != null && param.args.length > 1 && param.args[1] instanceof String
                                    ? (String) param.args[1]
                                    : null;
                            if (did == null || did.isEmpty()) {
                                XposedBridge.log(TAG + ": " + s(
                                        "onConnectSuccess 已命中，但 DID 为空",
                                        "onConnectSuccess was hit, but the DID is empty"));
                                return;
                            }

                            String sessionId = DndDiag.newSessionId("native_reconnect");
                            DndDiag.mark("native_reconnect", sessionId, did);
                            XposedBridge.log(TAG + ": " + s(
                                    "onConnectSuccess 已命中（仅诊断），DID=",
                                    "onConnectSuccess was hit (diagnostic only), DID=") + did
                                    + s("，mac=", ", mac=") + safeDid(mac)
                                    + s("，session=", ", session=") + sessionId);
                        }
                    });
            XposedBridge.log(TAG + ": " + s(
                    "已 Hook onConnectSuccess 原生 reconnect 补同步入口",
                    "Hooked native reconnect DND sync entry on onConnectSuccess"));
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": " + s(
                    "Hook onConnectSuccess 原生 reconnect 补同步入口失败：",
                    "Failed to hook the native reconnect DND sync entry on onConnectSuccess: ") + t);
        }
    }

    private static void hookNativeReconnectDiagnostics(ClassLoader cl) {
        hookSyncZenModeModeEntry(cl);
        hookSyncZenModeModeCallbacks(cl);
        hookNativeFollowupDiagnostics(cl);
    }

    private static void hookSyncZenModeModeEntry(ClassLoader cl) {
        try {
            Class<?> continuationClass = cl.loadClass(KOTLIN_CONTINUATION_CLASS);
            XposedHelpers.findAndHookMethod(
                    DEVICE_SETTINGS_SYNCER_CLASS,
                    cl,
                    "syncZenModeMode",
                    String.class,
                    continuationClass,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            String did = param.args != null && param.args.length > 0 && param.args[0] instanceof String
                                    ? (String) param.args[0]
                                    : null;
                            String scopedSource = DndDiag.currentScopedSource();
                            String scopedSessionId = DndDiag.currentScopedSessionId();
                            if (isInternalSyncZenModeSource(scopedSource)) {
                                XposedBridge.log(TAG + ": " + s(
                                        "已忽略内部 syncZenModeMode 入口，source=",
                                        "Ignored internal syncZenModeMode entry, source=")
                                        + scopedSource
                                        + s("，session=", ", session=") + String.valueOf(scopedSessionId)
                                        + s("，DID=", ", DID=") + safeDid(did)
                                        + s("，", ", ") + DndDiag.contextSummary());
                                return;
                            }

                            XposedBridge.log(TAG + ": " + s(
                                    "原生 reconnect DND 同步入口已触发，DID=",
                                    "Native reconnect DND sync entry was triggered, DID=") + safeDid(did)
                                    + s("，", ", ") + DndDiag.contextSummary());

                            DndEnv.Snapshot snapshot = DndEnv.getSnapshot(false);
                            if (!snapshot.canUsePrivilegedDnd()) {
                                XposedBridge.log(TAG + ": " + s(
                                        "已跳过 syncZenModeMode 外部入口调度：当前没有可用 DND 路由，DID=",
                                        "Skipped scheduling from external syncZenModeMode entry: no DND route is available, DID=")
                                        + safeDid(did));
                                return;
                            }
                            if (did == null || did.isEmpty()) {
                                XposedBridge.log(TAG + ": " + s(
                                        "已跳过 syncZenModeMode 外部入口调度：DID 为空",
                                        "Skipped scheduling from external syncZenModeMode entry: DID is empty"));
                                return;
                            }
                            if (!shouldRunNativeReconnectSync(did)) {
                                XposedBridge.log(TAG + ": " + s(
                                        "已跳过重复的 syncZenModeMode 外部入口调度，DID=",
                                        "Skipped duplicate scheduling from external syncZenModeMode entry, DID=")
                                        + did);
                                return;
                            }

                            String sessionId = DndDiag.newSessionId("native_reconnect");
                            DndDiag.mark("native_reconnect", sessionId, did);
                            Application app = AppCtx.app();
                            if (app == null) {
                                XposedBridge.log(TAG + ": " + s(
                                        "syncZenModeMode 外部入口已命中，但 Application 为空，无法调度 reconnect_headless，DID=",
                                        "External syncZenModeMode entry was hit, but Application is null; unable to schedule reconnect_headless, DID=")
                                        + did
                                        + s("，session=", ", session=") + sessionId);
                                return;
                            }

                            DndReconnectRestore.scheduleReconnect(app, "native_sync_entry", did);
                            XposedBridge.log(TAG + ": " + s(
                                    "已在 syncZenModeMode 外部入口后调度 reconnect_headless，DID=",
                                    "Scheduled reconnect_headless after external syncZenModeMode entry, DID=")
                                    + did
                                    + s("，session=", ", session=") + sessionId);
                        }
                    });
            XposedBridge.log(TAG + ": " + s(
                    "已 Hook DeviceSettingsSyncer.syncZenModeMode",
                    "Hooked DeviceSettingsSyncer.syncZenModeMode"));
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": " + s(
                    "Hook DeviceSettingsSyncer.syncZenModeMode 失败：",
                    "Failed to hook DeviceSettingsSyncer.syncZenModeMode: ") + t);
        }
    }

    private static void hookSyncZenModeModeCallbacks(ClassLoader cl) {
        try {
            Class<?> syncResultClass = cl.loadClass("com.xiaomi.fitness.device.contact.export.SyncResult");
            XposedHelpers.findAndHookMethod(
                    DEVICE_SETTINGS_SYNCER_CALLBACK_CLASS,
                    cl,
                    "onSuccess",
                    String.class,
                    int.class,
                    syncResultClass,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            String did = readSyncDeviceDid(param.thisObject);
                            Boolean syncWithPhone = extractSyncWithPhone(param.args[2]);
                            String followup = resolveNativeFollowup(cl);
                            XposedBridge.log(TAG + ": " + s(
                                    "原生 reconnect DND 同步成功，DID=",
                                    "Native reconnect DND sync succeeded, DID=") + safeDid(did)
                                    + s("，syncWithPhone=", ", syncWithPhone=") + String.valueOf(syncWithPhone)
                                    + s("，后续链路=", ", followup=") + followup
                                    + s("，", ", ") + DndDiag.contextSummary());
                        }
                    });

            XposedHelpers.findAndHookMethod(
                    DEVICE_SETTINGS_SYNCER_CALLBACK_CLASS,
                    cl,
                    "onError",
                    String.class,
                    int.class,
                    int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            String did = readSyncDeviceDid(param.thisObject);
                            int errorCode = param.args != null && param.args.length > 2 && param.args[2] instanceof Integer
                                    ? (Integer) param.args[2]
                                    : Integer.MIN_VALUE;
                            String followup = resolveNativeFollowup(cl);
                            XposedBridge.log(TAG + ": " + s(
                                    "原生 reconnect DND 同步失败，DID=",
                                    "Native reconnect DND sync failed, DID=") + safeDid(did)
                                    + s("，errorCode=", ", errorCode=") + errorCode
                                    + s("，后续链路=", ", followup=") + followup
                                    + s("，", ", ") + DndDiag.contextSummary());
                        }
                    });
            XposedBridge.log(TAG + ": " + s(
                    "已 Hook syncZenModeMode 回调诊断",
                    "Hooked syncZenModeMode callback diagnostics"));
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": " + s(
                    "Hook syncZenModeMode 回调诊断失败：",
                    "Failed to hook syncZenModeMode callback diagnostics: ") + t);
        }
    }

    private static synchronized boolean shouldRunNativeReconnectSync(String did) {
        long now = SystemClock.uptimeMillis();
        if (did.equals(sLastWearReconnectDid)
                && now - sLastWearReconnectAtUptimeMs < WEAR_RECONNECT_DEBOUNCE_MS) {
            return false;
        }
        sLastWearReconnectDid = did;
        sLastWearReconnectAtUptimeMs = now;
        return true;
    }

    private static String readSyncDeviceDid(Object callback) {
        try {
            Object did = XposedHelpers.getObjectField(callback, "$syncDeviceDid");
            return did instanceof String ? (String) did : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Boolean extractSyncWithPhone(Object syncResult) {
        if (syncResult == null) {
            return null;
        }
        try {
            Object packet = XposedHelpers.callMethod(syncResult, "getPacket");
            if (packet == null) return null;
            Object h9q = XposedHelpers.callMethod(packet, "E");
            if (h9q == null) return null;
            Object j9q = XposedHelpers.callMethod(h9q, "L");
            if (j9q == null) return null;
            Object config = XposedHelpers.getObjectField(j9q, "d");
            if (config == null) return null;
            return XposedHelpers.getBooleanField(config, "c");
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String resolveNativeFollowup(ClassLoader cl) {
        try {
            Class<?> helperClass = cl.loadClass(ZEN_MODE_SYNC_HELPER_CLASS);
            Object instance = XposedHelpers.getStaticObjectField(helperClass, "INSTANCE");
            Method method = helperClass.getMethod("isSupportZenRuleSync", Context.class);
            boolean support = Boolean.TRUE.equals(method.invoke(instance, AppCtx.app()));
            return support ? "getDeviceZenRules" : "postSyncZenMode";
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private static String safeDid(String did) {
        return did == null || did.isEmpty() ? s("<空>", "<empty>") : did;
    }

    private static void hookZenModeSettingDiagnostics(ClassLoader cl) {
        hookZenModeSettingPage(cl);
        hookZenModeSettingObserverCallbacks(cl);
        hookZenModeSettingActions(cl);
        hookZenModeViewModelRequest(cl);
        hookZenModeViewModelHandlerDiagnostics(cl);
        hookZenListenerDiagnostics(cl);
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
                            Object fragment = param.thisObject;
                            String did = resolveZenModeSettingFragmentDid(fragment);
                            String sessionId = DndDiag.newSessionId("settings_page");
                            rememberSettingsPageSession(fragment, sessionId);
                            DndDiag.mark("settings_page", sessionId, did);
                            XposedBridge.log(TAG + ": " + s(
                                    "已进入勿扰同步设置页，DID=",
                                    "Entered the DND sync settings page, DID=") + safeDid(did)
                                    + s("，session=", ", session=") + sessionId
                                    + s("，本地状态=", ", localState=") + readLocalZenSyncState(fragment, did));
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

    private static void hookZenModeSettingObserverCallbacks(ClassLoader cl) {
        try {
            Class<?> fragmentClass = cl.loadClass(ZEN_MODE_SETTING_FRAGMENT_CLASS);
            boolean hooked = false;
            for (Method method : fragmentClass.getDeclaredMethods()) {
                Class<?>[] parameterTypes = method.getParameterTypes();
                if (!Modifier.isStatic(method.getModifiers())) continue;
                if (!method.getName().startsWith("onViewCreated$lambda$")) continue;
                if (parameterTypes.length != 2) continue;
                if (parameterTypes[0] != fragmentClass) continue;
                if (parameterTypes[1] != Boolean.class && parameterTypes[1] != boolean.class) continue;
                final Method target = method;
                XposedBridge.hookMethod(target, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Object fragment = param.args != null && param.args.length > 0 ? param.args[0] : null;
                        String did = resolveZenModeSettingFragmentDid(fragment);
                        param.setObjectExtra("amapfix.did", did);
                        param.setObjectExtra("amapfix.localBefore", readLocalZenSyncState(fragment, did));
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object fragment = param.args != null && param.args.length > 0 ? param.args[0] : null;
                        String did = objectExtraToString(param.getObjectExtra("amapfix.did"));
                        boolean localBefore = objectExtraToBoolean(param.getObjectExtra("amapfix.localBefore"));
                        boolean localAfter = readLocalZenSyncState(fragment, did);
                        String sessionId = settingsPageSession(fragment);
                        DndDiag.mark("settings_page", sessionId, did);
                        Object result = param.args != null && param.args.length > 1 ? param.args[1] : null;
                        XposedBridge.log(TAG + ": " + s(
                                "设置页 observer 回调，方法=",
                                "Settings page observer callback, method=") + target.getName()
                                + s("，结果=", ", result=") + String.valueOf(result)
                                + s("，DID=", ", DID=") + safeDid(did)
                                + s("，本地前=", ", localBefore=") + localBefore
                                + s("，本地后=", ", localAfter=") + localAfter
                                + s("，session=", ", session=") + sessionId);
                    }
                });
                hooked = true;
                XposedBridge.log(TAG + ": " + s(
                        "已 Hook 设置页 observer 回调 ",
                        "Hooked settings page observer callback ") + target.getName());
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

    private static void hookZenModeSettingActions(ClassLoader cl) {
        hookZenModeSettingAction(cl, "onZenModeOpen", boolean.class);
        hookZenModeSettingAction(cl, "onZenModeClose");
        hookZenModeSettingAction(cl, "setZenModeSyncWithPhone", boolean.class);
    }

    private static void hookZenModeSettingAction(ClassLoader cl, String methodName, Object... parameterTypes) {
        try {
            Object[] callbackSignature = new Object[parameterTypes.length + 1];
            System.arraycopy(parameterTypes, 0, callbackSignature, 0, parameterTypes.length);
            callbackSignature[parameterTypes.length] = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Object fragment = param.thisObject;
                    String did = resolveZenModeSettingFragmentDid(fragment);
                    param.setObjectExtra("amapfix.did", did);
                    param.setObjectExtra("amapfix.localBefore", readLocalZenSyncState(fragment, did));
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object fragment = param.thisObject;
                    String did = objectExtraToString(param.getObjectExtra("amapfix.did"));
                    boolean localBefore = objectExtraToBoolean(param.getObjectExtra("amapfix.localBefore"));
                    boolean localAfter = readLocalZenSyncState(fragment, did);
                    String sessionId = settingsPageSession(fragment);
                    DndDiag.mark("settings_page", sessionId, did);
                    String argSummary = summarizeArgs(param.args);
                    XposedBridge.log(TAG + ": " + s(
                            "设置页方法命中，方法=",
                            "Settings page method hit, method=") + methodName
                            + s("，参数=", ", args=") + argSummary
                            + s("，DID=", ", DID=") + safeDid(did)
                            + s("，本地前=", ", localBefore=") + localBefore
                            + s("，本地后=", ", localAfter=") + localAfter
                            + s("，session=", ", session=") + sessionId);
                }
            };
            XposedHelpers.findAndHookMethod(ZEN_MODE_SETTING_FRAGMENT_CLASS, cl, methodName, callbackSignature);
            XposedBridge.log(TAG + ": " + s(
                    "已 Hook 设置页方法 ",
                    "Hooked settings page method ") + methodName);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": " + s(
                    "Hook 设置页方法失败 ",
                    "Failed to hook settings page method ") + methodName + ": " + t);
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
                            String did = resolveZenModeViewModelDid(param.thisObject);
                            XposedBridge.log(TAG + ": " + s(
                                    "已触发 ZenModeViewModel.getZenModeSyncWithPhone(LifecycleOwner)",
                                    "ZenModeViewModel.getZenModeSyncWithPhone(LifecycleOwner) was triggered")
                                    + s("，DID=", ", DID=") + safeDid(did)
                                    + s("，", ", ") + DndDiag.contextSummary());
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

    private static void hookZenModeViewModelHandlerDiagnostics(ClassLoader cl) {
        hookSimpleNoArgMethod(
                cl,
                ZEN_MODE_VIEW_MODEL_CLASS,
                "registerSystemDataHandler",
                s("ZenModeViewModel.registerSystemDataHandler 已触发，DID=",
                        "ZenModeViewModel.registerSystemDataHandler was triggered, DID="),
                true);
        hookSimpleNoArgMethod(
                cl,
                ZEN_MODE_VIEW_MODEL_CLASS,
                "unregisterSystemDataHandler",
                s("ZenModeViewModel.unregisterSystemDataHandler 已触发，DID=",
                        "ZenModeViewModel.unregisterSystemDataHandler was triggered, DID="),
                true);
    }

    private static void hookZenListenerDiagnostics(ClassLoader cl) {
        hookSimpleNoArgMethod(
                cl,
                ZEN_UTILS_CLASS,
                "registerZenListener",
                s("ZenUtils.registerZenListener 已触发",
                        "ZenUtils.registerZenListener was triggered"),
                false);
        hookSimpleNoArgMethod(
                cl,
                ZEN_MODE_SYNC_HELPER_CLASS,
                "registerZenModeListener",
                s("ZenModeSyncHelper.registerZenModeListener 已触发",
                        "ZenModeSyncHelper.registerZenModeListener was triggered"),
                false);
    }

    private static void hookNativeFollowupDiagnostics(ClassLoader cl) {
        hookPostSyncZenMode(cl);
        hookGetDeviceZenRules(cl);
        hookSetZenModeSyncWithPhone(cl);
        hookHandleDeviceSettingDnd(cl);
        hookUpdatePhoneZenRules(cl);
    }

    private static void hookPostSyncZenMode(ClassLoader cl) {
        try {
            Class<?> continuationClass = cl.loadClass(KOTLIN_CONTINUATION_CLASS);
            XposedHelpers.findAndHookMethod(
                    ZEN_UTILS_CLASS,
                    cl,
                    "postSyncZenMode",
                    continuationClass,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            XposedBridge.log(TAG + ": " + s(
                                    "ZenUtils.postSyncZenMode 已触发",
                                    "ZenUtils.postSyncZenMode was triggered")
                                    + s("，", ", ") + DndDiag.contextSummary());
                        }
                    });
            XposedBridge.log(TAG + ": " + s(
                    "已 Hook ZenUtils.postSyncZenMode",
                    "Hooked ZenUtils.postSyncZenMode"));
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": " + s(
                    "Hook ZenUtils.postSyncZenMode 失败：",
                    "Failed to hook ZenUtils.postSyncZenMode: ") + t);
        }
    }

    private static void hookGetDeviceZenRules(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                    ZEN_MODE_SYNC_HELPER_CLASS,
                    cl,
                    "getDeviceZenRules",
                    Context.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            XposedBridge.log(TAG + ": " + s(
                                    "ZenModeSyncHelper.getDeviceZenRules 已触发",
                                    "ZenModeSyncHelper.getDeviceZenRules was triggered")
                                    + s("，", ", ") + DndDiag.contextSummary());
                        }
                    });
            XposedBridge.log(TAG + ": " + s(
                    "已 Hook ZenModeSyncHelper.getDeviceZenRules",
                    "Hooked ZenModeSyncHelper.getDeviceZenRules"));
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": " + s(
                    "Hook ZenModeSyncHelper.getDeviceZenRules 失败：",
                    "Failed to hook ZenModeSyncHelper.getDeviceZenRules: ") + t);
        }
    }

    private static void hookSetZenModeSyncWithPhone(ClassLoader cl) {
        try {
            Class<?> function1Class = cl.loadClass(KOTLIN_FUNCTION1_CLASS);
            Class<?> continuationClass = cl.loadClass(KOTLIN_CONTINUATION_CLASS);
            XposedHelpers.findAndHookMethod(
                    ZEN_UTILS_CLASS,
                    cl,
                    "setZenModeSyncWithPhone",
                    boolean.class,
                    function1Class,
                    continuationClass,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            boolean enabled = param.args != null
                                    && param.args.length > 0
                                    && param.args[0] instanceof Boolean
                                    && (Boolean) param.args[0];
                            XposedBridge.log(TAG + ": " + s(
                                    "ZenUtils.setZenModeSyncWithPhone 已触发，enabled=",
                                    "ZenUtils.setZenModeSyncWithPhone was triggered, enabled=") + enabled
                                    + s("，", ", ") + DndDiag.contextSummary());
                        }
                    });
            XposedBridge.log(TAG + ": " + s(
                    "已 Hook ZenUtils.setZenModeSyncWithPhone",
                    "Hooked ZenUtils.setZenModeSyncWithPhone"));
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": " + s(
                    "Hook ZenUtils.setZenModeSyncWithPhone 失败：",
                    "Failed to hook ZenUtils.setZenModeSyncWithPhone: ") + t);
        }
    }

    private static void hookHandleDeviceSettingDnd(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                    ZEN_MODE_SYNC_HELPER_CLASS,
                    cl,
                    "handleDeviceSettingDnd",
                    String.class,
                    Boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            String did = param.args != null && param.args.length > 0 && param.args[0] instanceof String
                                    ? (String) param.args[0]
                                    : null;
                            Object enabled = param.args != null && param.args.length > 1 ? param.args[1] : null;
                            XposedBridge.log(TAG + ": " + s(
                                    "ZenModeSyncHelper.handleDeviceSettingDnd 已触发，DID=",
                                    "ZenModeSyncHelper.handleDeviceSettingDnd was triggered, DID=") + safeDid(did)
                                    + s("，enabled=", ", enabled=") + String.valueOf(enabled)
                                    + s("，", ", ") + DndDiag.contextSummary());
                        }
                    });
            XposedBridge.log(TAG + ": " + s(
                    "已 Hook ZenModeSyncHelper.handleDeviceSettingDnd",
                    "Hooked ZenModeSyncHelper.handleDeviceSettingDnd"));
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": " + s(
                    "Hook ZenModeSyncHelper.handleDeviceSettingDnd 失败：",
                    "Failed to hook ZenModeSyncHelper.handleDeviceSettingDnd: ") + t);
        }
    }

    private static void hookUpdatePhoneZenRules(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                    ZEN_MODE_SYNC_HELPER_CLASS,
                    cl,
                    "updatePhoneZenRules",
                    Context.class,
                    boolean.class,
                    long.class,
                    java.util.List.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            boolean enabled = param.args != null
                                    && param.args.length > 1
                                    && param.args[1] instanceof Boolean
                                    && (Boolean) param.args[1];
                            long ruleId = param.args != null && param.args.length > 2 && param.args[2] instanceof Long
                                    ? (Long) param.args[2]
                                    : Long.MIN_VALUE;
                            int ruleCount = param.args != null
                                    && param.args.length > 3
                                    && param.args[3] instanceof java.util.List
                                    ? ((java.util.List<?>) param.args[3]).size()
                                    : -1;
                            XposedBridge.log(TAG + ": " + s(
                                    "ZenModeSyncHelper.updatePhoneZenRules 已触发，enabled=",
                                    "ZenModeSyncHelper.updatePhoneZenRules was triggered, enabled=") + enabled
                                    + s("，ruleId=", ", ruleId=") + ruleId
                                    + s("，ruleCount=", ", ruleCount=") + ruleCount
                                    + s("，", ", ") + DndDiag.contextSummary());
                        }
                    });
            XposedBridge.log(TAG + ": " + s(
                    "已 Hook ZenModeSyncHelper.updatePhoneZenRules",
                    "Hooked ZenModeSyncHelper.updatePhoneZenRules"));
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": " + s(
                    "Hook ZenModeSyncHelper.updatePhoneZenRules 失败：",
                    "Failed to hook ZenModeSyncHelper.updatePhoneZenRules: ") + t);
        }
    }

    private static void hookSimpleNoArgMethod(
            ClassLoader cl,
            String className,
            String methodName,
            String message,
            boolean resolveDid) {
        try {
            XposedHelpers.findAndHookMethod(
                    className,
                    cl,
                    methodName,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            StringBuilder sb = new StringBuilder(message);
                            if (resolveDid) {
                                sb.append(s("，DID=", ", DID="))
                                        .append(safeDid(resolveZenModeViewModelDid(param.thisObject)));
                            }
                            sb.append(s("，", ", "))
                                    .append(DndDiag.contextSummary());
                            XposedBridge.log(TAG + ": " + sb);
                        }
                    });
            XposedBridge.log(TAG + ": " + s(
                    "已 Hook ",
                    "Hooked ") + className + "." + methodName);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": " + s(
                    "Hook 失败 ",
                    "Failed to hook ") + className + "." + methodName + ": " + t);
        }
    }

    private static void rememberSettingsPageSession(Object fragment, String sessionId) {
        if (fragment == null || sessionId == null) {
            return;
        }
        XposedHelpers.setAdditionalInstanceField(fragment, SETTINGS_PAGE_SESSION_KEY, sessionId);
    }

    private static String settingsPageSession(Object fragment) {
        if (fragment == null) {
            return DndDiag.newSessionId("settings_page_missing");
        }
        Object sessionId = XposedHelpers.getAdditionalInstanceField(fragment, SETTINGS_PAGE_SESSION_KEY);
        if (sessionId instanceof String && !((String) sessionId).isEmpty()) {
            return (String) sessionId;
        }
        String fallback = DndDiag.newSessionId("settings_page");
        rememberSettingsPageSession(fragment, fallback);
        return fallback;
    }

    private static String resolveZenModeSettingFragmentDid(Object fragment) {
        if (fragment == null) return null;
        try {
            Method method = fragment.getClass().getDeclaredMethod("getMDeviceModel");
            method.setAccessible(true);
            Object model = method.invoke(fragment);
            return resolveDidFromModel(model);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String resolveZenModeViewModelDid(Object viewModel) {
        if (viewModel == null) return null;
        try {
            Method method = viewModel.getClass().getDeclaredMethod("getMDeviceModel");
            method.setAccessible(true);
            Object model = method.invoke(viewModel);
            return resolveDidFromModel(model);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String resolveDidFromModel(Object model) {
        if (model == null) return null;
        try {
            Method getDid = model.getClass().getMethod("getDid");
            Object value = getDid.invoke(model);
            return value instanceof String ? (String) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean readLocalZenSyncState(Object owner, String did) {
        if (did == null || did.isEmpty() || owner == null) {
            return false;
        }
        try {
            ClassLoader cl = owner.getClass().getClassLoader();
            if (cl == null) {
                return false;
            }
            Class<?> prefClass = cl.loadClass(DEVICE_SETTINGS_PREFERENCE_CLASS);
            Object instance = XposedHelpers.getStaticObjectField(prefClass, "INSTANCE");
            Object value = XposedHelpers.callMethod(instance, "isZenModeOpen", did);
            return value instanceof Boolean && (Boolean) value;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean objectExtraToBoolean(Object value) {
        return value instanceof Boolean && (Boolean) value;
    }

    private static String objectExtraToString(Object value) {
        return value instanceof String ? (String) value : null;
    }

    private static String summarizeArgs(Object[] args) {
        if (args == null || args.length == 0) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < args.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            Object arg = args[i];
            if (arg instanceof String) {
                sb.append('"').append(arg).append('"');
            } else {
                sb.append(String.valueOf(arg));
            }
        }
        sb.append(']');
        return sb.toString();
    }

    private static String safeProcessName(String processName) {
        return processName == null || processName.isEmpty()
                ? s("<未知>", "<unknown>")
                : processName;
    }

    private static boolean isInternalSyncZenModeSource(String source) {
        return "startup_headless".equals(source)
                || "reconnect_headless".equals(source)
                || "settings_page".equals(source)
                || "reconnect_followup".equals(source)
                || "native_reconnect".equals(source);
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
