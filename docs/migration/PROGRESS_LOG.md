# Progress Log

Reverse-chronological journal. **Newest entry on top.** Read the top entry first when resuming.

---

## 2026-06-16 — Stage 0.3 begun: NoSlopRepository decomposition (2 of ~5 extracted) 🟡

Started splitting the 1,474-line `NoSlopRepository` god-object per `DECOMPOSITION_MAP.md`, lowest-
entanglement domains first. Each extraction is mechanical + behavior-preserving (ADR-004) — logic moved
verbatim, the facade keeps identical public methods that delegate, and the full 31-test suite is re-run
green after each. **Pushed to the fork.**

**Extracted (committed):**
- `data/PreferencesRepository.kt` (177 lines) — content prefs: categories, per-category keywords, negative
  keywords, language, music/video genres, creator keywords, `UserProfile`. Pure `AppSettingDao` (+ `FeedDao`
  for the `getUserSelectedCategories` fallback). Commit `fe0cb14`.
- `data/EngagementRepository.kt` (88 lines) — viewed history + swipe tracking over `ViewedHistoryDao` /
  `SwipeTrackerDao` (incl. `HISTORY_LIMIT` prune cap). Commit `3ca9d58`.

`NoSlopRepository`: 1,474 → **1,355** lines so far.

**Remaining extractions (increasing entanglement, see `PHASE_0.md` for the list):** `SettingsRepository`
(owns StateFlows the facade must re-expose) → `FeedRepository` (the `refreshFeeds` pipeline + private RSS/API
fetch helpers; depends on Preferences) → `MeshSocialRepository` (~400 lines; needs identity, `meshTransport`,
`GossipService`, `repositoryScope` — extract last). Then the other five monoliths (`MeshPacketHandler`,
`NoSlopViewModel`, `OnboardingScreen`, `UnifiedFeedTab`, `MediaComponents`).

**Note on the safety net:** the repository has no direct unit tests, so "behavior preserved" here rests on:
verbatim moves + unchanged public method signatures + clean compile + the 31-test core suite staying green.
A repo-level integration test is a candidate before the riskier `MeshSocialRepository` split.

---

## 2026-06-16 — Stage 0.2 golden-vector suite landed (core behavior locked) 🟢

**Done — 31 unit tests, all green** (`./gradlew :app:testDebugUnitTest` + dummy `-PNOSLOP_*` props):
- `crypto/CryptoDerivationTest` (4, pure JVM) — tripcode + onion v3 golden vectors vs. an **independent
  Python reference** (SHA3-256 + custom Base32). These are the cross-language conformance vectors (ADR-005).
- `crypto/MnemonicGeneratorTest` (5, pure JVM) — PBKDF2-HMAC-SHA512 seed golden vector + determinism +
  salt sensitivity + 12-word generation.
- `crypto/CryptoServiceRobolectricTest` (5, Robolectric `@Config(sdk=34)`) — sign→verify with tamper/
  wrong-key rejection; X25519+ChaCha20-Poly1305 DM round-trip; wrong-key decrypt → `null`; identity shape;
  `deriveSeedB64` matches the same golden seed.
- `mesh/WireProtocolTest` (8, pure JVM) — envelope + POST/REACTION round-trips, snake_case wire keys,
  typed-accessor type safety, unknown-field tolerance. *(This file was drafted earlier and is now green.)*
- `mesh/GossipServiceTest` (9, pure JVM) — table-driven TTL / dedup (bounded-LRU eviction) / per-sender
  rate-limit (20 / 10s).

**Test strategy decision → ADR-007:** independent reference values + a pure-JVM vs. Robolectric split, plus
`unitTests.isReturnDefaultValues=true` so `Logger`→`android.util.Log` no-ops in pure tests. The Android
coupling these tests had to route around (`Base64`, `Build`, logging) is an early, concrete inventory of the
Phase-1 `expect`/`actual` surface.

**Discoveries (logged, not "fixed" — Stage 0.2 pins current behavior):**
1. **Gson ignores Kotlin defaults** → `ReactionPayload.action` is `null` (not `"add"`) when absent from the
   wire. Safe today (handlers only test `== "remove"`), but the contract is "absent ⇒ treated as add". Pinned.
2. **Plan had the wrong location** for dedup/TTL/rate-limit: it's in `GossipService.processIncoming`, not
   `MeshPacketHandler` (a pure dispatcher). Corrected in `PHASE_0.md`.

**Not done (non-blocking, noted in `PHASE_0.md`):** vectors for the remaining payload types (COMMENT/VOTE/
handshake/SYNC/media) beyond the POST+REACTION representatives; rate-limit *window-expiry* test (needs an
injectable clock — candidate refactor for Stage 0.3/Phase 1).

