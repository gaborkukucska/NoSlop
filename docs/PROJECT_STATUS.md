# NoSlop — Project Status (v0.1)

NoSlop is a **privacy-first Android application** that combines an immersive, TikTok-style vertical media feed with a serverless encrypted social mesh. It aggregates content from the open web (RSS/Atom feeds, YouTube, Archive.org, Jamendo music, and public APIs) without trackers or algorithmic manipulation, while providing end-to-end encrypted peer-to-peer messaging over Tor. All identity, data, and cryptographic keys are stored exclusively on the user's device — no accounts, no servers, no phone numbers. NoSlop is built for the **[HAI-Net](https://hai-net.com)** decentralized initiative and the **[People Power Initiative](https://pplpwr.me)**.

### Core Capabilities
- **Immersive Feed**: Full-screen vertical pager with blurred image backgrounds, paginated article reader, native ExoPlayer video/audio playback, and YouTube WebView embeds.
- **Clearnet Aggregation**: Curated sources across 14+ categories (Tech, Science, Music, Gaming, Photography, etc.) with user-configurable interests and genre preferences that dynamically personalize API-driven content.
- **Encrypted Social Mesh**: Tor-routed gossip protocol with Ed25519-signed posts, X25519/ChaCha20-Poly1305 encrypted DMs, QR code pairing, and gossip flood routing with per-sender rate limiting.
- **Sovereign Identity**: BIP39 mnemonic recovery, hardware-backed key storage, user profile management, and AES-256 encrypted backup/restore with full media portability.
- **Self-Contained Networking**: Embedded Tor daemon (no Orbot dependency), automatic hidden service registration, DNS-over-HTTPS fallback, and a unified settings panel for all system configuration.

## Current Build & Integration Status
- **Android Compilation Target**: API Level 35 (compiled using JDK 17, Jetpack Compose with Material Design 3)
- **Status**: **PASSING & FULLY COMPILED** (verified via `./gradlew assembleDebug`)
- **Visual Identity**: Premium brutalist dark-mode styling with custom adaptive color system (AccentGreen, DestructiveRed, SurfaceDark) and Material Design 3 guidelines.

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
14. **Send Retry with Backoff**: `MeshTransport` retries failed transmissions up to 5 times, with a `attempt * 3s` exponential backoff (3s/6s/9s/12s between attempts 2-5) and a 30s per-attempt onion connect timeout.
15. **Background Feed Sync (WorkManager)**: Integrated periodic WorkManager `FeedSyncWorker` task to refresh subscribed RSS/Atom feed content every 15 minutes when connected to a network.
16. **QR Code Pairing (Scan & Share)**: Built `QRScanScreen` using mobile CameraX + Google ML Kit for frictionless live QR companion pairing, paired with `QRShareSheet` to display/share generated thematic contact profiles.
17. **Worker Repository Leak Fix**: Separated mesh socket initialization (`startListening`) into `NoSlopApp.onCreate()` singleton from `NoSlopRepository.init`, preventing dangerous port rebinding exceptions when `FeedSyncWorker` spins up in the background.
18. **Embedded Tor daemon (tor-android) — no Orbot dependency**: Replaced external dependency with a native daemon binding.
19. **Tor hidden service auto-registration**: Onion address is now automatically requested via `jtorctl` (ADD_ONION) on Tor daemon start, providing a listening endpoint for peer connections.
20. **Debug screen for two-device test visibility**: Created a unified Debug/Test screen to monitor Tor routing, listener state, active peer lists, and recent diagnostic logs cleanly.
21. **DebugScreen type bugs fixed**: Fixed state flows and collection mappings in `DebugScreen` to unblock the final two-device tests.
22. **Proguard rules hardened**: Added explicit `-keep` and `-dontwarn` rules for `tor-android`, `jtorctl`, and `netcipher` to prevent runtime `ClassNotFoundException` in release builds.
23. **registerHiddenService() raw implementation**: Refactored `TorService.registerHiddenService()` to use raw `sendAndWaitForResponse` and reflection fallback, making it version-safe across `jtorctl` variations.
24. **registerHiddenService() ReplyLine extraction hardened with multi-strategy fallback**: Hardened response extraction using active field probing and raw `toString()` fallbacks, ensuring robust ServiceID extraction across any packaged library build.
25. **BIP39 Word Cloud Password**: Implemented `MnemonicGenerator.kt` for 12-word mnemonic generation and seed derivation, replacing raw key display with a human-readable recovery phrase.
26. **Secure Data Portability**: Developed `BackupManager.kt` providing AES-256 encrypted export/import of SQLite databases and secure preferences, keyed by the mnemonic seed.
27. **Session Locking**: Added `logout()` and `unlock(mnemonic)` capabilities to `IdentityRepository.kt`, enabling secure session management without clearing hardware keys.
28. **TikTok-style Full Screen Vertical Feed**: Finalized `UnifiedFeedTab` implementation natively using Jetpack Compose `VerticalPager`.
29. **P2P Media Exchange Architecture**: Implemented robust distributed content fetching with `MEDIA_REQUEST` routing logic over the SOCKS5 proxy layer.
30. **Hardware Codec Exhaustion Guard**: Mitigated fatal Android `MediaCodec` allocation failures by properly scoping ExoPlayer to Compose `DisposableEffect` with proactive `.release()` calls tied to pager offscreen recycling.
31. **Dynamic Media Pre-fetching & Loading Polish**: Added an ExoPlayer `PreloadManager` pool to asynchronously prepare the next upcoming audio or video stream during idle feed state. Implemented dynamic look-ahead logic across up to 10 slides based on active feed filters, drastically reducing playback latency without exceeding hardware codec limits. Replaced basic placeholders with a pulsing `LoadingShimmer` composable for a premium aesthetic.
32. **Real Media Capture Engine**: Integrated CameraX and MediaRecorder in `MediaCaptureManager.kt` for native photo, video, and audio capture.
33. **Advanced Onboarding Flow**: Refactored `OnboardingScreen.kt` into a 6-step journey including interest-based filtering and background content pre-loading (50+ items).
34. **Snapping Full-Screen Feed**: Refactored `MainScreen.kt` to use `VerticalPager` (TikTok-style) for immersive, focused content viewing.
35. **Sophisticated Media Rendering**:
    - **Blurred Backgrounds**: `BlurredImageBackground` Composable for uncropped images with aesthetic fill.
    - **Segmented Articles**: `SegmentedArticleReader` for horizontal pagination of long text content within the vertical feed.
