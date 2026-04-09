package io.github.mihealthamapfix.dnd;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import de.robv.android.xposed.XposedBridge;

import static io.github.mihealthamapfix.util.L.s;

public final class DndReconnectRestore {
    private static final String TAG = "AmapFix-DND";
    private static final int MAX_ATTEMPTS = 6;
    private static final long INITIAL_DELAY_MS = 3500L;
    private static final long RETRY_DELAY_MS = 2000L;
    private static final long DEBOUNCE_MS = 3000L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static RefreshRequest sRunningRequest;
    private static RefreshRequest sPendingRequest;
    private static Runnable sPendingRunnable;
    private static String sLastAcceptedDid;
    private static long sLastAcceptedAtUptimeMs;

    private DndReconnectRestore() {
    }

    public static void scheduleReconnect(Application app, String source, String did) {
        if (app == null || did == null || did.isEmpty()) return;
        final String safeSource = source == null || source.isEmpty() ? "eco_connect_state" : source;
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                scheduleInternal(app, safeSource, did);
            }
        });
    }

    private static void scheduleInternal(Application app, String source, String did) {
        long now = SystemClock.uptimeMillis();
        if (did.equals(sLastAcceptedDid) && now - sLastAcceptedAtUptimeMs < DEBOUNCE_MS) {
            XposedBridge.log(TAG + ": " + s(
                    "已跳过重复的 reconnect headless refresh，来源=",
                    "Skipped a duplicate reconnect headless refresh, source=")
                    + source
                    + s("，DID=", ", DID=") + did);
            return;
        }
        sLastAcceptedDid = did;
        sLastAcceptedAtUptimeMs = now;

        RefreshRequest request = RefreshRequest.first(app, source, did);
        if (sRunningRequest != null) {
            sPendingRequest = request;
            XposedBridge.log(TAG + ": " + s(
                    "已排队 reconnect headless refresh，来源=",
                    "Queued reconnect headless refresh, source=")
                    + request.source
                    + s("，DID=", ", DID=") + request.did
                    + s("，session=", ", session=") + request.sessionId);
            return;
        }

        postRequest(request);
    }

    private static void postRequest(RefreshRequest request) {
        sPendingRequest = request;
        if (sPendingRunnable != null) {
            MAIN.removeCallbacks(sPendingRunnable);
        }
        sPendingRunnable = new Runnable() {
            @Override
            public void run() {
                if (sPendingRequest != request) {
                    return;
                }
                sPendingRunnable = null;
                sPendingRequest = null;
                startAttempt(request);
            }
        };
        long delayMs = Math.max(0L, request.executeAtUptimeMs - SystemClock.uptimeMillis());
        MAIN.postDelayed(sPendingRunnable, delayMs);
        XposedBridge.log(TAG + ": " + s(
                "已调度 reconnect headless refresh，来源=",
                "Scheduled reconnect headless refresh, source=")
                + request.source
                + s("，DID=", ", DID=") + request.did
                + s("，session=", ", session=") + request.sessionId
                + s("，延迟=", ", delay=") + delayMs + "ms");
    }

    private static void startAttempt(RefreshRequest request) {
        sRunningRequest = request;

        DndEnv.Snapshot snapshot = DndEnv.getSnapshot(true);
        if (!snapshot.canUsePrivilegedDnd()) {
            XposedBridge.log(TAG + ": " + s(
                    "重连恢复已跳过：当前没有可用 DND 路由，",
                    "Reconnect restore skipped: no DND route is available, ")
                    + snapshot.summary());
            completeRunningRequest();
            return;
        }

        XposedBridge.log(TAG + ": " + s(
                "开始 重连 headless refresh，第 ",
                "Starting reconnect headless refresh attempt ")
                + request.attempt + "/" + MAX_ATTEMPTS
                + s("，来源=", ", source=") + request.source
                + s("，DID=", ", DID=") + request.did
                + s("，session=", ", session=") + request.sessionId
                + s("，", ", ")
                + snapshot.summary());

        DndHeadlessRefresh.start(
                request.app,
                new DndHeadlessRefresh.AttemptContext(
                        "reconnect_headless",
                        request.sessionId,
                        "重连",
                        "reconnect",
                        request.attempt),
                new DndHeadlessRefresh.Completion() {
                    @Override
                    public void onComplete(DndHeadlessRefresh.Result result) {
                        finishAttempt(result, request);
                    }
                });
    }

    private static void finishAttempt(DndHeadlessRefresh.Result result, RefreshRequest request) {
        XposedBridge.log(TAG + ": " + result.detail());
        switch (result.kind()) {
            case ENABLED_CONFIRMED:
                DndFollowupSync.DispatchResult dispatchResult = DndFollowupSync.dispatch(request.app, result);
                XposedBridge.log(TAG + ": " + dispatchResult.detail());
                completeRunningRequest();
                return;
            case RETRYABLE_NOT_READY:
                if (request.attempt < MAX_ATTEMPTS) {
                    scheduleRetry(request);
                    return;
                }
                XposedBridge.log(TAG + ": " + s(
                        "重连恢复在最大重试次数内仍未确认同步开启，保持本地状态不变，来源=",
                        "Reconnect restore did not confirm sync enabled within the max retry count; keeping the local state unchanged, source=")
                        + request.source
                        + s("，DID=", ", DID=") + request.did
                        + s("，session=", ", session=") + request.sessionId);
                completeRunningRequest();
                return;
            case HARD_FAILURE:
            default:
                if (request.attempt < MAX_ATTEMPTS) {
                    scheduleRetry(request);
                    return;
                }
                XposedBridge.log(TAG + ": " + s(
                        "重连 headless refresh 已达到最大重试次数 " + MAX_ATTEMPTS,
                        "Reconnect headless refresh reached the max retry count " + MAX_ATTEMPTS));
                completeRunningRequest();
                return;
        }
    }

    private static void scheduleRetry(RefreshRequest request) {
        RefreshRequest retry = request.nextAttempt();
        sRunningRequest = retry;
        MAIN.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (sRunningRequest == retry) {
                    startAttempt(retry);
                }
            }
        }, RETRY_DELAY_MS);
    }

    private static void completeRunningRequest() {
        sRunningRequest = null;
        if (sPendingRequest == null) {
            return;
        }
        RefreshRequest next = sPendingRequest;
        sPendingRequest = null;
        postRequest(next);
    }

    private static final class RefreshRequest {
        private final Application app;
        private final String source;
        private final String did;
        private final String sessionId;
        private final int attempt;
        private final long executeAtUptimeMs;

        private RefreshRequest(
                Application app,
                String source,
                String did,
                String sessionId,
                int attempt,
                long executeAtUptimeMs) {
            this.app = app;
            this.source = source;
            this.did = did;
            this.sessionId = sessionId;
            this.attempt = attempt;
            this.executeAtUptimeMs = executeAtUptimeMs;
        }

        private static RefreshRequest first(Application app, String source, String did) {
            return new RefreshRequest(
                    app,
                    source,
                    did,
                    DndDiag.newSessionId("reconnect_headless"),
                    1,
                    SystemClock.uptimeMillis() + INITIAL_DELAY_MS);
        }

        private RefreshRequest nextAttempt() {
            return new RefreshRequest(
                    app,
                    source,
                    did,
                    sessionId,
                    attempt + 1,
                    SystemClock.uptimeMillis() + RETRY_DELAY_MS);
        }
    }
}
