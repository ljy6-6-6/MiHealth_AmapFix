package io.github.mihealthamapfix.util;

import android.app.Application;

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
}
