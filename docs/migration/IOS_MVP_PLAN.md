# iOS MVP Plan — Identity + Clearnet Feed Reader

**Goal (owner, 2026-06-16):** *"an iOS MVP I can test on my iPhone."*
**Scope + stack + provisioning decisions:** see **ADR-008**. This doc is the execution runway.

> **MVP = two things, on-device:** (1) generate & show a real NoSlop identity (tripcode + onion),
> (2) browse the clearnet feed. **No mesh / Tor / DMs** (that needs the Home HUB — ADR-002).

## Environment (verified 2026-06-16)
- Xcode **26.5**, Swift **6.3.2**, CocoaPods **1.11.3** — Mac is iOS-build-ready.
- Repo is a **pure Android single-module** app (AGP + `kotlin-android`); **no KMP scaffolding yet.**
- Provisioning: **free Apple ID** → Xcode sideload (7-day expiry).

## Target architecture (Compose Multiplatform)
A new CMP structure (the cross-platform seed; the existing `app/` Android monolith keeps running untouched):

```
shared/ (or composeApp/)
  commonMain/   — Compose UI (2 screens) + portable core + expect declarations
  androidMain/  — actual: BouncyCastle Ed25519, OkHttp/Ktor-OkHttp, android logging
  iosMain/      — actual: CryptoKit Ed25519 (Curve25519.Signing), Ktor Darwin engine
  commonTest/   — golden-vector tests (tripcode/onion) — same vectors as ADR-007
iosApp/         — thin Xcode project hosting the Compose UIViewController
```
Targets: `androidTarget()`, `iosArm64()`, `iosSimulatorArm64()`.

## The portable vs. platform split (the `expect`/`actual` seams)
| Concern | commonMain | androidMain (actual) | iosMain (actual) |
|---|---|---|---|
| **Tripcode / onion derivation** | ✅ pure Kotlin (SHA3-256 + custom Base32, ported verbatim from `CryptoService`) | — | — |
| SHA3-256 | `org.kotlincrypto.hash:sha3` (multiplatform) | (same) | (same) |
| **Ed25519 keypair gen** | `expect fun generateEd25519PublicKey(): ByteArray` | BouncyCastle (reuse `CryptoService`) | CryptoKit `Curve25519.Signing.PrivateKey().publicKey.rawRepresentation` |
| **HTTP feed fetch** | Ktor `HttpClient` (common) + kotlinx-serialization | Ktor OkHttp engine | Ktor Darwin engine |
| Logging | `expect` thin logger | `android.util.Log` | `NSLog`/println |

**MVP feed source:** start with a **JSON** API (e.g. Hacker News / a Lemmy or Reddit JSON endpoint) to
avoid RSS/XML parsing in the MVP. RSS (needs a multiplatform XML parser) is a fast-follow.

## Screens (Compose, commonMain)
1. **Identity** — "Generate identity" button → shows handle, **tripcode**, **onion**, pubkey (truncated). Persist later; MVP can regenerate in-memory.
2. **Feed** — pull a JSON feed, render a scrollable list (title, source, excerpt, thumbnail).

## Execution steps (incremental, verify each)
- [ ] **0. Scaffold** the CMP project (Gradle KMP + Compose plugins; targets; source sets). Verify Android + JVM compile.
- [ ] **1. Portable identity core** in `commonMain`: `IdentityDerivation.deriveTripcode/deriveOnionAddress` (SHA3 + Base32, verbatim logic). `commonTest` pins the **golden vectors** (`aufeq4`, `aaaqeayeaud…ead.onion`) — proves the port matches Android byte-for-byte.
- [ ] **2. Ed25519 `expect`/`actual`** keypair gen → wire into identity generation. Verify on Android first.
- [ ] **3. Compose Identity screen** — generate + display. Run on Android, then iOS simulator.
- [ ] **4. Ktor feed fetch** (`expect`/`actual` engine) + kotlinx-serialization model + Feed screen.
- [ ] **5. iOS app target** — `iosApp` Xcode project hosting the Compose VC. Build for `iosSimulatorArm64`.
- [ ] **6. On-device** — set Team = personal Apple ID, unique bundle id, plug in iPhone, trust, Run.

## Known risks / unknowns
- **Kotlin/Native + Compose-iOS toolchain downloads** are large and slow on first build (network).
- **KMP plugin ↔ Gradle/AGP/Kotlin version compatibility** — pin a known-good Compose-Multiplatform + Kotlin combo; this is the most likely early friction.
- **CryptoKit ↔ Kotlin/Native interop** for Ed25519 (cinterop or a Swift bridge) — the fiddliest seam; the golden-vector test de-risks correctness once wired.
- Free-provisioning **7-day expiry** — fine for testing; document the re-install step.

## Version triple (verified from the existing build — pin the scaffold to these)
- **Kotlin** `2.2.10` · **AGP** `9.2.1` · **Gradle** `9.4.1` · JDK 17 (Zulu) · Compose BOM `2024.09.00`.
- ⇒ Use **Compose Multiplatform `1.9.x`** (the line that pairs with Kotlin 2.2.x) and the bundled
  `org.jetbrains.kotlin.plugin.compose` compiler plugin. Pinning these avoids the #1 early-friction risk.

## Status
Planning recorded (ADR-008 + this doc); version triple pinned. **Next: step 0 — scaffold the CMP project**
(self-contained `mvp/` so it can't destabilize the mid-refactor Android build). That step begins the heavier
iOS toolchain work (first Kotlin/Native + Compose-iOS build downloads a large toolchain — several
build-verify cycles expected). Steps 1–2 (portable identity core + golden `commonTest`, then the Ed25519
`expect`/`actual`) are verifiable on JVM/Android before any iOS compile.
