package io.github.mihealthamapfix.dnd;

import android.app.NotificationManager;

/** Mapping helpers and constants for DND. */
public final class DndUtils {
    private DndUtils() {}

    /** Map NotificationManager interruption filter to zen_mode int. */
    public static int filterToZen(int filter) {
        switch (filter) {
            case NotificationManager.INTERRUPTION_FILTER_ALL: return 0; // off
            case NotificationManager.INTERRUPTION_FILTER_PRIORITY: return 1;
            case NotificationManager.INTERRUPTION_FILTER_NONE: return 2;
            case NotificationManager.INTERRUPTION_FILTER_ALARMS: return 3;
            default: return -1;
        }
    }

    /** Map zen int to cmd string for 'cmd notification set_zen_mode'. */
    public static String zenToCmd(int zen) {
        switch (zen) {
            case 0: return "off";
            case 1: return "priority";
            case 2: return "none";
            case 3: return "alarms";
            default: return "off";
        }
    }

    /** Map zen int to NotificationManager interruption filter. */
    public static int zenToFilter(int zen) {
        switch (zen) {
            case 0: return NotificationManager.INTERRUPTION_FILTER_ALL;
            case 1: return NotificationManager.INTERRUPTION_FILTER_PRIORITY;
            case 2: return NotificationManager.INTERRUPTION_FILTER_NONE;
            case 3: return NotificationManager.INTERRUPTION_FILTER_ALARMS;
            default: return -1;
        }
    }
}
