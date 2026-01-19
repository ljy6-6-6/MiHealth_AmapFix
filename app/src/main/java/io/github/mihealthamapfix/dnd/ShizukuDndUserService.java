package io.github.mihealthamapfix.dnd;

import android.os.Build;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Shizuku UserService implementation.
 *
 * This class is instantiated in Shizuku's privileged process.
 */
public class ShizukuDndUserService extends IShizukuDndService.Stub {

    private volatile String status = "shizuku(usersvc): ready";

    @Override
    public void destroy() {
        // Shizuku will call this (transaction code 16777114) when starting a new version or when requested.
        System.exit(0);
    }

    @Override
    public void setZenMode(int mode) {
        if (Build.VERSION.SDK_INT < 26) {
            status = "shizuku(usersvc): sdk<26";
            return;
        }
        try {
            String cmd1 = "cmd notification set_zen_mode " + DndUtils.zenToCmd(mode);
            int code = runShell(cmd1);
            if (code != 0) {
                code = runShell("settings put global zen_mode " + mode);
                if (code != 0) {
                    status = "shizuku(usersvc): set failed";
                    return;
                }
            }
            status = "shizuku(usersvc): set zen=" + mode;
        } catch (Throwable t) {
            status = "shizuku(usersvc): set error " + t.getClass().getSimpleName();
        }
    }

    @Override
    public int getZenMode() {
        if (Build.VERSION.SDK_INT < 26) return -1;
        try {
            String out = runShellReadLine("settings get global zen_mode");
            if (out == null) return -1;
            out = out.trim();
            return Integer.parseInt(out);
        } catch (Throwable t) {
            status = "shizuku(usersvc): get error " + t.getClass().getSimpleName();
            return -1;
        }
    }

    @Override
    public boolean ping() {
        return true;
    }

    @Override
    public String getStatus() {
        return status;
    }

    private int runShell(String cmd) throws Exception {
        Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
        return p.waitFor();
    }

    private String runShellReadLine(String cmd) throws Exception {
        Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
        BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
        String line = br.readLine();
        br.close();
        p.waitFor();
        return line;
    }
}
