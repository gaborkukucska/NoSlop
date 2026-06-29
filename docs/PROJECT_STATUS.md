# Project Status - NoSlop

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
