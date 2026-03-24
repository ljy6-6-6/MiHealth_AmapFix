package io.github.mihealthamapfix.dnd;

public enum DndMode {
    NONE(0),
    ROOT(1),
    SHIZUKU(2);

    private final int code;

    DndMode(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public boolean canUsePrivilegedDnd() {
        return this != NONE;
    }

    public String routeLabel() {
        switch (this) {
            case ROOT:
                return "ROOT";
            case SHIZUKU:
                return "SHIZUKU";
            default:
                return "NONE";
        }
    }

    public static DndMode fromCode(int code) {
        switch (code) {
            case 1:
                return ROOT;
            case 2:
                return SHIZUKU;
            default:
                return NONE;
        }
    }
}
