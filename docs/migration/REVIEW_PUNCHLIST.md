# Migration Review Punch-List (2026-06-23)

Findings from a read-only review pass (compound-engineering: architecture, simplicity, security)
over the KMP/iOS migration code under `mvp/`. **No code was changed** — this is the to-do list to
work through *after* the next upstream (Gabby) wave lands, so the merge doesn't bury it.

Severity tags; **★ = flagged independently by 2+ reviewers (high confidence)**. "ours" vs "upstream"
notes whether the issue is in code we authored (`mvp/` iOS-KMP) or merged-in upstream Android code.

Foundation was praised by all three: transport→firewall→node→sink layering, expect/actual crypto
seams, pinned wire protocol + golden interop tests, leaf↔hub↔bridge model, and the crypto primitives
themselves (fresh 12-byte nonces, correct AEAD tag handling, consistent key encoding). Keep that as-is.

---

## 🔴 CRITICAL — security (do first)

- [x] **C1 ★ Signature verification is never wired.** `MeshNode.verify` defaults to `{ true }`; no
  caller passes a real verifier, and the check is skipped when `signature == null`. Forged/unsigned
  POST/REACTION/handshake packets are accepted. *(ours)* — `MeshNode.kt:41,56`, `MeshClient.kt:20`,
  `jvmMain/HubMain.kt:41`. Fix: supply a real Ed25519 verifier and reject unsigned packets for
  signed types.
- [x] **C2 Unauthenticated handshake → contact injection / MITM.** `onHandshake` adds a contact from
  attacker-supplied id + DM public key and auto-accepts, no signature check — key-substitution
  against the blind hub. Depends on C1. *(ours)* — `App.kt:475–495`. Also bind `fromUserId` ↔
  `fromEncryptionPublicKey`.
- [x] **C3 Clearnet fetch of mesh-supplied URLs = Tor deanonymization.** A malicious post's
  `clearnet_thumbnail_url`/`clearnet_url` makes the device hit an attacker server directly over
  clearnet (real IP leak) on scroll. HTTP clients aren't proxied through Tor. *(ours)* —
  `Platform.ios.kt:317` (`HttpClient(Darwin)` no proxy), `NetworkImage.kt:41`, `App.kt:391–398`.
  Fix: never fetch mesh-post URLs over clearnet (or route through Tor).

## 🟠 HIGH

- [x] **H1 ★ `MeshFirewall` thread-safety.** Unsynchronized `seen`/`rates` mutated by N concurrent
  per-link read-loop coroutines on the hub/bridge (not the iOS single-leaf). Corrupted dedup →
  dropped/duplicated packets or a hung hub under fan-out. *(ours)* — `MeshFirewall.kt:21–47`,
  `SocketTransport.kt:179`, `BridgeMain.kt:50,106`. Fix: guard with a Mutex/lock.
- [ ] **H2 No hub identity pinning.** `MeshInvite.nodeId` is transmitted but never compared on
  connect — anyone answering at the onion becomes the hub undetected. *(ours)* — `App.kt:525`,
  `SocketTransport.kt:160`.
- [ ] **H3 Bridge inbound listener authenticates nothing.** Anyone who learns the bridge onion can
  inject packets to all leaves (remote delivery path for C1/C2). Blind-hub property itself is intact
  (never decrypts DMs). *(ours)* — `BridgeMain.kt:93–124`.

## 🟡 MEDIUM

- [ ] **M1 ★ Two conflicting mesh-social code paths.** `data/MeshSocialRepository.kt` +
  `EngagementRepository.kt` (~326 LOC, from upstream) re-invent `ReactionPayload`/`CommentPayload`
  with a wire shape incompatible with our pinned `Packets.kt`, and `MeshNode` doesn't deliver their
  types. Currently **dead** (zero refs) but a landmine. *(upstream — likely changes in Gabby's wave,
  defer)* — collapse onto `Packets.kt` or delete.
- [ ] **M2 ★ `NodeId.isAndroidId` is a char-class heuristic** gating all bridge routing; a
  misclassification = silently undeliverable DMs. *(ours)* — `NodeId.kt:37–38`. Fix: structural
  length/DER check + enforce 64-lowercase-hex invariant.
- [ ] **M3 `DIAL_PEER` processed pre-firewall, unsigned, unbounded** — a leaf can make the bridge
  dial arbitrary onions; peer map uncapped. *(ours)* — `BridgeMain.kt:44–49,141–153`.
- [ ] **M4 No `HttpTimeout`/response-size cap** on fetches → decode-bomb/OOM/hang via hostile URL.
  (Per-source timeouts exist in the feed, but not at HttpClient level or in `NetworkImage`.) *(ours)*
  — `NetworkImage.kt:28`, `Feed.kt`, `feeds/api/*`.
- [ ] **M5 DMs encrypted but not sender-authenticated** — decryption uses sender-supplied enc key;
  reply contact uses sender-supplied node id. Bind to verified identity (follow-on from C1/C2).
  *(ours)* — `MeshClient.kt:33,37`.
- [ ] **M6 Connect success inferred from racy `linkCount`** (link registered on a different
  coroutine); reconnect-monitor / Tor-retry / user-tap interaction is fragile. *(ours)* —
  `App.kt:525–584`, `SocketTransport.kt:179`.
- [ ] **M7 API keys in plaintext app-meta** — acceptable for these low-value read keys; do NOT extend
  the store to anything sensitive. *(ours)* — `feeds/ApiKeyStore.kt`.

## ⚪ CLEANUP (lowest)

- [ ] `MeshUiState` is a ~300-line god-object (connection + Tor retry + handshake state machine +
  reactions + DMs) in UI code, untestable — extract a non-UI `MeshController`/`MeshSession`.
  `App.kt:429–739`.
- [ ] Persistence is write-only from the UI — `MeshStore` exists but feed/reactions aren't loaded on
  launch. `App.kt:432,458`.
- [ ] Dead code: `MeshClient.dmPublicKeyB64()` (`MeshClient.kt:68`), unused `ChatScreen` +
  its import (`App.kt:91`, `ui/tabs/ChatScreen.kt`), doubled KDoc on `myContactQr()` (`App.kt:624–629`).
- [ ] Duplicated helpers across the 12 API clients: `parseDate`/`stripHtml`/`Json{}` (~120–180 LOC) →
  one `feeds/api/ApiUtil.kt`. *(touches upstream-adjacent surface — defer to post-merge.)*
- [ ] Route transport/bridge `println` through the existing `debug/Logger`.
- [ ] Make `NetworkPacket.signature` a `val`; document which of packet-level vs payload-level
  signature is canonical. `Packets.kt:35`.

---

### Read of the whole thing
The security cluster (C1–C3, H2, H3, M5) is the real story and mostly **one root cause**: the
signing/verifying machinery was built but the verification loop + transport trust were never closed.
Highest-leverage fix, and squarely our code. M1 is upstream and may change in Gabby's wave — defer.
