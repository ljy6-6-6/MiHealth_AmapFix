package io.github.mihealthamapfix;

import android.service.notification.StatusBarNotification;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import static io.github.mihealthamapfix.HookConstants.AMAP_NAV_ID;
import static io.github.mihealthamapfix.HookConstants.AMAP_PACKAGE;
import static io.github.mihealthamapfix.HookConstants.TARGET_PACKAGES;
import static io.github.mihealthamapfix.util.L.s;

/**
 * Universal modern entry for API 100 and API 101 style runtimes.
 * <p>
 * Actual business hooks still use XposedBridge/XposedHelpers for maximum
 * compatibility with the already-working hook implementation.
 */
public class ModernEntry extends XposedModule {

    private static final String TAG = "AmapFix";
    private static final Set<String> INSTALLED = ConcurrentHashMap.newKeySet();

    private volatile int frameworkApiVersion = -1;
    private volatile String processName = "unknown";

    public ModernEntry() {
        super();
    }

    public ModernEntry(XposedInterface base, XposedModuleInterface.ModuleLoadedParam param) {
        super(base, param);
        rememberModuleLoaded(param);
    }

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        rememberModuleLoaded(param);
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        String pkg = param.getPackageName();
        if (!TARGET_PACKAGES.contains(pkg)) {
            return;
        }

        if (shouldWaitForPackageReady()) {
            XposedBridge.log(TAG + ": " + s(
                    "已命中 Modern onPackageLoaded，等待 onPackageReady 再安装 Hook，包名=",
                    "Modern onPackageLoaded hit, waiting for onPackageReady before installing hooks, package=") + pkg);
            return;
        }

        installForPackage(pkg, getClassLoaderCompat(param), "onPackageLoaded");
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        String pkg = param.getPackageName();
        if (!TARGET_PACKAGES.contains(pkg)) {
            return;
        }

        installForPackage(pkg, getClassLoaderCompat(param), "onPackageReady");
    }

    private void rememberModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        if (param == null) {
            return;
        }

        processName = safeProcessName(param);
        frameworkApiVersion = safeApiVersion();

        XposedBridge.log(TAG + ": " + s(
                "Modern 入口已附加，进程=",
                "Modern entry attached, process=") + processName
                + ", framework=" + safeFrameworkName()
                + "(" + safeFrameworkVersionCode() + ")"
                + ", api=" + describeApiVersion());
    }

    private boolean shouldWaitForPackageReady() {
        return frameworkApiVersion >= 101;
    }

    private void installForPackage(String pkg, ClassLoader cl, String source) {
        if (cl == null) {
            XposedBridge.log(TAG + ": " + s(
                    "无法获取 ClassLoader，来源=",
                    "Cannot get ClassLoader, source=") + source + ", package=" + pkg);
            return;
        }

        String key = processName + "|" + pkg + "|" + System.identityHashCode(cl);
        if (!INSTALLED.add(key)) {
            XposedBridge.log(TAG + ": " + s(
                    "跳过重复安装，来源=",
                    "Skipping duplicate install, source=") + source + ", package=" + pkg);
            return;
        }

        hookNotificationFilter(cl);
        DndHook.install(cl);
        XposedBridge.log(TAG + ": " + s(
                "已通过 Modern 入口安装 Hook，来源=",
                "Installed hooks through modern entry, source=") + source + ", package=" + pkg);
    }

    private static ClassLoader getClassLoaderCompat(XposedModuleInterface.PackageLoadedParam param) {
        try {
            return param.getClassLoader();
        } catch (Throwable ignored) {}
        try {
            return param.getDefaultClassLoader();
        } catch (Throwable ignored) {}
        return null;
    }

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
                    "已 Hook isMipmapNotification (Modern 入口)",
                    "Hooked isMipmapNotification (Modern entry)"));
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": " + s(
                    "isMipmapNotification 未找到，可能是旧版 APP: ",
                    "isMipmapNotification not found, maybe old app version: ") + t);
        }
    }

    private int safeApiVersion() {
        try {
            return getApiVersion();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private String describeApiVersion() {
        return frameworkApiVersion > 0 ? String.valueOf(frameworkApiVersion) : "unknown";
    }

    private String safeFrameworkName() {
        try {
            String name = getFrameworkName();
            return name == null || name.isEmpty() ? "unknown" : name;
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private long safeFrameworkVersionCode() {
        try {
            return getFrameworkVersionCode();
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    private static String safeProcessName(XposedModuleInterface.ModuleLoadedParam param) {
        try {
            String name = param.getProcessName();
            return name == null || name.isEmpty() ? "unknown" : name;
        } catch (Throwable ignored) {
            return "unknown";
        }
    }
}
