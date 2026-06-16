# Phase 0 — Decompose & Test the Core

**Status:** 🟡 In progress · **Started:** 2026-06-16

> **Goal:** Make the existing Android codebase AI-maintainable and migration-ready **without changing behavior.** When this phase is done, the code is split into small single-responsibility files, organized into clear module-shaped packages, and the crypto/protocol core is locked behind golden-vector tests. This is the foundation every later phase builds on.

**Definition of done for Phase 0:**
- [ ] No source file exceeds ~300 lines (target; documented exceptions allowed with a `// WHY:` note).
- [ ] Code is organized into module-shaped packages: `core.crypto`, `core.mesh`, `core.data`, `core.feeds`, plus `app` (Android). *(Packages first; real Gradle modules happen in Phase 1.)*
- [ ] Golden-vector test suite passes for all crypto + wire-protocol round-trips.
- [ ] Every refactored file meets the code-documentation standard (see `README.md`).
- [ ] App still builds and behaves identically (manual smoke test + existing tests green).

---

## Stage 0.1 — Project setup & baseline  🟡 in progress

- [x] Clean full clone at `~/Documents/NoSlop-xplatform`
- [x] Branch `feat/cross-platform-migration` created off `main`
- [x] Migration tracking docs scaffolded under `docs/migration/`
- [x] Monolith structure mapped (see `DECOMPOSITION_MAP.md`)
- [x] Establish a build baseline: **debug Kotlin compiles clean** (`:app:compileDebugKotlin` BUILD SUCCESSFUL, 0 errors) once dummy signing props are supplied. Only deprecation warnings present. Release signing config is a contributor papercut (logged below).
- [ ] Add a `.editorconfig` / formatting baseline so AI edits stay consistent
- [x] Commit the scaffold

## Stage 0.2 — Golden-vector tests for the core (do this BEFORE refactoring)  🟢 substantially done

Lock current behavior so refactors are provably safe. Tests live in `app/src/test/`. **31 tests, all green**
(`./gradlew :app:testDebugUnitTest` with dummy `-PNOSLOP_*` signing props). Strategy + the pure-JVM /
Robolectric split is recorded in **ADR-007**.

- [x] **Crypto vectors** (`CryptoService`) — `CryptoDerivationTest` (pure JVM) + `CryptoServiceRobolectricTest`:
  - [x] Ed25519 sign → verify (rejects tampered payload, tampered sig, and wrong public key)
  - [x] X25519 key agreement (A↔B DM round-trip proves the shared secret is symmetric/deterministic)
  - [x] DM encrypt → decrypt round-trip (ChaCha20-Poly1305), incl. wrong-key → `null` (AEAD auth fail, never throws)
  - [x] Onion v3 address derivation from a known pubkey — golden vector vs. independent Python reference
  - [x] Tripcode (SHA3-256 Base32) from a known pubkey — golden vector vs. independent Python reference
- [x] **Mnemonic vectors** (`MnemonicGenerator`) — `MnemonicGeneratorTest` + Robolectric `deriveSeedB64`:
      PBKDF2WithHmacSHA512 seed golden vector, determinism, salt sensitivity, 12-word generation.
- [x] **Wire-protocol vectors** (`Packets` / `NetworkPacket`) — `WireProtocolTest`: envelope + POST/REACTION
      round-trips, snake_case wire keys pinned, typed-accessor type safety, unknown fields tolerated.
- [x] **Packet handling**: TTL / dedup (bounded LRU) / per-sender rate limit — `GossipServiceTest`, table-driven.
      *(Note: this logic lives in `GossipService.processIncoming`, NOT `MeshPacketHandler` — the original
      plan had the location wrong. `MeshPacketHandler` is a pure type-dispatcher.)*
- [x] Record any behavior that looks like a *bug* (logged below + in `PROGRESS_LOG.md`)

**Remaining (optional hardening, not blocking Stage 0.3):**
- [ ] Round-trip vectors for the *remaining* packet payload types (COMMENT, VOTE, handshake, SYNC_*, media) —
      `WireProtocolTest` currently pins POST + REACTION as representatives; extend if a type proves fragile.
- [ ] Rate-limit *window expiry* (entries older than 10s are dropped) is not tested — would need injectable
      time to avoid a real 10s sleep. Refactor `GossipService` to take a clock in Phase 0.3/1, then test.

### Discovered behaviors (Stage 0.2)
- **[behavior, pinned] Gson bypasses Kotlin constructor defaults.** A field declared with a default (e.g.
  `ReactionPayload.action = "add"`) deserializes to **`null`** when absent from the JSON — Gson uses Unsafe
  allocation and never runs the Kotlin constructor. Currently SAFE: every consumer in `MeshPacketHandler`
  tests `action == "remove"`, so `null` behaves as "add" (the intent). But the contract is "absent ⇒ treated
  as add", not "absent ⇒ value 'add'". A re-implementation must replicate null-on-absent. Pinned in
  `WireProtocolTest.reactionPayload_actionToggle_roundTrips`.
- **[plan correction] dedup/TTL/rate-limit location.** These guards are in `GossipService`, not
  `MeshPacketHandler` (corrected above and in the Stage 0.3 list).
- **[infra] `Logger` couples core logic to `android.util.Log`.** Forced `unitTests.isReturnDefaultValues=true`
  (see ADR-007). This logging coupling is part of the `expect`/`actual` surface for Phase 1.

