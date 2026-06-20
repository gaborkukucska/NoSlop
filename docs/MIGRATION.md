# NoSlop — Migration Plan: `app/` → `mvp/`
**Last updated 2026-06-20**

---

## Context

`mvp/` is the canonical codebase from this point forward. It is a **Kotlin Multiplatform + Compose Multiplatform** project that builds both Android and iOS from one shared core. `app/` is **read-only reference only** — consult it for business logic not yet ported, then port and delete. All PRs target `mvp/`. `app/` is retired once feature parity is reached (Phase G).

---

## Current State

| Layer | MVP status | Gap vs old `app/` |
|---|---|---|
| Identity / crypto | ✅ Full (CryptoKit + BouncyCastle, golden-tested) | None |
| Wire protocol | ✅ Full (byte-identical, golden-tested on iOS+Android) | None |
| Mesh routing engine | ✅ Full (`MeshNode` + `MeshFirewall`, tested) | None |
| Encrypted DMs | ✅ Full (X25519+ChaCha20-Poly1305, golden-tested) | None |
| TCP transport | ✅ Full (`SocketTransport`, tested) | None |
| Tor (desktop HUB) | ✅ Bundled `tor` binary, publishes live onions | None |
| Tor (iOS) | ✅ `Tor.framework` via CocoaPods, embedded | None |
| QR scan (iOS) | ✅ AVFoundation, wired to `MeshInvite` | None |
| SQLDelight persistence | ✅ Posts, DMs, peers, meta — iOS + Android | None |
| Desktop HUB | ✅ JVM `HubMain`, runnable | None |
| **Clearnet feed aggregator** | ✅ Full (all 14 clients ported) | None |
| **Full UI** | ✅ Functional mesh tabs, full settings and onboarding | None |
| Notifications | ✅ Full (`expect/actual Notifier`) | None |
| Background sync (Android) | ✅ Full (`BackgroundScheduler` / `NoSlopForegroundService`) | None |
| Media playback | ✅ Full (`MediaPlayer`, ExoPlayer, AVPlayer) | None |
| Onboarding flow | ✅ Full | None |

---

## Tasks — Ordered by Priority

Complete one task fully (compiles + tests green) before starting the next. Each phase is scoped for one LLM session.

---

### Phase A — Feed Layer (✅ Completed)

The MVP has a basic RSS parser. The old app has 14 API clients under `app/src/main/java/com/noslop/app/feeds/api/`.

**A1 — Port `FeedParser.kt`**
- Source: `app/.../feeds/FeedParser.kt` (486 lines, partly decomposed)
- Target: `mvp/.../commonMain/.../feeds/FeedParser.kt`
- Action: rewrite using `kotlinx.serialization` + a multiplatform XML parser (`io.github.pdvrieze.xmlutil`); no `android.*` imports allowed in `commonMain`
- Test: `FeedParserTest.kt` already exists in `mvp/commonTest` — make it pass

**A2 — Port the 14 API clients**
- Source: `app/.../feeds/api/*.kt`
- Target: `mvp/.../commonMain/.../feeds/api/`
- Action: replace `OkHttp` calls with `Ktor HttpClient` (already a dependency); keep business logic verbatim
- Clients (in order of user value): `RedditApiClient`, `HackerNewsApiClient` (add — not in old app), `InvidiousApiClient`, `PodcastIndexClient`, `GuardianApiClient`, `NewsApiClient`, `WikimediaApiClient`, `JamendoApiClient`, `NasaApiClient`, `PexelsApiClient`, `VimeoApiClient`, `InternetArchiveClient`
- Test: one `@Test` per client verifying JSON parsing against a pinned response fixture (no live network in tests)

**A3 — Port `FeedSyncWorker` as background scheduler**
- Source: `app/.../util/FeedSyncWorker.kt`
- Target: `expect/actual BackgroundScheduler` — Android actual uses `WorkManager`; iOS actual uses `BGTaskScheduler`
- Add `commonMain` interface; wire both actuals

---

### Phase B — Onboarding (✅ Completed)

**B1 — Port `OnboardingScreen.kt`**
- Source: `app/.../ui/OnboardingScreen.kt` and its Phase-0 splits under `app/.../ui/onboarding/`
- Target: `mvp/.../commonMain/.../ui/onboarding/` — one Composable per step
- Steps to port (in order): Welcome → Identity generation → Mnemonic backup → Interest selection → Feed preload
- iOS note: the identity step already works (CryptoKit); wire it in
- Android note: BouncyCastle path already works; wire it in

---

### Phase C — Settings + Notifications (✅ Completed)

**C1 — Port `SettingsTab.kt` + `SettingsRepository`**
- Source: `app/.../ui/tabs/SettingsTab.kt`, `app/.../data/SettingsRepository.kt`
- Target: `mvp/.../commonMain/.../ui/tabs/SettingsScreen.kt` + `commonMain/.../data/SettingsRepository.kt`
- Add settings to the SQLDelight schema (`appMeta.sq` already exists — extend it)

