# NoSlop — Mesh Wire Protocol Reference (Current State, 2026-06-19)

**Scope**: This is the single, complete reference for NoSlop's HAI-Net mesh
wire protocol — envelope format, the full packet-type catalog, every
payload's JSON field shape, signed-string formats, and the sync/presence/
media sub-protocols — derived directly from `Packets.kt`,
`MeshPacketHandler.kt`, the seven `*PacketHandler.kt` classes, and
`GossipService.kt`. It supersedes `docs/TECHNICAL_REFERENCE.md` §4.2, §4.4,
§4.5, and §5.2 (packet dispatch table, sync protocol, and payload type
table), which described an earlier, smaller version of the protocol.
`docs/TECHNICAL_REFERENCE.md` remains authoritative for everything **outside**
the wire protocol (identity/crypto derivation, Tor, clearnet aggregator,
media storage/auto-download policy, build config).

This document merges what used to be two separate files
(`WIRE_PROTOCOL_REFERENCE.md` and `PACKET_SCHEMA.md`) — the old
`PACKET_SCHEMA.md` covered plain JSON field tables for 9 of the protocol's 24
payload types; that coverage is now folded into §2 below so there's one place
to look up any packet's shape.

---

## 1. Envelope

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

| Field | Type | Required | Description |
|---|---|---|---|
| `id` | String | No | Unique packet ID (UUID) |
| `hops` | Integer | No | Hop count/TTL for flood routing; defaults to 6 if absent (`GossipService`) |
| `sender_id` | String | Yes | Base64 Ed25519 public key of the sender |
| `target_user_id` | String | No | Target recipient for DMs/Handshakes/direct replies |
| `signature` | String | No | Ed25519 signature (only meaningful for packet types that use it — see §6) |
| `type` | String | Yes | Packet type string, e.g. `"POST"`, `"MESSAGE"` |
| `payload` | Object | No | The specific payload for the type |

Wire format: newline-delimited JSON over the SOCKS5/Tor mesh transport
(`MeshTransport`, port 9999, loopback-bound — see TECHNICAL_REFERENCE.md
§4.1). One `NetworkPacket` per line, serialized via a fresh `Gson()` instance
each call (`toJson()`/`fromJson()`).

13 typed payload accessor methods on `NetworkPacket` (`getPostPayload()`,
`getMessagePayload()`, etc.) each guard on `type == "<TYPE>"` before
attempting `Gson().fromJson(payload, X::class.java)`, returning `null` on
type mismatch or `payload == null`.

All JSON wire fields use `snake_case` via `@SerializedName`, while Kotlin
properties use `camelCase`.

---

## 2. Full Packet Type Catalog (24 distinct type strings)

`Packets.kt`'s `type` field KDoc comment lists 19 non-media type strings;
together with the 6 `MEDIA_*` types (§5) and `CONNECTION_REJECTED`, that's
**24 distinct `type` values** total in active use, plus `ANNOUNCE_PEER`'s
payload class which sits alongside them. `MeshPacketHandler.handlePacket`'s
`when` block dispatches **21 cases** (not all 24 — `MEDIA_RELAY_REQUEST` and
`MEDIA_RECOVERY_FOUND`'s relay-forwarding side is intercepted earlier inside
`GossipService.processIncoming` itself, before reaching the dispatcher; see
§5 and §6 of TECHNICAL_REFERENCE.md §4.2 for that routing logic).

**Architecture note**: the per-type handler logic does **not** live inside
one large `MeshPacketHandler.kt` file. As of a "Phase 0, Stage 0.3"
refactor, `MeshPacketHandler` is a thin dispatcher that owns only the
identity check + `GossipService.processIncoming` gate, then delegates to one
of seven single-responsibility handler classes (each constructed with the
same `(repo, db)` pair, method bodies moved verbatim per ADR-004):

