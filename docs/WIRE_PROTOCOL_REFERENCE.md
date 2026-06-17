# NoSlop — Mesh Wire Protocol Reference (Current State, 2026-06-14)

**Scope**: This document is a from-scratch, code-derived technical reference
for NoSlop's mesh wire protocol as it exists today in `Packets.kt` and
`MeshPacketHandler.kt`/`GossipService.kt`. It supersedes
`docs/TECHNICAL_REFERENCE.md` §4.2, §4.5, and §5.2 (packet dispatch table,
sync protocol, and payload type table respectively), which describe an
earlier, 11-handler/14-payload version of the protocol. It should be read
alongside `docs/PACKET_SCHEMA.md` (field-naming conventions) and
`docs/GAP_ANALYSIS.md` §1, §6, §7 (the protocol-parity backlog that this
document's content was, in part, written to close out).

---

## 1. Envelope

Unchanged from TECHNICAL_REFERENCE.md §5.1:

```kotlin
data class NetworkPacket(
    val id: String? = null,
    val hops: Int? = null,
    @SerializedName("sender_id") val senderId: String,
    @SerializedName("target_user_id") val targetUserId: String? = null,
    var signature: String? = null,
    val type: String,
    val payload: JsonElement? = null
)
```

Wire format: newline-delimited JSON over the SOCKS5/Tor mesh transport
(`MeshTransport`, port 9999). One `NetworkPacket` per line.

---

## 2. Full Packet Type Catalog (19 types)

`Packets.kt`'s `type` field documents 19 string values. Each is listed below
with its payload class, signed-payload format (where applicable), and
handler in `MeshPacketHandler.handlePacket`.

| # | `type` | Payload class | Signed string format | Handler | Persistence |
|---|---|---|---|---|---|
| 1 | `POST` | `PostPayload` | `id\|authorId\|content\|timestamp` | `handlePost` | `postDao.insertPost`; triggers media auto-download |
| 2 | `MESSAGE` | `EncryptedPayload` | n/a (encryption is the auth) | `handleDirectMessage` | `messageDao.insertMessage`; auto-download if media |
| 3 | `CONNECTION_REQUEST` | `PeerHandshakePayload` | carried but **not verified** on receipt | `handleConnectionRequest` | inserts untrusted `Peer`, sets `_incomingRequestFlow` |
| 4 | `USER_HANDSHAKE` | `PeerHandshakePayload` | carried but **not verified** on receipt | `handleUserHandshake` | upserts `Peer` with `isTrusted = true` |
| 5 | `SYNC_REQUEST` | `SyncRequestPayload` | n/a | `handleSyncRequest` | none — replies `SYNC_RESPONSE` |
| 6 | `SYNC_RESPONSE` | `SyncResponsePayload` | per-post `id\|authorId\|content\|timestamp` | `handleSyncResponse` | `postDao.insertPost` per valid post; also processes `comments`/`reactions` arrays |
| 7 | `INVENTORY_SYNC_REQUEST` | `InventorySyncRequestPayload` | n/a | `handleInventorySyncRequest` | none — replies with a `SYNC_RESPONSE` containing only missing/updated posts + their comments/reactions |
| 8 | `COMMENT` | `CommentPayload` | `postId\|commentId\|content\|timestamp` | `handleComment` | `commentDao.insertComment` |
| 9 | `REACTION` | `ReactionPayload` | `postId\|reactionType\|authorId\|timestamp` | `handleReaction` | `reactionDao.insertReaction`/`deleteReactionById` per `action` |
| 10 | `CHAT_REACTION` | `ChatReactionPayload` | `messageId\|reactionType\|authorId\|timestamp` | `handleChatReaction` | reaction table keyed off `chat_messages.id`, add/remove per `action` |
| 11 | `COMMENT_REACTION` | `CommentReactionPayload` | `commentId\|reactionType\|authorId\|timestamp` | `handleCommentReaction` | `commentReactionDao.insertReaction`/`deleteReactionById` |
| 12 | `VOTE` | `VotePayload` | `postId\|voteType\|authorId\|timestamp` | `handleVote` | `voteDao.insertVote`/`deleteVoteById` per `action` |
| 13 | `COMMENT_VOTE` | `CommentVotePayload` | `commentId\|voteType\|authorId\|timestamp` | `handleCommentVote` | `commentVoteDao.insertVote`/`deleteVoteById` |
| 14 | `ANNOUNCE_PEER` | (peer presence payload) | signed; rejected with `"Rejected ANNOUNCE_PEER: Signature verification failed"` on bad sig | `handleAnnouncePeer` | `peerDao.insertPeer(peer.copy(isOnline = true, lastSeenAt = now))` |
| 15 | `IDENTITY_UPDATE` | `IdentityUpdatePayload` | `userId\|displayName\|timestamp` (signed) | `handleIdentityUpdate` | updates `Peer.handle`/display fields for `userId` |
| 16 | `USER_EXIT` | `UserExitPayload` | `userId\|timestamp` (signed) | `handleUserExit` | `peerDao.insertPeer(peer.copy(isOnline = false, lastSeenAt = now))` |
| 17 | `EDIT_POST` | (edit payload: `postId`, new `content`, `timestamp`, `signature`) | author-checked against `existingPost.authorPublicKeyB64` | `handleEditPost` | updates `mesh_posts.content` if `!isOrphaned && timestamp >= existingPost.timestamp` |
| 18 | `DELETE_POST` | (delete payload: `postId`, `timestamp`, `signature`) | author-checked against `existingPost.authorPublicKeyB64` | `handleDeletePost` | marks `mesh_posts.isOrphaned = true` if `!isOrphaned && timestamp >= existingPost.timestamp` |
| 19 | `MEDIA_REQUEST` / `MEDIA_CHUNK` / `MEDIA_RELAY_REQUEST` / `MEDIA_RECOVERY_FOUND` / `MEDIA_PENDING` / `MEDIA_TRANSFER_ACK` | see §5 | none | delegated to `MediaManager`/`GossipService` | see §5 |

Notes:

- Rows 17–18 (`EDIT_POST`/`DELETE_POST`) share an identical guard pattern:
  reject if the sender's public key doesn't match the existing post's author,
  and reject if the existing post is already `isOrphaned` or the incoming
  `timestamp` is older than the stored one (last-write-wins per author,
  tombstones are sticky — once `isOrphaned = true`, a later `EDIT_POST` for
  the same `postId` cannot resurrect it because the `!existingPost.isOrphaned`
  guard fails).
- Row 14 (`ANNOUNCE_PEER`) is the only *unsolicited, repeatedly broadcast*
  packet type that is nonetheless signature-checked — every other "heartbeat
  style" packet (`SYNC_REQUEST`, `MEDIA_REQUEST`, etc.) has no signature
  field at all. This makes `ANNOUNCE_PEER` forgeable-detection the strictest
  of the presence-related packets.
- Rows 3–4 (`CONNECTION_REQUEST`/`USER_HANDSHAKE`) remain the one documented
  verification gap: `PeerHandshakePayload.signature` is populated by the
  sender (`NoSlopRepository.sendConnectionRequest` /
  `acceptConnectionRequest` both call `CryptoService.sign`) but
  `MeshPacketHandler` does not verify it on receipt. This is unchanged from
  TECHNICAL_REFERENCE.md §4.4 and is still tracked in
  `docs/PROJECT_STATUS.md`'s "Handshake Signature Verification Gap" item.

---

## 3. Inventory-Based Sync (`INVENTORY_SYNC_REQUEST`)

This replaces the pure timestamp-based `SYNC_REQUEST`/`SYNC_RESPONSE` flow
described in TECHNICAL_REFERENCE.md §4.5 as the **primary** reconciliation
mechanism, while `SYNC_REQUEST`/`SYNC_RESPONSE` remain in the protocol and are
still used as the *reply* vehicle.

### 3.1 Request

```kotlin
data class InventorySyncRequestPayload(
    val inventory: List<InventoryItem>   // InventoryItem = { id: String, hash: String }
)
```

Sent (instead of, or alongside, `SYNC_REQUEST`) by a peer that wants to
reconcile its local post set against a remote peer's. `inventory` is the
requester's own list of `{postId, contentHash}` pairs for posts it already
has.

### 3.2 Server-Side Diff (`handleInventorySyncRequest`)

1. Build `peerInventory: Map<String, String>` from
   `syncPay.inventory.associate { it.id to it.hash }` — i.e. the *requester's*
   known `{id -> hash}` map (despite the name "peerInventory" referring to
   data about the peer's, i.e. requester's, holdings from the receiver's
   point of view).
2. Compare against the receiver's own `postDao` contents: any local post
   whose `id` is **absent** from `peerInventory`, or present but with a
   **different hash**, is considered "missing or updated" from the
   requester's perspective.
3. Collect those posts into `missingOrUpdatedPosts`.
4. Additionally collect `commentSyncList` and `reactionSyncList` — comments
   and reactions attached to those posts (and possibly to posts the
   requester already has, to backfill engagement data — see §3.3).
5. Wrap everything in a `SYNC_RESPONSE` (`SyncResponsePayload`) and send it
   **directly** (hops=1, not gossiped) to the requester's onion address.
6. Log line: `"INVENTORY_SYNC_REQUEST handled — sent N missing posts, M
   comments, K reactions to <sender prefix>"`.

### 3.3 `SyncResponsePayload` (extended)

```kotlin
data class SyncResponsePayload(
    val posts: List<PostPayload>,
    val comments: List<CommentSyncEntry>? = null,    // milestone 159/172
    val reactions: List<ReactionSyncEntry>? = null    // milestone 159/172
)
```

This is a **breaking extension** of the `SyncResponsePayload` documented in
TECHNICAL_REFERENCE.md §5.2 (`posts: List<PostPayload>` only). Both the
original `handleSyncRequest`/`handleSyncResponse` (timestamp-based) path and
the new inventory-diff path produce/consume this same extended type — a
`SYNC_RESPONSE` packet's `comments`/`reactions` fields may be `null` (older
timestamp-based replies) or populated (inventory-diff replies).

