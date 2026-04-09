package io.github.mihealthamapfix.dnd;

import android.app.Application;
import android.app.NotificationManager;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import de.robv.android.xposed.XposedBridge;

import static io.github.mihealthamapfix.util.L.s;

final class DndHeadlessRefresh {
    private static final String TAG = "AmapFix-DND";
    private static final String ZEN_MODE_VIEW_MODEL_CLASS =
            "com.xiaomi.fitness.devicesettings.common.zenmode.ZenModeViewModel";
    private static final String DEVICE_SETTINGS_PREFERENCE_CLASS =
            "com.xiaomi.fitness.devicesettings.utils.DeviceSettingsPreference";
    private static final String LIFECYCLE_OWNER_CLASS = "androidx.lifecycle.LifecycleOwner";
    private static final String LIFECYCLE_REGISTRY_CLASS = "androidx.lifecycle.LifecycleRegistry";
    private static final String LIFECYCLE_EVENT_CLASS = "androidx.lifecycle.Lifecycle$Event";
    private static final String OBSERVER_CLASS = "androidx.lifecycle.Observer";
    private static final long QUERY_TIMEOUT_MS = 2500L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private DndHeadlessRefresh() {
    }

    interface Completion {
        void onComplete(Result result);
    }

    enum ResultKind {
        ENABLED_CONFIRMED,
        RETRYABLE_NOT_READY,
        HARD_FAILURE
    }

    static final class Result {
        private final String sourceTag;
        private final String sessionId;
        private final String did;
        private final String syncWithPhoneRaw;
        private final boolean localBefore;
        private final boolean localAfter;
        private final ResultKind kind;
        private final String detail;

        private Result(
                String sourceTag,
                String sessionId,
                String did,
                String syncWithPhoneRaw,
                boolean localBefore,
                boolean localAfter,
                ResultKind kind,
                String detail) {
            this.sourceTag = sourceTag;
            this.sessionId = sessionId;
            this.did = did;
            this.syncWithPhoneRaw = syncWithPhoneRaw;
            this.localBefore = localBefore;
            this.localAfter = localAfter;
            this.kind = kind;
            this.detail = detail;
        }

        static Result enabledConfirmed(
                AttemptContext context,
                String did,
                String syncWithPhoneRaw,
                boolean localBefore,
                boolean localAfter,
                String detail) {
            return new Result(
                    context.sourceTag(),
                    context.sessionId(),
                    did,
                    syncWithPhoneRaw,
                    localBefore,
                    localAfter,
                    ResultKind.ENABLED_CONFIRMED,
                    detail);
        }

        static Result retryableNotReady(
                AttemptContext context,
                String did,
                String syncWithPhoneRaw,
                boolean localBefore,
                boolean localAfter,
                String detail) {
            return new Result(
                    context.sourceTag(),
                    context.sessionId(),
                    did,
                    syncWithPhoneRaw,
                    localBefore,
                    localAfter,
                    ResultKind.RETRYABLE_NOT_READY,
                    detail);
        }

        static Result hardFailure(
                AttemptContext context,
                String did,
                String syncWithPhoneRaw,
                boolean localBefore,
                boolean localAfter,
                String detail) {
            return new Result(
                    context.sourceTag(),
                    context.sessionId(),
                    did,
                    syncWithPhoneRaw,
                    localBefore,
                    localAfter,
                    ResultKind.HARD_FAILURE,
                    detail);
        }

        String sourceTag() {
            return sourceTag;
        }

        String sessionId() {
            return sessionId;
        }

        String did() {
            return did;
        }

        String syncWithPhoneRaw() {
            return syncWithPhoneRaw;
        }

        boolean localBefore() {
            return localBefore;
        }

        boolean localAfter() {
            return localAfter;
        }

        ResultKind kind() {
            return kind;
        }

        String detail() {
            return detail;
        }
    }

    static void start(Application app, AttemptContext context, Completion completion) {
        if (app == null) {
            completion.onComplete(Result.hardFailure(
                    context,
                    null,
                    "n/a",
                    false,
                    false,
                    s(context.label() + " headless refresh 启动失败：Application 为空，session=",
                            "Failed to start " + context.label() + " headless refresh: Application is null, session=")
                            + context.sessionId()));
            return;
        }

        ClassLoader appCl = app.getClassLoader();
        if (appCl == null) {
            completion.onComplete(Result.hardFailure(
                    context,
                    null,
                    "n/a",
                    false,
                    false,
                    s(context.label() + " headless refresh 启动失败：Application ClassLoader 为空，session=",
                            "Failed to start " + context.label() + " headless refresh: Application ClassLoader is null, session=")
                            + context.sessionId()));
            return;
        }

        new Session(app, appCl, context, completion).start();
    }

