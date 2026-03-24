package io.github.mihealthamapfix.util;

import android.app.Application;
import android.os.Build;

import java.io.BufferedReader;
import java.io.FileReader;

public final class AppCtx {
    private AppCtx() {}

    public static Application app() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            return (Application) app;
        } catch (Throwable t) {
            return null;
        }
    }

    public static String currentProcessName() {
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                return Application.getProcessName();
            } catch (Throwable ignored) {}
        }
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object name = at.getMethod("currentProcessName").invoke(null);
            return name instanceof String ? (String) name : null;
        } catch (Throwable ignored) {}
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/self/cmdline"))) {
            String raw = reader.readLine();
            if (raw == null) return null;
            String name = raw.trim();
            return name.isEmpty() ? null : name;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static boolean isMainProcess(Application app) {
        if (app == null) return false;
        String current = currentProcessName();
        if (current == null || current.isEmpty()) {
            return true;
        }
        String expected = null;
        try {
            if (app.getApplicationInfo() != null) {
                expected = app.getApplicationInfo().processName;
            }
        } catch (Throwable ignored) {}
        if (expected == null || expected.isEmpty()) {
            expected = app.getPackageName();
        }
        return expected == null || expected.isEmpty() || expected.equals(current);
    }
}
