# NoSlop — Mesh Wire Protocol Reference (Current State, 2026-10-08)

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

> **⚠️ Breaking change (2026-10-07, review round 2 D07): v2-only handshakes.**
> `CONNECTION_REQUEST` and `USER_HANDSHAKE` are accepted **only** with a
> `canonicalHandshakePayloadV2` signature (§3, §7). Nodes running an older
> NoSlop sign handshakes with the legacy encoding and can no longer connect to
> updated nodes, and vice versa — **both sides must update**. Rejected legacy
> handshakes are logged (`legacy (pre-v2) handshake — peer must update NoSlop`),
> the sender's key is remembered, and the user is told ("Older NoSlop
> Version"; the DMs tab's *Sent requests* list marks such peers).
>
> **2026-10-07/08 additions** (no other envelope or payload changes):
> the handshake reply path (§3.1), `media_metadata` on `CommentSyncData`
> and v2 (media-hash) signature acceptance in `SYNC_RESPONSE` (§3, §4.3),
> and the 256 KB chunk window (§6).

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

## 2. Full Packet Type Catalog

> The numbered rows below group some families onto one line (row 19 covers
> six media types), so the row count is not the number of distinct `type`
> strings. The authoritative list is the `when (packet.type)` block in
> `MeshPacketHandler.handleIncomingPacket`, plus `MEDIA_RELAY_REQUEST` and
> `MEDIA_PENDING`, which `GossipService` intercepts before dispatch.

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
| `DmPacketHandler` | `MESSAGE`, `DELETE_MESSAGE` |
| `HandshakePacketHandler` | `CONNECTION_REQUEST`, `USER_HANDSHAKE`, `CONNECTION_REJECTED`, `ANNOUNCE_PEER`, `IDENTITY_UPDATE`, `USER_EXIT` |
| `MediaPacketHandler` | `MEDIA_REQUEST`, `MEDIA_CHUNK`, `MEDIA_RECOVERY_FOUND` |

| # | `type` | Payload class | Signed string format | Handler (class.method) | Persistence |
|---|---|---|---|---|---|
| 1 | `POST` | `PostPayload` | `id\|authorId\|content\|timestamp` (+`\|authorAvatarB64` if set) | `PostPacketHandler.handlePost` | `postDao.insertPost`; triggers media auto-download |
| 2 | `MESSAGE` | `EncryptedPayload` | Pairwise directional ChaCha20-Poly1305 with AAD (`v: 2`); fallback v1 unauthenticated | `DmPacketHandler.handleDirectMessage` | `messageDao.insertMessage`; outbox retry until `DM_ACK` |
| 3 | `CONNECTION_REQUEST` | `PeerHandshakePayload` | `fromUserId\|fromUsername\|fromHomeNode\|timestamp` (+`\|authorAvatarB64`, `\|bio` if set) | `HandshakePacketHandler.handleConnectionRequest` | inserts untrusted `Peer`, sets `_incomingRequestFlow` |
| 4 | `USER_HANDSHAKE` | `PeerHandshakePayload` | `fromUserId\|fromUsername\|fromHomeNode\|timestamp` (+`\|authorAvatarB64`, `\|bio` if set) | `HandshakePacketHandler.handleUserHandshake` | upserts `Peer` with `isTrusted = true` |
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
| 20 | `CONNECTION_REJECTED` | `ConnectionRejectedPayload` | `fromUserId\|timestamp` (signed, dual-mode: encodeForSigning & pipe) | `HandshakePacketHandler.handleConnectionRejected` | Deletes the untrusted `Peer` locally and triggers a decline notification |
| 21 | `GROUP_INVITE` | `GroupInvitePayload` | `groupId\|title\|adminPublicKeyB64\|timestamp` | `HandshakePacketHandler.handleGroupInvite` | `groupChatDao.insertGroupChat` — only if the signature verifies against `adminPublicKeyB64`, our identity is in `members`, and any existing group of that id has the same admin |
| 22 | `GROUP_UPDATE` | `GroupUpdatePayload` | `groupId\|title\|signerPublicKeyB64\|timestamp` | `HandshakePacketHandler.handleGroupUpdate` | `groupChatDao.insertGroupChat` with the merged member list, or `deleteGroupChat` if the update removed us |
| 23 | `GROUP_DELETE` | `GroupDeletePayload` | `groupId\|delete\|adminPublicKeyB64\|timestamp` | `HandshakePacketHandler.handleGroupDelete` | `groupChatDao.deleteGroupChat` — admin only, signature-verified |
| 24 | `TYPING` | `TypingPayload` | **none — unsigned** | `DmPacketHandler.handleTyping` | none; updates the in-memory `peerTypingStates` flow |
| 25 | `READ_RECEIPT` | `ReadReceiptPayload` | **none — unsigned** | `DmPacketHandler.handleReadReceipt` | `messageDao.markAsReadById(receipt.messageId)` |
| 26 | `DELETE_MESSAGE` | `DeleteMessagePayload` | `messageId\|authorId\|timestamp` | `DmPacketHandler.handleDeleteMessage` | DM: `messageDao.deleteMessageByIdAndSender`; Group (if `group_id` set): `messageDao.deleteMessageById` after verifying author is message sender or group admin |
| 27 | `GROUP_MESSAGE` | *(retired)* | *(retired)* | *(retired — unencrypted cleartext packet eliminated per C22; all group messaging strictly uses pairwise E2EE `MESSAGE` fan-out)* | none |
| 28 | `PEER_REMOVED` | `PeerRemovedPayload` | `userId|timestamp` (signed, supporting encodeForSigning and pipe) | `HandshakePacketHandler.handlePeerRemoved` | Deletes the peer and purges all of their posts, comments, reactions, and on-disk media files locally without remote re-notification |
| 29 | `GROUP_QUERY` | `GroupQueryPayload` | n/a (queries group state and member keys) | `HandshakePacketHandler.handleGroupQuery` | Replies with `GROUP_SYNC` containing full group schema and `memberDetails` |
| 30 | `GROUP_SYNC` | `GroupSyncPayload` | Canonical `canonicalGroupSyncPayload(groupId, groupChatJson, timestamp, sortedMemberDetails)` | `HandshakePacketHandler.handleGroupSync` | Merges group members, handles, and member details; verified against admin or member keys |
| 31 | `EDIT_COMMENT` | `EditCommentPayload` | `postId|commentId|content|timestamp` (+`|authorAvatarB64`) (dual-mode: encodeForSigning & pipe in verifier & handler) | `CommentPacketHandler.handleEditComment` | updates `mesh_comments.content`, `timestamp`, `signature` |
| 32 | `DELETE_COMMENT` | `DeleteCommentPayload` | `postId|commentId|authorId|timestamp` (dual-mode: encodeForSigning & pipe) | `CommentPacketHandler.handleDeleteComment` | marks `mesh_comments.content = '[Deleted]'` |
| 33 | `FOLLOW` / `UNFOLLOW` | `FollowPayload` | `followedPublicKeyB64|followerPublicKeyB64|timestamp` (dual-mode: encodeForSigning & pipe) | `HandshakePacketHandler.handleFollow` | `peerDao.updateFollowState` |
| 34 | `ANNOUNCE_INVIDIOUS_INSTANCE` | *(retired)* | *(retired)* | *(retired — unauthenticated video instance gossip eliminated per C05 to prevent SSRF and resolver hijacking)* | none |
| 35 | `DM_ACK` | `DmAckPayload` | AEAD directional ChaCha20-Poly1305 AAD authentication over `(msg_id, senderPub, recipientPub, timestamp)` | `DmPacketHandler.handleDmAck` | `messageDao.updateDeliveryStatus(msgId, 'DELIVERED')`; removes message from persistent outbox |

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
- Rows 3–4 (`CONNECTION_REQUEST`/`USER_HANDSHAKE`) verify signatures
  on receipt using dual-mode verification: length-prefixed `CryptoService.encodeForSigning`
  as primary, with fallback to legacy pipe-delimited strings (`fromUserId|fromUsername|fromHomeNode|timestamp`
  + optional `authorAvatarB64` and `bio`).
