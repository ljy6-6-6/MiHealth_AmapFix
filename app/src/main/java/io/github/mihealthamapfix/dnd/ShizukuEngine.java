package io.github.mihealthamapfix.dnd;

import android.os.Build;

import rikka.shizuku.Shizuku;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/** Executes 'cmd notification set_zen_mode' via Shizuku shell. */
public class ShizukuEngine implements DndEngine {
    private String status = "shizuku: not initialized";

    @Override public boolean isActive() {
        try {
            if (Build.VERSION.SDK_INT < 26) return false;
            if (!Shizuku.pingBinder()) { status = "shizuku: binder not available"; return false; }
            if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                status = "shizuku: permission not granted";
                return false;
            }
            status = "shizuku: ready";
            return true;
        } catch (Throwable t) {
            status = "shizuku: " + t.getClass().getSimpleName();
            return false;
        }
    }

    @Override public String getStatus() { return status; }

    @Override public void setZen(int zen) throws Exception {
        String cmd = "cmd notification set_zen_mode " + DndUtils.zenToCmd(zen);
        int code = runShell(cmd);
        if (code != 0) {
            code = runShell("settings put global zen_mode " + zen);
            if (code != 0) throw new IllegalStateException("shizuku: cmds failed");
        }
        status = "shizuku: set zen=" + zen;
    }

    @Override public int getZen() {
        try {
            Process p = Shizuku.newProcess(new String[]{"sh", "-c", "settings get global zen_mode"}, null, null);
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String s = br.readLine();
            br.close();
            p.waitFor();
            if (s == null) return -1;
            s = s.trim();
            try { return Integer.parseInt(s); } catch (NumberFormatException e) { return -1; }
        } catch (Throwable t) {
            status = "shizuku: get failed " + t.getClass().getSimpleName();
            return -1;
        }
    }

    private int runShell(String cmd) throws Exception {
        Process p = Shizuku.newProcess(new String[]{"sh", "-c", cmd}, null, null);
        return p.waitFor();
    }
}