| Handler class | Packet types it owns |
|---|---|
| `SyncPacketHandler` | `SYNC_REQUEST`, `INVENTORY_SYNC_REQUEST`, `SYNC_RESPONSE` |
| `PostPacketHandler` | `POST`, `EDIT_POST`, `DELETE_POST` |
| `CommentPacketHandler` | `COMMENT` |
| `ReactionPacketHandler` | `REACTION`, `VOTE`, `COMMENT_VOTE`, `CHAT_REACTION`, `COMMENT_REACTION` |
| `DmPacketHandler` | `MESSAGE` |
| `HandshakePacketHandler` | `CONNECTION_REQUEST`, `USER_HANDSHAKE`, `CONNECTION_REJECTED`, `ANNOUNCE_PEER`, `IDENTITY_UPDATE`, `USER_EXIT` |
| `MediaPacketHandler` | `MEDIA_REQUEST`, `MEDIA_CHUNK`, `MEDIA_RECOVERY_FOUND` |

| # | `type` | Payload class | Signed string format | Handler (class.method) | Persistence |
|---|---|---|---|---|---|
| 1 | `POST` | `PostPayload` | `id\|authorId\|content\|timestamp` (+`\|authorAvatarB64` if set) | `PostPacketHandler.handlePost` | `postDao.insertPost`; triggers media auto-download |
| 2 | `MESSAGE` | `EncryptedPayload` | n/a (encryption is the auth) | `DmPacketHandler.handleDirectMessage` | `messageDao.insertMessage`; auto-download if media |
| 3 | `CONNECTION_REQUEST` | `PeerHandshakePayload` | carried but **not verified** on receipt | `HandshakePacketHandler.handleConnectionRequest` | inserts untrusted `Peer`, sets `_incomingRequestFlow` |
| 4 | `USER_HANDSHAKE` | `PeerHandshakePayload` | carried but **not verified** on receipt | `HandshakePacketHandler.handleUserHandshake` | upserts `Peer` with `isTrusted = true` |
| 5 | `SYNC_REQUEST` | `SyncRequestPayload` | n/a | `SyncPacketHandler.handleSyncRequest` | none — replies `SYNC_RESPONSE` |
| 6 | `SYNC_RESPONSE` | `SyncResponsePayload` | per-post `id\|authorId\|content\|timestamp` | `SyncPacketHandler.handleSyncResponse` | `postDao.insertPost` per valid post; also processes `comments`/`reactions` arrays |
| 7 | `INVENTORY_SYNC_REQUEST` | `InventorySyncRequestPayload` | n/a | `SyncPacketHandler.handleInventorySyncRequest` | none — replies with a `SYNC_RESPONSE` containing only missing/updated posts + their comments/reactions |
| 8 | `COMMENT` | `CommentPayload` | `postId\|commentId\|content\|timestamp` | `CommentPacketHandler.handleComment` | `commentDao.insertComment` |
| 9 | `REACTION` | `ReactionPayload` | `postId\|reactionType\|authorId\|timestamp` | `ReactionPacketHandler.handleReaction` | `reactionDao.insertReaction`/`deleteReactionById` per `action` |
| 10 | `CHAT_REACTION` | `ChatReactionPayload` | `messageId\|reactionType\|authorId\|timestamp` | `ReactionPacketHandler.handleChatReaction` | reaction table keyed off `chat_messages.id`, add/remove per `action` |
| 11 | `COMMENT_REACTION` | `CommentReactionPayload` | `commentId\|reactionType\|authorId\|timestamp` | `ReactionPacketHandler.handleCommentReaction` | `commentReactionDao.insertReaction`/`deleteReactionById` |
| 12 | `VOTE` | `VotePayload` | `postId\|voteType\|authorId\|timestamp` | `ReactionPacketHandler.handleVote` | `voteDao.insertVote`/`deleteVoteById` per `action` |
| 13 | `COMMENT_VOTE` | `CommentVotePayload` | `commentId\|voteType\|authorId\|timestamp` | `ReactionPacketHandler.handleCommentVote` | `commentVoteDao.insertVote`/`deleteVoteById` |
| 14 | `ANNOUNCE_PEER` | `AnnouncePeerPayload` | `authorId\|timestamp` (signed) | `HandshakePacketHandler.handleAnnouncePeer` | `peerDao.insertPeer(peer.copy(isOnline = true, lastSeenAt = now))` |
| 15 | `IDENTITY_UPDATE` | `IdentityUpdatePayload` | `userId\|handle\|timestamp` (+`\|authorAvatarB64` if set) | `HandshakePacketHandler.handleIdentityUpdate` | updates `Peer.handle`/`Peer.authorAvatarB64` for `userId` |
| 16 | `USER_EXIT` | `UserExitPayload` | `userId\|timestamp` (signed) | `HandshakePacketHandler.handleUserExit` | `peerDao.insertPeer(peer.copy(isOnline = false, lastSeenAt = now))` |
| 17 | `EDIT_POST` | `EditPostPayload` | `postId\|authorId\|content\|timestamp` | `PostPacketHandler.handleEditPost` | updates `mesh_posts.content` if `!isOrphaned && timestamp >= existingPost.timestamp` |
| 18 | `DELETE_POST` | `DeletePostPayload` | `postId\|authorId\|timestamp` | `PostPacketHandler.handleDeletePost` | marks `mesh_posts.isOrphaned = true` if `!isOrphaned && timestamp >= existingPost.timestamp` |
| 19 | `MEDIA_REQUEST` / `MEDIA_CHUNK` / `MEDIA_RELAY_REQUEST` / `MEDIA_RECOVERY_FOUND` / `MEDIA_PENDING` / `MEDIA_TRANSFER_ACK` | see §5 | none | see §5 for routing (`GossipService` vs `MediaPacketHandler`/`MediaManager`) | see §5 |
| 20 | `CONNECTION_REJECTED` | `ConnectionRejectedPayload` | `fromUserId\|timestamp` (signed) | `HandshakePacketHandler.handleConnectionRejected` | Deletes the untrusted `Peer` locally and triggers a decline notification |

