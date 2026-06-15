# NoSlop Cross-Platform Migration — Master Plan

**Status:** 🟡 Phase 0 in progress · **Branch:** `feat/cross-platform-migration` · **Last updated:** 2026-06-16

---

## Vision

> One NoSlop codebase that runs on iOS, Android, and desktop (Windows/macOS/Linux), preserving the privacy-first serverless mesh — built so that **AI tooling can safely maintain and extend it** for years.

**Primary optimization target:** long-term AI-assisted maintainability. **First deliverable:** a working iOS client.

## Strategy in one line

Adopt **Kotlin Multiplatform + Compose Multiplatform** (one language for logic *and* UI), reuse the portable core (JSON wire protocol + standard crypto), keep platform specifics behind thin `expect`/`actual` adapters, and treat **file decomposition + a tested core** as the foundational work. Full rationale: [`STRATEGY.md`](STRATEGY.md).

## Phase status board

| Phase | Goal | Status | Detail doc |
|---|---|---|---|
| **0 — Decompose & test the core** | Split monolith files; extract `:core-*` modules; golden-vector tests for crypto/protocol. *No behavior change.* | 🟡 **In progress** | [`PHASE_0.md`](PHASE_0.md) |
| **1 — KMP-ify the core** | Move core modules to `commonMain`; Room→SQLDelight; define platform seams. Android still runs on shared core. | ⚪ Not started | _TBD `PHASE_1.md`_ |
| **2 — iOS leaf client** | Compose MP UI on iOS; `actual` Tor, Keychain, AVPlayer, QR. Pairs to a HUB. App Store compliance. | ⚪ Not started | _TBD_ |
| **3 — Desktop / Home HUB** | JVM desktop = always-on HUB that keeps iOS reachable. Win/macOS/Linux. | ⚪ Not started | _TBD_ |
| **4 — Web (optional)** | Wasm bridge client only — no in-browser Tor; talks to a HUB. | ⚪ Deferred | _TBD_ |
| **5 — Rust/Arti core (optional)** | Port only crypto+mesh+Tor to Rust via Arti, gated on Phase 0 tests as conformance suite. | ⚪ Deferred | _TBD_ |

Legend: ⚪ not started · 🟡 in progress · 🟢 complete · 🔴 blocked

## Scope guardrails

- **In scope now:** Phase 0 (decompose + test). Nothing platform-specific is written until the core is clean and tested.
- **Out of scope until Phase 0 done:** any iOS code, any KMP module conversion, any new features.
- **Never in scope:** changing the wire protocol or crypto scheme without an ADR + conformance tests — it's the interop contract with the existing Android network.

## The portable core vs. the platform shell (what moves where)

| Portable (→ shared `commonMain`) | Platform-specific (→ `expect`/`actual`) |
|---|---|
| Crypto scheme, mnemonic, identity | Secure key storage (Keystore / Keychain) |
| Mesh: gossip, packet handling, wire (de)serialization | Tor controller (tor-android / Tor.framework / Arti) |
| Feed parsing + ~14 API clients | Media playback (ExoPlayer / AVPlayer) |
| Repository/data *logic* (Room→SQLDelight) | Camera / QR scanning |
| ViewModels / presentation state | Background execution (WorkManager / BGTask) |
| Compose UI (most of it, via Compose MP) | HTTP client wiring (SOCKS5-aware) |

## Indicative timeline

First installable iOS build ≈ 3 months in; iOS + desktop polished ≈ 4–5 months. Phases 4–5 are open-ended/optional.

## Key risks (full table in `STRATEGY.md`)

1. iOS background-socket limits ⇒ leaf-node + Home HUB model (pulls desktop forward).
2. AI-written crypto/protocol may hide bugs ⇒ golden-vector tests **before** any port (Phase 0).
3. Tor packaging on iOS ⇒ keep Tor behind an interface; Arti held in reserve (Phase 5).
4. App Store review of a P2P/Tor app ⇒ compliance work starts in Phase 2.
