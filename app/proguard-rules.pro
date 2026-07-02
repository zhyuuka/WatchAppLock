# WatchAppLock ProGuard rules

# Keep JsBridge (called from WebView via reflection)
-keepclassmembers class com.watchapplock.JsBridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.watchapplock.JsBridge { *; }

# Keep data classes used across boundaries
-keep class com.watchapplock.** { *; }

# Kotlin metadata
-dontwarn kotlin.**
-keep class kotlin.Metadata { *; }