### 3.4 `handleSyncResponse` Verification

Unchanged in spirit from TECHNICAL_REFERENCE.md §4.5: each post in `posts` is
independently signature-verified
(`id|authorId|content|timestamp`) before `postDao.insertPost`; invalid
signatures are dropped per-post with a warning log. The new `comments` and
`reactions` arrays are similarly verified using their respective signed
formats (`postId|commentId|content|timestamp` and
`postId|reactionType|authorId|timestamp`) before insertion via
`commentDao`/`reactionDao`.

### 3.5 No Separate Response Type

There is **no** `INVENTORY_SYNC_RESPONSE` string anywhere in `Packets.kt`.
Both sync request types (`SYNC_REQUEST` and `INVENTORY_SYNC_REQUEST`) reply
using the single `SYNC_RESPONSE` type, distinguished only by whether
`comments`/`reactions` are populated. Any future protocol documentation or
interop work against `hainet-social` should treat `SYNC_RESPONSE` as the
unified reply envelope for both reconciliation strategies.

---

## 4. Presence Protocol (`ANNOUNCE_PEER` / `USER_EXIT`)

### 4.1 `ANNOUNCE_PEER`

- Broadcast periodically (per `docs/PROJECT_STATUS.md` milestone 156, every
  60 seconds) to trusted peers, carrying the sender's `onionAddress` and
  alias/handle.
