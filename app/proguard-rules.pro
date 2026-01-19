# 让 LSPosed 能通过类名加载到入口与 Hooker（名字不能被混淆/删除）
-keep class io.github.mihealthamapfix.ModernEntry { *; }
-keep class io.github.mihealthamapfix.ModernEntry$IsMipmapHooker { *; }
-keep class io.github.mihealthamapfix.LegacyInit { *; }

# 保留 libxposed Hooker 的静态回调方法签名（before/after）
-keepclassmembers class * implements io.github.libxposed.api.XposedInterface$Hooker {
    public static void before(...);
    public static void after(...);
}

# runtime 由 LSPosed 提供 libxposed，实现不存在于 APK 内，避免警告（可选）
-dontwarn io.github.libxposed.api.**

# 保留兼容性占位注解，避免被移除（可选）
-keep @interface io.github.libxposed.api.annotations.XposedHooker

# ----------------------------
# Shizuku UserService
# ----------------------------
# Shizuku binds user services by class name. Keep it stable in release builds.
-keep class io.github.mihealthamapfix.dnd.ShizukuDndUserService { *; }
-keep class io.github.mihealthamapfix.dnd.IShizukuDndService** { *; }

# Keep bridge AIDL (used跨进程 Binder)
-keep class io.github.mihealthamapfix.IDndBridgeService** { *; }

# Keep Shizuku API classes to avoid aggressive shrinking causing bind issues
-keep class rikka.shizuku.** { *; }
