# NoSlop Support Documentation

## Troubleshooting

### Feed is Empty
*   **Tor Connection**: Ensure Tor is connected. Check the status indicator at the top of the feed.
*   **Refresh**: Pull down to refresh or use the "Refresh Feed" button in the search modal.
*   **API Keys**: Some sources like Pexels or NewsAPI require an API key in Settings. Wikimedia and NASA do not.
*   **Old items disappear**: NoSlop removes stored feed items after about 75 days, or sooner past 10,000 unread items. Saved items stay. Search can still find older items.

### Media Playback Issues
*   **Videos not playing**: With "Route Clearnet via Tor" on, YouTube refuses many Tor exits. NoSlop tries other circuits by itself, which can take several seconds. Videos that can't play over Tor at all drop out of the Live Feed for that session. If a slide still shows an error, tap Retry or swipe on.
*   **Video pauses to buffer during a long watch**: The Tor circuit carrying it has slowed or failed. NoSlop moves the video to a new circuit by itself, at the same position, and the picture stays on screen. "Finding stream" only appears if that keeps failing.
*   **Video stops when you leave the app**: Turn on **Background Playback** and **Foreground Service** in Settings.
*   **Audio buffering**: Clearnet audio (your aggregated feed) comes from its source, over Tor when "Route Clearnet via Tor" is on, so it can take a few seconds to start; audio or media attached to **mesh posts/DMs** is fetched chunk-by-chunk from a peer over Tor and may take longer to buffer, especially on a slow or freshly-built circuit.

### Installing an Update
NoSlop checks the downloaded update against its published checksum, then saves it to your Download folder. It can't start the install itself. Tap **Open Downloads**, then tap the NoSlop APK. The Settings banner keeps these steps until the update is installed.

### Mesh Connectivity
*   **Adding Peers**: Use the QR scan feature to exchange identity details with friends.
*   **Tor v3 Addresses**: Mesh communication depends on Tor hidden services. If Tor is not ready, you won't be able to send or receive mesh posts.

