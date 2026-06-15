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
- [ ] Establish a build baseline: record whether the project builds as-is (`./gradlew assembleDebug`) and capture current test status
- [ ] Add a `.editorconfig` / formatting baseline so AI edits stay consistent
- [ ] Commit the scaffold

## Stage 0.2 — Golden-vector tests for the core (do this BEFORE refactoring)  ⚪ not started

Lock current behavior so refactors are provably safe. Tests live in `app/src/test/`.

- [ ] **Crypto vectors** (`CryptoService`): fixed-input expected outputs for
  - [ ] Ed25519 sign → verify (and verify rejects tampered payloads/sigs)
  - [ ] X25519 key agreement (deterministic shared secret)
  - [ ] DM encrypt → decrypt round-trip (ChaCha20-Poly1305), incl. wrong-key failure
  - [ ] Onion v3 address derivation from a known pubkey
  - [ ] Tripcode (SHA3-256 Base32) from a known pubkey
- [ ] **Mnemonic vectors** (`MnemonicGenerator`): BIP39 12-word generate/restore round-trip; identity-from-mnemonic determinism
- [ ] **Wire-protocol vectors** (`Packets` / `NetworkPacket`): JSON serialize → deserialize round-trip for every packet type (POST, REACTION, DM, handshake, etc.); signature survives round-trip; unknown fields tolerated
- [ ] **Packet handling**: `MeshPacketHandler` dedup (LRU), TTL decrement, rate-limit enforcement — table-driven tests
- [ ] Record any behavior that looks like a *bug* (don't fix yet — log in `PROGRESS_LOG.md` and raise an ADR if it affects the protocol contract)

## Stage 0.3 — Decompose the monolith files  ⚪ not started

Follow `DECOMPOSITION_MAP.md`. One file → many, mechanically, re-running tests after each split. Order (lowest-risk / highest-value first):

- [ ] `data/NoSlopRepository.kt` (1,474) → split by domain (identity, settings, feeds, preferences, mesh-social, tracking)
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

- Build baseline not yet confirmed — Android SDK/Gradle availability on this machine is unverified; see Stage 0.1.
