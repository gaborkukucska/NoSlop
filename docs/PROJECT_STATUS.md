# Project Status - NoSlop

## Completed Changes (2026-07-31)

### 1. Discoverable Mode & Temporary Contacts
*   **Heartbeat Identity Leak Fixed**: Fixed `MeshSocialRepository`'s background periodic heartbeat which was inadvertently broadcasting `ANNOUNCE_DISCOVERABLE` packets signed by the *main* identity instead of the *burnable* identity.
*   **Heuristic Discoverability Filtering**: Added logic to `HandshakePacketHandler` to drop `ANNOUNCE_DISCOVERABLE` packets if the handle perfectly matches an already trusted peer's handle or the local burnable identity. This keeps the feed clean and prevents known friends (or the user themselves) from popping up as "new" discoverable peers.
*   **Temporary Status Persistency**: Fixed a bug where a trusted peer broadcasting a discoverable packet would overwrite its local `isTemporary` state to `false`, accidentally upgrading them to a permanent contact.
*   **Temporary Contacts UI Persistence**: The "Temporary Contacts" list in `DMsTab` now saves its collapsed/expanded state across app sessions using `AppSetting`.

### 2. Media Downloads for Temporary Contacts
*   **Burnable Proxy Routing**: Fixed a major bug in `MediaManager` and `GossipService` where media packets (`MEDIA_RELAY_REQUEST`, `MEDIA_RECOVERY_FOUND`, `MEDIA_REQUEST`, and `MEDIA_CHUNK`) sent between temporary contacts were signed with the local node's *main* identity rather than the *burnable* identity. The recipient's mesh firewall would subsequently drop them (as it only trusted the burnable identity). Media requests and chunk relays are now properly routed through the burnable identity for temporary contacts.

### 3. Stability & Tor Resiliency
*   **Foreground Service Crash Loop**: Wrapped `startForegroundService` and `stopForeground` in safety blocks to prevent fatal `AndroidRuntime` crashes under Android 12+ strict background execution limits.
*   **Tor Auto-Recovery**: Added an active monitoring loop in `NoSlopViewModel` that seamlessly attempts up to 3 restarts if the Tor daemon falls into a `FAILED` state.
*   **Logcat Spam Reduction**: Downgraded routine ExoPlayer `VIDEO_DEBUG` events from `Log.e` to internal debug logging, clearing up 86% of the false-positive error noise.

### 4. Media & Feed UX Polish
*   **Mesh Feed Top-Snapping**: The "Mesh Network" and "Your Broadcasts" feeds now explicitly bypass the "last viewed" memory feature. Opening these filters instantly snaps to the top to show the newest content, resolving the need to scroll back up.
*   **Large File Transfers**: Reduced the maximum Tor chunk size from 512KB to 256KB and lowered the maximum concurrent SOCKS5 connections from 8 to 4, preventing Tor circuit timeouts and buffer congestion on large media transfers.
*   **Relay Restamping for Temporary Contacts**: `MEDIA_RECOVERY_FOUND` packets now correctly restamp the sender ID as the node's *Burnable Identity* when relaying to a Temporary Contact, preventing the target's firewall from silently dropping the recovery payload.
*   **VideoCompressor Crash Fix**: Handled Media3 Transformer exceptions in `VideoCompressor.kt` to gracefully emit error states instead of fatally crashing the background coroutine.
*   **Invidious Instance Refresh**: Replaced 10 dead or rate-limited YouTube proxy instances with 5 known-good instances (`yewtu.be`, `projectsegfau.lt`, etc.) resolving the widespread `NXDOMAIN` and `403` search failures.

## Completed Changes (2026-07-28)

### 1. Discoverable Mode & Burnable Identity Fixes
*   **Burnable Identity Leak Fixed**: `NoSlopViewModel` now correctly pulls the `Burnable Identity` instead of the Main Identity when generating the `ANNOUNCE_DISCOVERABLE` broadcast. This guarantees peers will treat the incoming connection request as a temporary, anonymous contact rather than a permanent one.
*   **Pending Request UI Visibility**: Corrected a bug in `DMsTab` where pending connection requests from Discoverable nodes instantly disappeared. The filter now hides `!isDiscoverable` instead of `!isTemporary`, allowing burnable pending requests to stay visible until accepted.

### 2. Tor Mesh Transport Tuning & Fast-Fail
*   **Timeout & Retry Reduction**: Dropped the Tor SOCKS5 `connectTimeout` in `MeshTransport` from 60 seconds to 30 seconds, and reduced the maximum attempts for critical packets from 5 to 3. Because Tor v3 hidden services can take 5-10 minutes to propagate to HSDirs, waiting 60s per attempt blocked the thread and gridlocked the queue. This change forces the transport to "fail-fast", instantly triggering the Gossip Relay fallback and background spooler for immediate delivery via intermediate peers.

### 3. DM Media & GIF Rendering
*   **MIME-Type Hack Removed**: Removed a legacy robustness hack in `DmPacketHandler` that forcefully rewrote `image` types to `gif`. This was causing the `MediaManager` to misclassify the file extension during download.
*   **Local File Prioritization in Coil**: Updated `ChatThreadScreen` to natively pass the locally downloaded `File` object to Coil's `AsyncImage` for GIFs, fully bypassing the `noslop-gif://` proxy scheme. This ensures GIFs animate immediately after downloading without attempting to stream through the local Tor proxy.

## Completed Changes (2026-07-26)

### 7. Discoverability & Profile Bio Support
*   **Firewall Bypass**: Corrected the gossip firewall to properly permit `IDENTITY_UPDATE` and `USER_EXIT` packets from discoverable 3rd parties, allowing profile propagation without a trusted connection.
*   **Bio Field Integration**: Added `bio` string support across the network protocol (`IdentityUpdatePayload`, `PeerHandshakePayload`, `AnnounceDiscoverablePayload`) and local database. The user info modal and DM lists now properly render bios.
*   **Discoverable Connect UI**: The 'Connect' button is now consistently injected into all user profile modals (feed, comments) for any non-trusted 3rd-party peer with a known onion address.

### 6. Privacy UI & Display Name Polish
*   **Share Button Guard**: The "Share" button is now explicitly hidden on all posts with `privacy = "friends"`, preventing peers from bridging or relaying private posts manually.
*   **Identity Formatting**: `CryptoService` no longer appends the cryptographically generated `.tripcode` to the default `displayName` string, meaning users' explicitly typed handles are used universally. Older legacy handles are dynamically stripped in the UI for a cleaner display.
*   **User Info Modal Privacy**: The User Profile modal no longer leaks public keys, tripcodes, or network details to any user. It exclusively displays the Avatar, Handle, and connection status.
*   **Burnable 3rd-Party Connections**: When a 3rd-party user taps on an author who is actively broadcasting `ANNOUNCE_DISCOVERABLE`, the modal now injects a "Connect" button. Tapping it triggers a mandatory warning dialog and exclusively routes the `CONNECTION_REQUEST` using the user's ephemeral/burnable identity, perfectly protecting the user's persistent onion address from strangers.


### 5. Friends-Only Broadcast Scoping
*   **Hop Count Restriction**: Outbound packets for friends-only broadcasts (Posts, Comments, Reactions, Votes, Edits, Deletes) are now strictly restricted to `hops = 1`. This explicitly scopes network propagation to directly connected trusted peers.
*   **Gossip Forwarder Guard**: The `GossipService` explicitly checks incoming `POST` payloads and drops any with `privacy = "friends"` from being relayed to other peers, completely isolating friends-only content from 3rd parties.


### 1. Tor Service Recovery & Auto-Healing
*   **Daemon Resurrection**: Fixed `TorService.kt` to handle Android's background service `IllegalStateException` limits safely and stop stuck daemon instances before restart.
*   **Auto-Retry Observer**: Added an observer in `NoSlopViewModel` that automatically detects if Tor falls into the `FAILED` state (e.g. killed by the OS when foreground service stops) and seamlessly auto-retries bootstrapping up to 3 times in the background.

### 2. Mesh Transport Fast-Failing & DM Spooler
*   **Semaphore Gridlock Prevention**: `MeshTransport` now instantly fast-fails and frees Tor circuits upon receiving explicit `SOCKS: Host unreachable` or `TTL expired` rejections, rather than stubbornly waiting 60s per attempt and hoarding permits.
*   **Background Retry Spooler**: Introduced a 15-minute background spooler in `MeshSocialRepository`. If a direct packet (DM/Handshake) fails initially due to the target's `.onion` still propagating to HSDirs, it will silently retry every 60 seconds. This perfectly mitigates Tor's 5-10 minute directory publication delay!

### 3. Foreground Service UX Polish
*   **HSDir Delay Transparency**: Added a dedicated `AlertDialog` in `SettingsTab` when the user toggles the Foreground Service off, educating them on the 5-10 minute Tor v3 publication propagation window and setting proper reachability expectations.

### 4. Mesh Filter UX Simplification
*   **Removed Outgoing Native Gates**: Stripped the confusing "Outgoing" toggles for Text, Image, and Video posts from `MeshFiltersScreen`, renaming them to "Broadcasts" for clarity.
*   **Unrestricted Network Engagement**: Removed outgoing mesh filters for `REACTION`, `VOTE`, and `COMMENT` packets. Native engagement is vital for the network's organic reputation and chronological sorting algorithms and must always be allowed to propagate. Mesh filters now exclusively govern heavy incoming media and automatic Clearnet-to-Mesh bridging.

## Completed Changes (2026-07-25)

### 1. Tor ED25519-V3 Key Derivation Math Fixed
*   **Proper SHA-512 Clamping**: Fixed a critical routing regression where `CryptoService.kt` and `SshDeployer.kt` were passing the 64-byte libsodium format (`seed || pubkey`) to the Tor daemon for `ADD_ONION`. Tor requires the 64-byte expanded and clamped secret key (SHA-512 of the seed with specific bitwise clamping). Corrected the math in both Kotlin and the Python deployment script to generate the exact same `.onion` address locally as the Tor daemon.