- `ANNOUNCE_PEER` (row 14) and `DM_SYNC_REQUEST` are whitelisted through
  the gossip firewall (`GossipService.processIncoming`), ensuring presence
  heartbeats and direct message sync packets are processed and peer online
  states update correctly. Directed `MESSAGE` packets (row 2) addressed to
  the local node bypass the untrusted sender gate so ChaCha20-Poly1305 AEAD
  decryption in `DmPacketHandler` serves as the cryptographic authenticity check.
  Unauthenticated incoming DMs are rate-limited to 10 packets per 60 seconds per
  sender in `GossipService.processIncoming`, and peer contact identities are only
  associated with burnable identities after cryptographic decryption succeeds.
- - **Friends-Only Broadcast Isolation**: Outbound packets for friends-only broadcasts (`POST` with `privacy = "friends"`,
  and `EDIT_POST`, `DELETE_POST`, `COMMENT`, `REACTION`, etc. with `hops = 1`) are strictly isolated to trusted
  direct peer connections (`isTrusted && !isTemporary && contactSetting != "burnable"`) and the post author. Temporary
  contacts and creator nodes connected via severable temporary connections are excluded from broadcast distribution
  and historical/inventory sync.

On multi-identity Creator Nodes, outbound `POST` broadcasts dynamically stamp `senderId`
  with either the burnable identity public key (for temporary follower contacts) or main identity public key
  (for personal contacts), ensuring recipient firewalls accept the packet.
- **Friends-Only Broadcast Isolation**: Outbound packets for friends-only broadcasts (`POST` with `privacy = "friends"`,
  and `EDIT_POST`, `DELETE_POST`, `COMMENT`, `REACTION`, etc. with `hops = 1`) are strictly isolated to trusted
  direct peer connections (`isTrusted && !isTemporary && !isCreator && contactSetting != "burnable"`). Temporary
  contacts and creator nodes connected via severable temporary connections are excluded from broadcast distribution
  and historical/inventory sync.
