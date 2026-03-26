package io.github.libxposed.api;

import androidx.annotation.NonNull;

/**
 * Compile-time shim for the modern module lifecycle interface.
 */
public interface XposedModuleInterface {

    interface ModuleLoadedParam {
        boolean isSystemServer();

        @NonNull String getProcessName();
    }

    interface PackageLoadedParam {
        @NonNull String getPackageName();

        @NonNull Object getApplicationInfo();

        boolean isFirstPackage();

        /**
         * API 101 default class loader. Some API 100 runtimes expose it too.
         */
        @NonNull ClassLoader getDefaultClassLoader();

        /**
         * API 100 package-ready class loader accessor. Some hybrid runtimes also
         * surface it on the package-loaded callback object.
         */
        @NonNull ClassLoader getClassLoader();
    }

    interface PackageReadyParam extends PackageLoadedParam {
        @NonNull Object getAppComponentFactory();
    }

    interface SystemServerStartingParam {
        @NonNull ClassLoader getClassLoader();
    }

    default void onModuleLoaded(@NonNull ModuleLoadedParam param) {}

    default void onPackageLoaded(@NonNull PackageLoadedParam param) {}

    default void onPackageReady(@NonNull PackageReadyParam param) {}

    default void onSystemServerStarting(@NonNull SystemServerStartingParam param) {}
}
