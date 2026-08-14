# Add project specific ProGuard rules here.
# R8 configuration for release builds.

# libxposed modern API (API 102+)
-dontwarn io.github.libxposed.**
-adaptresourcefilecontents META-INF/xposed/java_init.list

# Preserve Xposed module entry classes.
# Keep constructors required by the framework while allowing optimization.
-keep,allowoptimization public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}

# Preserve annotations used by framework discovery.
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,AnnotationDefault

# Preserve generic signatures and inner class metadata.
-keepattributes Signature,InnerClasses,EnclosingMethod

# Keep native methods if used by hooks.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# Keep Android components referenced by Manifest.
-keep class * extends android.app.Activity { *; }
-keep class * extends android.content.BroadcastReceiver { *; }
-keep class * extends android.app.Service { *; }

# Keep enum values used by reflection.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