32. **Interaction Overlays**: Integrated floating Reaction (Like), Share, and Comment buttons with context-aware logic for mesh vs. clearnet content.
33. **YouTube RSS Integration**: Enhanced `FeedParser.kt` to support YouTube's `media:group` RSS schema for high-quality video extraction without sign-in.
34. **Mnemonic Clipboard Support**: Added tap-to-copy functionality for the word cloud during onboarding.
35. **Sanitized Feed Content**: Enhanced `FeedParser.kt` to strip `<code>` and `<pre>` blocks from articles, ensuring a clean reading experience free of technical "slop".
36. **Rich Article Previews**: Articles now extract and display the first relevant image from their content within the `SegmentedArticleReader`.
37. **Video Playback Fix**: Optimized `VideoPlayer` with explicit MIME type detection (including HLS/m3u8 support) and automatic `playWhenReady` for clearnet streams.
38. **Massive Source Library Expansion**: Added 14 interest categories (Lifestyle, Gaming, Music, etc.) and dozens of tracker-free sources to `SourceLibrary.kt`.
39. **Creator Search**: Integrated a real-time search bar in the onboarding flow to allow users to find specific channels, creators, or topics.
40. **UI UX Overhaul**: 
    - Repositioned the **Compose FAB** to the bottom-middle for better thumb accessibility.
    - Compacted the **Main Navigation Menu** by 20% to maximize vertical screen real estate.
41. **Social Clearnet Expansion**: Added native RSS bridge support for **Reddit**, **Mastodon**, and **TikTok** (via ProxiTok), allowing users to follow their favorite social creators without sign-in or tracking.
42. **Immersive Video Tilt**: Implemented automatic full-screen expansion for horizontal videos when the device is tilted to landscape mode.
43. **Connected Mesh Chat**: The "Chat" button on mesh feed posts now directly opens the encrypted DM thread with the post's author.
44. **Polished Article Paging**: Refined `SegmentedArticleReader` to break content at natural paragraph and sentence boundaries for a superior digital reading experience.
45. **Feed Crash Resolution**: Fixed a critical `StringIndexOutOfBoundsException` in the article segmentation engine by implementing robust boundary checking and fallback constraints.
46. **YouTube & HLS Video Reliability**: Integrated `media3-exoplayer-hls` and `media3-exoplayer-dash` for industrial-grade clearnet video streaming. Implemented a privacy-preserving `WebView` embed for YouTube content to ensure playback compatibility without data leaks.
47. **Onion Collision Self-Healing**: Hardened `TorService.kt` to handle `550 Onion address collision` errors by intelligently verifying existing registrations and re-triggering UI state updates.

