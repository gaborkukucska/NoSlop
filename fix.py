#!/usr/bin/env python3
import sys
import re

APPLIED = []
FAILED = []

def edit(path, old, new, label):
    try:
        with open(path, "r", encoding="utf-8") as f:
            src = f.read()
    except Exception as e:
        FAILED.append(f"{label}: Could not read {path}: {e}")
        return

    if old not in src:
        FAILED.append(f"{label}: Target string not found in {path}")
        return

    count = src.count(old)
    if count > 1:
        FAILED.append(f"{label}: Target string appears {count} times (expected 1) in {path}")
        return

    src = src.replace(old, new, 1)
    try:
        with open(path, "w", encoding="utf-8") as f:
            f.write(src)
        APPLIED.append(label)
    except Exception as e:
        FAILED.append(f"{label}: Could not write {path}: {e}")

STATUS_FILE = "docs/PROJECT_STATUS.md"
TECH_FILE = "docs/TECHNICAL_REFERENCE.md"

# ---------------------------------------------------------------------------
# 1. Update docs/PROJECT_STATUS.md
# ---------------------------------------------------------------------------
NEW_STATUS_SECTION = """# Project Status - NoSlop

## Completed Changes (2026-09-09) — Feed Swipe History, Cold-Start Position Resume, 40+ Creator Variety & Tor Playback Stabilization

* **Immediate Swipe-Away History & Multi-Key Exclusion (`UnifiedFeedTab.kt`, `NoSlopViewModel.kt`, `EngagementRepository.kt`)**:
  * Fixed an issue where swiped-away clearnet slides (even if still loading or briefly viewed) were not saved to history and were repeatedly re-served.
  * `UnifiedFeedTab.kt` detects page transitions using hoisted `lastSettledPage`: whenever `lastSettledPage != pagerState.settledPage`, the vacated slide is immediately marked read (`markItemReadState`), added to viewed history (`markItemViewed`), and recorded in the swipe tracker (`recordItemSwiped`).
  * `EngagementRepository.recordSwipe()` now writes the raw `itemId`, normalized ID (`normId`), and canonical URL/title key (`canonicalKey`) into `swipe_tracker`.
  * Updated `loadMoreFeedItems()` in `NoSlopViewModel.kt` to check `cachedExcludedIds` and `cachedViewedIds` across all representation keys, preventing swiped or viewed items from resurrecting into the Live Feed.
* **Cold-Start Slide Position Persistence (`UnifiedFeedTab.kt`, `NoSlopViewModel.kt`)**:
  * Eliminated cold-start position clobbering: introduced `isSavedPositionLoaded` StateFlow in `NoSlopViewModel.kt` to ensure Compose waits for SQLite to load `saved_feed_active_id`.
  * Prevented Room's initial empty emission on cold start from clearing the saved feed list by waiting until `feeds.isNotEmpty() || meshes.isNotEmpty()`.
  * Removed `!isRefreshing` guard from `saveFeedPosition` so active slide positions are preserved even when background feed sync is active.
  * In `NoSlopViewModel.saveFeedPosition`, centered `itemsToSave` around the active `itemId` rather than naive `takeLast(100)`, ensuring early slides are never dropped from `saved_feed_list`.
  * Initialized `lastSettledPage` to the restored index on cold start to avoid false swipe triggers on the initial scroll.
* **Fair 40+ Creator Distribution & Variety (`NoSlopViewModel.kt`, `FeedRepository.kt`)**:
  * Resolved the bottleneck where only ~10 frequent daily uploaders were ever displayed while 30+ other favorite creators were never shown.
  * Updated `takeRoundRobin` in `NoSlopViewModel.kt` to shuffle the priority creator queues. Every batch now draws from 5 different creators across the user's entire list of 40+, preventing frequent daily posters from monopolizing 150 consecutive slides.
  * In `FeedRepository.kt`, randomized `rampUpCreators` and expanded the background sample to 8 creators per sync so all 40+ channels regularly refresh.
* **Tor Video Resolution & Playback Hardening (`YouTubeInternalClient.kt`, `VideoPlayer.kt`, `TorService.kt`)**:
  * **Fast-fail age-restricted videos**: Inspected `playabilityStatus.reason` in `YouTubeInternalClient.kt` for "age", "inappropriate", or "private" keywords to bail out in 0.1s instead of wasting 40s hopping 6 circuits.
  * **Fast-fail dead circuits on timeout**: Immediately advance circuit isolation nonces on socket timeouts (`Read timed out`), jumping to a fresh circuit without trying other configs on the same stalled socket.
  * **Aligned timeouts for Tor circuit construction**: Set 20s connect/read timeouts and 25s call timeout in `playerClient()`, providing Tor sufficient time (14–18s) to build fresh 3-hop circuits on mobile networks.
  * **Bypassed dead Invidious fallback**: Bypassed dead public Invidious and Piped instances over Tor, preventing 50s socket hangs.
  * **Bandwidth de-duplication**: When `ExoVideoPlayer` mounts a fresh player, it immediately cancels any duplicate background `doWarmUp` download in `PreloadManager` for that URL, preventing two ExoPlayers from competing for the same Tor bandwidth.
  * **Dedicated visible player resolution**: Restricted `VideoPlayer` network resolution to `isVisible == true` so off-screen next slides do not steal bandwidth or lock resolve mutexes.
  * **Eliminated false "no traffic" popup**: Removed false-alarm `setTorStatusMessage` triggers from background offline-peer checks in `TorService.kt`, and auto-cleared `torBlockedMessage` whenever video progress is made.
* **Background Feed Sync Coordination (`FeedRepository.kt`, `PreloadManager.kt`, `UnifiedFeedTab.kt`)**:
  * Introduced `isVideoActive` in `PreloadManager.kt`. `FeedRepository.kt` pauses Phase 2/3 background RSS, category, and creator sync while videos are actively resolving, buffering, or playing.
  * Made Phase 2 & 3 background sync asynchronous so `refreshFeeds()` returns immediately after Phase 1 Ramp-Up, and added an auto-dismiss timeout (max 5s) for the "Fetching fresh content..." banner.
* **Search Modal Query & Relevance Alignment (`NoSlopViewModel.kt`, `UnifiedFeedTab.kt`)**:
  * Allowed custom search execution even when background feed sync is running (`isRefreshingFeeds`).
  * Preserved `activeSearchQuery` in `syncFilterMode()` during search mode.
  * Updated search feed sorting to prioritize videos at the top and preserve exact search API relevance order (`lastSearchResultIds`), preventing date-disparity from burying videos beneath generic Wikipedia articles.
  * Replaced the feed on search results instead of appending to the live feed.
"""

