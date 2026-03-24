package io.github.libxposed.api;

/**
 * Compile-time shim for XposedModule.
 * <p>
 * API 101 uses the no-arg constructor plus attachFramework(), while API 100
 * still instantiates modules through the two-argument constructor. Keeping both
 * here lets one source tree compile a universal module APK.
 */
@SuppressWarnings("unused")
public abstract class XposedModule extends XposedInterfaceWrapper implements XposedModuleInterface {

    public XposedModule() {}

    public XposedModule(XposedInterface base, XposedModuleInterface.ModuleLoadedParam param) {
        attachFramework(base);
    }
}
