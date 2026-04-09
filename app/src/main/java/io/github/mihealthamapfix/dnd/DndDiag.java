package io.github.mihealthamapfix.dnd;

import android.os.SystemClock;

import java.util.concurrent.atomic.AtomicInteger;

import static io.github.mihealthamapfix.util.L.s;

public final class DndDiag {
    private static final long CONTEXT_TTL_MS = 15000L;
    private static final AtomicInteger NEXT_SESSION_ID = new AtomicInteger(1);
    private static final ThreadLocal<TraceContext> SCOPED_CONTEXT = new ThreadLocal<>();

    private static volatile TraceContext LAST_CONTEXT;

    private DndDiag() {
    }

    public static String newSessionId(String source) {
        String safeSource = source == null || source.isEmpty() ? "unknown" : source;
        return safeSource + "#" + NEXT_SESSION_ID.getAndIncrement();
    }

    public static void mark(String source, String sessionId, String did) {
        LAST_CONTEXT = newTraceContext(source, sessionId, did);
    }

    public static Scope enterScopedContext(String source, String sessionId, String did) {
        TraceContext previous = SCOPED_CONTEXT.get();
        TraceContext current = newTraceContext(source, sessionId, did);
        SCOPED_CONTEXT.set(current);
        LAST_CONTEXT = current;
        return new Scope(previous);
    }

    public static boolean hasScopedContext() {
        return resolveScopedContext() != null;
    }

    public static String currentScopedSource() {
        TraceContext context = resolveScopedContext();
        return context == null ? null : context.source;
    }

    public static String currentScopedSessionId() {
        TraceContext context = resolveScopedContext();
        return context == null ? null : context.sessionId;
    }

    public static String currentScopedDid() {
        TraceContext context = resolveScopedContext();
        return context == null ? null : context.did;
    }

    public static String contextSummary() {
        TraceContext context = resolveActiveContext();
        if (context == null) {
            return s("ctx=<无>", "ctx=<none>");
        }

        return "ctx=" + context.source
                + ", session=" + context.sessionId
                + ", did=" + safeDid(context.did)
                + ", age=" + (SystemClock.uptimeMillis() - context.markedAtUptimeMs) + "ms";
    }

    private static TraceContext newTraceContext(String source, String sessionId, String did) {
        return new TraceContext(
                source == null || source.isEmpty() ? "unknown" : source,
                sessionId == null || sessionId.isEmpty() ? "unknown" : sessionId,
                did,
                SystemClock.uptimeMillis());
    }

    private static TraceContext resolveActiveContext() {
        TraceContext scoped = resolveScopedContext();
        if (scoped != null) {
            return scoped;
        }

        TraceContext sticky = LAST_CONTEXT;
        if (sticky == null) {
            return null;
        }
        long age = SystemClock.uptimeMillis() - sticky.markedAtUptimeMs;
        if (age > CONTEXT_TTL_MS) {
            return null;
        }
        return sticky;
    }

    private static TraceContext resolveScopedContext() {
        TraceContext scoped = SCOPED_CONTEXT.get();
        if (scoped == null) {
            return null;
        }
        long age = SystemClock.uptimeMillis() - scoped.markedAtUptimeMs;
        if (age > CONTEXT_TTL_MS) {
            SCOPED_CONTEXT.remove();
            return null;
        }
        return scoped;
    }

    private static String safeDid(String did) {
        return did == null || did.isEmpty()
                ? s("<空>", "<empty>")
                : did;
    }

    private static final class TraceContext {
        private final String source;
        private final String sessionId;
        private final String did;
        private final long markedAtUptimeMs;

        private TraceContext(String source, String sessionId, String did, long markedAtUptimeMs) {
            this.source = source;
            this.sessionId = sessionId;
            this.did = did;
            this.markedAtUptimeMs = markedAtUptimeMs;
        }
    }

    public static final class Scope implements AutoCloseable {
        private final TraceContext previous;
        private boolean closed;

        private Scope(TraceContext previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (previous == null) {
                SCOPED_CONTEXT.remove();
            } else {
                SCOPED_CONTEXT.set(previous);
            }
        }
    }
}