### 2. Hub Firewall & Mesh Sync Disconnection
*   **Mobile Identity Trust**: Fixed an issue where the Hub's `TrustFirewall` was rejecting `sync_push_packets` from the linked mobile device. `api_router.rs` now explicitly calls `engine.trust_peer(my_node_id)` ensuring the mobile app's signature is inherently trusted by its own Hub.

### 3. Mesh Transport & Gossip Storm Prevention
*   **Direct Routing Gate**: Patched `api_router.rs` to stop a dual-routing flood. If a directed packet has a known `target_onion`, the Hub will now only execute the direct SOCKS5 connection (with local background retries). It drops the redundant gossip broadcast unless the direct send fundamentally fails *and* no onion address was known.

### 4. Tor Ephemeral Onboarding Fallback
*   **Null Key Registration**: Fixed `TorService.kt` where a well-intentioned guard skipped hidden service registration if no identity key was loaded. Restored the ephemeral `registerHiddenService(null)` fallback, allowing new users to complete onboarding with a temporary `.onion` before their permanent identity is generated.

## Completed Changes (2026-07-13)

### 4. Bulletproof OTA Downloader (HttpURLConnection)
*   **Parse Error Resolution**: Fixed the "There was a problem parsing the package" error which occurred when OkHttp silently downloaded HTML login/404 pages (due to GitHub Draft/Private release states) instead of binary APKs.
*   **Native Progress Tracking**: `UpdateManager.kt` now uses a pure, interceptor-free `HttpURLConnection` pipeline. It manually negotiates redirects, strictly verifies the `Content-Type` to reject `text/html` payloads, enforces a >2MB minimum file size, and provides live UI Toasts (e.g., "Downloading update: 14MB...") every 3 seconds so the user is never left guessing the background state.

### 3. OTA Update Manager Rewrite (OkHttp)
*   **DownloadManager Bypass**: Completely removed reliance on Android's unreliable system `DownloadManager` which was hanging silently in `STATUS_PENDING` due to GitHub redirect chains.
*   **Native Coroutine Download**: `UpdateManager.kt` now streams the APK directly to disk via `HttpClientProvider.clearnetClient` in a Coroutine, ensuring 100% reliability, DNS-over-HTTPS fallback, and immediate installer launching upon completion.
*   **Settings Banner Polish**: Integrated a permanent Update Banner at the top of the Settings page that intelligently handles `REQUEST_INSTALL_PACKAGES` permission states, offering "Give Permission" and "Just Download APK" distinct workflows.

### 1. Admin AI Integration & ASN.1 Crypto Fixes
*   **Admin AI Auto-Peer**: Fixed an issue where DMs to the Hub's Admin AI failed because the app lacked a local peer record for it. `NoSlopRepository.ensureAdminPeerExists()` now automatically injects the `Admin AI (Hub)` contact upon linking.
*   **ASN.1 X25519 Decryption**: Resolved a critical crash where Android's BouncyCastle wrapped X25519 keys in 83-byte ASN.1 structures instead of raw 32-byte arrays. The Hub now mathematically extracts the raw scalars (`0x04, 0x20` and `0x03, 0x21, 0x00`), allowing seamless DM decryption and responses from the Admin AI.
*   **Newline Stripping**: Fixed hidden `\n` characters in Android JSON payloads breaking exact-match target interception.

### 2. Historical Hub Sync & SQLite Migration
*   **SQLite Persistence**: The Hub transitioned from volatile JSON arrays to a persistent SQLite database (`social.db`) for storing DMs, Posts, and Peers.
*   **Historical Pull Sync**: When linking to a Hub, NoSlop now dynamically pulls historical data (`get_dms`, `get_social_feed`, `get_mesh_peers`) to populate the local app state.
*   **Media Metadata Retention**: Fixed an issue where syncing DMs and Posts from the Hub to the mobile app dropped the associated media metadata, ensuring image and video UI elements render correctly.

## Completed Changes (2026-07-12)

### 1. DM Contacts Organization — Collapsible "All" List & Folder Assignment
*   **Collapsible "All" Contacts Tab**: The "All" contacts list on the DMs page can now be hidden by tapping the "All" tab header. When collapsed, only a compact icon is shown, and the page defaults to displaying the first custom folder. The collapsed state is persisted to `app_settings["dm_all_tab_hidden"]` so it survives navigation and app restarts.
*   **Contact Folder Assignment**: The "Assign to Folder" modal now includes a dropdown of existing folders (populated from previously created folders) alongside the text field for creating new folders. Users can either select an existing folder or type a new name.

### 2. Complete Settings Page Localization
*   **Full `.tr` Extension Audit**: Conducted a comprehensive audit of `SettingsTab.kt` and applied the `.tr` translation extension to all remaining hardcoded user-facing strings, including: Tor proxy status labels (`"Active Tor Proxy"`, `"Tor Disconnected"`), media file limit label (`"Max File Size: "`), data & backup workflow strings (`"Export Backup"`, `"Import Backup"`, `"Save File"`, `"Select File"`, `"Importing..."`, `"Nuclear Option"`), and app update notification strings (`"Version "`, `"is out (you have "`, `"Unknown"`).
*   **Hungarian (Magyar) Translation Completion**: Updated `content_hu.json` with proper Hungarian translations for all newly localized strings. Also translated the previously untranslated "About NoSlop" and "Help Development" modal strings (`"Help Development"`, `"Support Gabby's work..."`, `"About NoSlop"`, `"Buy Gabby a Coffee"`, `"Version "`, `"Resources"`, `"GitHub Repository"`, `"Privacy Policy"`, `"Imagined by Gabor Kukucska"`), which had been left in English.
*   **DM Folder UI Strings**: Added localization keys for the new folder assignment UI (`"Choose existing folder"`, `"Select a folder"`, `"or create new"`, `"New Folder Name"`, `"Folder Name"`, `"Assign to Folder"`) to both `content_en.json` and `content_hu.json`.

### 3. Media Settings Restructure & Trust-Based Auto-Download
*   **"Automatic Media Download" Rename**: Renamed the "Enable Media" toggle to "Automatic Media Download" for clarity.
*   **Background Playback Decoupled**: Moved the "Background Playback" and "Play Outside App" toggles out of the media download card into their own dedicated `Card` container, making it clear these settings are independent of auto-download behavior.
*   **Trust-Based Auto-Download Toggles**: Replaced the confusing "Auto-download Friends" / "Auto-download Private" toggles with a clearer trust-based hierarchy:
    *   **Friends** — "only auto-download media from contacts" (respects `isTrusted` peer status).
    *   **Public** — "Also auto-download media from public broadcasts" (covers non-contact mesh peers).
*   **Public Media Warning Dialog**: Toggling "Public" auto-download ON now triggers a confirmation `AlertDialog` warning: *"You'll download 3rd party media!"*. The setting is only applied if the user taps "Accept".
*   **File Attachment Exclusion**: `MediaManager.checkAndAutoDownload()` now explicitly skips media with `metadata.type == "file"`, ensuring file attachments are never auto-downloaded and must be manually tapped to sync.
*   **Data Model Migration**: Renamed `MediaSettings.autoDownloadPrivate` to `autoDownloadPublic` in `MediaSettings.kt` and updated all downstream consumers (`MediaManager.kt`, `SettingsTab.kt`).
*   **Files**: Modified: `MediaSettings.kt`, `MediaManager.kt`, `SettingsTab.kt`, `content_en.json`, `content_hu.json`.

### 4. Background Sync & HAI-Net Hub Onboarding Refactor
*   **Foreground Service Relocation**: Moved the "Foreground Service" toggle out of the General settings tab into the Network tab to align with architecture.
*   **Battery Warning Dialog**: Adding manual foreground mesh sync now triggers an aggressive battery warning dialog with a direct "Deploy HUB" call-to-action that securely routes the user to the HUBs tab.
*   **Automated Hub-Aware Gating**: The foreground service toggle is automatically disabled (grayed out) when a Home Hub is connected. Furthermore, the `NoSlopViewModel` dynamically halts the local foreground service upon successful Hub linkage to preserve mobile battery.
*   **Onboarding Routing Fix**: Corrected the "Set up Hub Now" button in the `OnboardingScreen` so it successfully navigates to the Hubs tab via the new `hubs-deploy` route and triggers an immediate local network scan.
*   **Deploy vs. Link Separation**: Refactored the network discovery UI (`HubSetupScreen.kt`) to support two distinct workflows: 
    *   **Deploy**: Finds a device, prompts for SSH credentials, and executes a full `SshDeployer` remote installation.
    *   **Link**: Resolves the broken "I already have a Hub running" option. It scans the network, bypasses SSH logic entirely, and instantly pairs the app to the selected existing node. Also introduced a secure "Manual IP Entry" modal exclusively for linking off-subnet Hubs.

## Completed Changes (2026-07-10)

### 1. Global Connectivity & Native Hub Tor Daemon
*   **Smart Hub API Client**: Implemented `invokeHubApi` in `NoSlopRepository.kt`. The app securely bridges communication to the Home Hub by attempting a LAN IP request first (for speed), and gracefully falling back to the SOCKS5 `torClient` targeting the Hub's `.onion` address when off-network.
*   **Active-Passive Identity**: Updated Mobile `TorService.kt` to dynamically disable its local Hidden Service broadcast when connected to a Hub. The mobile app pivots to using Tor purely as an outbound SOCKS5 proxy to conserve bandwidth and prevent identity collisions on the mesh.
*   **Native Hub Tor Generation**: Upgraded `hainet-seed` to parse the PKCS#8 Mobile Identity Clone, apply SHA-512 expansion and bit-clamping natively in Rust, and generate a standard `/var/lib/tor/hainet/hs_ed25519_secret_key`.
*   **Unified Onion Routing**: Configured the Hub's `/etc/tor/torrc` to expose both Port 9999 (Mesh Gossip) and Port 8080 (REST API) under the single, persistent `.onion` address.

