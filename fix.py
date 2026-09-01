#!/usr/bin/env python3
"""
NoSlop surgical fix script 01 — code, build and repo hygiene.

Run from the root of the NoSlop project:

    python3 noslop_fix_01_code_and_build.py

Covers:
  1. IdentityRepository  — unlock() silent-lockout bug, burnable key plaintext
                           write, getBurnableIdentity read, clearAll docstring
  2. MeshTransport       — unbounded readLine() frame, raw packet in error log
  3. AndroidManifest     — remove the dead DownloadReceiver registration
  4. app/build.gradle.kts— make the release signingConfig conditional so a
                           clean clone builds; align okhttp; swap abandoned jsch
  5. libs.versions.toml  — okhttp 4.10.0 -> 4.12.0, drop unused bcprov entry
  6. .gitignore          — stop excluding *.sh / *.py / tests/, add tmp_logs/
  7. Delete test.kt and tmp_logs/

Every edit is checked before it is applied. Already-applied edits are reported
as SKIP, so the script is safe to run twice.
"""

import os
import shutil
import sys

APPLIED = []
SKIPPED = []
FAILED = []


def edit(path, old, new, label, marker=None):
    """marker: text unique to the post-edit state. Needed for pure insertions,
    where the anchor still matches after the edit has been applied."""
    if not os.path.exists(path):
        FAILED.append(f"{label}: file not found ({path})")
        return
    with open(path, "r", encoding="utf-8") as f:
        src = f.read()
    if marker is not None and marker in src:
        SKIPPED.append(f"{label}: already applied")
        return
    if marker is None and new in src and old not in src:
        SKIPPED.append(f"{label}: already applied")
        return
    count = src.count(old)
    if count == 0:
        FAILED.append(f"{label}: anchor text not found in {path}")
        return
    if count > 1:
        FAILED.append(f"{label}: anchor matched {count} times in {path}, refusing")
        return
    with open(path, "w", encoding="utf-8") as f:
        f.write(src.replace(old, new))
    APPLIED.append(label)


# ---------------------------------------------------------------------------
# 1. IdentityRepository
# ---------------------------------------------------------------------------

IDENT = "app/src/main/java/com/noslop/app/data/IdentityRepository.kt"

edit(
    IDENT,
    """    suspend fun unlock(mnemonic: String): Boolean {
        val savedMnemonic = prefs.getString("mnemonic", null)
        return if (savedMnemonic == mnemonic) {""",
    """    suspend fun unlock(mnemonic: String): Boolean {
        // NOSLOP_UNLOCK_FALLBACK_V1
        // The stored value is "ENC_GCM:<base64>" whenever EncryptedSharedPreferences
        // failed and the AES-GCM fallback store is in use. Reading it raw compared
        // ciphertext against the user's typed words, so unlock could never succeed
        // on exactly the devices already running degraded — a permanent lockout.
        val savedMnemonic = secureFallbackRead(prefs.getString("mnemonic", null))
        val normalised = mnemonic.trim().lowercase().split(Regex("\\\\s+")).joinToString(" ")
        val savedNormalised = savedMnemonic?.trim()?.lowercase()?.split(Regex("\\\\s+"))?.joinToString(" ")
        return if (savedNormalised != null && savedNormalised == normalised) {""",
    "IdentityRepository.unlock() reads through the fallback decryptor",
)

edit(
    IDENT,
    """        prefs.edit()
            .putString("burnable_ed25519_private_key", burnableIdentity.privateKeyB64)
            .putString("burnable_enc_private_key", burnableIdentity.encPrivateKeyB64)""",
    """        prefs.edit()
            // NOSLOP_BURNABLE_FALLBACK_V1 — these are private keys and must go
            // through the same fallback encryption as the main identity.
            .putString("burnable_ed25519_private_key", secureFallbackWrite(burnableIdentity.privateKeyB64))
            .putString("burnable_enc_private_key", secureFallbackWrite(burnableIdentity.encPrivateKeyB64))""",
    "IdentityRepository.generateBurnableIdentity() encrypts private keys in fallback mode",
)