- **Signed** — `handleAnnouncePeer` verifies the signature and, on failure,
  logs `"Rejected ANNOUNCE_PEER: Signature verification failed"` and drops
  the packet (no persistence change).
- On success: `peerDao.insertPeer(peer.copy(isOnline = true, lastSeenAt =
  System.currentTimeMillis()))`.
- **Staleness**: per PROJECT_STATUS.md milestone 156, peers that haven't sent
  an `ANNOUNCE_PEER` (or any other packet updating `lastSeenAt`) within 3
  minutes are treated as offline by the UI layer (`PeerItem.kt`'s green
  indicator), even though `Peer.isOnline` in Room is not actively flipped
  back to `false` by a timeout sweep — the UI derives "online" from
  `isOnline == true && (now - lastSeenAt) < 3min` rather than relying solely
  on the stored boolean. (If a future change moves this logic into Room via a
  background sweep, this section should be updated.)

### 4.2 `USER_EXIT`

- Broadcast on `logout()` and from `NoSlopForegroundService.onDestroy()`.
- Payload: `UserExitPayload { userId, timestamp, signature }`, signed string
  `userId|timestamp`.
- `handleUserExit`: on receipt, immediately sets
  `peerDao.insertPeer(peer.copy(isOnline = false, lastSeenAt = now))` for the
  sending peer — i.e., this is the only presence-related packet that can
  drive `isOnline` to `false` directly (mirroring gChat's `USER_EXIT`
  mitigation for "Ghost Peers", though without the `USER_EXIT_ACK`/30-second
  wait gChat's desktop implementation uses — NoSlop fires `USER_EXIT` as a
  best-effort broadcast and proceeds with teardown immediately, which is
  appropriate for Android's more abrupt process lifecycle).

---

## 5. Media Packet Family (6 types)

| Type | Payload | Role |
|---|---|---|
| `MEDIA_REQUEST` | `MediaRequestPayload { mediaId, chunkIndex, chunkSize, accessKey?, hlsFile? }` | Requests a specific chunk of a media item by index |
| `MEDIA_CHUNK` | `MediaChunkPayload { mediaId, chunkIndex, totalChunks, data (Base64) }` | Carries one chunk's bytes |
| `MEDIA_RELAY_REQUEST` | `MediaRelayRequestPayload { mediaId, originNode?, ownerId?, accessKey?, metadata? }` | Broadcast to trusted peers when the direct author is unreachable/unknown |
| `MEDIA_RECOVERY_FOUND` | `MediaRecoveryFoundPayload { mediaId }` | Sent back along the relay chain once the media's source node is located |
| `MEDIA_PENDING` | `MediaPendingPayload { mediaId, chunkIndex }` | (signals an in-flight/awaited chunk — used by the AIMD inflight-tracking state in `MediaManager`, see §6) |
| `MEDIA_TRANSFER_ACK` | `MediaTransferAckPayload { mediaId }` | Transfer-completion acknowledgement |

`MediaManager.kt`'s AIMD controller (per-download state: `windowSize` init
4.0, `ssthresh` init 128.0, `inflight: Set<Int>` of chunk indices, `rttEma`):