48. **Key size corrected to 255**: Corrected Ed25519 key size to 255 for compatibility with Android's Conscrypt cryptographic provider to prevent onboarding page crashes on actual devices.

49. **Instant Mesh Previews**: Implemented automatic generation of tiny, high-compression thumbnails for mesh posts. These are included in the `MediaMetadata` gossip packet, allowing peers to see a visual preview immediately while the full file downloads over Tor.

50. **Industrial-Grade Playback**: Enhanced `ExoPlayer` with custom load controls (3s start buffer) and broader MIME type detection (HLS, DASH, diverse MP4/MKV containers) to handle high-latency mesh and clearnet streams reliably.

51. **Secure DNS-over-HTTPS (DoH)**: Integrated `okhttp-dnsoverhttps` and configured Cloudflare (1.1.1.1) as a secure fallback. This resolves `Unable to resolve host` and SSL verification errors without routing all traffic through the slower Tor network.

52. **TikTok-Style Feed Auto-play**: Configured `WebView` and `ExoPlayer` components to automatically start media playback on scroll, creating a seamless, immersive vertical feed experience for Archive.org and YouTube content.

53. **Clean Article Engine**: Implemented robust HTML sanitization in `FeedParser.stripHtml` to remove tags, styles, and scripts from full-length articles, providing a distraction-free text reading experience.

54. **Hardened Media Proxy**: Improved `MediaProxyService` with larger connection backlogs and extended timeouts (120s) to better accommodate deep-mesh synchronization latencies.

55. **Visual-First Curation**: Added a dedicated "Photography" category with high-quality sources (500px, Flickr) and updated the UI to prioritize displaying media over text segments for art and photography feeds.

56. **Unified Peer Handshake Payloads**: Removed duplicate `UserHandshakePayload` (exact copy of `ConnectionRequestPayload`). Both packet types now use a single `PeerHandshakePayload` data class, reducing code surface and preventing future deserialization drift.

57. **Tap-to-Turn Article Reader**: Replaced the `HorizontalPager`-based article reader (which conflicted with the parent `VerticalPager` gesture system) with invisible tap zones on the left/right 20% edges. Tapping right advances to the next page; tapping left goes back. Added a page counter ("1 / 3") below the dot indicators for clarity.

58. **Hardened Zoomable Image Viewer**: `ZoomableImageDialog` now clamps pan offset to screen bounds, preventing images from being dragged off-screen. Added double-tap gesture to toggle between 1x and 3x zoom, resetting pan position on zoom-out.

59. **RSS Auto-Discovery**: `FeedParser.resolveRssUrl()` automatically discovers RSS/Atom feeds from any URL. Checks HTML `<link rel="alternate">` tags first, then probes well-known paths (`/feed`, `/rss`, `/feed.xml`, etc.) as a fallback. This means users can now just type "https://theverge.com" and the app finds the real feed.

60. **Enhanced Debug Observability**: Added strategic `Logger.debug`/`Logger.warn`/`Logger.info` entries across `MediaManager.checkAndAutoDownload`, `MediaCaptureManager`, `MeshTransport`, `ProxiTokClient`, and `NitterApiClient`. Silent degradation paths now explicitly warn instead of silently returning empty results.

