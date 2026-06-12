# NoSlop — Gap Analysis Against gChat & HAI-Net

**Purpose**: NoSlop is a Kotlin/Android re-implementation of gChat's serverless social
mesh (the "HAI-Net Social" layer), with a clearnet content aggregator and
content-bridge bolted on top. gChat is the original Node.js/React reference
implementation, and `hainet-social` (inside the HAI-Net monorepo) is the
canonical Rust port of gChat that the rest of the HAI-Net ecosystem is
converging on. This document captures details, protocols, and design
decisions that exist in gChat and/or HAI-Net but are **not yet reflected** in
NoSlop's own documentation (`docs/PROJECT_STATUS.md`, `docs/PACKET_SCHEMA.md`,
`docs/ANALYSiS.md`, README). It is intended as a backlog / spec reference for
bringing NoSlop's mesh layer to parity with its siblings.

Everything below is organized by subsystem. Each item notes:
- **Source**: which codebase the detail comes from
- **NoSlop status**: present / partial / absent
- **Why it matters**: relevance to NoSlop's roadmap

---

## 1. Packet Types — Missing From NoSlop's Wire Protocol

NoSlop's `Packets.kt` / `PACKET_SCHEMA.md` currently define:
`POST, MESSAGE, CONNECTION_REQUEST, USER_HANDSHAKE, SYNC_REQUEST,
SYNC_RESPONSE, COMMENT, REACTION, MEDIA_REQUEST, MEDIA_CHUNK,
MEDIA_RELAY_REQUEST, MEDIA_RECOVERY_FOUND, MEDIA_PENDING, MEDIA_TRANSFER_ACK`.

gChat's `services/packetSchema.ts` (and the matching `hainet-social/src/packets.rs`)
define a substantially larger set. The following packet types exist in
gChat/HAI-Net but have **no equivalent** in NoSlop:

| Packet | Source | Purpose | NoSlop status |
|---|---|---|---|
| `EDIT_POST` | gChat | Author edits an existing post's content in place; propagates the new content + original post ID | Absent — NoSlop posts are immutable once gossiped |
| `DELETE_POST` | gChat | Signed tombstone request; marks a post `isOrphaned` across the mesh ("Nuclear Option" in HAI-Net Vision docs) | Absent |
| `VOTE` | gChat | Up/down vote on a **post** (distinct from emoji `REACTION`); feeds the Content Health soft/hard-block ratio | Partial — NoSlop folds "downvote" into `REACTION` (`reactionType="downvote"`), but gChat tracks `votes` and `reactions` as separate maps on the post |
| `COMMENT_VOTE` | gChat | Up/down vote on an individual **comment** | Absent — NoSlop comments have no voting at all |
| `COMMENT_REACTION` | gChat | Emoji reaction scoped to a comment (not just the parent post) | Absent |
| `CHAT_REACTION` | gChat | Emoji reaction on a **direct message** (1:1 chat) | Absent — README's Phase 2 roadmap mentions "Extend reactions to Direct Messages" but no packet type is defined yet |
| `CHAT_VOTE` | gChat | Up/down vote on a chat message | Absent |
| `IDENTITY_UPDATE` | gChat | Propagates changes to display name / avatar / bio to peers without a full re-handshake | Absent — NoSlop has no mechanism to notify peers of profile changes after initial handshake |
| `ANNOUNCE_PEER` | gChat | Lightweight "I'm online" heartbeat carrying `onionAddress` + `alias`; drives gChat's "Live Contact Sync" (instant green/online indicator) | Absent — NoSlop has no online/offline presence signal at all |
| `FOLLOW` / `UNFOLLOW` | gChat | Asymmetric "follow" relationship distinct from the symmetric Trusted Peer / firewall relationship | Absent — NoSlop's only relationship model is binary trust (`Peer.isTrusted`) |
| `GROUP_INVITE` / `GROUP_UPDATE` / `GROUP_DELETE` / `GROUP_QUERY` / `GROUP_SYNC` | gChat | Full decentralized group-chat packet family (see §3) | Absent — NoSlop README Phase 2 lists "Group Chats" as planned but no schema exists |
| `TYPING` | gChat | Ephemeral typing-indicator packet (not persisted) | Absent |
| `READ_RECEIPT` | gChat | Per-message read acknowledgement | Absent |
| `INVENTORY_SYNC_REQUEST` / `INVENTORY_SYNC_RESPONSE` | gChat | Hash-based inventory diffing — peer sends a list of `{id, hash}` pairs; the other side replies only with posts the requester is missing | Partial — NoSlop's `SYNC_REQUEST`/`SYNC_RESPONSE` is timestamp-based ("everything since X"), not hash-based. Less efficient and can re-send posts the peer already has |
| `USER_EXIT` / `NODE_SHUTDOWN` | gChat | Graceful shutdown protocol — broadcast on exit, peers ACK, process waits up to 30s before terminating (avoids "ghost peers") | Absent — NoSlop has no shutdown broadcast; peers will see a node as "last seen" stale rather than cleanly offline |

