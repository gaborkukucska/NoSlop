# Project Status - NoSlop

## Completed Changes (2026-06-20)

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
