# NoSlop Cross-Platform Migration — Master Plan

**Status:** 🟢 Phases 0–1 done · Phase 2 (iOS leaf) ~80% · Phase 3 (HUB) functionally working · **Branch:** `feat/cross-platform-migration` · **Last updated:** 2026-06-19

---

## Vision

> One NoSlop codebase that runs on iOS, Android, and desktop (Windows/macOS/Linux), preserving the privacy-first serverless mesh — built so that **AI tooling can safely maintain and extend it** for years.

**Primary optimization target:** long-term AI-assisted maintainability. **First deliverable:** a working iOS client.

## Strategy in one line

Adopt **Kotlin Multiplatform + Compose Multiplatform** (one language for logic *and* UI), reuse the portable core (JSON wire protocol + standard crypto), keep platform specifics behind thin `expect`/`actual` adapters, and treat **file decomposition + a tested core** as the foundational work. Full rationale: [`STRATEGY.md`](STRATEGY.md).

## Phase status board

| Phase | Goal | Status | Detail doc |
|---|---|---|---|
| **0 — Decompose & test the core** | Split monolith files; golden-vector tests for crypto/protocol. *No behavior change.* | 🟢 **Done** — portable core extracted + golden-tested cross-platform (identity, wire protocol, sign/verify, DM crypto). | [`PHASE_0.md`](PHASE_0.md) |
| **0.5 — iOS MVP (identity + feed)** | A runnable iOS MVP: identity + clearnet feed. | 🟢 **Done** — runs on device; real CryptoKit Ed25519 identity + live JSON feed. | [`IOS_MVP_PLAN.md`](IOS_MVP_PLAN.md) · ADR-008 |
| **1 — KMP-ify the core** | Core in `commonMain`; Room→SQLDelight; platform seams. | 🟢 **Done** — wire protocol, Ed25519 sign/verify, **DM crypto (X25519)**, gossip/firewall/routing, SQLDelight persistence, TCP transport, SOCKS5 — all shared + tested on iOS/Android/JVM. | [`IOS_MVP_PLAN.md`](IOS_MVP_PLAN.md) |
| **2 — iOS leaf client** | Compose MP UI on iOS; `actual` Tor, Keychain, AVPlayer, QR. Pairs to a HUB. App Store compliance. | 🟢 **~80%** — ✅ Tor (embedded, onion dial), QR scan **+ share**, hub pairing + persistence, identity in Keychain, **e2e DMs**, contacts. ❌ remaining: **media/AVPlayer**, DM-key→Keychain, App Store/TestFlight. See roadmap ↓. | this doc ↓ |
| **3 — Desktop / Home HUB** | JVM desktop = always-on HUB; publishes onion, relays blind. | 🟢 **Functionally working** — `runHub` bootstraps Tor, publishes an onion (`Flags=Detach`), relays posts + ciphertext DMs. ❌ not packaged as a desktop app/service. | this doc ↓ |
| **4 — Web (optional)** | Wasm bridge client; talks to a HUB. | ⚪ Deferred | _TBD_ |
| **5 — Rust/Arti core (optional)** | Port crypto+mesh+Tor to Rust via Arti, gated on Phase 0 tests. | ⚪ Deferred | _TBD_ |

Legend: ⚪ not started · 🟡 in progress · 🟢 complete/working · 🔴 blocked

> **Note (2026-06-19):** progress ran well ahead of the original plan — the iOS MVP was scoped as identity+feed
> only, but mesh, Tor onion, and end-to-end DMs are all working on-device now. The remaining work is feature
> parity with the Android app + hardening + distribution, tracked in the roadmap below.

## Scope guardrails

- **In scope now:** Phase 2 completion — feature parity with the Android app on iOS, hardening, distribution (roadmap below).
- **Never in scope:** changing the wire protocol or crypto scheme without an ADR + conformance tests — it's the interop contract with the existing Android network. (The DM payload gained optional `sender_enc_pub` / `sender_node_id` fields — additive, backward-compatible.)
- **Privacy invariant:** the HUB relays ciphertext only and must never decrypt DMs.

## Feature parity vs. the Android app (the working yardstick)

Goal (owner): *"mimic the Android features on a functioning iOS app."*

| Feature | Android | iOS now |
|---|---|---|
| Identity (Ed25519, tripcode, onion) | ✅ | ✅ (CryptoKit, Keychain) |
| Clearnet feed reader | ✅ | ✅ JSON (RSS pending) |
| Mesh posts (gossip broadcast/receive) | ✅ | ✅ |
| Tor onion connectivity | ✅ | ✅ (embedded Tor, dials hub onion) |
| QR pair / scan | ✅ | ✅ + **share-my-QR** |
| Encrypted DMs (X25519 · SHA3 · ChaCha20-Poly1305) | ✅ | ✅ end-to-end, blind hub |
| Contacts | ✅ | ✅ persisted + nameable |
| Saved hubs / auto-connect | ~ | ✅ persisted + default (★) |
| **Media / video playback** | ✅ | ✅ clearnet video (full-screen AVPlayer); inline/mesh-media pending |
| Reactions / votes | ✅ proto+UI | ⚠️ wire proto only, no UI/routing |
| Settings / Notifications / Logs / API keys / Content prefs / Onboarding | ✅ | ❌ not ported |
| Secure DM key storage | ✅ | ✅ iOS Keychain (Android Keystore pending) |
| App icon · distribution | ✅ | ✅ icon (NoSlop floppy); ❌ TestFlight for others |

## Remaining roadmap (ordered) — Phase 2 completion → parity

1. ✅ **Media / video playback** — `VideoPlayer` expect/actual; iOS full-screen AVPlayer (Swift bridge), Android system viewer. Verified on-device (plays w/ audio). *Pending: inline-in-feed embedding, peer-to-peer mesh media.*
2. ✅ **Harden DM key storage** — iOS DM private key migrated into the Keychain (identity preserved). *Pending: Android Keystore.*
3. **Reactions / votes** — wire types exist but the mesh doesn't route/aggregate them yet, so it's a real feature: react/vote on a post → packet → blind relay → aggregate counts → UI.
4. **Connection UX pass** — keep the onion link alive / auto-reconnect, optimistically pre-build the circuit after Tor bootstrap, clearer "building circuit…" progress. (Tor latency is inherent; this is about not re-dialing + perceived speed.)
5. **Onboarding + Settings** — handle setup, hub management, Tor status, content prefs.
6. **Distribution / TestFlight** — Apple Developer Program so others (e.g. Gabor, the original repo creator) can install + do a real remote DM. *External dependency: paid Apple account.* (App icon ✅ done.)
7. **RSS feed sources** — multiplatform XML, beyond the JSON feed.

Then: Phase 3 packaging (desktop HUB as an installable app/service), Phases 4–5 (optional).

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