try:
    with open(STATUS_FILE, "r", encoding="utf-8") as f:
        status_src = f.read()
    
    # Replace existing top section down to Direct Message Fast-Lane
    if "## Completed Changes (2026-09-07) — Direct Message Fast-Lane" in status_src:
        prefix = status_src.split("## Completed Changes (2026-09-07) — Direct Message Fast-Lane")[1]
        new_status = NEW_STATUS_SECTION + "\n## Completed Changes (2026-09-07) — Direct Message Fast-Lane" + prefix
        with open(STATUS_FILE, "w", encoding="utf-8") as f:
            f.write(new_status)
        APPLIED.append("docs/PROJECT_STATUS.md: updated with Section 2026-09-09 milestone")
    else:
        FAILED.append("docs/PROJECT_STATUS.md: anchor not found")
except Exception as e:
    FAILED.append(f"docs/PROJECT_STATUS.md: {e}")

# ---------------------------------------------------------------------------
# 2. Update docs/TECHNICAL_REFERENCE.md
# ---------------------------------------------------------------------------
NEW_TECH_SECTION = """## 20. Feed Engagement Tracking, Slide Position Persistence & Playback Hardening (2026-09-09)

### 20.1 Immediate Swipe-Away History & Multi-Key Exclusion
Previously, swiping away a slide only invoked `markItemViewed` if a 4-second dwell timer elapsed. If a user swiped away while the card was loading or within 3 seconds, the item remained unread (`isRead = false`), was never registered in `swipe_tracker`, and was omitted from `cachedExcludedIds`.
- `UnifiedFeedTab.kt` tracks page transitions using hoisted `lastSettledPage`: whenever `lastSettledPage != pagerState.settledPage`, the vacated slide is immediately marked read (`markItemReadState`), added to viewed history (`markItemViewed`), and recorded in the swipe tracker (`recordItemSwiped`).
- `EngagementRepository.recordSwipe()` writes the raw `itemId`, normalized ID (`normId`), and canonical key (`canonicalKey`) into `swipe_tracker`.
- `NoSlopViewModel.loadMoreFeedItems()` filters candidates against `cachedExcludedIds` across raw, normalized, and canonical keys, ensuring swiped items are permanently excluded from the Live Feed.

### 20.2 Cold-Start Slide Position Persistence & Race Condition Elimination
`UnifiedFeedTab.kt` previously initialized `hasRestoredInitialPosition = true` on the first frame if `savedTargetId` was null. Because `NoSlopViewModel` loads `saved_feed_active_id` asynchronously from Room on `Dispatchers.IO`, `savedTargetId` was momentarily null during early composition. Additionally, Room's initial empty emission on cold start caused `NoSlopViewModel` to fall into `else { loadMoreFeedItems() }`, discarding `saved_feed_list`.
- `isSavedPositionLoaded` StateFlow in `NoSlopViewModel` indicates when Room DB retrieval is complete.
- `NoSlopViewModel` ignores Room's initial empty emission (`feeds.isEmpty() && meshes.isEmpty()`), waiting for disk contents to emit before building `restoredFeed`.
- `UnifiedFeedTab.kt` gates position restoration on `isSavedPositionLoaded == true`, scrolling to the saved target via `pagerState.scrollToPage(index)` before permitting any position saves.
- `saveFeedPosition` centers `itemsToSave` around the active `itemId` rather than naive `takeLast(100)`, ensuring early slides are never dropped from `saved_feed_list`.
- `lastSettledPage` is initialized to the restored index on cold start to avoid false swipe triggers on the initial scroll.

### 20.3 Fair Round-Robin Creator Variety for 40+ Channels
In `takeRoundRobin()`, sorting priority creator queues strictly by `effectiveDate` descending caused ~10 frequent daily uploaders to permanently occupy the top positions of `rawVideos`. Because each batch requested only 5 videos, the other 30+ creators were never reached until all 15 videos from the first 10 creators were consumed (~150 slides).
- `NoSlopViewModel.takeRoundRobin()` partitions and shuffles priority creator queues so every batch draws from 5 different creators across the user's entire list of 40+.
- `FeedRepository.kt` randomizes `rampUpCreators` and expands the background creator sample to 8 channels per sync.

### 20.4 Tor Stream Resolution, Age-Gate Fast-Fail & Circuit Hopping
- **Age-Gate Fast-Fail**: `YouTubeInternalClient.kt` inspects `playabilityStatus.reason` for *"age"*, *"inappropriate"*, or *"private"*, bailing out in 0.1s instead of wasting 40s hopping 6 circuits for videos that require Google account authentication.
- **Fast-fail Dead Circuits**: On `SocketTimeoutException` (`Read timed out`), `YouTubeInternalClient` immediately increments `streamNonce` to jump to a fresh Tor circuit.
- **Circuit Construction Timeouts**: Set 20s connect/read timeouts and 25s call timeout in `playerClient()`, providing Tor sufficient time (14–18s) to build fresh 3-hop circuits on mobile networks.
- **Dead Invidious Fallback Elimination**: Bypassed dead public Invidious and Piped instances over Tor, eliminating 50s socket exhaustion.
- **Bandwidth Contention Prevention**: When `VideoPlayer` mounts a fresh player, it immediately cancels any duplicate background `doWarmUp` download in `PreloadManager` for that URL.
- **Dedicated Visible Player Resolution**: Restricted `VideoPlayer` network resolution to `isVisible == true` so off-screen next slides do not steal bandwidth or lock resolve mutexes.
- **False-Alarm Banner Elimination**: Removed false-alarm `setTorStatusMessage` triggers from background offline-peer checks in `TorService.kt`, and auto-cleared `torBlockedMessage` on media progress (`noteMediaProgress()`) and playback readiness.

### 20.5 Background Feed Sync Throttling & Banner Auto-Dismissal
- `PreloadManager.kt` maintains `isVideoActive` while videos are resolving, buffering, or playing.
- `FeedRepository.kt` pauses Phase 2 and 3 background RSS, category, and creator sync while `isVideoActive || currentlyPlayingUrl != null`, giving foreground playback 100% of Tor's bandwidth.
- Phase 2 & 3 background sync executes asynchronously so `refreshFeeds()` returns immediately after Phase 1 Ramp-Up.
- Added a 5-second auto-dismiss timeout to `isResettingFeed` in `UnifiedFeedTab.kt` so the "Fetching fresh content..." banner never gets stuck on screen.

### 20.6 Search Relevance & Concurrency Unblocking
- `NoSlopViewModel.searchAndCreateCustomFeed()` runs even when background feed sync is active (`isRefreshingFeeds`).
- `syncFilterMode()` preserves `activeSearchQuery` during active searches.
- Search feed sorting prioritizes videos at the top and preserves exact relevance order from the search API (`lastSearchResultIds`), preventing date-disparity from burying videos beneath generic Wikipedia articles.
- Search results replace the active feed instead of appending to the live feed.

---"""

