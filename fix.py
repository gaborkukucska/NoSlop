#!/usr/bin/env python3
import os
import sys

APPLIED = []
FAILED = []

def edit(path, old, new, label):
    if not os.path.exists(path):
        FAILED.append(f"{label}: File not found: {path}")
        return
    try:
        with open(path, "r", encoding="utf-8") as f:
            src = f.read()
    except Exception as e:
        FAILED.append(f"{label}: Could not read {path}: {e}")
        return

    if new in src:
        APPLIED.append(f"{label} (already applied)")
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

PROJECT_STATUS_FILE = "docs/PROJECT_STATUS.md"
TECH_REF_FILE = "docs/TECHNICAL_REFERENCE.md"

# ---------------------------------------------------------------------------
# 1. Update docs/PROJECT_STATUS.md
# ---------------------------------------------------------------------------
NEW_STATUS_HEADER = """# Project Status - NoSlop

## Completed Changes (2026-09-09) — Mesh Empty State, History Search & Pagination, Content Mix Variety & Audio Pipeline

* **Mesh Tab Empty State Default & Button Gating (`UnifiedFeedTab.kt`)**:
  * Prioritized `(filterMode == "Mesh" || filterMode == "P2P Mesh")` in the empty-feed container of `UnifiedFeedTab.kt`, ensuring that the "Nothing New Here" card with the "See Old Posts" button is the default empty state with zero delay.
  * Gated the "See Old Posts" button on `viewModel.hasMeshPosts == true` so fresh devices without mesh posts see "No posts have been received from the mesh network yet." without the button.
  * Decoupled the Mesh view from `isRefreshing`, completely eliminating "Curating your feed..." when switching to the Mesh tab.
* **History Filter Ordering, Search Integration & Pagination (`NoSlopViewModel.kt`, `Daos.kt`, `EngagementRepository.kt`, `UnifiedFeedTab.kt`)**:
  * Switched `ViewedHistoryDao.insertViewedItem` from `OnConflictStrategy.IGNORE` to `REPLACE`, ensuring that re-viewing or re-dwelling on content refreshes its `viewedAt` timestamp to the current time.
  * Added `getAllViewedItemsList()` in `ViewedHistoryDao`, `EngagementRepository`, and `NoSlopRepository` for reliable retrieval of all history items from SQLite.
  * Made `viewedHistoryRecords` in `NoSlopViewModel.kt` use `SharingStarted.Eagerly` so in-memory history records are always active and populated, even without direct Compose UI subscribers.
  * Implemented fast chronological `loadHistoryBatch` in `NoSlopViewModel.kt`, strictly sorting history by `viewedAt DESC` using O(1) in-memory maps.
  * Integrated real-time query filtering into `loadHistoryBatch`, allowing users to search their viewed history by keyword across titles, authors, excerpts, and content.
  * Implemented canonical key de-duplication (`com.noslop.app.data.getCanonicalItemKey(it) !in currentKeys`) and clean pagination stops, eliminating duplicate re-appending or loopback to slide 0 when scrolling past slide 30.
  * Protected viewed items (`isRead = 1`) in `feed_items` from deletion during feed resets/cleanups (`deleteYouTubeItems`, `deleteExpiredItems`, `clearUnsavedItems`) and added on-the-fly synthesis in `loadHistoryBatch` for historical YouTube items whose rows were previously purged, unlocking complete infinite scroll through all past viewed history.
  * In `UnifiedFeedTab.kt`, guarded swipe-away tracking (`recordItemSwiped`) and dwell-time marking (`markItemViewed`) with `if (filterMode != "History" && filterMode != "Saved" && filterMode != "Liked")`, ensuring that browsing past historical items does not mutate SQLite timestamps or displace active pager items.
  * Reset `lastSettledPage = -1` on all filter mode changes, preventing false swipe-away events against vacated slides during filter changes.
  * Avoided `forceRefresh = true` on filter dismissals and modal clear-alls so returning to "Live Feed" immediately restores `cachedDefaultFeed` and scrolls to `savedFeedItemId` with 0ms delay.
* **Content Mix Variety & Unblocked RSS/Audio Background Sync (`FeedRepository.kt`, `NoSlopViewModel.kt`)**:
  * Unblocked Phase 2 RSS feeds and API categories (Music, Art, Photography) in `FeedRepository.kt` by replacing infinite video-idle loops (`while (currentlyPlayingUrl != null || isVideoActive)`) with non-blocking Tor staggers (`delay(1000ms)`).
  * Unified media detection helpers (`isAudioFeedItem`, `isVideoFeedItem`, `isImageFeedItem`, `isArticleFeedItem`) in `NoSlopViewModel.kt`, accurately classifying audio tracks (Internet Archive, Openverse, podcasts) and images across both the ViewModel and `UnifiedFeedTab.kt`.
  * Raised batch size for all specific filters (`Videos`, `Audio`, `Images`, `Articles`, `Mesh`, `History`, `Liked`, `Saved`) from 3 to 30, unblocking infinite scrolling past slide 27.
  * Exempted specific content-type filter tabs (`Videos`, `Audio`, `Images`, `Articles`) from the aggressive `cachedViewedIds` purge, restoring full browsing capability in those tabs.
  * Added fallback bucket selection in `NoSlopViewModel.loadMoreFeedItems()` so that if all fresh audio, images, or articles in the database have been viewed, the Live Feed interleaver falls back to un-swiped items from the library instead of starving the bucket and backfilling with 100% video.
* **Tor Video Playback Optimization (`YouTubeInternalClient.kt`, `PreloadManager.kt`, `VideoPlayer.kt`)**:
  * Fast-failed background preload resolve permits in `YouTubeInternalClient.kt` (2s timeout), prioritizing the active on-screen video.
  * Tuned `MAX_PRELOAD` to 2 in `PreloadManager.kt`, focusing Tor connection bandwidth on the active and immediate next slide for instant startup on swipe.
"""

