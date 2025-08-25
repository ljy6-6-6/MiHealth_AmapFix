package io.github.amapnotifyfix;

import android.service.notification.StatusBarNotification;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import de.robv.android.xposed.XposedHelpers;

/**
 * Legacy Xposed entry (works in LSPosed too).
 * Same logic as modern entry, but using XposedBridge API.
 */
public class LegacyInit implements IXposedHookLoadPackage {

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        String pkg = lpparam.packageName;
        if (!"com.xiaomi.wearable".equals(pkg)
                && !"com.mi.health".equals(pkg)
                && !"com.xiaomi.hm.health".equals(pkg)) {
            return;
        }
        try {
            XposedHelpers.findAndHookMethod(
                    "com.xiaomi.fitness.notify.util.NotificationFilterHelper",
                    lpparam.classLoader,
                    "isMipmapNotification",
                    StatusBarNotification.class,
                    new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) {
                            StatusBarNotification sbn = (StatusBarNotification) param.args[0];
                            if (sbn != null && "com.autonavi.minimap".equals(sbn.getPackageName()) && sbn.getId() == 0x4d4) {
                                return false;
                            }
                            // Fallback: also false
                            return false;
                        }
                    }
            );
        } catch (Throwable t) {
            // Method may not exist on older versions; ignore
        }
    }
}
