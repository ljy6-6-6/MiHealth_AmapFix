package io.github.mihealthamapfix;

/** Minimal binder interface for controlling DND from a privileged side. */
interface IDndBridgeService {
    /** Set zen mode: 0=OFF, 1=PRIORITY, 2=NONE, 3=ALARMS */
    void setZenMode(int mode);

    /** Query current zen mode: returns 0..3, or -1 on error. */
    int getZenMode();

    /** Whether privileged engine is active (su or Shizuku). */
    boolean isActive();

    /** Human readable last status for logging / diagnostics. */
    String getStatus();
}