**Next:** Stage 0.3 — begin decomposing the monolith files per `DECOMPOSITION_MAP.md`, lowest-risk first
(`data/NoSlopRepository.kt`), re-running this suite after each extraction to prove behavior is preserved.

---

## 2026-06-16 — Build baseline established (Stage 0.1 ✅)

- **Code compiles clean.** `:app:compileDebugKotlin` → BUILD SUCCESSFUL, **0 Kotlin errors** (deprecation warnings only). Toolchain: JDK 17, Android SDK `~/Library/Android/sdk`, Gradle 9.4.1, AGP (legacy-DSL warnings, non-blocking).
- **Discovered blocker (not code):** release `signingConfigs` in `app/build.gradle.kts:27` eagerly reads `NOSLOP_STORE_FILE` etc., so configuration fails without those secrets — even for debug. Workaround: pass dummy `-P` values. Fix later: guard with `if (project.hasProperty(...))`. Logged in `PHASE_0.md`.
- Stage 0.1 essentially complete (remaining: `.editorconfig`).
- **Next:** Stage 0.2 — golden-vector tests for crypto + wire protocol, before any refactor.

---

## 2026-06-16 — Draft PR opened upstream

- Opened **draft PR [gaborkukucska/NoSlop#1](https://github.com/gaborkukucska/NoSlop/pull/1)** (`kufton:feat/cross-platform-migration` → `gaborkukucska:main`), docs-only, so Gabor can review the plan before code lands. Marked DRAFT/WIP.
- Body summarizes approach (KMP, iOS leaf + Home HUB, Phase-0-first, small phase-scoped PRs) and points to the key docs.
- **Next:** baseline build result, then Stage 0.2 tests.

---

## 2026-06-16 — Fork created, branch pushed, remotes wired

**Done:**
- Forked `gaborkukucska/NoSlop` → **`kufton/NoSlop`**.
- Reconfigured remotes: `origin` = fork (push), `upstream` = Gabor's repo (fetch/sync).
- Pushed `feat/cross-platform-migration` to the fork → **work is now backed up to a remote.**
- Updated ADR-003 (fork→PR workflow) and added ADR-006 (small phase-scoped PRs); updated README repo facts.

**Merge plan (agreed):** push to fork; when Gabor is happy, open PR → `gaborkukucska:main`; he merges (he holds write). Keep branch rebased on `upstream/main` since Gabor is actively developing.

**Next:** baseline build result (running), then Stage 0.2 golden-vector tests.

---

## 2026-06-16 — Project kickoff & Phase 0 setup

**Done:**
- Established the migration approach (KMP + Compose Multiplatform; see `STRATEGY.md` and ADR-001).
- Full clone of `gaborkukucska/NoSlop` → `~/Documents/NoSlop-xplatform` (full history for archaeology).
- Created branch `feat/cross-platform-migration` off `main` (HEAD `09d2b3f "still working on video playback"`).
- Scaffolded the migration documentation hub under `docs/migration/`: `README.md`, `MIGRATION_PLAN.md`, `STRATEGY.md`, `PHASE_0.md`, `DECOMPOSITION_MAP.md`, `DECISIONS.md`, and this log.
- Mapped the internal structure of the six monolith files and produced a grounded, behavior-preserving decomposition plan (`DECOMPOSITION_MAP.md`).
- Recorded 5 ADRs covering the stack, the iOS leaf-node model, branch/fork workflow, Phase-0-first ordering, and the wire-protocol contract.

**Verified facts about the environment:**
- GitHub account `kufton` has **READ-only** access to the upstream repo; **no fork exists** → pushing requires a fork or write access (ADR-003). Work is **local-only** for now.
- `~/Documents/noslop` (lowercase) and the session worktree belong to an unrelated home-spanning git repo (racing-sim/marketing history) — **not** the NoSlop app. The canonical working copy is `~/Documents/NoSlop-xplatform`.

**Open questions for the user/owner:**
1. **Push strategy:** fork to `kufton/NoSlop` now, or request write access to upstream? (Blocks remote backup — see ADR-003.)
2. Build environment: is the Android SDK + a JDK installed here so we can establish a `./gradlew assembleDebug` baseline? (Stage 0.1)

**Next up (Stage 0.1 → 0.2):**
1. Commit the scaffold to the branch.
2. Confirm/record the build baseline (or note tooling gaps).
3. Begin Stage 0.2 — golden-vector tests for `CryptoService`, `MnemonicGenerator`, and `Packets`/`NetworkPacket` **before** any refactoring.

**Notes:**
- Decompose-order is deliberately lowest-risk-first: data repo → packet handler → view-model → UI screens. Tests must pass after each extraction.
- The previously-discussed standalone assessment doc was written outside this repo; its content now lives in `STRATEGY.md` + `DECISIONS.md` inside the project, which is the durable home.
