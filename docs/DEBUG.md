# Debugging NoSlop Android

NoSlop includes an offline-first, local-only logging architecture designed for decentralized node debugging. There are no analytics trackers, telemetry SDKs, or central reporting backends. All diagnostics write to:
1.  An in-memory **500-entry ring buffer** (`Logger.kt`'s `MAX_ENTRIES`) for real-time viewing in the Settings tab.
2.  A local, persistent, newline-delimited text log file on the device.

This document describes how to access, filter, and share these diagnostic details.

---

## 1. Where Logs are Written

All system events are logged using our high-performance `Logger.kt` engine. The persistent log file is recorded locally in your app's isolated private files directory:

*   **Log File Name**: `noslop-debug.log`
*   **Absolute Path On Device**:
    `/data/data/com.noslop.app/files/noslop-debug.log`
*   **Android Context Reference**: `context.filesDir.absolutePath + "/noslop-debug.log"`

---

## 2. Log Entry Format

Each line inside `noslop-debug.log` represents a structured entry. The format is designed to be highly readable for both developers and LLM context injectors. A sample entry is formatted as follows:

```
[TIMESTAMP] [LEVEL] [MODULE] message | details
```

### Fields:
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

## 3. Copying Logs in App (No-Computer Method)

To review and extract logs directly on your phone:

1.  Open NoSlop, navigate to the **Settings** tab.
2.  Tap on **Structured Debug Logs** to enter the fully integrated logs console.
3.  Filter logs dynamically by Level (`All`, `DEBUG`, `INFO`, `WARN`, `ERROR`).
4.  Tap on the **Copy Logs (Refresh Icon)** button in the upper header.
5.  A clipboard copy action will write the current logs buffer to your system's paste buffer, allowing you to share/paste it in any email, chat thread, or debugging window.
6.  You can also wipe files at any time by pressing the **Trash** icon.

---

## 4. Pulling Logs via ADB

If you are developing locally with a physical phone connected over USB, use standard Android Debug Bridge (ADB) commands to read or stream files.

### 4.1 Live Streaming Logs to Terminal
Use `tail` to observe system operations in real-time as you tap on features:
```bash
adb shell run-as com.noslop.app tail -f /data/data/com.noslop.app/files/noslop-debug.log
```

### 4.2 Pulling the Log File to your Laptop
Download the entire `.log` file to your active working directory on your personal machine:
```bash
adb exec-out run-as com.noslop.app cat files/noslop-debug.log > ./noslop-debug.log
```

---

## 5. Key Modules and What They Reveal

Filters are applied in the logger to help isolate specific subsystem issues:

*   **`ONBOARDING`**: Documents the user wizard. Look here if keys or feeds aren't writing on first-run.
*   **`CRYPTO`**: Details public key creation, SHA3-256 derivations (tripcode, onion address), Ed25519 signing, and X25519 DM key-agreement procedures (see [TECHNICAL_REFERENCE.md §3](TECHNICAL_REFERENCE.md#3-identity--cryptography) for the exact derivations).
    *   *Security Precaution: The cryptography engine NEVER logs raw private keys or seed variables.*
*   **`TOR`**: Details embedded Tor daemon lifecycle, SOCKS5 port checks, hidden service registration, and result parameters from pings to `check.torproject.org`.
*   **`FEED`**: Audits RSS/Atom XML parsing, HTML sanitization, API pipeline fetches (Jamendo, YouTube, Archive.org), and database insertions.
*   **`FIREWALL`**: Audits our strict mesh packet filter rules. It lists sender pubkeys and drops packets that are not explicitly trusted.
*   **`REPOSITORY` / `DATABASE`**: Audits CRUD actions, Room transactions, and synchronization locks.

---

## 6. Annotated Console Example

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
[2026-05-30 12:45:05.419] [INFO] [TOR] Polling socket 127.0.0.1:9050 to check if Tor proxy is accepting connections...
[2026-05-30 12:45:05.441] [INFO] [TOR] Tor SOCKS5 proxy connected on port 9050! Tunnel is active.
[2026-05-30 12:45:05.501] [INFO] [TOR] Testing check.torproject.org through local SOCKS5 proxy...
[2026-05-30 12:45:06.128] [INFO] [TOR] Tor link status test completed. isTor=true
[2026-05-30 12:45:10.009] [WARN] [FIREWALL] DROP PACKET: Sender key is NOT registered as trusted. Dropping senderPubB64=MIIBIjANBgkqhkiG9w0BA...
[2026-05-30 12:45:15.311] [INFO] [REPOSITORY] Adding new peer node: alice.fed82e | trusted=true
```

---

## 7. Providing Logs for Debugging

When debugging issues or seeking help:

1.  Navigate to **Settings** → **Structured Debug Logs** on your emulator/phone.
2.  Tap the **Copy Logs** button.
3.  Paste the **copied log dump** into your bug report, chat thread, or issue tracker.
4.  Add a brief description of what you expected to see on screen vs. what actually occurred.
5.  Include the device model and Android version for hardware-specific debugging (e.g., MediaCodec exhaustion varies by chipset).

---

**Related docs**: [SUPPORT.md](SUPPORT.md) for general troubleshooting steps before you reach for logs · [TECHNICAL_REFERENCE.md](TECHNICAL_REFERENCE.md) if a log message references an internal module/function you want to look up.
