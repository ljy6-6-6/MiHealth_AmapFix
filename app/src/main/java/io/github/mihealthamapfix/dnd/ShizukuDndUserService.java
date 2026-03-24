package io.github.mihealthamapfix.dnd;

import android.os.Build;

import java.io.BufferedReader;
import java.io.InputStreamReader;

import static io.github.mihealthamapfix.util.L.s;

/**
 * Shizuku UserService implementation.
 *
 * This class is instantiated in Shizuku's privileged process.
 */
public class ShizukuDndUserService extends IShizukuDndService.Stub {

    private volatile String status = s("Shizuku 用户服务：已就绪", "Shizuku user service: ready");

    @Override
    public void destroy() {
        System.exit(0);
    }

    @Override
    public void setZenMode(int mode) {
        if (Build.VERSION.SDK_INT < 26) {
            status = s("Shizuku 用户服务：SDK < 26", "Shizuku user service: sdk<26");
            return;
        }

        try {
            runShell("cmd notification set_zen_mode " + DndUtils.zenToCmd(mode));
            if (verifyZen(mode)) {
                status = s("Shizuku 用户服务：已设置 zen=", "Shizuku user service: set zen=") + mode;
                return;
            }

            runShell("settings put global zen_mode " + mode);
            int actual = getZenMode();
            if (actual != mode) {
                status = s("Shizuku 用户服务：校验失败，期望=", "Shizuku user service: verification failed, expected=")
                        + mode + s("，实际=", ", actual=") + actual;
                return;
            }

            status = s("Shizuku 用户服务：已设置 zen=", "Shizuku user service: set zen=") + mode;
        } catch (Throwable t) {
            status = s("Shizuku 用户服务：写入失败 ", "Shizuku user service: set failed ")
                    + t.getClass().getSimpleName();
        }
    }

    @Override
    public int getZenMode() {
        if (Build.VERSION.SDK_INT < 26) return -1;
        try {
            String out = runShellReadLine("settings get global zen_mode");
            if (out == null) return -1;
            return Integer.parseInt(out.trim());
        } catch (Throwable t) {
            status = s("Shizuku 用户服务：读取失败 ", "Shizuku user service: read failed ")
                    + t.getClass().getSimpleName();
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

    private boolean verifyZen(int expected) {
        return getZenMode() == expected;
    }

    private int runShell(String cmd) throws Exception {
        Process process = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
        return process.waitFor();
    }

    private String runShellReadLine(String cmd) throws Exception {
        Process process = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        String line = reader.readLine();
        reader.close();
        process.waitFor();
        return line;
    }
}
