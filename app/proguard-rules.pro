# Bouncy Castle — required for Ed25519 on API 24-32
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# Room — keep entity and DAO classes
-keep class com.noslop.app.data.** { *; }

# OkHttp + Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# Keep source file line numbers for crash debugging
-keepattributes SourceFile,LineNumberTable

# tor-android + jtorctl + netcipher — required for embedded Tor daemon
-keep class net.freehaven.tor.control.** { *; }
-keep class org.torproject.android.** { *; }
-keep class org.torproject.jni.** { *; }
-keep class info.guardianproject.** { *; }
-keep class info.guardianproject.netcipher.** { *; }
-dontwarn net.freehaven.tor.control.**
-dontwarn org.torproject.android.**
-dontwarn info.guardianproject.**
