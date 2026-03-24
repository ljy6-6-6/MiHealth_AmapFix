package io.github.mihealthamapfix.dnd;

/**
 * Shizuku UserService AIDL.
 *
 * Runs in Shizuku's privileged process and executes simple DND (zen_mode) operations.
 */
interface IShizukuDndService {
    /** Special transaction code reserved by Shizuku to stop the user service process. */
    void destroy() = 16777114;

    void setZenMode(int mode) = 1;
    int getZenMode() = 2;

    boolean ping() = 3;
    String getStatus() = 4;
}