- **Slow start**: while `windowSize < ssthresh`, each received chunk
  increments `windowSize += 1`.
- **Congestion avoidance**: once `windowSize >= ssthresh`, each received
  chunk increments `windowSize += 1 / windowSize` (sub-linear growth).
- **Multiplicative decrease**: on a chunk timeout, `ssthresh = max(2.0,
  windowSize * 0.5)` and `windowSize` resets to `1.0` (classic Reno-style
  AIMD), matching the algorithm documented for `hainet-social/src/congestion.rs`.
- **Window cap**: `windowSize` is clamped to a maximum of `128.0`.
- The downloader issues new `MEDIA_REQUEST`s for additional chunks only while
  `dl.inflight.size < dl.windowSize.toInt()`, i.e. `windowSize` (rounded down)
  is the live concurrency cap, replacing the previously flat
  `MAX_CONCURRENCY = 4` (that constant remains as the AIMD initial value,
  `windowSize = 4.0`, but no longer hard-caps concurrency at steady state).

---

## 6. Cryptographic Signed-String Formats — Consolidated Table

For quick reference, every signed pipe-delimited string format currently in
use across the 19 packet types (per §3.4's note that "the verifier must know
the precise format per packet type"):

| Packet type | Signed string |
|---|---|
| `POST` / `SYNC_RESPONSE.posts[i]` | `id\|authorPublicKeyB64\|content\|timestamp` |
| `COMMENT` / `SYNC_RESPONSE.comments[i]` | `postId\|commentId\|content\|timestamp` |
| `REACTION` / `SYNC_RESPONSE.reactions[i]` | `postId\|reactionType\|authorPublicKeyB64\|timestamp` |
| `CHAT_REACTION` | `messageId\|reactionType\|authorPublicKeyB64\|timestamp` |
| `COMMENT_REACTION` | `commentId\|reactionType\|authorPublicKeyB64\|timestamp` |
| `VOTE` | `postId\|voteType\|authorPublicKeyB64\|timestamp` |
| `COMMENT_VOTE` | `commentId\|voteType\|authorPublicKeyB64\|timestamp` |
| `IDENTITY_UPDATE` | `userId\|displayName\|timestamp` |
| `USER_EXIT` | `userId\|timestamp` |
| `EDIT_POST` | `postId\|content\|timestamp` (author-checked against stored post's `authorPublicKeyB64`, not re-derived from the signed string alone) |
| `DELETE_POST` | `postId\|timestamp` (author-checked against stored post's `authorPublicKeyB64`) |
| `ANNOUNCE_PEER` | sender's identity fields + timestamp (exact field order not confirmed from `Packets.kt` alone; verify against `NoSlopRepository`'s broadcast call site if reimplementing) |
| `CONNECTION_REQUEST` / `USER_HANDSHAKE` | computed and sent (`PeerHandshakePayload.signature`) but **never verified on receipt** — see §2 row 3–4 note |

All signature operations use Ed25519 (`CryptoService.sign`/`verify`), Base64
no-wrap encoding, over the UTF-8 bytes of the literal pipe-delimited string —
unchanged from TECHNICAL_REFERENCE.md §3.4.

---

## 7. Things Intentionally Not Covered Here

The following remain accurately described by the existing
`docs/TECHNICAL_REFERENCE.md` and are **not** duplicated in this document:

- §1–3 (system overview, package layout, identity/crypto derivation —
  tripcode, onion address, BIP39 mnemonic, DM encryption) — all still
  accurate.
- §6.1 (AIMD media congestion control) and §6.2–6.5 (media storage layout,
  auto-download policy, `MediaProxyService`, thumbnail pipeline) — all now
  accurate (§6.1 has since been updated to document the AIMD controller
  described in §5 above, rather than contradicting it).
- §7 (clearnet aggregator: HTTP client separation, source library, API client
  roster, feed sync pipeline, RSS parsing) — still accurate.
- §8 (clearnet-to-mesh bridge: deterministic anchor IDs) — still accurate;
  the `REACTION` packet type and anchor-creation flow described there are
  consistent with §2 row 9 of this document.
- §9 (Tor integration) — still accurate.
- §10 (Room schema v23) — still accurate; the new packet types in this
  document map onto existing tables (`mesh_votes`, `comment_votes`,
  `mesh_reactions`, `peers.isOnline`, `mesh_posts.isOrphaned`) that §10
  already lists.
- §11–14 (background work, build configuration, future HUB architecture,
  known discrepancies) — still accurate.

---

**Related docs**: [PACKET_SCHEMA.md](PACKET_SCHEMA.md) for plain JSON field
tables of the payloads referenced here · [TECHNICAL_REFERENCE.md](TECHNICAL_REFERENCE.md)
for everything outside the wire protocol (identity, Tor, clearnet aggregator,
build config) · [GAP_ANALYSIS.md](GAP_ANALYSIS.md) for the backlog this
protocol surface was built against.