- Rows 21–23 (the `GROUP_*` family): all three carry a signature and all
  three verify it. `GROUP_INVITE` and `GROUP_DELETE` verify against the
  `adminPublicKeyB64` in the payload, and `GROUP_DELETE` additionally
  requires that key to equal the *stored* group's admin — a packet-supplied
  key matching itself proves nothing.

  `GROUP_UPDATE` is the awkward one: it has no signer field, and
  `GossipService.forwardPacket` re-stamps `senderId` on relay, so neither
  can identify who signed it. `resolveUpdateSigner` recovers the signer by
  testing the signature against the admin key and each current member key in
  turn — O(members) Ed25519 verifies, on this packet type only. Once the
  signer is known, authorisation is per-field: only the admin may change
  title / description / avatar; a non-admin may add members only when
  `allowMemberInvites` is set, and may remove only itself and only when
  `allowMemberSelfRemove` is set. The admin can never be removed by an
  inbound packet. These two switches live on the `GroupChat` entity and were
  previously stored but never enforced on the wire.
- Rows 24–25 (`TYPING`/`READ_RECEIPT`) are unsigned by design — they carry
  no durable state and are cheap to spoof. `READ_RECEIPT` marks exactly the
  message named by `messageId`; it used to mark the whole conversation read
  and ignore that field entirely.

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

When `media_metadata.sha256` is present the author signs the **v2** form
(the 8 canonical fields plus the digest, §7). A receiver that can only verify
the 8-field form treats the digest as unsigned. In `SYNC_RESPONSE` the digest
is then removed before auto-download (2026-10-08); the live `POST` /
`EDIT_POST` path does not strip it yet (review finding D08, open).

### MESSAGE (Secure Direct Messages)
**Type:** `MESSAGE` · class `EncryptedPayload`

| Field | Type | Description |
|---|---|---|
| `id` | String | Unique message ID |
| `nonce` | String | Initialization Vector/Nonce for encryption |
| `ciphertext` | String | Encrypted payload (Base64) |
| `group_id`? | String | Set when this DM is one fan-out leg of a group message; the receiver files it under this group's thread instead of a 1:1 thread. `null` for ordinary DMs. |
| `timestamp`? | Long | Epoch timestamp |

If a DM carries media, the decrypted plaintext is a JSON object
`{"content": "<text>", "media": <MediaMetadata>}` rather than raw text;
`handleDirectMessage` attempts to parse it as JSON and falls back to raw
text if parsing fails.

**Group messages** ride on this same `MESSAGE` type. `sendGroupMessage`
encrypts the body once per member with that member's X25519 key and sends N
separate packets, each carrying the same `group_id` and the same message
`id`. The plaintext JSON also carries a `groupId` field, which
`handleDirectMessage` reads as a fallback for senders that only populate it
there. On receipt, a packet with a resolved group id is checked against the
local `GroupChat` — unknown group, or a sender who is not a member, and the
packet is dropped. Accepted group messages are stored with
`chatWithPeerPub = groupId` (matching what the sender's own local echo does)
and with the *decrypted* text in the `ciphertext` column, because a group
thread has no single counterparty key for the chat screen to decrypt against
at render time.

### CONNECTION_REQUEST / USER_HANDSHAKE
**Type:** `CONNECTION_REQUEST` or `USER_HANDSHAKE` · class `PeerHandshakePayload` (shared, unified type per milestone 56)

| Field | Type | Description |
|---|---|---|
| `id` | String | Unique request ID |
| `from_user_id` | String | Sender's public key |
| `from_username` | String | Sender's handle |
| `from_display_name` | String | Sender's display name |
| `author_avatar_b64`? | String | Sender's avatar, if set |
| `bio`? | String | Sender's bio, if set |
| `from_home_node` | String | Sender's onion address |
| `from_encryption_public_key`? | String | Sender's X25519 public key |
| `timestamp` | Long | Epoch timestamp |
| `signature`? | String | v2 signature (below); the envelope `signature` takes precedence when both are set |
| `request_nonce`? | String | `CONNECTION_REQUEST`: 128-bit random nonce chosen by the requester. **Required** (blank → rejected) |
| `in_reply_to_nonce`? | String | `USER_HANDSHAKE`: echo of the requester's `request_nonce`; promotion to `ACCEPTED` requires it to match the stored `pendingNonce` |
| `target_user_id`? | String | Identity this handshake is addressed to (main or burnable key). Must be a local identity and must equal the envelope `targetUserId` when that is set |
| `version` | Int | `2` for handshakes built by current clients |

**Signature (v2 only).** `canonicalHandshakePayloadV2` =
`encodeForSigning("noslop-hs-v2", fromUserId, fromUsername, fromHomeNode,
fromEncryptionPublicKey, targetUserId, nonce, timestamp, authorAvatarB64?, bio?)`,
where `nonce` is `request_nonce` for a request and `in_reply_to_nonce` for a
handshake. Legacy encodings (`fromUserId|fromUsername|fromHomeNode|timestamp…`)
are recognised only to report "peer must update"; they are never accepted.

**Receiver rules (`HandshakePacketHandler`).** Requests older than 1 hour from
unknown peers are ignored, as are requests from a peer deleted within the last
7 days unless the request was created after the deletion (a deliberate
reconnect). A
`BLOCKED` peer's request is ignored. A request crossing our own
`OUTGOING_PENDING` request is treated as mutual consent. A request from an
`ACCEPTED` friend re-sends our handshake echoing their nonce; a changed
encryption key is held in `pendingEncKey` and never applied silently.

#### 3.1 Handshake reply path (Round A2, 2026-10-07)

A freshly published onion descriptor can take minutes to propagate, so the
requester can often reach the accepter long before the accepter can reach the
requester. The answer therefore travels back on the requester's own
connection:

1. The requester writes the `CONNECTION_REQUEST` and keeps the socket open for
   `MeshTransport.HANDSHAKE_REPLY_WINDOW_MS` = **6 s**, reading newline-delimited
   frames. It accepts exactly one `USER_HANDSHAKE` or `CONNECTION_REJECTED`
   whose envelope `senderId` equals the identity it contacted (the request's
   `targetUserId`); anything else on that socket is ignored.
2. The accepter may write a reply on that inbound connection only after the
   request passed signature, target and freshness checks (timestamp within
   ±5 minutes), and at most once per request timestamp per sender (a
   timestamp must be strictly newer than the last one answered). A replayed
   or forged request never collects a handshake.
3. Reply content: `USER_HANDSHAKE` when the user has accepted (or the peer is
   already `ACCEPTED`); `CONNECTION_REJECTED` when the request repeats a nonce
   the user declined (`declined_request_<pub>`); nothing while the request is
   still awaiting the user — the requester learns by probing.
4. **Probing.** While a request is `OUTGOING_PENDING`, the requester re-sends it
   every **15 s** for up to **10 min**: same `request_nonce`, same identity,
   fresh `timestamp`, fresh packet `id` (so dedup lets it through), each
   probe re-signed. Probes are direct only (no Hub, outbox, gossip or cooldown
   bookkeeping) and stop once the row leaves `OUTGOING_PENDING` or its nonce
   changes. A pending request is never re-notified to the accepting user;
   *Re-send* creates a new nonce and is asked normally.

The normal (separate-connection) delivery of `USER_HANDSHAKE` and
`CONNECTION_REJECTED` still happens as before; the reply path is an addition.

### ANNOUNCE_PEER
**Type:** `ANNOUNCE_PEER` · class `AnnouncePeerPayload`

| Field | Type | Description |
|---|---|---|
| `author_id` | String | Sender's public key |
| `onion_address`? | String | Sender's current onion address — **not covered by the signature** (open finding S2: a replayed fresh announce can rewrite a friend's stored onion) |
| `timestamp` | Long | Epoch timestamp |
| `signature` | String | Ed25519 signature over `authorId\|timestamp` |