61. **Feed Aggregation Reliability**: Removed hardcoded list size gating during repository syncing, ensuring that late-arriving video elements (from slower sources like Archive.org or NASA) are consistently added to the Unified Feed instead of being completely starved by fast-loading RSS articles.
62. **Video Layout Geometry**: Defined explicit layout parameters (`MATCH_PARENT`) for both native `WebView` and ExoPlayer `PlayerView` inside Compose `AndroidView`, resolving bugs where videos silently played with 0px heights.
63. **ExoPlayer View Synchronization**: Added required state reassignment (`view.player = exoPlayer`) to the Compose `update` block for the `PlayerView`, completely eliminating "transparent player" bugs when the vertical pager recycled off-screen slides.
64. **Hardware Codec Exhaustion Fix**: Updated `DisposableEffect` logic to aggressively release `ExoPlayer` instances when their corresponding pager slides are fully scrolled off-screen. This frees hardware `MediaCodec` resources and completely mitigates ExoPlayer crashing out with `Media Quality Service not found` black screens.
65. **YouTube Error 153 Resolution**: Scrubbed synthetic JavaScript `v.play()` injections that conflicted with YouTube's internal API state machine, migrating to URL-based `?autoplay=1` and DOM-synthetic play button clicks to ensure seamless, error-free WebView autoplay.
66. **FeedParser Main Thread ANRs**: Rewrote the `stripHtml` parser to use Android's native C-backed `Html.fromHtml()` instead of unbounded Regex, instantly resolving multi-second UI thread blocking and ANRs during massive ScienceDaily article compositions.
67. **Coil AsyncImage Render Freezes**: Refactored `AsyncImage` in `BlurredImageBackground` to use explicit `ImageRequest.Builder` payloads with `crossfade(true)`, solving cases where Coil failed to paint loaded images until the user manually triggered a click event/recomposition.
68. **WebView Global Engine Freezing**: Removed Android's global `pauseTimers()` from the WebView's Compose lifecycle. This resolved a catastrophic UI bug where swiping past an off-screen WebView paused the Chromium layout engine for the entire application, leaving incoming slides permanently blank until swiped backwards.
69. **Pre-Emptive Swipe Initialization**: Extended the `isVisible` predicate for ExoPlayer and WebView initializations to include `pagerState.targetPage == index`, triggering media loading the exact millisecond the user begins swiping instead of waiting for the physical snap physics to settle.
70. **Rapid Swipe Rendering Resilience**: Fixed completely blank slides appearing during rapid swiping by increasing `VerticalPager`'s `beyondViewportPageCount` to 2, expanding the `isVisible` predicate to include `settledPage`, and wrapping feed cards in error-tolerant `Box` layers.
71. **Archive.org Validation & ExoPlayer Source Errors**: Mitigated "401 Unauthorized" ExoPlayer crash states for Archive.org streams by adding a pre-flight `HEAD` check to `/download/` URLs. Automatically switches to `WebView` embeds when authentication is required. Replaced default ExoPlayer data source with `OkHttpDataSource` backed by the system's `clearnetClient` to leverage secure DoH DNS routing.
72. **WebView GPU Exhaustion Fix**: Resolved a fatal Chromium GPU process crash (`exit_code=0`) and resulting ANRs during rapid scrolling. Replaced immediate `AndroidView` creation with a lazy `if (isVisible)` initialization, falling back to a lightweight `AsyncImage` thumbnail when slides are pre-buffered. This reduced peak active Chromium instances from 5 down to 1.
73. **YouTube Embed Error 150/153 Mitigation**: Resolved YouTube iFrame API blocked video restrictions by transitioning from `loadUrl` to `loadDataWithBaseURL` injection. Synthetically generated local HTML `<iframe>` payloads are now mounted with the `https://com.noslop.app` Base URL to securely mock a verified Android package origin without relying on a centralized domain.
74. **Anti-Bot Detection Fix (Error 152-4)**: Fixed "Video is unavailable" errors on YouTube embeds. Removed hardcoded legacy user-agent strings that triggered YouTube's scraping heuristics. Injected `strict-origin-when-cross-origin` referrer policies to ensure the `WebView` dynamically uses the real, non-flagged device environment.
75. **Unified Settings & Profile Configuration**: Replaced the fragmented "My Profile" tab with a unified Settings page, housing discrete screens for User Identity (display name, bio, avatar) and dynamic Content Preferences, all fully persisted to local storage.
76. **Dynamic Aggregated Content Refreshes**: Refactored the `FeedSync` logic to seamlessly union newly selected User Preferences with previously active feed sources, guaranteeing that changing interests instantly triggers a live pull of new API content. Added cache-clear operations (`clearApiData`) to expunge irrelevant stale feeds when topics are toggled off.
77. **Secure Application Portability**: Extended `BackupManager.kt` zip generation functionality to map and compress local media directories natively (Pictures, Movies, Music), allowing true zero-loss device migrations without centralized server sync. Coupled with a strict, UI-gated "Factory Reset" option for deep device wiping.

78. **Two-Tier Negative Keyword Filtering**: Implemented a dual-layer content moderation system. A hardcoded `OFFICIAL_NEGATIVE_KEYWORDS` blocklist (nude, porn, murder, rape, gore, nsfw, sex, kill) is applied at compile time and cannot be modified by users. A user-editable comma-separated keyword list is persisted via `AppSettingDao` and configurable from the Settings screen. Both lists are merged at sync time and applied to all RSS/Atom and Public API pipeline items, filtering on title and excerpt content before database insertion.

79. **Language Preference for Clearnet Aggregation**: Added a language preference setting (defaulting to English) accessible via an `ExposedDropdownMenu` in Settings. The selected language code is injected directly as `&language=` query parameters into NewsAPI and PodcastIndex API calls, and appended as a search keyword to Invidious queries. Supports 9 languages: English, Spanish, French, German, Italian, Portuguese, Russian, Chinese, and Japanese.

