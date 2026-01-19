package io.github.mihealthamapfix.dnd;

import android.content.ComponentName;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.IBinder;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import rikka.shizuku.Shizuku;

/**
 * DND engine backed by Shizuku.
 *
 * Shizuku v13 makes Shizuku#newProcess inaccessible, so we run commands through a Shizuku UserService instead.
 */
public class ShizukuEngine implements DndEngine {

    private static final String PKG = "io.github.mihealthamapfix";

    private final Object lock = new Object();
    private volatile String status = "shizuku: not initialized";

    private final AtomicReference<IShizukuDndService> svcRef = new AtomicReference<>();

    private final Shizuku.UserServiceArgs userServiceArgs =
            new Shizuku.UserServiceArgs(new ComponentName(PKG, ShizukuDndUserService.class.getName()))
                    .daemon(true)
                    .processNameSuffix("dnd")
                    .debuggable(false)
                    .version(1);

    private volatile ServiceConnection serviceConnection;

    @Override
    public boolean isActive() {
        try {
            if (Build.VERSION.SDK_INT < 26) return false;
            if (!Shizuku.pingBinder()) {
                status = "shizuku: binder not available";
                return false;
            }
            if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                status = "shizuku: permission not granted";
                return false;
            }
            ensureConnected(800);
            IShizukuDndService s = svcRef.get();
            if (s == null) {
                status = "shizuku: user service not connected";
                return false;
            }
            try {
                if (!s.ping()) {
                    status = "shizuku: user service ping failed";
                    return false;
                }
            } catch (Throwable ignored) {
                // Treat remote exceptions as inactive.
                svcRef.set(null);
                status = "shizuku: user service dead";
                return false;
            }
            status = "shizuku: ready";
            return true;
        } catch (Throwable t) {
            status = "shizuku: " + t.getClass().getSimpleName();
            return false;
        }
    }

    @Override
    public String getStatus() {
        IShizukuDndService s = svcRef.get();
        if (s != null) {
            try {
                String remote = s.getStatus();
                if (remote != null && !remote.isEmpty()) return remote;
            } catch (Throwable ignored) {
                // ignore
            }
        }
        return status;
    }

    @Override
    public void setZen(int zen) throws Exception {
        ensureConnected(1200);
        IShizukuDndService s = svcRef.get();
        if (s == null) throw new IllegalStateException("shizuku: user service not connected");
        s.setZenMode(zen);
    }

    @Override
    public int getZen() {
        try {
            ensureConnected(1200);
            IShizukuDndService s = svcRef.get();
            if (s == null) return -1;
            return s.getZenMode();
        } catch (Throwable t) {
            status = "shizuku: get failed " + t.getClass().getSimpleName();
            return -1;
        }
    }

    private void ensureConnected(long timeoutMs) {
        if (svcRef.get() != null) return;

        synchronized (lock) {
            if (svcRef.get() != null) return;
            if (serviceConnection != null) return; // already binding/bound

            final CountDownLatch latch = new CountDownLatch(1);
            serviceConnection = new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder service) {
                    svcRef.set(IShizukuDndService.Stub.asInterface(service));
                    status = "shizuku: user service connected";
                    latch.countDown();
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    svcRef.set(null);
                    status = "shizuku: user service disconnected";
                }
            };

            try {
                Shizuku.bindUserService(userServiceArgs, serviceConnection);
            } catch (Throwable t) {
                status = "shizuku: bind failed " + t.getClass().getSimpleName();
                serviceConnection = null;
                return;
            }

            try {
                // Wait a bit for the async connection, so the first call doesn't just fail immediately.
                latch.await(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            // If bind didn't connect in time, leave it; later calls will still benefit once connected.
        }
    }
}