    static final class AttemptContext {
        private final String sourceTag;
        private final String sessionId;
        private final String labelZh;
        private final String labelEn;
        private final int attempt;

        AttemptContext(String sourceTag, String sessionId, String labelZh, String labelEn, int attempt) {
            this.sourceTag = sourceTag;
            this.sessionId = sessionId;
            this.labelZh = labelZh;
            this.labelEn = labelEn;
            this.attempt = attempt;
        }

        String label() {
            return s(labelZh, labelEn);
        }

        String sourceTag() {
            return sourceTag;
        }

        String sessionId() {
            return sessionId;
        }

        int attempt() {
            return attempt;
        }
    }

    private static final class Session {
        private final Application app;
        private final ClassLoader appCl;
        private final AttemptContext context;
        private final Completion completion;
        private boolean finished;

        private Object viewModel;
        private Object liveData;
        private Object lifecycleOwner;
        private Object lifecycleRegistry;
        private Object observer;
        private Method removeObserverMethod;
        private Method unregisterHandlerMethod;
        private Runnable timeoutRunnable;
        private String did;

        private Session(
                Application app,
                ClassLoader appCl,
                AttemptContext context,
                Completion completion) {
            this.app = app;
            this.appCl = appCl;
            this.context = context;
            this.completion = completion;
        }

        private void start() {
            try {
                Class<?> viewModelClass = appCl.loadClass(ZEN_MODE_VIEW_MODEL_CLASS);
                Constructor<?> ctor = viewModelClass.getConstructor();
                viewModel = ctor.newInstance();

                Method getMDeviceModel = viewModelClass.getDeclaredMethod("getMDeviceModel");
                getMDeviceModel.setAccessible(true);
                Object deviceModel = getMDeviceModel.invoke(viewModel);
                did = resolveDid(deviceModel);
                if (did == null || did.isEmpty()) {
                    finish(Result.retryableNotReady(
                            context,
                            null,
                            "did_missing",
                            false,
                            false,
                            s(context.label() + " headless refresh 未拿到当前设备 DID，session=",
                                    context.label() + " headless refresh did not get a current device DID, session=")
                                    + context.sessionId()));
                    return;
                }

                DndDiag.mark(context.sourceTag(), context.sessionId(), did);
                XposedBridge.log(TAG + ": " + s(
                        context.label() + " headless refresh 已拿到 DID=",
                        context.label() + " headless refresh got DID=") + did
                        + s("，session=", ", session=") + context.sessionId());

                Method registerHandlerMethod = viewModelClass.getMethod("registerSystemDataHandler");
                unregisterHandlerMethod = viewModelClass.getMethod("unregisterSystemDataHandler");
                Method getLiveDataMethod = viewModelClass.getMethod("getZenModeSyncWithPhone");
                Method requestMethod = viewModelClass.getMethod(
                        "getZenModeSyncWithPhone",
                        appCl.loadClass(LIFECYCLE_OWNER_CLASS));

                liveData = getLiveDataMethod.invoke(viewModel);
                if (liveData == null) {
                    finish(Result.hardFailure(
                            context,
                            did,
                            "liveData_null",
                            false,
                            false,
                            s(context.label() + " headless refresh 获取 LiveData 失败，session=",
                                    context.label() + " headless refresh failed to obtain LiveData, session=")
                                    + context.sessionId()));
                    return;
                }

                lifecycleOwner = createLifecycleOwner();
                if (lifecycleOwner == null || lifecycleRegistry == null) {
                    finish(Result.hardFailure(
                            context,
                            did,
                            "lifecycle_owner_null",
                            false,
                            false,
                            s(context.label() + " headless refresh 创建 LifecycleOwner 失败，session=",
                                    context.label() + " headless refresh failed to create a LifecycleOwner, session=")
                                    + context.sessionId()));
                    return;
                }

                observer = createObserver();
                removeObserverMethod = liveData.getClass().getMethod(
                        "removeObserver", appCl.loadClass(OBSERVER_CLASS));
                Method observeMethod = liveData.getClass().getMethod(
                        "observe",
                        appCl.loadClass(LIFECYCLE_OWNER_CLASS),
                        appCl.loadClass(OBSERVER_CLASS));

                registerHandlerMethod.invoke(viewModel);
                observeMethod.invoke(liveData, lifecycleOwner, observer);
                requestMethod.invoke(viewModel, lifecycleOwner);

                timeoutRunnable = new Runnable() {
                    @Override
                    public void run() {
                        finish(Result.hardFailure(
                                context,
                                did,
                                "timeout",
                                readLocalZenSyncState(),
                                readLocalZenSyncState(),
                                s(context.label() + " headless refresh 超时，session=",
                                        context.label() + " headless refresh timed out, session=")
                                        + context.sessionId()));
                    }
                };
                MAIN.postDelayed(timeoutRunnable, QUERY_TIMEOUT_MS);
            } catch (Throwable t) {
                finish(Result.hardFailure(
                        context,
                        did,
                        "exception",
                        false,
                        false,
                        s(context.label() + " headless refresh 启动失败，session=",
                                "Failed to start " + context.label() + " headless refresh, session=")
                                + context.sessionId()
                                + s("，异常=", ", error=") + t));
            }
        }

