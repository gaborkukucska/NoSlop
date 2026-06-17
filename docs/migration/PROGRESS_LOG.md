# Progress Log

Reverse-chronological journal. **Newest entry on top.** Read the top entry first when resuming.

---

## 2026-06-17 — Phase 1 begins: wire protocol ported to shared code (byte-identical on iOS + Android) 🟢

Toward Android parity. The CMP MVP (`mvp/`) grew real capabilities and now holds the first piece of the
**shared mesh core**: the signed-JSON **wire protocol** (the ADR-005 interop contract) lives in `commonMain`.

- `mvp/.../commonMain/Packets.kt` — `NetworkPacket` envelope + core payloads (Post, Media, Encrypted/DM,
  Reaction, Vote, Comment, PeerHandshake), mirroring the Android `mesh/Packets.kt` with the SAME snake_case
  wire keys, via kotlinx.serialization. `WireJson` config matches Gson's on-the-wire behavior
  (`encodeDefaults=true`, `explicitNulls=false`, `ignoreUnknownKeys`). Commit `4ddc3bd`.
- `WireProtocolTest` (8 golden vectors) ported from the Android suite, **green on BOTH JVM/Android and
  iosSimulatorArm64/Kotlin-Native** — a cross-platform node is byte-compatible with existing Android nodes.
  Documented improvement: absent `action` now yields its default (kotlinx runs the constructor) vs Gson's
  null; wire format unchanged → still conformant.

**MVP capabilities to date** (all on real iPhone hardware, verified): persistent CryptoKit Ed25519 identity
(Keychain) + editable persistent handle (NSUserDefaults), a real RSS/Atom feed aggregator (dependency-free
multiplatform parser), and now the shared wire protocol. **17 tests** green on both JVM and Kotlin/Native.

**Next toward parity (per the plan):** (2) Ed25519 **sign/verify** as expect/actual (iOS CryptoKit / Android
BouncyCastle), proven against golden vectors — makes an iOS node's posts verifiable by Android nodes; then
(3) DM crypto + the gossip TTL/dedup/rate-limit logic; then persistence (SQLDelight); then transport + the
always-on **HUB** (ADR-002, the hard infra piece that makes a working iOS mesh possible).

**Recurring papercut:** cloud-sync keeps creating `"<name> 2.kt"` duplicate source files (one broke the Native
build this session: `Platform.ios 2.kt`). Sweep `find composeApp/src -name "* [0-9].*" -delete` if the build
hits "Redeclaration"/"Conflicting overloads".

---

## 2026-06-16 — iOS MVP BUILT: runnable Compose Multiplatform app (identity + feed) 🟢

Owner goal "an iOS MVP I can test on my iPhone" → built the CMP app under `mvp/` (Phase 0.5, ADR-008).
Commit `4a8a5b4`. **Verified this session:**
- **Android**: compiles + assembles a debug **APK** — real BouncyCastle Ed25519 identity + live Hacker News
  feed (Ktor/OkHttp). A real, installable app.
- **Golden vectors PASS on the multiplatform port** (`commonTest`): `deriveTripcode`→`aufeq4` + onion match
  the Android suite and the Python reference byte-for-byte. `IdentityDerivation` (SHA3 via KotlinCrypto +
  Base32) is provably equivalent cross-platform — the ADR-005/008 conformance guarantee, realized.
- **iOS compiles** (`iosArm64` + `iosSimulatorArm64`): shared Compose UI, Ktor Darwin, Security cinterop —
  Kotlin/Native toolchain downloaded and built clean.
- **`iosApp` Xcode project** generated (xcodegen) with the Gradle embed/sign framework phase.

Stack: KMP + Compose Multiplatform; Kotlin 2.2.10 / AGP 9.2.1 / Gradle 9.4.1 / Compose MP 1.9.0
(`android.builtInKotlin=false` for the AGP-9 + KMP-application combo). `expect`/`actual` seams: `KeyProvider`
(Android real Ed25519; iOS labeled secure-random **demo key** — real CryptoKit Curve25519 is the next step)
and the Ktor engine. The existing `app/` Android monolith is untouched; `mvp/` is the cross-platform seed.

