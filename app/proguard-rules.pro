# Bouncy Castle — required for Ed25519 signing across all API levels
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# Lazysodium + JNA — required for Ed25519 key generation
-keep class com.goterl.lazysodium.** { *; }
-keep class com.sun.jna.** { *; }
-dontwarn com.goterl.lazysodium.**
-dontwarn com.sun.jna.**

# Room — granular entity and DAO keep rules
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-keep class com.noslop.app.data.ReactionDao$ReactionCount { *; }
-dontwarn androidx.room.paging.**

# OkHttp + Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# Keep source file line numbers and generic signatures for Gson TypeToken reflection
-keepattributes SourceFile,LineNumberTable,Signature,InnerClasses,EnclosingMethod,*Annotation*

# tor-android + jtorctl + netcipher — required for embedded Tor daemon
-keep class net.freehaven.tor.control.** { *; }
-keep class org.torproject.android.** { *; }
-keep class org.torproject.jni.** { *; }
-keep class info.guardianproject.** { *; }
-keep class info.guardianproject.netcipher.** { *; }
-dontwarn net.freehaven.tor.control.**
-dontwarn org.torproject.android.**
-dontwarn info.guardianproject.**

# ---------------------------------------------------------
# RELEASE BUILD RULES & GRANULAR MODEL RETENTION
# ---------------------------------------------------------

# 1. AndroidX Security / Google Tink
-keep class androidx.security.crypto.** { *; }
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# 2. Gson Core & SerializedName reflection preservation
-keep class com.google.gson.** { *; }
-keep class sun.misc.Unsafe { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# 3. Explicit @Keep annotations (DTOs, entities, and public serialization models)
-keep @androidx.annotation.Keep class * { *; }
-keepclassmembers @androidx.annotation.Keep class * { *; }
-keepclassmembers enum * { *; }

# 4. Mesh Network Packets & Wire Payloads
-keep class com.noslop.app.mesh.NetworkPacket { *; }
-keep class com.noslop.app.mesh.*Payload { *; }
-keep class com.noslop.app.mesh.*Data { *; }
-keep class com.noslop.app.mesh.MediaMetadata { *; }
-keep class com.noslop.app.mesh.InventoryItem { *; }

# 5. Data & Settings Models (Serialized to Room app_settings as JSON)
-keep class com.noslop.app.data.UserProfile { *; }
-keep class com.noslop.app.data.MediaSettings { *; }
-keep class com.noslop.app.data.MeshFilterSettings { *; }
-keep class com.noslop.app.data.FeedMixSettings { *; }
-keep class com.noslop.app.data.NotificationSettings { *; }
-keep class com.noslop.app.data.BackupMediaOption { *; }

# 6. QR Scanning Models
-keep class com.noslop.app.ui.QRScannedPeer { *; }

# 7. JSch SSH Deployment & Algorithms
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**