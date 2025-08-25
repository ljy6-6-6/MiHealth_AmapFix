package io.github.amapnotifyfix;

import android.service.notification.StatusBarNotification;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * Modern LSPosed (libxposed API 100) entry.
 * Hooks NotificationFilterHelper#isMipmapNotification(StatusBarNotification)
 * and forces it to return false for Amap's navigation notification (and as a safe fallback).
 */
public class ModernEntry extends XposedModule {

    public ModernEntry(XposedInterface base, XposedModuleInterface.ModuleLoadedParam param) {
        super(base, param);
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        String pkg = param.getPackageName();
        if (!"com.xiaomi.wearable".equals(pkg)
                && !"com.mi.health".equals(pkg)
                && !"com.xiaomi.hm.health".equals(pkg)) {
            return;
        }
        try {
            ClassLoader cl = param.getClassLoader();
            Class<?> helper = cl.loadClass("com.xiaomi.fitness.notify.util.NotificationFilterHelper");
            Method m = helper.getDeclaredMethod("isMipmapNotification", StatusBarNotification.class);
            hook(m, IsMipmapHooker.class);
            log("MiFitnessAmapNavFix: hooked isMipmapNotification in " + pkg);
        } catch (Throwable t) {
            log("MiFitnessAmapNavFix: method not found; maybe old version. " + t);
        }
    }

    /** Hooker for the modern API. */
    public static class IsMipmapHooker implements XposedInterface.Hooker {
        public static void before(XposedInterface.BeforeHookCallback callback) {
            try {
                Object[] args = callback.getArgs();
                if (args != null && args.length > 0 && args[0] instanceof StatusBarNotification) {
                    StatusBarNotification sbn = (StatusBarNotification) args[0];
                    String pkg = sbn.getPackageName();
                    int id = sbn.getId();
                    // Amap (Gaode) navigation ongoing notification id is 0x4d4
                    if ("com.autonavi.minimap".equals(pkg) && id == 0x4d4) {
                        callback.setResult(Boolean.FALSE);
                        return;
                    }
                }
                // Fallback: still make it false (avoid blocking other notifications by this filter)
                callback.setResult(Boolean.FALSE);
            } catch (Throwable t) {
                callback.setResult(Boolean.FALSE);
            }
        }

        public static void after(XposedInterface.AfterHookCallback callback) {
            try {
                // If original returned true somehow, force it to false.
                if (!callback.isSkipped() && Boolean.TRUE.equals(callback.getResult())) {
                    callback.setResult(Boolean.FALSE);
                }
            } catch (Throwable ignored) {}
        }
    }
}
