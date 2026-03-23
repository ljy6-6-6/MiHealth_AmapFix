# 让 LSPosed 能通过类名加载到入口（名字不能被混淆/删除）
-keep class io.github.mihealthamapfix.ModernEntry { *; }
-keep class io.github.mihealthamapfix.LegacyInit { *; }
-keep class io.github.mihealthamapfix.DndHook { *; }

# runtime 由 LSPosed 提供 libxposed，实现不存在于 APK 内，避免警告
-dontwarn io.github.libxposed.api.**

# ----------------------------
# Shizuku UserService
# ----------------------------
-keep class io.github.mihealthamapfix.dnd.ShizukuDndUserService { *; }
-keep class io.github.mihealthamapfix.dnd.IShizukuDndService** { *; }

# Keep bridge AIDL (跨进程 Binder)
-keep class io.github.mihealthamapfix.IDndBridgeService** { *; }

# Keep Shizuku API classes
-keep class rikka.shizuku.** { *; }