edit(
    IDENT,
    """        val privEd = prefs.getString("burnable_ed25519_private_key", null) ?: return null
        val privEnc = prefs.getString("burnable_enc_private_key", null) ?: return null""",
    """        val privEd = secureFallbackRead(prefs.getString("burnable_ed25519_private_key", null)) ?: return null
        val privEnc = secureFallbackRead(prefs.getString("burnable_enc_private_key", null)) ?: return null""",
    "IdentityRepository.getBurnableIdentity() reads through the fallback decryptor",
)

edit(
    IDENT,
    """    /**
     * Wipe all identity data from both ESP and Room. Used by factory reset.
     */
    suspend fun clearAll() {
        prefs.edit().clear().apply()
        Logger.info(TAG, "All identity data cleared from EncryptedSharedPreferences")
    }""",
    """    /**
     * Wipe all identity data from the encrypted preference store AND the public
     * mirror of it in Room. Used by factory reset.
     */
    suspend fun clearAll() {
        prefs.edit().clear().apply()
        // NOSLOP_CLEARALL_ROOM_V1 — the docstring claimed Room was cleared too but
        // nothing did it, so a factory reset left the public identity behind and a
        // subsequent loadIdentity() saw a half-wiped state.
        listOf(
            "local_handle", "local_pub_ed25519", "local_pub_enc",
            "local_tripcode", "local_onion", "local_display_name",
            "onboarding_complete", "session_locked"
        ).forEach { appSettingDao.removeSetting(it) }
        Logger.info(TAG, "All identity data cleared from encrypted prefs and Room")
    }""",
    "IdentityRepository.clearAll() actually clears Room",
)

# ---------------------------------------------------------------------------
# 2. MeshTransport
# ---------------------------------------------------------------------------

TRANSPORT = "app/src/main/java/com/noslop/app/mesh/MeshTransport.kt"

edit(
    TRANSPORT,
    """import java.io.BufferedReader
import java.io.InputStreamReader""",
    """import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.InputStreamReader""",
    "MeshTransport imports BufferedInputStream",
    marker="import java.io.BufferedInputStream",
)

edit(
    TRANSPORT,
    """    private val MAX_SIMULTANEOUS_CONNECTIONS = 16""",
    """    private val MAX_SIMULTANEOUS_CONNECTIONS = 16

    // NOSLOP_FRAME_CAP_V1 — hard ceiling on a single newline-delimited frame.
    // Generous enough for the largest MEDIA_CHUNK payload, small enough that a
    // peer cannot buffer the heap away by never sending a newline.
    private val MAX_PACKET_CHARS = 4 * 1024 * 1024""",
    "MeshTransport declares MAX_PACKET_CHARS",
    marker="MAX_PACKET_CHARS = 4 * 1024 * 1024",
)

edit(
    TRANSPORT,
    """            socket.soTimeout = 30000 // 30-second read timeout
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val packetStr = line ?: break
                
                try {
                    Logger.debug(TAG, "Parsing incoming packet (length: ${packetStr.length})")
                    val packet = NetworkPacket.fromJson(packetStr)
                    Logger.info(TAG, "Received packet over TCP", "type=${packet.type} | sender=${packet.senderId}")
                    repository.handleIncomingPacket(packet)
                } catch (e: Exception) {
                    Logger.error(TAG, "Failed to parse incoming packet JSON: ${e.message}. Raw packet: $packetStr")
                }
            }""",
    """            socket.soTimeout = 30000 // 30-second read timeout

            // --- NOSLOP_FRAME_CAP_V1 ---
            // BufferedReader.readLine() has no upper bound. A peer that sends
            // bytes forever without a newline buffered all of them into heap,
            // which is a one-connection OOM. Read framed by hand with a cap and
            // drop the connection when a frame exceeds it.
            val reader = InputStreamReader(BufferedInputStream(socket.getInputStream()), Charsets.UTF_8)
            val frame = StringBuilder(8192)
            while (true) {
                val ch = reader.read()
                if (ch == -1) break
                if (ch == '\\r'.code) continue
                if (ch != '\\n'.code) {
                    if (frame.length >= MAX_PACKET_CHARS) {
                        Logger.warn(TAG, "Dropping connection from $clientIp: frame exceeded $MAX_PACKET_CHARS chars with no newline")
                        return@withContext
                    }
                    frame.append(ch.toChar())
                    continue
                }

                val packetStr = frame.toString().trim()
                frame.setLength(0)
                if (packetStr.isEmpty()) continue

                try {
                    Logger.debug(TAG, "Parsing incoming packet (length: ${packetStr.length})")
                    val packet = NetworkPacket.fromJson(packetStr)
                    Logger.info(TAG, "Received packet over TCP", "type=${packet.type}")
                    repository.handleIncomingPacket(packet)
                } catch (e: Exception) {
                    // NOSLOP_NO_RAW_FRAME_LOG_V1 — the raw frame used to be logged
                    // here. It lands in the user-exportable log file and can carry
                    // DM ciphertext, nonces and peer public keys.
                    Logger.error(TAG, "Failed to parse incoming packet JSON: ${e.message}", "bytes=${packetStr.length}")
                }
            }""",
    "MeshTransport caps frame length and stops logging raw packets",
)