        private String resolveDid(Object model) throws Exception {
            if (model == null) return null;
            Method getDid = model.getClass().getMethod("getDid");
            Object value = getDid.invoke(model);
            return value instanceof String ? (String) value : null;
        }

        private Object createLifecycleOwner() throws Exception {
            Class<?> lifecycleOwnerClass = appCl.loadClass(LIFECYCLE_OWNER_CLASS);
            Class<?> lifecycleRegistryClass = appCl.loadClass(LIFECYCLE_REGISTRY_CLASS);
            Class<?> lifecycleEventClass = appCl.loadClass(LIFECYCLE_EVENT_CLASS);
            final Object[] registryHolder = new Object[1];
            InvocationHandler ownerHandler = new InvocationHandler() {
                @Override
                public Object invoke(Object proxy, Method method, Object[] args) {
                    String name = method.getName();
                    if ("getLifecycle".equals(name)) {
                        return registryHolder[0];
                    }
                    if ("hashCode".equals(name)) {
                        return System.identityHashCode(proxy);
                    }
                    if ("equals".equals(name)) {
                        return proxy == (args != null && args.length > 0 ? args[0] : null);
                    }
                    if ("toString".equals(name)) {
                        return "DndHeadlessLifecycleOwner";
                    }
                    return null;
                }
            };

            Object owner = Proxy.newProxyInstance(
                    appCl,
                    new Class<?>[]{lifecycleOwnerClass},
                    ownerHandler);
            Constructor<?> ctor = lifecycleRegistryClass.getConstructor(lifecycleOwnerClass);
            Object registry = ctor.newInstance(owner);
            registryHolder[0] = registry;
            lifecycleRegistry = registry;

            Method handleEvent = lifecycleRegistryClass.getMethod(
                    "handleLifecycleEvent", lifecycleEventClass);
            handleEvent.invoke(registry, enumConstant(lifecycleEventClass, "ON_CREATE"));
            handleEvent.invoke(registry, enumConstant(lifecycleEventClass, "ON_START"));
            handleEvent.invoke(registry, enumConstant(lifecycleEventClass, "ON_RESUME"));
            return owner;
        }