Notes:

- Rows 17–18 (`EDIT_POST`/`DELETE_POST`): the signed string includes
  `authorId`, and `CryptoService.verify` is called with `editPay.authorId`/
  `deletePay.authorId` as the verifying public key — this is a **separate**
  check from the subsequent `existingPost.authorPublicKeyB64 != editPay.authorId`
  guard (which rejects if the *stored* post's author doesn't match the
  payload's claimed author). Both guards must pass. Tombstones are sticky —
  once `isOrphaned = true`, a later `EDIT_POST` for the same `postId` cannot
  resurrect it because the `!existingPost.isOrphaned` guard fails.
- Row 14 (`ANNOUNCE_PEER`) is broadcast with `hops = 1` (not flood-gossiped
  like `POST`'s `hops = 6`) and is signed — every other "heartbeat style"
  packet (`SYNC_REQUEST`, `MEDIA_REQUEST`, etc.) has no signature field at
  all, making `ANNOUNCE_PEER` the only repeatedly-broadcast, low-TTL packet
  that is nonetheless signature-checked.
- Row 15 (`IDENTITY_UPDATE`): the payload's field is named `handle` (not
  `displayName`), and the signed string uses that same field name —
  `userId|handle|timestamp`.
- Rows 3–4 (`CONNECTION_REQUEST`/`USER_HANDSHAKE`) remain the one documented
  verification gap: `PeerHandshakePayload.signature` is populated by the
  sender (`NoSlopRepository.sendConnectionRequest`/`acceptConnectionRequest`
  both call `CryptoService.sign`) but `MeshPacketHandler` does not verify it
  on receipt. Still open — tracked in `docs/PROJECT_STATUS.md`'s "Handshake
  Signature Verification Gap" item.

---

## 3. Payload JSON Field Tables

Every payload class in `Packets.kt`, with wire (`snake_case`) field names.
`?` marks an optional/nullable field.

### POST
**Type:** `POST` · class `PostPayload`

| Field | Type | Description |
|---|---|---|
| `id` | String | Unique post ID |
| `author_id` | String | Author's public key (used as the verifying key) |
| `author_name` | String | Display name |
| `author_public_key` | String | Base64 Ed25519 identity key |
| `author_avatar_b64`? | String | Base64-encoded small avatar image, if set |
| `origin_node`? | String | Network node where post originated |
| `content` | String | Text content of the post |
| `timestamp` | Long | Epoch time in milliseconds |
| `privacy` | String | `"public"` (default), `"friends"`, or `"private"` |
| `hashtags`? | Array\<String\> | List of hashtags |
| `signature`? | String | Signature of the post payload |
| `media_id`? | String | ID of associated media |
| `media_metadata`? | Object (`MediaMetadata`, §5) | Media metadata object |
| `clearnet_url`? | String | Original URL if sharing a clearnet article |
| `clearnet_title`? | String | Original title if sharing a clearnet article |
| `clearnet_thumbnail_url`? | String | URL of the thumbnail for the clearnet article |

### MESSAGE (Secure Direct Messages)
**Type:** `MESSAGE` · class `EncryptedPayload`

| Field | Type | Description |
|---|---|---|
| `id` | String | Unique message ID |
| `nonce` | String | Initialization Vector/Nonce for encryption |
| `ciphertext` | String | Encrypted payload (Base64) |
| `group_id`? | String | Reserved, unused — group chats are not implemented (see GAP_ANALYSIS.md §3) |
| `timestamp`? | Long | Epoch timestamp |

If a DM carries media, the decrypted plaintext is a JSON object
`{"content": "<text>", "media": <MediaMetadata>}` rather than raw text;
`handleDirectMessage` attempts to parse it as JSON and falls back to raw
text if parsing fails.

### CONNECTION_REQUEST / USER_HANDSHAKE
**Type:** `CONNECTION_REQUEST` or `USER_HANDSHAKE` · class `PeerHandshakePayload` (shared, unified type per milestone 56)

| Field | Type | Description |
|---|---|---|
| `id` | String | Unique request ID |
| `from_user_id` | String | Sender's public key |
| `from_username` | String | Sender's handle |
| `from_display_name` | String | Sender's display name |
| `author_avatar_b64`? | String | Sender's avatar, if set |
| `from_home_node` | String | Sender's onion address |
| `from_encryption_public_key`? | String | Sender's X25519 public key |
| `timestamp` | Long | Epoch timestamp |
| `signature`? | String | Computed and sent, but **not verified on receipt** — see §2 row 3–4 |

### ANNOUNCE_PEER
**Type:** `ANNOUNCE_PEER` · class `AnnouncePeerPayload`

| Field | Type | Description |
|---|---|---|
| `author_id` | String | Sender's public key |
| `timestamp` | Long | Epoch timestamp |
| `signature` | String | Ed25519 signature over `authorId\|timestamp` |

### IDENTITY_UPDATE
**Type:** `IDENTITY_UPDATE` · class `IdentityUpdatePayload`

| Field | Type | Description |
|---|---|---|
| `user_id` | String | Subject's public key (verifying key) |
| `handle` | String | New handle/display name |
| `author_avatar_b64`? | String | New avatar, if changed |
| `timestamp` | Long | Epoch timestamp |
| `signature` | String | Signature over `userId\|handle\|timestamp` (+`\|authorAvatarB64` if present) |

### USER_EXIT
**Type:** `USER_EXIT` · class `UserExitPayload`

| Field | Type | Description |
|---|---|---|
| `user_id` | String | Exiting peer's public key |
| `timestamp` | Long | Epoch timestamp |
| `signature` | String | Signature over `userId\|timestamp` |

### CONNECTION_REJECTED
**Type:** `CONNECTION_REJECTED` · class `ConnectionRejectedPayload`

| Field | Type | Description |
|---|---|---|
| `from_user_id` | String | Public key of the peer who declined |
| `timestamp` | Long | Epoch timestamp |
| `signature` | String | Signature over `fromUserId\|timestamp` |

### EDIT_POST
**Type:** `EDIT_POST` · class `EditPostPayload`

| Field | Type | Description |
|---|---|---|
| `post_id` | String | ID of the post being edited |
| `author_id` | String | Claimed author's public key (verifying key; cross-checked against the stored post's author) |
| `content` | String | New content |
| `timestamp` | Long | Epoch timestamp (must be ≥ stored post's timestamp to apply) |
| `signature` | String | Signature over `postId\|authorId\|content\|timestamp` |

### DELETE_POST
**Type:** `DELETE_POST` · class `DeletePostPayload`

| Field | Type | Description |
|---|---|---|
| `post_id` | String | ID of the post being tombstoned |
| `author_id` | String | Claimed author's public key (verifying key) |
| `timestamp` | Long | Epoch timestamp |
| `signature` | String | Signature over `postId\|authorId\|timestamp` |

### COMMENT
**Type:** `COMMENT` · class `CommentPayload` (nests `CommentData`)

| Field | Type | Description |
|---|---|---|
| `post_id` | String | ID of the post being commented on |
| `comment` | Object (`CommentData`) | `id, author_id, author_name, author_avatar_b64?, content, timestamp, signature` |
| `parent_comment_id`? | String | For threaded replies |

`CommentData.signature` covers `postId|commentId|content|timestamp`.

### REACTION / CHAT_REACTION / COMMENT_REACTION
**Types:** `REACTION`, `CHAT_REACTION`, `COMMENT_REACTION` · classes `ReactionPayload`, `ChatReactionPayload`, `CommentReactionPayload` (identical shape, different target-ID field name)

| Field | Type | Description |
|---|---|---|
| `post_id` / `message_id` / `comment_id` | String | ID of the target being reacted to (field name varies by type) |
| `reaction_type` | String | e.g. `"like"`, `"upvote"`, `"downvote"`, `"angry"` |
| `author_id` | String | Public key of the reactor |
| `timestamp` | Long | Epoch timestamp |
| `signature` | String | Ed25519 signature (see §2 for exact per-type string) |
| `action` | String | `"add"` (default) or `"remove"` — toggles the reaction |

### VOTE / COMMENT_VOTE
**Types:** `VOTE`, `COMMENT_VOTE` · classes `VotePayload`, `CommentVotePayload`

| Field | Type | Description |
|---|---|---|
| `post_id` / `comment_id` | String | ID of the target |
| `vote_type` | String | `"upvote"` or `"downvote"` |
| `author_id` | String | Public key of the voter |
| `timestamp` | Long | Epoch timestamp |
| `signature` | String | Ed25519 signature |
| `action` | String | `"add"` (default) or `"remove"` |

### SYNC_REQUEST
**Type:** `SYNC_REQUEST` · class `SyncRequestPayload`

| Field | Type | Description |
|---|---|---|
| `since` | Long | Epoch timestamp limit for historical sync |

### INVENTORY_SYNC_REQUEST
**Type:** `INVENTORY_SYNC_REQUEST` · class `InventorySyncRequestPayload`

| Field | Type | Description |
|---|---|---|
| `inventory` | Array\<`InventoryItem`\> | `{id, hash}` pairs — the requester's own known post IDs and content hashes |

### SYNC_RESPONSE
**Type:** `SYNC_RESPONSE` · class `SyncResponsePayload`

| Field | Type | Description |
|---|---|---|
| `posts` | Array\<`PostPayload`\> | Posts satisfying the sync bounds |
| `comments`? | Array\<`CommentSyncData`\> | Comments attached to those posts (milestone 159/172) |
| `reactions`? | Array\<`ReactionSyncData`\> | Reactions attached to those posts (milestone 159/172) |

`CommentSyncData`: `id, post_id, author_id, author_name, author_avatar_b64?, content, timestamp, signature, parent_comment_id?`
`ReactionSyncData`: `id, post_id, author_id, reaction_type, timestamp, signature`

There is **no** separate `INVENTORY_SYNC_RESPONSE` type — both `SYNC_REQUEST`
and `INVENTORY_SYNC_REQUEST` reply using this same `SYNC_RESPONSE` type,
distinguished only by whether `comments`/`reactions` are populated (older
timestamp-based replies leave them `null`).

---

## 4. Inventory-Based Sync (`INVENTORY_SYNC_REQUEST`)

This is the **primary** reconciliation mechanism, replacing pure
timestamp-based sync as the main strategy — `SYNC_REQUEST`/`SYNC_RESPONSE`
remain in the protocol and are still used as the *reply vehicle* for both
strategies.

### 4.1 Server-Side Diff (`SyncPacketHandler.handleInventorySyncRequest`)

1. Build `peerInventory: Map<String, String>` from the requester's
   `inventory` list (`{id, hash}` pairs) — i.e. the *requester's* own
   `{id -> hash}` map of posts it already has.
2. Compare against the receiver's own `postDao` contents: any local post
   whose `id` is **absent** from `peerInventory`, or present but with a
   **different hash**, is "missing or updated" from the requester's
   perspective.
3. Collect those into `missingOrUpdatedPosts`.
4. Additionally collect attached `comments`/`reactions` for those posts (and
   possibly for posts the requester already has, to backfill engagement
   data).
5. Wrap everything in a `SYNC_RESPONSE` and send it **directly** (hops=1,
   not gossiped) to the requester's onion address.

### 4.2 Timestamp-Based Path (still present, used as fallback/legacy)

- `SYNC_REQUEST` (`{since: Long}`) is sent automatically by
  `acceptConnectionRequest` immediately after a `USER_HANDSHAKE`, with
  `since = now - 7 days`.
- `handleSyncRequest` queries `postDao.getPostsSince(since)`, maps each post
  back into a `PostPayload`, wraps in `SyncResponsePayload(posts = ...)`, and
  sends directly (hops=1) to the requester.

### 4.3 `handleSyncResponse` Verification

Each post in `posts` is independently signature-verified
(`id|authorId|content|timestamp`) before `postDao.insertPost`; invalid
signatures are dropped per-post with a warning log. The `comments` and
`reactions` arrays are similarly verified using their own signed formats
before insertion via `commentDao`/`reactionDao`.

---

## 5. Presence Protocol (`ANNOUNCE_PEER` / `USER_EXIT`)

### 5.1 `ANNOUNCE_PEER`

- Broadcast every **60 seconds** (`MeshSocialRepository.startPresenceHeartbeat`)
  to trusted peers, with `hops = 1`, carrying the sender's `authorId` and
  timestamp, signed.
- `handleAnnouncePeer` verifies the signature and, on failure, logs
  `"Rejected ANNOUNCE_PEER: Signature verification failed"` and drops the
  packet.
- On success: `peerDao.insertPeer(peer.copy(isOnline = true, lastSeenAt = now))`.
- **Staleness is actively swept, not just UI-derived**: the same
  60-second heartbeat loop that broadcasts `ANNOUNCE_PEER` also iterates all
  known peers and, for any peer whose `lastSeenAt` is older than **3
  minutes**, calls `peerDao.insertPeer(peer.copy(isOnline = false))` directly
  — i.e. `Peer.isOnline` in Room *is* actively flipped back to `false` by a
  timeout sweep running in `MeshSocialRepository`, not left to the UI layer
  to infer.

### 5.2 `USER_EXIT`

- Broadcast on `logout()` and from `NoSlopForegroundService.onDestroy()`.
- Payload: `UserExitPayload {userId, timestamp, signature}`, signed string
  `userId|timestamp`.
- `handleUserExit`: rejects if `exitPay.userId != packet.senderId` (logged as
  `"Rejected USER_EXIT: userId does not match packet sender"`); otherwise
  verifies the signature and, on success, immediately sets
  `peerDao.insertPeer(peer.copy(isOnline = false, lastSeenAt = now))` — the
  only presence packet that can drive `isOnline` to `false` directly on
  receipt (mirroring gChat's `USER_EXIT` mitigation for "Ghost Peers", though
  without gChat's `USER_EXIT_ACK`/30-second wait — NoSlop fires `USER_EXIT`
  as a best-effort broadcast and proceeds with teardown immediately, which
  fits Android's more abrupt process lifecycle).

---

## 6. Media Packet Family (6 types)

| Type | Payload | Role |
|---|---|---|
| `MEDIA_REQUEST` | `MediaRequestPayload {media_id, chunk_index, chunk_size, access_key?, hls_file?}` | Requests a specific chunk of a media item by index |
| `MEDIA_CHUNK` | `MediaChunkPayload {media_id, chunk_index, total_chunks, data (Base64)}` | Carries one chunk's bytes |
| `MEDIA_RELAY_REQUEST` | `MediaRelayRequestPayload {media_id, origin_node?, owner_id?, access_key?, metadata?}` | Broadcast to trusted peers when the direct author is unreachable/unknown |
| `MEDIA_RECOVERY_FOUND` | `MediaRecoveryFoundPayload {media_id}` | Sent back along the relay chain once the media's source node is located |
| `MEDIA_PENDING` | `MediaPendingPayload {media_id, chunk_index}` | Signals an in-flight/awaited chunk — used by the AIMD inflight-tracking state in `MediaManager` |
| `MEDIA_TRANSFER_ACK` | `MediaTransferAckPayload {media_id}` | Transfer-completion acknowledgement |

`MediaMetadata` (embedded in `PostPayload.media_metadata` and
`MediaRelayRequestPayload.metadata`): `id, type ("audio"|"video"|"file"|
"image"), mime_type, size, chunk_count, access_key?, filename?, origin_node?,
owner_id?, thumbnail_b64?`.

### 6.1 Relay Routing (`GossipService`)

`MEDIA_RELAY_REQUEST` and `MEDIA_RECOVERY_FOUND` are intercepted inside
`GossipService.processIncoming` itself (not the `MeshPacketHandler`
dispatcher table in §2):

- **`handleRelayRequest(senderId, packet)`**: checks the local media
  directory for a file named `mediaId`. If present, immediately sends
  `MEDIA_RECOVERY_FOUND` (hops=1) directly back to `senderId`. If absent,
  registers `senderId` as a listener in `relayStates[mediaId]` (creating the
  entry with `payload.metadata` if new), then forwards the request along the
  mesh.
- **`handleRecoveryFound(senderId, packet)`**: sets
  `relayStates[mediaId].sourceNode = senderId`, then for each registered
  listener (excluding self), sends `MEDIA_RECOVERY_FOUND` (hops=1) to that
  listener's onion address.

`RelayState` tracks `establishedAt`/`lastActivity` per `mediaId`; a periodic
60-second sweeper evicts any entry idle for more than 5 minutes.

### 6.2 Zero-Copy Chunk Forwarding — Implemented

When a relay node receives a `MEDIA_CHUNK` packet, `MediaPacketHandler.
handleMediaChunk` calls `GossipService.forwardRelayChunk(mediaId, packet)`
**before** also calling `MediaManager.handleMediaChunk` for its own local
copy. `forwardRelayChunk` looks up `relayStates[mediaId]`, refreshes
`lastActivity`, and for every registered listener (other than the original
sender), re-stamps the packet with a fresh `id` (to bypass the next node's
dedup cache), decrements `hops`, sets `senderId` to the local node's key, and
sends it on — a live, in-flight forward to all downstream listeners rather
than a "fetch the whole file, then maybe re-share" model. This closes the
gap an earlier revision of this documentation flagged as unimplemented (see
GAP_ANALYSIS.md §6, corrected). Note the relay node *also* runs
`MediaManager.handleMediaChunk` for itself in the same call, so it is not a
pure disk-free pass-through — it forwards live **and** retains its own copy.

### 6.3 AIMD Congestion Control

Per-download state: `windowSize` init `4.0`, `ssthresh` init `128.0`,
`inflight: Set<Int>` of chunk indices, `rttEma`.

- **Slow start**: while `windowSize < ssthresh`, each received chunk does
  `windowSize += 1`.
- **Congestion avoidance**: once `windowSize >= ssthresh`, each received
  chunk does `windowSize += 1 / windowSize` (sub-linear growth).
- **Multiplicative decrease**: on a chunk timeout, `ssthresh = max(2.0,
  windowSize * 0.5)` and `windowSize` resets to `1.0` (classic Reno-style
  AIMD).
- `windowSize` is capped at `128.0`.
- New `MEDIA_REQUEST`s for additional chunks are issued only while
  `inflight.size < windowSize.toInt()` — i.e. `windowSize` (rounded down) is
  the live concurrency cap, replacing the previously-flat
  `MAX_CONCURRENCY = 4` (that constant remains in `MediaManager.kt` only as
  the AIMD initial window value).

This mirrors the algorithm documented for `hainet-social/src/congestion.rs`.
See TECHNICAL_REFERENCE.md §6 for chunk size, storage layout, auto-download
policy, and the local streaming proxy (`MediaProxyService`) — all unchanged
and still accurate.

---

## 7. Cryptographic Signed-String Formats — Consolidated Table

| Packet type | Signed string |
|---|---|
| `POST` / `SYNC_RESPONSE.posts[i]` | `id\|authorId\|content\|timestamp` (+`\|authorAvatarB64` if set on `POST`) |
| `COMMENT` / `SYNC_RESPONSE.comments[i]` | `postId\|commentId\|content\|timestamp` |
| `REACTION` / `SYNC_RESPONSE.reactions[i]` | `postId\|reactionType\|authorId\|timestamp` |
| `CHAT_REACTION` | `messageId\|reactionType\|authorId\|timestamp` |
| `COMMENT_REACTION` | `commentId\|reactionType\|authorId\|timestamp` |
| `VOTE` | `postId\|voteType\|authorId\|timestamp` |
| `COMMENT_VOTE` | `commentId\|voteType\|authorId\|timestamp` |
| `ANNOUNCE_PEER` | `authorId\|timestamp` |
| `IDENTITY_UPDATE` | `userId\|handle\|timestamp` (+`\|authorAvatarB64` if set) |
| `USER_EXIT` | `userId\|timestamp` |
| `EDIT_POST` | `postId\|authorId\|content\|timestamp` |
| `DELETE_POST` | `postId\|authorId\|timestamp` |
| `CONNECTION_REJECTED` | `fromUserId\|timestamp` |
| `CONNECTION_REQUEST` / `USER_HANDSHAKE` | computed and sent (`PeerHandshakePayload.signature`) but **never verified on receipt** — see §2 row 3–4 |

All signature operations use Ed25519 (`CryptoService.sign`/`verify`), Base64
no-wrap encoding, over the UTF-8 bytes of the literal pipe-delimited string.
Reconstructing the exact same string from received fields is the verifier's
job — field reordering or extra fields do not automatically invalidate or
validate a signature; each packet type's verifier must know its precise
format (this table is the canonical list).

---

## 8. Things Intentionally Not Covered Here

The following remain accurately described by `docs/TECHNICAL_REFERENCE.md`
and are not duplicated in this document:

- §1–3 (system overview, package layout, identity/crypto derivation —
  tripcode, onion address, BIP39 mnemonic, DM encryption).
- §6.2–6.5 (media storage layout, auto-download policy, `MediaProxyService`,
  thumbnail pipeline).
- §7 (clearnet aggregator: HTTP client separation, source library, API
  client roster, feed sync pipeline, RSS parsing).
- §8 (clearnet-to-mesh bridge: deterministic anchor IDs).
- §9 (Tor integration).
- §10 (Room schema v23).
- §11–14 (background work, build configuration, future HUB architecture,
  known discrepancies).

---

**Related docs**: [TECHNICAL_REFERENCE.md](TECHNICAL_REFERENCE.md) for
everything outside the wire protocol · [GAP_ANALYSIS.md](GAP_ANALYSIS.md) for
the feature backlog this protocol surface was built against ·
[PROJECT_STATUS.md](PROJECT_STATUS.md) for the milestone-by-milestone change
log.
