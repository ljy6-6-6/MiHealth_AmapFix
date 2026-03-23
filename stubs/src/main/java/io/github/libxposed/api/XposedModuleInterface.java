package io.github.libxposed.api;

import androidx.annotation.NonNull;

/**
 * Compile-time stub for XposedModuleInterface.
 * At runtime this class is provided by the Xposed framework.
 */
@SuppressWarnings("unused")
public interface XposedModuleInterface {

    interface ModuleLoadedParam {
        boolean isSystemServer();
        @NonNull String getProcessName();
    }

    interface PackageLoadedParam {
        @NonNull String getPackageName();

        /** API 100: classloader accessor. */
        @NonNull ClassLoader getClassLoader();

        /** API 101: renamed classloader accessor. */
        @NonNull ClassLoader getDefaultClassLoader();
    }

    /** Called when the module is loaded (API 101). */
    default void onModuleLoaded(@NonNull ModuleLoadedParam param) {}

    /** Called when a package is loaded. */
    default void onPackageLoaded(@NonNull PackageLoadedParam param) {}
}
