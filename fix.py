#!/usr/bin/env python3
import sys

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

VIEW_MODEL = "app/src/main/java/com/noslop/app/ui/NoSlopViewModel.kt"
FEED_TAB = "app/src/main/java/com/noslop/app/ui/UnifiedFeedTab.kt"

# ---------------------------------------------------------------------------
# 1. NoSlopViewModel.kt: Expose savedActiveItemId StateFlow and load on init
# ---------------------------------------------------------------------------
OLD_SAVED_DECL = """    private var savedFeedItemId: String? = null
    val currentSavedFeedItemId: String? get() = savedFeedItemId"""

NEW_SAVED_DECL = """    private var savedFeedItemId: String? = null
    val currentSavedFeedItemId: String? get() = savedFeedItemId
    private val _savedActiveItemId = MutableStateFlow<String?>(null)
    val savedActiveItemId: StateFlow<String?> = _savedActiveItemId.asStateFlow()"""

edit(VIEW_MODEL, OLD_SAVED_DECL, NEW_SAVED_DECL, "NoSlopViewModel.kt: expose savedActiveItemId StateFlow")

# ---------------------------------------------------------------------------
# 2. NoSlopViewModel.kt: Load savedActiveItemId on ViewModel init
# ---------------------------------------------------------------------------
OLD_VM_INIT = """        viewModelScope.launch {
            _appLanguage.value = repository.getAppLanguage()"""

NEW_VM_INIT = """        viewModelScope.launch {
            val savedId = repository.getAppSetting("saved_feed_active_id")
            savedFeedItemId = savedId
            _savedActiveItemId.value = savedId
        }

        viewModelScope.launch {
            _appLanguage.value = repository.getAppLanguage()"""

edit(VIEW_MODEL, OLD_VM_INIT, NEW_VM_INIT, "NoSlopViewModel.kt: load savedActiveItemId in init")

# ---------------------------------------------------------------------------
# 3. NoSlopViewModel.kt: Do not truncate candidateIds with subList; keep full feed
# ---------------------------------------------------------------------------
OLD_RESTORE_FEED = """                        val savedIdsStr = repository.getAppSetting("saved_feed_list")
                        val savedActiveId = repository.getAppSetting("saved_feed_active_id")
                        if (!savedIdsStr.isNullOrEmpty() && currentFilterMode == "Live Feed" && !isSearchModeActive) {
                            val idList = savedIdsStr.split(",")
                            val activeIdxInSaved = if (!savedActiveId.isNullOrEmpty()) idList.indexOf(savedActiveId) else 0
                            val candidateIds = if (activeIdxInSaved >= 0) idList.subList(activeIdxInSaved, idList.size) else idList
                            val restoredFeed = candidateIds.mapNotNull { id ->
                                if (id in cachedExcludedIds) return@mapNotNull null
                                val feed = feeds.find { it.id == id }
                                if (feed != null) UnifiedItem.Feed(feed)
                                else {
                                    val mesh = meshes.find { it.id == id }
                                    if (mesh != null) UnifiedItem.Mesh(mesh) else null
                                }
                            }
                            if (restoredFeed.isNotEmpty()) {
                                cachedDefaultFeed = restoredFeed
                                _unifiedFeed.value = restoredFeed
                                savedFeedItemId = savedActiveId
                                sessionLoadedIds.addAll(restoredFeed.map { it.id })

                                viewModelScope.launch {
                                    if (savedActiveId != null) {
                                        _restoreScrollPositionEvent.emit(savedActiveId)
                                    }
                                }"""

NEW_RESTORE_FEED = """                        val savedIdsStr = repository.getAppSetting("saved_feed_list")
                        val savedActiveId = repository.getAppSetting("saved_feed_active_id")
                        if (!savedIdsStr.isNullOrEmpty() && currentFilterMode == "Live Feed" && !isSearchModeActive) {
                            val idList = savedIdsStr.split(",")
                            // Keep full restored feed so user can scroll both up and down
                            val candidateIds = idList
                            val restoredFeed = candidateIds.mapNotNull { id ->
                                if (id in cachedExcludedIds) return@mapNotNull null
                                val feed = feeds.find { it.id == id }
                                if (feed != null) UnifiedItem.Feed(feed)
                                else {
                                    val mesh = meshes.find { it.id == id }
                                    if (mesh != null) UnifiedItem.Mesh(mesh) else null
                                }
                            }
                            if (restoredFeed.isNotEmpty()) {
                                cachedDefaultFeed = restoredFeed
                                _unifiedFeed.value = restoredFeed
                                savedFeedItemId = savedActiveId
                                _savedActiveItemId.value = savedActiveId
                                sessionLoadedIds.addAll(restoredFeed.map { it.id })

                                viewModelScope.launch {
                                    if (savedActiveId != null) {
                                        _restoreScrollPositionEvent.emit(savedActiveId)
                                    }
                                }"""

edit(VIEW_MODEL, OLD_RESTORE_FEED, NEW_RESTORE_FEED, "NoSlopViewModel.kt: preserve full restored feed without subList truncation")