### ANNOUNCE_DISCOVERABLE
**Type:** `ANNOUNCE_DISCOVERABLE` · class `AnnounceDiscoverablePayload`

| Field | Type | Description |
|---|---|---|
| `author_id` | String | Sender's public key |
| `handle` | String | Sender's handle |
| `onion_address` | String | Sender's onion address |
| `enc_public_key` | String | Sender's X25519 public key |
| `is_creator` | Boolean | True if the node is a creator |
| `fund_me_link`? | String | Donation link, if set |
| `author_avatar_b64`? | String | Sender's avatar, if set |
| `bio`? | String | Sender's bio, if set |
| `timestamp` | Long | Epoch timestamp |
| `signature` | String | Signature over payload (using colons `:`) |

### IDENTITY_UPDATE
**Type:** `IDENTITY_UPDATE` · class `IdentityUpdatePayload`

| Field | Type | Description |
|---|---|---|
| `user_id` | String | Subject's public key (verifying key) |
| `handle` | String | New handle/display name |
| `author_avatar_b64`? | String | New avatar, if changed |
| `bio`? | String | New bio, if changed |
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

Sent by the identity the request addressed. Since 2026-10-07 it can also arrive
as the answer on the requester's own connection (§3.1), and the decliner
remembers the declined nonce so repeated probes get this packet instead of a
new prompt. Received `CONNECTION_REJECTED` only cancels our own
`OUTGOING_PENDING` request; it never removes an accepted friend.

### EDIT_POST
**Type:** `EDIT_POST` · class `EditPostPayload`

