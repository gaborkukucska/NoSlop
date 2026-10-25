# NoSlop Cross-Platform Migration — Master Guide

**Status:** 🟡 Phase 1 / iOS MVP in progress · **Branch:** `feat/cross-platform-migration` · **Target Codebase:** `mvp/`

---

## 1. Vision & Architecture Strategy

> **Goal:** Maintain one unified NoSlop core codebase that runs seamlessly across iOS, Android, and Desktop (Windows/macOS/Linux), preserving our serverless privacy-first mesh — built so that **AI tooling can safely maintain and extend it** long-term.

### Stack & Seams (ADR-001)
- **Language & Framework:** Kotlin Multiplatform (KMP) + Compose Multiplatform (CMP) for shared business logic and UI.
- **Portable Core (`commonMain`):** Crypto schemes, JSON wire protocol, packet handling, gossip logic, feed parsing, presentation ViewModels.
- **Platform Seams (`expect`/`actual`):** Secure key storage (Keystore / Keychain), Tor lifecycle, media engines (ExoPlayer / AVPlayer), camera / QR scanning.

---

## 2. Phase Status & Roadmap

| Phase | Goal | Status | Key Deliverable |
|---|---|---|---|
| **Phase 0** | **Decompose & Test Core** | 🟢 **Complete** | Golden-vector unit tests for SHA3-256 derivations, tripcodes, onion addresses, and wire packets. |
| **Phase 0.5** | **iOS MVP (Identity + Feed)** | 🟡 **In Progress** | CMP app: generate Ed25519 identity + clearnet feed reader on iOS (`iosArm64` / `iosSimulatorArm64`). |
| **Phase 1** | **KMP-ify Shared Core** | 🟡 **In Progress** | Move wire protocol & crypto derivations to `commonMain`; SQLDelight persistence; Ktor HTTP. |
| **Phase 2** | **iOS Leaf Node Client** | ⚪ **Planned** | Compose MP UI on iOS; native Tor.framework / iCepa SOCKS5 proxy integration; AVPlayer media. |
| **Phase 3** | **Desktop Home HUB** | ⚪ **Planned** | JVM Desktop app (Win/macOS/Linux) acting as an always-on relay HUB & identity backup server. |

---

## 3. Architecture Decision Records (ADRs Summary)

1. **ADR-001 (KMP + Compose MP):** Selected KMP and Compose Multiplatform to maximize single-language maintainability across logic and UI.
2. **ADR-002 (iOS Leaf + Home HUB Relay):** Due to iOS background socket suspension, iOS operates as a leaf node connecting outbound to an always-on Home HUB.
3. **ADR-003 (Fork & PR Workflow):** Forked to `kufton/NoSlop`, merging upstream to `gaborkukucska/NoSlop` via small PRs.
4. **ADR-005 (Immutable Wire Protocol):** The JSON wire protocol and Ed25519/X25519/ChaCha20-Poly1305 scheme remain the exact interop contract across all platform nodes.
5. **ADR-007 (Golden-Vector Testing):** Independent reference vectors (Python `hashlib` baseline) pin tripcodes, onion addresses, and packet roundtrips in `commonTest`.
6. **ADR-008 (Scoped iOS MVP):** First iOS deliverable is identity generation + clearnet feed reader (no mesh/Tor initially) via free personal Apple ID sideloading.
7. **ADR-009 (Tor SOCKS5 Seam):** Tor integration uses a decoupled SOCKS5 proxy seam; desktop HUB bundles `tor` binary; iOS uses `Tor.framework`.

---

## 4. Code Structure & Sub-Documents

Sub-documents detailing historical phases and architectural decision records are archived for reference in [`docs/archived/migration/`](../archived/migration/):
- `MIGRATION_PLAN.md` — Historical 6-phase master plan.
- `STRATEGY.md` — Strategic options evaluation and rationale.
- `PHASE_0.md` & `DECOMPOSITION_MAP.md` — Code decomposition specifications.
- `DECISIONS.md` — Detailed ADR catalog.
- `IOS_MVP_PLAN.md` — Detailed execution plan for iOS MVP sideload.
- `PROGRESS_LOG.md` — Session journal log.