### Recommendation
At minimum, NoSlop should prioritize:
1. `ANNOUNCE_PEER` (online presence — currently the biggest UX gap vs gChat's "Live Contact Sync")
2. `COMMENT_REACTION` / `COMMENT_VOTE` (comments currently have zero engagement signals)
3. `INVENTORY_SYNC_REQUEST`/`RESPONSE` to replace timestamp-based sync (reduces redundant transfer)
4. `EDIT_POST` / `DELETE_POST` for basic moderation/correction capability

---

## 2. Content Health / Moderation — Partially Ported, Some Details Missing

NoSlop's milestone 90 ("Enhanced Reactions and Content Health (gChat Alignment)")
already ports the soft-block (>66% negative) / hard-block (>95% negative, min 5
total) thresholds from gChat v1.3.1. However, gChat's `docs/PROJECT_STATUS.md`
describes additional behavior not yet present in NoSlop:

- **Opt-in Transparency**: gChat lets users choose, via a settings toggle, to
  interact with "Community Flagged" content anyway — the soft-block overlay
  is replaced by a non-blocking warning badge. NoSlop's soft-block is
  currently a hard overlay with no user override.
- **Hard-block interaction lockout**: gChat explicitly disables *all*
  interactions (votes, comments, replies, reactions) on hard-blocked posts.
  NoSlop should verify this is enforced — the milestone notes the *display*
  overlay but doesn't mention disabling the underlying action handlers.
- **Separate `votes` vs `reactions` maps**: gChat's `PostSchema` keeps
  `votes: Record<userId, 'up'|'down'>` and `reactions: Record<emoji,
  userId[]>` as two independent structures. NoSlop currently overloads
  `REACTION` (`reactionType` includes `"upvote"`/`"downvote"` alongside emoji
  reactions like `"😡"`), which conflates the two signals the Content Health
  algorithm is supposed to combine. Splitting these mirrors gChat's model and
  makes the "downvotes + 😡 > 66%" formula explicit rather than implicit.

---

## 3. Group Chats — Full Spec Exists in gChat, Absent in NoSlop

NoSlop's README lists "Group Chats" as a Phase 2 planned feature with no
schema. gChat already has a complete, working implementation
(`GroupSettingsModal.tsx`, `groups.rs` in hainet-social, and the `GroupSchema`
in `packetSchema.ts`). The relevant spec:

```
GroupSchema = {
  id: string
  name: string
  members: string[]        // public keys
  admins: string[]          // subset of members
  ownerId: string            // single owner, distinct from admins
  bannedIds?: string[]
  settings?: {
    allowMemberInvite: boolean
    allowMemberNameChange: boolean
  }
  isMuted?: boolean          // local-only mute flag, not synced
}
```

**Propagation model** (from gChat `PROJECT_STATUS.md` §4 "Group Dynamics"):
- `GROUP_INVITE` — sent directly (not gossiped) to each new member's onion
  address; contains the full `Group` object so the invitee can bootstrap
  group state without querying anyone else.
- `GROUP_UPDATE` — broadcast to all current members whenever membership,
  name, admin list, or settings change. Receivers overwrite their local copy
  of the group by `id`.
- `GROUP_DELETE` — owner-only; removes the group for all members.
- `GROUP_QUERY` / `GROUP_SYNC` — a member who reconnects after being offline
  asks any currently-connected member for the latest group list state
  (`GROUP_QUERY` with `requesterId`); any member can respond with
  `GROUP_SYNC` containing all groups it knows about that include the
  requester.
- **Role permissions**: Owner can kick/ban/promote/demote and delete the
  group. Admins can kick/ban (but not the owner) and, if
  `allowMemberInvite=false`, are the only ones who can `GROUP_INVITE`.
  Regular members can rename themselves within the group if
  `allowMemberNameChange=true`.
- **Group encryption**: gChat's `cryptoService.ts` performs **multi-recipient
  encryption** for group payloads — the message is encrypted once per
  member's X25519 public key (NaCl `box`, not a shared symmetric group key),
  meaning a `MESSAGE` packet sent to a group is fanned out as N individually
  encrypted `EncryptedPayload`s, each carrying the same `groupId` so receivers
  know which group thread to file it under. There is no group-wide symmetric
  key — this avoids key-rotation-on-membership-change complexity at the cost
  of O(N) encryption work per message.

For NoSlop, implementing groups would require: a `Group` Room entity mirroring
`GroupSchema`, the five `GROUP_*` packet types in `Packets.kt`, fan-out logic
in `NoSlopRepository.sendDirectMessage` (or a new `sendGroupMessage`) that
loops over `group.members` and calls `CryptoService.encryptDM` once per
recipient, and UI surfaces equivalent to gChat's `GroupSettingsModal.tsx`.

---

## 4. Presence / Online Status — Entirely Absent in NoSlop

gChat's "Live Contact Sync" (v1.4.0) makes contacts turn green/online the
moment their hosting node comes online, driven by the `ANNOUNCE_PEER` packet
(`payload: { onionAddress, alias?, description? }`). This is a lightweight,
unsigned, frequently-rebroadcast packet — essentially a heartbeat — distinct
from the heavyweight signed `USER_HANDSHAKE`/`CONNECTION_REQUEST` packets.

NoSlop currently has **no online/offline concept**. `Peer.lastSeenAt` is only
updated on handshake or incoming packets, so the UI cannot distinguish "online
right now" from "was seen three days ago."

> **UPDATE (2026-06-13):** This gap has been **fully closed**. `ANNOUNCE_PEER`
> heartbeats broadcast every 60s to trusted peers, `Peer.isOnline` is tracked
> in Room, stale peers auto-timeout after 3 minutes, and `PeerItem.kt` renders
> a green online indicator. See `PROJECT_STATUS.md` milestone 156.

---

## 5. Shutdown Protocol — Missing in NoSlop

gChat's shutdown protocol (`docs/ARCHITECTURE.md` §6):

1. On `SIGINT`/logout, the node broadcasts `USER_EXIT { userId }` to all
   connected peers.
2. The node **waits up to 30 seconds** for `USER_EXIT_ACK` from each peer
   before tearing down its hidden service and exiting.
3. This specifically mitigates "Ghost Peers" — the situation where Tor
   circuit teardown takes long enough that peers continue showing a node as
   connected/online for an extended period after it has actually gone away.

NoSlop has no equivalent. On Android this is less critical (the OS kills
processes more abruptly than a desktop shutdown), but the `logout()` flow in
`IdentityRepository`/`NoSlopRepository` could still broadcast a `USER_EXIT`-
equivalent so peers' presence indicators (once §4 is implemented) update
promptly rather than relying on a timeout.

---

## 6. Trusted Media Relay — Conceptually Present, Streaming Semantics Differ

Both NoSlop (`GossipService.handleRelayRequest`/`handleRecoveryFound`,
`MediaProxyService`) and gChat/HAI-Net implement the "Trusted Relay" pattern
for fetching media from an unknown author via a mutual friend:

1. Requester broadcasts `MEDIA_RELAY_REQUEST` to trusted peers only.
2. A friend who doesn't have the content forwards the request along the
   mesh (daisy chain).
3. When the author is located, it (or the chain) responds with
   `MEDIA_RECOVERY_FOUND`.
4. The friend acts as a streaming proxy for subsequent `MEDIA_CHUNK` traffic.

gChat's `docs/ARCHITECTURE.md` describes this explicitly as **"Pure
Streaming Proxy"**: the relaying friend does *not* download the file to disk
or RAM — it forwards `MEDIA_CHUNK` packets on-demand, re-stamping sender
identity so the requester never learns the original author's `.onion`
address. The Rust port (`hainet-social/src/relay.rs`) models this with a
`RelayManager` holding `RelayRoute { source_peer_id, target_peer_id,
established_at, last_activity }` per transfer session, with a
`cleanup_stale_routes(timeout_secs)` sweep.

NoSlop's current `GossipService.RelayState` (in `GossipService.kt`) tracks
`mediaId -> listeners` but:
- Has **no stale-route cleanup** — ~~`relayStates` is an unbounded
  `ConcurrentHashMap` with no TTL/eviction, a potential slow memory leak on
  long-running nodes that participate in many relays.~~ **FIXED (2026-06-13):**
  `RelayState` now tracks `establishedAt`/`lastActivity`. A periodic 60-second
  sweeper evicts routes idle for >5 minutes. `MeshPacketHandler` refreshes
  `lastActivity` on every `MEDIA_CHUNK` via `GossipService.touchRelayState()`.
- Does **not yet implement the actual chunk-forwarding proxy** —
  `handleRelayRequest`/`handleRecoveryFound` resolve *where* the media is, but
  `MeshPacketHandler.handleMediaChunk` delegates straight to
  `MediaManager.handleMediaChunk`, which is written from the perspective of
  "I am downloading this for myself," not "I am forwarding chunks I don't
  want to keep." A relaying node in NoSlop today would therefore download
  the whole file rather than acting as a zero-copy pass-through, contrary to
  the "pure streaming proxy" privacy guarantee gChat documents.

---

## 7. Congestion Control for Media Chunks — Absent in NoSlop

`hainet-social/src/congestion.rs` (the Rust port, derived from gChat's binary
transport strategy) implements an AIMD (Additive-Increase/Multiplicative-
Decrease) congestion controller per peer/download:

- Starts with a window of **4** concurrent in-flight chunks
  (`INITIAL_WINDOW`), caps at **128** (`MAX_WINDOW`).
- Slow-start: window += 1 per ACK while `window < ssthresh`.
- On timeout/loss: `ssthresh = max(2, window * 0.5)`, window resets to 1
  (Reno-style).
- Maintains an EMA of RTT (`new_rtt = (current_rtt*7 + sample)/8`) used to
  size timeouts (`TIMEOUT_MS = 5000` default).

NoSlop's `MediaManager` has a flat `MAX_CONCURRENCY = 4` constant and a fixed
`DOWNLOAD_TIMEOUT_MS = 60000` — no adaptive behavior. Over Tor, where circuit
latency and throughput vary wildly between peers, an AIMD-style controller
(even a simplified version) would likely reduce both stalled downloads (window
too large for a slow circuit) and under-utilization (window too small for a
fast one). This is called out as a known gap because NoSlop's own
`PROJECT_STATUS.md` flags general media reliability issues ("Audio Playback
Failure", "Feed Pre-loading & Hybrid Mixing") that an adaptive window could
partially address.

---

## 8. Plugin & Theme Engine — gChat Feature With No NoSlop Analogue

gChat v1.5.0 added:
- **`pluginLoader.js`**: dynamically imports backend extensions from a
  `/plugins` directory at runtime — third-party Node modules that can hook
  into the gossip pipeline, add new packet handlers, or expose new
  socket.io events.
- **`themeEngine.ts`**: frontend service that asynchronously fetches and
  mounts CSS files from `/themes` into the DOM, allowing users to skin the UI
  without rebuilding.

NoSlop, being a compiled native Android app, cannot directly replicate
dynamic code loading (Play Store policy + APK signing make runtime-loaded
native plugins impractical), but the **theming** half is feasible — NoSlop
already has a `ui/theme/` package (`Color.kt`, `Theme.kt`, `Type.kt`) with a
hardcoded "brutalist dark-mode" palette (`AccentGreen`, `DestructiveRed`,
`SurfaceDark`). A user-selectable theme system (even just 3-4 built-in
palettes stored as `AppSetting` JSON and applied via `MaterialTheme`
overrides) would bring NoSlop conceptually closer to gChat's theme engine
without the security implications of dynamic plugin loading. This is a "nice
to have," not a parity-critical item, and is **not** recommended as a
priority — flagged here purely because it's a documented gChat capability
with zero NoSlop mention.

---

## 9. Master/Slave Node Roles & Dual Hidden Services — HAI-Net/gChat Concept, Not Applicable to NoSlop As-Is

gChat's backend (`server.js`) supports four `NODE_ROLE` values (`MASTER`,
`SLAVE_STORAGE`, `SLAVE_FRONTEND`, `MICRO_SITE`) and runs **two** Tor v3
hidden services simultaneously: a public one for mesh routing (mapped to
local port 3456) and a private, authenticated one for remote admin/frontend
access (mapped to local port 3001). This lets a user's phone run a
"frontend-only" UI that talks to a home-server "master" node over its private
onion.

This entire model is **architecturally inapplicable to NoSlop as a standalone
Android app** — NoSlop *is* the single-device node; there's no separate
backend process to split into roles. However, it is directly relevant to
**how NoSlop fits into the broader HAI-Net picture**: the HAI-Net `VISION.md`
describes the "Local Hub" as a mesh of a user's own devices (desktop, NAS, old
phone) collectively running the full stack. In that world, NoSlop running on
a phone could become the equivalent of gChat's `SLAVE_FRONTEND` — a UI-only
node that authenticates to a HAI-Net hub's private onion rather than running
its own embedded Tor daemon and full mesh stack. This is **not a near-term
NoSlop change**, but worth documenting so future architecture discussions
about "NoSlop as a HAI-Net hub client" have this precedent on record.

