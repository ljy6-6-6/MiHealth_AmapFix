package io.github.mihealthamapfix.dnd;

import android.content.ComponentName;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.github.mihealthamapfix.util.AppCtx;
import rikka.shizuku.Shizuku;

import static io.github.mihealthamapfix.util.L.s;

/**
 * Experimental module-side DND engine backed by a Shizuku UserService.
 */
public class ShizukuEngine implements DndEngine {
    private static final String TAG = "AmapFix-DND";
    private static final String MODULE_PACKAGE = "io.github.mihealthamapfix";
    private static final ComponentName USER_SERVICE_COMPONENT = new ComponentName(
            MODULE_PACKAGE,
            "io.github.mihealthamapfix.dnd.ShizukuDndUserService");

    private static final AtomicReference<IShizukuDndService> SERVICE = new AtomicReference<>();
    private static final AtomicReference<ServiceConnection> CONNECTION = new AtomicReference<>();
    private static final AtomicReference<CountDownLatch> BIND_LATCH = new AtomicReference<>();
    private static final AtomicBoolean BINDING = new AtomicBoolean(false);

    private volatile String status = s("Shizuku：未初始化", "Shizuku: not initialized");

    static Probe probeAccess() {
        try {
            if (Build.VERSION.SDK_INT < 26) {
                return new Probe(false, s("Shizuku：SDK < 26", "Shizuku: sdk<26"));
            }
            if (!Shizuku.pingBinder()) {
                return new Probe(false, s("Shizuku：binder 不可用", "Shizuku: binder unavailable"));
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                return new Probe(false, s("Shizuku：权限未授予", "Shizuku: permission not granted"));
            }
            return new Probe(true, s("Shizuku：权限已授予", "Shizuku: permission granted"));
        } catch (Throwable t) {
            return new Probe(false, s("Shizuku：检查失败 ", "Shizuku: check failed ")
                    + simpleName(t));
        }
    }

    @Override
    public boolean isActive() {
        Probe probe = probeAccess();
        if (!probe.granted()) {
            clearService(probe.detail());
            status = probe.detail();
            return false;
        }

        try {
            IShizukuDndService service = ensureService(1500L);
            if (service == null) {
                return false;
            }
            boolean alive = service.ping();
            status = alive
                    ? s("Shizuku：用户服务已连接", "Shizuku: user service connected")
                    : s("Shizuku：用户服务未响应", "Shizuku: user service not responding");
            if (!alive) {
                clearService(status);
            }
            return alive;
        } catch (Throwable t) {
            status = s("Shizuku：检查失败 ", "Shizuku: check failed ") + simpleName(t);
            clearService(status);
            return false;
        }
    }

    @Override
    public String getStatus() {
        return status;
    }

    @Override
    public void setZen(int zen) throws Exception {
        IShizukuDndService service = ensureService(1500L);
        if (service == null) {
            throw new IllegalStateException(status);
        }
        try {
            service.setZenMode(zen);
            status = s("Shizuku：已通过用户服务请求设置 zen=", "Shizuku: requested zen via user service=") + zen;
        } catch (Throwable t) {
            status = s("Shizuku：写入失败 ", "Shizuku: set failed ") + simpleName(t);
            clearService(status);
            if (t instanceof Exception) {
                throw (Exception) t;
            }
            throw new IllegalStateException(status, t);
        }
    }

    @Override
    public int getZen() {
        try {
            IShizukuDndService service = ensureService(1500L);
            if (service == null) {
                return -1;
            }
            return service.getZenMode();
        } catch (Throwable t) {
            status = s("Shizuku：读取失败 ", "Shizuku: read failed ") + simpleName(t);
            clearService(status);
            return -1;
        }
    }

    private IShizukuDndService ensureService(long timeoutMs) {
        IShizukuDndService current = SERVICE.get();
        if (current != null) {
            return current;
        }

        Probe probe = probeAccess();
        if (!probe.granted()) {
            status = probe.detail();
            clearService(status);
            return null;
        }

        if (BINDING.compareAndSet(false, true)) {
            CountDownLatch latch = new CountDownLatch(1);
            BIND_LATCH.set(latch);
            status = s("Shizuku：正在绑定用户服务", "Shizuku: binding user service");

            ServiceConnection connection = new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder service) {
                    IShizukuDndService remote = IShizukuDndService.Stub.asInterface(service);
                    if (remote == null) {
                        clearService(s("Shizuku：用户服务返回空 Binder", "Shizuku: user service returned a null binder"));
                        return;
                    }
                    SERVICE.set(remote);
                    status = s("Shizuku：用户服务已连接", "Shizuku: user service connected");
                    BINDING.set(false);
                    BIND_LATCH.set(null);
                    latch.countDown();
                    Log.i(TAG, s("Shizuku 用户服务已连接", "Shizuku user service connected"));
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    clearService(s("Shizuku：用户服务已断开", "Shizuku: user service disconnected"));
                }

                @Override
                public void onBindingDied(ComponentName name) {
                    clearService(s("Shizuku：用户服务绑定已失效", "Shizuku: user service binding died"));
                }

                @Override
                public void onNullBinding(ComponentName name) {
                    clearService(s("Shizuku：用户服务返回空绑定", "Shizuku: user service returned a null binding"));
                }
            };
            CONNECTION.set(connection);

            try {
                Shizuku.bindUserService(buildUserServiceArgs(), connection);
            } catch (Throwable t) {
                status = s("Shizuku：绑定用户服务失败 ", "Shizuku: user service bind failed ")
                        + simpleName(t);
                clearService(status);
            }
        }

        CountDownLatch latch = BIND_LATCH.get();
        if (latch != null && timeoutMs > 0 && SERVICE.get() == null) {
            try {
                latch.await(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return SERVICE.get();
    }

    private static void clearService(String ignoredStatus) {
        SERVICE.set(null);
        CONNECTION.set(null);
        BINDING.set(false);
        CountDownLatch latch = BIND_LATCH.getAndSet(null);
        if (latch != null) {
            latch.countDown();
        }
    }

    private static String simpleName(Throwable t) {
        return t == null ? "unknown" : t.getClass().getSimpleName();
    }

    private static Shizuku.UserServiceArgs buildUserServiceArgs() {
        int version = 1;
        try {
            if (AppCtx.app() != null) {
                version = AppCtx.app().getPackageManager()
                        .getPackageInfo(MODULE_PACKAGE, 0).versionCode;
            }
        } catch (Throwable ignored) {
        }
        return new Shizuku.UserServiceArgs(USER_SERVICE_COMPONENT)
                .tag("mihealthamapfix-dnd")
                .version(version)
                .debuggable(false)
                .processNameSuffix("dnd");
    }

    static final class Probe {
        private final boolean granted;
        private final String detail;

        Probe(boolean granted, String detail) {
            this.granted = granted;
            this.detail = detail;
        }

        boolean granted() {
            return granted;
        }

        String detail() {
            return detail;
        }
    }
}