### 2. Edge-Case Fixes (Hotspots & QR Decoding)
*   **Ghost Notification Cleanup**: Fixed a UI bug in `NoSlopViewModel.kt` where accepting or rejecting a mesh handshake via the UI popup left a ghost notification in the drawer. The ViewModel now actively sweeps and deletes associated `NotificationItem` entries upon resolution.

*   **Hotspot Subnet Discovery**: Upgraded `HubDiscoveryService.kt` to extract all active IPv4 prefixes (Cellular, Hotspot, WiFi) and concurrently scan them for SSH (Port 22), bypassing Android's mDNS/multicast isolation on tethered connections.
*   **Gallery QR Decoding Fallback**: Enhanced `QRScanScreen.kt` with a dual-engine decoder. If ML Kit fails to parse a synthetic screenshot from the gallery, it falls back to a ZXing binarizer on a dedicated IO thread.
*   **Port Alignment**: Fixed authentication port routing to target the unified `8080` port now handled by `hainet-core` instead of the legacy `3000` port.


### 3. Monetization & Support Architecture
*   **Stripe Integration**: Added a "Help Development" banner to the Settings page. This links directly to a Stripe Payment Link for seamless donations, avoiding the need for backend payment processing and keeping the app strictly peer-to-peer.
*   **About NoSlop Modal**: Consolidated the legacy app information and update notifications into a unified "About NoSlop" modal, accessible from the bottom of the Settings page. This includes dynamic version rendering and links to project resources (GitHub, Privacy Policy) and the creator's portfolio.
*   **Complete Localization Parity**: Ensured all new UI strings for the Donation and About modals are fully translatable, integrating them into the `.tr` extension and `content_en.json`/`content_hu.json` framework.

### 4. Robust Auto-Update System
*   **OTA Download Reliability**: Completely rewrote `UpdateManager.kt` to fix silent failures associated with Android's native `DownloadManager`.
*   **Dynamic Permission Handling**: Integrated runtime checks for `REQUEST_INSTALL_PACKAGES` (required on Android 8.0+) to automatically redirect users to system settings if the permission is missing, ensuring the APK installer can actually launch.
*   **Resilient Completion Detection**: Replaced the static manifest receiver with a dual-strategy approach: dynamic runtime `BroadcastReceiver` registration combined with a 3-second background polling loop via Coroutines. This guarantees the app detects when the update finishes downloading, even if the OS drops the broadcast.
*   **State Recovery**: The active download ID is now persisted to `SharedPreferences`, allowing the fallback static receiver to resume the installation process even if NoSlop is killed in the background during the download.

## Completed Changes (2026-07-09)

### 2. Multi-Path Hub Discovery & Reliable Authentication
*   **Robust Hotspot Discovery**: Upgraded `HubDiscoveryService.kt` with a concurrent multi-subnet scanner. It now extracts all active IPv4 prefixes (Cellular, Hotspot, WiFi) and scans them all for SSH (Port 22), bypassing mDNS/multicast isolation on tethered connections.
*   **High-Fidelity QR Authentication**: Fixed a port mismatch (3000 -> 8080) and IP routing logic in `NoSlopViewModel.kt`. Scanned login attempts now correctly target the integrated Hub Portal.
*   **Gallery QR Decoding Fallback**: Enhanced `QRScanScreen.kt` with a dual-engine decoder. If ML Kit fails to parse a screenshot (common with synthetic QR codes), it falls back to ZXing on a dedicated IO thread.
*   **Headless Auth Handshake**: Implemented the "QR-Login-Only" state in the Hub backend. If a Hub is deployed via NoSlop, it automatically skips 24-word seed generation and prompts for the mobile-assisted signature verification immediately.

### 1. HAI-Net Hub Deployment Fixes & Dashboard Polish
*   **Systemd Unit Deployment Fix**: Fixed a critical bug in `SshDeployer.kt` where pipelined `sudo tee` combined with quoted heredocs resulted in empty (masked) systemd unit files on the Hub. Re-wrote the installer script to write the unit file locally, expand dynamic variables, and `sudo mv` it into place.
*   **Deployment Overwrite Strategies**: Replaced blind overwrites with a secure collision detection mechanism during deployment. If an existing `hainet-core` install is detected, NoSlop halts and provides a Tripartite Resolution Dialog: "Sign In", "Reset Identity (Keep Media)", or "Full Re-deploy (Wipe All)".
*   **Lightning Fast Identity Reset**: The "Reset Identity" option completely bypasses the standard heavy `hainet-seed` build process. Instead, it securely injects the user's Ed25519/X25519 identity JSON directly, safely regenerates Tor hidden service keys via embedded Python scripts, deletes stale `auth.json` files, and restarts services in seconds, guaranteeing immediate QR login readiness.
*   **Native Hub Dashboard**: Restored and polished the native Compose Hub Dashboard in `HubSetupScreen.kt`. Removed the heavy embedded WebView in favor of a clean, text-based UI providing the user with the direct LAN IP and Port (3000) to access the HAI-Net Portal Web UI from an external browser.
*   **Persistent Hub Connection**: Removed the immediate "Disconnect" button from the main Hubs tab to reinforce the persistence of the Home Hub connection. 
*   **Safe Hub Unlinking**: Injected a secure "Disconnect HAI-Net Hub" option into `SettingsTab.kt` above the Developer logs, protected by a descriptive confirmation dialog to prevent accidental unlinking.
*   **Hardened QR Authentication**: Integrated connection and read timeouts into `handleQrLogin` to prevent silent hanging when a Hub is unreachable. Additionally, NoSlop now dynamically intercepts unroutable `0.0.0.0` target IPs originating from the Hub's QR code and automatically resolves them to the Hub's known active LAN IP from the deployment state.


## Completed Changes (2026-07-08)

### 1. HAI-Net Hub Integration (Phase 1) & Identity Clone
*   **Zero-Terminal Deployment**: Implemented `SshDeployer.kt` using JSch to deploy the HAI-Net Hub directly from the NoSlop mobile app. It connects via SSH, clones the repository, and executes `hainet-seed install` non-interactively.
*   **Zero-Config mDNS Discovery**: Added `HubDiscoveryService.kt` utilizing Android's `NsdManager` to scan the local network for `_ssh._tcp` services. Discovered hubs appear instantly in the UI, auto-filling the IP Address field so users never have to manually type or find IPs.
*   **Tor-Exclusive Remote Access**: Completely removed all Cloudflare Tunnel automation, UI inputs, and deployment configuration parameters (`cloudflareToken`, `hasStaticIp`). The Hub's remote accessibility now relies 100% on the embedded Tor Hidden Services architecture (`Phase 2`), simplifying the user experience drastically.
*   **Identity Clone Architecture**: Replaced the previous public-key-only design with a full "Identity Clone" model. `HubSetupScreen.kt` now automatically injects the user's complete `CryptoService.IdentityKeys` (including Ed25519 and X25519 private keys) into the `hub_config.json` payload.
*   **Secure Serialization**: Rewrote `SshDeployer` payload generation using `org.json.JSONObject` to prevent escaping errors and properly serialize the nested identity block for secure transport to the Hub.
*   **Hub Setup UI**: Updated `HubSetupScreen` to dynamically handle mDNS results and standard inputs (Username, Password, Shared Media Folder).
*   **Mesh Discoverability Fix**: Completed `broadcastDiscoverable()` in `NoSlopViewModel`, ensuring ephemeral "burnable" `.onion` addresses are correctly registered via `TorService` and announced to the mesh with cryptographic signatures.

## Completed Changes (2026-07-07)

### 1. Legacy Architecture Audit & Code Cleanup
*   **MainScreen Cleanup**: Evaluated `MainScreen.kt` and identified that the large `FullScreenMeshCard` composable was dead code (replaced by V2). Removed the unused code entirely and renamed the file to `MediaUtils.kt` to accurately reflect its remaining utility (`resolveMediaUrl`).
*   **Repository Facade Verification**: Analyzed `NoSlopRepository.kt` and confirmed it acts as a necessary facade for `NoSlopViewModel`, correctly delegating to domain-specific repositories. Safely exposed `postDao` internally to resolve visibility issues without breaking encapsulation.

### 2. Mesh Network Observability & Stability
*   **GossipService Tracing**: Injected precise trace logging in `GossipService.processIncoming` to monitor the mesh packet pipeline. We can now observe exactly where packets are dropped (e.g., TTL expiry, deduplication checks, rate limiting, firewall blocks, or mesh filter rejections).
*   **MeshPacketHandler Telemetry**: Added detailed logging for packet dispatching to ensure we can trace incoming traffic as it is routed to domain-specific handlers after gossip validation.

### 3. Comprehensive Unit Testing & Regression Guards
*   **Firewall & Mesh Filters Assertions**: Implemented robust unit tests using `MockK` in `GossipServiceTest.kt` to assert the firewall dropping logic and mesh filter rules. Validated that untrusted senders are blocked correctly while system packets like `CONNECTION_REQUEST` bypass the block.
*   **Test Isolation Fixed**: Resolved a critical state leak in `GossipServiceTest` where the singleton `GossipService` retained mock dependencies across tests, ensuring complete test isolation via reflection-based resets.
*   **Test Doubles Updated**: Fixed several compilation errors in `FakeDaos.kt` (missing overrides in `FakePostDao`, `FakeEngagementDao`, and `FakeMessageDao`) caused by upstream interface changes, ensuring the pure-JVM tests compile successfully.
## Completed Changes (2026-07-06)

