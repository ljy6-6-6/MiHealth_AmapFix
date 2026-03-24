package io.github.mihealthamapfix.dnd;

import android.app.NotificationManager;

import com.topjohnwu.superuser.Shell;

import java.util.List;

import io.github.mihealthamapfix.util.AppCtx;

import static io.github.mihealthamapfix.util.L.s;

public class RootEngine implements DndEngine {
    private static final int SHORT_VERIFY_ATTEMPTS = 6;
    private static final long SHORT_VERIFY_SLEEP_MS = 100L;

    private String status = s("ROOT：未初始化", "ROOT: not initialized");
    private String lastWritePath = "none";
    private String lastWriteSummary = "none";

    public RootEngine() {
        status = probeAccess().detail();
    }

    @Override
    public boolean isActive() {
        Probe probe = probeAccess();
        status = probe.detail();
        return probe.granted();
    }

    @Override
    public String getStatus() {
        return status;
    }

    public String getLastWritePath() {
        return lastWritePath;
    }

    public String getLastWriteSummary() {
        return lastWriteSummary;
    }

    @Override
    public void setZen(int zen) throws Exception {
        lastWritePath = "none";
        lastWriteSummary = "none";

        Attempt setDnd = runAttempt("set_dnd", "cmd notification set_dnd " + DndUtils.zenToCmd(zen), zen);
        if (setDnd.verified) {
            markRequested(zen, setDnd);
            return;
        }

        Attempt setZenMode = runAttempt("set_zen_mode", "cmd notification set_zen_mode " + DndUtils.zenToCmd(zen), zen);
        if (setZenMode.verified) {
            markRequested(zen, setZenMode);
            return;
        }

        Attempt settings = runAttempt("settings", "settings put global zen_mode " + zen, zen);
        if (settings.verified) {
            markRequested(zen, settings);
            return;
        }

        boolean anyCommandSucceeded = setDnd.commandSucceeded || setZenMode.commandSucceeded || settings.commandSucceeded;
        lastWritePath = settings.commandSucceeded
                ? settings.path
                : setZenMode.commandSucceeded
                ? setZenMode.path
                : setDnd.commandSucceeded
                ? setDnd.path
                : "none";
        lastWriteSummary = setDnd.summary + " | " + setZenMode.summary + " | " + settings.summary;

        if (anyCommandSucceeded) {
            status = s("ROOT：已发出写入请求，等待最终校验，路径=",
                    "ROOT: write requested, waiting for final verification, path=")
                    + lastWritePath;
            return;
        }

        Readback readback = readCurrentState();
        status = s("ROOT：所有写入路径都失败，当前读回=",
                "ROOT: all write paths failed, current readback=") + readback.summary();
        throw new IllegalStateException(status);
    }

    @Override
    public int getZen() {
        return readCurrentState().runtimeZen();
    }

    public Readback readCurrentState() {
        Integer interruptionFilter = null;
        int runtimeZen = -1;
        String runtimeDetail = null;

        try {
            NotificationManager notificationManager = AppCtx.app() != null
                    ? AppCtx.app().getSystemService(NotificationManager.class)
                    : null;
            if (notificationManager == null) {
                runtimeDetail = s("宿主 NotificationManager 不可用", "Host NotificationManager unavailable");
            } else {
                interruptionFilter = notificationManager.getCurrentInterruptionFilter();
                runtimeZen = DndUtils.filterToZen(interruptionFilter);
            }
        } catch (Throwable t) {
            runtimeDetail = s("读取 interruptionFilter 失败：", "Failed to read interruptionFilter: ")
                    + t.getClass().getSimpleName();
        }

        Integer settingsZen = readSettingsZen();
        if (runtimeZen >= 0) {
            status = s("ROOT：运行时读回 zen=", "ROOT: runtime readback zen=") + runtimeZen
                    + s("，interruptionFilter=", ", interruptionFilter=") + interruptionFilter;
        } else if (runtimeDetail != null) {
            status = s("ROOT：运行时读回失败：", "ROOT: runtime readback failed: ") + runtimeDetail;
        }
        return new Readback(interruptionFilter, runtimeZen, settingsZen, runtimeDetail);
    }

    private Attempt runAttempt(String path, String command, int expectedZen) {
        Shell.Result result;
        try {
            result = Shell.cmd(command).exec();
        } catch (Throwable t) {
            return new Attempt(path, false, false, path + "(exception=" + t.getClass().getSimpleName() + ")");
        }

        boolean verified = false;
        if (result.isSuccess()) {
            verified = verifyRuntimeZen(expectedZen, SHORT_VERIFY_ATTEMPTS, SHORT_VERIFY_SLEEP_MS);
        }

        String summary = path
                + "(cmdSuccess=" + result.isSuccess()
                + ", verified=" + verified + ")";
        return new Attempt(path, result.isSuccess(), verified, summary);
    }

