# youtubedl-android JNI and Python
-keep class com.yausername.youtubedl_android.** { *; }
-keep class io.github.junkfood02.** { *; }
-keepclassmembers class * {
    native <methods>;
}

# Kotlin Serialization & Data Models
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-keep class com.spotywoop.kt.data.** { *; }
-keep class com.spotywoop.kt.playback.** { *; }

# OkHttp & Coroutines
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.codehaus.mojo.animal_sniffer.**