80. **Decentralized Clearnet Content Sharing**: When sharing clearnet content to the mesh, the original URL and title are now embedded in the `PostPayload` as `clearnet_url` and `clearnet_title` fields, persisted in the `MeshPost` Room entity, and propagated through the gossip protocol. Mesh peers receiving shared clearnet posts see a "View on Clearnet" button that opens the original content via `ACTION_VIEW` intent. Updated `PACKET_SCHEMA.md` to document the new fields.

81. **Settings Menu Consolidation**: Unified the fragmented User Profile, Content Categories, Genres, Languages, and Source Manager screens into a single `ContentPreferencesScreen` (`Profile & Preferences`), removing redundant menus and local search bars for a cleaner user experience.
82. **Robust Source Toggling**: Rewrote the feed source toggle logic to use SQL `INSERT OR REPLACE` operations. This ensures that manually enabling a new source securely commits it to the database regardless of its prior caching state.
83. **Aggressive Feed Wiping**: Modified the `refreshFeeds()` pipeline to properly clear the in-memory feed cache (`_unifiedFeed.value = emptyList()`) and rigorously purge all unsaved RSS/API items from the local database (`clearUnsavedItems`) whenever preferences are changed. This guarantees the feed immediately reconstructs itself using exclusively the newest user parameters.
84. **Smart Source Fallback**: Updated the API fetching logic (`syncApiFeeds()`) to determine API sources strictly on a per-category basis. If a user selects a category but explicitly toggles *no* API sources for it, the engine dynamically falls back to querying all built-in sources for that category. If the user explicitly selects *any* source within the category, the system strictly enforces the manual selection.
85. **BIP39 Compliance**: Hardened `MnemonicGenerator.kt` with the full 2048-word official BIP39 English wordlist, ensuring industry-standard mnemonic recovery phrases.
86. **Secure Backup IV**: Refactored `BackupManager.kt` to use a cryptographically secure random Initialization Vector (IV) for AES-256-CBC encryption, prepending it to the backup file to ensure proper decryption on any device.
87. **Data Layer Refactoring**: Decoupled `NoSlopRepository.kt` by extracting complex mesh packet processing into a dedicated `MeshPacketHandler.kt`, reducing repository size and improving logic isolation.
88. **UI Componentization**: Extracted 1,500+ lines from the oversized `MainScreen.kt` into atomic, reusable components (VideoPlayer, AudioPlayer) and specialized tab screens (DMsTab, SettingsTab, LogsViewer, ApiKeysScreen) for better modularity.
89. **Identity Security Awareness**: Enhanced `IdentityRepository` to detect hardware-backed keystore availability and implemented a high-visibility UI security warning in Settings for users on devices with restricted encryption capabilities.

90. **Enhanced Reactions and Content Health (gChat Alignment)**:
    - **Expanded Reactions**: Support for ❤️, 😂, 😮, 😢, 😡, 🔥, 👍, 👎.
    - **Net Score Calculation**: Mesh posts and shared clearnet content now display a live Net Score (Upvotes - Downvotes).
    - **Content Health Moderation**: Implemented gChat-style automatic moderation. Content with >66% negative signals (downvotes + 😡) is soft-blocked with a "Community Flagged" overlay. Content with >95% negative signals (min 5 total) is hard-blocked.
    - **Reaction Toggling**: Users can now add/remove reactions by clicking the same emoji again, with full mesh synchronization via the `action: "remove"` packet field.
    - **Clearnet-to-Mesh Bridge**: Clearnet feed items now correctly bind to deterministic mesh anchor posts based on SHA3-256 URL hashes, unifying mesh engagement (comments, votes) across all nodes.
    - **Interaction Isolation**: Removed Like/Comment buttons from clearnet feed items to drive engagement toward shared mesh broadcasts.
    - **Rich Clearnet Previews**: Shared clearnet links now include a `clearnet_thumbnail_url` for high-fidelity visual previews within the mesh social layer.

91. **Tor Establishment Stability**: Resolved an issue where Tor would flap/restart on every `onResume` call in `MainActivity`. Implementation is now idempotent via `TorService.startTor`.

