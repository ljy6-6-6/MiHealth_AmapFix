package io.github.mihealthamapfix;

import android.service.notification.StatusBarNotification;

import java.lang.reflect.Method;
import java.util.Set;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

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
            hook(m, IsMipmapHooker.class);
            log("MiHealthAmapFix: hooked isMipmapNotification in " + pkg);
        } catch (Throwable t) {
            log("MiHealthAmapFix: method not found; maybe old version. " + t);
        }
    }

    /** Hooker for the modern API. */
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
