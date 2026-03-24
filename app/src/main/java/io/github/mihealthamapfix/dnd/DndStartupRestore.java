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
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XposedBridge;

import static io.github.mihealthamapfix.util.L.s;

public final class DndStartupRestore {
    private static final String TAG = "AmapFix-DND";
    private static final String ZEN_MODE_VIEW_MODEL_CLASS =
            "com.xiaomi.fitness.devicesettings.common.zenmode.ZenModeViewModel";
    private static final String DEVICE_SETTINGS_PREFERENCE_CLASS =
            "com.xiaomi.fitness.devicesettings.utils.DeviceSettingsPreference";
    private static final String LIFECYCLE_OWNER_CLASS = "androidx.lifecycle.LifecycleOwner";
    private static final String LIFECYCLE_REGISTRY_CLASS = "androidx.lifecycle.LifecycleRegistry";
    private static final String LIFECYCLE_EVENT_CLASS = "androidx.lifecycle.Lifecycle$Event";
    private static final String OBSERVER_CLASS = "androidx.lifecycle.Observer";
    private static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_DELAY_MS = 1500L;
    private static final long QUERY_TIMEOUT_MS = 2500L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean SCHEDULED = new AtomicBoolean(false);
    private static final AtomicBoolean COMPLETED = new AtomicBoolean(false);

    private DndStartupRestore() {
    }