# ---------------------------------------------------------------------------
# 4. NoSlopViewModel.kt: Do not wipe saved position on refreshLiveFeed
# ---------------------------------------------------------------------------
OLD_REFRESH_WIPE = """                // Clear saved feed persistence so the DB flow doesn't resurrect old state
                repository.putAppSetting("saved_feed_list", "")
                repository.putAppSetting("saved_feed_active_id", "")"""

NEW_REFRESH_WIPE = """                // Keep saved position intact so user does not lose their place on refresh"""

edit(VIEW_MODEL, OLD_REFRESH_WIPE, NEW_REFRESH_WIPE, "NoSlopViewModel.kt: stop wiping saved position on refreshLiveFeed")

# ---------------------------------------------------------------------------
# 5. UnifiedFeedTab.kt: Add hasRestoredInitialPosition guard against Page 0 clobbering
# ---------------------------------------------------------------------------
OLD_TAB_RESTORE = """    var restoreItemId by remember { mutableStateOf<String?>(null) }
    
    LaunchedEffect(Unit) {
        viewModel.restoreScrollPositionEvent.collect { itemId ->
            restoreItemId = itemId
        }
    }
    
    // Explicitly wait for unifiedItems state to populate before scrolling
    LaunchedEffect(restoreItemId, unifiedItems) {
        if (restoreItemId != null && unifiedItems.isNotEmpty()) {
            val index = unifiedItems.indexOfFirst { it.id == restoreItemId }
            if (index >= 0) {
                pagerState.scrollToPage(index)
                restoreItemId = null // Consume event
            }
        }
    }

    LaunchedEffect(pagerState.settledPage) {
        if (pagerState.settledPage in unifiedItems.indices) {
            val currentItem = unifiedItems[pagerState.settledPage]
            
            if (currentItem is UnifiedItem.Tutorial) {
                viewModel.setFeedTutorialStep(currentItem.step + 1)
            } else if (currentTutStep != -1 && currentTutStep < 5) {
                viewModel.completeFeedTutorial()
            }

            if (currentItem !is UnifiedItem.Tutorial) {
                // Only save position if we aren't currently waiting to restore a saved position
                if (filterMode == "Live Feed" && !searchResultsActive && !isRefreshing && restoreItemId == null) {
                    // Do not let startup page 0 overwrite a pending saved active slide position
                    val currentSaved = viewModel.currentSavedFeedItemId
                    if (currentSaved == null || currentSaved == currentItem.id || pagerState.settledPage > 0) {
                        viewModel.saveFeedPosition(currentItem.id)
                    }
                }"""

NEW_TAB_RESTORE = """    var restoreItemId by remember { mutableStateOf<String?>(null) }
    var hasRestoredInitialPosition by remember { mutableStateOf(false) }
    val savedTargetId by viewModel.savedActiveItemId.collectAsState()
    
    LaunchedEffect(Unit) {
        viewModel.restoreScrollPositionEvent.collect { itemId ->
            restoreItemId = itemId
        }
    }
    
    // Scroll directly to saved position once unifiedItems populates on cold start
    val activeRestoreTarget = restoreItemId ?: savedTargetId
    LaunchedEffect(activeRestoreTarget, unifiedItems.size) {
        if (!hasRestoredInitialPosition && !activeRestoreTarget.isNullOrEmpty() && unifiedItems.isNotEmpty()) {
            val index = unifiedItems.indexOfFirst { it.id == activeRestoreTarget }
            if (index >= 0) {
                pagerState.scrollToPage(index)
                hasRestoredInitialPosition = true
                restoreItemId = null
            }
        } else if (unifiedItems.isNotEmpty() && activeRestoreTarget.isNullOrEmpty()) {
            hasRestoredInitialPosition = true
        }
    }

    LaunchedEffect(pagerState.settledPage) {
        if (pagerState.settledPage in unifiedItems.indices) {
            val currentItem = unifiedItems[pagerState.settledPage]
            
            if (currentItem is UnifiedItem.Tutorial) {
                viewModel.setFeedTutorialStep(currentItem.step + 1)
            } else if (currentTutStep != -1 && currentTutStep < 5) {
                viewModel.completeFeedTutorial()
            }

            if (currentItem !is UnifiedItem.Tutorial) {
                // Only save position AFTER initial restore has completed so startup page 0 never clobbers saved state
                if (hasRestoredInitialPosition && filterMode == "Live Feed" && !searchResultsActive && !isRefreshing) {
                    viewModel.saveFeedPosition(currentItem.id)
                }"""

edit(FEED_TAB, OLD_TAB_RESTORE, NEW_TAB_RESTORE, "UnifiedFeedTab.kt: implement hasRestoredInitialPosition to protect saved feed position")

print("\n=== PATCH EXECUTION RESULTS ===")
for item in APPLIED:
    print(f"  [APPLIED] {item}")

if FAILED:
    print("\nErrors occurred:")
    for item in FAILED:
        print(f"  [FAILED]  {item}")
    sys.exit(1)
else:
    print(f"\nAll {len(APPLIED)} feed position patches applied successfully!")