**C2 — Port `PreferencesRepository` + `ContentPreferencesScreen`**
- Source: `app/.../data/PreferencesRepository.kt`, `app/.../ui/ContentPreferencesScreen.kt`
- Target: `commonMain` with the same pattern

**C3 — Port `NotificationHelper`**
- Source: `app/.../util/NotificationHelper.kt`
- Target: `expect/actual Notifier` — Android actual wraps `NotificationManager`; iOS actual uses `UNUserNotificationCenter`

---

### Phase D — Media Playback (✅ Completed 2026-06-20)

**D1 — Port `VideoPlayer.kt` + `AudioPlayer.kt`**
- Ported: Created `expect/actual MediaPlayer`. Android actual uses `media3`/ExoPlayer; iOS actual uses `AVPlayer` via `MediaManager.swift` and `IosMediaPlayer` bridge.

**D2 — Port `MediaComponents.kt`**
- Ported: Split into `ui/media/ArticleReader.kt`, `ImageMedia.kt`, `AudioPlayer.kt`, `VideoPlayer.kt`, `EmbedWebViewPlayer.kt`, and `OverlayInteractions.kt`.

---

### Phase E — Service Layer Integration (✅ Completed 2026-06-20)

**E1 — Wire `EngagementRepository` + `MeshSocialRepository` into MVP**
- Ported: Extracted the logic to `commonMain` utilizing SQLDelight queries `engagement.sq` and `social.sq`. Fully replaced legacy Android `Room` data persistence layer with the `MeshStore` abstraction. `IdentityKeyStore` handles persistent cryptographic key integration cleanly.

**E2 — Replace `EncryptedSharedPreferences` with SQLDelight `appMeta`**
- Ported: Legacy `EncryptedSharedPreferences` replaced with KMP equivalents. `IdentityKeyStore` fully integrates X25519 properties inside Android `EncryptedSharedPreferences`.

**E3 — Android foreground service**
- Ported: Defined `BackgroundExecutor` `expect/actual` objects across all platforms. Android actual correctly instantiates `NoSlopForegroundService.kt` to keep MeshSync active and broadcast Android-specific presence heartbeats. Manifest updated with Service declarations.

---

### Phase F — App Store Prep

**Do not start any task in this phase without explicit owner sign-off.** These require credentials and policy decisions.

- **F1 — Android release signing**: guard `signingConfigs.release` with `project.hasProperty(...)` (known papercut from Phase 0)
- **F2 — iOS App Store**: add `ITSAppUsesNonExemptEncryption = false` to Info.plist; add content-moderation narrative; bump bundle id from `com.noslop.mvp` to `com.noslop.app`
- **F3 — Android Play Store**: update `applicationId`, add privacy policy URL, configure ProGuard rules

---

### Phase G — Retire `app/`

**Prerequisite:** Phase E complete and MVP APK smoke-tested on a real device.

- Delete `app/` directory
- Update `settings.gradle.kts` to remove the `:app` include
- Archive the old migration docs as `docs/archived/`
- Update `README.md` — single entry point is now `mvp/`

---

## Execution Rules

```
RULES FOR THIS MIGRATION:
1. Target is `mvp/composeApp/`. Never add `android.*` imports to `commonMain`.
2. Every new file in `commonMain` must compile for BOTH iosSimulatorArm64 AND androidTarget.
3. Platform-specific code goes in `androidMain/` or `iosMain/` behind `expect/actual`.
4. iOS platform bridges follow the pattern in `mvp/iosApp/iosApp/TorManager.swift`.
5. All new logic needs at least one test in `commonTest/` or the relevant `*Test/` source set.
6. Never change the wire protocol (snake_case JSON keys in `Packets.kt`) without an ADR.
7. Run `./gradlew :composeApp:testDebugUnitTest` (Android) AND
   `./gradlew :composeApp:iosSimulatorArm64Test` after every change.
8. Keep files under ~300 lines. One responsibility per file.
9. Consult `app/` for source logic, but never copy `android.*` / `androidx.*` into `commonMain`.
10. Finish one task completely (compiles + tests green) before starting the next.
```

---

## Quick Reference — Key Files in `mvp/`

| What | Where |
|---|---|
| Shared entry point | `commonMain/App.kt` |
| Wire protocol | `commonMain/Packets.kt` |
| Mesh routing | `commonMain/MeshNode.kt` |
| Persistence | `commonMain/MeshStore.kt` + `sqldelight/*.sq` |
| TCP transport | `commonMain/SocketTransport.kt` |
| Tor control | `commonMain/TorControl.kt`, `TorService.kt` |
| iOS Tor bridge | `iosApp/iosApp/TorManager.swift` |
| iOS QR bridge | `iosApp/iosApp/QrScanner.swift` |
| iOS Media bridge | `iosApp/iosApp/MediaManager.swift` |
| Desktop HUB | `jvmMain/HubMain.kt` |
| Android entry | `androidMain/MainActivity.kt` |
| iOS entry | `iosMain/MainViewController.kt` |