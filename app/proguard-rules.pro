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

# commons-compress (dézippage de Python par youtubedl-android) crée les champs « extra » ZIP par réflexion :
# sans leur constructeur vide, l'initialisation de youtube-dl échoue en release.
-keep class * implements org.apache.commons.compress.archivers.zip.ZipExtraField { <init>(); }