### 1. Onboarding & Tutorial Flow Polish
*   **Onboarding Reordering**: Swapped the Interests and Creators steps. Moving Interests first ensures the Creator search word-cloud dynamically populates with relevant channel suggestions based on the user's chosen categories.
*   **Creator Search Fix**: Split the comma-separated creator keywords string to perform concurrent background searches via `launch(Dispatchers.IO)`, fixing the `searchCustomFeed` failure.
*   **Invidious API Tuning**: Removed aggressive `/api/v1/stats` background pinging that was incorrectly blacklisting healthy instances. Increased the probe client timeout from 5s to 10s to allow channel searches (autocomplete) to succeed reliably over Tor.
*   **Feed Tutorial Race Condition**: Fixed an issue where the feed tutorial state locked into the UI before the DB could restore it. The ViewModel now initializes tutorial states to `-1` (loading) and the UI waits before injecting the slides.
*   **Native Tutorial UI & Polish**: Replaced the hacky background-content rendering with a solid, native-looking tutorial slide featuring mock author info and standard right-aligned action buttons. Fixed the Step 2 UI arrow collision. Removed the "Skip" button to force gesture learning.
*   **Tutorial Theming**: Changed tutorial highlight elements (text chips, arrows) from the app's native `AccentGreen` to Material Amber to clearly differentiate instructional overlays from interactive app UI. Softened the DM tutorial scrim from 85% to 65% opacity.
*   **DM Tutorial Auto-Completion**: Added a `LaunchedEffect` in `DMsTab` that instantly skips or auto-completes the DM tutorial if the user already has active connections (e.g., via a restored backup or manual QR scan).

### 2. Feed Generation & Priority Sorting
*   **Empty Feed Bug Fix**: Added `rawImages` and `rawAudios` back into the feed generation `leftovers` pool. This fixes a critical bug that caused feeds to load entirely empty and block swiping if YouTube/Invidious video APIs failed or hit rate limits.
*   **3-Tier Priority Sorting**: Upgraded the chronological sorting algorithm in `loadMoreFeedItems` to strictly prioritize content in three tiers: `Creators > Explicitly Chosen Categories > Trending/Fallback`. This guarantees the first items a user sees upon completing onboarding perfectly match their personal interests.

### 3. Dynamic OTA Localization Framework
*   **JSON-Based Language Manager**: Implemented `LanguageManager.kt` to load string dictionaries from `assets/languages/content_XX.json`. This replaces hardcoded UI strings with an English-anchored key-value system.
*   **Reactive UI Translations**: Created a `@Composable` `.tr` String extension mapping directly to a `StateFlow` of the current language. The entire UI now instantly translates without requiring an Activity restart.
*   **Community Translation Ready**: The app now supports drop-in JSON files for immediate multi-language support (starting with English and Hungarian).

## Completed Changes (2026-07-05)

### 1. UI/UX Polish: Notifications, File Attachments & Contacts Fixes
*   **Ghost Contacts & Handshake Race Condition**: Fixed a critical race condition in `HandshakePacketHandler.kt` where duplicate `CONNECTION_REQUEST`, `USER_HANDSHAKE`, or `IDENTITY_UPDATE` packets would overwrite an existing trusted peer, accidentally downgrading their `isTrusted` status or wiping their handle.
*   **DM JSON Payload Fix**: Fixed an issue in `ChatThreadScreen.kt` where replying to an older message would display the raw JSON payload wrapper (`{"content": "..."}`) in the reply context preview instead of the cleanly extracted text.
*   **Notification Management**: Added "Mark All Read" and "Clear All" features to the `NotificationsScreen`. The "Clear All" action is protected by an explicit "Are you sure?" confirmation dialog.
*   **File Attachment Preservation**: Replaced naive `.bin` file extensions in `ChatThreadScreen` and `UnifiedFeedTab` by querying the Android `ContentResolver` for `OpenableColumns.DISPLAY_NAME`, preserving exact original filenames (e.g., `document.pdf`) during P2P transfers.
*   **Native File Export & Broadcast Fixes**: Added `exportToPublicDownloads` in `MediaManager.kt` leveraging `MediaStore.Downloads` on Android Q+ to safely export downloaded mesh file attachments to the user's public Downloads directory. Fixed a Compose UI issue in `FeedCard.kt` where a parent `.clickable` modifier was swallowing touch events, re-enabling the "Save to Device" and "Download File" buttons on mesh broadcast attachments.


### 2. Mesh File Transfer & MIME Type Resolution
*   **Native MIME Type Mapping**: Replaced the hardcoded media type `when` blocks in `MediaManager.kt`, `UnifiedFeedTab.kt`, and `ChatThreadScreen.kt` with Android's system-wide `MimeTypeMap.getSingleton()`. This allows NoSlop to natively recognize and preserve exact file extensions (e.g., `.pdf`, `.zip`, `.docx`, `.apk`) during both upload and download.
*   **File Transfer Stall Fixed**: Fixed a critical bug where general file transfers over the mesh network stalled indefinitely at "Connecting..." (0% progress). Because uploads were previously defaulting to `application/octet-stream`, the sender's local storage mapping failed to match incoming `MEDIA_REQUEST` chunk requests. Accurately stamping the `MediaMetadata` with the correct MIME type allows peers to locate the file blocks and immediately resolves the transfer gridlock.

*   **Historical Sync Media Type Loss**: Fixed a bug in `SyncPacketHandler.kt` where `clearnetMediaType` was omitted from the database insert during `SYNC_RESPONSE` handling. This caused historical clearnet audio/video shares to incorrectly render as plain articles for newly joined peers, while live peers received the correct type.

*   **Mesh Edits & Signature Integrity**: Fixed a critical bug in `PostPacketHandler` and `Daos.kt` where editing a post updated the text string but retained the original cryptographic signature and timestamp. This caused historical syncs (`INVENTORY_SYNC_REQUEST`) to permanently fail signature verification (`CryptoService.verify`) for peers pulling the edited post. Edits now generate and store a fresh timestamp and signature. Added `EDIT_COMMENT` and `DELETE_COMMENT` packets to the network protocol with full repository support.

*   **Clearnet-to-Mesh Sync & Media Fixes**: Fixed an issue where the initial reaction triggering a clearnet share was blocked by the outgoing filter, causing peers to see the share without the context. Fixed an issue where commenting on an unshared clearnet item failed to generate the anchor post. Fixed a bug where shared clearnet images rendered as black screens due to loading the webpage URL instead of the high-res thumbnail. Fixed an ExoPlayer bug where shared YouTube embeds failed to autoplay because the player was waiting for a native video frame to clear the Base64 thumbnail.

## Completed Changes (2026-07-04)

### 1. Invidious Network Latency & Fast-Failing
*   **Lock-Free Registry Cache**: Removed a critical `@Synchronized` bottleneck in `InvidiousApiClient.getInstances()` and replaced it with a non-blocking `AtomicBoolean`. Background instance registry fetches no longer freeze the UI thread while searching. Failed registry lookups now correctly cache the hardcoded fallback list instead of hanging on every keystroke.
*   **Strict Search Timeouts**: Swapped the underlying OkHttpClient for all Invidious search functions (`searchChannels`, `searchVideos`, `getTrendingVideos`) to a dedicated `probeClient` with a strict 5-second timeout, bypassing the default 30-second client timeouts.
*   **Proactive Instance Pinging**: Added background asynchronous instance health checks during the `preWarmInstances()` startup phase. The app now silently pings the `stats` endpoint of the top 3 registry servers upon launch, proactively adding dead or blocked nodes to the `instanceFailureTime` blacklist before the user can even initiate a search.
*   **Smart Instance Filtering**: Search functions now strictly filter out any instances present in the cooling-down blacklist *before* attempting network requests, preventing the 5-second timeout penalty from being absorbed repeatedly on successive search queries.

### 2. Video Playback UI Polish
*   **Disabled Default ExoPlayer Artwork**: Explicitly set `useArtwork = false` on the Android `PlayerView` component to prevent ExoPlayer from aggressively flashing its own low-quality generic media icon over the Coil high-res thumbnail before the first frame is buffered.
*   **True Frame Rendering Transitions**: Re-wired the `isVideoReady` state to rely strictly on the `onRenderedFirstFrame()` decoder callback rather than the premature `STATE_READY` status. This guarantees the Coil thumbnail overlay remains solid until actual video pixels are ready to display.
*   **YouTube IFrame API Sync**: Rewrote the fallback `EmbedWebViewPlayer` (used when Invidious resolution fails). Removed the blind 800ms timer that was prematurely hiding the thumbnail. Injected the official **YouTube IFrame API** and a custom Android `JavascriptInterface`, forcing the Android UI to wait for the JavaScript engine's explicit `PLAYING` state before dismissing the high-res thumbnail.

### 3. Feed Recency Optimization
*   **Chronological Feed Scoping**: Appended the `&date=month` parameter to all `searchVideos` API calls in `InvidiousApiClient.kt`. While the API still sorts by relevance by default, this strict date filter prevents the feed from surfacing extremely old videos (5-10 years ago) simply because they have higher overall view counts, ensuring the unified feed remains focused on current month/year content.

## Completed Changes (2026-07-02)

### 1. Identity Display & Backup Enhancements
*   **Handle Dot Parsing Fix**: Updated payload generation and packet handling logic (`MeshSocialRepository`, `HandshakePacketHandler`) to properly extract handles using trailing delimiters (`substringBeforeLast`). Usernames containing dots (e.g., `satoshi.nakamoto`) are no longer prematurely truncated.
*   **Encrypted Zip Export/Import (SAF)**: Fully wired up the "Export/Import Profile" buttons in the Settings Tab. Implemented Android's Storage Access Framework (`ActivityResultContracts`) allowing users to natively choose where to save or open the encrypted `noslop_backup.zip`. Added a UI dialog to securely capture the user's Mnemonic password for AES derivation prior to reading/writing IO streams via `BackupManager`.
*   **Cleaner Identity UI**: Scrubbed the cryptographic `.tripcode` suffix from primary user feeds and chat headers (`PeerItem`, `UnifiedFeedTab`, `ChatThreadScreen`) for a cleaner modern aesthetic, while preserving the full `handle.tripcode` hash inside the detailed ContactCardDialog and Onboarding Identity Card for verification.

