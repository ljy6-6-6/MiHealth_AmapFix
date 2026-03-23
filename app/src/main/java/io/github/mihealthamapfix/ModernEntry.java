package io.github.mihealthamapfix;

import android.service.notification.StatusBarNotification;


import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.XC_MethodHook;

import static io.github.mihealthamapfix.HookConstants.AMAP_NAV_ID;
import static io.github.mihealthamapfix.HookConstants.AMAP_PACKAGE;
import static io.github.mihealthamapfix.HookConstants.TARGET_PACKAGES;

/**
 * Modern LSPosed entry (libxposed API 101).
 *
 * API 101 uses a no-arg constructor and interceptor-chain hooking.
 * Actual hooking falls back to legacy XposedBridge/XposedHelpers API for maximum compatibility
 * with both API 100 and 101 frameworks.
 */
public class ModernEntry extends XposedModule {

    /** API 101: no-arg constructor. */
    public ModernEntry() {
        super();
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        String pkg = param.getPackageName();
        if (!TARGET_PACKAGES.contains(pkg)) return;

        ClassLoader cl;
        try {
            // API 101: getDefaultClassLoader() replaces getClassLoader()
            cl = param.getDefaultClassLoader();
        } catch (Throwable e) {
            // If getDefaultClassLoader() does not exist (shouldn't happen on 101),
            // try reflection fallback to getClassLoader() for safety
            try {
                cl = (ClassLoader) param.getClass()
                        .getMethod("getClassLoader")
                        .invoke(param);
            } catch (Throwable e2) {
                log(android.util.Log.WARN, "MiHealthAmapFix",
                        "Cannot get classloader: " + e2);
                return;
            }
        }

        hookNotificationFilter(cl);

        // DND sync fix for Android 15+ (SDK 35)
        DndHook.install(cl);
    }

    /**
     * Hook NotificationFilterHelper.isMipmapNotification using legacy XposedBridge API.
     * This provides maximum compatibility across framework versions.
     */
    private void hookNotificationFilter(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.xiaomi.fitness.notify.util.NotificationFilterHelper",
                    cl,
                    "isMipmapNotification",
                    StatusBarNotification.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Object arg0 = param.args != null && param.args.length > 0 ? param.args[0] : null;
                            if (arg0 instanceof StatusBarNotification) {
                                StatusBarNotification sbn = (StatusBarNotification) arg0;
                                if (AMAP_PACKAGE.equals(sbn.getPackageName())
                                        && sbn.getId() == AMAP_NAV_ID) {
                                    param.setResult(false);
                                }
                            }
                        }
                    }
            );
            log(android.util.Log.INFO, "MiHealthAmapFix",
                    "Hooked isMipmapNotification in " + cl);
        } catch (Throwable t) {
            log(android.util.Log.WARN, "MiHealthAmapFix",
                    "isMipmapNotification not found; maybe old version. " + t);
        }
    }
}