    public static void schedule(Application app) {
        if (app == null || COMPLETED.get()) return;
        if (!SCHEDULED.compareAndSet(false, true)) return;
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                startAttempt(app, 1);
            }
        });
    }

    private static void startAttempt(Application app, int attempt) {
        if (app == null) {
            SCHEDULED.set(false);
            return;
        }
        if (COMPLETED.get()) {
            SCHEDULED.set(false);
            return;
        }

        DndEnv.Snapshot snapshot = DndEnv.getSnapshot(true);
        if (!snapshot.canUsePrivilegedDnd()) {
            XposedBridge.log(TAG + ": " + s(
                    "启动恢复已跳过：当前没有可用 DND 路由，",
                    "Startup restore skipped: no DND route is available, ")
                    + snapshot.summary());
            SCHEDULED.set(false);
            return;
        }

        XposedBridge.log(TAG + ": " + s(
                "启动 headless refresh，第 ",
                "Starting headless refresh attempt ")
                + attempt + "/" + MAX_ATTEMPTS
                + s("，", ", ")
                + snapshot.summary());

        ClassLoader appCl = app.getClassLoader();
        if (appCl == null) {
            finishAttempt(false, app, attempt, s(
                    "Application ClassLoader 为空",
                    "Application ClassLoader is null"));
            return;
        }

        new HeadlessRefreshSession(app, appCl, attempt).start();
    }

    private static void finishAttempt(boolean success, Application app, int attempt, String detail) {
        XposedBridge.log(TAG + ": " + detail);
        if (success) {
            COMPLETED.set(true);
            SCHEDULED.set(false);
            return;
        }

        if (attempt < MAX_ATTEMPTS) {
            MAIN.postDelayed(new Runnable() {
                @Override
                public void run() {
                    startAttempt(app, attempt + 1);
                }
            }, RETRY_DELAY_MS);
            return;
        }

        XposedBridge.log(TAG + ": " + s(
                "headless refresh 已达到最大重试次数",
                "headless refresh reached the max retry count"));
        SCHEDULED.set(false);
    }

    private static final class HeadlessRefreshSession {
        private final Application app;
        private final ClassLoader appCl;
        private final int attempt;
        private final AtomicBoolean finished = new AtomicBoolean(false);

        private Object viewModel;
        private Object liveData;
        private Object lifecycleOwner;
        private Object lifecycleRegistry;
        private Object observer;
        private Method removeObserverMethod;
        private Method unregisterHandlerMethod;
        private Runnable timeoutRunnable;
        private String did;

        HeadlessRefreshSession(Application app, ClassLoader appCl, int attempt) {
            this.app = app;
            this.appCl = appCl;
            this.attempt = attempt;
        }

        void start() {
            try {
                Class<?> viewModelClass = appCl.loadClass(ZEN_MODE_VIEW_MODEL_CLASS);
                Constructor<?> ctor = viewModelClass.getConstructor();
                viewModel = ctor.newInstance();

                Method getMDeviceModel = viewModelClass.getDeclaredMethod("getMDeviceModel");
                getMDeviceModel.setAccessible(true);
                did = resolveDid(getMDeviceModel);
                if (did == null || did.isEmpty()) {
                    finish(false, s(
                            "第 " + attempt + " 次 headless refresh 未拿到当前设备 DID",
                            "Headless refresh attempt " + attempt + " did not get a current device DID"));
                    return;
                }

                XposedBridge.log(TAG + ": " + s(
                        "第 " + attempt + " 次 headless refresh 已拿到 DID=",
                        "Headless refresh attempt " + attempt + " got DID=") + did);

                Method registerHandlerMethod = viewModelClass.getMethod("registerSystemDataHandler");
                unregisterHandlerMethod = viewModelClass.getMethod("unregisterSystemDataHandler");
                Method getLiveDataMethod = viewModelClass.getMethod("getZenModeSyncWithPhone");
                Method requestMethod = viewModelClass.getMethod(
                        "getZenModeSyncWithPhone",
                        appCl.loadClass(LIFECYCLE_OWNER_CLASS));

                liveData = getLiveDataMethod.invoke(viewModel);
                if (liveData == null) {
                    finish(false, s(
                            "第 " + attempt + " 次 headless refresh 获取 LiveData 失败",
                            "Headless refresh attempt " + attempt + " failed to obtain LiveData"));
                    return;
                }

                lifecycleOwner = createLifecycleOwner();
                if (lifecycleOwner == null || lifecycleRegistry == null) {
                    finish(false, s(
                            "第 " + attempt + " 次 headless refresh 创建 LifecycleOwner 失败",
                            "Headless refresh attempt " + attempt + " failed to create a LifecycleOwner"));
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
                        finish(false, s(
                                "第 " + attempt + " 次 headless refresh 超时",
                                "Headless refresh attempt " + attempt + " timed out"));
                    }
                };
                MAIN.postDelayed(timeoutRunnable, QUERY_TIMEOUT_MS);
            } catch (Throwable t) {
                finish(false, s(
                        "第 " + attempt + " 次 headless refresh 启动失败：",
                        "Failed to start headless refresh attempt " + attempt + ": ") + t);
            }
        }

        private String resolveDid(Method getMDeviceModel) throws Exception {
            Object model = getMDeviceModel.invoke(viewModel);
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
            boolean localEnabled = readLocalZenSyncState();
            String resultLabel;
            if (value instanceof Boolean) {
                resultLabel = ((Boolean) value).toString();
            } else if (value == null) {
                resultLabel = "null";
            } else {
                resultLabel = String.valueOf(value);
            }

            XposedBridge.log(TAG + ": " + s(
                    "headless refresh 收到 getZenModeSyncWithPhone 结果=",
                    "headless refresh received getZenModeSyncWithPhone result=")
                    + resultLabel
                    + s("，DID=", ", DID=") + did
                    + s("，本地兜底=", ", localFallback=") + localEnabled);

            if (Boolean.FALSE.equals(value)) {
                writeLocalZenSyncState(false);
                finish(true, s(
                        "headless refresh 已写回本地 zen sync 状态=false，DID=",
                        "headless refresh wrote local zen sync state=false, DID=") + did);
                return;
            }

            if (Boolean.TRUE.equals(value) || (value == null && localEnabled)) {
                if (!isPolicyAccessGranted()) {
                    finish(false, s(
                            "headless refresh 检测到通知策略权限仍不可用",
                            "headless refresh found notification policy access still unavailable"));
                    return;
                }
                writeLocalZenSyncState(true);
                finish(true, s(
                        "headless refresh 已写回本地 zen sync 状态=true，DID=",
                        "headless refresh wrote local zen sync state=true, DID=") + did);
                return;
            }

            finish(false, s(
                    "headless refresh 未得到可用的 zen sync 状态，保持本地状态不变",
                    "headless refresh did not get a usable zen sync state; leaving local state unchanged"));
        }

        private boolean readLocalZenSyncState() {
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

        private void finish(boolean success, String detail) {
            if (!finished.compareAndSet(false, true)) return;
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

            finishAttempt(success, app, attempt, detail);
        }

        @SuppressWarnings("unchecked")
        private Object enumConstant(Class<?> enumClass, String name) {
            return Enum.valueOf((Class<? extends Enum>) enumClass.asSubclass(Enum.class), name);
        }
    }
}
