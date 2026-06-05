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
14. **Send Retry with Backoff**: `MeshTransport` retries failed transmissions up to 3 times with a 2-second and 4-second exponential backoff structure.
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
28. **Real Media Capture Engine**: Integrated CameraX and MediaRecorder in `MediaCaptureManager.kt` for native photo, video, and audio capture.
29. **Advanced Onboarding Flow**: Refactored `OnboardingScreen.kt` into a 6-step journey including interest-based filtering and background content pre-loading (50+ items).
30. **Snapping Full-Screen Feed**: Refactored `MainScreen.kt` to use `VerticalPager` (TikTok-style) for immersive, focused content viewing.
31. **Sophisticated Media Rendering**:
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

## Pending Implementations & Limitations
- **Audio Playback Failure**: Audio content pieces currently do not play despite proxy and codec updates; requires investigation into direct file extraction vs. landing pages.
- **Clearnet Video Compatibility**: Currently, only Archive.org and YouTube (via WebView) playback is verified; other clearnet video sources may still trigger format errors.
- ~~**Article Pagination Gestures**~~: ✅ Resolved in milestone 57 (tap-to-turn zones).
- ~~**Zoomable Media**~~: ✅ Resolved in milestone 58 (bounds-clamped zoom + double-tap reset).
- ~~**RSS Subscription Logic**~~: ✅ Resolved in milestone 59 (auto-discovery from HTML `<link>` tags).
- **Feed Pre-loading & Hybrid Mixing**: The current feed loading mechanism needs a rethink to pre-buffer next items and intelligently mix aggregated (clearnet) and broadcasted (mesh) content without stalling or overloading Tor circuits.
- **Comment Module UI**: The comment module currently lacks a user-facing way to actually post/leave a new comment within the unified feed UI.

## Cryptographic Specification Contract
| Function | Primitive | Format / Library | Storage Backend |
| :--- | :--- | :--- | :--- |
| **Post Signatures** | Ed25519 | Base64 strings (X.509/PKCS#8) | Android Keystore / `EncryptedSharedPreferences` |
| **Tripcode Derivation** | SHA3-256 | 6-char lowercase Base32 sequence | Database / Memory |
| **Onion Addressing** | SHA3-256 Tor v3 | 56-char `.onion` address | Database / Memory |
| **Key Agreement** | X25519 | Bouncy Castle | `EncryptedSharedPreferences` |
| **Direct Message E2EE** | ChaCha20-Poly1305 | 12-byte random nonce + SHA3-256 shared secret derivation | Local DB (Encrypted) |

