# NoSlop Support & Operations Guide

Welcome to the NoSlop operational handbook. This guide covers common configurations, troubleshooting steps, and security procedures for managing serverless decentralized nodes.

## Orbot/Tor Connectivity Troubleshooting
Because NoSlop is designed to aggregate feeds and mesh gossips serverlessly without centralized infrastructure, its direct client routing leverages the Android Tor companion **Orbot**.

### 1. Loopback Port Polling Issues
If NoSlop reports "Offline Proxy" or "Tor offline", verify the following:
- Ensure Orbot is installed on the device and active (the onion is glowing green).
- Ensure the **SOCKS5 proxy** option is enabled inside Orbot's settings (default port is `9050`).
- Ensure "VPN Mode" is toggled in Orbot if your device requires system-wide routing overrides.
- Use the **Test Tor** feature inside Settings to manually trigger connection checking on `check.torproject.org`.

### 2. SOCKS5 Port Collisions
On certain custom ROMs, the local port `9050` might be bound by internal system daemons. If polling fails systematically, examine log messages inside the Structured Debug Log viewer for socket initialization errors.

## Backup and Device Migration
Because NoSlop respects the "Not Your Keys, Not Your Node" mandate, **private keys are stored strictly on-device**.

### Storage Locations
- **Public Handle, Saved Items, and Posts**: Stored inside `/databases/noslop_app_database` (Room SQLite instance).
- **Private Sign/Encrypt Keys**: Stored inside `/shared_prefs/noslop_identity_secure.xml` using `EncryptedSharedPreferences`.

To back up your Node profile completely, copy the files from the isolated app directory:
`adb backup -f noslop_backup.ab com.noslop.app`

## Cleartext Exceptions
The app isolates feed queries globally using standard TLS profiles in `network_security_config.xml`. If a self-hosted custom feed source requires HTTP/cleartext transport, you must manually whitelist its domain or lookback IP inside:
`/app/src/main/res/xml/network_security_config.xml`

## Extracting Debug Logs
If you face unresolved bugs or packet drops, open **Settings -> Structured Debug Logs**.
- You can copy the raw log stream immediately to your device clipboard.
- The file is stored asynchronously at `/data/user/0/com.noslop.app/files/noslop-debug.log`.
