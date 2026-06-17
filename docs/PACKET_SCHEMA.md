# NoSlop Packet Schema

This document defines the JSON structure of each packet type used in NoSlop's HAI-Net mesh wire protocol. All peer-to-peer gossip broadcasts, encrypted direct messages, connection requests, and history synchronizations use these packet formats. Packets are transmitted as newline-delimited JSON over Tor-routed SOCKS5 TCP connections.

> **Related docs**: this file covers field-naming/JSON shape only. For the
> full 19-type packet catalog, dispatch/handler table, signed-string formats,
> and inventory-sync/media-relay protocol details, see
> [WIRE_PROTOCOL_REFERENCE.md](WIRE_PROTOCOL_REFERENCE.md) (current) and
> [TECHNICAL_REFERENCE.md §5](TECHNICAL_REFERENCE.md#5-wire-protocol--networkpacket-and-payloads)
> (envelope-level detail, packet-table superseded by WIRE_PROTOCOL_REFERENCE.md).

## Base Container (NetworkPacket)
All packets broadcast or sent over DM are wrapped in this JSON container.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `id` | String | No | Unique packet ID (UUID) |
| `hops` | Integer | No | Hop count/TTL for flood routing |
| `sender_id` | String | Yes | Base64 Ed25519 public key of the sender |
| `target_user_id` | String | No | Target recipient for DMs/Handshakes |
| `signature` | String | No | Ed25519 signature of the payload |
| `type` | String | Yes | Packet type (e.g. "POST", "MESSAGE") |
| `payload` | Object | No | The specific payload for the type |

---

## POST (Mesh Gossip)
**Type:** `POST`

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `id` | String | Yes | Unique post ID |
| `author_id` | String | Yes | Author's unique identifier / handle |
| `author_name` | String | Yes | Display name |
| `author_public_key` | String | Yes | Base64 Ed25519 identity key |
| `author_avatar_b64` | String | No | Base64-encoded small avatar image for the author, if set |
| `origin_node` | String | No | Network node where post originated |
| `content` | String | Yes | Text content of the post |
| `timestamp` | Long | Yes | Epoch time in milliseconds |
| `privacy` | String | Yes (default "public") | "public", "friends", or "private" |
| `hashtags` | Array<String> | No | List of hashtags |
| `signature` | String | No | Signature of the post payload |
| `media_id` | String | No | ID of associated media |
| `media_metadata` | Object | No | Media metadata object |
| `clearnet_url` | String | No | Original URL if sharing a clearnet article |
| `clearnet_title` | String | No | Original title if sharing a clearnet article |
| `clearnet_thumbnail_url` | String | No | URL of the thumbnail for the clearnet article |

> For the signed-string format used to verify `POST` signatures, and the full
> 19-type packet catalog this schema is part of, see
> [WIRE_PROTOCOL_REFERENCE.md §2 and §6](WIRE_PROTOCOL_REFERENCE.md).

---

## MESSAGE (Secure Direct Messages)
**Type:** `MESSAGE`

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `id` | String | Yes | Unique message ID |
| `nonce` | String | Yes | Initialization Vector/Nonce for encryption |
| `ciphertext` | String | Yes | Encrypted payload (Base64) |
| `group_id` | String | No | Group ID if a group chat message |

---

## CONNECTION_REQUEST
**Type:** `CONNECTION_REQUEST`

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `id` | String | Yes | Unique request ID |
| `from_user_id` | String | Yes | Sender's ID |
| `from_username` | String | Yes | Sender's handle |
| `from_display_name` | String | Yes | Sender's display name |
| `from_home_node` | String | Yes | Sender's Onion address |
| `from_encryption_public_key` | String | No | Sender's ECDH public key |
| `timestamp` | Long | Yes | Epoch timestamp |
| `signature` | String | No | Packet signature |

---

## USER_HANDSHAKE
**Type:** `USER_HANDSHAKE`

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `id` | String | Yes | Unique handshake ID |
| `from_user_id` | String | Yes | Sender's ID |
| `from_username` | String | Yes | Sender's handle |
| `from_display_name` | String | Yes | Sender's display name |
| `from_home_node` | String | Yes | Sender's Onion address |
| `from_encryption_public_key` | String | No | Sender's ECDH public key |
| `timestamp` | Long | Yes | Epoch timestamp |
| `signature` | String | No | Packet signature |

---

## REACTION
**Type:** `REACTION`

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `post_id` | String | Yes | ID of the post being reacted to |
| `reaction_type` | String | Yes | Type of reaction (e.g. "like", "upvote", "downvote", "angry") |
| `author_id` | String | Yes | Public key of the reactor |
| `timestamp` | Long | Yes | Epoch timestamp |
| `signature` | String | Yes | Ed25519 signature of the reaction payload |
| `action` | String | No | "add" (default) or "remove" to toggle reaction |

---

## CHAT_REACTION
**Type:** `CHAT_REACTION`

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `message_id` | String | Yes | ID of the chat message being reacted to |
| `reaction_type` | String | Yes | Type of reaction (e.g. "like", "upvote", "downvote", "angry") |
| `author_id` | String | Yes | Public key of the reactor |
| `timestamp` | Long | Yes | Epoch timestamp |
| `signature` | String | Yes | Ed25519 signature of the reaction payload |
| `action` | String | No | "add" (default) or "remove" to toggle reaction |

---

## COMMENT_REACTION
**Type:** `COMMENT_REACTION`

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `comment_id` | String | Yes | ID of the comment being reacted to |
| `reaction_type` | String | Yes | Type of reaction (e.g. "like", "upvote", "downvote", "angry") |
| `author_id` | String | Yes | Public key of the reactor |
| `timestamp` | Long | Yes | Epoch timestamp |
| `signature` | String | Yes | Ed25519 signature of the reaction payload |
| `action` | String | No | "add" (default) or "remove" to toggle reaction |

---

## SYNC_REQUEST
**Type:** `SYNC_REQUEST`

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `since` | Long | Yes | Epoch timestamp limit for historical sync |

---

## SYNC_RESPONSE
**Type:** `SYNC_RESPONSE`

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `posts` | Array<POST> | Yes | List of Post objects satisfying sync bounds |
| `comments` | Array<COMMENT> | No | List of Comment objects satisfying sync bounds |
| `reactions` | Array<REACTION> | No | List of Reaction objects satisfying sync bounds |
