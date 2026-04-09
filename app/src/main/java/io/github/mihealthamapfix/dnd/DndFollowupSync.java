package io.github.mihealthamapfix.dnd;

import android.app.Application;
import android.content.Context;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XposedHelpers;

import static io.github.mihealthamapfix.util.L.s;

final class DndFollowupSync {
    private static final String DEVICE_SETTINGS_SYNCER_CLASS =
            "com.xiaomi.fitness.devicesettings.DeviceSettingsSyncer";
    private static final String ZEN_MODE_SYNC_HELPER_CLASS =
            "com.xiaomi.fitness.devicesettings.common.zenmode.ZenModeSyncHelper";
    private static final String KOTLIN_CONTINUATION_CLASS = "kotlin.coroutines.Continuation";

    private static final Set<String> DISPATCHED_SESSION_IDS =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private DndFollowupSync() {
    }

    static DispatchResult dispatch(Application app, DndHeadlessRefresh.Result result) {
        if (app == null) {
            return DispatchResult.failure(s(
                    "follow-up 未执行：Application 为空",
                    "Follow-up was not dispatched: Application is null"));
        }
        if (result == null || result.kind() != DndHeadlessRefresh.ResultKind.ENABLED_CONFIRMED) {
            return DispatchResult.failure(s(
                    "follow-up 未执行：当前结果未确认开启",
                    "Follow-up was not dispatched: the current result has not confirmed sync enabled"));
        }

        if (!DISPATCHED_SESSION_IDS.add(result.sessionId())) {
            return DispatchResult.success(s(
                    "已跳过重复的 follow-up，session=",
                    "Skipped a duplicate follow-up, session=") + result.sessionId());
        }

        ClassLoader cl = app.getClassLoader();
        if (cl == null) {
            DISPATCHED_SESSION_IDS.remove(result.sessionId());
            return DispatchResult.failure(s(
                    "follow-up 未执行：Application ClassLoader 为空",
                    "Follow-up was not dispatched: Application ClassLoader is null"));
        }

        try {
            DndDiag.mark(result.sourceTag(), result.sessionId(), result.did());
            if ("reconnect_headless".equals(result.sourceTag())) {
                DispatchResult nativeResult = dispatchNativeReconnectSync(cl, result);
                if (nativeResult.success()) {
                    return nativeResult;
                }
                DispatchResult fallbackResult = dispatchReconnectFallback(cl, app, result);
                return fallbackResult.success() ? fallbackResult : nativeResult;
            }

            return dispatchStartupFollowup(cl, app, result);
        } catch (Throwable t) {
            DISPATCHED_SESSION_IDS.remove(result.sessionId());
            return DispatchResult.failure(s(
                    "follow-up 执行失败，session=",
                    "Follow-up dispatch failed, session=") + result.sessionId()
                    + s("，DID=", ", DID=") + safeDid(result.did())
                    + s("，异常=", ", error=") + formatThrowable(t));
        }
    }

    private static DispatchResult dispatchNativeReconnectSync(
            ClassLoader cl,
            DndHeadlessRefresh.Result result) throws Exception {
        Class<?> syncerClass = cl.loadClass(DEVICE_SETTINGS_SYNCER_CLASS);
        Object instance = XposedHelpers.getStaticObjectField(syncerClass, "INSTANCE");
        Class<?> continuationClass = cl.loadClass(KOTLIN_CONTINUATION_CLASS);
        Method method = syncerClass.getMethod("syncZenModeMode", String.class, continuationClass);
        try (DndDiag.Scope ignored = DndDiag.enterScopedContext(
                "reconnect_followup",
                result.sessionId(),
                result.did())) {
            method.invoke(instance, result.did(), null);
        }
        return DispatchResult.success(s(
                "已确认同步开启，正在执行 follow-up=syncZenModeMode，session=",
                "Sync enabled confirmed, running follow-up=syncZenModeMode, session=")
                + result.sessionId()
                + s("，DID=", ", DID=") + safeDid(result.did()));
    }

