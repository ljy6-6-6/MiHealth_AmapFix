package io.github.mihealthamapfix;

import android.service.notification.StatusBarNotification;

import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import static io.github.mihealthamapfix.HookConstants.AMAP_NAV_ID;
import static io.github.mihealthamapfix.HookConstants.AMAP_PACKAGE;
import static io.github.mihealthamapfix.HookConstants.TARGET_PACKAGES;
import static io.github.mihealthamapfix.util.L.s;

/**
 * Legacy Xposed entry (works in LSPosed too).
 * Serves as fallback for frameworks that don't support the modern libxposed API.
 */
public class LegacyInit implements IXposedHookLoadPackage {

    private static final String TAG = "AmapFix";

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
            XposedBridge.log(TAG + ": " + s(
                    "已 Hook isMipmapNotification (Legacy入口)",
                    "Hooked isMipmapNotification (Legacy entry)"));
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": " + s(
                    "isMipmapNotification 未找到，可能是旧版 APP: ",
                    "isMipmapNotification not found, maybe old app version: ") + t);
        }

        // DND sync fix for Android 15+ (SDK 35)
        DndHook.install(lpparam.classLoader);
    }
}
