package io.github.mihealthamapfix;

import android.service.notification.StatusBarNotification;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.XC_MethodHook;

import static io.github.mihealthamapfix.HookConstants.AMAP_NAV_ID;
import static io.github.mihealthamapfix.HookConstants.AMAP_PACKAGE;
import static io.github.mihealthamapfix.HookConstants.TARGET_PACKAGES;
import static io.github.mihealthamapfix.util.L.s;

/**
 * Modern LSPosed entry — works on both API 100 and API 101 frameworks.
 * <p>
 * API 100: framework calls the 2-arg constructor (XposedInterface, ModuleLoadedParam).
 * API 101: framework calls the no-arg constructor, then attachFramework().
 * <p>
 * Actual hooking uses legacy XposedBridge/XposedHelpers API for maximum compatibility.
 */
public class ModernEntry extends XposedModule {

    private static final String TAG = "AmapFix";

    /** API 101: no-arg constructor. */
    public ModernEntry() {
        super();
    }

    /** API 100: 2-arg constructor. */
    public ModernEntry(XposedInterface base, XposedModuleInterface.ModuleLoadedParam param) {
        super(base, param);
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        String pkg = param.getPackageName();
        if (!TARGET_PACKAGES.contains(pkg)) return;

        ClassLoader cl = getClassLoaderCompat(param);
        if (cl == null) {
            XposedBridge.log(TAG + ": " + s("无法获取 ClassLoader", "Cannot get ClassLoader"));
            return;
        }

        hookNotificationFilter(cl);
        DndHook.install(cl);
    }

    /**
     * Compatible classloader accessor.
     * API 100 has getClassLoader(), API 101 has getDefaultClassLoader().
     */
    private static ClassLoader getClassLoaderCompat(XposedModuleInterface.PackageLoadedParam param) {
        // Try API 101 first
        try {
            return param.getDefaultClassLoader();
        } catch (Throwable ignored) {}
        // Fallback to API 100
        try {
            return param.getClassLoader();
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * Hook NotificationFilterHelper.isMipmapNotification using legacy XposedBridge API.
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
            XposedBridge.log(TAG + ": " + s(
                    "已 Hook isMipmapNotification (Modern入口)",
                    "Hooked isMipmapNotification (Modern entry)"));
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": " + s(
                    "isMipmapNotification 未找到，可能是旧版 APP: ",
                    "isMipmapNotification not found, maybe old app version: ") + t);
        }
    }
}