### 2. UI/UX Refinements & Bug Fixes
*   **Reaction Layout Alignment**: Fixed an issue in `MediaComponents.kt` where wrapped reaction pills would misalign the primary action buttons (React, Share, Chat). The main action buttons are now strictly right-aligned in their own container independent of the reaction pills row width.
*   **Import Destructive Warning & Restart**: Upgraded the Import Backup flow in `SettingsTab` to include a mandatory destructive warning dialog, notifying the user that their current data will be wiped. On confirmation, the system now safely closes the active Room Database (`NoSlopDatabase.closeInstance()`) before overwriting it via `BackupManager`, and then actively triggers a full application intent restart (`FLAG_ACTIVITY_CLEAR_TASK`) to safely reload the new state.
*   **Mesh Broadcast Privacy**: Removed the cryptographic `.tripcode` suffix from the author string on Mesh Broadcast cards across `MainScreen`, `FeedCard`, and `UnifiedFeedTab` to improve privacy and visual clarity.
*   **Interactive User Info Modal**: Added an interactive `clickable` modifier to the Avatar and Username row on Mesh Broadcast cards. Tapping now opens a `User Info Modal` displaying the user's enlarged Avatar, full Handle, Tripcode, and abbreviated Public Key.
*   **Tearing Face Reaction Bug**: Fixed a logic bug in `MeshSocialRepository.kt` where emotional reactions like `sad` were incorrectly grouped with negative moderation signals (`downvote`, `angry`). This previously prevented `sad` from functioning as the initial reaction on a Clearnet post, as the system blocked negative signals from initializing anchor posts to prevent spam.

## Completed Changes (2026-07-01)

### 1. Granular Mesh Broadcast Filters
*   **User-Facing Filter UI**: Added a new **Mesh Filters** screen (`MeshFiltersScreen.kt`) accessible from Settings → Account & Preferences → Mesh Filters. Provides 12 toggle switches (6 content types × 2 directions) controlling which packet types are pushed to and pulled from the mesh network:
    *   Reactions (default: **off** for both directions to reduce noise)
    *   Comments (default: on)
    *   Text Posts (default: on)
    *   Clearnet Shares (default: on)
    *   Image Posts (default: on)
    *   Video Posts (default: on)
*   **Local-First Architecture**: Filters operate exclusively at the network sync layer. All user actions (reactions, comments, votes) are **always persisted locally** regardless of filter settings — only the `GossipService.broadcast()` / `MeshTransport.sendPacket()` calls are conditionally gated.
*   **"Already Shared" Exemption**: Incoming reactions, comments, and votes targeting posts or comments that already exist in the local database are **exempt** from incoming filters. This ensures engagement on tracked content is never silently dropped, while still filtering noise from unknown/unbridged anchor posts.
*   **Clearnet Bridging Guard**: When a user likes a clearnet feed item for the first time, NoSlop creates a deterministic anchor post and a reaction simultaneously. The `allowOutgoingReactions` filter only blocks the reaction broadcast during this initial bridging step (`isBridging = true`). Reactions on posts already present in the local database always broadcast freely.
*   **DM Chat Fully Exempt**: Direct message chats and their reactions (`CHAT_REACTION` packets) are completely excluded from all mesh filter logic — both incoming firewall checks and outgoing broadcast gates.
*   **Persistence**: Filter settings are stored as JSON in the `app_settings` table via `SettingsRepository` and exposed reactively via `StateFlow` through the ViewModel.
*   **Files**: New: `MeshFilterSettings.kt`, `MeshFiltersScreen.kt`. Modified: `SettingsRepository.kt`, `NoSlopViewModel.kt`, `GossipService.kt`, `MeshSocialRepository.kt`, `NoSlopRepository.kt`, `NoSlopApp.kt`, `SettingsTab.kt`, `Daos.kt`.

## Completed Changes (2026-06-30)

### 1. Tor Daemon Isolation & Port Decoupling
*   **Embedded Tor Decoupling**: Fixed a critical EADDRINUSE conflict that occurred when running debug and release builds side-by-side on the same device. Both builds were previously attempting to spawn internal Tor daemons bound to hardcoded ports (`9050`/`9051`). Extracted these into build variant configs (`TOR_SOCKS_PORT` and `TOR_CONTROL_PORT`) so the debug and release builds now run completely isolated Tor instances (`9050`/`9051` vs `9052`/`9053`).
*   **Tor Service Double Registration**: Fixed a race condition where both the Tor daemon status broadcast receiver and the self-healing bootstrap loop concurrently called `triggerRegistration()` when Tor finished bootstrapping. This prevented the daemon from spamming `550 Onion address collision` and throwing `Bad sequence size: 3` exceptions during startup.

### 2. Mesh Transport Circuit Timeouts
*   **Extended Connection Timeout**: Increased the `CONNECTION_REQUEST` Tor SOCKS5 connect timeout from 20 seconds to 60 seconds. Freshly minted Tor v3 onion services (such as new installs) can take up to 45 seconds to publish their descriptors to the HSDirs. A 20-second timeout was actively interrupting Tor's circuit-building process before completion, dropping the connection and triggering an infinite retry loop that prevented peers from ever successfully shaking hands.

### 3. UX Polish & GitHub Issue Integration
*   **Video Playback Persistence**: Fixed a regression where videos restarted from the beginning. `ExoVideoPlayer` was rearchitected to avoid state race conditions by seeking to the saved position immediately before calling `prepare()`. Added strict `> 0L` guards to prevent transient loading states from overwriting valid saved positions in the `PlaybackPositionStore`.
*   **Live Feed Refresh**: Replaced the redundant "Random" button in the filter modal with a "Refresh Feed" button that actively clears and rebuilds the feed locally from the database without a network trip, respecting seen/swiped exclusions. Made the modal scrollable to fix UI clipping on smaller screens.
*   **Video Thumbnail Bleed & Letterboxing**: Fixed YouTube thumbnail letterboxing by setting the primary thumbnail to `ContentScale.Fit` and layering a 1.35x scaled, 24dp blurred background image behind it (clipped to the container bounds) to fill the empty space without bleeding over the UI.
*   **GIF Support in DMs**: Integrated a global GIF-aware Coil `ImageLoader` and wrapped the chat UI in a `CompositionLocalProvider`. GIFs sent in Direct Messages now render and animate inline seamlessly instead of displaying as static thumbnails.
*   **Native GitHub Issue Reporting**: Upgraded the "Bug Report" flow from a raw browser intent to a native GitHub REST API submission tool. It now automatically pulls `GITHUB_PAT` and `GITHUB_ASSIGNEE` from `local.properties` (exposed via `BuildConfig`), allows users to select an Issue Type (Bug, Feature Request, Question), and automatically formats and attaches their device hardware info and recent app logs to the GitHub issue.

## Completed Changes (2026-06-29)

### 1. Bouncy Castle Migration & Lazysodium Key Generation
*   **Unified Bouncy Castle Signing**: Completely migrated `CryptoService.kt` to use Bouncy Castle's lightweight `Ed25519Signer` directly, bypassing the platform JCA `Signature` API. This unifies signing across all Android versions (API 24-35).
*   **Lazysodium Key Generation**: Added `lazysodium-android` as the primary key generator for Ed25519, ensuring high-quality keys consistent with iOS and other platforms. Includes a Bouncy Castle fallback if the JNA native library fails to load.
*   **ProGuard Fixes**: Added explicit keep rules for `com.goterl.lazysodium.**` and `com.sun.jna.**` to prevent release builds from crashing due to R8 stripping native method bindings.

### 2. DM Chat UI & Navigation Fixes
*   **NavigationBar Keyboard Fix**: Fixed a visual glitch where the bottom navigation bar remained behind the keyboard when typing in a DM, exposing an empty black space. The `NavigationBar` and `FloatingActionButton` are now explicitly hidden when the user is in an active chat thread (`selectedPeerPub != null`).
*   **Hardware Back Button Navigation**: Implemented a Compose `BackHandler` in `DMsTab`. Pressing the phone's hardware back button while in a chat now properly returns to the contacts list instead of minimizing the entire application.

## Completed Changes (2026-06-25)

### 1. Video Thumbnail & Preload State Fixes

### 2. Tor Identity Unification & Semaphore Gridlock Fix

### 4. Reverted Tor Identity Bug & Mitigated Semaphore Gridlock
*   **Tor Identity Reverted**: The previous "fix" for Tor ED25519-V3 math was fundamentally incorrect. Tor Control Port (`ADD_ONION ED25519-V3`) explicitly expects the 64-byte expanded secret key (clamped secret scalar + PRF secret), not the 64-byte libsodium format (seed + public key). By passing the seed, Tor recomputed a completely different public key and onion address, breaking all peer connectivity. Reverted `CryptoService.kt` to the correct SHA-512 clamped expansion.
*   **Semaphore Queue Prioritization**: The `Semaphore(8)` in `MeshTransport` was causing infinite queuing gridlocks because background `ANNOUNCE_PEER` heartbeats to offline peers consumed all 8 permits and took up to 60s to timeout, completely starving user-initiated actions (like `CONNECTION_REQUEST`). Background packets now use `tryAcquire()` and are aggressively dropped if Tor circuits are saturated, keeping the queue free for essential actions.

### 3. Mesh Transport Fast-Fail for Unreachable Peers
*   **Dead Peer SOCKS Rejection**: Added a fast-fail check in `MeshTransport.kt`. If the Tor proxy explicitly rejects a connection with `SOCKS: Host unreachable`, `TTL expired`, or a general failure, the transport layer now instantly aborts the send instead of stubbornly executing the remaining retries and holding up the `torSemaphore`. This keeps the mesh pipeline fluid when a trusted peer is genuinely offline or has changed their onion address.
*   **Tor ED25519-V3 Math Fixed**: Corrected a severe mathematical split where the Kotlin `CryptoService` was deriving a completely different onion address than the internal Tor C-daemon. Tor expects the `libsodium` secret key format (32-byte seed + 32-byte public key), but we were passing it a clamped SHA-512 expansion which Tor then re-hashed, resulting in a misaligned identity.
*   **Semaphore Gridlock Resolved**: Drastically reduced `MeshTransport` SOCKS5 connect timeouts from `45s` (with 5 retries) down to `20s` (with 3 retries). This prevents offline peers from hoarding the `torSemaphore` limits and gridlocking the entire gossip network for 4+ minutes per offline node.
*   **Thumbnail Visibility Bug**: Fixed an issue where video thumbnails would permanently stay on screen (blocking the playing video) when returning to the feed. Added the missing `onReady()` state callbacks to the fallback (non-preloaded) `ExoPlayer` initialization block.
*   **Lifecycle Thumbnail Reset**: Bound the `isVideoReady` state to the `activeVisible` lifecycle. When a user tabs away and the `VideoPlayer` unmounts, the readiness state is properly reset to `false`. This prevents the thumbnail from disappearing prematurely when tabbing back before the video has actually buffered its first frame.



