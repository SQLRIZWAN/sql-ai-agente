# SQL AI AGENTE - R8 / ProGuard rules
-keep class com.sqlai.agente.nativebridge.** { *; }
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