---

## 10. Identity System — Naming & Derivation Differences Worth Reconciling

All three codebases agree on the core algorithm (Ed25519 public key → SHA3-256
→ Base32 → first 6 chars, lowercase → tripcode), but there are small
terminology/format differences worth noting for cross-compatibility:

- **gChat** (`docs/IDENTITY_SYSTEM.md`) describes the hash step as
  "SHA-256 (or SHA3-256)" — i.e. it documents some ambiguity/flexibility.
  **HAI-Net's Rust port** (`hainet-social/src/identity.rs`) and **NoSlop**
  (`CryptoService.deriveTripcode`) both concretely use **SHA3-256**. NoSlop's
  implementation is therefore aligned with the canonical Rust spec, but
  gChat's own docs are looser — if NoSlop ever needs to interoperate with a
  live gChat node (not just HAI-Net), it's worth confirming which hash the
  *running* gChat `cryptoService.ts` actually uses (the docs hedge, the code
  is presumably definitive).
- **Display format**: All three agree on `Handle.Tripcode` (e.g.
  `Tom.x7z9`), with the UI convention of bold handle + dim monospace
  tripcode. NoSlop's `CryptoService.IdentityKeys.displayName` already follows
  this (`"$handle.$tripcode"`).
- **Key exchange algorithm naming**: gChat's `SECURITY.md` says E2EE uses
  "XSalsa20-Poly1305 (NaCl `box`)" with X25519 key exchange. NoSlop uses
  **ChaCha20-Poly1305** (a closely related but distinct AEAD — both are
  Bernstein-family ciphers, but XSalsa20 and ChaCha20 are different stream
  ciphers) with an X25519 → SHA3-256 → ChaCha20 key derivation. This means
  **NoSlop's DM ciphertexts are not wire-compatible with gChat's `box()`
  output** even though both use X25519 for key agreement. This divergence is
  intentional (NoSlop's `CryptoService` comment documents it as
  "ECDH (P-256) ... -> AES-256-GCM" in one place and ChaCha20-Poly1305 in the
  actual implementation — see also item 11 below for the doc/code mismatch).
  For HAI-Net-wide interoperability, `hainet-social/src/crypto.rs` should be
  treated as the tie-breaker spec once it's finalized.