## Completed Changes (2026-06-23)

### 11. Search, Media Preloading, and Feed Memory Polish
*   **Search Routing & Filtering**: Re-wired the search query dispatcher in `PublicApiService.kt` to explicitly route queries to search endpoints (e.g., "Search Videos") rather than falling back to trending lists. Enforced strict keyword matching on the UI side to filter out unrelated API fallback content.
*   **Media Type Classification**: Fixed a bug in `FeedParser.kt` where RSS `<enclosure>` or `<media:content>` tags intended as thumbnails were incorrectly promoted to standalone image posts, ensuring articles always render in the `SegmentedArticleReader`. Fixed Clearnet-to-Mesh broadcasts not properly falling back to `clearnetUrl` for media resolution, restoring native playback for shared videos and audio.
*   **Feed Memory & Interleaving**: Stopped the background sync from violently prepending new content to the top of the feed and breaking scroll state. Implemented feed memory pruning: leaving a search/filter now keeps the 3 previous slides, discards deeply scrolled history to save memory, and gracefully interleaves fresh content immediately below the user\'s saved position.
*   **Interaction Jump Bugs**: Fixed a jarring bug where "Liking" a clearnet post caused the feed to jump to the next item by removing the strict `!isSaved` UI filter rule. Prevented the feed from forcefully scrolling to the top when broadcasting a mid-feed share.
*   **Aggressive Splash Preloading**: Hijacked the app\'s initial `SplashScreen` to act as a 4-second buffer window. `MainActivity` now aggressively resolves and pre-warms the first media item in the feed via `PreloadManager` before the splash curtain drops, resulting in instant playback.
*   **Filter Synchronization**: Introduced a strict `syncFilterMode()` flow to prevent the ViewModel from getting stuck in specific filters (like "Articles") when the UI clears them via the \'x\' button, ensuring seamless return to the "Live Feed" regardless of list size.

### 10. Tor AIMD Tuning, UI Recomposition & Mesh Sort Fixes
*   **Tor TCP Slow Start**: Dramatically overhauled the `MediaManager` AIMD algorithm to respect Tor\'s circuit latency. Chunk requests now strictly begin at `windowSize = 1.0` and conservatively ramp up to a maximum concurrency of `32.0` (down from `128.0`). This stops the client from immediately choking Tor nodes with massive simultaneous requests on new circuits, allowing mesh media to actually transfer successfully.
*   **ExoPlayer Reloading Loop Fixed**: Removed a severe recomposition bug where the video player would arbitrarily restart every 5 seconds. The `viewedHistoryIds` state listener was improperly bound to the vertical pager\'s memory cache, causing the entire list instance to violently regenerate the moment a video was marked as "viewed" in the background.
*   **Thumb Wiggle Mount Fix**: Adjusted the `isVisible` lifecycle bounds on `VideoPlayer` to rely strictly on `pagerState.currentPage`. Micro-movements of a thumb resting on the screen no longer toggle the `targetPage` state, preventing ExoPlayer from constantly unmounting and remounting.
*   **Mesh Chronological Strictness**: The "TikTok Vibe" initialization logic (which hunts for the first video payload and drags it to the absolute top of the feed) has been explicitly disabled for the "Mesh" and "History" filters, ensuring those lists remain mathematically chronological.

### 9. Mesh Media Transport & UI Navigation Fixes
*   **Mesh Media Integrity**: Fixed a dual-sided bug where `.mp4` and other media chunks were arriving padded with empty zero-bytes, corrupting their structure. The chunking algorithm now correctly dynamically scopes the byte buffer to the remaining file size and utilizes `RandomAccessFile.seek` instead of the unreliable `skip()`. Math mismatch in `chunkCount` evaluation between sender and receiver was also aligned to strict integer floor division.
*   **Feed State Memory on Scroll**: Bound the positional memory logic strictly to the `pagerState.settledPage` event. The Live Feed now dynamically saves your scroll position into the encrypted database seamlessly as you scroll down, rather than only saving when interacting with buttons.
*   **Search Online UI**: Shifted the "Search Online" button in the filter modal to appear directly underneath the text input field for immediate context. It now natively echoes the active search string in its label.

### 1. Android Auto Backup & Keystore Corruption Fix
*   **EncryptedSharedPreferences Crash**: Fixed a critical bug where installing the release version over the debug version (or reinstalling the app) caused a permanent crash loop. `android:allowBackup="true"` was improperly restoring encrypted preference files without their corresponding hardware-backed `MasterKey`.
*   Disabled Android Auto Backup in the KMP manifest to match the legacy app's security model.
*   Added a recovery fallback in `Platform.android.kt` that detects Keystore failures and actively wipes the corrupted preferences to regenerate a clean identity rather than throwing a fatal `GeneralSecurityException`.

## Completed Changes (2026-06-20)







### 8. Feed State Persistence, DM Camera Alignment & Media Fixes
*   **Live Feed Memory**: The app now reliably saves your exact `Live Feed` array and scroll index to the database (`app_settings`) when closed. Restarting the app instantly restores your exact position instead of dropping you into a generic 3-video startup shuffle.
*   **Bottom-Loaded Refresh**: When clearing searches or filters, you are instantly returned to your preserved `Live Feed` position. All newly aggregated chronological content is silently appended to the absolute *bottom* of your list to prevent jarring vertical jumps.
*   **DM Video Playback Fixed**: Found and resolved a critical bug in `MediaManager.kt` where `input.read(buffer)` was randomly returning partial byte arrays. Forced a strict `while` loop to guarantee exactly 256KB are read per chunk, perfectly preventing the `.mp4` structural corruption that triggered ExoPlayer's `s31: Source Error`.
*   **DM Camera UI Upgraded**: Removed the redundant GIF button from direct messages. Unified the DM camera experience with the Broadcast camera, including the `DestructiveRed` immediate-action buttons and the 3-second recording countdown safety.

### 7. UX Polish: Feed Memory, Modal Layout & Camera Countdown
*   **Strict Live Feed Memory**: The app's positional memory (saving your spot in the vertical feed) is now strictly restricted to the main "Live Feed". Browsing specific filters like "History" or "Random" will dynamically start from the top, keeping your core progression intact when returning.
*   **Camera Polish**: Implemented a 3-second countdown sequence for video recording within the Broadcast UI to give users time to prepare. Re-themed all immediate-action camera buttons (Take Photo, Record, Close) to `DestructiveRed` for better visual signaling.
*   **Search & Filter Modal Clean Up**: "Mesh" is now prominently separated from the generic content types and placed immediately below the user's "My Content" toggle. Removed ghost borders around the Random Discover button.

### 6. Background Resource Hoarding & Camera Leaks Fixed
*   **MediaCodec Exhaustion**: Fixed a major hardware resource leak where `ExoPlayer` instances were not being released when the user navigated away from the "Feed" tab. Added an `isActiveTab` state parameter to `UnifiedFeedTab` to explicitly unmount active videos when the tab loses focus, resolving the `MediaCodec error -32` crashes.
*   **Camera Lifecycle Leak**: Fixed a persistent 9-minute hardware camera leak occurring after closing the Broadcast Compose modal. CameraX streams are now strictly unbound from the `ProcessCameraProvider` via `DisposableEffect` the moment the camera view leaves the Compose tree.
### 5. Compose State Fixes & True Creator Priority
*   **True Creator Priority**: Followed creators now completely bypass the diverse interleaving limitations. Any new items from your followed creators are batched and stacked at the absolute top of the feed ahead of mesh posts and general discovery content.
*   **"Shining Through" Shimmer Bug Fixed**: Removed an underlying rogue `LoadingShimmer` from the base layer of `UnifiedFeedTab`. The UI no longer flashes the loading gradient over active videos when database updates (like marking an item read or viewed) trigger micro-recompositions of the player's SurfaceView.
*   **Pager Filter Desync Fixed**: Hard-bound the `VerticalPager` scroll state to the `filterMode`. Switching from Live Feed to "My Content" or "Mesh" now strictly snaps the pager index back to `0`, fixing the "swiping down to go up" bug caused by retained scroll state.
*   **Loading UX Polish**: The Feed screen now displays a proper `CircularProgressIndicator` during the initial launch or when the feed is actively curating, resolving the "waaaay too long" perceived delay where it used to prematurely state "Your feed is empty".
### 4. Feed Performance & Layout Fixes
*   **Staggered Loading Restored**: Initial load times have been slashed. The feed now strictly requests only 3 items on a cold start or filter switch to immediately dismiss the splash curtain, instantly firing a silent background request for the next 10 items.
*   **Strict Creator Priority**: Heavily optimized the chronological feed pull to ensure the user's selected creators override diversity limits, bringing preferred content straight to the top of the feed instead of irrelevant fallback filler.
*   **Non-Destructive Preferences**: Changing content preferences in Settings no longer wipes the active feed history or clears the screen. It seamlessly pulls down updated content in the background.
*   **Mesh & My Content Ordering Bug**: Fixed a jarring layout bug where the Compose Pager incorrectly retained its old index when switching to "My Content", forcing the user to swipe down to find their own items. The feed now actively forces a `scrollToTop` event on filter changes.
*   **Random Discover Button Polish**: Softened the UI of the Random Discover button in the Search modal to match the primary button aesthetics.
### 3. Creator Prioritization, Random Discover, and Mesh Filter Sort Fix
*   **Followed Creators Up Front**: Overhauled the feed algorithm so that content matching the user's selected creators (from onboarding or settings) is strongly prioritized and pushed to the very front of the chronological list across "Live Feed", "Videos", "Images", and "Articles" modes.
*   **Mesh Sort Order Fix**: Fixed the "Mesh" filter where posts were loading backwards. Specific lists like "Mesh" and "History" are now strictly chronologically ordered and skip the random shuffling that is applied to mixed feeds on initial load.
*   **Random Discover Mode**: Repurposed the "Refresh Feed" button into a new "Random Discover" mode. This mode bypasses chronological and creator sorting, fetching an entirely random shuffle of unseen content to help break filter bubbles.
### 2. Smart Feed Interleaving & Strict Deduplication
*   **Feed Repetition Bug**: Fixed an issue where the Live Feed would routinely serve old, repeated, or randomly shuffled content instead of the most recent synced items.
*   **Stale Card Banishing**: The feed now aggressively filters out `isRead = true` items from the Live Feed. Once a user dwells on a card, it is permanently banished from their main feed across app restarts, preventing stale clogging.
*   **Smart Media Ratios**: Rebuilt the feed algorithm to chronologically sort and seamlessly interleave ~5 videos, 1 image, 1 audio, 1 article, and 2 mesh posts per scrolling batch. 
*   **Source Limits**: Integrated a `takeDiverse` function to mathematically prevent any single RSS source from dominating a batch (max 2 items per source per page).