| Field | Type | Description |
|---|---|---|
| `post_id` | String | ID of the post being edited |
| `author_id` | String | Claimed author's public key (verifying key; cross-checked against the stored post's author) |
| `author_avatar_b64`? | String | Author's avatar Base64, if set |
| `content` | String | New content |
| `timestamp` | Long | Epoch timestamp (must be ≥ stored post's timestamp to apply) |
| `signature` | String | Signature over `postId\|authorId\|content\|timestamp` |
| `media_id`? | String | ID of updated media attachment, if present |
| `media_metadata`? | Object | Media descriptor object, if present |
| `privacy`? | String | `"public"` (hops=6) or `"friends"` (hops=1) |
| `clearnet_url`? | String | Clearnet URL anchor if editing a shared post |

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

**Types:** `REACTION`, `CHAT_REACTION`, `COMMENT_REACTION` · classes `ReactionPayload`, `ChatReactionPayload`, `CommentReactionPayload`

| Field | Type | Description |
|---|---|---|
| `post_id` / `message_id` / `comment_id` | String | ID of the target being reacted to (field name varies by type) |
| `reaction_type` | String | e.g. `"like"`, `"upvote"`, `"downvote"`, `"angry"` |
| `author_id` | String | Public key of the reactor |
| `timestamp` | Long | Epoch timestamp |
| `signature` | String | Ed25519 signature (see §2 for exact per-type string) |
| `action` | String | `"add"` (default) or `"remove"` — toggles the reaction |
| `group_id`? | String | Optional group identifier (present on `CHAT_REACTION` for group messages) |

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

`CommentSyncData`: `id, post_id, author_id, author_name, author_avatar_b64?, content, timestamp, signature, parent_comment_id?, media_id?, media_type?, media_metadata?`

`posts[i].media_metadata.sha256` and `comments[i].media_metadata.sha256` carry
the signed media digest (from 2026-10-08). The sender takes it from the
`media_owner` index, or hashes its local copy; a missing or wrong digest can
only make the receiver fall back to (or fail) the 8-field / legacy check,
never accept a forged item.
`ReactionSyncData`: `id, post_id, author_id, reaction_type, timestamp, signature`

There is **no** separate `INVENTORY_SYNC_RESPONSE` type — both `SYNC_REQUEST`
and `INVENTORY_SYNC_REQUEST` reply using this same `SYNC_RESPONSE` type,
distinguished only by whether `comments`/`reactions` are populated (older
timestamp-based replies leave them `null`).

### GROUP_INVITE

**Type:** `GROUP_INVITE` · class `GroupInvitePayload`

| Field | Type | Description |
|---|---|---|
| `group_id` | String | Group identifier (UUID, minted by the creator) |
| `title` | String | Group name |
| `admin_public_key` | String | Creator's Ed25519 key; also the verifying key for the signature |
| `members` | Array<String> | Full member list as Ed25519 public keys |
| `avatar_b64`? | String | Group picture |
| `description`? | String | Group description |
| `member_handles`? | Map<String, String> | Display handles of members |
| `member_details`? | Map<String, GroupMemberInfo> | Directory of member onion addresses and X25519 encryption keys |
| `allow_member_invites`? | Boolean | Whether non-admin members are permitted to invite peers (default: true) |
| `allow_member_self_remove`? | Boolean | Whether members may voluntarily leave the group (default: true) |
| `timestamp` | Long | Epoch milliseconds |
| `signature` | String | Signature over `groupId|title|adminPublicKeyB64|timestamp` |
| `admin_onion`? | String | Onion address of group admin |
| `admin_enc_public_key`? | String | X25519 encryption public key of group admin |

Rejected unless the signature verifies against `admin_public_key`, our own
(or burnable) identity appears in `members`, and — if we already hold a group
with this `group_id` — the admin key matches the stored one. An inbound
packet can never reassign a group's admin.

### GROUP_UPDATE

**Type:** `GROUP_UPDATE` · class `GroupUpdatePayload`

| Field | Type | Description |
|---|---|---|
| `group_id` | String | Group identifier |
| `title`? | String | New title, or the unchanged current title |
| `avatar_b64`? | String | New group picture |
| `description`? | String | New description |
| `added_members`? | Array<String> | Members added by this update (a delta, not the full list) |
| `removed_members`? | Array<String> | Members removed by this update |
| `banned_members`? | Array<String> | Members blacklisted/banned by this update (admin only) |
| `member_handles`? | Map<String, String> | Display handles of members |
| `member_details`? | Map<String, GroupMemberInfo> | Directory of member onion addresses and X25519 encryption keys |
| `allow_member_invites`? | Boolean | Updated invite permission flag (admin only) |
| `allow_member_self_remove`? | Boolean | Updated self-remove permission flag (admin only) |
| `timestamp` | Long | Epoch milliseconds |
| `signature` | String | Signature over `groupId|title|signerPublicKeyB64|timestamp` |

`added_members` / `removed_members` are genuine deltas computed by
`NoSlopRepository.updateGroupChat` against the stored member list. They were
previously the *whole* new member list in `added_members` with
`removed_members` never populated, which is why removals could not propagate:
the receiver does `addAll(added)` then `distinct()`.

There is no signer field — see the note under §2 rows 21–23 for how the
signer is recovered and what each role is permitted to change.

### GROUP_DELETE
**Type:** `GROUP_DELETE` · class `GroupDeletePayload`

| Field | Type | Description |
|---|---|---|
| `group_id` | String | Group identifier |
| `admin_public_key` | String | Claimed admin key; must equal the stored group's admin |
| `timestamp` | Long | Epoch milliseconds |
| `signature` | String | Signature over `groupId\|delete\|adminPublicKeyB64\|timestamp` |

### GROUP_QUERY
**Type:** `GROUP_QUERY` · class `GroupQueryPayload`

| Field | Type | Description |
|---|---|---|
| `group_id` | String | Group identifier |
| `requester_id` | String | Public key of the member requesting group catch-up |
| `timestamp` | Long | Epoch milliseconds |

### GROUP_SYNC
**Type:** `GROUP_SYNC` · class `GroupSyncPayload`

| Field | Type | Description |
|---|---|---|
| `group_chat_json` | String | Serialized GroupChat JSON |
| `member_details`? | Map<String, GroupMemberInfo> | Directory of member handles, onion addresses, and X25519 encryption keys |
| `timestamp` | Long | Epoch milliseconds |
| `signature` | String | Signature over `canonicalGroupSyncPayload(groupId, groupChatJson, timestamp, sortedMemberDetails)` |

### EDIT_COMMENT
**Type:** `EDIT_COMMENT` · class `EditCommentPayload`

| Field | Type | Description |
|---|---|---|
| `post_id` | String | ID of the post containing the comment |
| `comment_id` | String | ID of the comment being edited |
| `author_id` | String | Author's public key (verifying key) |
| `author_avatar_b64`? | String | Author avatar Base64, if set |
| `content` | String | New comment content |
| `timestamp` | Long | Epoch milliseconds |
| `signature` | String | Signature over `postId|commentId|content|timestamp` (+`|authorAvatarB64`) |

### DELETE_COMMENT
**Type:** `DELETE_COMMENT` · class `DeleteCommentPayload`

| Field | Type | Description |
|---|---|---|
| `post_id` | String | ID of the post containing the comment |
| `comment_id` | String | ID of the comment being deleted |
| `author_id` | String | Author's public key (verifying key) |
| `timestamp` | Long | Epoch milliseconds |
| `signature` | String | Signature over `postId|commentId|authorId|timestamp` |

### FOLLOW / UNFOLLOW
**Types:** `FOLLOW`, `UNFOLLOW` · class `FollowPayload`

| Field | Type | Description |
|---|---|---|
| `followed_public_key` | String | Public key of the node being followed |
| `follower_public_key` | String | Public key of the follower (verifying key) |
| `timestamp` | Long | Epoch milliseconds |
| `signature` | String | Signature over `followedPublicKeyB64|followerPublicKeyB64|timestamp` |
| `action` | String | `"follow"` or `"unfollow"` |

### DM_ACK (Direct Message Delivery Acknowledgment)
**Type:** `DM_ACK` · class `DmAckPayload`

| Field | Type | Description |
|---|---|---|
| `msg_id` | String | ID of the acknowledged message |
| `nonce` | String | ChaCha20-Poly1305 initialization vector |
| `ciphertext` | String | AEAD ciphertext encrypting `"ACK"` |
| `timestamp`? | Long | Epoch timestamp |
| `v` | Int | Protocol version (defaults to 2) |

Carries authenticated delivery confirmation from the recipient back to the message author. Keyed using the counterparty's directional key (`k_dir = HKDF-SHA256(X25519, recipient -> sender)`), guaranteeing receipt cannot be forged or reflected by network intermediaries.

### TYPING
**Type:** `TYPING` · class `TypingPayload` · **unsigned**

| Field | Type | Description |
|---|---|---|
| `chat_with_peer_pub` | String | Sender's own public key, i.e. which thread the indicator belongs to from the receiver's point of view |
| `is_typing` | Boolean | Whether the indicator should show |
| `timestamp` | Long | Epoch milliseconds |

### READ_RECEIPT
**Type:** `READ_RECEIPT` · class `ReadReceiptPayload` · **unsigned**

| Field | Type | Description |
|---|---|---|
| `message_id` | String | The specific message being acknowledged |
| `reader_public_key` | String | Reader's public key |
| `timestamp` | Long | Epoch milliseconds |

Blank `message_id` is rejected. Neither of these two types is signed, so
neither should be treated as evidence of anything.

### PEER_REMOVED
**Type:** `PEER_REMOVED` · class `PeerRemovedPayload`

| Field | Type | Description |
|---|---|---|
| `user_id` | String | Public key of the peer initiating the removal |
| `timestamp` | Long | Epoch milliseconds |
| `signature` | String | Signature over `userId\|timestamp` (supporting both `encodeForSigning` and pipe) |

Directly informs a connected peer that they have been removed from contacts, triggering symmetric peer deletion and complete local content/media purging.

### DELETE_MESSAGE
**Type:** `DELETE_MESSAGE` · class `DeleteMessagePayload`

| Field | Type | Description |
|---|---|---|
| `message_id` | String | ID of the message to delete |
| `author_id` | String | Public key of the user requesting deletion |
| `timestamp` | Long | Epoch milliseconds |
| `signature` | String | Signature over `messageId\|authorId\|timestamp` |
| `group_id` | String? | Optional. When present, enables group-mode authorization: the deleter may be either the message's original author or the group admin. When absent, only the message's original sender may delete it (DM mode). |

**DM mode** (`group_id` absent): `authorId` must equal `packet.senderId` and
the message is deleted via `deleteMessageByIdAndSender`.

**Group mode** (`group_id` present): the handler loads the group, checks
that `authorId` is either the message's `senderPub` (author deleting own
message) or the group's `adminPublicKeyB64` (admin purging any message).
The message is deleted via `deleteMessageById` (no sender constraint).