---

## 11. Documentation/Code Mismatches Found *Within* NoSlop Itself

While comparing against gChat/HAI-Net, the following internal inconsistencies
in NoSlop's existing docs vs. its code were also noticed (not gaps vs. other
projects, but worth fixing alongside the above):

- **README "Tech Stack" table** lists DM encryption as "ChaCha20-Poly1305
  (DMs), AES-256-CBC (backup)" — correct and matches code.
- **`CryptoService.kt` class-level KDoc comment**, however, says: *"DM Crypto:
  ECDH (P-256) key agreement -> SHA-256 -> AES-256-GCM"*. This is stale/wrong
  on three counts: the actual key agreement is **X25519** (not P-256/ECDH),
  the hash is **SHA3-256** (not SHA-256), and the cipher is **ChaCha20-
  Poly1305** (not AES-256-GCM). The README is correct; the code comment is
  outdated and should be fixed to avoid confusing future contributors.
- **Earlier README sentence** ("End-to-end encrypted DMs — direct messages
  use ECDH (X25519) key agreement into AES-256-GCM") repeats the AES-256-GCM
  error from the stale code comment, while the Tech Stack table (further
  down the same README) correctly says ChaCha20-Poly1305. These two sections
  of the README contradict each other.
- **`PACKET_SCHEMA.md`** documents `clearnet_thumbnail_url` as absent from the
  `POST` payload table, but `Packets.kt`'s `PostPayload` (and milestone 90 in
  `PROJECT_STATUS.md`) already include `clearnet_thumbnail_url`. The schema
  doc is one field behind the code.

