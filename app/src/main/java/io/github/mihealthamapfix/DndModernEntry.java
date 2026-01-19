package io.github.mihealthamapfix;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

import static io.github.mihealthamapfix.HookConstants.TARGET_PACKAGES;

public class DndModernEntry extends XposedModule {
    public DndModernEntry(XposedInterface base, XposedModuleInterface.ModuleLoadedParam param) {
        super(base, param);
    }

    @Override public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        String pkg = param.getPackageName();
        if (!TARGET_PACKAGES.contains(pkg)) return;
        DndHook.install(param.getClassLoader());
    }
}