### GROUP_MESSAGE
**Type:** `GROUP_MESSAGE` · class `GroupMessagePayload`

| Field | Type | Description |
|---|---|---|
| `id` | String | Unique message ID (UUID) |
| `group_id` | String | Target group identifier |
| `sender_handle` | String | Display name/handle of the author |
| `sender_tripcode`? | String | Short tripcode fingerprint |
| `content` | String | Plaintext message body |
| `timestamp` | Long | Epoch milliseconds |
| `privacy` | String | `"public"` (relayed mesh-wide, hops=6) or `"friends"` (direct contacts only, hops=1) |
| `media_id`? | String | Attached media ID, if present |
| `media_type`? | String | MIME category (`image`, `video`, `file`, `audio`), if present |
| `media_metadata`? | Object (`MediaMetadata`, §5) | Media descriptor object |
| `reply_to`? | String | ID of the message being replied to |

Delivers group messages across multi-hop mesh relays to non-connected members
in open groups without exposing the sender's raw public keys or onion addresses.

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

Each post in `posts` is independently signature-verified before
`postDao.insertPostSafely`, accepting — as the live `POST` handler does — the
**v2** form (canonical 8 fields + `media_metadata.sha256`) or the canonical
8-field form (§7); invalid signatures are dropped per post with a warning log.
Until 2026-10-08 only the 8-field form was accepted here, so every post with a
media digest was rejected by sync (regression R5). When v2 verified, the digest
is recorded (`NoSlopRepository.recordMediaDigest`) so the item can be re-served
with its v2 signature; when only the 8-field form verified, `sha256` is
stripped before auto-download.