    private void markRequested(int zen, Attempt attempt) {
        lastWritePath = attempt.path;
        lastWriteSummary = attempt.summary;
        status = s("ROOT：已请求设置 zen=", "ROOT: requested zen=") + zen
                + s("，路径=", ", path=") + attempt.path;
    }

    private boolean verifyRuntimeZen(int expected, int attempts, long sleepMs) {
        for (int i = 0; i < attempts; i++) {
            if (readCurrentState().runtimeZen() == expected) {
                return true;
            }
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return readCurrentState().runtimeZen() == expected;
    }

    private Integer readSettingsZen() {
        try {
            Shell.Result result = Shell.cmd("settings get global zen_mode").exec();
            if (!result.isSuccess() || result.getOut().isEmpty()) {
                return null;
            }
            String raw = result.getOut().get(0).trim();
            if (raw.isEmpty() || "null".equalsIgnoreCase(raw)) {
                return null;
            }
            return Integer.parseInt(raw);
        } catch (Throwable ignored) {
            return null;
        }
    }

    static Probe probeAccess() {
        try {
            Boolean granted = Shell.isAppGrantedRoot();
            if (Boolean.TRUE.equals(granted)) {
                return new Probe(true, s("ROOT：libsu 已确认授予", "ROOT: libsu confirmed root access"));
            }
            if (Boolean.FALSE.equals(granted)) {
                return new Probe(false, s("ROOT：libsu 报告未授予 root", "ROOT: libsu reports root not granted"));
            }
        } catch (Throwable ignored) {
        }

        try {
            Shell shell = Shell.getShell();
            if (shell == null) {
                return new Probe(false, s("ROOT：shell 为空", "ROOT: shell is null"));
            }
            if (!shell.isAlive()) {
                return new Probe(false, s("ROOT：shell 不可用", "ROOT: shell is not alive"));
            }
            if (shell.isRoot()) {
                return new Probe(true, s("ROOT：root shell 已就绪", "ROOT: root shell is ready"));
            }

            Probe probe = probeViaId();
            if (probe != null) {
                return probe;
            }
            return new Probe(false, s("ROOT：当前 shell 不是 root", "ROOT: current shell is not root"));
        } catch (Throwable t) {
            return new Probe(false, s("ROOT：检查失败 ", "ROOT: check failed ")
                    + t.getClass().getSimpleName());
        }
    }

    private static Probe probeViaId() {
        try {
            Shell.Result uidResult = Shell.cmd("id -u").exec();
            String uid = firstLine(uidResult.getOut());
            if (uidResult.isSuccess() && uid != null) {
                if ("0".equals(uid)) {
                    return new Probe(true, s("ROOT：id -u=0", "ROOT: id -u=0"));
                }
                return new Probe(false, s("ROOT：id -u=", "ROOT: id -u=") + uid);
            }
        } catch (Throwable ignored) {
        }

        try {
            Shell.Result idResult = Shell.cmd("id").exec();
            String id = firstLine(idResult.getOut());
            if (idResult.isSuccess() && id != null) {
                if (id.contains("uid=0")) {
                    return new Probe(true, s("ROOT：id 输出包含 uid=0", "ROOT: id output shows uid=0"));
                }
                return new Probe(false, s("ROOT：id 输出为 ", "ROOT: id output was ") + id);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String firstLine(List<String> lines) {
        if (lines == null || lines.isEmpty()) return null;
        String line = lines.get(0);
        if (line == null) return null;
        String value = line.trim();
        return value.isEmpty() ? null : value;
    }

    private static final class Attempt {
        private final String path;
        private final boolean commandSucceeded;
        private final boolean verified;
        private final String summary;

        private Attempt(String path, boolean commandSucceeded, boolean verified, String summary) {
            this.path = path;
            this.commandSucceeded = commandSucceeded;
            this.verified = verified;
            this.summary = summary;
        }
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

    public static final class Readback {
        private final Integer interruptionFilter;
        private final int runtimeZen;
        private final Integer settingsZen;
        private final String runtimeDetail;

        Readback(Integer interruptionFilter, int runtimeZen, Integer settingsZen, String runtimeDetail) {
            this.interruptionFilter = interruptionFilter;
            this.runtimeZen = runtimeZen;
            this.settingsZen = settingsZen;
            this.runtimeDetail = runtimeDetail;
        }

        public Integer interruptionFilter() {
            return interruptionFilter;
        }

        public int runtimeZen() {
            return runtimeZen;
        }

        public Integer settingsZen() {
            return settingsZen;
        }

        public String runtimeDetail() {
            return runtimeDetail;
        }

        public String summary() {
            StringBuilder sb = new StringBuilder();
            sb.append("interruptionFilter=").append(interruptionFilter);
            sb.append(", mappedZen=").append(runtimeZen);
            if (settingsZen != null) {
                sb.append(", settingsZen=").append(settingsZen);
            }
            if (runtimeDetail != null && !runtimeDetail.isEmpty()) {
                sb.append(", detail=").append(runtimeDetail);
            }
            return sb.toString();
        }
    }
}