---

## 12. Summary Checklist

Quick-reference list of items from this document, roughly ordered by
implementation effort vs. value:

- [x] Fix doc/code mismatches in `CryptoService.kt` KDoc and README (§11) — trivial, do first
- [x] Add `clearnet_thumbnail_url` to `PACKET_SCHEMA.md`'s POST table (§11)
- [x] `ANNOUNCE_PEER` packet + online/offline presence indicator (§4)
- [ ] `COMMENT_REACTION` / `COMMENT_VOTE` packets + UI (§1, §2)
- [ ] Split `votes` vs `reactions` data model per gChat's `PostSchema` (§2)
- [ ] "Opt-in Transparency" override for soft-blocked content (§2)
- [x] Relay state TTL/cleanup in `GossipService.RelayState` (§6)
- [ ] True zero-copy chunk-forwarding for `MEDIA_RELAY_REQUEST` relays (§6)
- [x] `INVENTORY_SYNC_REQUEST`/`RESPONSE` (hash-based sync) to replace
      timestamp-based `SYNC_REQUEST`/`RESPONSE` (§1)
- [ ] `EDIT_POST` / `DELETE_POST` packets (§1)
- [x] AIMD congestion control for media chunk downloads (§7)
- [ ] `CHAT_REACTION` / `CHAT_VOTE` for DM engagement (§1)
- [ ] `IDENTITY_UPDATE` packet for profile-change propagation (§1)
- [ ] `FOLLOW`/`UNFOLLOW` asymmetric relationship model (§1)
- [ ] `USER_EXIT` graceful-shutdown broadcast on logout (§5)
- [ ] Group chats: `Group` entity + `GROUP_*` packets + multi-recipient
      encryption fan-out (§3)
- [ ] User-selectable theme palettes (low priority, §8)
- [ ] Document NoSlop's potential future role as a HAI-Net hub
      "frontend client" (§9) — architecture discussion, not code
