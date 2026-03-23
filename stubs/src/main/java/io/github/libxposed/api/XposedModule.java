package io.github.libxposed.api;

/**
 * Compile-time stub for XposedModule.
 * <p>
 * Provides BOTH the API 100 constructor (2-arg) and API 101 constructor (no-arg)
 * so that {@code ModernEntry} can compile a single class that works on both
 * framework versions. At runtime, the framework provides its own implementation;
 * only the matching constructor will be called.
 * </p>
 */
@SuppressWarnings("unused")
public abstract class XposedModule implements XposedModuleInterface {

    /** API 101: no-arg constructor. */
    public XposedModule() {}

    /** API 100: 2-arg constructor. */
    public XposedModule(XposedInterface base, XposedModuleInterface.ModuleLoadedParam param) {}

    /** Logging (API 100 style). */
    public void log(String msg) {}

    /** Logging (API 101 style). */
    public void log(int priority, String tag, String msg) {}
}
