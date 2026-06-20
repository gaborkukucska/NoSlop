# KMP Parity: Complete Gap Analysis & Implementation Plan

## The Problem

The legacy Android app (`app/`) has **28 UI files totalling ~10,400 lines** of mature, feature-rich UI code. The KMP mvp module (`mvp/`) currently has **~1,390 lines** of UI code across its screens/tabs — roughly **13% of the legacy app's UI surface area**. The migration has not delivered parity.

This document catalogues every gap and proposes a phased plan to close them.

---

## Gap Report

### 1. Missing Core Screens (Total: ~4,800 lines not ported)

| Legacy File | Lines | KMP Equivalent | Status |
|---|---|---|---|
| [UnifiedFeedTab.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/ui/UnifiedFeedTab.kt) | 1,264 | None — `App.kt` has a basic `LazyColumn` RSS feed | ❌ **Missing entirely** |
| [MainScreen.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/ui/MainScreen.kt) | 279 | `App.kt` has a simplified `NavigationBar` | ⚠️ Skeleton only |
| [NoSlopViewModel.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/ui/NoSlopViewModel.kt) | 1,154 | None | ❌ **Missing entirely** |
| [HaiNetTab.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/ui/HaiNetTab.kt) | 87 | `App.kt` has inline `MeshScreen` | ⚠️ Different layout |
| [TorWarningPanel.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/ui/TorWarningPanel.kt) | 172 | None | ❌ **Missing** |
| [QRScanScreen.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/ui/QRScanScreen.kt) | 420 | `QrScanner.kt` expect/actual (scan only) | ⚠️ Partial |
| [QRShareSheet.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/ui/QRShareSheet.kt) | 242 | None | ❌ **Missing** |
| [SplashScreen.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/ui/SplashScreen.kt) | 94 | None | ❌ **Missing** |
| [PreloadManager.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/ui/PreloadManager.kt) | 156 | None | ❌ **Missing** |
| [MediaComponents.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/ui/MediaComponents.kt) | 821 | Partial in `ui/media/` | ⚠️ Partial |

### 2. Missing UI Components (~2,600 lines)

| Legacy File | Lines | KMP Equivalent | Status |
|---|---|---|---|
| [FeedCard.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/ui/components/FeedCard.kt) | 321 | None | ❌ **Missing** |
| [ChatThreadScreen.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/ui/components/ChatThreadScreen.kt) | 306 | `ChatScreen.kt` (55-line stub) | ❌ Stub only |
| [CommentsBottomSheet.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/ui/components/CommentsBottomSheet.kt) | 234 | None | ❌ **Missing** |
| [PeerItem.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/ui/components/PeerItem.kt) | 408 | None | ❌ **Missing** |
| [VideoPlayer.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/ui/components/VideoPlayer.kt) | 721 | `ui/media/VideoPlayer.kt` exists | ⚠️ Different impl |
| [AudioPlayer.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/ui/components/AudioPlayer.kt) | 259 | `ui/media/AudioPlayer.kt` exists | ⚠️ Different impl |
| [AvatarCropper.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/ui/components/AvatarCropper.kt) | 245 | None | ❌ **Missing** |

### 3. Missing Tab Screens (~1,060 lines)

| Legacy File | Lines | KMP Equivalent | Status |
|---|---|---|---|
| [SettingsTab.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/ui/tabs/SettingsTab.kt) | 603 | `SettingsScreen.kt` (395 lines) | ⚠️ Different content |
| [DMsTab.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/ui/tabs/DMsTab.kt) | 200 | `ChatScreen.kt` (55-line stub) | ❌ Stub only |
| [NotificationsScreen.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/ui/tabs/NotificationsScreen.kt) | 180 | `NotificationsScreen.kt` (41-line stub) | ❌ Stub only |
| [LogsViewerScreen.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/ui/tabs/LogsViewerScreen.kt) | 137 | `LogsViewerScreen.kt` (44-line stub) | ❌ Stub only |
| [ApiKeysScreen.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/ui/tabs/ApiKeysScreen.kt) | 141 | `ApiKeysScreen.kt` (51-line stub) | ❌ Stub only |

### 4. Missing Data Layer (~3,000 lines)

The legacy data layer is built on **Android Room** with DAOs, entities, and a monolithic `NoSlopRepository`. The KMP module uses **SQLDelight** but only has schemas for mesh posts, peers, messages, engagement, and social data. It is **completely missing**:

| Legacy File | Lines | Status |
|---|---|---|
| `NoSlopRepository.kt` | 405 | ❌ No KMP equivalent |
| `Entities.kt` (FeedItem, FeedSource, NotificationItem, etc.) | 205 | ⚠️ Partial (mesh/peer/message exist in SQLDelight, but `FeedItem`, `FeedSource`, `NotificationItem` are NOT in any `.sq` file) |
| `Daos.kt` | ~400 | ❌ No KMP equivalent |
| `NoSlopDatabase.kt` | ~100 | N/A (replaced by SQLDelight `MeshDatabase`) |
| `ApiKeyRepository.kt` | ~80 | ❌ Missing |
| `MediaSettings.kt` | ~50 | ❌ Missing |
| `NotificationSettings.kt` | ~50 | ❌ Missing |
| `IdentityRepository.kt` | ~200 | ⚠️ Partial (some in `Identity.kt`) |

### 5. Android-Only Dependencies in Legacy UI

These Android-specific APIs are used throughout the legacy UI and **cannot compile in `commonMain`**:

| Dependency | Used In | KMP Alternative |
|---|---|---|
| `android.app.Application` / `AndroidViewModel` | `NoSlopViewModel` | `androidx.lifecycle.ViewModel` (KMP) |
| CameraX (`androidx.camera.*`) | `QRScanScreen`, compose dialog | `expect/actual` in `androidMain` |
| ExoPlayer (`androidx.media3.*`) | `VideoPlayer`, `AudioPlayer` | Already has `expect/actual MediaPlayer` |
| Coil 1 (`coil.compose.*`) | `FeedCard`, `UnifiedFeedTab` | Coil 3 (`coil3.compose.*`) already in deps |
| Accompanist Permissions | `QRScanScreen` | `expect/actual` permission handling |
| `AndroidView` / `PreviewView` | Camera preview, ExoPlayer | `expect/actual` in `androidMain` |
| `android.content.Context` / `LocalContext` | Throughout | `expect/actual` platform utils |
| `android.widget.Toast` | Throughout | `expect/actual` notification |
| ZXing (`com.google.zxing.*`) | `QRShareSheet` | `expect/actual` QR generation |
| Gson (`com.google.gson.*`) | `QRShareSheet` | `kotlinx.serialization` |

### 6. Theme Differences

The legacy [Theme.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/ui/theme/Theme.kt) applies `MyApplicationTheme` with the dark cyberpunk color scheme everywhere. The KMP `App.kt` now calls `MyApplicationTheme` ✅, but the legacy theme file still contains `android.os.Build` and `LocalContext` imports which make it Android-only. The color scheme itself is correct though.

---

## Root Cause Summary

> [!CAUTION]
> The fundamental issue is that the legacy app's UI is **not a thin skin** — it is a deeply integrated Android application. The `NoSlopViewModel` alone (1,154 lines) orchestrates the entire app state through `AndroidViewModel`, Room DAOs, Android `TorService`, `CryptoService`, and the mesh transport. Every screen depends on it. Without porting the ViewModel and its backing data layer, the screens are empty shells.

The KMP `mvp/` module was designed from scratch with its own independent architecture (`MeshClient`, `MeshStore`, `FeedRepository`, `HandleStore`). These two architectures are **fundamentally incompatible** — the old UI cannot simply be dropped into the new framework.

---

## Proposed Approach

> [!IMPORTANT]
> **Recommendation: Create a dedicated branch** (`kmp-parity`) so `main` stays safe with your working legacy app. All parity work happens on the branch and only merges after you verify it.

### Phase 1: Branch & Foundation (~1 hour)
- Create `kmp-parity` branch from `main`
- Add missing SQLDelight schemas: `feedItem.sq`, `feedSource.sq`, `notification.sq`
- Create KMP `NoSlopRepository` that wraps SQLDelight queries with the same API surface as the legacy one

### Phase 2: ViewModel Rewrite (~2 hours)
- Port `NoSlopViewModel` to KMP `ViewModel` (no `Application` dependency)
- Wire all `StateFlow`s to the new SQLDelight-backed repository
- Move Android-specific code (Tor status, CameraX) behind `expect/actual`

### Phase 3: Main Navigation Shell (~1 hour)
- Port `MainScreen.kt` layout: bottom `NavigationBar` with Feed/DMs/HUBs/Settings tabs
- Match exact icons, colors (`AccentGreen`, `PrimaryBlack`, `SurfaceDark`), and badge logic
- Add the center FAB for mesh post composition

### Phase 4: TikTok-style Feed (~3 hours)
- Port `UnifiedFeedTab.kt`: `VerticalPager`, search/filter modal, compose dialog
- Port `FeedCard.kt`: `FullScreenFeedCard`, `FullScreenMeshCard` with overlay interactions
- Port `CommentsBottomSheet.kt` and `OverlayInteractions` (already partially in `ui/media/`)
- Wire video/audio/image rendering through existing `ui/media/` components
- Replace `coil` → `coil3`, `AndroidView` → `expect/actual`

### Phase 5: DMs & Chat (~1.5 hours)
- Port `DMsTab.kt`: peer list with unread badges, conversation selection
- Port `ChatThreadScreen.kt`: message bubbles, encryption status, reactions
- Port `PeerItem.kt`: peer cards with avatar, status, last message preview

### Phase 6: Settings & Support Screens (~1.5 hours)
- Port `SettingsTab.kt` fully (603 lines → currently 395, missing ~40% of features)
- Port `LogsViewerScreen.kt`, `ApiKeysScreen.kt`, `NotificationsScreen.kt` with real functionality
- Port `QRShareSheet.kt` (QR code generation for node sharing)
- Port `TorWarningPanel.kt`
- Port `SplashScreen.kt`

### Phase 7: Platform-Specific Shims (~1 hour)
- `expect/actual` for CameraX (QR scanning) → `androidMain` only
- `expect/actual` for Toast/Clipboard → platform-specific
- `expect/actual` for file picker (media attachment)
- Clean up `Theme.kt` to remove `android.os.Build` / `LocalContext`

### Phase 8: Verification
- `./gradlew -p mvp :composeApp:compileDebugKotlinAndroid` must succeed
- `./gradlew -p mvp :composeApp:assembleRelease` must produce working APK
- User installs and smoke-tests all tabs

---

## Open Questions

> [!IMPORTANT]
> **Branch strategy**: I recommend creating `kmp-parity` from `main` so your working legacy app is never at risk. Do you agree, or would you prefer I continue on `main`?

> [!IMPORTANT]
> **Scope of "same"**: The legacy app uses Android-only features (ExoPlayer inline video, CameraX for photo/video capture in the compose dialog, system Toasts). On the KMP side these will need `expect/actual` wrappers that only work on Android initially, with iOS stubs. Is that acceptable for now?

> [!WARNING]
> **Effort estimate**: This is roughly **10-12 hours** of focused work across 7 phases. The TikTok-style `VerticalPager` feed with full-screen media cards and the `NoSlopViewModel` rewrite are the two largest single items. Shall I proceed with all phases, or would you prefer to prioritize specific ones?
