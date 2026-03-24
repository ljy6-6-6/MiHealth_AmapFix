package io.github.mihealthamapfix.dnd;

import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import io.github.mihealthamapfix.IDndBridgeService;

import static io.github.mihealthamapfix.util.L.s;

public class DndBridgeService extends Service {
    private static final String TAG = "AmapFix-DND";

    private DndEngine engine;
    private DndMode engineMode = DndMode.NONE;
    private volatile String bridgeStatus = s(
            "实验桥：Binder 已连接，模块桥模式=NONE",
            "experimental bridge: binder connected, module bridge mode=NONE");

    private final IDndBridgeService.Stub binder = new IDndBridgeService.Stub() {
        @Override
        public void setZenMode(int mode) {
            if (!isEligible()) return;
            DndEngine current = ensureEngine();
            if (current == null) {
                Log.i(TAG, s("实验桥 setZen 已忽略：后端不可用", "Experimental bridge setZen ignored: backend unavailable"));
                return;
            }
            try {
                current.setZen(mode);
            } catch (Throwable t) {
                Log.w(TAG, s("实验桥 setZen 出错", "Experimental bridge setZen error"), t);
                invalidateEngine();
            }
        }

        @Override
        public int getZenMode() {
            if (!isEligible()) return -1;
            DndEngine current = ensureEngine();
            if (current == null) return -1;
            try {
                return current.getZen();
            } catch (Throwable t) {
                Log.w(TAG, s("实验桥 getZen 出错", "Experimental bridge getZen error"), t);
                invalidateEngine();
                return -1;
            }
        }

        @Override
        public int getCapabilityMode() {
            if (!isEligible()) return DndMode.NONE.code();
            return resolveCapabilityMode(true).code();
        }

        @Override
        public boolean isActive() {
            if (!isEligible()) return false;
            DndEngine current = ensureEngine();
            return current != null && current.isActive();
        }

        @Override
        public String getStatus() {
            if (!isEligible()) return s("sdk<35：已禁用", "sdk<35: disabled");
            DndEngine current = engine;
            if (current != null) {
                try {
                    if (current.isActive()) {
                        bridgeStatus = formatBridgeStatus(engineMode, current.getStatus());
                        return bridgeStatus;
                    }
                    bridgeStatus = formatBridgeStatus(engineMode, current.getStatus());
                } catch (Throwable t) {
                    bridgeStatus = s(
                            "实验桥：后端存活检查失败，原因=",
                            "experimental bridge: backend liveness check failed, reason=")
                            + t.getClass().getSimpleName();
                }
            } else {
                resolveCapabilityMode(true);
            }
            return bridgeStatus;
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    private boolean isEligible() {
        return Build.VERSION.SDK_INT >= 35;
    }

    private synchronized DndEngine ensureEngine() {
        if (engine != null) {
            try {
                if (engine.isActive()) {
                    bridgeStatus = formatBridgeStatus(engineMode, engine.getStatus());
                    return engine;
                }
                bridgeStatus = formatBridgeStatus(engineMode, engine.getStatus());
                Log.i(TAG, s(
                        "丢弃失活的实验桥后端：",
                        "Discarding inactive experimental bridge backend: ") + bridgeStatus);
            } catch (Throwable t) {
                Log.w(TAG, s(
                        "实验桥后端存活检查失败",
                        "Experimental bridge backend liveness check failed"), t);
                bridgeStatus = s(
                        "实验桥：后端存活检查失败",
                        "experimental bridge: backend liveness check failed");
            }
            engine = null;
            engineMode = DndMode.NONE;
        }

        engine = pickEngine();
        return engine;
    }

    private synchronized void invalidateEngine() {
        engine = null;
        engineMode = DndMode.NONE;
    }

    private DndEngine pickEngine() {
        DndEngine shizuku = tryCreateShizukuEngine();
        if (shizuku != null) return shizuku;

        resolveCapabilityMode(true);
        Log.i(TAG, s("实验桥当前不可用：", "Experimental bridge currently unavailable: ") + bridgeStatus);
        return null;
    }

    private DndEngine tryCreateShizukuEngine() {
        ShizukuEngine.Probe probe = ShizukuEngine.probeAccess();
        if (!probe.granted()) {
            bridgeStatus = formatBridgeStatus(DndMode.NONE, probe.detail());
            return null;
        }

        DndEngine candidate;
        try {
            candidate = new ShizukuEngine();
        } catch (Throwable t) {
            bridgeStatus = s(
                    "实验桥：创建 Shizuku 后端失败",
                    "experimental bridge: failed to create Shizuku backend");
            Log.w(TAG, s(
                    "创建 Shizuku 实验桥后端失败",
                    "Failed to create Shizuku experimental bridge backend"), t);
            return null;
        }
        if (!candidate.isActive()) {
            bridgeStatus = formatBridgeStatus(DndMode.SHIZUKU, candidate.getStatus());
            return null;
        }

        engineMode = DndMode.SHIZUKU;
        bridgeStatus = formatBridgeStatus(engineMode, candidate.getStatus());
        Log.i(TAG, s(
                "实验桥已选择 Shizuku 后端",
                "Experimental bridge is using the Shizuku backend"));
        return candidate;
    }

    private DndMode resolveCapabilityMode(boolean updateStatus) {
        ShizukuEngine.Probe shizuku = ShizukuEngine.probeAccess();
        if (shizuku.granted()) {
            if (updateStatus) {
                bridgeStatus = formatBridgeStatus(DndMode.SHIZUKU, shizuku.detail());
            }
            return DndMode.SHIZUKU;
        }

        if (updateStatus) {
            bridgeStatus = formatBridgeStatus(DndMode.NONE, shizuku.detail());
        }
        return DndMode.NONE;
    }

    private String formatBridgeStatus(DndMode mode, String detail) {
        switch (mode) {
            case SHIZUKU:
                return s(
                        "实验桥：Binder 已连接，backend=SHIZUKU",
                        "experimental bridge: binder connected, backend=SHIZUKU")
                        + (detail == null || detail.isEmpty() ? "" : s("，状态=", ", status=") + detail);
            default:
                return s(
                        "实验桥：Binder 已连接，模块桥模式=NONE",
                        "experimental bridge: binder connected, module bridge mode=NONE")
                        + (detail == null || detail.isEmpty() ? "" : s("，状态=", ", status=") + detail);
        }
    }
}
