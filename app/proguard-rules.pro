# 让 LSPosed 能通过类名加载到入口与 Hooker（名字不能被混淆/删除）
-keep class io.github.mihealthamapfix.ModernEntry { *; }
-keep class io.github.mihealthamapfix.LegacyInit { *; }
-keep class io.github.mihealthamapfix.DndHook { *; }

# 保留 libxposed API 101 基类
-keep class io.github.libxposed.api.** { *; }

# runtime 由 LSPosed 提供 libxposed，实现不存在于 APK 内，避免警告
-dontwarn io.github.libxposed.api.**

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