### Kotlin Multiplatform (KMP) Migration - Phase A (Feeds)
*   **Architecture Shift**: The NoSlop canonical codebase is moving from the legacy Android `app/` module to the new Kotlin Multiplatform `mvp/` module. The `app/` module is now read-only reference until it is retired.
*   **Networking Layer**: Unified the networking layer using Ktor `HttpClient` (with OkHttp on Android, Darwin on iOS) across all feed integrations.
*   **Feed Parser**: Ported `FeedParser.kt` to `commonMain`, replacing Android-specific XML dependencies with multiplatform `xmlutil`.
*   **API Clients Ported**:
    *   Successfully ported `HackerNewsApiClient`, `RedditApiClient`, `InvidiousApiClient`, `PodcastIndexApiClient`, `GuardianApiClient`, `NewsApiClient`, `WikimediaApiClient`, `JamendoApiClient`, `NasaApiClient`, `PexelsApiClient`, `VimeoApiClient`, and `InternetArchiveApiClient` to `commonMain`.
    *   All clients are now 100% deterministic and golden-tested using Ktor `MockEngine`.
*   **Background Sync**: Implemented `BackgroundScheduler` interface using `expect/actual` pattern (`WorkManager` for Android, `BGTaskScheduler` for iOS) for cross-platform background feed synchronization.
*   **Crypto & Dates**: Migrated hashing (`kotlincrypto-sha1`) and date parsing to KMP native implementations (`kotlinx-datetime`).
*   **Media Synchronization**:
    *   **Inline GIF Transport**: Gboard GIF attachments in comments are now embedded directly as base64 `data:` URIs (`noslop-gif://data:image/gif;base64,...`) avoiding mesh chunking overhead for small animated images.
    *   **Native Auto-Rendering**: DM attachments now correctly verify if a file exists locally via `MediaManager.isMediaDownloaded()`, enabling instantaneous native rendering (AsyncImage, VideoPlayer) without freezing in a "Tap to Download" state.
    *   **Mesh Routing Fixes**: Corrected `PostPacketHandler` and `SyncPacketHandler` to resolve authentic `.onion` peer addresses from the internal database instead of utilizing raw sender public keys when triggering automated chunk downloads.

## Completed Changes (2026-06-14)

### 0. HUBs Rebranding & Home Hub Vision
*   Renamed "HAI-Net" tab to "HUBs".
*   Added "HAI-Net (coming soon)" indicator to the HUBs page.
*   Documented the vision for Home HUBs as the primary backup for mesh Identity, data, and media.

### 1. Mesh Filtering & Notification Deep-links
*   Verified self-post filtering in `NoSlopViewModel.kt`.
*   Confirmed `ensurePostInFeed()` handles notification deep-links correctly by bypassing filters.

### 2. RSS Content Classification
*   Fixed `FeedParser.kt` to prevent RSS articles with embedded images from being promoted to "Image" media type.
*   Preserved extracted images as `thumbnailUrl` for use in the new article hero layout.

### 3. Wikimedia Commons Integration
*   Added `WikimediaApiClient.kt` to fetch featured pictures from Wikimedia Commons.
*   Added "Wikimedia Featured" as a built-in API source in `SourceLibrary.kt`.
*   Integrated into `PublicApiService.kt` under "Photography" and "Art" categories.
*   No API key required for this source.

### 4. Article Card Redesign & Pagination Fix
*   Implemented a rich hero layout for articles in `SegmentedArticleReader` (`MediaComponents.kt`).
*   **Fix**: Migrated to `HorizontalPager` for sideways swiping between article segments.
*   **Fix**: Removed vertical scrolling from segments to ensure they always fit the viewport and vertical "swipe away" gestures work reliably.
*   Added a "Read Full Article" button on the final page of every article.

### 5. Media Playback & Source Reliability
*   **Fix**: Resolved YouTube "Error 153" (Video Player Configuration Error) by strictly aligning the `origin` parameter in the embed URL with the `baseURL` provided to the WebView's `loadDataWithBaseURL` method.
*   **Fix**: Added `mute=1` to the YouTube embed to ensure `autoplay=1` is respected by modern browser security policies.
*   **Fix**: Updated YouTube embed logic in `VideoPlayer.kt` with a modern Mobile User-Agent and optimized iframe parameters (`origin`, `enablejsapi`, `rel=0`).
*   **Fix**: Refreshed Invidious API fallback instances with healthy servers.
*   **Fix**: Cleaned up dead RSS sources in `SourceLibrary.kt` (Self-Hosted Hero, Threatpost, etc.).

### 6. Article UX Refinements & Content Classification
*   **Fix**: Updated `GuardianApiClient` and `NewsApiClient` to ensure their articles are classified as "Article" type (`mediaType = null`) instead of being promoted to "Image" status.
*   **Fix**: Articles with missing content now correctly fall back to excerpts.
*   **Fix**: Added a high-quality default fallback hero image for articles without thumbnails.
*   **Fix**: Improved article pagination state to always show a content page with the "Read Full Article" button even when no text is extractable.

### 7. Handshake Reply Notifications
*   Added logic to notify the sender of a connection request when it is accepted (`USER_HANDSHAKE`).
*   Introduced new packet type `CONNECTION_REJECTED` to notify the sender if their request is declined, safely removing the pending peer and displaying a local notification.

### 8. Feed OOM Prevention & Settings Build Fix
*   **Settings Build Error Fix**: Added missing `Search` and `Close` icon imports in `ContentPreferencesScreen.kt` introduced by the new creator search bar.
*   **Auto-Play Video Player with OOM Prevention in Feed**: Fixed a fatal `OutOfMemoryError` (MediaCodec buffer exhaustion) when swiping through the vertical video feed. `FeedCard.kt` now conditionally mounts the `VideoPlayer` only when the item is fully visible on screen. This preserves the immersive auto-play experience while ensuring off-screen ExoPlayer instances are instantly destroyed to free up hardware resources.

*   **Creator Search Fix**: Moved the `InvidiousApiClient.searchChannels` network call off the Main Thread to `Dispatchers.IO` to prevent silent `NetworkOnMainThreadException` failures. Capped the appended search results to the top 3 matches as intended.
*   **SD Card Installation Support**: Added `android:installLocation="auto"` to the AndroidManifest to allow the app to be installed or moved to an external SD card on storage-constrained devices.
## Pending / Future Work
*   Add more no-auth image and video sources.
*   Enhance WebView with ad-blocking or reader mode if possible.
*   Further optimize preloading for diverse media types.

## Completed Changes (2026-06-19)

### 1. Onboarding & Categories Refinement
*   **Auto-Included Sources**: Removed "Video Platforms" and "Social Clearnet" from the user-facing category selection list in Onboarding and Settings since these are essential and always included by the pipeline.
*   **Expanded Genres**: Added more diverse genre options to the Music and Video categories in `SourceLibrary.kt` to broaden content discovery.
*   **Creator Cloud Layout**: Replaced the fixed chunking layout with a responsive `FlowRow` in both Onboarding and Settings, preventing creator names from being truncated.
*   **Suggested Feeds UI**: Removed the search field from the "Suggested Clearnet Feeds" slide to streamline the onboarding experience.

### 2. Media Player & Feed Immersiveness
*   **Landscape Auto-Hide UI**: Implemented an immersive feed view when the device is in horizontal orientation. The top status overlays (notifications, search), bottom navigation bar, right-side interaction icons (like, share, comment), and bottom-left author details automatically slide off-screen after 1 second of inactivity. Tapping the screen instantly restores them.
*   **Edge-to-Edge Media**: Updated the main Scaffold's `innerPadding` to dynamically animate to `0dp` when the landscape UI auto-hides, allowing video and image content to fully stretch into the freed navigation bar space.
*   **ExoPlayer Resize Mode Fix**: Updated `VideoPlayer.kt` to unconditionally use `RESIZE_MODE_FIT` (instead of `RESIZE_MODE_ZOOM` in landscape). This prevents the media from becoming artificially oversized and cropped at the edges when playing in horizontal orientation.

### 3. QR Mesh Scanner Enhancements
*   **Gallery Selection Fix**: Resolved an issue where the "Select from Gallery" button was completely hidden beneath the camera preview layer (`AndroidView`). The layout was restructured into a `Column`, placing the gallery picker safely below the camera area, making it universally visible and functional.

---

