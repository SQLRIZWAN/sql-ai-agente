# SQL AI AGENTE - R8 / ProGuard rules

# Keep the whole app package: JNI callbacks (C++ resolves class/method names by
# string), WorkManager workers (instantiated reflectively from the DB) and
# Compose entry points must never be renamed or stripped.
-keep class com.sqlai.agente.** { *; }
-keepclassmembers class com.sqlai.agente.** { native <methods>; }
-keep class com.sqlai.agente.ui.nativebridge.NativeEngine$TokenListener { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# SQLCipher
-keep class net.zetetic.** { *; }
-dontwarn net.zetetic.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keepattributes Signature
-keepattributes *Annotation*

# Kotlin coroutines
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# ML Kit
-keep class com.google.mlkit.** { *; }

# Keep data models used in JSON parsing
-keepclassmembers class com.sqlai.agente.data.model.** { *; }
-keep class com.sqlai.agente.data.model.** { *; }
