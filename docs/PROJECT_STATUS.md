# NoSlop — Project Status

NoSlop is a serverless, offline-first personal RSS/Atom feed reader and private decentralized social mesh node configured for the **HAI-Net** decentralized initiative.

## Current Build & Integration Status
- **Android Compilation Target**: API Level 35 (compiled using JDK 11, standard Jetpack Compose UI)
- **Status**: **PASSING & FULLY COMPILED** (successfully verified via `compile_applet`)
- **Visual Identity**: Premium brutalist dark slate styling integrated with custom adaptive colors and Material Design 3 guidelines.

## Completed Milestones
1. **Package Re-alignment**: Restructured and renamed all packages from original placeholders to `com.noslop.app` dynamically across directories.
2. **Real Mesh Networking (Tor-routed TCP)**: Implemented SOCKS5 proxied TCP sockets (`MeshTransport.kt`) on port `9999` to stream newline-delimited JSON packets to peer onion addresses.
3. **Rust-Aligned Packet Schema**: Modeled standard, robust JSON packet frames (`Packets.kt`) matching the original `gossip.rs`/`packets.rs` specifications (Post, Encrypted Message, UserHandshake, ConnectionRequest).
4. **Gossip Protocol Engine (`GossipService.kt`)**: Built fully specification-compliant gossip routing:
   - TTL verification (drop when remaining hops == 0).
   - Insertion-ordered `LinkedHashSet` duplicate packer filter (capped at 1000 entries).
   - Local firewall mapping to drop all packets from untrusted senders except connection/handshake requests.
   - Decoupled re-stamping of local sender IDs for anonymized hops routing.
   - Sliding-window rate limiting of 20 packets per sender per 10-second interval.
5. **HAI-Net Crypto Realignment**: Migrated DM cryptography to Native X25519 (via Bouncy Castle) and ChaCha20-Poly1305, operating on SHA3-256 key derivations.
6. **Hardware Storage Isolation (`IdentityRepository`)**: Migrated raw private keys into secure `EncryptedSharedPreferences` backed by the hardware-backed Android Keystore, keeping Room tables free of target exposure.
7. **Offline Logging Daemon (`Logger`)**: Developed thread-safe ring-buffer logging combined with non-blocking concurrent async file-write queues to context directory files.
8. **Cleartext Security Profiles (`network_security_config.xml`)**: Configured strict TLS requirements globally with explicit isolated cleartext exceptions for whitelisted local loopbacks and specific feed nodes.
9. **F-Droid Orbot Deep Linking, Warnings, and Retries**: Configured a beautiful error warning card (`TorWarningPanel.kt`) paired with deep linking (`fdroid://details?id=org.torproject.android`), browser fallback links, and immediate automatic activities refresh checks (`onResume()`).
10. **SYNC_REQUEST/SYNC_RESPONSE protocol**: New peers auto-request 7 days of backlogged posts on handshake; nodes respond with verified post history (using post signatures verification).
11. **Coroutine Lifecycle Management**: All fire-and-forget sends use supervised repository and gossip scopes, not bare `CoroutineScope`.
12. **Signed Handshakes**: `USER_HANDSHAKE` packets are Ed25519-signed before transmission to establish robust trust identity.
13. **Isolated Server Socket Binding**: `ServerSocket` is bound strictly to loopback `127.0.0.1` to prevent local network exposure, solidifying a Tor hidden service only architecture.
14. **Send Retry with Backoff**: `MeshTransport` retries failed transmissions up to 3 times with a 2-second and 4-second exponential backoff structure.
15. **Background Feed Sync (WorkManager)**: Integrated periodic WorkManager `FeedSyncWorker` task to refresh subscribed RSS/Atom feed content every 15 minutes when connected to a network.
16. **QR Code Pairing (Scan & Share)**: Built `QRScanScreen` using mobile CameraX + Google ML Kit for frictionless live QR companion pairing, paired with `QRShareSheet` to display/share generated thematic contact profiles.

## Pending Implementations & Limitations
- **Tor hidden service auto-registration**: onion address is derived from key but not yet actively registered with a Tor daemon — peers must manually exchange onion addresses out-of-band.

## Cryptographic Specification Contract
| Function | Primitive | Format / Library | Storage Backend |
| :--- | :--- | :--- | :--- |
| **Post Signatures** | Ed25519 | Base64 strings (X.509/PKCS#8) | Android Keystore / `EncryptedSharedPreferences` |
| **Tripcode Derivation** | SHA3-256 | 6-char lowercase Base32 sequence | Database / Memory |
| **Onion Addressing** | SHA3-256 Tor v3 | 56-char `.onion` address | Database / Memory |
| **Key Agreement** | X25519 | Bouncy Castle | `EncryptedSharedPreferences` |
| **Direct Message E2EE** | ChaCha20-Poly1305 | 12-byte random nonce + SHA3-256 shared secret derivation | Local DB (Encrypted) |