The `comments` array is verified the same way (v2 comment form with the media
digest, then the older comment encodings); `reactions` use their own signed
format. Accepted items are inserted via `commentDao` / `reactionDao`.

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
| `MEDIA_REQUEST` | `MediaRequestPayload {media_id, chunk_index, chunk_size, byte_offset?, byte_length?, access_key?, hls_file?, origin_onion?}` | Requests a byte range of a media item. `chunk_size` and `byte_length` must be in `1..262144` (256 KB, `MAX_CHUNK_BYTES`) and `byte_offset ≥ 0`; `chunk_size = 0` with no `byte_length` is a metadata request. The reply always goes to the requester's **stored** onion (C03); `origin_onion` is not used as a reply target. |
| `MEDIA_CHUNK` | `MediaChunkPayload {media_id, chunk_index, total_chunks, data (Base64), total_size?}` | Carries one chunk's bytes. `total_size` (optional Long) communicates the sender's known file size so receivers with indeterminate metadata can compute accurate download progress. |
| `MEDIA_RELAY_REQUEST` | `MediaRelayRequestPayload {media_id, origin_node?, owner_id?, access_key?, metadata?}` | Broadcast to trusted peers when the direct author is unreachable/unknown |
| `MEDIA_RECOVERY_FOUND` | `MediaRecoveryFoundPayload {media_id, onion_address?}` | Sent back along the relay chain once the media's source node is located; `onion_address` provides the source's direct Tor onion address. |
| `MEDIA_PENDING` | `MediaPendingPayload {media_id, chunk_index}` | Signals an in-flight/awaited chunk — used by the AIMD inflight-tracking state in `MediaManager` |
| `MEDIA_TRANSFER_ACK` | `MediaTransferAckPayload {media_id}` | Transfer-completion acknowledgement |

> **Note on Temporary Contacts and Media Routing:** Media packets exchanged with a peer designated as a "Temporary Contact" (e.g., from a Discoverable connection) must explicitly use the local node's **burnable identity** public key as the `senderId` instead of the main identity. Because the temporary contact's gossip firewall is only aware of the burnable identity that performed the handshake, any media response or chunk request signed by the main identity will be immediately dropped as an unknown sender.

**Chunk window and sender limits (2026-10-07).** The downloader's byte window
starts at 256 KB, halves on a chunk timeout (down to 128 KB, `MIN_CHUNK_SIZE`)
and grows back by 32 KB per received chunk, capped at **256 KB**
(`MAX_CHUNK_SIZE = MAX_CHUNK_BYTES`). It used to grow to
1 MB while senders reject anything above 256 KB, which stalled every file
needing more than two requests (the "video stuck at 9%" bug). Pending ranges
larger than 256 KB are split without gaps before being requested. Senders
serve at most **16 MB per requester per 60 s** (previously 5 MB).

**Who is served.** `MediaManager.isMediaAuthorizedForSender` first consults the
`media_owner` index: public post/comment media → anyone; private → author;
friends-only → direct friends and the author; DM → the conversation partner;
group → members and admin. The older per-table checks follow (including the
"trusted peer" fallback that review finding D02 asks to remove).

