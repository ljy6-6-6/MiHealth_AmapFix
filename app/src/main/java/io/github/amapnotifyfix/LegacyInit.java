package io.github.amapnotifyfix;

import android.service.notification.StatusBarNotification;

import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import static io.github.amapnotifyfix.HookConstants.AMAP_NAV_ID;
import static io.github.amapnotifyfix.HookConstants.AMAP_PACKAGE;
import static io.github.amapnotifyfix.HookConstants.TARGET_PACKAGES;

/**
 * Legacy Xposed entry (works in LSPosed too).
 * Same logic as modern entry, but using XposedBridge API.
 */
public class LegacyInit implements IXposedHookLoadPackage {

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        String pkg = lpparam.packageName;
        Set<String> targets = TARGET_PACKAGES;
        if (!targets.contains(pkg)) {
            return;
        }
        try {
            XposedHelpers.findAndHookMethod(
                    "com.xiaomi.fitness.notify.util.NotificationFilterHelper",
                    lpparam.classLoader,
                    "isMipmapNotification",
                    StatusBarNotification.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            StatusBarNotification sbn = (StatusBarNotification) param.args[0];
                            if (sbn != null
                                    && AMAP_PACKAGE.equals(sbn.getPackageName())
                                    && sbn.getId() == AMAP_NAV_ID) {
                                param.setResult(false);
                            }
                        }
                    }
            );
        } catch (Throwable t) {
            // Method may not exist on older versions; ignore
        }
    }
}
