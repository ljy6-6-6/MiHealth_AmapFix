package io.github.mihealthamapfix.dnd;

public enum DndRoute {
    NONE,
    HOST_ROOT;

    public boolean canUsePrivilegedDnd() {
        return this != NONE;
    }

    public String routeLabel() {
        switch (this) {
            case HOST_ROOT:
                return "HOST_ROOT";
            default:
                return "NONE";
        }
    }
}
