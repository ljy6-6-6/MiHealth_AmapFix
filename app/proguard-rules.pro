-adaptresourcefilecontents META-INF/xposed/java_init.list

# LSPosed must be able to resolve both modern and legacy entries by class name.
-keep class io.github.mihealthamapfix.ModernEntry {
    public <init>();
    public <init>(...);
    public void onModuleLoaded(...);
    public void onPackageLoaded(...);
    public void onPackageReady(...);
}
-keep class io.github.mihealthamapfix.LegacyInit { *; }
-keep class io.github.mihealthamapfix.DndHook { *; }

# Runtime libxposed implementations come from the framework, not from this APK.
-dontwarn io.github.libxposed.api.**

# Shizuku user service
-keep class io.github.mihealthamapfix.dnd.ShizukuDndUserService { *; }
-keep class io.github.mihealthamapfix.dnd.IShizukuDndService** { *; }

# Bridge AIDL
-keep class io.github.mihealthamapfix.IDndBridgeService** { *; }

# Shizuku API classes
-keep class rikka.shizuku.** { *; }
