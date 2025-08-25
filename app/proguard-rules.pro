# Keep Xposed entry points and hooker signatures
-keep class io.github.amapnotifyfix.** { *; }
-keep class ** implements io.github.libxposed.api.XposedInterface$Hooker {
  public static *** before(...);
  public static void after(...);
  public static void after(..., ***);
}
