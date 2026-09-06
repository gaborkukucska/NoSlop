#!/usr/bin/env python3
import os
import sys

APPLIED = []
FAILED = []

def edit(path, old, new, label):
    if not os.path.exists(path):
        FAILED.append(f"{label}: file not found {path}")
        return
    with open(path, "r", encoding="utf-8") as f:
        src = f.read()
    if old not in src:
        FAILED.append(f"{label}: anchor not found in {path}")
        return
    if src.count(old) != 1:
        FAILED.append(f"{label}: anchor matched {src.count(old)} times, expected 1 in {path}")
        return
    with open(path, "w", encoding="utf-8") as f:
        f.write(src.replace(old, new, 1))
    APPLIED.append(label)

VM_FILE = "app/src/main/java/com/noslop/app/ui/NoSlopViewModel.kt"

# 1. Fix syncFilterMode so it never drops _unifiedFeed to emptyList and properly restores Live Feed
OLD_SYNC_FILTER = """    fun syncFilterMode(mode: String, forceRefresh: Boolean = false) {
        if (currentFilterMode != mode || forceRefresh) {
            currentFilterMode = mode
            if (mode == "Live Feed") {
                activeSearchQuery = ""
                isSearchModeActive = false
                if (cachedDefaultFeed.isNotEmpty() && !forceRefresh) {
                    _unifiedFeed.value = cachedDefaultFeed.toList()
                    sessionLoadedIds.clear()
                    sessionLoadedIds.addAll(cachedDefaultFeed.map { it.id })
                    viewModelScope.launch {
                        kotlinx.coroutines.delay(50)
                        if (savedFeedItemId != null) {
                            _restoreScrollPositionEvent.emit(savedFeedItemId!!)
                        } else {
                            _scrollToTopEvent.emit(Unit)
                        }
                    }
                } else {
                    _unifiedFeed.value = emptyList()
                    sessionLoadedIds.clear()
                    loadMoreFeedItems("Live Feed")
                    viewModelScope.launch {
                        kotlinx.coroutines.delay(150)
                        _scrollToTopEvent.emit(Unit)
                    }
                }
            } else {
                _unifiedFeed.value = emptyList()
                sessionLoadedIds.clear()
                loadMoreFeedItems(mode)
                if (forceRefresh) {
                    viewModelScope.launch {
                        kotlinx.coroutines.delay(150)
                        _scrollToTopEvent.emit(Unit)
                    }
                }
            }
        }
    }"""

NEW_SYNC_FILTER = """    fun syncFilterMode(mode: String, forceRefresh: Boolean = false) {
        if (currentFilterMode != mode || forceRefresh) {
            currentFilterMode = mode
            if (mode == "Live Feed") {
                activeSearchQuery = ""
                isSearchModeActive = false
                if (cachedDefaultFeed.isNotEmpty() && !forceRefresh) {
                    _unifiedFeed.value = cachedDefaultFeed.toList()
                    sessionLoadedIds.clear()
                    sessionLoadedIds.addAll(cachedDefaultFeed.map { it.id })
                    viewModelScope.launch {
                        kotlinx.coroutines.delay(50)
                        if (savedFeedItemId != null) {
                            _restoreScrollPositionEvent.emit(savedFeedItemId!!)
                        } else {
                            _scrollToTopEvent.emit(Unit)
                        }
                    }
                } else {
                    sessionLoadedIds.clear()
                    loadMoreFeedItems("Live Feed")
                    viewModelScope.launch {
                        kotlinx.coroutines.delay(150)
                        _scrollToTopEvent.emit(Unit)
                    }
                }
            } else {
                sessionLoadedIds.clear()
                loadMoreFeedItems(mode)
                if (forceRefresh) {
                    viewModelScope.launch {
                        kotlinx.coroutines.delay(150)
                        _scrollToTopEvent.emit(Unit)
                    }
                }
            }
        }
    }"""

edit(VM_FILE, OLD_SYNC_FILTER, NEW_SYNC_FILTER, "NoSlopViewModel.kt: safe syncFilterMode without emptyList crash")

# 2. Fix unseenFeeds fallback when all DB items are in historical cache
OLD_LOAD_FEEDS_EXCL = """        var unseenFeeds = allFeeds.filter { 
            if (isPersistentList) {
                it.id !in currentIds
            } else {
                val cKey = com.noslop.app.data.getCanonicalItemKey(UnifiedItem.Feed(it))
                val normId = com.noslop.app.data.normalizeFeedItemId(it.id, it.url ?: "")
                it.id !in exclusionIds &&
                it.id !in cachedViewedIds &&
                normId !in cachedViewedIds &&
                cKey !in cachedViewedIds &&
                it.title.lowercase().trim() !in readTitles && 
                cKey !in excludedFeedKeys
            }
        }"""

NEW_LOAD_FEEDS_EXCL = """        var unseenFeeds = allFeeds.filter { 
            if (isPersistentList) {
                it.id !in currentIds
            } else {
                val cKey = com.noslop.app.data.getCanonicalItemKey(UnifiedItem.Feed(it))
                it.id !in exclusionIds &&
                it.id !in cachedViewedIds &&
                cKey !in cachedExcludedIds &&
                it.title.lowercase().trim() !in readTitles && 
                cKey !in excludedFeedKeys
            }
        }
        // Fallback: If all local items have been viewed in previous sessions, show un-swiped items rather than an empty feed
        if (unseenFeeds.isEmpty() && !isPersistentList && allFeeds.isNotEmpty() && !isSearchActive) {
            unseenFeeds = allFeeds.filter { it.id !in exclusionIds && it.id !in cachedExcludedIds }
        }"""

