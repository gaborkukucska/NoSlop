# Architecture Decision Records (ADRs)

Append-only. Each ADR records a binding choice and *why*, so it isn't re-litigated. Format: Status · Context · Decision · Consequences.

---

## ADR-001 — Use Kotlin Multiplatform + Compose Multiplatform as the cross-platform stack
- **Status:** Accepted (2026-06-16)
- **Context:** App is AI-built native Android (~19.5k LOC). Goal is iOS-first cross-platform, optimized for *AI-assisted maintainability*. Candidates: KMP+Compose MP, Rust core + native UI, Flutter, React Native, .NET MAUI.
- **Decision:** KMP + Compose Multiplatform. It uniquely maximizes the two properties that matter most for AI maintenance: (1) one language for logic *and* UI across all targets (no FFI glue boundaries), and (2) a strong typed compiler as a guardrail. It also keeps the most existing code viable.
- **Consequences:** Tor/secure-storage/media/camera become thin `expect`/`actual` adapters written per platform. Compose-on-iOS maturity is an accepted, monitored risk (SwiftUI escape hatch available). See `STRATEGY.md`.

## ADR-002 — iOS is a "leaf" node; an always-on "Home HUB" is required
- **Status:** Accepted (2026-06-16)
- **Context:** iOS suspends apps and closes sockets shortly after backgrounding; a reliable Tor hidden service / inbound listener is not possible in the background. This is an OS limit, independent of language.
- **Decision:** iOS connects *outbound* to an always-on Home HUB (a desktop/server build) that holds its hidden service and relays inbound traffic. The desktop build (Phase 3) is therefore part of making iOS viable, not optional.
- **Consequences:** Mesh design must support leaf↔HUB relaying. Desktop is prioritized right after iOS. Pure-mobile (no HUB) iOS operation is degraded by design.

## ADR-003 — Fork-based workflow; merge upstream via PR when Gabor approves
- **Status:** Accepted (2026-06-16, updated same day)
- **Context:** Authenticated GitHub account `kufton` has **READ-only** permission on `gaborkukucska/NoSlop`. Owner (Gabor) is actively committing to `main` and is hands-off on the migration.
- **Decision:** Use the standard fork → PR flow. Forked to **`kufton/NoSlop`**. Remotes: `origin` = the fork (`git@github.com:kufton/NoSlop.git`, push), `upstream` = Gabor's repo (fetch, for syncing). Work pushes to the fork; when Gabor is happy, open a PR from `kufton:<branch>` → `gaborkukucska:main`. **Gabor performs the merge** (only he has write on his repo) — nothing lands without his approval.
- **Consequences:** Work is now backed up to the fork. Must periodically `git fetch upstream && rebase`/merge to stay current with Gabor's active `main`. PR can be opened as a draft early for visibility.

## ADR-004 — Phase 0 (decompose + test) before any platform work
- **Status:** Accepted (2026-06-16)
- **Context:** Monolith files (up to 1,474 lines) are the biggest obstacle to AI-maintainability and safe refactoring. The crypto/protocol core is AI-written and unverified.
- **Decision:** No KMP conversion or iOS code until files are decomposed (~300-line rule) and the crypto/wire-protocol core is locked behind golden-vector tests. Tests are written *before* refactoring so equivalence is provable.
- **Consequences:** Slower start, but every later phase is safer and AI can work on small, tested units. Tests double as the conformance suite for an optional future Rust/Arti port (Phase 5).

## ADR-006 — Ship the migration as small, phase-scoped PRs (not one mega-PR)
- **Status:** Accepted (2026-06-16)
- **Context:** Gabor's `main` is moving (active development). A single 19k-line refactor PR would be near-impossible to review and would constantly conflict with his changes.
- **Decision:** Open small, self-contained PRs scoped to a stage/unit (e.g. "Phase 0: golden-vector tests", "Phase 0: split NoSlopRepository"). Each is behavior-preserving and independently reviewable/mergeable. Keep the branch rebased on `upstream/main`.
- **Consequences:** More PRs, but each is digestible and low-conflict. The migration docs can land first as their own PR so Gabor has the plan before the code arrives.