# ---------------------------------------------------------------------------
# 3. AndroidManifest — dead DownloadReceiver
# ---------------------------------------------------------------------------

MANIFEST = "app/src/main/AndroidManifest.xml"

edit(
    MANIFEST,
    """        <receiver android:name=".util.UpdateManager$DownloadReceiver"
            android:exported="false">
            <intent-filter>
                <action android:name="android.intent.action.DOWNLOAD_COMPLETE" />
            </intent-filter>
        </receiver>

""",
    "",
    "AndroidManifest drops the dead DownloadReceiver registration",
)

# ---------------------------------------------------------------------------
# 4. app/build.gradle.kts
# ---------------------------------------------------------------------------

GRADLE = "app/build.gradle.kts"

edit(
    GRADLE,
    """    signingConfigs {
        create("release") {
            storeFile = file(project.property("NOSLOP_STORE_FILE") as String)
            storePassword = project.property("NOSLOP_STORE_PASSWORD") as String
            keyAlias = project.property("NOSLOP_KEY_ALIAS") as String
            keyPassword = project.property("NOSLOP_KEY_PASSWORD") as String
        }
    }""",
    """    // NOSLOP_CONDITIONAL_SIGNING_V1
    // project.property() throws when the property is absent, and signingConfigs is
    // evaluated during the configuration phase — so a clone without the keystore
    // properties failed on EVERY task, including assembleDebug and test. Configure
    // the release signing config only when all four properties are present.
    val hasReleaseSigning = listOf(
        "NOSLOP_STORE_FILE", "NOSLOP_STORE_PASSWORD",
        "NOSLOP_KEY_ALIAS", "NOSLOP_KEY_PASSWORD"
    ).all { project.hasProperty(it) }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(project.property("NOSLOP_STORE_FILE") as String)
                storePassword = project.property("NOSLOP_STORE_PASSWORD") as String
                keyAlias = project.property("NOSLOP_KEY_ALIAS") as String
                keyPassword = project.property("NOSLOP_KEY_PASSWORD") as String
            }
        }
    }""",
    "build.gradle.kts release signing is conditional",
)

edit(
    GRADLE,
    """            signingConfig = signingConfigs.getByName("release")  // add this""",
    """            // Unsigned when the keystore properties are absent; assembleRelease then
            // produces an unsigned APK instead of failing configuration outright.
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }""",
    "build.gradle.kts release buildType only attaches a signing config when it exists",
)

edit(
    GRADLE,
    """    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.0")""",
    """    implementation(libs.okhttp.dnsoverhttps)""",
    "build.gradle.kts okhttp-dnsoverhttps moves to the version catalog",
)

edit(
    GRADLE,
    """    implementation("com.jcraft:jsch:0.1.55")""",
    """    // NOSLOP_JSCH_FORK_V1 — com.jcraft:jsch has been unmaintained since 2018 and
    // supports neither rsa-sha2-* nor ssh-ed25519 host keys, so Hub deployment
    // failed key exchange against any current OpenSSH server. This fork is a
    // drop-in replacement and keeps the com.jcraft.jsch package names.
    implementation("com.github.mwiede:jsch:0.2.17")""",
    "build.gradle.kts swaps jsch for the maintained fork",
)

