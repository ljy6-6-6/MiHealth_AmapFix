package io.github.mihealthamapfix.dnd;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.github.mihealthamapfix.IDndBridgeService;

import static io.github.mihealthamapfix.util.L.s;

/**
 * Experimental module-side binder client used from inside the hooked app process.
 */
public final class DndBridgeClient {
    private static final String TAG = "AmapFix-DND";
    private static final String MODULE_PACKAGE = "io.github.mihealthamapfix";
    private static final ComponentName SERVICE_COMPONENT = new ComponentName(
            MODULE_PACKAGE,
            "io.github.mihealthamapfix.dnd.DndBridgeService");

    private static final AtomicReference<IDndBridgeService> SVC = new AtomicReference<>();
    private static final AtomicReference<ServiceConnection> CONNECTION = new AtomicReference<>();
    private static final AtomicReference<CountDownLatch> BIND_LATCH = new AtomicReference<>();
    private static final AtomicInteger LOCAL_MODE = new AtomicInteger(DndMode.NONE.code());
    private static final AtomicReference<String> LOCAL_STATUS = new AtomicReference<>(
            s("实验桥：Binder 未连接", "experimental bridge: binder not connected"));
    private static final AtomicBoolean BINDING = new AtomicBoolean(false);

    private DndBridgeClient() {
    }

    public static boolean isEligible() {
        return Build.VERSION.SDK_INT >= 35;
    }

    public static boolean ensureBound(Context ctx, long timeoutMs) {
        if (!isEligible()) {
            return false;
        }
        if (ctx == null) {
            return SVC.get() != null;
        }

        if (SVC.get() == null) {
            bindIfNeeded(ctx);
        }

        CountDownLatch latch = BIND_LATCH.get();
        if (timeoutMs > 0 && SVC.get() == null && latch != null) {
            try {
                latch.await(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return SVC.get() != null;
    }

    public static void bindIfNeeded(Context ctx) {
        if (!isEligible() || ctx == null || SVC.get() != null) {
            return;
        }
        if (!BINDING.compareAndSet(false, true)) {
            return;
        }

        final CountDownLatch latch = new CountDownLatch(1);
        BIND_LATCH.set(latch);
        LOCAL_STATUS.set(s("实验桥：正在绑定服务", "experimental bridge: binding service"));

        final Context appCtx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        final Context bindCtx = createBridgeContext(appCtx);
        ServiceConnection connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                IDndBridgeService bridge = IDndBridgeService.Stub.asInterface(service);
                if (bridge == null) {
                    Log.w(TAG, s("实验桥返回了空 Binder", "Experimental bridge connected with a null binder"));
                    clearConnectionState(s("实验桥：空 Binder", "experimental bridge: null binder"));
                    return;
                }
                SVC.set(bridge);
                BINDING.set(false);
                BIND_LATCH.set(null);
                LOCAL_MODE.set(DndMode.NONE.code());
                LOCAL_STATUS.set(s("实验桥：Binder 已连接", "experimental bridge: binder connected"));
                latch.countDown();
                Log.i(TAG, s("实验桥 Binder 已连接", "Experimental bridge binder connected"));
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                Log.i(TAG, s("实验桥 Binder 已断开", "Experimental bridge binder disconnected"));
                clearConnectionState(s("实验桥：Binder 已断开", "experimental bridge: binder disconnected"));
            }

            @Override
            public void onBindingDied(ComponentName name) {
                Log.w(TAG, s("实验桥绑定已失效", "Experimental bridge binding died"));
                clearConnectionState(s("实验桥：绑定已失效", "experimental bridge: binding died"));
            }

            @Override
            public void onNullBinding(ComponentName name) {
                Log.w(TAG, s("实验桥返回了空绑定", "Experimental bridge returned a null binding"));
                clearConnectionState(s("实验桥：空绑定", "experimental bridge: null binding"));
            }
        };
        CONNECTION.set(connection);

        Intent intent = new Intent();
        intent.setComponent(SERVICE_COMPONENT);
        try {
            boolean bound = bindCtx.bindService(intent, connection, Context.BIND_AUTO_CREATE);
            if (!bound) {
                Log.w(TAG, s("实验桥 bindService 返回 false", "Experimental bridge bindService returned false"));
                clearConnectionState(s("实验桥：bindService 返回 false", "experimental bridge: bindService returned false"));
            }
        } catch (Throwable t) {
            Log.w(TAG, s("实验桥绑定失败", "Experimental bridge bind failed"), t);
            clearConnectionState(s("实验桥：绑定失败 ", "experimental bridge: bind failed ")
                    + t.getClass().getSimpleName());
        }
    }

    public static boolean isConnected() {
        return SVC.get() != null;
    }

    public static int getCapabilityMode() {
        IDndBridgeService service = SVC.get();
        if (service == null) {
            return LOCAL_MODE.get();
        }
        try {
            int mode = service.getCapabilityMode();
            LOCAL_MODE.set(mode);
            return mode;
        } catch (Throwable t) {
            Log.w(TAG, s("实验桥 getCapabilityMode 调用失败", "Experimental bridge getCapabilityMode failed"), t);
            clearConnectionState(s("实验桥：capability 读取失败 ", "experimental bridge: capability read failed ")
                    + t.getClass().getSimpleName());
            return DndMode.NONE.code();
        }
    }

    public static boolean isActive() {
        IDndBridgeService service = SVC.get();
        if (service == null) {
            return false;
        }
        try {
            return service.isActive();
        } catch (Throwable t) {
            Log.w(TAG, s("实验桥 isActive 调用失败", "Experimental bridge isActive failed"), t);
            clearConnectionState(s("实验桥：isActive 失败 ", "experimental bridge: isActive failed ")
                    + t.getClass().getSimpleName());
            return false;
        }
    }

    public static boolean setZen(int zen) {
        IDndBridgeService service = SVC.get();
        if (service == null) {
            return false;
        }
        try {
            service.setZenMode(zen);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, s("实验桥 setZen 调用失败", "Experimental bridge setZen failed"), t);
            clearConnectionState(s("实验桥：setZen 失败 ", "experimental bridge: setZen failed ")
                    + t.getClass().getSimpleName());
            return false;
        }
    }

