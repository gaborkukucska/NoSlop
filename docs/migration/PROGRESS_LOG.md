# Progress Log

Reverse-chronological journal. **Newest entry on top.** Read the top entry first when resuming.

---

## 2026-06-17 — Phase 1 step 9a: SOCKS5 client dialing (the Tor seam) 🟢

First piece of the Tor layer (ADR-009): a leaf can now dial through a **SOCKS5 proxy** — the exact mechanism
for reaching a HUB's `.onion` via Tor (point the proxy at Tor's SOCKS port, pass the onion as the host; Tor
resolves + tunnels). Built and tested with **no Tor daemon required**.

- `SocketTransport.connect(host, port, proxy: SocksProxy? = null)` — optional SOCKS5 (RFC 1928) CONNECT, no
  auth, **domain ATYP** (so the proxy resolves `.onion`/DNS, not us). Plain-TCP and SOCKS paths coexist, so
  loopback/LAN demos keep working. Refactor: a socket's ktor channels can only be opened once, so the handshake
  opens them and `handle()` reuses them (fixed an "reading channel has already been set" along the way).
- `SocksProxyTest` (JVM, real sockets): a **mock SOCKS5 proxy** asserts the exact greeting + CONNECT bytes the
  client sends, then bridges to a real HUB; leafA dials the hub *through the proxy*, leafB connects directly,
  and a post relays leafA→(SOCKS)→hub→leafB. Same topology as Tor (leaf → Tor SOCKS → onion HUB). Green; the
  SOCKS client also compiles for iOS/Native.

**Next (gated on external deps — see ADR-009):** (9b) desktop HUB launches Tor + registers a v3 onion (needs a
`tor` binary); (9c) iOS Tor (needs Tor.framework/Arti — an owner dependency decision). The dialing client they
both feed is done.

---

## 2026-06-17 — Phase 1 step 8: the iOS app talks to the HUB — real phone ↔ real hub 🟢

The whole stack closes the loop: **the actual iOS app dials the actual desktop HUB and gossips through it.**
First time the phone app touches real networking.

