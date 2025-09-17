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

-keepattributes *Annotation*,InnerClasses,EnclosingMethod,Signature,RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations
-keep class io.github.mihealthamapfix.compat.** { *; }
-keep class de.robv.android.xposed.** { *; }
-dontwarn de.robv.android.xposed.**
-keep class kotlin.** { *; }
-keepclassmembers class ** { @kotlin.Metadata *; }
