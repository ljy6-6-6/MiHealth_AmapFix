package io.github.mihealthamapfix;

import android.service.notification.StatusBarNotification;

import java.lang.reflect.Method;
import java.util.Set;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.XC_MethodHook;


import io.github.libxposed.api.annotations.XposedHooker;
import static io.github.mihealthamapfix.HookConstants.AMAP_NAV_ID;
import static io.github.mihealthamapfix.HookConstants.AMAP_PACKAGE;
import static io.github.mihealthamapfix.HookConstants.TARGET_PACKAGES;

/**
 * Modern LSPosed (libxposed API 100) entry.
 * Hooks NotificationFilterHelper#isMipmapNotification(StatusBarNotification)
 * and forces it to return false for Amap's navigation notification only.
 */
public class ModernEntry extends XposedModule {

    public ModernEntry(XposedInterface base, XposedModuleInterface.ModuleLoadedParam param) {
        super(base, param);
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        String pkg = param.getPackageName();
        // 仅在小米运动健康相关包内工作
        Set<String> targets = TARGET_PACKAGES;
        if (!targets.contains(pkg)) {
            return;
        }
        try {
            ClassLoader cl = param.getClassLoader();
            Class<?> helper = cl.loadClass("com.xiaomi.fitness.notify.util.NotificationFilterHelper");
            Method m = helper.getDeclaredMethod("isMipmapNotification", StatusBarNotification.class);
try {
    // 优先使用新版 libxposed Hook（官方 API 100）
    hook(m, IsMipmapHooker.class);
} catch (IllegalArgumentException e) {
    String _msg = e.getMessage() == null ? "" : e.getMessage();
    if (_msg.contains("Hooker should be annotated with @XposedHooker")) {
        // 兼容：第三方旧实现仍然要求 @XposedHooker 注解，自动回退到旧版 XposedBridge Hook 方案
        XposedBridge.log("MiHealthAmapFix: fallback → Legacy (XC_MethodHook) due to: " + _msg);
        try {
            XposedHelpers.findAndHookMethod(
                    "com.xiaomi.fitness.notify.util.NotificationFilterHelper",
                    cl,
                    "isMipmapNotification",
                    android.service.notification.StatusBarNotification.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Object arg0 = param.args != null && param.args.length > 0 ? param.args[0] : null;
                            if (arg0 instanceof android.service.notification.StatusBarNotification) {
                                android.service.notification.StatusBarNotification sbn =
                                        (android.service.notification.StatusBarNotification) arg0;
                                if (sbn != null
                                        && "com.autonavi.minimap".equals(sbn.getPackageName())
                                        && sbn.getId() == 0x4d4 /* AMAP_NAV_ID */) {
                                    param.setResult(false);
                                }
                            }
                        }
                    }
            );
        } catch (Throwable t2) {
            XposedBridge.log("MiHealthAmapFix: Legacy fallback failed: " + t2);
        }
    } else {
        throw e;
    }
}

            log("MiHealthAmapFix: hooked isMipmapNotification in " + pkg);
        } catch (Throwable t) {
            log("MiHealthAmapFix: method not found; maybe old version. " + t);
        }
    }

    /** Hooker for the modern API. */
    @XposedHooker
        public static class IsMipmapHooker implements XposedInterface.Hooker {

        // libxposed: 在 before 回调里若要短路并返回，使用 returnAndSkip(result)
        public static void before(XposedInterface.BeforeHookCallback callback) {
            try {
                Object[] args = callback.getArgs();
                if (args != null && args.length > 0 && args[0] instanceof StatusBarNotification) {
                    StatusBarNotification sbn = (StatusBarNotification) args[0];
                    // 仅针对高德导航这条常驻通知 (id = 0x4d4) 关闭过滤
                    if (AMAP_PACKAGE.equals(sbn.getPackageName())
                            && sbn.getId() == AMAP_NAV_ID) {
                        // 让 isMipmapNotification 直接返回 false，并跳过原方法
                        callback.returnAndSkip(false);
                    }
                }
            } catch (Throwable t) {
                // 出错不强行改结果，避免误伤其它通知
            }
        }

        // 这里不需要 after；如果之后想兜底再翻转，也可以在 after 判断再 setResult(false)
        public static void after(XposedInterface.AfterHookCallback callback) {
            // no-op
        }
    }
}
