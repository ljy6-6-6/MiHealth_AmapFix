package io.github.mihealthamapfix;

/**
 * AIDL bridge used between the hooked app process and this module's isolated bridge process.
 */
interface IDndBridgeService {
    void setZenMode(int mode);
    int getZenMode();
    int getCapabilityMode();
    boolean isActive();
    String getStatus();
}
