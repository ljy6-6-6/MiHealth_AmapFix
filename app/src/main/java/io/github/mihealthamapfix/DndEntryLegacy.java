package io.github.mihealthamapfix;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import static io.github.mihealthamapfix.HookConstants.TARGET_PACKAGES;

/** Additional legacy entry to install DND hook. */
public class DndEntryLegacy implements IXposedHookLoadPackage {
    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!TARGET_PACKAGES.contains(lpparam.packageName)) return;
        DndHook.install(lpparam.classLoader);
    }
}
