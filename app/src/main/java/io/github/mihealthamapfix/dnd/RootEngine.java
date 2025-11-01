package io.github.mihealthamapfix.dnd;

import com.topjohnwu.superuser.Shell;

public class RootEngine implements DndEngine {
    private String status = "root: not initialized";

    public RootEngine() {
        try {
            Shell.getShell(); // lazy init
            status = "root: shell ready";
        } catch (Throwable t) {
            status = "root: " + t.getClass().getSimpleName();
        }
    }

    @Override public boolean isActive() {
        try {
            Shell.Result r = Shell.cmd("id").exec();
            return r.isSuccess();
        } catch (Throwable t) {
            status = "root: " + t.getClass().getSimpleName();
            return false;
        }
    }

    @Override public String getStatus() { return status; }

    @Override public void setZen(int zen) throws Exception {
        String cmd1 = "cmd notification set_zen_mode " + DndUtils.zenToCmd(zen);
        String cmd2 = "settings put global zen_mode " + zen;
        Shell.Result r1 = Shell.cmd(cmd1).exec();
        if (!r1.isSuccess()) {
            Shell.Result r2 = Shell.cmd(cmd2).exec();
            if (!r2.isSuccess()) {
                throw new IllegalStateException("root: both cmd failed");
            }
        }
        status = "root: set zen=" + zen;
    }

    @Override public int getZen() {
        try {
            Shell.Result r = Shell.cmd("settings get global zen_mode").exec();
            if (!r.isSuccess() || r.getOut().isEmpty()) return -1;
            String s = r.getOut().get(0).trim();
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException e) {
                return -1;
            }
        } catch (Throwable t) {
            status = "root: get failed " + t.getClass().getSimpleName();
            return -1;
        }
    }
}