## Backup and Restore
*   **Word Cloud**: Your 12-word mnemonic generates your cryptographic identity keys via HKDF and encrypts your backup archives. Write it down and keep it safe.
*   **Export Backup**: Go to Settings -> Backup to export an authenticated AES-256-GCM encrypted archive containing your identity, the full local database (peers, mesh posts, comments, DMs, votes/reactions), and any downloaded media files. The encryption key is derived from your Word Cloud mnemonic — see [TECHNICAL_REFERENCE.md §3.8](TECHNICAL_REFERENCE.md#38-bip39-mnemonic).
*   **Import Backup**: Importing a `.zip` backup will restore your database and media files after verifying the AES-256-GCM authentication tag. Legacy unauthenticated CBC archives prompt for explicit confirmation before proceeding. When migrating across devices, Keystore-sealed identity files require re-deriving your identity from your Word Cloud mnemonic.

## Known Issues
*   WebView for "Read Full Article" might not block all trackers on the source website.
*   Large mesh attachments (>5MB) may propagate slowly over Tor circuits.

---

## Debug Logs

NoSlop includes an offline-first, local-only logging architecture designed for decentralized node debugging. There are no analytics trackers, telemetry SDKs, or central reporting backends. All diagnostics write to:
1.  An in-memory **2,000-entry ring buffer** (`Logger.kt`'s `MAX_ENTRIES`) for real-time viewing in the Settings tab.
2.  A local, persistent, newline-delimited text log file on the device.

This document describes how to access, filter, and share these diagnostic details.

---

### 1. Where Logs are Written

All system events are logged using our high-performance `Logger.kt` engine. The persistent log file is recorded locally in your app's isolated private files directory:

*   **Log File Name**: `noslop-debug.log`
*   **Absolute Path On Device**:
    `/data/data/com.noslop.app/files/noslop-debug.log`
*   **Android Context Reference**: `context.filesDir.absolutePath + "/noslop-debug.log"`

---

### 2. Log Entry Format

Each line inside `noslop-debug.log` represents a structured entry. The format is designed to be highly readable for both developers and LLM context injectors. A sample entry is formatted as follows:

```
[TIMESTAMP] [LEVEL] [MODULE] message | details
```

#### Fields:
*   **`TIMESTAMP`**: Recorded in ISO 8601 format (`yyyy-MM-dd HH:mm:ss.SSS`) in the active timezone.
*   **`LEVEL`**: Represents severity. Possible keys are:
    *   `DEBUG`: Highly verbose events detailing routine flows, SOCKS pings, or database transaction logs.
    *   `INFO`: High-level operational events (e.g. Identity created, Feed sync started, Peer registered).
    *   `WARN`: Non-fatal warning messages (e.g. Feed parse failure on malformed XML, Tor proxy delay).
    *   `ERROR`: Critical occurrences (e.g. Signature mismatch, DB connection failure).
*   **`MODULE`**: Denotes the functional component that outputted the log (e.g. `CRYPTO`, `TOR`, `FIREWALL`).
*   **`message`**: Clear textual description of the action.
*   **`details`**: Optional metadata context (e.g. specific IDs, exception classes, derived public key stubs).

---

### 3. Copying Logs in App (No-Computer Method)

To review and extract logs directly on your phone:

1.  Open NoSlop, navigate to the **Settings** tab.
2.  Tap on **Structured Debug Logs** to enter the fully integrated logs console.
3.  Filter logs dynamically by Level (`All`, `DEBUG`, `INFO`, `WARN`, `ERROR`).
4.  Tap on the **Copy Logs (Refresh Icon)** button in the upper header.
5.  A clipboard copy action will write the current logs buffer to your system's paste buffer, allowing you to share/paste it in any email, chat thread, or debugging window.
6.  You can also wipe files at any time by pressing the **Trash** icon.

---

### 4. Pulling Logs via ADB

If you are developing locally with a physical phone connected over USB, use standard Android Debug Bridge (ADB) commands to read or stream files.

#### 4.1 Live Streaming Logs to Terminal
Use `tail` to observe system operations in real-time as you tap on features:
```bash
adb shell run-as com.noslop.app tail -f /data/data/com.noslop.app/files/noslop-debug.log
```

#### 4.2 Pulling the Log File to your Laptop
Download the entire `.log` file to your active working directory on your personal machine:
```bash
adb exec-out run-as com.noslop.app cat files/noslop-debug.log > ./noslop-debug.log
```

4.1 and 4.2 use `run-as`, which only works on a debuggable build. A release build answers `run-as: package not debuggable`, so use 4.3 or the in-app **Copy Logs** there. Logcat works on both.

#### 4.3 Capturing Logcat

Everything `Logger.kt` writes also goes to logcat, tagged `NoSlop/<MODULE>`. Capture it unfiltered, so the Tor daemon's own output is included, and search it afterwards:
```bash
adb logcat -c
adb logcat -v time > noslop.log
grep -E 'NoSlop/(JANK|VIDEO_AWAY|AV_SYNC|VIDEO|PLAYBACK_DIAG|PRELOAD|YT_INTERNAL_API|VM|FEED)' noslop.log
```

The commands in 4.1 and 4.2 use the `github` flavour's package. For a Play build, use `com.noslop.me.app`.

---

### 5. Key Modules and What They Reveal

Filters are applied in the logger to help isolate specific subsystem issues:

*   **`ONBOARDING`**: Documents the user wizard. Look here if keys or feeds aren't writing on first-run.
*   **`CRYPTO`**: Details public key creation, SHA3-256 derivations (tripcode, onion address), Ed25519 signing, and X25519 DM key-agreement procedures (see [TECHNICAL_REFERENCE.md §3](TECHNICAL_REFERENCE.md#3-identity--cryptography) for the exact derivations).
    *   *Security Precaution: The cryptography engine NEVER logs raw private keys or seed variables.*
*   **`TOR`**: Details embedded Tor daemon lifecycle, SOCKS5 port checks, hidden service registration, and result parameters from pings to `check.torproject.org`.
*   **`FEED`**: Audits RSS/Atom XML parsing, HTML sanitization, API pipeline fetches (Jamendo, YouTube, Archive.org), and database insertions.
*   **`FIREWALL`**: Audits our strict mesh packet filter rules. It lists sender pubkeys and drops packets that are not explicitly trusted.
*   **`REPOSITORY` / `DATABASE`**: Audits CRUD actions, Room transactions, and synchronization locks.
*   **`JANK`**: The main thread was silent for 600 ms or more. The line carries its stack, so it names the code that froze the app. Any `JANK` line is a bug.
*   **`VIDEO_AWAY`**: A video while the app is in the background: `App left`, `App returned`. Also any recovery in place, whether the app is away or on screen.
*   **`AV_SYNC`**: Picture and sound timing for each player, as `warm#N` or `fresh#N`: decoders, when sound started, clock rate, and dropped or late frames. See [TECHNICAL_REFERENCE.md §16.34](TECHNICAL_REFERENCE.md).
*   **`VIDEO` / `PLAYBACK_DIAG`**: Resolves, stalls, first frames and buffer samples for the video on screen.
*   **`PRELOAD`**: Warm-ups and claims. `Claimed preloaded video` means a swipe landed on a warm player.
*   **`YT_INTERNAL_API`**: YouTube resolves over Tor. It shows circuits refused, rotations and which client won.
*   **`VM`**: `Feed batch built off the main thread` gives how long a batch took.

[TECHNICAL_REFERENCE.md §16.28](TECHNICAL_REFERENCE.md) lists which lines to look for in what order. Read a capture before you post it: some lines carry the URL of what was playing.

---

### 6. Annotated Console Example

The following is a realistic 14-line log snippet showing a successful lifecycle start, identity generation, a feed fetch, and a blocked spam packet event:

```text
[2026-05-30 12:45:01.011] [INFO] [TOR] Embedded Tor daemon bootstrapped successfully
[2026-05-30 12:45:01.121] [INFO] [CRYPTO] Generating secure cryptographic identity for handle: bob
[2026-05-30 12:45:01.320] [INFO] [CRYPTO] Identity keys successfully created | tripcode=287cf0 | onion=e63ba...onion
[2026-05-30 12:45:01.334] [INFO] [REPOSITORY] Local Identity saved in database for user 'bob'
[2026-05-30 12:45:01.350] [INFO] [REPOSITORY] Onboarding status set to true
[2026-05-30 12:45:01.401] [INFO] [REPOSITORY] Starting feed synchronization...
[2026-05-30 12:45:01.421] [INFO] [FEED_PARSER] Fetching feed contents from: https://feeds.arstechnica.com/arstechnica/index
[2026-05-30 12:45:02.193] [INFO] [FEED_PARSER] Fetched 20 items for Ars Technica
[2026-05-30 12:45:05.419] [INFO] [TOR] Polling socket 127.0.0.1:9052 to check if Tor proxy is accepting connections...
[2026-05-30 12:45:05.441] [INFO] [TOR] Tor SOCKS5 proxy connected on port 9052! Tunnel is active.
[2026-05-30 12:45:05.501] [INFO] [TOR] Testing check.torproject.org through local SOCKS5 proxy...
[2026-05-30 12:45:06.128] [INFO] [TOR] Tor link status test completed. isTor=true
[2026-05-30 12:45:10.009] [WARN] [FIREWALL] DROP PACKET: Sender key is NOT registered as trusted. Dropping senderPubB64=MIIBIjANBgkqhkiG9w0BA...
[2026-05-30 12:45:15.311] [INFO] [REPOSITORY] Adding new peer node: alice.fed82e | trusted=true
```

---

### 7. Providing Logs for Debugging

When debugging issues or seeking help:

1.  Navigate to **Settings** → **Structured Debug Logs** on your emulator/phone.
2.  Tap the **Copy Logs** button.
3.  Paste the **copied log dump** into your bug report, chat thread, or issue tracker.
4.  Add a brief description of what you expected to see on screen vs. what actually occurred.
5.  Include the device model and Android version for hardware-specific debugging (e.g., MediaCodec exhaustion varies by chipset).

---

**Related docs**: [BUILD.md](BUILD.md) for build/install issues · [PROJECT_STATUS.md](PROJECT_STATUS.md) for currently-known bugs and in-progress fixes.