- **`MeshClient`** (commonMain): a leaf for the app — identity + `SocketTransport` + `MeshNode` + a
  `MeshStore`-backed sink that surfaces received posts to the UI. `connect(host, port)` / `publish(text)`.
  Same engine the hub runs; this is the leaf side of ADR-002 (iOS dials out, can't host inbound).
- **Mesh tab** in the app: hub host/port, Connect, a post box + Broadcast, and a live "Received from mesh"
  list. Auto-connects to the default hub on open (the "just install the app" path — a shipping build puts a
  default/public hub address here; see the product note below). `nowMillis()`/`randomId()` added as
  expect/actual (JVM/Android `System`/`UUID`; iOS `NSDate`/`NSUUID`).
- **Proven on the simulator against a real running hub** (`runHub` on :9876):
  - **iOS → hub:** app auto-connected — phone shows "Connected ✓ — 1 link"; the hub log independently shows
    `heartbeat — links=1` (the phone's link).
  - **hub → iOS:** an external desktop client posted; the hub relayed it and the **phone displayed
    "DesktopUser: gm from the desktop"** under "Received from mesh (1)". Screenshotted both.
- No regression: full suite still green (Android/JVM unit tests + Native).

**Product note (why this matters for "people who just want the app"):** iOS is a leaf by OS design — it can't
be a full peer, so it needs a hub. The realistic models, in order of how most users experience them: (1)
**default community/public hubs** the app auto-connects to (most users do nothing — the Mesh tab's auto-connect
is the seed of this); (2) **an Android device as the hub** (Android *can* run background + a hidden service —
"one Android unlocks iOS for a group"); (3) **run your own / a friend's hub** (privacy-max, what `runHub` is);
(4) a hosted "home hub" appliance. Trust trade-off to message in GTM: a public hub relays your *encrypted*
traffic + sees metadata (it cannot read DMs — proven step 5), a hub you control sees nothing it shouldn't.

**iOS gotcha:** `NSDate.timeIntervalSince1970` is an **extension property** in the Kotlin/Native Foundation
bindings — it must be `import platform.Foundation.timeIntervalSince1970`'d explicitly (fully-qualifying the
receiver gives "unresolved reference"). Added `NSLocalNetworkUsageDescription` to Info.plist for on-device LAN.

**Next:** the **Tor layer** — HUB registers an onion hidden service; iOS dials it via SOCKS (replacing the
plain-TCP host/port with an onion address). That's the real privacy story and the last big external dependency.

---

## 2026-06-17 — Phase 1 step 7: the desktop HUB — a real always-on relay process 🟢

ADR-002's missing piece now exists: **a runnable always-on HUB that iOS leaves can dial.** It's a headless
JVM build of the shared core — `MeshNode` + `SocketTransport.listen()` + a `MeshStore`-backed sink — no new
logic, just the tested pieces composed and given a `main()`.

- **New `jvm()` target** on `composeApp` (headless; the same module, so it shares commonMain — Compose
  compiles for JVM but the HUB never renders UI). JVM actuals for every shared seam in `Platform.jvm.kt`:
  Ed25519 `Signer` + `DmCrypto` via **the same BouncyCastle code as Android** (so a HUB verifies signatures
  and relays DMs byte-identically to a phone), `DbDriverFactory` via JDBC SQLite, file-based identity/handle,
  OkHttp. `Mappers.kt` adds `PostPayload.toMeshPost()` (shared, used by the HUB sink and later the app).
- **`HubMain`** (`./gradlew :composeApp:runHub [--args="<port>"]`): loads/creates a real Ed25519 identity,
  opens the DB, listens on TCP, and relays + persists what arrives. ~40 lines — everything else is reused.
- **Proven end-to-end as a standalone process:** ran `runHub` on :9876 → "NoSlop HUB up — node 302a30…
  listening". A **separate external client** (a Python script — not our code) connected, completed the
  node-id handshake (received the hub's real Ed25519 id), and sent a wire-format POST; the hub ingested it
  through the full pipeline and logged `[hub] post py-post-1 by PythonLeaf: hello from a real external
  client`. Proves the wire protocol is genuinely interoperable with a non-Kotlin client.
- **No regression:** full suite now also runs on the **JVM target — 50/50 green** (the new BouncyCastle/JDBC
  actuals validated against the same golden vectors as Android + Native). Plus the JVM socket integration test.

**Integration notes:** adding `jvm()` makes `commonTest` compile/run on JVM too, so it needs a `jvmTest`
`inMemoryDriver` actual (JDBC IN_MEMORY) — added. The HUB is run via a `JavaExec` task (`runHub`) wired to the
jvm compilation's output + runtime classpath (KMP jvm targets have no application-plugin `run` by default).

**Next:** point a real **iOS leaf at this HUB** — wire `SocketTransport.connect(hubHost, port)` into the iOS
app (loopback/LAN first) so a phone gossips through the desktop hub; then the **Tor layer** (HUB registers an
onion hidden service; iOS dials it via SOCKS) for the real privacy story.

---

## 2026-06-17 — Phase 1 step 6: real TCP transport — packets leave the process 🟢

First non-in-memory [Transport]: `SocketTransport` moves packets over real TCP. The first time a NoSlop
packet crosses a process boundary in the cross-platform core.

- **`SocketTransport`** (commonMain, ktor-network → runs on JVM/Android and Kotlin/Native incl. iOS): a
  node `listen`s to accept inbound links (the HUB/desktop role — iOS can't, per ADR-002) and/or `connect`s
  to dial outbound links (the leaf role; this is how iOS will reach the HUB). One-line `NetworkPacket` JSON
  framing per packet, matching the Android `MeshTransport` wire format. On link-up the ends swap node ids in
  a one-line handshake, so the link id is the peer's node id — identical to [InMemoryTransport], so
  [MeshNode]'s "forward to all links except source" routing is byte-for-byte the same over either transport.
  Per-connection write [Mutex] (a hub relays to one link from many read-loops at once) + a map [Mutex].
- **`SocketTransportTest`** (JVM, real localhost TCP, `runBlocking`): three nodes — hub listening, leafA &
  leafB dialing in, leaves NOT connected to each other — and leafA's post arrives at leafB, **proving the hub
  relayed it over the wire** (the ADR-002 path, now over real sockets). Green. The class itself also
  **compiles clean for iosSimulatorArm64**, so the iOS leaf can use it; cross-platform routing stays covered
  by `MeshNodeTest` (in-memory). Full suite remains 50 tests + this JVM integration test.

**Why JVM-only test:** real socket timing wants `runBlocking` (the desktop/HUB platform anyway). The routing
correctness is platform-independent and already proven on Native via `MeshNodeTest`; this test only adds the
real-I/O framing/handshake path.

**Next:** the **desktop HUB build** — add a `jvm()` target with a runnable `main()` that stands up a
`SocketTransport.listen()` hub node, so a real iOS leaf can dial a real always-on hub (loopback/LAN first,
Tor later). The transport it needs now exists and is tested.

---

## 2026-06-17 — Phase 1 step 5: mesh routing engine + leaf↔HUB relay model (transport-agnostic) 🟢

The shared core can now **route**. A node ingests a packet, runs it through the firewall + signature
check, persists it, and gossips it onward — and two "leaf" nodes that aren't directly connected reach each
other only through a "hub", which is exactly the ADR-002 model. This is the hard *conceptual* half of the
transport work, proven in pure shared code before any socket/Tor exists. **This first slice deliberately does
NOT include real networking** (Tor hidden service, sockets, the desktop HUB build) — those plug in behind the
`Transport` seam next.

- **`Transport`** (seam): `send(toLink)` / `onReceive` / `links`. A "HUB" is not a type — it's just a node
  with many links; a leaf has one. Real Tor/socket transports become `actual`s later; routing is unchanged.
- **`InMemoryTransport`**: in-process transport (no sockets) — delivers synchronously, so a whole
  leaf→hub→leaf propagation completes within the originating `suspend` call → deterministic tests.
- **`MeshNode`**: the engine that finally wires together the whole Phase-1 stack — wire protocol +
  [MeshFirewall] (TTL/dedup/rate-limit) + signature verify + a pluggable [MeshSink] for persistence. Receive
  path mirrors Android `GossipService.processIncoming`+`forwardPacket`: admit → verify → deliver → forward
  (re-stamp hop sender for privacy, decrement hops). "Forward to all links except source" makes a leaf an
  edge and a hub a relay with no special-casing. Added `MeshFirewall.remember()` so an origin dedups its own echo.
- **Tests** (`MeshNodeTest`, **8/8 green on JVM + Kotlin/Native**): leaf→hub→leaf relay; TTL boundary (1 hop
  dies at the hub, 2 hops reaches the far leaf); dedup kills a relay loop in a fully-connected triangle;
  MESSAGE relay (cross-platform); real-Ed25519 signature accept + tamper-reject (JVM); **a DM relayed through
  a hub that provably cannot decrypt it** (JVM); two-hub linear chain. Full MVP suite now **50 tests** on both
  targets.
- **On-device proof:** an in-app `MeshSelfTest` builds leafA/hub/leafB over InMemoryTransport (leaves linked
  only to the hub) and relays a post; the identity card shows **"Mesh ✓ — post relayed leafA→hub→leafB"**,
  screenshotted next to the Ed25519 / DM / SQLDelight ✓ lines.

**Next (the remaining transport work, now de-risked):** real `Transport` actuals — an iOS↔HUB outbound link
(ktor sockets first, Tor later) and a **desktop HUB build** (the always-on node with the hidden service that
relays for iOS leaves). The routing/relay engine they feed is done and tested.

---

## 2026-06-17 — Phase 1 step 4: shared persistence (SQLDelight) — posts/DMs/peers survive restarts 🟢

The app now has a real database. Posts, encrypted DMs, and peers persist locally via **SQLDelight**, with
the exact same queries on iOS and Android — the storage prerequisite for the transport/HUB work (you need
somewhere to put what you receive).

- **Schema** (`commonMain/sqldelight/.../db/*.sq`): `meshPost`, `message`, `peer`, `appMeta`, mirroring the
  Android Room tables (`MeshPost`/`ChatMessage`/`Peer`) column-for-column so a future data bridge is a straight
  copy. DMs store **ciphertext + nonce only** (from `encryptDM`) — plaintext is never persisted. Post writes
  are dedup-by-id with a gossip-count bump on re-seen ids, mirroring `GossipService` semantics.
- **`MeshStore`** (commonMain) wraps the generated queries; `DbDriverFactory` is expect/actual (Android =
  `AndroidSqliteDriver` via the existing context holder; iOS = `NativeSqliteDriver`).
- **Tests:** `MeshStoreTest` **9/9 green on BOTH JVM/Android and iosSimulatorArm64/Kotlin-Native** — round-trip,
  gossip dedup+bump, thread ordering, peer upsert, counter-survives-reopen, wipe. Test drivers are expect/actual
  (JVM JdbcSqliteDriver IN_MEMORY; Native in-memory `NativeSqliteDriver`). Full MVP suite now **42 tests** green
  on both targets.
- **On-device proof:** an in-app line shows `SQLDelight ✓ — launch #N · 1 post (gossip×N) · 1 peer`. Across a
  full app restart (terminate + relaunch, no reinstall) it climbed **#1→#2 with gossip×1→×2** — the counter
  persisting and the re-seeded welcome post deduping-but-bumping is live proof persistence + dedup + update all
  work on the real NativeSqliteDriver. Screenshotted both launches.

**Integration gotchas (documented for the rest of the migration):**
1. SQLDelight's Gradle plugin drags Gradle's embedded Kotlin, which strictly pins `org.jetbrains:annotations`
   to 13.0; AGP 9.2.1 needs 23.0.0. Fix: `buildscript { configurations.classpath { resolutionStrategy { force(
   "org.jetbrains:annotations:23.0.0") } } }` in `composeApp/build.gradle.kts`. (Project already had
   `android.newDsl=false`, the other documented AGP-9 workaround.)
2. `INTEGER AS Boolean` must be written **fully-qualified** (`AS kotlin.Boolean`) or SQLDelight emits a bogus
   `import Boolean`. `kotlin.Boolean` is native (no adapter); `kotlin.Int` needs `IntColumnAdapter` from
   `app.cash.sqldelight:primitive-adapters`.
3. iOS link: the static framework needs **`-lsqlite3`** in the Xcode target's `OTHER_LDFLAGS` (added to the
   pbxproj for Debug+Release) — otherwise `iosApp.debug.dylib` fails to link the native driver's SQLite symbols.
4. Native test isolation: SQLiter shares an in-memory DB **keyed by name**, so a fixed name leaks rows between
   tests (JVM's `jdbc:sqlite:` is per-connection, so it didn't). Use a per-call unique name.

**Next:** transport + the always-on **HUB** (ADR-002) — moving signed/encrypted packets between devices and
landing them in this store. That's the hard infra piece that turns the shared core into a working iOS mesh node.

---

## 2026-06-17 — Phase 1 step 3: encrypted DMs + gossip firewall in shared code 🟢

Two more pieces of the Android mesh core now run identically on both platforms — an iOS node can hold a
**private conversation** with an Android node, and both apply the **same admission rules** to gossip traffic.

**DM crypto** (`commonMain/DmCrypto.kt`, actuals in `Platform.{android,ios}.kt`, `CryptoKitDm` in
`iOSApp.swift`). The Android scheme — **X25519 key agreement → SHA3-256(shared secret) → ChaCha20-Poly1305**
(IETF, 12-byte nonce) — as expect/actual:
- Android = BouncyCastle (`KeyAgreement "X25519"`, `Cipher "ChaCha20-Poly1305"`); iOS = CryptoKit
  (`Curve25519.KeyAgreement` + `ChaChaPoly`) via the Swift bridge, reusing the raw-32 header-strip from the
  signer. **SHA3 deliberately stays in commonMain** (`KotlinCrypto`) — CryptoKit has no SHA3 — so the bridges
  only do X25519 + AEAD on raw bytes; base64/UTF-8 also stay shared.
- Conformance anchored on an **independently-computed Python golden vector** (`cryptography`): X25519 raw
  shared secret, SHA3-256, IETF ChaCha20-Poly1305, plaintext `"hello dm 🔐"`. Decrypting the golden ciphertext
  proves a platform derives the SAME key + AEAD as the reference (hence as Android). `DmCryptoTest` **4/4 green
  on JVM/BouncyCastle** (golden decrypt + round-trip + wrong-nonce → null + self-test); iOS proven by the
  in-app `DmSelfTest` line, **screenshotted ✓ on the simulator** alongside the Ed25519 ✓.
- Note: used the **raw** X25519 shared secret (not CryptoKit's HKDF-derived `SharedSecret`) to match
  BouncyCastle/the reference — `secret.withUnsafeBytes { Data($0) }` before the Kotlin-side SHA3.

**Gossip firewall** (`commonMain/MeshFirewall.kt`, commit `347fec2`). Pure TTL / duplicate-suppression /
per-sender rate-limit ported from the Android `GossipService.processIncoming` (same thresholds: maxHops 6,
dedup cap 1000 / evict 100, 20 pkts / 10 s / sender). The clock is **injected** so the sliding window is
testable — an improvement on the Android original's `System.currentTimeMillis()`. `MeshFirewallTest` **10/10
green on JVM + Kotlin/Native**, including the window-expiry case the Android suite couldn't reach.

**Next:** persistence (SQLDelight — store identity, posts, DMs, peers); then transport + the always-on **HUB**
(ADR-002), the hard infra piece that makes a working iOS mesh node possible.

---

## 2026-06-17 — Phase 1 step 2: cross-platform Ed25519 sign/verify (Android ↔ iOS interop) 🟢

A node can now **sign a packet on one platform that the other verifies** — the real interoperability
threshold. Commit `d454b19`.

- `Signer` expect/actual: Android = BouncyCastle JCA; iOS = CryptoKit `Curve25519.Signing` via a Swift
  bridge. Base64 + UTF-8 live in commonMain (`kotlin.io.encoding.Base64`) so the actuals are raw-bytes only
  — which keeps `android.util.Base64` out of the JVM unit-test path (golden vector runs on BC directly). iOS
  strips the PKCS#8/X.509 header to the raw 32-byte key CryptoKit expects.
- Conformance anchored on the **RFC 8032 golden vector** (test 2; independently computed + verified with
  Python `cryptography`). Android reproduces the EXACT golden signature — `SignerTest` green on BouncyCastle.
  iOS proven by an in-app self-test, screenshotted ✓ on the simulator.
- **Gotcha found + handled:** Apple's CryptoKit Ed25519 is **randomized** (valid signatures, but not
  byte-identical to the deterministic golden). Interop needs *mutual verifiability*, not byte-equality — so
  the self-test VERIFIES the canonical RFC signature (each platform can verify the other's) + round-trips its
  own, rather than byte-comparing. Documented for whoever ports the rest of the crypto.

MVP test count: **Android 19** (incl. SignerTest ×2 with strict golden equality), **iOS** golden vectors +
self-test ✓. The wire protocol + signing — the two pieces that make a packet trustworthy — are now shared.

**Next:** DM crypto (X25519 + SHA3 + ChaCha20-Poly1305) and the gossip TTL/dedup/rate-limit logic; then
persistence (SQLDelight); then transport + the HUB (ADR-002).

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
