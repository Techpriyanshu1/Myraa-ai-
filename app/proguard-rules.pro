# MYRAA AI ProGuard / R8 Rules
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Room Database
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# OkHttp & Kotlin Coroutines
-dontwarn okhttp3.**
-dontwarn okio.**
-keepclassmembers class kotlinx.coroutines.internal.** { *; }

# Shizuku API classes
-dontwarn moe.shizuku.**
-keep class com.example.shizuku.** { *; }