edit(VM_FILE, OLD_LOAD_FEEDS_EXCL, NEW_LOAD_FEEDS_EXCL, "NoSlopViewModel.kt: unseenFeeds fallback to prevent dead feed")

# 3. Fix specific items assignment in loadMoreFeedItems
OLD_SPECIFIC_ASSIGN = """            sessionLoadedIds.addAll(batch.map { it.id })
            if (isInjection) {
                val currentList = _unifiedFeed.value.toMutableList()
                val upFront = batch.take(3)
                val dispersed = batch.drop(3)
                
                val bufferSize = 3.coerceAtMost(currentList.size)
                currentList.addAll(bufferSize, upFront)
                
                dispersed.forEachIndexed { i, item ->
                    val insertIndex = (bufferSize + 3 + 2 + (i * 2)).coerceAtMost(currentList.size)
                    currentList.add(insertIndex, item)
                }
                
                _unifiedFeed.value = currentList.distinctBy { com.noslop.app.data.getCanonicalItemKey(it) }
                viewModelScope.launch {
                    kotlinx.coroutines.delay(150)
                    if (upFront.isNotEmpty()) {
                        _restoreScrollPositionEvent.emit(upFront.first().id)
                    }
                }
            } else {
                _unifiedFeed.value = (_unifiedFeed.value + batch).distinctBy { com.noslop.app.data.getCanonicalItemKey(it) }
                if (isInitialLoad) {
                    viewModelScope.launch {
                        kotlinx.coroutines.delay(150)
                        _scrollToTopEvent.emit(Unit)
                    }
                }
            }
            return"""

NEW_SPECIFIC_ASSIGN = """            sessionLoadedIds.addAll(batch.map { it.id })
            if (isInjection) {
                val currentList = _unifiedFeed.value.toMutableList()
                val upFront = batch.take(3)
                val dispersed = batch.drop(3)
                
                val bufferSize = 3.coerceAtMost(currentList.size)
                currentList.addAll(bufferSize, upFront)
                
                dispersed.forEachIndexed { i, item ->
                    val insertIndex = (bufferSize + 3 + 2 + (i * 2)).coerceAtMost(currentList.size)
                    currentList.add(insertIndex, item)
                }
                
                _unifiedFeed.value = currentList.distinctBy { com.noslop.app.data.getCanonicalItemKey(it) }
                viewModelScope.launch {
                    kotlinx.coroutines.delay(150)
                    if (upFront.isNotEmpty()) {
                        _restoreScrollPositionEvent.emit(upFront.first().id)
                    }
                }
            } else {
                // If switching filters, replace feed directly with new batch rather than appending
                _unifiedFeed.value = if (actualFilter == "Mesh" || actualFilter == "My Content" || currentFilterMode != "Live Feed") {
                    batch.distinctBy { com.noslop.app.data.getCanonicalItemKey(it) }
                } else {
                    (_unifiedFeed.value + batch).distinctBy { com.noslop.app.data.getCanonicalItemKey(it) }
                }
                viewModelScope.launch {
                    kotlinx.coroutines.delay(150)
                    _scrollToTopEvent.emit(Unit)
                }
            }
            return"""

edit(VM_FILE, OLD_SPECIFIC_ASSIGN, NEW_SPECIFIC_ASSIGN, "NoSlopViewModel.kt: replace feed on filter change")

# 4. Protect VerticalPager key lambda in UnifiedFeedTab.kt against crashes
TAB_FILE = "app/src/main/java/com/noslop/app/ui/UnifiedFeedTab.kt"

OLD_PAGER_KEY = """            VerticalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 2,
                key = { index -> unifiedItems[index].id }
            )"""

NEW_PAGER_KEY = """            VerticalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 2,
                key = { index -> if (index in unifiedItems.indices) unifiedItems[index].id else "item_$index" }
            )"""

edit(TAB_FILE, OLD_PAGER_KEY, NEW_PAGER_KEY, "UnifiedFeedTab.kt: bounds-protect VerticalPager key")

# 5. Over Tor, preload 1 slide ahead to avoid saturating Tor daemon
OLD_PRELOAD_TOR = """        val overTor = com.noslop.app.net.HttpClientProvider.useTorForClearnet
        // Stream isolation guarantees separate circuits, allowing 2 forward preloads without circuit contention
        val forwardPreloadLimit = 2"""

NEW_PRELOAD_TOR = """        val overTor = com.noslop.app.net.HttpClientProvider.useTorForClearnet
        // Over Tor on mobile, preload 1 slide forward to prevent choking the Tor daemon
        val forwardPreloadLimit = if (overTor) 1 else 2"""

edit(TAB_FILE, OLD_PRELOAD_TOR, NEW_PRELOAD_TOR, "UnifiedFeedTab.kt: throttle Tor preload to 1 slide ahead")

print("\n=== PATCH EXECUTION RESULTS ===")
for item in APPLIED:
    print(f"  [APPLIED] {item}")

if FAILED:
    print("\nErrors:")
    for item in FAILED:
        print(f"  [FAILED]  {item}")
    sys.exit(1)
else:
    print("\nAll stability patches applied successfully!")