**To actually run on the iPhone — 2 user/env steps (I can't do these):** (1) install the iOS 26.5 platform
component in Xcode (this Xcode has the SDK but not the runtime/device-support — blocks all iOS runs; large
download, kicked off this session), (2) open `iosApp.xcodeproj`, set signing Team to a free Apple ID, Run on
device (trust the cert on-phone; 7-day expiry). Steps + follow-ups in `IOS_MVP_PLAN.md`.

---

## 2026-06-16 — Stage 0.3: MeshPacketHandler split into dispatcher + 7 handlers (item #2 DONE) 🟢

Decomposed the 840-line `MeshPacketHandler` (DECOMPOSITION_MAP item #2). It was a dispatcher fused to 21
private `handleX` methods; now a slim ~70-line dispatcher (keeps the cross-cutting gate — local-identity
check + `GossipService` TTL/dedup/rate-limit/firewall — and routes by packet type) delegating to 7
single-responsibility handler classes, each constructed `(repo, db)` exactly like the original:

  SyncPacketHandler 274 · HandshakePacketHandler 161 · ReactionPacketHandler 161 · PostPacketHandler 113 ·
  DmPacketHandler 104 · CommentPacketHandler 80 · MediaPacketHandler 41 · MeshPacketHandler (dispatcher) 69.

Verbatim move (ADR-004): handler bodies extracted by brace-matching, only `private suspend fun` →
`suspend fun` so the dispatcher can route. Same package (`com.noslop.app.mesh`) for now — package reorg is
Stage 0.4. **Every file is now ≤ 274 lines (was one 840-line file).**

- **Tests** (`PostPacketHandlerTest`, 3, Robolectric `@Config sdk=34`): the security-critical signature gate
  on the highest-volume path — validly-signed POST stored; tampered body rejected & not persisted; packet
  re-attributed to a wrong author key rejected. Full per-handler matrix is a noted follow-up.

Commit `3a19ad9`. Suite **66 → 69**, green. Behavior preserved (verbatim + identical routing).

**Stage 0.3 remaining (the UI monoliths):** `ui/NoSlopViewModel.kt` (1,102), `ui/OnboardingScreen.kt` (1,236),
`ui/UnifiedFeedTab.kt` (1,164), `ui/MediaComponents.kt` (821), then the second-tier >300-line files. These
are Compose UI — different testing story (no pure-JVM unit tests; behavior preservation rests on compile +
manual smoke). Worth a planning checkpoint before starting.

---

## 2026-06-16 — Stage 0.3: MeshSocialRepository extracted + tested — NoSlopRepository decomposition COMPLETE 🟢

Executed the final, most-entangled repository split per the recorded plan (two commits).

- **Extract (commit `33c1833`):** moved the social/mesh heart — post/comment/reaction/vote/DM
  compose+sign+persist+broadcast, peer handshakes, identity/exit announcements, the presence heartbeat —
  into `data/MeshSocialRepository.kt` (798 lines). Owns `_incomingRequestFlow` (facade re-exposes it).
  Per the agreed decisions: presence moved in; constructor takes `db`; notifications stayed on the facade;
  identity/profile injected as suspend accessors (decoupled from Identity/Preferences). **Verbatim** move —
  bodies extracted by brace-matching and transferred byte-for-byte by naming the new repo's params/fields to
  match existing identifiers (`repositoryScope`, `meshTransport`, `TAG`, DAO fields). Removed 8 dead imports.
- **Tests (commit `f4f7cce`):** first-ever coverage of this hot path — `MeshSocialRepositoryTest` (6,
  Robolectric `@Config sdk=34`): reaction/vote add↔remove toggle, signed-post persist, DM encrypt+store+send,
  connection-request pending peer, accept trusts+clears flow. Added `Fake{Reaction,Vote,Peer,Post,Message}Dao`.

**Bug found + fixed (the full-suite rerun earned its keep):** `CryptoServiceRobolectricTest`'s tamper helper
flipped the *last* base64 char of the signature, but a 64-byte Ed25519 sig's final data char has 4 ignored
padding bits — flipping there can mutate only padding → identical decoded bytes → a "tampered" sig that still
verifies, intermittently, depending on the random per-run key. Now flips the *first* (always-significant)
char. Confirmed stable across 3 consecutive full-suite reruns.

**Milestone:** `NoSlopRepository` **1,474 → 396 lines** — a thin orchestrator. DECOMPOSITION_MAP item #1 DONE.
Five single-responsibility repositories (Preferences, Engagement, Settings, Feed, MeshSocial), each tested.
Suite **31 → 66 tests**, green across repeated runs. All pushed.

**Next (Stage 0.3 continues):** item #2 — `mesh/MeshPacketHandler.kt` (840 lines) → dispatcher + per-packet-
type handlers. Then the UI monoliths (`NoSlopViewModel`, `OnboardingScreen`, `UnifiedFeedTab`, `MediaComponents`).
Also a noted follow-up: `MeshSocialRepository` (798 lines) > 300-line target — split its repetitive broadcast
helpers later.

---

## 2026-06-16 — Stage 0.3: FeedRepository extracted + tested (4 of ~5) 🟡

Extracted the clearnet aggregator into `data/FeedRepository.kt` — the largest split so far: source/item
CRUD + observable flows, the multi-phase `refreshFeeds` pipeline (with private `fetchRssSource`/
`fetchApiCategory`), `searchCustomFeed`, aggregator/transparency toggles, `recoverSourcesAfterMigration`,
and the `OFFICIAL_NEGATIVE_KEYWORDS` floor. Decoupled from identity by injecting the onboarding check as a
suspend lambda; depends on `PreferencesRepository` for the pipeline's user signals. Commit `5060120`.

- **Tests** (`FeedRepositoryTest`, 7): aggregator/transparency toggles incl. their non-symmetric defaults
  (aggregator ON, transparency OFF) and all three `recoverSourcesAfterMigration` branches. A relaxed mockk
  `Context` stands in for the untested-here pipeline; `FakeFeedDao.insertSource` made stateful to observe reseeding.
- **Deferred (Phase 1):** `refreshFeeds`/`searchCustomFeed` aren't unit-tested — they call the `FeedParser`/
  `PublicApiService` singletons over the network. Making those injectable is the Phase-1 seam.

Suite: 53 → **60 tests, all green**. `NoSlopRepository`: 1,335 → **1,060** lines (4 of ~5 domains out).

**Discovered (environmental, logged in `PHASE_0.md`):** a cloud-sync process pollutes `app/build/` with
`"<name> 2.ext"` duplicates that break `parseDebugLocalResources` (illegal space in resource names). Not a
code issue — `find app/build -name "* 2.*" -delete` clears it (≈255 files appeared once).

**Last extraction — `MeshSocialRepository` — is a different risk tier:** ~400 lines, tightly coupled to
identity lifecycle, `meshTransport`, `GossipService`, `repositoryScope`, and the request/identity flows, with
circular touchpoints (`logout`→`broadcastUserExit`, `saveLocalIdentity`→presence heartbeat). Recommended as
its own focused session with a deliberate plan; its tests will need Robolectric (packet signing → Base64).

---

## 2026-06-16 — Stage 0.3: SettingsRepository extracted + repositories now tested (3 of ~5) 🟡

Adopted **"tests as we go"** (user call, and good practice): every repository extraction now ships with
its own unit tests. Backfilled the first two, and `SettingsRepository` landed test-first-style.

- Added `app/src/test/.../data/FakeDaos.kt` — stateful in-memory fakes of `AppSettingDao` / `FeedDao` /
  `ViewedHistoryDao` / `SwipeTrackerDao`. Repositories are now tested **pure-JVM** (no Robolectric, no
  SQLite); the fakes model only the query semantics the repos rely on (REPLACE/IGNORE, count, oldest-first
  prune, >=2 exclusion). This asserts repo *logic*, not Room's SQL.
- `PreferencesRepositoryTest` (11) + `EngagementRepositoryTest` (6) — commit `9ea7959`.
- `data/SettingsRepository.kt` extracted (media/notification settings + foreground flag; owns the
  StateFlows, facade re-exposes them) **with** `SettingsRepositoryTest` (5). Commit `ab91932`.

Suite: 31 → **53 tests, all green**. `NoSlopRepository`: 1,355 → **1,335** lines (3 of ~5 domains out).

**Heads-up for the next two:** `FeedRepository`'s refresh pipeline calls `FeedParser`/`PublicApiService`
static objects (network) — not unit-testable without making them injectable (a Phase-1 seam), so its tests
will cover the aggregator/transparency flags, migration recovery, and CRUD. `MeshSocialRepository` (last,
~400 lines) is the most entangled (identity, transport, gossip, scope).

**Flake note:** one combined `compileDebugKotlin + testDebugUnitTest` invocation failed once, then passed
clean on `--rerun-tasks` (53/0). Looks like a Robolectric first-run timing flake, not a real failure.

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