    private static DispatchResult dispatchStartupFollowup(
            ClassLoader cl,
            Application app,
            DndHeadlessRefresh.Result result) throws Exception {
        Class<?> helperClass = cl.loadClass(ZEN_MODE_SYNC_HELPER_CLASS);
        Object helper = XposedHelpers.getStaticObjectField(helperClass, "INSTANCE");
        Method supportMethod = helperClass.getMethod("isSupportZenRuleSync", Context.class);
        boolean supportZenRuleSync = Boolean.TRUE.equals(supportMethod.invoke(helper, app));
        if (supportZenRuleSync) {
            Method getDeviceZenRules = helperClass.getMethod("getDeviceZenRules", Context.class);
            try (DndDiag.Scope ignored = DndDiag.enterScopedContext(
                    result.sourceTag(),
                    result.sessionId(),
                    result.did())) {
                getDeviceZenRules.invoke(helper, app);
            }
            return DispatchResult.success(s(
                    "已确认同步开启，正在执行 follow-up=getDeviceZenRules，session=",
                    "Sync enabled confirmed, running follow-up=getDeviceZenRules, session=")
                    + result.sessionId()
                    + s("，DID=", ", DID=") + safeDid(result.did()));
        }

        return DispatchResult.success(s(
                "已确认同步开启，当前无需额外 follow-up，session=",
                "Sync enabled confirmed; no extra follow-up is needed for the current session, session=")
                + result.sessionId()
                + s("，DID=", ", DID=") + safeDid(result.did()));
    }

    private static DispatchResult dispatchReconnectFallback(
            ClassLoader cl,
            Application app,
            DndHeadlessRefresh.Result result) throws Exception {
        Class<?> helperClass = cl.loadClass(ZEN_MODE_SYNC_HELPER_CLASS);
        Object helper = XposedHelpers.getStaticObjectField(helperClass, "INSTANCE");
        Method supportMethod = helperClass.getMethod("isSupportZenRuleSync", Context.class);
        boolean supportZenRuleSync = Boolean.TRUE.equals(supportMethod.invoke(helper, app));
        if (!supportZenRuleSync) {
            return DispatchResult.failure(s(
                    "reconnect follow-up 主路径失败，且当前不支持 getDeviceZenRules fallback，session=",
                    "Reconnect follow-up primary path failed and getDeviceZenRules fallback is not supported, session=")
                    + result.sessionId()
                    + s("，DID=", ", DID=") + safeDid(result.did()));
        }

        Method getDeviceZenRules = helperClass.getMethod("getDeviceZenRules", Context.class);
        try (DndDiag.Scope ignored = DndDiag.enterScopedContext(
                "reconnect_followup",
                result.sessionId(),
                result.did())) {
            getDeviceZenRules.invoke(helper, app);
        }
        return DispatchResult.success(s(
                "reconnect follow-up 主路径失败，已回退到 getDeviceZenRules，session=",
                "Reconnect follow-up primary path failed; fell back to getDeviceZenRules, session=")
                + result.sessionId()
                + s("，DID=", ", DID=") + safeDid(result.did()));
    }

    private static String formatThrowable(Throwable throwable) {
        if (throwable instanceof InvocationTargetException) {
            Throwable target = ((InvocationTargetException) throwable).getTargetException();
            if (target != null) {
                return throwable.getClass().getSimpleName()
                        + s("，内层异常=", ", target=")
                        + target.getClass().getName()
                        + ": "
                        + String.valueOf(target.getMessage());
            }
        }
        Throwable cause = throwable.getCause();
        if (cause != null && cause != throwable) {
            return throwable.getClass().getName()
                    + s("，原因=", ", cause=")
                    + cause.getClass().getName()
                    + ": "
                    + String.valueOf(cause.getMessage());
        }
        return String.valueOf(throwable);
    }

    private static String safeDid(String did) {
        return did == null || did.isEmpty()
                ? s("<空>", "<empty>")
                : did;
    }

    static final class DispatchResult {
        private final boolean success;
        private final String detail;

        private DispatchResult(boolean success, String detail) {
            this.success = success;
            this.detail = detail;
        }

        static DispatchResult success(String detail) {
            return new DispatchResult(true, detail);
        }

        static DispatchResult failure(String detail) {
            return new DispatchResult(false, detail);
        }

        boolean success() {
            return success;
        }

        String detail() {
            return detail;
        }
    }
}