        private Object createObserver() throws Exception {
            Class<?> observerClass = appCl.loadClass(OBSERVER_CLASS);
            return Proxy.newProxyInstance(
                    appCl,
                    new Class<?>[]{observerClass},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            String name = method.getName();
                            if ("onChanged".equals(name)) {
                                Object value = args != null && args.length > 0 ? args[0] : null;
                                handleChanged(value);
                                return null;
                            }
                            if ("hashCode".equals(name)) {
                                return System.identityHashCode(proxy);
                            }
                            if ("equals".equals(name)) {
                                return proxy == (args != null && args.length > 0 ? args[0] : null);
                            }
                            if ("toString".equals(name)) {
                                return "DndHeadlessObserver";
                            }
                            return null;
                        }
                    });
        }

        private void handleChanged(Object value) {
            boolean localBefore = readLocalZenSyncState();
            String syncWithPhoneRaw = value == null ? "null" : String.valueOf(value);

            XposedBridge.log(TAG + ": " + s(
                    context.label() + " headless refresh 收到 getZenModeSyncWithPhone 结果=",
                    context.label() + " headless refresh received getZenModeSyncWithPhone result=")
                    + syncWithPhoneRaw
                    + s("，DID=", ", DID=") + did
                    + s("，本地兜底=", ", localFallback=") + localBefore
                    + s("，session=", ", session=") + context.sessionId());

            if (Boolean.TRUE.equals(value) || (value == null && localBefore)) {
                if (!isPolicyAccessGranted()) {
                    finish(Result.hardFailure(
                            context,
                            did,
                            syncWithPhoneRaw,
                            localBefore,
                            localBefore,
                            s(context.label() + " headless refresh 检测到通知策略权限仍不可用，session=",
                                    context.label() + " headless refresh found notification policy access still unavailable, session=")
                                    + context.sessionId()));
                    return;
                }

                writeLocalZenSyncState(true);
                boolean localAfter = readLocalZenSyncState();
                finish(Result.enabledConfirmed(
                        context,
                        did,
                        syncWithPhoneRaw,
                        localBefore,
                        localAfter,
                        s(context.label() + " headless refresh 已确认同步开启，DID=",
                                context.label() + " headless refresh confirmed sync enabled, DID=") + did
                                + s("，本地前=", ", localBefore=") + localBefore
                                + s("，本地后=", ", localAfter=") + localAfter
                                + s("，session=", ", session=") + context.sessionId()));
                return;
            }

            if (Boolean.FALSE.equals(value)) {
                finish(Result.retryableNotReady(
                        context,
                        did,
                        syncWithPhoneRaw,
                        localBefore,
                        localBefore,
                        s(context.label() + " headless refresh 收到 syncWithPhone=false，视为临时 false，保持本地状态不变并重试，DID=",
                                context.label() + " headless refresh received syncWithPhone=false; treating it as provisional false, keeping local state unchanged and retrying, DID=") + did
                                + s("，本地状态=", ", localState=") + localBefore
                                + s("，session=", ", session=") + context.sessionId()));
                return;
            }

            finish(Result.retryableNotReady(
                    context,
                    did,
                    syncWithPhoneRaw,
                    localBefore,
                    localBefore,
                    s(context.label() + " headless refresh 当前未确认同步开启，保持本地状态不变并重试，DID=",
                            context.label() + " headless refresh has not confirmed sync enabled yet; keeping local state unchanged and retrying, DID=") + did
                            + s("，本地状态=", ", localState=") + localBefore
                            + s("，session=", ", session=") + context.sessionId()));
        }

        private boolean readLocalZenSyncState() {
            if (did == null || did.isEmpty()) {
                return false;
            }
            try {
                Class<?> prefClass = appCl.loadClass(DEVICE_SETTINGS_PREFERENCE_CLASS);
                Field instanceField = prefClass.getField("INSTANCE");
                Object instance = instanceField.get(null);
                Method getter = prefClass.getMethod("isZenModeOpen", String.class);
                Object value = getter.invoke(instance, did);
                return value instanceof Boolean && (Boolean) value;
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": " + s(
                        "读取本地 zen sync 状态失败：",
                        "Failed to read local zen sync state: ") + t);
                return false;
            }
        }

        private void writeLocalZenSyncState(boolean enabled) {
            if (did == null || did.isEmpty()) {
                return;
            }
            try {
                Class<?> prefClass = appCl.loadClass(DEVICE_SETTINGS_PREFERENCE_CLASS);
                Field instanceField = prefClass.getField("INSTANCE");
                Object instance = instanceField.get(null);
                Method setter = prefClass.getMethod("isZenModeOpen", String.class, boolean.class);
                setter.invoke(instance, did, enabled);
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": " + s(
                        "写回本地 zen sync 状态失败：",
                        "Failed to write local zen sync state: ") + t);
            }
        }

        private boolean isPolicyAccessGranted() {
            try {
                NotificationManager nm = app.getSystemService(NotificationManager.class);
                return nm != null && nm.isNotificationPolicyAccessGranted();
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": " + s(
                        "读取通知策略权限失败：",
                        "Failed to read notification policy access: ") + t);
                return false;
            }
        }

        private void finish(Result result) {
            if (finished) return;
            finished = true;
            if (timeoutRunnable != null) {
                MAIN.removeCallbacks(timeoutRunnable);
            }

            try {
                if (liveData != null && observer != null && removeObserverMethod != null) {
                    removeObserverMethod.invoke(liveData, observer);
                }
            } catch (Throwable ignored) {
            }

            try {
                if (lifecycleRegistry != null) {
                    Class<?> lifecycleEventClass = appCl.loadClass(LIFECYCLE_EVENT_CLASS);
                    Method handleEvent = lifecycleRegistry.getClass().getMethod(
                            "handleLifecycleEvent", lifecycleEventClass);
                    handleEvent.invoke(lifecycleRegistry, enumConstant(lifecycleEventClass, "ON_DESTROY"));
                }
            } catch (Throwable ignored) {
            }

            try {
                if (viewModel != null && unregisterHandlerMethod != null) {
                    unregisterHandlerMethod.invoke(viewModel);
                }
            } catch (Throwable ignored) {
            }

            completion.onComplete(result);
        }

        @SuppressWarnings("unchecked")
        private Object enumConstant(Class<?> enumClass, String name) {
            return Enum.valueOf((Class<? extends Enum>) enumClass.asSubclass(Enum.class), name);
        }
    }
}