try:
    with open(TECH_FILE, "r", encoding="utf-8") as f:
        tech_src = f.read()

    # Replace Section 20 if present or append before the ending footer
    if "## 20. Feed Engagement Tracking" in tech_src:
        prefix = tech_src.split("## 20. Feed Engagement Tracking")[0]
        suffix = tech_src.split("---")[-1]
        new_tech = prefix + NEW_TECH_SECTION + "\n---" + suffix
    else:
        target = "### 19.4 Peer Cooldown Dynamic Reset & Verification Alignment\n- Peer failure cooldown is capped at 120s max (preventing 1-hour lockout traps).\n- Incoming authenticated packets from a peer immediately clear any failure cooldown on the peer's onion address.\n- `ANNOUNCE_PEER` signature verification accepts both `CryptoService.encodeForSigning` and legacy pipe payloads, ensuring peers are accurately marked `isOnline = true`.\n- Typing indicators feature a 6-second auto-expiration guard, immediate dismissal upon message delivery, and a 4-second client-side idle debounce.\n\n---"
        replacement = "### 19.4 Peer Cooldown Dynamic Reset & Verification Alignment\n- Peer failure cooldown is capped at 120s max (preventing 1-hour lockout traps).\n- Incoming authenticated packets from a peer immediately clear any failure cooldown on the peer's onion address.\n- `ANNOUNCE_PEER` signature verification accepts both `CryptoService.encodeForSigning` and legacy pipe payloads, ensuring peers are accurately marked `isOnline = true`.\n- Typing indicators feature a 6-second auto-expiration guard, immediate dismissal upon message delivery, and a 4-second client-side idle debounce.\n\n" + NEW_TECH_SECTION
        if target in tech_src:
            new_tech = tech_src.replace(target, replacement, 1)
        else:
            new_tech = tech_src + "\n\n" + NEW_TECH_SECTION

    with open(TECH_FILE, "w", encoding="utf-8") as f:
        f.write(new_tech)
    APPLIED.append("docs/TECHNICAL_REFERENCE.md: documented Section 20")
except Exception as e:
    FAILED.append(f"docs/TECHNICAL_REFERENCE.md: {e}")

print("\n=== DOCS UPDATE RESULTS ===")
for item in APPLIED:
    print(f"  [APPLIED] {item}")

if FAILED:
    print("\nErrors occurred:")
    for item in FAILED:
        print(f"  [FAILED]  {item}")
    sys.exit(1)
else:
    print(f"\nAll documentation updates applied successfully!")