149. **BackupManager Security Fix**: Corrected the AES-256-CBC IV generation to use cryptographically secure random bytes instead of a static array.
150. **DNS Error Spam Resolution**: Added an empty favicon `<link>` tag to the HTML injected into `VideoPlayer.kt` and `AudioPlayer.kt`'s `WebView`.
151. **Identity Security Warning**: Exposed `isUsingInsecureStorage` state flow and added a prominent SECURITY WARNING banner in `ContentPreferencesScreen.kt` to inform the user if their private keys are stored in plaintext.
152. **Architectural Cleanup (God Component)**: Refactored `MainScreen.kt` by extracting `UnifiedFeedTab` and `FullScreenFeedCard`, drastically reducing UI monolith sizes.

## Technical Debt & Security Improvements (from March 2025 Audit)
- ~~**God Files Refactoring**~~: ✅ Largely resolved. `MainScreen.kt` is now 332 lines (down from 2,889) with UI extracted into `ui/components/` (FeedCard, VideoPlayer, AudioPlayer, ChatThreadScreen, CommentsBottomSheet, PeerItem) and `ui/tabs/` (DMsTab, SettingsTab, ApiKeysScreen, LogsViewerScreen), plus dedicated `UnifiedFeedTab.kt`/`HaiNetTab.kt`. `NoSlopRepository.kt` is now 869 lines (down from 1,061) with mesh packet dispatch fully extracted to `MeshPacketHandler.kt`.
- **Identity Security**: Currently, `IdentityRepository` silently falls back to plaintext `SharedPreferences` if `EncryptedSharedPreferences` fails. Needs a UI-level warning for users if hardware-backed encryption is unavailable.
- **Tor Control Security**: `TorService.writeTorrc` now writes `CookieAuthentication 1` (transitioned from `CookieAuthentication 0` as planned). However, `registerHiddenService` still authenticates to the control port with a bare `AUTHENTICATE` (no cookie/password), which is only valid under `CookieAuthentication 0`. **This needs verification against the running `tor-android` daemon** — either the control-port auth call needs to read and send the auth cookie file, or the torrc setting should remain `0` until cookie-based auth is implemented.
- **Dependency Alignment**: 
    - Align `okhttp` (4.10.0) with `okhttp-dnsoverhttps` (4.12.0).
    - Migrate from `accompanist-permissions` to native Compose permission APIs.
    - Consider upgrading `security-crypto` to stable `1.0.0` if alpha features are no longer strictly required.
- **Test Coverage**: Critical need for unit tests in `CryptoService` (sign/verify/encryption) and `GossipService` (firewall/rate-limiting logic).

