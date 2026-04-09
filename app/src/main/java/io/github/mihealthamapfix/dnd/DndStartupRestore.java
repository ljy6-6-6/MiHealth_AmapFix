package io.github.mihealthamapfix.dnd;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XposedBridge;

import static io.github.mihealthamapfix.util.L.s;

public final class DndStartupRestore {
    private static final String TAG = "AmapFix-DND";
    private static final int MAX_ATTEMPTS = 4;
    private static final long INITIAL_DELAY_MS = 2500L;
    private static final long RETRY_DELAY_MS = 2000L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean SCHEDULED = new AtomicBoolean(false);
    private static final AtomicBoolean COMPLETED = new AtomicBoolean(false);

    private DndStartupRestore() {
    }

    public static void scheduleStartup(Application app) {
        if (app == null || COMPLETED.get()) return;
        if (!SCHEDULED.compareAndSet(false, true)) return;
        final String sessionId = DndDiag.newSessionId("startup_headless");
        XposedBridge.log(TAG + ": " + s(
                "已调度 启动 headless refresh，来源=startup，延迟=",
                "Scheduled startup headless refresh, source=startup, delay=")
                + INITIAL_DELAY_MS + "ms"
                + s("，session=", ", session=") + sessionId);
        MAIN.postDelayed(new Runnable() {
            @Override
            public void run() {
                startAttempt(app, 1, sessionId);
            }
        }, INITIAL_DELAY_MS);
    }

    private static void startAttempt(Application app, int attempt, String sessionId) {
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
                "开始 启动 headless refresh，第 ",
                "Starting startup headless refresh attempt ")
                + attempt + "/" + MAX_ATTEMPTS
                + s("，session=", ", session=") + sessionId
                + s("，", ", ")
                + snapshot.summary());

        DndHeadlessRefresh.start(
                app,
                new DndHeadlessRefresh.AttemptContext(
                        "startup_headless",
                        sessionId,
                        "启动",
                        "startup",
                        attempt),
                new DndHeadlessRefresh.Completion() {
                    @Override
                    public void onComplete(DndHeadlessRefresh.Result result) {
                        finishAttempt(result, app, attempt, sessionId);
                    }
                });
    }

    private static void finishAttempt(
            DndHeadlessRefresh.Result result,
            Application app,
            int attempt,
            String sessionId) {
        XposedBridge.log(TAG + ": " + result.detail());
        switch (result.kind()) {
            case ENABLED_CONFIRMED:
                DndFollowupSync.DispatchResult dispatchResult = DndFollowupSync.dispatch(app, result);
                XposedBridge.log(TAG + ": " + dispatchResult.detail());
                COMPLETED.set(true);
                SCHEDULED.set(false);
                return;
            case RETRYABLE_NOT_READY:
                if (attempt < MAX_ATTEMPTS) {
                    scheduleRetry(app, attempt, sessionId);
                    return;
                }
                XposedBridge.log(TAG + ": " + s(
                        "启动恢复在最大重试次数内仍未确认同步开启，保持本地状态不变，session=",
                        "Startup restore did not confirm sync enabled within the max retry count; keeping the local state unchanged, session=")
                        + sessionId);
                COMPLETED.set(true);
                SCHEDULED.set(false);
                return;
            case HARD_FAILURE:
            default:
                if (attempt < MAX_ATTEMPTS) {
                    scheduleRetry(app, attempt, sessionId);
                    return;
                }
                XposedBridge.log(TAG + ": " + s(
                        "启动 headless refresh 已达到最大重试次数 " + MAX_ATTEMPTS,
                        "Startup headless refresh reached the max retry count " + MAX_ATTEMPTS));
                SCHEDULED.set(false);
                return;
        }
    }

    private static void scheduleRetry(Application app, int attempt, String sessionId) {
        MAIN.postDelayed(new Runnable() {
            @Override
            public void run() {
                startAttempt(app, attempt + 1, sessionId);
            }
        }, RETRY_DELAY_MS);
    }
}
