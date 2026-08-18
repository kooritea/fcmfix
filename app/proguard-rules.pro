# R8 / minify for personal A16 build (minSdk 36).
# Keep apk small without breaking Xposed discovery or Material theming.

# libxposed modern API (API 102+)
-dontwarn io.github.libxposed.**
-adaptresourcefilecontents META-INF/xposed/java_init.list

# Keep the Xposed entry point; the framework instantiates it via no-arg constructor.
-keep,allowoptimization public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}
-keep class sumicya.fcmself.XposedMain { *;
}
-keep class sumicya.fcmself.MainActivity { *;
}
-keep class sumicya.fcmself.BootCompletedReceiver { *;
}

# Preserve annotations used by framework discovery.
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,AnnotationDefault

# Keep enum values used by reflection (used by FilterMode etc).
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep native methods if used by hooks.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# Strip verbose logging in release (app process); system_server hook logs via
# XposedBridge.log are kept so diagnostics remain on device.
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
}

# Allow R8 to fully optimize; keep Kotlin metadata only if present.
-dontwarn kotlin.**
