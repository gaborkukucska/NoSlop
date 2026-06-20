# Kotlin rules
-keep class kotlin.** { *; }
-keepclassmembers class kotlin.** { *; }
-keep class kotlinx.** { *; }
-keepclassmembers class kotlinx.** { *; }

# JNA / sqlite-jdbc
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { *; }
-keep class org.sqlite.** { *; }

# Ktor
-keep class io.ktor.** { *; }
-dontwarn java.lang.management.**

# SQLDelight
-keep class app.cash.sqldelight.** { *; }

# BouncyCastle
-keep class org.bouncycastle.** { *; }

# Compose / AndroidX
-keep class androidx.compose.** { *; }
-keep class androidx.activity.** { *; }
-keep class androidx.core.** { *; }
-keep class androidx.lifecycle.** { *; }
-keep class androidx.work.** { *; }
-keep class androidx.room.** { *; }

# Android UI
-keepclassmembers class **.R$* {
    public static <fields>;
}

# The application itself
-keep class com.noslop.mvp.** { *; }
