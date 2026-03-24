package io.github.mihealthamapfix.dnd;

import android.os.Build;
import android.os.SystemClock;

import static io.github.mihealthamapfix.util.L.s;

/**
 * Cached DND capability snapshot used from inside the hooked app process.
 */
public final class DndEnv {
    private static final long CACHE_TTL_MS = 3000L;
    private static final String SHIZUKU_HIDDEN_DETAIL = s(
            "已隐藏：模块侧 IPC 仍受 bindService=false 限制",
            "hidden: module-side IPC is still blocked by bindService=false");

    private static volatile Snapshot SNAPSHOT = Snapshot.none(
            "sdk<35: disabled",
            "ROOT: not checked",
            SHIZUKU_HIDDEN_DETAIL);
    private static volatile long CHECKED_AT_MS;

    private DndEnv() {
    }

    public static Snapshot getSnapshot() {
        return getSnapshot(false);
    }

    public static synchronized Snapshot getSnapshot(boolean forceRefresh) {
        long now = SystemClock.elapsedRealtime();
        if (!forceRefresh && now - CHECKED_AT_MS < CACHE_TTL_MS) {
            return SNAPSHOT;
        }

        if (Build.VERSION.SDK_INT < 35) {
            SNAPSHOT = Snapshot.none(
                    "sdk<35: disabled",
                    "ROOT: sdk<35",
                    SHIZUKU_HIDDEN_DETAIL);
            CHECKED_AT_MS = now;
            return SNAPSHOT;
        }

        RootEngine.Probe root = RootEngine.probeAccess();
        DndRoute route = root.granted() ? DndRoute.HOST_ROOT : DndRoute.NONE;
        String routeStatus = root.granted()
                ? root.detail()
                : s("无可用 DND 路由（稳定 ROOT 不可用）", "No DND route available (stable ROOT unavailable)");

        SNAPSHOT = new Snapshot(
                route,
                routeStatus,
                root.granted(),
                root.detail(),
                SHIZUKU_HIDDEN_DETAIL);
        CHECKED_AT_MS = now;
        return SNAPSHOT;
    }

    public static final class Snapshot {
        private final DndRoute route;
        private final String routeStatus;
        private final boolean hostRootGranted;
        private final String hostRootDetail;
        private final String shizukuHiddenDetail;

        private Snapshot(
                DndRoute route,
                String routeStatus,
                boolean hostRootGranted,
                String hostRootDetail,
                String shizukuHiddenDetail) {
            this.route = route;
            this.routeStatus = routeStatus;
            this.hostRootGranted = hostRootGranted;
            this.hostRootDetail = hostRootDetail;
            this.shizukuHiddenDetail = shizukuHiddenDetail;
        }

        private static Snapshot none(
                String routeStatus,
                String hostRootDetail,
                String shizukuHiddenDetail) {
            return new Snapshot(
                    DndRoute.NONE,
                    routeStatus,
                    false,
                    hostRootDetail,
                    shizukuHiddenDetail);
        }

        public DndRoute route() {
            return route;
        }

        public String status() {
            return routeStatus;
        }

        public boolean hostRootGranted() {
            return hostRootGranted;
        }

        public String hostRootDetail() {
            return hostRootDetail;
        }

        public String shizukuHiddenDetail() {
            return shizukuHiddenDetail;
        }

        public boolean canUsePrivilegedDnd() {
            return route.canUsePrivilegedDnd();
        }

        public String summary() {
            return s("稳定 ROOT=", "ROOT(stable)=")
                    + grantLabel(hostRootGranted, hostRootDetail)
                    + s("；Shizuku(hidden)=", "; Shizuku(hidden)=")
                    + safe(shizukuHiddenDetail)
                    + s("；最终路由=", "; effective route=")
                    + route.routeLabel()
                    + s("；状态=", "; status=")
                    + routeStatus;
        }

        private String grantLabel(boolean granted, String detail) {
            String state = granted ? s("允许", "granted") : s("拒绝", "denied");
            return state + " (" + safe(detail) + ")";
        }

        private String safe(String value) {
            return value == null || value.isEmpty()
                    ? s("未知", "unknown")
                    : value;
        }
    }
}
