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

## ADR-003 — Branch-only for now; fork required before any push/PR
- **Status:** Accepted (2026-06-16)
- **Context:** Authenticated GitHub account `kufton` has **READ-only** permission on `gaborkukucska/NoSlop`; no fork exists. Owner is hands-off but wants the work on a branch.
- **Decision:** All work proceeds locally on branch `feat/cross-platform-migration` in `~/Documents/NoSlop-xplatform`. No push/PR until the maintainer either (a) grants write access, or (b) approves creating a fork under `kufton/NoSlop`.
- **Consequences:** Work is not backed up to a remote yet — local clone is the only copy. **Open question for the user/owner:** fork now, or get write access? Until resolved, commit often locally.

## ADR-004 — Phase 0 (decompose + test) before any platform work
- **Status:** Accepted (2026-06-16)
- **Context:** Monolith files (up to 1,474 lines) are the biggest obstacle to AI-maintainability and safe refactoring. The crypto/protocol core is AI-written and unverified.
- **Decision:** No KMP conversion or iOS code until files are decomposed (~300-line rule) and the crypto/wire-protocol core is locked behind golden-vector tests. Tests are written *before* refactoring so equivalence is provable.
- **Consequences:** Slower start, but every later phase is safer and AI can work on small, tested units. Tests double as the conformance suite for an optional future Rust/Arti port (Phase 5).

## ADR-005 — The JSON wire protocol is the immutable interop contract
- **Status:** Accepted (2026-06-16)
- **Context:** Mesh is signed JSON over TCP/Tor. Existing Android nodes are already on this network.
- **Decision:** The wire format and crypto scheme are not changed without an ADR + passing conformance tests. New clients (iOS, desktop, a future Rust HUB) must interoperate with existing Android nodes.
- **Consequences:** Stack/language choices are reversible at the component level; the network is never broken by a refactor. This is what makes ADR-001 low-risk and Phase 5 possible.
