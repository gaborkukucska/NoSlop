# Bouncy Castle — preserve JCA provider and crypto engine classes used by NoSlop
-keep class org.bouncycastle.jce.provider.BouncyCastleProvider { *; }
-keep class org.bouncycastle.jcajce.provider.asymmetric.edec.** { *; }
-keep class org.bouncycastle.crypto.** { *; }
-keep class org.bouncycastle.asn1.** { *; }
-dontwarn org.bouncycastle.**

# Room — granular entity and DAO keep rules
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-keep class com.noslop.app.data.ReactionDao$ReactionCount { *; }
-dontwarn androidx.room.paging.**

# OkHttp + Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# Keep source file line numbers and generic signatures for Gson TypeToken reflection
-keepattributes SourceFile,LineNumberTable,Signature,InnerClasses,EnclosingMethod,*Annotation*

# tor-android — embedded Tor daemon native wrapper
-keep class org.torproject.android.** { *; }
-keep class org.torproject.jni.** { *; }
-keep class info.guardianproject.torservices.** { *; }
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

# Mesh protocol packets and payloads
-keep class com.noslop.app.mesh.** { *; }
-keep class com.noslop.app.data.GroupChat { *; }
