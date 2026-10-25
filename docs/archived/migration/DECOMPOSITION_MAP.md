# Decomposition Map — Splitting the Monolith Files

Grounded in the actual code on `main` as of 2026-06-16. Each split preserves behavior; run the Phase 0.2 golden-vector tests after every extraction. Target: one responsibility per file, ~300 lines max.

> **Principle:** these splits are *mechanical re-organization*, not redesign. Move code as-is, add docs, keep the public surface identical, let the tests prove equivalence. Redesign happens later, per-file, once it's small enough to reason about.

---

## 1. `data/NoSlopRepository.kt` (1,474 lines) — **god-object, split first**

Today it mixes at least six unrelated domains. Proposed split into focused repositories, coordinated by a thin facade:

| New file | Responsibility (methods to move) |
|---|---|
| `data/IdentityRepository.kt` *(exists — consolidate here)* | identity load/save, lock/unlock, handle, onion address, onboarding flag, encryption-active |
| `data/SettingsRepository.kt` | media settings, notification settings, foreground-service setting |
| `data/FeedRepository.kt` | sources & items CRUD, `refreshFeeds` (382–532), `searchCustomFeed`, read/saved state, `clearFeedData`, aggregator toggle, source recovery/migration |
| `data/PreferencesRepository.kt` | categories, per-category keywords, negative keywords, language, music/video genres, creator keywords, user profile |
| `data/EngagementRepository.kt` | viewed tracking, swipe tracking, exclusion sets |
| `data/MeshSocialRepository.kt` | messages/comments/reactions/votes flows + presence heartbeat |
| `data/NoSlopRepository.kt` *(slimmed facade)* | wires the above together; `factoryReset`; holds `meshTransport` reference |

**WHY:** the ViewModel and mesh layers both depend on this class; a facade keeps their call sites unchanged while the internals become small and testable.

## 2. `mesh/MeshPacketHandler.kt` (840 lines) — **one giant `handleIncomingPacket`**

Currently a single suspend function that branches on packet type. Split into a dispatcher + per-type handlers:

| New file | Responsibility |
|---|---|
| `mesh/handlers/PacketDispatcher.kt` | validates signature/TTL/dedup/rate-limit, then routes to the right handler |
| `mesh/handlers/PostPacketHandler.kt` | POST (incl. clearnet anchor logic) |
| `mesh/handlers/ReactionPacketHandler.kt` | REACTION add/remove + counts |
| `mesh/handlers/CommentPacketHandler.kt` | comments/replies |
| `mesh/handlers/DmPacketHandler.kt` | encrypted DMs |
| `mesh/handlers/HandshakePacketHandler.kt` | connection request/accept/reject, peer trust |
| `mesh/handlers/PresencePacketHandler.kt` | presence/heartbeat |
| `mesh/handlers/MediaPacketHandler.kt` | media offer/request/transfer |

**WHY:** packet handling is the security-critical hot path. Per-type handlers are individually testable (Stage 0.2) and let an AI safely modify one packet type without touching the others. The dispatcher concentrates the cross-cutting checks (sig/TTL/dedup/rate-limit) in one audited place.

## 3. `ui/NoSlopViewModel.kt` (1,102 lines) — **feature god-VM**

Split by feature into focused ViewModels (or, if shared state makes that hard initially, into `// region`-delimited partials first, then extract):

| New file | Responsibility |
|---|---|
| `ui/feed/FeedViewModel.kt` | feed load/refresh/search/pagination, read/viewed/swipe/saved, sources |
| `ui/social/SocialViewModel.kt` | posts, comments, reactions, votes, clearnet→mesh injection |
| `ui/peers/PeersViewModel.kt` | connect/handshake/accept/reject/trust/remove, chat peer selection, DMs |
| `ui/identity/IdentityViewModel.kt` | mnemonic, onboarding, lock/unlock, backup import/export, factory reset, profile |
| `ui/system/SystemViewModel.kt` | Tor start/status, foreground service, settings, log copy |

**WHY:** screens currently over-subscribe to one VM. Feature VMs shrink recomposition scope and give AI a small, bounded surface per screen.

## 4. `ui/OnboardingScreen.kt` (1,236 lines)

Split one composable per onboarding step + shared pieces: `onboarding/WelcomeStep.kt`, `IdentityStep.kt`, `WordCloudBackupStep.kt`, `InterestSelectionStep.kt`, `FeedPreloadStep.kt`, plus `onboarding/OnboardingScaffold.kt` (progress/nav chrome) and `onboarding/OnboardingState.kt`.

## 5. `ui/UnifiedFeedTab.kt` (1,164 lines)

Split into `feed/FeedList.kt` (the pager/list), `feed/FeedCardHost.kt` (per-item rendering selection), and `feed/FeedInteractions.kt` (like/share/comment → mesh broadcast wiring). Reuse the existing `ui/components/FeedCard.kt`.

## 6. `ui/MediaComponents.kt` (821 lines)

Split per media type: `media/SegmentedArticleReader.kt`, `media/ImageMedia.kt`, `media/AudioMedia.kt`, `media/VideoMedia.kt`, sharing a small `media/MediaScaffold.kt`. Player engines (`VideoPlayer.kt`, `AudioPlayer.kt`) stay separate and become Phase-1 `expect`/`actual` candidates.

## 7. Second-tier files > 300 lines (after the big six)

`ContentPreferencesScreen.kt` (653), `VideoPlayer.kt` (604), `SettingsTab.kt` (538), `FeedParser.kt` (486), `QRScanScreen.kt` (484), `InvidiousApiClient.kt` (451), `PeerItem.kt` (408), `TorService.kt` (359), `CryptoService.kt` (352). Split by the same one-responsibility rule; several (`VideoPlayer`, `QRScanScreen`, `TorService`, `CryptoService`) are platform-adapter candidates flagged for Phase 1.

---

## Android-only touchpoints to flag for Phase 1 (running list)

Populate during Stage 0.4. Known so far from imports analysis:
- `android.content.Context` threaded through `NoSlopRepository` and many call sites → will need a KMP-friendly abstraction.
- Android Keystore + Bouncy Castle in `CryptoService` → `expect/actual SecureKeyStore`.
- `guardianproject` Tor libs in `TorService` → `expect/actual TorController`.
- `media3`/ExoPlayer + WebView in `VideoPlayer`/`MediaComponents` → `expect/actual MediaPlayer`.
- CameraX + ML Kit in `QRScanScreen` → `expect/actual QrScanner`.
- `EncryptedSharedPreferences` (security-crypto) → `expect/actual SecurePrefs`.
- Room annotations across `data/` → migrate to SQLDelight in Phase 1.
- `WorkManager` (`FeedSyncWorker`) → `expect/actual BackgroundScheduler`.