try:
    with open(PROJECT_STATUS_FILE, "r", encoding="utf-8") as f:
        status_src = f.read()

    # Replace from top to Completed Changes (2026-09-07)
    if "## Completed Changes (2026-09-07) — Direct Message Fast-Lane" in status_src:
        suffix = status_src.split("## Completed Changes (2026-09-07) — Direct Message Fast-Lane")[1]
        new_status = NEW_STATUS_HEADER + "\n## Completed Changes (2026-09-07) — Direct Message Fast-Lane" + suffix
        with open(PROJECT_STATUS_FILE, "w", encoding="utf-8") as f:
            f.write(new_status)
        APPLIED.append("docs/PROJECT_STATUS.md: updated milestone documentation")
    else:
        FAILED.append("docs/PROJECT_STATUS.md: anchor not found")
except Exception as e:
    FAILED.append(f"docs/PROJECT_STATUS.md: {e}")

# ---------------------------------------------------------------------------
# 2. Update docs/TECHNICAL_REFERENCE.md
# ---------------------------------------------------------------------------
NEW_TECH_SECTION = """### 20.7 Mesh Tab Instant Empty State & Position Restoration on Return
Previously, toggling to the Mesh tab checked `if (isRefreshing)` before checking for Mesh empty state. Because background clearnet feed sync was running over Tor, `isRefreshing` remained true for up to a minute, displaying "Curating your feed..." over the Mesh tab despite no clearnet feeds belonging to Mesh mode. Additionally, `loadMoreFeedItems()` in `NoSlopViewModel` triggered `refreshFeeds()` whenever `unseenFeeds.isEmpty()`, which was always true on the Mesh tab.
- `NoSlopViewModel.loadMoreFeedItems()` restricts `isUsingFallback` to feed modes (`actualFilter == "Live Feed" || actualFilter == "Random"`), preventing Mesh switches from firing clearnet sync.
- `UnifiedFeedTab.kt` prioritizes `filterMode == "Mesh"` in the empty state container, instantly rendering the "Nothing New Here" card by default with zero delay.
- The "See Old Posts" button on the "Nothing New Here" card is conditionally rendered only when `viewModel.hasMeshPosts == true`.
- The tab-switching restore mechanism uses a dedicated `LaunchedEffect(restoreItemId, unifiedItems.size)` independent of the one-time cold-start flag, restoring the exact slide position when returning to "All".
- On tab clicks ("All" / "Mesh"), `lastSettledPage` is reset to -1 so tab switches do not trigger false swipe-away events against the vacated slide.

### 20.8 History Filter Chronological Ordering, In-Memory Fast Path & Search Integration
Previously, using the History filter presented several severe defects:
1. `specificNeeded` evaluated to 3 on `isInitialLoad` when switching to History. Because `UnifiedFeedTab.kt` requires `unifiedItems.size >= 5` to trigger infinite scroll, History was permanently trapped at 3 slides.
2. `viewedHistoryDao.insertViewedItem` used `OnConflictStrategy.IGNORE`. Re-viewed content never had its `viewedAt` timestamp refreshed.
3. `sortedHistory` sorted items by `viewedIdsList.indexOf(it.id)`. When an item's ID did not exactly match the stored key representation, `indexOf` returned `-1`, sorting unviewed items to the front ahead of real history.
4. Dismissing the History filter called `syncFilterMode("Live Feed", forceRefresh = true)`. Passing `forceRefresh = true` bypassed `cachedDefaultFeed`, clobbered `savedFeedItemId`, and fired `refreshFeeds()` over Tor, causing a 20-second delay and returning a brand new feed.
5. In `UnifiedFeedTab.kt`, swiping past an item in History triggered `recordItemSwiped` and `markItemViewed`, re-inserting the slide at the current timestamp and displacing the active list.

**Implementations Applied:**
- `ViewedHistoryDao.insertViewedItem` uses `OnConflictStrategy.REPLACE` so every dwell or swipe updates `viewedAt = System.currentTimeMillis()`.
- `NoSlopViewModel.loadMoreFeedItems()` handles `"History"` with an instant in-memory O(1) map resolution against `viewedHistoryRecords.value` (sorted by `viewedAt DESC`), bypassing all heavy feed filtering, regexes, and network sync.
- `viewedHistoryRecords` uses `SharingStarted.Eagerly` in `NoSlopViewModel.kt`, guaranteeing that history records are immediately active and fresh in memory even without UI subscribers.
- Real-time search query filtering is integrated into `loadHistoryBatch`, allowing users to search their viewed history by keyword across titles, authors, excerpts, and content.
- `unconsumed` checks `com.noslop.app.data.getCanonicalItemKey(it) !in currentKeys` and performs a clean return when empty, preventing duplicate re-appending or loopback to slide 0 when scrolling past slide 30.
- Viewed items (`isRead = 1`) in `feed_items` are protected from deletion in `deleteYouTubeItems`, `deleteExpiredItems`, and `clearUnsavedItems`.
- `loadHistoryBatch` automatically reconstructs historical YouTube items on the fly from their `yt_VIDEO_ID` stored in `viewed_history` if their rows were purged in earlier versions, enabling continuous infinite scrolling through all historical content.
- In `UnifiedFeedTab.kt`, `recordItemSwiped` and dwell-time `markItemViewed` are guarded with `if (filterMode != "History" && filterMode != "Saved" && filterMode != "Liked")`. Merely browsing historical items does not mutate timestamps or displace active pager items.
- Dismissing filter chips and clearing modal filters calls `syncFilterMode("Live Feed", forceRefresh = false)`, immediately restoring `cachedDefaultFeed` and scrolling to `savedFeedItemId` with zero delay.

### 20.9 Content Mix Variety, Media Classification & Non-Blocking Background Sync
Previously, users experienced Live Feeds that quickly degraded into 100% video, with audio showing zero items and images/articles repeating stale content:
1. `FeedRepository.kt` wrapped background RSS and category sync in `while (currentlyPlayingUrl != null || isVideoActive) { delay(4000L) }`. Because the user is browsing the feed playing videos, this loop spun indefinitely, completely blocking background fetching of RSS articles, NASA images, and Music audio.
2. In `NoSlopViewModel.kt`, `specificFeeds` for Audio strictly checked `it.mediaType?.contains("audio") == true`. Audio items from the Internet Archive, Openverse, and podcast RSS feeds without explicit `mediaType` fields were rejected and misclassified as articles.
3. Swiping past audio, image, and article items in the Live Feed placed their IDs into `cachedExcludedIds`. In `NoSlopViewModel.kt`, content-type filter tabs (`Videos`, `Audio`, `Images`, `Articles`) were subjected to the aggressive `cachedViewedIds + cachedExcludedIds` purge, clearing out all available items and leaving tabs at 0 items or frozen at 3 items.

**Implementations Applied:**
- Replaced blocking while-loops in `FeedRepository.kt` with non-blocking Tor staggers (`delay(1000ms)`), allowing RSS articles, NASA images, and Music audio to fetch continuously in the background while videos play.
- Unified media detection helpers (`isAudioFeedItem`, `isVideoFeedItem`, `isImageFeedItem`, `isArticleFeedItem`) in `NoSlopViewModel.kt`, ensuring audio tracks (MP3/FLAC/AAC) and images are recognized consistently across the entire pipeline.
- Raised batch size for all specific filters (`Videos`, `Audio`, `Images`, `Articles`, `Mesh`, `History`, `Liked`, `Saved`) from 3 to 30, unblocking infinite scrolling past slide 27.
- Exempted specific content-type filter tabs (`Videos`, `Audio`, `Images`, `Articles`) from the aggressive `cachedViewedIds` purge, restoring full browsing capability in those tabs.
- Added fallback bucket selection in `NoSlopViewModel.loadMoreFeedItems()` so that if all fresh audio, images, or articles in the database have been viewed, the Live Feed interleaver falls back to un-swiped items from the library instead of starving the bucket and backfilling with 100% video.

### 20.10 Tor Resolve Priority & Bandwidth De-Contention
- **Resolve Permit Priority**: In `YouTubeInternalClient.kt`, background preloads (`isPreload = true`) now use a 2-second timeout on `playerResolveGate` and yield immediately if permits are busy, preventing speculative preloads from locking out the active on-screen video.
- **Bandwidth Concentration**: Reduced `MAX_PRELOAD` from 4 to 2 in `PreloadManager.kt`, focusing Tor connection bandwidth on the active and immediate next slide for instant startup on swipe."""

try:
    with open(TECH_REF_FILE, "r", encoding="utf-8") as f:
        tech_src = f.read()

    # Replace Section 20.7 down to the trailing footer
    if "### 20.7 Mesh Tab Instant Empty State" in tech_src:
        prefix = tech_src.split("### 20.7 Mesh Tab Instant Empty State")[0]
        suffix = tech_src.split("---")[-1]
        new_tech = prefix + NEW_TECH_SECTION + "\n\n---\n---" + suffix
        with open(TECH_REF_FILE, "w", encoding="utf-8") as f:
            f.write(new_tech)
        APPLIED.append("docs/TECHNICAL_REFERENCE.md: documented Sections 20.7 - 20.10")
    else:
        FAILED.append("docs/TECHNICAL_REFERENCE.md: anchor not found")
except Exception as e:
    FAILED.append(f"docs/TECHNICAL_REFERENCE.md: {e}")

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
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