    public static int getZen() {
        IDndBridgeService service = SVC.get();
        if (service == null) {
            return -1;
        }
        try {
            return service.getZenMode();
        } catch (Throwable t) {
            Log.w(TAG, s("实验桥 getZen 调用失败", "Experimental bridge getZen failed"), t);
            clearConnectionState(s("实验桥：getZen 失败 ", "experimental bridge: getZen failed ")
                    + t.getClass().getSimpleName());
            return -1;
        }
    }

    public static String getStatus() {
        IDndBridgeService service = SVC.get();
        if (service == null) {
            return LOCAL_STATUS.get();
        }
        try {
            return service.getStatus();
        } catch (Throwable t) {
            Log.w(TAG, s("实验桥 getStatus 调用失败", "Experimental bridge getStatus failed"), t);
            clearConnectionState(s("实验桥：状态读取失败 ", "experimental bridge: status read failed ")
                    + t.getClass().getSimpleName());
            return LOCAL_STATUS.get();
        }
    }

    private static Context createBridgeContext(Context appCtx) {
        try {
            return appCtx.createPackageContext(
                    MODULE_PACKAGE,
                    Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
        } catch (PackageManager.NameNotFoundException e) {
            LOCAL_STATUS.set(s("实验桥：模块包上下文不可用", "experimental bridge: module package context unavailable"));
            return appCtx;
        } catch (Throwable t) {
            LOCAL_STATUS.set(s("实验桥：创建模块包上下文失败 ", "experimental bridge: failed to create module package context ")
                    + t.getClass().getSimpleName());
            return appCtx;
        }
    }

    private static void clearConnectionState(String status) {
        SVC.set(null);
        CONNECTION.set(null);
        BINDING.set(false);
        LOCAL_MODE.set(DndMode.NONE.code());
        LOCAL_STATUS.set(status);
        CountDownLatch latch = BIND_LATCH.getAndSet(null);
        if (latch != null) {
            latch.countDown();
        }
    }
}
