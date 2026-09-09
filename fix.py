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

STATUS_FILE = "docs/PROJECT_STATUS.md"
TECH_FILE = "docs/TECHNICAL_REFERENCE.md"

# ---------------------------------------------------------------------------
# 1. Update docs/PROJECT_STATUS.md
# ---------------------------------------------------------------------------
OLD_STATUS_TARGET = """* **Cold-Start Slide Position Persistence (`UnifiedFeedTab.kt`, `NoSlopViewModel.kt`)**:"""

NEW_STATUS_ITEM = """* **Mesh Tab Instant "Nothing New Here" Default & Tab-Switch Restore (`UnifiedFeedTab.kt`, `NoSlopViewModel.kt`)**:
  * Prioritized `filterMode == "Mesh"` in the empty-feed container of `UnifiedFeedTab.kt`, completely decoupling the Mesh view from `isRefreshing`. Toggling to Mesh now displays "Nothing New Here" and the "See Old Posts" button instantly with zero delay, rather than stalling for 30s behind clearnet background sync.
  * Restricted `isUsingFallback` in `NoSlopViewModel.loadMoreFeedItems()` to feed modes (`Live Feed` / `Random`), preventing Mesh mode from ever misinterpreting empty clearnet lists as feed exhaustion or triggering redundant clearnet refreshes.
  * Decoupled `restoreScrollPositionEvent` handling in `UnifiedFeedTab.kt` into a dedicated `LaunchedEffect(restoreItemId, unifiedItems.size)`, guaranteeing that returning from Mesh back to "All" restores the exact slide position instantly.
  * Reset `lastSettledPage = -1` on All/Mesh toggle clicks so switching tabs never falsely marks the vacated slide as swiped away.
* **Cold-Start Slide Position Persistence (`UnifiedFeedTab.kt`, `NoSlopViewModel.kt`)**:"""

edit(STATUS_FILE, OLD_STATUS_TARGET, NEW_STATUS_ITEM, "docs/PROJECT_STATUS.md: document Mesh instant default and tab-switch restore")

# ---------------------------------------------------------------------------
# 2. Update docs/TECHNICAL_REFERENCE.md
# ---------------------------------------------------------------------------
OLD_TECH_TARGET = """- Search results replace the active feed instead of appending to the live feed.

---"""

NEW_TECH_SECTION = """- Search results replace the active feed instead of appending to the live feed.

### 20.7 Mesh Tab Instant Empty State & Position Restoration on Return
Previously, toggling to the Mesh tab checked `if (isRefreshing)` before checking for Mesh empty state. Because background clearnet feed sync was running over Tor, `isRefreshing` remained true for up to a minute, displaying "Curating your feed..." over the Mesh tab despite no clearnet feeds belonging to Mesh mode. Additionally, `loadMoreFeedItems()` in `NoSlopViewModel` triggered `refreshFeeds()` whenever `unseenFeeds.isEmpty()`, which was always true on the Mesh tab.
- `NoSlopViewModel.loadMoreFeedItems()` restricts `isUsingFallback` to feed modes (`actualFilter == "Live Feed" || actualFilter == "Random"`), preventing Mesh switches from firing clearnet sync.
- `UnifiedFeedTab.kt` prioritizes `filterMode == "Mesh"` in the empty state container, instantly rendering the "Nothing New Here" card with the "See Old Posts" button by default with zero delay.
- The tab-switching restore mechanism uses a dedicated `LaunchedEffect(restoreItemId, unifiedItems.size)` independent of the one-time cold-start flag, restoring the exact slide position when returning to "All".
- On tab clicks ("All" / "Mesh"), `lastSettledPage` is reset to -1 so tab switches do not trigger false swipe-away events against the vacated slide.

---"""

edit(TECH_FILE, OLD_TECH_TARGET, NEW_TECH_SECTION, "docs/TECHNICAL_REFERENCE.md: add Section 20.7 Mesh empty state & position restore")

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
