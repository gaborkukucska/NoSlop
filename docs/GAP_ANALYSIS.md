# NoSlop — Gap Analysis Against gChat & HAI-Net

**Purpose**: NoSlop is a Kotlin/Android re-implementation of gChat's serverless social
mesh (the "HAI-Net Social" layer), with a clearnet content aggregator and
content-bridge bolted on top. gChat is the original Node.js/React reference
implementation, and `hainet-social` (inside the HAI-Net monorepo) is the
canonical Rust port of gChat that the rest of the HAI-Net ecosystem is
converging on. This document captures details, protocols, and design
decisions that exist in gChat and/or HAI-Net but are **not yet reflected** in
NoSlop's own documentation (`docs/PROJECT_STATUS.md`, `docs/PACKET_SCHEMA.md`,
`docs/archived/ANALYSiS.md`, README). It is intended as a backlog / spec reference for
bringing NoSlop's mesh layer to parity with its siblings.

Everything below is organized by subsystem. Each item notes:
- **Source**: which codebase the detail comes from
- **NoSlop status**: present / partial / absent
- **Why it matters**: relevance to NoSlop's roadmap

> **2026-06-13 merge note**: this document previously had a companion file,
> `docs/ADDENDUM.md`, that cross-referenced a test-run log against the
> codebase and flagged places where this document's "Done" checkmarks had
> outpaced `docs/TECHNICAL_REFERENCE.md`'s prose. That content has now been
> merged in below (§7's congestion-control status, §13's log findings, and
> §14's HAI-Net ecosystem notes) and `ADDENDUM.md` has been removed to avoid
> two files describing the same gaps with different "current" answers.

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
| `EDIT_POST` | gChat | Author edits an existing post's content in place; propagates the new content + original post ID | **Done** — Added in recent updates |
| `DELETE_POST` | gChat | Signed tombstone request; marks a post `isOrphaned` across the mesh ("Nuclear Option" in HAI-Net Vision docs) | **Done** — Added in recent updates |
| `VOTE` | gChat | Up/down vote on a **post** (distinct from emoji `REACTION`); feeds the Content Health soft/hard-block ratio | **Done** — `MeshVote` entity + `VoteDao` + `VOTE` packet type + UI integration. Content Health now sources upvote/downvote counts from `mesh_votes` table |
| `COMMENT_VOTE` | gChat | Up/down vote on an individual **comment** | **Done** — `CommentVote` entity + `CommentVoteDao` + `COMMENT_VOTE` packet type + CommentsBottomSheet integration |
| `COMMENT_REACTION` | gChat | Emoji reaction scoped to a comment (not just the parent post) | **Done** — `COMMENT_REACTION` payloads and persistence added in Phase 3 |
| `CHAT_REACTION` | gChat | Emoji reaction on a **direct message** (1:1 chat) | **Done** — `CHAT_REACTION` payloads and DM long-press UI added in Phase 3 |
| `CHAT_VOTE` | gChat | Up/down vote on a chat message | Absent |
| `IDENTITY_UPDATE` | gChat | Propagates changes to display name / avatar / bio to peers without a full re-handshake | **Done** — Handled when `displayName` is changed in settings |
| `ANNOUNCE_PEER` | gChat | Lightweight "I'm online" heartbeat carrying `onionAddress` + `alias`; drives gChat's "Live Contact Sync" (instant green/online indicator) | **Done** — `ANNOUNCE_PEER` implemented in Phase 1 |
| `FOLLOW` / `UNFOLLOW` | gChat | Asymmetric "follow" relationship distinct from the symmetric Trusted Peer / firewall relationship | Absent — NoSlop's only relationship model is binary trust (`Peer.isTrusted`) |
| `GROUP_INVITE` / `GROUP_UPDATE` / `GROUP_DELETE` / `GROUP_QUERY` / `GROUP_SYNC` | gChat | Full decentralized group-chat packet family (see §3) | Absent — NoSlop README Phase 2 lists "Group Chats" as planned but no schema exists |
| `TYPING` | gChat | Ephemeral typing-indicator packet (not persisted) | Absent |
| `READ_RECEIPT` | gChat | Per-message read acknowledgement | Absent |
| `INVENTORY_SYNC_REQUEST` / `INVENTORY_SYNC_RESPONSE` | gChat | Hash-based inventory diffing — peer sends a list of `{id, hash}` pairs; the other side replies only with posts the requester is missing | **Done** — Replaced legacy sync with `INVENTORY_SYNC_REQUEST` in Phase 2 |
| `USER_EXIT` / `NODE_SHUTDOWN` | gChat | Graceful shutdown protocol — broadcast on exit, peers ACK, process waits up to 30s before terminating (avoids "ghost peers") | **Done** — NoSlop implements `USER_EXIT` during logout and foreground service termination |

### Recommendation
At minimum, NoSlop should prioritize:
1. ~~`ANNOUNCE_PEER` (online presence — currently the biggest UX gap vs gChat's "Live Contact Sync")~~ ✅ Resolved.
2. ~~`COMMENT_REACTION` / `COMMENT_VOTE` (comments currently have zero engagement signals)~~ ✅ Resolved.
3. ~~`INVENTORY_SYNC_REQUEST`/`RESPONSE` to replace timestamp-based sync (reduces redundant transfer)~~ ✅ Resolved.
4. ~~`EDIT_POST` / `DELETE_POST` for basic moderation/correction capability~~ ✅ Resolved.
5. ~~`VOTE` / `COMMENT_VOTE` (separate vote data model from emoji reactions)~~ ✅ Resolved.

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
  userId[]>` as two independent structures.

  > **UPDATE (2026-06-13):** This gap has been **fully closed**. NoSlop now
  > maintains `MeshVote` / `CommentVote` entities in dedicated Room tables
  > (`mesh_votes`, `comment_votes`), separate from emoji reactions. `VOTE`
  > and `COMMENT_VOTE` packet types are defined in `Packets.kt` with
  > crypto-verified handlers in `MeshPacketHandler`. The ViewModel routes
  > "upvote"/"downvote" actions to `voteToMeshPost`/`voteToComment` while
  > emoji reactions continue using the legacy `REACTION` path. Content
  > Health scoring in `FeedCard`, `MainScreen`, and `CommentsBottomSheet`
  > now sources upvote/downvote counts from the `mesh_votes` table and
  > combines them with angry reactions from the `reactions` table for the
  > soft-block/hard-block ratio calculation.

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

> **UPDATE (2026-06-13):** This gap has been **fully closed**. NoSlop now properly implements the `USER_EXIT` packet, and triggers `broadcastUserExit()` both during `logout()` and inside the lifecycle hook `NoSlopForegroundService.onDestroy()`. When a `USER_EXIT` packet is received, the node immediately sets the peer's `isOnline` status to `false`.

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

## 7. Congestion Control for Media Chunks — Implemented (was: Absent)

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

**Status: implemented.** `MediaManager.kt` now has a per-download AIMD state
machine matching this algorithm: `windowSize` starting at 4.0, `ssthresh`
starting at 128.0, slow-start `windowSize += 1` while `windowSize <
ssthresh`, congestion-avoidance `windowSize += 1/windowSize` otherwise,
multiplicative decrease `ssthresh = max(2, windowSize * 0.5); windowSize = 1`
on timeout, capped at 128, plus an RTT EMA. `MAX_CONCURRENCY = 4` remains in
the file but now only serves as the AIMD initial window value, not a hard
concurrency cap. See
[TECHNICAL_REFERENCE.md §6.1](TECHNICAL_REFERENCE.md#61-chunking-constants-mediamanagerkt)
for the exact current code-level detail.

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
projects, but worth fixing alongside the above). All items below are now
resolved as of this revision; kept for traceability:

- ~~**README "Tech Stack" table** lists DM encryption as "ChaCha20-Poly1305
  (DMs), AES-256-CBC (backup)" — correct and matches code. **`CryptoService.kt`
  class-level KDoc comment**, however, says: "DM Crypto: ECDH (P-256) key
  agreement -> SHA-256 -> AES-256-GCM". This is stale/wrong on three counts:
  the actual key agreement is X25519 (not P-256/ECDH), the hash is SHA3-256
  (not SHA-256), and the cipher is ChaCha20-Poly1305 (not AES-256-GCM).~~
  **Fixed** — `CryptoService.kt`'s KDoc now correctly reads "DM Crypto: X25519
  key agreement -> SHA3-256 -> ChaCha20-Poly1305".
- ~~**Earlier README sentence** repeated the AES-256-GCM error while the Tech
  Stack table correctly said ChaCha20-Poly1305 — the two sections of the
  README contradicted each other.~~ **Fixed** — both now consistently say
  ChaCha20-Poly1305; see [TECHNICAL_REFERENCE.md §14](TECHNICAL_REFERENCE.md#14-known-discrepancies-between-documentation-and-code)
  items 1–2 for the full history.
- ~~**`PACKET_SCHEMA.md`** documents `clearnet_thumbnail_url` as absent from
  the `POST` payload table, but `Packets.kt`'s `PostPayload` already includes
  it.~~ **Fixed** — `PACKET_SCHEMA.md`'s POST table now includes both
  `clearnet_thumbnail_url` and `author_avatar_b64` (which was also missing).

---

## 12. Summary Checklist

Quick-reference list of items from this document, roughly ordered by
implementation effort vs. value:

- [x] Fix doc/code mismatches in `CryptoService.kt` KDoc and README (§11) — trivial, do first
- [x] Add `clearnet_thumbnail_url` to `PACKET_SCHEMA.md`'s POST table (§11)
- [x] `ANNOUNCE_PEER` packet + online/offline presence indicator (§4)
- [x] `COMMENT_REACTION` packets + UI (§1, §2)
- [x] Split `votes` vs `reactions` data model per gChat's `PostSchema` (§2)
- [x] "Opt-in Transparency" override for soft-blocked content (§2)
- [x] Relay state TTL/cleanup in `GossipService.RelayState` (§6)
- [ ] True zero-copy chunk-forwarding for `MEDIA_RELAY_REQUEST` relays (§6) —
      **correction**: this is still *not* implemented as of this revision.
      `MeshPacketHandler.handleMediaChunk` delegates straight to
      `MediaManager.handleMediaChunk` (the "download for myself" path) for
      every relay node, not a separate pass-through proxy. A relaying node
      today downloads the whole file rather than streaming it through. This
      item was previously (incorrectly) checked off — verified against
      current code while merging in `ADDENDUM.md`'s findings.
- [x] `INVENTORY_SYNC_REQUEST`/`RESPONSE` (hash-based sync) to replace
      timestamp-based `SYNC_REQUEST`/`RESPONSE` (§1)
- [x] `EDIT_POST` / `DELETE_POST` packets (§1)
- [x] AIMD congestion control for media chunk downloads (§7)
- [x] `CHAT_REACTION` for DM engagement (§1)
- [x] `IDENTITY_UPDATE` packet for profile-change propagation (§1)
- [ ] `FOLLOW`/`UNFOLLOW` asymmetric relationship model (§1)
- [x] `USER_EXIT` graceful-shutdown broadcast on logout (§5)
- [ ] Group chats: `Group` entity + `GROUP_*` packets + multi-recipient
      encryption fan-out (§3)
- [ ] User-selectable theme palettes (low priority, §8)
- [ ] Document NoSlop's potential future role as a HAI-Net hub
      "frontend client" (§9) — architecture discussion, not code
- [ ] Spot-check and prune dead `BuiltInSource` feed URLs — **done** for the
      sources known at the time (see §13.1 below); keep watching for new dead
      sources as an ongoing maintenance task, not a one-time fix
- [ ] Confirm `Dns.SYSTEM` fallback exists in `HttpClientProvider.clearnetClient`'s
      DoH chain (§13.1) — not yet re-verified

---

## 13. Findings From a Test-Run Log Cross-Referenced Against Code

*(Merged in from the now-removed `docs/ADDENDUM.md`, 2026-06-13.)*

A captured `logcat` excerpt (roughly `00:18:27`–`00:34:04`) was cross-checked
against the codebase at the time. Three NoSlop-tagged issue classes were
found; all three turned out to trace back to one underlying cause.

### 13.1 DNS failures: `selfhostedhero.com`, `feeds.apnews.com`, `rssfeeds.webmd.com`

All three hosts were real `BuiltInSource` entries in `SourceLibrary.kt`. The
log showed `NXDOMAIN` (not a TLS/cert error), meaning even the corrected DoH
chain (Cloudflare → Google, see `HttpClientProvider`) returned no records.
Investigation outcome per source:

- **`selfhostedhero.com`** — a typo. The real site is `selfhosthero.com` (no
  "ed"). This source has since been **removed from `SourceLibrary.kt`**
  entirely (current code has neither the typo'd nor the corrected domain —
  it was pruned rather than renamed, which still resolves the dead-source
  problem).
- **`feeds.apnews.com`** — not a typo; Associated Press discontinued this RSS
  endpoint entirely. The `"ap-top"` source has been **removed**, with a
  comment in `SourceLibrary.kt` explaining why.
- **`rssfeeds.webmd.com`** — appeared to be a transient failure at the time,
  but has since been **replaced** with a Medical Xpress general-health feed
  in `SourceLibrary.kt` (a comment there documents the retirement).

### 13.2 XML parse error on `selfhostedhero.com/rss`

Traced to `FeedParser` choking on a cached/stale response body at a fixed
byte offset — consistent with explanation 13.1's dead-source theory rather
than a parser bug. `fetchAndParse()` now passes `feedUrl` through to
`parseStream()` so future parse-error logs identify which feed failed
(previously only an internal `sourceId` was available).

### 13.3 NASA APOD request timeout

`NasaApiClient.fetchAPOD()` hitting `api.nasa.gov` with the public `DEMO_KEY`
(shared globally, rate-limited, sometimes slow) — informational/external,
not a NoSlop bug. Wrapped in try/catch, fails gracefully. A personal NASA API
key in Settings → API Keys makes this more reliable.

**Overall assessment**: all three issue classes traced back to either dead
upstream feed endpoints or DNS/network conditions in the test environment,
not independent NoSlop bugs.

---

## 14. HAI-Net Ecosystem Notes Not Yet Reflected Elsewhere

*(Also merged in from `docs/ADDENDUM.md`.)*

### 14.1 HAI-Net's Admin AI / Persona layer

HAI-Net's `hainet-core/src/admin_bridge.rs` bridges a Tauri/web frontend
(`hainet-portal`) to an `AdminAgent` — a local LLM-backed assistant with its
own chat IPC schema, session history, STT/TTS handlers, and MCP tool access.
The HAI-Net "Local Hub" is explicitly intended as both the identity/data
store *and* local LLM host for a user — the same role NoSlop's
`IdentityRepository` plays standalone on-device today, plus a much larger
compute/AI layer NoSlop has no equivalent of.

**Relevance to NoSlop's docs**: [TECHNICAL_REFERENCE.md §13](TECHNICAL_REFERENCE.md#13-future-architecture-hubs--hai-net-hub-client)
and [HUB_INTEGRATION_PLAN.md](HUB_INTEGRATION_PLAN.md) already sketch NoSlop
as a future Hub client. The addendum here is that such a Hub, per HAI-Net's
own architecture, would also run an `AdminBridge`-style local AI assistant —
meaning the currently-stub `ui/HaiNetTab.kt` could eventually become a thin
client for that chat IPC schema, not just a mesh-peer list. Forward-looking
only, not a current gap.

### 14.2 HAI-Net `hainet-vault` (constitutional/governance docs)

HAI-Net's "Constitutional Framework" lives in `hainet-vault/`
(`CONSTITUTION.md`/`GOVERNANCE.md`/`DECLARATION.md`). NoSlop has no
equivalent and arguably doesn't need one as a standalone app, but if it's
ever positioned as "the mobile client for HAI-Net," its README/onboarding
copy (which already uses similar privacy-by-design language) could reference
`hainet-vault/DECLARATION.md` as a shared values document. Flagged for
awareness only.

### 14.3 `hainet-social`'s module boundaries vs. NoSlop's

`hainet-social/src/lib.rs` declares `firewall`, `dedup`, and `feed` as
separate Rust modules. NoSlop's equivalents (`GossipService`'s firewall
checks, its `processedPacketIds` dedup set, and the Room-backed feed tables)
are functionally present but live inline inside `GossipService.kt`/
`NoSlopRepository.kt` rather than as separate units. This is a code-
organization difference between a Rust crate and a Kotlin object/class, not
a feature gap — noted here only for anyone porting code between the two
projects.

---

**Related docs**: [PROJECT_STATUS.md](PROJECT_STATUS.md) for the
milestone-by-milestone log of what's shipped · [WIRE_PROTOCOL_REFERENCE.md](WIRE_PROTOCOL_REFERENCE.md)
and [TECHNICAL_REFERENCE.md](TECHNICAL_REFERENCE.md) for current
implementation detail of the items marked done above ·
[HUB_INTEGRATION_PLAN.md](HUB_INTEGRATION_PLAN.md) for the concrete plan
behind §14.1's forward-looking Hub/AI notes.
