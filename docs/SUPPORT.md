# NoSlop Support & Operations Guide

Welcome to the NoSlop operational handbook. This guide covers common configurations, troubleshooting steps, and security procedures for managing your privacy-first media aggregator and encrypted mesh node.

## Tor Connectivity Troubleshooting
NoSlop includes a fully embedded Tor daemon (`tor-android`) — no external app like Orbot is required. All network traffic (feed fetches, mesh messages, media requests) is routed through a local SOCKS5 proxy on port 9050.

### 1. Loopback Port Polling Issues
If NoSlop reports "Tor Disconnected" in the Settings panel, verify the following:
- Wait 30–60 seconds after app launch for the embedded Tor daemon to complete bootstrapping.
- Use the **Test Tor** button in **Settings → Tor Routing Status** to manually trigger a connection check against `check.torproject.org`.
- If the daemon repeatedly fails, force-close the app completely and relaunch to restart the native Tor process.
- On some devices, battery optimization may kill the background Tor process. Exempt NoSlop from battery restrictions in your device's settings.

### 2. SOCKS5 Port Collisions
On certain custom ROMs, the local port `9050` might be bound by internal system daemons. If polling fails systematically, examine log messages inside the Structured Debug Log viewer for socket initialization errors.

## Backup, Restore, and Device Migration
NoSlop follows the principle of **"Not Your Keys, Not Your Node"** — all private keys and data are stored exclusively on-device.

### Storage Locations
- **User Profile, Feed Items, Contacts, and Posts**: Stored in `/databases/noslop_app_database` (Room SQLite with WAL mode).
- **Private Signing/Encryption Keys**: Stored in `/shared_prefs/noslop_identity_secure.xml` using `EncryptedSharedPreferences` backed by Android's hardware Keystore.
- **User Preferences (interests, genres, settings)**: Stored in the `app_settings` Room table as key-value JSON pairs.
- **Captured Media**: Stored in app-private external storage under `NoSlop/` subdirectories (Pictures, Movies, Music, Downloads).

### Encrypted Export/Import (In-App)
NoSlop provides a built-in backup system accessible from **Settings → Data & Backup**:
- **Export Profile (Zip)**: Creates an AES-256 encrypted archive containing the database, preferences, and all local media files.
- **Import Profile (Zip)**: Restores a previously exported archive, replacing the current database and media.
- The encryption key is derived from your BIP39 mnemonic phrase. Without the 12-word recovery phrase, backup files cannot be decrypted.

### Manual ADB Backup
To back up your Node profile via ADB:
`adb backup -f noslop_backup.ab com.noslop.app`

## Profile & Preferences
All identity and content configuration is managed through a single unified interface accessible via **Settings → Profile & Preferences**:
- **Identity**: Edit your display name, bio, and profile avatar.
- **Content Interests**: Adjust your interest categories, music genres, video genres, and content language.
- **Source Management**: Manually toggle individual RSS and API sources on or off. The system uses a smart fallback—if no sources are explicitly checked for an active category, it derives from the built-in library automatically.
- **Negative Keywords**: Add custom keywords to filter out unwanted topics.
- **API Keys**: Configure optional API keys for enhanced content sources via the separate **Settings → API Keys** menu.

## Factory Reset
NoSlop includes a "nuclear option" accessible from **Settings → Data & Backup → Factory Reset**. This permanently:
- Wipes all database tables (contacts, posts, messages, feed items, settings).
- Resets the onboarding state, forcing the app to restart from identity generation.
- Does **not** erase the hardware-backed Keystore entries or external media files.
- Requires explicit confirmation via a dialog before execution.

## Cleartext Exceptions
The app isolates feed queries globally using strict TLS profiles in `network_security_config.xml`. If a self-hosted custom feed source requires HTTP/cleartext transport, you must manually whitelist its domain inside:
`/app/src/main/res/xml/network_security_config.xml`

For DNS resolution, NoSlop uses DNS-over-HTTPS (Cloudflare 1.1.1.1) as a secure fallback to prevent DNS leaks.

## Extracting Debug Logs
If you face unresolved bugs or packet drops, open **Settings → Structured Debug Logs**.
- Filter logs by level (DEBUG, INFO, WARN, ERROR) for targeted analysis.
- Copy the raw log stream to your device clipboard for sharing.
- The file is stored asynchronously at `/data/user/0/com.noslop.app/files/noslop-debug.log`.
- See [DEBUG.md](DEBUG.md) for full diagnostic documentation.
