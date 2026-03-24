package io.github.libxposed.api;

/**
 * Compile-time shim for the modern libxposed interface.
 * <p>
 * The runtime framework provides the real implementation. This local stub keeps
 * only the members needed by the module while remaining source-compatible with
 * API 101 and the API 100 constructor path used by current LSPosed builds.
 */
@SuppressWarnings("unused")
public interface XposedInterface {
    default int getApiVersion() {
        return 0;
    }

    default String getFrameworkName() {
        return "";
    }

    default String getFrameworkVersion() {
        return "";
    }

    default long getFrameworkVersionCode() {
        return 0L;
    }

    default void log(int priority, String tag, String msg) {}

    default void log(int priority, String tag, String msg, Throwable tr) {}
}
