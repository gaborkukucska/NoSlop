# NoSlop Cross-Platform Migration — Strategy & Rationale

This document captures *why* the migration is shaped the way it is. If you're tempted to change the approach, read this and `DECISIONS.md` first.

## The decision

Build on **Kotlin Multiplatform (KMP) + Compose Multiplatform**, with platform specifics behind `expect`/`actual` adapters, and the security core kept swappable to **Rust/Arti** later if warranted.

## Why — optimized for AI-assisted maintenance

The owner's brief: the app is AI-built and will likely be AI-maintained; optimize for *what AI tools maintain best*. That comes down to two dominant properties:

1. **One language across the whole app, including the UI.** Language/FFI boundaries are the #1 place AI tools silently break invariants (keeping a struct, its binding, and its call sites in sync). KMP is one language — Kotlin — for logic *and* UI across every target.
2. **A strict compiler that turns mistakes into fixable compile errors.** AI's failure mode is plausible-but-wrong code; strong typing + null safety converts that into errors it can resolve in a loop. Kotlin is strong here (Rust is stronger, but at the cost of property #1).

Supporting factors: large training corpus (high AI fluency), shared single codebase (no per-platform drift), and a testable pure-logic core.

## Why not the alternatives

- **Rust core + native UI:** best *core* correctness (the compiler catches exactly the class of bug AI introduces in security code, and Arti removes the C-tor maintenance burden) — but across the *whole app* it reintroduces a second language, FFI glue, and per-platform UI duplication. Kept as optional **Phase 5** for the security core only, gated on the Phase 0 conformance tests.
- **Flutter:** single language incl. UI, but weaker compiler guardrail, smaller corpus, and the same native Tor-glue problem. ~0% reuse.
- **React Native / .NET MAUI:** weak desktop and/or weak Tor/P2P story. Rejected.

## The structural work matters more than the language

The current code actively fights AI tooling: files of 1,474 / 1,236 / 1,164 / 1,102 lines are too big to hold in context and edit safely. So the foundational work (Phase 0) is, independent of stack:
- decompose the monoliths into small, single-responsibility files;
- lock the crypto/protocol behind golden-vector tests (the one place a compiler *can't* catch a logic bug, and it's AI-written today);
- expose typed, documented interfaces at every boundary.

## iOS constraints that shape the product (OS-level, apply to every stack)

1. **No background hidden service / inbound sockets on iOS.** Apps are suspended and sockets closed shortly after backgrounding. ⇒ **iOS must be a leaf node** that connects outbound to an always-on **Home HUB** (a desktop/server build) which holds its hidden service and relays inbound traffic. This makes the desktop build part of making iOS work — not a separate nice-to-have.
2. **Tor packaging differs:** no NDK on iOS — use `Tor.framework` (C build) or Arti (Rust). Keep Tor behind an interface so it's swappable.
3. **App Store review:** Tor + P2P apps ship (Orbot, Onion Browser) but draw scrutiny; needs encryption export compliance (`ITSAppUsesNonExemptEncryption`) and a content-moderation story. Start in Phase 2.
4. **Mappings:** Android Keystore → iOS Keychain + Secure Enclave; ExoPlayer → AVPlayer; WebView → WKWebView; CameraX/ML Kit → AVFoundation/Vision; WorkManager → BGTaskScheduler.

## Why the stack choice is low-risk

The mesh is **signed JSON over TCP through Tor** — transport- and language-neutral. A Kotlin iOS client, a future Rust HUB, and the existing Android app all interoperate on one mesh. The wire format, not the codebase, is the real contract; components can be re-implemented in another language later without breaking the network. That is why committing to KMP now carries almost no long-term lock-in, and why Phase 5 (Rust core) remains open without rework.

## Risk register

| Risk | Severity | Mitigation |
|---|---|---|
| iOS background limits cripple reachability | High | Leaf-node + Home HUB model; bring desktop HUB forward (Phase 3). |
| AI-written crypto/protocol has latent bugs | High | Golden-vector tests + audit in Phase 0 before any port. |
| Tor C-daemon packaging fragile on iOS | Medium | Tor behind an interface; Arti ready as a substitute (Phase 5). |
| App Store rejection (P2P/unfiltered content) | Medium | Compliance + moderation narrative early in Phase 2. |
| Monolith files keep accreting | Medium | Enforce file-size/responsibility limits in review; Phase 0 resets the baseline. |
| Compose-on-iOS rough edges | Low–Med | SwiftUI escape hatch for any problem screen. |