## Pending Implementations & Limitations
- **Audio Playback Failure**: Audio content pieces currently do not play despite proxy and codec updates; requires investigation into direct file extraction vs. landing pages.
- **Clearnet Feed Starvation**: Reported issue where only a subset of selected content sources are actually loaded into the unified feed, even when all interests are enabled during onboarding. Requires investigation into the `refreshFeeds()` pipeline and database insertion limits.
- **Clearnet Video Compatibility**: Currently, only Archive.org and YouTube (via WebView) playback is verified; other clearnet video sources may still trigger format errors.
- ~~**Article Pagination Gestures**~~: ✅ Resolved in milestone 57 (tap-to-turn zones).
- ~~**Zoomable Media**~~: ✅ Resolved in milestone 58 (bounds-clamped zoom + double-tap reset).
- ~~**RSS Subscription Logic**~~: ✅ Resolved in milestone 59 (auto-discovery from HTML `<link>` tags).
- **Feed Pre-loading & Hybrid Mixing**: The current feed loading mechanism needs a rethink to pre-buffer next items and intelligently mix aggregated (clearnet) and broadcasted (mesh) content without stalling or overloading Tor circuits.
- **Comment Module UI**: The comment module currently lacks a user-facing way to actually post/leave a new comment within the unified feed UI.
- **Handshake Signature Verification Gap**: `PeerHandshakePayload` (`CONNECTION_REQUEST`/`USER_HANDSHAKE`) carries a `signature` field that is computed and sent by `NoSlopRepository`, but `MeshPacketHandler` never verifies it on receipt. A peer could theoretically forge a handshake claiming to be a different identity. Add signature verification (`fromUserId|fromUsername|timestamp` or similar) to `handleConnectionRequest`/`handleUserHandshake`.
- **Relay State Memory Growth**: `GossipService.relayStates` (`ConcurrentHashMap`) has no TTL/eviction policy — long-running nodes that participate in many media relays will accumulate stale entries indefinitely. Add a periodic sweep (mirroring `hainet-social`'s `cleanup_stale_routes`).

## Phase 2: Feature Expansion & Social Parity (Planned)

Based on recent user testing, the following core features are planned for the next development cycle to bring NoSlop to parity with modern messaging apps (e.g., gChat):

1. ~~**Enhanced Reactions UI**~~: ✅ Largely resolved (milestone 90 — "Enhanced Reactions and Content Health"). `REACTION` packets, signed/gossiped reaction toggling, and the soft-block/hard-block Content Health overlay are implemented. Remaining: per-post/per-comment reaction counters in the feed UI, and extending reactions to Direct Messages (`CHAT_REACTION` — see gap analysis doc).
2. **Group Chats**: Implement decentralized group chat capabilities with dedicated mesh packet routing. gChat already has a complete reference spec (`Group` entity, `GROUP_INVITE`/`GROUP_UPDATE`/`GROUP_DELETE`/`GROUP_QUERY`/`GROUP_SYNC` packets, multi-recipient X25519 encryption fan-out) — see [NOSLOP_GAP_ANALYSIS_AND_UPSTREAM_NOTES.md](NOSLOP_GAP_ANALYSIS_AND_UPSTREAM_NOTES.md) §3 for the full spec to port.
3. **Mesh Comment Synchronization Fix**: Resolve the issue where users only see their own comments on the mesh. Requires an audit of the `COMMENT` packet broadcast and firewall ingestion logic.
4. **Sophisticated Notification System**: Implement Android local notifications for DMs and mentions. Add in-app popups and UI markers for active engagement. Provide a toggle in settings to enable/disable notifications.
5. **Semi-Active Background Mode**: Implement a Foreground Service to keep the mesh connection alive and Tor daemon running while the phone is locked, allowing notifications to arrive in real-time. Include auto-lock timeouts.
6. **Biometric Security**: Integrate `androidx.biometric`. Add a logout/login mechanism and prompt the user for fingerprint/face unlock upon returning to the app after the auto-lock timeout.
7. **Rich Clearnet Sharing Overhaul**: Partially resolved — `clearnet_thumbnail_url` now flows through `PostPayload` and `MeshPost` (milestone 90), and anchor posts already carry title/thumbnail metadata for shared links. Remaining: render these as rich preview cards mirroring native mesh broadcasts in the feed UI (see "Remaining polish work" under Clearnet-to-Mesh Broadcast System below).
8. **Online Presence Indicators**: Add a lightweight `ANNOUNCE_PEER` heartbeat packet so contacts show as online/offline in real time, matching gChat's "Live Contact Sync". See gap analysis doc §4.
9. **Hash-Based Sync (`INVENTORY_SYNC`)**: Replace the current timestamp-based `SYNC_REQUEST`/`SYNC_RESPONSE` ("everything since X") with hash-based inventory diffing to avoid re-transferring posts peers already have. See gap analysis doc §1.

## Clearnet-to-Mesh Broadcast System

This feature is the core bridge between NoSlop's clearnet aggregator and the mesh social layer. When a user interacts with clearnet feed content (via like, share, or comment), that interaction becomes a signed mesh broadcast, and all subsequent engagement on that content happens peer-to-peer on the mesh.

### What is implemented

- **`PostPayload` schema** (`Packets.kt`): `clearnet_url`, `clearnet_title`, and `clearnet_thumbnail_url` fields exist and are serialised in every gossip `POST` packet.
- **Room persistence** (`Entities.kt`): `MeshPost` entity stores `clearnetUrl`, `clearnetTitle`, and `clearnetThumbnailUrl`, persisting shared clearnet references locally.
- **Share-to-Mesh dialog** (`MainScreen.kt`): `showShareDialog` state and the "Share to Mesh" confirmation dialog are implemented for `UnifiedItem.Feed` items. The dialog calls `composeAndBroadcastPost` with the item's URL, title, and thumbnail.
- **ViewModel wiring** (`NoSlopViewModel.kt`): `composeAndBroadcastPost()` accepts `clearnetUrl`, `clearnetTitle`, and `clearnetThumbnailUrl` parameters and passes them through to `NoSlopRepository`.
- **Repository broadcast path** (`NoSlopRepository.kt`): `composeAndBroadcastPost()` builds and signs the `NetworkPacket` with clearnet metadata and calls `GossipService.broadcast()`.
- **"View on Clearnet" button**: Peers receiving a shared clearnet post see a button that opens the original URL via `ACTION_VIEW` intent.
- **Comment gossip engine**: `COMMENT` packet type, `CommentPayload`/`CommentData` structures, signature verification, Room persistence, and `composeAndBroadcastComment()` are fully implemented for mesh-native posts.
- **`REACTION` packet type** (`Packets.kt`): `ReactionPayload` with `post_id`, `reaction_type`, `author_id`, `timestamp`, `signature`, and `action` (`"add"`/`"remove"` toggle).
- **`REACTION` gossip handling** (`MeshPacketHandler.kt`): incoming `REACTION` packets are signature-verified (`postId|reactionType|authorId|timestamp`) and persisted to/removed from the `mesh_reactions` Room table via `ReactionDao`. `getReactionsForPost(postId)` and `getReactionSummaryForPost(postId)` Flows are exposed from the repository.
- **Clearnet broadcast trigger on Like** (`NoSlopRepository.reactToFeedItemWithType`): tapping a reaction on a clearnet feed item (a) derives a deterministic anchor ID via `"clearnet_" + SHA3-256(url).take(16)`, (b) creates the anchor `POST` if it doesn't exist locally yet, then (c) broadcasts a `REACTION` packet against that anchor ID.
- **Broadcast anchor deduplication**: the SHA3-256-derived `anchorId` is deterministic per URL, so every node that shares the same clearnet link converges on the same mesh post ID without coordination — comments and reactions across nodes land on a single shared thread per URL.
- **Interaction overlays** (`MainScreen.kt`): Like, Share, and Comment buttons are rendered on `FullScreenFeedCard`. Per milestone 90, Like/Comment are removed from raw clearnet feed cards (engagement is funneled through the mesh anchor) while Share-to-Mesh remains available.

### Remaining polish work

1. **Reaction count display on feed cards** — surface a live reaction counter (via `getReactionSummaryForPost`) directly on clearnet feed cards that already have a mesh anchor, not just on the mesh feed.
2. **Rich clearnet preview cards** — visually mirror native mesh broadcasts using `clearnet_thumbnail_url`, rather than a plain "View on Clearnet" link.
3. **Feed hybrid mixing** — surface comment/reaction counts directly on clearnet feed cards once an anchor exists, blurring the boundary between aggregated content and mesh conversation (tracked together with "Feed Pre-loading & Hybrid Mixing" below).
4. **Network-aware anchor check** — `reactToFeedItemWithType` currently only checks for an existing anchor *locally* before broadcasting a new `POST`; because `anchorId` is deterministic this still converges correctly, but it means the same anchor post may be re-broadcast by multiple originating peers. A lightweight "anchor already seen on the mesh" check would reduce redundant gossip traffic.

See [docs/NOSLOP_GAP_ANALYSIS_AND_UPSTREAM_NOTES.md](NOSLOP_GAP_ANALYSIS_AND_UPSTREAM_NOTES.md) for further parity items versus gChat/HAI-Net (presence/`ANNOUNCE_PEER`, `COMMENT_REACTION`/`COMMENT_VOTE`, hash-based `INVENTORY_SYNC`, group chats, etc.).

## Cryptographic Specification Contract
| Function | Primitive | Format / Library | Storage Backend |
| :--- | :--- | :--- | :--- |
| **Post Signatures** | Ed25519 | Base64 strings (X.509/PKCS#8) | Android Keystore / `EncryptedSharedPreferences` |
| **Tripcode Derivation** | SHA3-256 | 6-char lowercase Base32 sequence | Database / Memory |
| **Onion Addressing** | SHA3-256 Tor v3 | 56-char `.onion` address | Database / Memory |
| **Key Agreement** | X25519 | Bouncy Castle | `EncryptedSharedPreferences` |
| **Direct Message E2EE** | ChaCha20-Poly1305 | 12-byte random nonce + SHA3-256 shared secret derivation | Local DB (Encrypted) |

## Build Configuration Notes
- **`minSdk` discrepancy**: `docs/BUILD.md` states `minSdk = 26`, but `app/build.gradle.kts` currently sets `minSdk = 24`. Confirm which is authoritative — if 24 is intentional (broader device support), update `BUILD.md`; if 26 is required (e.g. for a dependency), update `build.gradle.kts` and re-test on API 24/25.

## Further Reading
- [docs/NOSLOP_TECHNICAL_REFERENCE.md](NOSLOP_TECHNICAL_REFERENCE.md) — exhaustive technical reference covering package layout, exact crypto derivation formulas, gossip pipeline internals, the full wire protocol, media/Tor internals, Room schema, and build configuration.
- [docs/NOSLOP_GAP_ANALYSIS_AND_UPSTREAM_NOTES.md](NOSLOP_GAP_ANALYSIS_AND_UPSTREAM_NOTES.md) — detailed comparison against gChat and HAI-Net's `hainet-social` Rust crate, with a prioritized backlog checklist for packet types, presence, group chats, congestion control, and more.
