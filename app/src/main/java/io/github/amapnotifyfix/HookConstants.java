package io.github.amapnotifyfix;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Common constants used by both legacy and modern entry points.
 */
public final class HookConstants {
    private HookConstants() {}

    /** Packages of Xiaomi's fitness apps that should be hooked. */
    public static final Set<String> TARGET_PACKAGES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "com.xiaomi.wearable",
                    "com.mi.health",
                    "com.xiaomi.hm.health")));

    /** Package name of Amap (Gaode Map). */
    public static final String AMAP_PACKAGE = "com.autonavi.minimap";

    /** Notification id used by Amap's persistent navigation notification. */
    public static final int AMAP_NAV_ID = 0x4d4;
}
