package io.github.mihealthamapfix.dnd;

public interface DndEngine {
    /** Return true if engine is ready (permission OK, backend available). */
    boolean isActive();

    /** Human readable status (engine name & last error). */
    String getStatus();

    /** Set zen mode: 0=OFF,1=PRIORITY,2=NONE,3=ALARMS; throws if fails. */
    void setZen(int zen) throws Exception;

    /** Get current zen mode (0..3), or -1 on error. */
    int getZen();
}
