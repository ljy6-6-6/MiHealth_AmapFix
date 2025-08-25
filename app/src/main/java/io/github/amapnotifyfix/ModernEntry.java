package io.github.amapnotifyfix;

import android.service.notification.StatusBarNotification;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

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

        // libxposed: 在 before 回调里若要短路并返回，使用 returnAndSkip(result)
        public static void before(XposedInterface.BeforeHookCallback callback) {
            try {
                Object[] args = callback.getArgs();
                if (args != null && args.length > 0 && args[0] instanceof StatusBarNotification) {
                    StatusBarNotification sbn = (StatusBarNotification) args[0];
                    // 仅针对高德导航这条常驻通知 (id = 0x4d4) 关闭过滤
                    if ("com.autonavi.minimap".equals(sbn.getPackageName())
                            && sbn.getId() == 0x4d4) {
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