## Stage 0.3 — Decompose the monolith files  🟡 in progress

Follow `DECOMPOSITION_MAP.md`. One file → many, mechanically, re-running tests after each split. Order (lowest-risk / highest-value first):

- [~] `data/NoSlopRepository.kt` (1,474 → **1,355**) → split by domain. **In progress, extracted so far:**
  - [x] `data/PreferencesRepository.kt` — categories, per-category keywords, negative keywords, language,
        music/video genres, creator keywords, `UserProfile` (over `AppSettingDao` + `FeedDao` fallback).
  - [x] `data/EngagementRepository.kt` — viewed history + swipe tracking (over `ViewedHistoryDao` +
        `SwipeTrackerDao`, incl. the `HISTORY_LIMIT` prune cap).
  - [x] `data/SettingsRepository.kt` — media/notification settings + foreground-service flag. Owns the
        `mediaSettingsFlow` / `notificationSettingsFlow` / `isForegroundServiceEnabled` StateFlows; the
        facade re-exposes them so UI subscribers are unchanged.
  - [ ] `data/FeedRepository.kt` — sources/items CRUD, `refreshFeeds` + private `fetchRssSource`/
        `fetchApiCategory` helpers, `searchCustomFeed`, read/saved state, `clearFeedData`, aggregator
        toggle, source recovery/migration. *(Depends on Preferences for keyword/category/lang lookups.
        Testability caveat: the refresh pipeline calls `FeedParser`/`PublicApiService` static objects —
        network. Unit-testable parts are the aggregator/transparency flags, `recoverSourcesAfterMigration`,
        and CRUD; the pipeline itself wants `FeedParser`/`PublicApiService` made injectable — a Phase-1 item.)*
  - [ ] `data/MeshSocialRepository.kt` — post/comment/reaction/vote/DM compose+broadcast, connection
        handshakes, presence heartbeat. *(~400 lines, the most entangled: needs identity, `meshTransport`,
        `GossipService`, `repositoryScope`, and many DAOs — extract last.)*
  - Each extraction is mechanical + behavior-preserving (ADR-004): logic moved verbatim, the facade keeps
    identical public methods that delegate, full suite re-run green after each.

  **Testing approach (added 2026-06-16):** extractions now ship WITH unit tests. Repositories are tested
  pure-JVM against stateful in-memory DAO fakes (`app/src/test/.../data/FakeDaos.kt`) — no Robolectric,
  no SQLite. This asserts the *logic* (JSON round-trips, fallbacks, prune-on-cap, flow-sync) without
  depending on Room's SQL. Suite is now **53 tests** (was 31 at end of Stage 0.2).
- [ ] `mesh/MeshPacketHandler.kt` (840) → one handler per packet type behind a dispatcher
- [ ] `ui/NoSlopViewModel.kt` (1,102) → split by feature (feed, social, peers, identity/lock, tor, settings)
- [ ] `ui/OnboardingScreen.kt` (1,236) → one composable per step + shared components
- [ ] `ui/UnifiedFeedTab.kt` (1,164) → feed list, card host, interaction handlers
- [ ] `ui/MediaComponents.kt` (821) → per-media-type components
- [ ] Re-check: any other file > 300 lines (`ContentPreferencesScreen` 653, `VideoPlayer` 604, `SettingsTab` 538, `FeedParser` 486, `InvidiousApiClient` 451, `QRScanScreen` 484, `PeerItem` 408)

## Stage 0.4 — Reorganize into module-shaped packages  ⚪ not started

- [ ] Group decomposed files under `core.crypto`, `core.mesh`, `core.data`, `core.feeds` packages
- [ ] Make cross-package dependencies explicit and one-directional (UI → core, never core → UI)
- [ ] Identify every Android-only touchpoint in `core.*` and list it for Phase 1's `expect`/`actual` work (append to this doc)
- [ ] Document each package with a `package-info`-style KDoc or `README.md`

## Stage 0.5 — Documentation pass & Phase 0 close  ⚪ not started

- [ ] Every refactored file has file-level + symbol-level KDoc and `// WHY:` notes
- [ ] Build green, tests green, manual smoke test passes
- [ ] Update status board in `MIGRATION_PLAN.md`; write Phase 0 retrospective in `PROGRESS_LOG.md`
- [ ] Draft `PHASE_1.md`

---

## Working notes / discovered tasks
_(Append as you go. This is the catch-all for things found mid-refactor.)_

- **[discovered] Release signing config blocks all builds without secrets.** `app/build.gradle.kts:27` reads `project.property("NOSLOP_STORE_FILE")` eagerly inside `signingConfigs.release`, so *configuration fails* (even for debug/compile) unless `NOSLOP_STORE_FILE/_PASSWORD`, `NOSLOP_KEY_ALIAS/_PASSWORD` are provided. Contributor papercut. **Fix later (non-urgent):** guard the release signing block to only configure when the properties exist (e.g. `if (project.hasProperty("NOSLOP_STORE_FILE")) { ... }`). Workaround for now: pass dummy `-P` values for debug compiles.
- Toolchain confirmed: JDK 17 (Zulu), Android SDK at `~/Library/Android/sdk`, Gradle 9.4.1 (wrapper), AGP emitting legacy-DSL deprecation warnings (non-blocking).