**Related docs**: [GAP_ANALYSIS.md](GAP_ANALYSIS.md) for the longer-term feature backlog vs. gChat/HAI-Net · [TECHNICAL_REFERENCE.md](TECHNICAL_REFERENCE.md) for how these changes fit into the overall architecture · [HUB_INTEGRATION_PLAN.md](HUB_INTEGRATION_PLAN.md) for the next major planned phase of work.


## Completed Changes (2026-06-21)

### 1. Media Routing & Tor Congestion Control
*   **AIMD Mesh Tuning**: Fixed a race condition where `MediaManager` would shrink the congestion window and trigger timeouts (5s) much faster than `MeshTransport` could build Tor circuits (up to 30s). 
*   **Mesh Relay Fallback**: Wired `MeshTransport`'s delivery status back into `MediaManager`. If a node is unreachable after consecutive timeouts, the download immediately triggers `attemptMeshRecovery()` via the Gossip Protocol to find alternative seeders on the mesh, rather than hanging indefinitely.
*   **MediaProxyService Reliability**: Updated the proxy loop to monitor `MediaManager`'s recovery states, allowing it to gracefully terminate HTTP streams if a mesh transfer fundamentally fails, preventing the UI/WebView from hanging in a permanent loading state.

### 2. Archive.org ExoPlayer Native Streaming
*   **WebView Bypass**: Fixed an issue where Archive.org videos (`.mp4?cnt=0`) were being pushed to the unreliable `EmbedWebViewPlayer` due to complex URL queries.
*   **Direct Stream Resolution**: `VideoPlayer.kt` now natively intercepts Archive.org URLs, calls the public `metadata/` API to find the underlying raw `.mp4` file, and feeds it directly to `ExoPlayer` for seamless, native playback.

### 3. Clearnet-to-Mesh Media Bridging
*   **Confirmed**: Videos from the Clearnet Aggregator successfully bridge over to the mesh network. Broadcasting a video post triggers the proper chunking and relay propagation over Tor.

### 4. DM Chat Performance & OOM Prevention
*   **Lazy Video Player Initialization**: Fixed a severe `OutOfMemoryError` (`MediaCodecBridge.getInputBuffer`) that occurred when opening a DM chat history containing multiple downloaded videos. 
*   `ChatThreadScreen.kt` now renders lightweight Coil thumbnails with a play button overlay for downloaded videos in the `LazyColumn`. Heavy `VideoPlayer` (WebView/ExoPlayer) components and their associated hardware `MediaCodec` allocations are strictly deferred until the user explicitly taps the thumbnail to begin playback.

### 5. Hardware Capture & Layout Polish
*   **CameraX Audio Enforcement**: Fixed a bug where in-app recorded videos lacked audio tracks. `MediaCaptureManager` now aggressively attempts to bind `withAudioEnabled()` and gracefully catches `SecurityExceptions` if permissions are explicitly denied, rather than silently failing the `ContextCompat` context check.
*   **QR Scanner Form Factor Support**: Fixed a layout bug on smaller devices where the "Select from Gallery" and "Paste Raw" buttons fell off the bottom of the Dialog screen. The buttons are now safely overlaid inside the Camera viewfinder bounds, mimicking native camera apps and ensuring 100% visibility regardless of screen height.

### 6. Video Audio, QR UI & Search Fixes
*   **Audio Recording Fixed**: Added `android.permission.RECORD_AUDIO` to the `AndroidManifest.xml` which was mysteriously missing, allowing the OS to actually grant the permission to the `MediaCaptureManager`.
*   **QR Scanner Buttons Fixed**: Shifted the "Gallery" and "Paste Raw" buttons into the center HUD column right underneath the QR scanning boundary, escaping the bottom edge that gets cut off on smaller screens.
*   **Mesh Search Results Fixed**: Updated the pagination logic in `NoSlopViewModel.loadMoreFeedItems()` to pre-filter mesh posts by the active search query. This prevents the ViewModel from paginating non-matching items that the UI promptly hides, fixing the bug where the feed appeared "empty" and refused to scroll.

### 7. Runtime Permissions & Final UI Polish
*   **Audio Capture Runtime Check**: Fixed the final hurdle with silent videos. The camera launcher in `UnifiedFeedTab.kt` was bypassing the microphone runtime permission prompt if the camera permission was already granted (e.g., from earlier QR scanning). It now strictly enforces both `CAMERA` and `RECORD_AUDIO` checks before opening the video capture UI, triggering the OS prompt correctly and ensuring videos always have sound.

### 9. Interaction, Media & Networking Fixes
*   **Search Clear Race Condition**: Removed the `isRefreshingFeeds` guard in `clearSearchAndRestoreFeed()` so tapping the 'x' reliably clears the feed filters and instantly restores the user's scroll state.
*   **Global GIF Support**: Injected Coil's `GifDecoder`/`ImageDecoder` factories into the custom `LocalImageLoader` provided by `UnifiedFeedTab`, instantly fixing static GIF rendering across the feed, DMs, and comments.
*   **Mesh Broadcast Media URLs**: Fixed a bug where missing `originNode` declarations in mesh posts defaulted to the raw `PublicKey` instead of resolving the local peer's `onionAddress`. Videos now route correctly over Tor to the proxy service.
*   **Comment Media Sync**: Added synthesized `MediaMetadata` extraction and explicitly bound it to `MediaManager.checkAndAutoDownload()` inside `CommentPacketHandler.kt` so media attached to comments actively syncs to receiving peers.
*   **OkHttp Connection Leak**: Fixed a critical leak in `WikimediaApiClient.kt` where `response.body` was never closed on API read errors, ensuring connection pool integrity.

### 10. Advanced UI, Filtering & Interaction Polish (2026-06-22)
*   **DM Fullscreen Media**: Tapping images or videos in Direct Messages now opens a full-screen zoomable image dialog or a full-screen video player overlay.
*   **Feed Video "Tap to Download"**: Mesh videos on the main feed now accurately check `MediaManager.isMediaDownloaded()`. If absent, they display a rich "Tap to Download" overlay with a live progress indicator, preventing black screens.
*   **Clearnet Engagement Shadow-blocking**: Negative reactions (downvote, angry, sad) on unsynced clearnet items are now intercepted. The action is dropped locally and blocks the item from being broadcasted to the mesh to prevent spamming peers with disliked content.
*   **Rich Share Modal**: Tapping "Share" on a clearnet item now opens the main Compose Modal instead of a basic alert. The shared item is embedded as a rich preview attachment, allowing the user to type custom context before broadcasting.
*   **"My Content" Filter & Feed Isolation**: The user's own mesh broadcasts are now globally excluded from the Live Feed, Video, Audio, and Image filters. They are securely isolated into a dedicated "My Content" toggle in the Search & Filter modal.
*   **Reaction Menu UX**: Added `120.dp` bottom padding to `LazyColumn`s in Chat and Comment sheets, ensuring long-press reaction popups are never clipped or hidden beneath the bottom input bar.
*   **Search Clear UX**: Fixed a race condition in `NoSlopViewModel.clearSearchAndRestoreFeed()` that prevented the feed from restoring its state when clearing a search query.

### 11. Search, Media Preloading, and Feed Memory Polish (2026-06-22)
*   **Search Routing & Filtering**: Re-wired the search query dispatcher in `PublicApiService.kt` to explicitly route queries to search endpoints (e.g., "Search Videos") rather than falling back to trending lists. Enforced strict keyword matching on the UI side to filter out unrelated API fallback content.
*   **Media Type Classification**: Fixed a bug in `FeedParser.kt` where RSS `<enclosure>` or `<media:content>` tags intended as thumbnails were incorrectly promoted to standalone image posts, ensuring articles always render in the `SegmentedArticleReader`. Fixed Clearnet-to-Mesh broadcasts not properly falling back to `clearnetUrl` for media resolution, restoring native playback for shared videos and audio.
*   **Feed Memory & Interleaving**: Stopped the background sync from violently prepending new content to the top of the feed and breaking scroll state. Implemented feed memory pruning: leaving a search/filter now keeps the 3 previous slides, discards deeply scrolled history to save memory, and gracefully interleaves fresh content immediately below the user's saved position.
*   **Interaction Jump Bugs**: Fixed a jarring bug where "Liking" a clearnet post caused the feed to jump to the next item by removing the strict `!isSaved` UI filter rule. Prevented the feed from forcefully scrolling to the top when broadcasting a mid-feed share.
*   **Aggressive Splash Preloading**: Hijacked the app's initial `SplashScreen` to act as a 4-second buffer window. `MainActivity` now aggressively resolves and pre-warms the first media item in the feed via `PreloadManager` before the splash curtain drops, resulting in instant playback.
*   **Filter Synchronization**: Introduced a strict `syncFilterMode()` flow to prevent the ViewModel from getting stuck in specific filters (like "Articles") when the UI clears them via the 'x' button, ensuring seamless return to the "Live Feed" regardless of list size.


### 3. Global Connectivity & Native Hub Tor Daemon
*   **Active-Passive Identity**: Updated Mobile `TorService.kt` to disable its local Hidden Service broadcast when connected to a Hub, pivoting purely to an outbound SOCKS5 proxy.
*   **Native Hub Tor Generation**: Upgraded `hainet-seed` to dynamically generate a standard `/var/lib/tor/hainet/hs_ed25519_secret_key` from the PKCS#8 Mobile Identity Clone, clamping and hashing the seed natively in Rust.
*   **Unified Onion Routing**: Configured the Hub's `/etc/tor/torrc` to expose both Port 9999 (Mesh Gossip) and Port 8080 (REST API) under the single, persistent `.onion` address, allowing NoSlop to securely hit the API from anywhere in the world.

## Next Steps (Planned)
*   **Global Onion Connectivity**: Transition NoSlop to use the Hub's public `.onion` address as the primary endpoint when the local LAN IP is unreachable.
*   **Deep Data Sync**: Synchronize Contact lists, trusted peer statuses, and DM histories between Room (Mobile) and the Hub's master database.
*   **Social Feed Mirroring**: Allow the Hub to serve the Mobile app's preferred RSS/Mesh feed content over the authenticated REST API.
