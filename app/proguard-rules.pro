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

# ---------------------------------------------------------
# NEW FIXES FOR RELEASE BUILD
# ---------------------------------------------------------

# 1. AndroidX Security / Google Tink (Fixes Hardware Encryption Warning)
-keep class androidx.security.crypto.** { *; }
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# 2. Gson Core (Required for reflection-based deserialization)
-keep class com.google.gson.** { *; }
-keep class sun.misc.Unsafe { *; }

# 3. Mesh Network Packets (Fixes dropped packets & failed handshakes)
# Keeps all fields/names in Packets.kt so Gson can map the JSON keys exactly
-keep class com.noslop.app.mesh.** { *; }

# 4. QR Scanning Data Model (Fixes the QR Scanner silently failing)
-keep class com.noslop.app.ui.QRScannedPeer { *; }

# 5. Backup Manager Data Model (In case you use Gson for backups too)
-keep class com.noslop.app.data.UserProfile { *; }

# 6. Update Checker Data Models (Fixes "abstract classes can't be instantiated"
#    Gson crash on release builds — these live in com.noslop.app.util, which
#    has no other keep rule, so R8 was free to strip their fields/constructors).
#    Wildcard covers UpdateInfo, ContentJson, HeroBlock, and the worker/checker
#    classes themselves so nothing in this package is touched by Gson reflection.
-keep class com.noslop.app.util.** { *; }