`MediaMetadata` (embedded in `PostPayload.media_metadata`,
`CommentSyncData.media_metadata`, DM plaintext and
`MediaRelayRequestPayload.metadata`): `id, type ("audio"|"video"|"file"|
"image"), mime_type, size, chunk_count, access_key?, filename?, origin_node?,
owner_id?, thumbnail_b64?, sha256?` (C17 whole-file SHA-256 integrity digest).

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
| `POST` / `SYNC_RESPONSE.posts[i]` | **v2** (when `media_metadata.sha256` is set): `encodeForSigning(id, authorId, content, timestamp, authorAvatarB64, privacy, mediaId, clearnetUrl, sha256)`. Otherwise canonical 8-field `encodeForSigning(id, authorId, content, timestamp, authorAvatarB64, privacy, mediaId, clearnetUrl)`. Both forms are accepted by the live handler and, since 2026-10-08, by sync. Legacy unauthenticated fallback restricted strictly to public posts without attachments (`id\|authorId\|content\|timestamp` + optional avatar). |
| `COMMENT` / `SYNC_RESPONSE.comments[i]` | **v2** (when `media_metadata.sha256` is set): `encodeForSigning(postId, commentId, content, timestamp, authorAvatarB64, sha256)`. Otherwise `encodeForSigning(postId, commentId, content, timestamp, authorAvatarB64)`, `encodeForSigning(postId, commentId, content, timestamp)`, or pipe `postId\|commentId\|content\|timestamp` (+`\|authorAvatarB64`) |
| `REACTION` / `SYNC_RESPONSE.reactions[i]` | `postId\|reactionType\|authorId\|timestamp` |
| `CHAT_REACTION` | `messageId\|reactionType\|authorId\|timestamp` |
| `COMMENT_REACTION` | `commentId\|reactionType\|authorId\|timestamp` |
| `VOTE` | `postId\|voteType\|authorId\|timestamp` |
| `COMMENT_VOTE` | `commentId\|voteType\|authorId\|timestamp` |
| `ANNOUNCE_PEER` | `authorId\|timestamp` (`onion_address` unsigned — S2, open) |
| `IDENTITY_UPDATE` | `userId\|handle\|timestamp` (+`\|authorAvatarB64` if set) (+`\|bio` if set) |
| `USER_EXIT` | `userId\|timestamp` |
| `ANNOUNCE_DISCOVERABLE` | `authorId:handle:onionAddress:encPublicKey:isCreator:fundMeLink:authorAvatarB64:bio:timestamp` (using colons `:` instead of pipes) |
| `EDIT_POST` | Canonical 8-field `encodeForSigning(postId, authorId, content, timestamp, authorAvatarB64, privacy, mediaId, clearnetUrl)`. Legacy fallbacks strictly eliminated; complete signed state is atomically persisted to database. |
| `DELETE_POST` | `postId\|authorId\|timestamp` |
| `CONNECTION_REJECTED` | `fromUserId\|timestamp` (supporting encodeForSigning and pipe) |
| `CONNECTION_REQUEST` / `USER_HANDSHAKE` | **v2 only** (D07 hard break): `encodeForSigning("noslop-hs-v2", fromUserId, fromUsername, fromHomeNode, fromEncryptionPublicKey, targetUserId, nonce, timestamp, authorAvatarB64?, bio?)` with `nonce` = `request_nonce` (request) or `in_reply_to_nonce` (handshake). The legacy `fromUserId\|fromUsername\|fromHomeNode\|timestamp` forms are rejected. |
| `GROUP_INVITE` | Canonical `canonicalGroupInvitePayload(groupId, title, adminPublicKeyB64, signerPublicKeyB64, timestamp, sortedMembers, allowMemberInvites, allowMemberSelfRemove, description, avatarB64, adminOnion, adminEncPublicKey, sortedMemberDetails, sortedMemberHandles)`. Legacy 7-field fallback strictly restricted to admin self-signed payloads with empty unsigned fields. |
| `GROUP_UPDATE` | Canonical 13-field presence-encoded `canonicalGroupUpdatePayload(groupId, encodeOptString(wireTitle), signerPublicKeyB64, timestamp, sortedAdded, sortedRemoved, encodeOptBanned(banned), encodeOptString(wireDesc), encodeOptString(wireAvatar), encodeOptBool(wireAllowInvites), encodeOptBool(wireAllowSelfRemove), sortedMemberDetails, sortedMemberHandles)`. Distinguishes absent (`ABSENT`) from cleared (`CLEAR`) values per W03. |
| `GROUP_SYNC` | Canonical `canonicalGroupSyncPayload(groupId, groupChatJson, timestamp, sortedMemberDetails)`. Legacy fallback only permitted when `memberDetails` is empty. |
| `GROUP_DELETE` | `groupId\|delete\|adminPublicKeyB64\|timestamp` |
| `PEER_REMOVED` | `userId\|timestamp` (supporting encodeForSigning and pipe) |
| `DM_ACK` | AEAD authenticated with directional key `HKDF-SHA256(sharedSecret, recipientEdPub -> senderEdPub)` and AAD `encodeForSigning("noslop-dm-v2", recipientEdPub, senderEdPub, msgId, "", timestamp)` |
| `DELETE_MESSAGE` | `messageId\|authorId\|timestamp` — DM: only message author; Group (if `group_id` set): author or admin |
| `GROUP_MESSAGE` | `groupId|id|content|timestamp|senderId` (legacy receive-only, verified against sender key) |
| `EDIT_COMMENT` | `postId|commentId|content|timestamp` (+`|authorAvatarB64` if set) (supporting encodeForSigning and pipe) |
| `DELETE_COMMENT` | `postId|commentId|authorId|timestamp` (supporting encodeForSigning and pipe) |
| `FOLLOW` / `UNFOLLOW` | `followedPublicKeyB64|followerPublicKeyB64|timestamp` (supporting encodeForSigning and pipe) |
| `TYPING` / `READ_RECEIPT` | *(unsigned by design)* |

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
- §10 (Room schema v19).
- §11–14 (background work, build configuration, future HUB architecture,
  known discrepancies).

---

**Related docs**: [TECHNICAL_REFERENCE.md](TECHNICAL_REFERENCE.md) for
everything outside the wire protocol · [GAP_ANALYSIS.md](GAP_ANALYSIS.md) for
the feature backlog this protocol surface was built against ·
[PROJECT_STATUS.md](PROJECT_STATUS.md) for the milestone-by-milestone change
log.
