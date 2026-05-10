## ProGuard rules for LLMApp
-keep class com.llmapp.jni.NativeLib { *; }
-keep class com.llmapp.jni.InferenceCallback { *; }
-keepclassmembers class com.llmapp.jni.InferenceCallback { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Markwon
-keep class io.noties.markwon.** { *; }
-keep class org.commonmark.** { *; }

# Gson (if used)
-keep class com.google.gson.** { *; }

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}