## ADR-005 — The JSON wire protocol is the immutable interop contract
- **Status:** Accepted (2026-06-16)
- **Context:** Mesh is signed JSON over TCP/Tor. Existing Android nodes are already on this network.
- **Decision:** The wire format and crypto scheme are not changed without an ADR + passing conformance tests. New clients (iOS, desktop, a future Rust HUB) must interoperate with existing Android nodes.
- **Consequences:** Stack/language choices are reversible at the component level; the network is never broken by a refactor. This is what makes ADR-001 low-risk and Phase 5 possible.

## ADR-007 — Golden-vector test strategy: independent reference + a pure-JVM / Robolectric split
- **Status:** Accepted (2026-06-16)
- **Context:** Stage 0.2 locks the crypto + wire-protocol core before refactoring (ADR-004/005). Two frictions surfaced: (1) parts of `CryptoService`/`MnemonicGenerator` call Android APIs (`android.util.Base64`, `android.os.Build`, and `Logger` → `android.util.Log`) that don't exist in a plain JVM unit test; (2) "golden" values are only trustworthy if they come from outside the code under test.
- **Decision:**
  1. **Independent reference values.** Expected tripcode/onion/seed vectors are computed by a second implementation (Python `hashlib`: SHA3-256 + the same custom Base32, and `pbkdf2_hmac`), then pinned in Kotlin. This is also the cross-language conformance vector a future non-Kotlin client must satisfy (ADR-005).
  2. **Split tests by Android coupling.** Pure functions (`deriveTripcode`, `deriveOnionAddress`, `MnemonicGenerator.deriveSeed`, all Gson wire round-trips, and the `GossipService` TTL/dedup/rate-limit pipeline) run as fast plain-JUnit tests. Functions that genuinely need framework APIs (`sign`/`verify`, `encryptDM`/`decryptDM`, `generateIdentity`, `deriveSeedB64`) run under **Robolectric** pinned to `@Config(sdk=[34])` so the modern (API 33+, JDK-default Ed25519) code path is exercised.
  3. **`unitTests.isReturnDefaultValues = true`** so incidental `android.util.Log` calls from `Logger` no-op instead of throwing "not mocked", letting core logic be tested without dragging Robolectric into every suite.
- **Consequences:** The suite is fast where it can be and faithful where it must be. The Android coupling these tests had to work around is precisely the surface Phase 1 must move behind `expect`/`actual` (`Base64`, `Build`, logging) — the test split is an early, concrete inventory of that work. First-run Robolectric downloads an `android-all` jar (network needed once).

## ADR-008 — First iOS deliverable is a scoped MVP: identity + clearnet feed reader (no mesh)
- **Status:** Accepted (2026-06-16)
- **Context:** Owner set a concrete goal — "an iOS MVP I can test on my iPhone." Build env is ready (Xcode 26.5, Swift 6.3, CocoaPods). The repo is still a pure single-module Android app; no KMP scaffolding exists. ADR-002 establishes that mesh/Tor on iOS needs an always-on Home HUB (a separate sub-project), so it is **out of scope** for a first runnable MVP.
- **Decision:** The first iOS target is a minimal **Compose Multiplatform** app doing exactly two things: (1) **generate/show a real cryptographic identity** (Ed25519 keypair → tripcode + Tor-v3 onion), and (2) **browse the clearnet feed** (HTTP-fetched). Decisions agreed with the owner:
  1. **Scope:** identity + feed reader only. No mesh, DMs, Tor, or media transfer in the MVP.
  2. **Stack:** KMP + Compose Multiplatform (per ADR-001) — one shared core + one Compose UI for iOS & Android. Targets: `androidTarget`, `iosArm64` (device), `iosSimulatorArm64` (Apple-silicon sim).
  3. **Provisioning:** free personal Apple ID — Xcode sideload to the physical iPhone (7-day expiry, re-install as needed). No paid account required for the MVP.
- **Consequences:** This starts Phase 1, scoped. The golden-tested derivations (`deriveTripcode`/`deriveOnionAddress` — pure SHA3-256 + Base32) port directly into `commonMain` and are pinned in `commonTest` against the SAME golden vectors (ADR-007) — cross-platform conformance for free. Ed25519 keypair generation and HTTP become `expect`/`actual` seams (JVM/Android: BouncyCastle + OkHttp; iOS: CryptoKit + Ktor Darwin). The existing Android monolith keeps running untouched; the MVP is the cross-platform seed we port verified pieces into. Detailed build/run steps live in `IOS_MVP_PLAN.md`.