# Read local.properties but never use it — remove the dead block.
edit(
    GRADLE,
    """// Read GitHub config from local.properties (outside android{} to avoid DSL scope issues)
val localProps = Properties()
val localPropsFile = rootProject.file("local.properties")
if (localPropsFile.exists()) {
    localPropsFile.reader().use { localProps.load(it) }
}

""",
    "",
    "build.gradle.kts drops the dead local.properties loader",
)

edit(
    GRADLE,
    """import java.util.Properties

plugins {""",
    """plugins {""",
    "build.gradle.kts drops the now-unused Properties import",
)

# ---------------------------------------------------------------------------
# 5. libs.versions.toml
# ---------------------------------------------------------------------------

CATALOG = "gradle/libs.versions.toml"

edit(
    CATALOG,
    """loggingInterceptor = "4.10.0"
okhttp = "4.10.0\"""",
    """loggingInterceptor = "4.12.0"
okhttp = "4.12.0\"""",
    "libs.versions.toml aligns okhttp on 4.12.0",
)

edit(
    CATALOG,
    """okhttp = { group = "com.squareup.okhttp3", name = "okhttp", version.ref = "okhttp" }""",
    """okhttp = { group = "com.squareup.okhttp3", name = "okhttp", version.ref = "okhttp" }
okhttp-dnsoverhttps = { group = "com.squareup.okhttp3", name = "okhttp-dnsoverhttps", version.ref = "okhttp" }""",
    "libs.versions.toml declares okhttp-dnsoverhttps",
    marker="okhttp-dnsoverhttps = {",
)

edit(
    CATALOG,
    """bouncycastle-bcprov = { group = "org.bouncycastle", name = "bcprov-jdk18on", version.ref = "bouncycastle" }
""",
    "",
    "libs.versions.toml drops the unused bcprov entry (app uses bcprov-jdk15to18)",
)

# ---------------------------------------------------------------------------
# 6. .gitignore
# ---------------------------------------------------------------------------

GITIGNORE = ".gitignore"

edit(
    GITIGNORE,
    """# Python files
*.py

""",
    "",
    ".gitignore stops excluding all Python files",
)

edit(
    GITIGNORE,
    """# Other
*.sh
test_parse/
tests/
local.properties
*.keystore
.kotlin/""",
    """# Other
test_parse/
local.properties
*.keystore
.kotlin/

# Captured device logs — never commit these, they contain onion addresses,
# peer keys and the full process list of the capturing device.
tmp_logs/
*.logcat""",
    ".gitignore stops excluding shell scripts and tests/, adds tmp_logs/",
)

# ---------------------------------------------------------------------------
# 7. Stray files
# ---------------------------------------------------------------------------

if os.path.exists("test.kt"):
    os.remove("test.kt")
    APPLIED.append("removed stray test.kt from the project root")
else:
    SKIPPED.append("test.kt: already gone")

if os.path.isdir("tmp_logs"):
    shutil.rmtree("tmp_logs")
    APPLIED.append("removed tmp_logs/ (41MB device logcat)")
else:
    SKIPPED.append("tmp_logs/: already gone")


# ---------------------------------------------------------------------------

print("\n=== APPLIED ===")
for x in APPLIED:
    print("  +", x)
print("\n=== SKIPPED ===")
for x in SKIPPED:
    print("  =", x)
if FAILED:
    print("\n=== FAILED ===")
    for x in FAILED:
        print("  !", x)

print(
    """
NOTE: tmp_logs/ is deleted from the working tree but is still in git history.
To purge it (rewrites history — coordinate before force-pushing):

    git rm -r --cached tmp_logs test.kt
    git commit -m "Remove committed device logs and scratch file"
    # then, with git-filter-repo installed:
    git filter-repo --path tmp_logs --invert-paths
"""
)

sys.exit(1 if FAILED else 0)
