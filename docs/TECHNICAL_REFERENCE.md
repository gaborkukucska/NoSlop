# NoSlop — Technical Reference

**Scope**: This document is a purely technical reference for the NoSlop
Android application as it exists in the codebase (`com.noslop.app`,
versionName `0.1.0`, Room schema version 16, compileSdk/targetSdk 35,
minSdk 24). It is intended to complement — not replace — `README.md`,
`docs/PROJECT_STATUS.md`, and `docs/PACKET_SCHEMA.md`. Where this document
and those files overlap, this document goes deeper into implementation
detail (file paths, function names, data flow, constants).

---

## 1. System Overview

NoSlop is a single APK with three largely independent subsystems sharing one
Room database and one identity:

1. **Clearnet Aggregator** (`feeds/`) — fetches RSS/Atom feeds and public API
   content over a direct (non-Tor) `OkHttpClient`, stores results in
   `feed_items`/`feed_sources`, and renders them in a TikTok-style vertical
   feed.
2. **HAI-Net Mesh / Social Layer** (`mesh/`, `crypto/`, `tor/`) — a Tor-v3
   hidden-service-addressed gossip network for posts, comments, reactions,
   direct messages, and media, modeled on gChat's protocol and partially
   aligned with the `hainet-social` Rust crate's packet definitions.
3. **Clearnet-to-Mesh Bridge** — the glue layer that lets a clearnet feed item
   become a mesh `POST` anchor (via a deterministic SHA3-256-derived post ID),
   so likes/comments/reactions on aggregated content flow through the same
   gossip pipeline as native mesh posts.

All three sit on top of a shared `NoSlopRepository` (the single Room-backed
data access point) and a shared `IdentityRepository` (Ed25519/X25519 keypair,
mnemonic, onion address).

```
┌─────────────────────────────────────────────────────────────────┐
│ UI Layer (Jetpack Compose, MainScreen.kt + tabs/*)                │
│  UnifiedFeedTab │ DMsTab │ SettingsTab │ ContentPreferencesScreen  │
└───────────────────────────┬───────────────────────────────────────┘
                             │ NoSlopViewModel (StateFlow)
┌───────────────────────────▼───────────────────────────────────────┐
│ NoSlopRepository                                                    │
│  ├─ IdentityRepository (keys, mnemonic, onion, lock state)          │
│  ├─ MeshPacketHandler  (incoming packet dispatch)                   │
│  ├─ FeedDao / PostDao / PeerDao / MessageDao / CommentDao /         │
│  │   MeshVoteDao / CommentVoteDao (Room, v20)                       │
│  │   ReactionDao / AppSettingDao (Room, v20)                        │
│  └─ ApiKeyRepository (user-supplied API keys, EncryptedSharedPrefs) │
└──────┬─────────────────────────────┬───────────────────────────────┘
       │                              │
┌──────▼─────────────┐      ┌─────────▼──────────────────────────────┐
│ feeds/              │      │ mesh/ + crypto/ + tor/                  │
│  FeedParser          │      │  GossipService (TTL, dedup, firewall,   │
│  PublicApiService     │      │     rate limit, relay)                  │
│  SourceLibrary        │      │  MeshTransport (SOCKS5 TCP :9999)        │
│  api/*Client (10x)     │      │  MediaManager / MediaProxyService        │
│  -> clearnetClient     │      │  CryptoService (Ed25519/X25519/ChaCha20) │
│     (no proxy)          │      │  TorService (embedded tor-android)       │
└─────────────────────┘      │  -> torClient (SOCKS5 127.0.0.1:9050)    │
                              └─────────────────────────────────────────┘
```

---

## 2. Package Layout

```
com.noslop.app
├── MainActivity.kt              Activity host, navigation graph entry
├── NoSlopApp.kt                 Application subclass — singleton init order
├── crypto/
│   ├── CryptoService.kt         Identity keys, signing, DM encryption, onion derivation
│   └── MnemonicGenerator.kt      BIP39 12-word mnemonic (full 2048-word list per milestone 85)
├── data/
│   ├── ApiKeyRepository.kt       User API keys (EncryptedSharedPreferences)
│   ├── BackupManager.kt           AES-256-CBC encrypted export/import (DB + media)
│   ├── Daos.kt                    Room DAOs (Feed, Peer, Post, Message, Comment, Reaction, Vote, AppSetting)
│   ├── Entities.kt                Room @Entity data classes
│   ├── IdentityRepository.kt      Identity persistence (EncryptedSharedPreferences + Room)
│   ├── MediaSettings.kt           Auto-download policy (JSON in app_settings)
│   ├── NoSlopDatabase.kt          Room database, version 16
│   ├── NoSlopRepository.kt        Central data/business logic facade (869 LOC)
│   └── UserProfile.kt             Display name / bio / avatar data class
├── debug/
│   └── Logger.kt                  Ring-buffer + async file-backed structured logger
├── feeds/
│   ├── FeedParser.kt              RSS/Atom parsing, HTML sanitization, RSS auto-discovery
│   ├── FeedSyncWorker.kt           WorkManager periodic sync (15 min)
│   ├── PublicApiService.kt         Category -> API client dispatch/orchestration
│   ├── SourceLibrary.kt            Built-in source catalog (16 categories)
│   └── api/                        10 API client implementations (see §7)
├── mesh/
│   ├── GossipService.kt            TTL, dedup, firewall, rate limit, relay routing
│   ├── MediaCaptureManager.kt       CameraX/MediaRecorder capture
│   ├── MediaManager.kt              Chunked media download/cache/auto-download
│   ├── MediaProxyService.kt         Local HTTP proxy (127.0.0.1:8080) for ExoPlayer/Coil
│   ├── MeshPacketHandler.kt         Incoming packet type dispatch + persistence
│   ├── MeshTransport.kt             SOCKS5 TCP transport, send retries
│   └── Packets.kt                   NetworkPacket + all payload data classes
├── net/
│   └── HttpClientProvider.kt        clearnetClient vs torClient OkHttpClient instances
├── tor/
│   └── TorService.kt                Embedded Tor lifecycle, hidden service registration
├── ui/
│   ├── ContentPreferencesScreen.kt  Unified profile/categories/genres/languages/sources
│   ├── HaiNetTab.kt                 Mesh feed / peers tab
│   ├── MainScreen.kt                Top-level Compose host (god file, 2,889 LOC pre-refactor)
│   ├── MediaComponents.kt           Shared media UI helpers
│   ├── NoSlopViewModel.kt            ViewModel exposing repository as StateFlow
│   ├── OnboardingScreen.kt           6-step onboarding flow
│   ├── PreloadManager.kt             ExoPlayer preload pool
│   ├── QRScanScreen.kt / QRShareSheet.kt  CameraX+MLKit QR pairing
│   ├── TorWarningPanel.kt            Tor-not-ready UI card + F-Droid/Orbot deep links
│   ├── UnifiedFeedTab.kt             VerticalPager feed (mesh + clearnet unified)
│   ├── components/                  FeedCard, VideoPlayer, AudioPlayer, ChatThreadScreen,
│   │                                 CommentsBottomSheet, PeerItem
│   ├── tabs/                        ApiKeysScreen, DMsTab, LogsViewerScreen, SettingsTab
│   └── theme/                       Color.kt, Theme.kt, Type.kt (Material3 + custom palette)
└── util/
    └── Constants.kt                 MESH_PORT = 9999
```

---

## 3. Identity & Cryptography

### 3.1 Key Material

`CryptoService.IdentityKeys` holds seven fields:

| Field | Format | Purpose |
|---|---|---|
| `publicKeyB64` | Base64, X.509 SubjectPublicKeyInfo | Ed25519 signing public key — also the node's primary identifier (`sender_id`, `author_id`, etc.) |
| `privateKeyB64` | Base64, PKCS#8 | Ed25519 signing private key — never logged, stored only in `EncryptedSharedPreferences` |
| `tripcode` | 6-char lowercase Base32 | `SHA3-256(raw Ed25519 pubkey bytes)` → Base32 → first 6 chars |
| `onionAddress` | 56-char `.onion` + suffix | Tor v3 address derived from the same Ed25519 key (see §3.3) |
| `displayName` | `"<handle>.<tripcode>"` | Human-facing identity string |
| `encPublicKeyB64` | Base64, X.509 | X25519 public key, used for DM key agreement |
| `encPrivateKeyB64` | Base64, PKCS#8 | X25519 private key |

### 3.2 Key Generation (`CryptoService.generateIdentity`)

- **API 33+ (Tiramisu and above)**: `KeyPairGenerator.getInstance("Ed25519")`
  using the platform Conscrypt provider, **no explicit `initialize()` call**
  (Conscrypt has a fixed key size).
- **API 24–32**: `KeyPairGenerator.getInstance("Ed25519", BC_PROVIDER)` with
  `initialize(255, SecureRandom())` — the `255` is a corrected value (see
  milestone 48: "Key size corrected to 255 for compatibility with Android's
  Conscrypt cryptographic provider to prevent onboarding page crashes on
  actual devices").
- X25519 keys are always generated via Bouncy Castle
  (`KeyPairGenerator.getInstance("X25519", BC_PROVIDER)`), regardless of API
  level.
- `BC_PROVIDER` is a singleton `org.bouncycastle.jce.provider.BouncyCastleProvider`
  instance held as an `object` property.

### 3.3 Tripcode and Onion Address Derivation

Both derivations strip the 12-byte X.509 SubjectPublicKeyInfo header when the
encoded key is 44 bytes (`encodedPubKeyBytes.copyOfRange(12, 44)`), leaving
the raw 32-byte Ed25519 public key.

**Tripcode** (`deriveTripcode`):
```
raw_pubkey (32 bytes)
  -> SHA3-256                         (32-byte digest)
  -> Base32 encode (RFC4648 alphabet "abcdefghijklmnopqrstuvwxyz234567")
  -> take first 6 characters
```

**Onion address** (`deriveOnionAddress`), following the Tor v3 spec:
```
version    = 0x03
prefix     = ".onion checksum" (UTF-8 bytes)
checksum_input = prefix + raw_pubkey + version
checksum   = SHA3-256(checksum_input)[0:2]
payload    = raw_pubkey + checksum + version
address    = Base32(payload).take(56) + ".onion"
```
If the Base32 encoding of `payload` is shorter than 56 characters it is
right-padded with `'a'` before truncation (defensive — in practice a 35-byte
payload Base32-encodes to exactly 56 characters, so this is a no-op safety
net).

### 3.4 Signing & Verification

- `sign(payload: String, privateKeyB64)` — `Signature.getInstance("Ed25519")`
  (Conscrypt on API 33+, BC otherwise), signs the **UTF-8 bytes of the literal
  string** `payload`, returns Base64 (no-wrap).
- `verify(payload, signatureB64, publicKeyB64)` — mirrors `sign`; returns
  `false` (never throws) on any exception.
- **Signed payload formats are pipe-delimited string concatenations**, not
  the JSON object itself. Examples found in `NoSlopRepository`:
  - Post: `"$id|${authorPublicKeyB64}|$content|$timestamp"`
  - Comment: `"$postId|$id|$content|$timestamp"`
  - Reaction: `"$postId|$reactionType|${authorPublicKeyB64}|$timestamp"`
  - Vote: `"$targetId|$voteType|${authorPublicKeyB64}|$timestamp"`

  This means signature verification must reconstruct the exact same
  pipe-delimited string from the received payload fields — any reordering or
  additional fields in a payload do **not** automatically invalidate or
  validate the signature; the verifier must know the precise format per
  packet type. `MeshPacketHandler` reconstructs these strings explicitly in
  `handlePost`, `handleComment`, `handleReaction`, `handleVote`, and `handleSyncResponse`.

### 3.5 Direct Message Encryption

`encryptDM(plaintext, theirEncPubB64, myEncPrivB64) -> Pair<ciphertextB64, nonceB64>`:

```
shared_secret = X25519(my_priv, their_pub)        // KeyAgreement "X25519", BC provider
chacha_key    = SHA3-256(shared_secret)            // 32 bytes
nonce         = 12 random bytes (SecureRandom)
ciphertext    = ChaCha20-Poly1305(chacha_key, nonce, plaintext_utf8)
```

`decryptDM` mirrors this; returns `null` on any failure (never throws).

If a DM carries media, the plaintext is actually a JSON object
`{"content": "<text>", "media": <MediaMetadata>}` before encryption — see
`sendDirectMessage` and `MeshPacketHandler.handleDirectMessage`, which
attempts to parse decrypted plaintext as JSON and falls back to treating it
as raw text if parsing fails.

### 3.6 Tor Hidden Service Key Expansion

`getRawEd25519Seed(privKeyB64)` converts the app's PKCS#8 Ed25519 private key
into the 64-byte `ED25519-V3` key blob format Tor's control-port `ADD_ONION`
command expects:

```
1. Parse PKCS#8 PrivateKeyInfo (Bouncy Castle ASN1)
2. Extract inner OCTET STRING -> 32-byte seed
3. expanded = SHA-512(seed)                      // 64 bytes
4. Clamp per Ed25519 spec:
     expanded[0]  &= 0b11111000   (and 248)
     expanded[31] &= 0b01111111   (and 127)
     expanded[31] |= 0b01000000   (or 64)
5. Base64-encode the 64 bytes -> "ED25519-V3:<base64>"
```
This produces a **persistent** onion address tied to the same key used for
post signatures — i.e., a node's mesh identity and its network address are
cryptographically the same key.

### 3.7 Storage Boundaries (`IdentityRepository`)

| Data | Storage | Notes |
|---|---|---|
| `ed25519_private_key`, `enc_private_key`, `mnemonic` | `EncryptedSharedPreferences` (`"noslop_identity_secure"`), AES-256-SIV keys / AES-256-GCM values, Keystore-backed `MasterKey` | Falls back to plaintext `SharedPreferences` (`"noslop_identity_fallback"`) if Keystore init throws; sets `isUsingInsecureStorage = true` (surfaced as a red UI banner per milestone 151) |
| `local_handle`, `local_pub_ed25519`, `local_pub_enc`, `local_tripcode`, `local_onion`, `local_display_name` | Room `app_settings` table | Public/displayable identity data |
| `session_locked` | Room `app_settings` | `"true"`/`"false"` string flag for `logout()`/`unlock(mnemonic)` |
| `onboarding_complete` | Room `app_settings` | Gates onboarding flow |

`isEncryptionActive()` checks `prefs.javaClass.name.contains("EncryptedSharedPreferences")`
to determine which backend is actually active at runtime.

### 3.8 BIP39 Mnemonic

`MnemonicGenerator.kt` (259 LOC) generates a 12-word mnemonic using the full
2048-word official BIP39 English wordlist (milestone 85 — earlier versions
used a truncated ~700–800-word list, flagged as non-BIP39-compliant in
`docs/ANALYSiS.md`). The mnemonic seeds the AES-256-CBC key used by
`BackupManager` for encrypted export/import.

---

## 4. Mesh Networking

### 4.1 Transport (`MeshTransport.kt`)

- **Listener**: `ServerSocket(MESH_PORT=9999, backlog=50,
  InetAddress.getByName("127.0.0.1"))` — bound **strictly to loopback**.
  Inbound connections only reach this socket via the Tor hidden service
  mapping (port 9999 on the `.onion` → `127.0.0.1:9999` locally), enforcing a
  "hidden-service-only" architecture (milestone 13).
- **Wire format**: newline-delimited JSON. Each line is one `NetworkPacket`
  serialized via Gson (`packet.toJson()` / `NetworkPacket.fromJson(line)`).
- **Outbound sends** (`sendPacket(onionAddress, port, packet)`):
  1. `TorService.waitForProxy(timeoutSeconds = 5)` — abort if SOCKS5 not
     reachable.
  2. Up to **5 attempts**. Each attempt opens a fresh
     `Socket(Proxy(SOCKS, 127.0.0.1:9050))`, calls
     `socket.connect(InetSocketAddress.createUnresolved(onionAddress, port), 30000)`
     (30s connect timeout — onion circuit builds can be slow), writes one
     line via `PrintWriter(autoFlush=true)`, then closes the socket.
  3. Backoff between attempts: `delay(attempt * 3000L)` ms (3s, 6s, 9s, 12s).
  4. Returns `true` on first successful write, `false` if all 5 attempts
     fail.

  Note: `docs/PROJECT_STATUS.md` milestone 14 describes "3 retries with 2s/4s
  backoff" — the current code (`MeshTransport.kt`, read directly) implements
  **5 attempts with `attempt*3000ms` backoff** (3s/6s/9s/12s for attempts
  2–5). The status doc appears to predate a later tuning pass.

### 4.2 Gossip Protocol (`GossipService.kt`)

`GossipService` is a Kotlin `object` (process-wide singleton), initialized via
`initialize(peerDao, transport, localPublicKeyB64)` from
`NoSlopRepository.saveLocalIdentity`.

**`processIncoming(packet)` pipeline** (returns `true` if the packet should be
processed locally by `MeshPacketHandler`):

1. **TTL check**: `hops = packet.hops ?: 6`. If `hops <= 0`, drop.
2. **Dedup**: `processedPacketIds: LinkedHashSet<String>` keyed by
   `packet.id`. If already present, drop (debug log only). Capped at 1000
   entries — when full, the **oldest 100** entries are removed
   (`repeat(100) { iterator.next(); iterator.remove() }`), i.e. eviction is
   batched, not strictly LRU-per-insert.
3. **Rate limiting**: `senderRateLimits: ConcurrentHashMap<String,
   MutableList<Long>>` per `senderId`. Sliding 10-second window
   (`now - it > 10000` entries are pruned), max **20** packets per window. If
   exceeded, drop with a `FIREWALL`-tagged warning log.
4. **Firewall**: For any packet that is **not**
   `CONNECTION_REQUEST`/`USER_HANDSHAKE` (connection packets) and **not**
   `MEDIA_RELAY_REQUEST`/`MEDIA_RECOVERY_FOUND` (media relay packets), the
   sender must exist in `peerDao` **and** have `isTrusted == true`, or the
   packet is dropped with a `FIREWALL BLOCKED` warning.
5. **Routing decision**:
   - If `packet.targetUserId != null` and `!= localPublicKeyB64` → call
     `forwardPacket(packet)` and return `false` (not for us, just relay).
   - Else if `type == "MEDIA_RELAY_REQUEST"` → `handleRelayRequest(...)`,
     also `forwardPacket(packet)`, return `false`.
   - Else if `type == "MEDIA_RECOVERY_FOUND"` → `handleRecoveryFound(...)`,
     **does not forward** (follows the reply chain back), return `true`.
   - Else (public broadcast addressed to us or untargeted) → `forwardPacket(packet)`
     **and** return `true` (process locally *and* relay).

**`forwardPacket(packet)`**:
- No-op if `hops <= 1` (would hit zero on next hop and be dropped anyway).
- Builds a **new** `NetworkPacket` with:
  - same `id`, `type`, `payload`, `signature`, `targetUserId`
  - `hops = currentHops - 1`
  - **`senderId` re-stamped to the local node's public key** — this is the
    "sender re-stamping" privacy mechanism: a peer receiving a forwarded
    packet sees *you* as the sender, not the original (possibly
    untrusted-to-them) author. This is the same mechanism gChat documents as
    "Link Identity" in its architecture doc.
- Sends to every peer in `peerDao.getAllPeersList()` where
  `publicKeyB64 != packet.senderId && publicKeyB64 != localPublicKeyB64 &&
  isTrusted`. Each send is launched independently via
  `scope.launch { tx.sendPacket(...) }` — fire-and-forget, no error
  aggregation.

**`broadcast(packet)`** (used for locally-originated content): sends the
**unmodified** packet to every `isTrusted && publicKeyB64 != localPublicKeyB64`
peer, each in its own coroutine.

### 4.3 Media Relay State Machine (`GossipService.RelayState`)

```kotlin
data class RelayState(
    val mediaId: String,
    val listeners: MutableSet<String> = ConcurrentHashMap.newKeySet(),
    var sourceNode: String? = null,
    val metadata: MediaMetadata? = null
)
```
Stored in `relayStates: ConcurrentHashMap<String, RelayState>` — **no
eviction policy** (see Gap Analysis §6).

- **`handleRelayRequest(senderId, packet)`**: checks
  `File(transport.repository.context.filesDir, "media")` for a file named
  `mediaId`. If present, immediately sends `MEDIA_RECOVERY_FOUND` (hops=1)
  directly back to `senderId`. If absent, registers `senderId` as a listener
  in `relayStates[mediaId]` (creating the entry with `payload.metadata` if
  new).
- **`handleRecoveryFound(senderId, packet)`**: sets `relayStates[mediaId].sourceNode
  = senderId`, then for each listener (excluding self), sends a
  `MEDIA_RECOVERY_FOUND` packet (hops=1) to that listener's onion address
  (looked up via `peerDao.getPeerByPublicKey`).

### 4.4 Incoming Packet Dispatch (`MeshPacketHandler.kt`)

`handleIncomingPacket(packet)`:
1. Fetches local identity (`repo.getLocalIdentity()`) — bails (`false`) if no
   identity yet.
2. Delegates to `GossipService.processIncoming(packet)` — if it returns
   `false`, stop here (packet was forwarded-only, deduped, rate-limited, or
   firewalled).
3. `when (packet.type)` dispatches to one of 11 handlers:

| Type | Handler | Signature verification | Persistence |
|---|---|---|---|
| `SYNC_REQUEST` | `handleSyncRequest` | n/a (no verification on the request itself) | none — replies with `SYNC_RESPONSE` |
| `SYNC_RESPONSE` | `handleSyncResponse` | per-post: `id\|authorId\|content\|timestamp` | `postDao.insertPost` for each valid post |
| `POST` | `handlePost` | `id\|authorId\|content\|timestamp` | `postDao.insertPost`; triggers `MediaManager.checkAndAutoDownload` if `mediaMetadata != null` |
| `COMMENT` | `handleComment` | `postId\|commentId\|content\|timestamp` | `commentDao.insertComment` |
| `REACTION` | `handleReaction` | `postId\|reactionType\|authorId\|timestamp` | `reactionDao.insertReaction` or `deleteReactionById` depending on `action` |
| `COMMENT_REACTION` | `handleCommentReaction` | `commentId\|reactionType\|authorId\|timestamp` | `commentReactionDao.insertReaction` or `deleteReactionById` |
| `VOTE` | `handleVote` | `postId\|voteType\|authorId\|timestamp` | `voteDao.insertVote` or `deleteVoteById` depending on `action` |
| `COMMENT_VOTE` | `handleCommentVote` | `commentId\|voteType\|authorId\|timestamp` | `commentVoteDao.insertVote` or `deleteVoteById` |
| `MEDIA_REQUEST` | `handleMediaRequest` | none | delegates to `MediaManager.handleMediaRequest` |
| `MEDIA_CHUNK` | `handleMediaChunk` | none | delegates to `MediaManager.handleMediaChunk` |
| `MEDIA_RECOVERY_FOUND` | `handleMediaRecoveryFound` | none | delegates to `MediaManager.handleRecoveryFound` |
| `MESSAGE` | `handleDirectMessage` | n/a (encryption itself is the auth) | `messageDao.insertMessage`; triggers auto-download if media attached |
| `CONNECTION_REQUEST` | `handleConnectionRequest` | none | inserts untrusted `Peer`, sets `_incomingRequestFlow` (UI prompt) |
| `USER_HANDSHAKE` | `handleUserHandshake` | none | upserts `Peer` with `isTrusted = true` |

Notably: **`CONNECTION_REQUEST` and `USER_HANDSHAKE` packets are not
signature-checked** in `MeshPacketHandler` even though both
`PeerHandshakePayload`s carry a `signature` field populated by the sender
(`NoSlopRepository.sendConnectionRequest`/`acceptConnectionRequest` both call
`CryptoService.sign(...)` before sending). This is a latent verification gap —
the signature is computed and transmitted but never checked on receipt.

### 4.5 Sync Protocol

- **`SYNC_REQUEST`** (`{ since: Long }`) is sent automatically by
  `acceptConnectionRequest` immediately after a `USER_HANDSHAKE`, with
  `since = now - 7 days` (`7 * 24 * 60 * 60 * 1000L`).
- **`handleSyncRequest`** queries `postDao.getPostsSince(syncPay.since)`,
  maps each `MeshPost` back into a `PostPayload` (re-deriving `MediaMetadata`
  with placeholder `mimeType="application/octet-stream"`, `chunkCount=0` if
  `mediaUrl != null`), wraps them in `SyncResponsePayload(posts = ...)`, and
  sends directly (not gossiped, hops=1) to the requester's onion address.
- **`handleSyncResponse`** verifies each post's signature independently
  before inserting — posts with invalid signatures are dropped with a
  per-post warning log, valid ones are upserted via `postDao.insertPost`
  (Room `OnConflictStrategy` determines overwrite-vs-ignore behavior, defined
  in `Daos.kt`).

This is **timestamp-based**, not hash/inventory-based (contrast with gChat's
`INVENTORY_SYNC_REQUEST`/`RESPONSE` — see Gap Analysis §1).

---

## 5. Wire Protocol — `NetworkPacket` and Payloads

### 5.1 Envelope

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
- `toJson()`/`fromJson()` use a fresh `Gson()` instance each call (no shared
  configured instance — default Gson settings apply).
- 13 typed payload accessor methods (`getPostPayload()`,
  `getMessagePayload()`, etc.) each guard on `type == "<TYPE>"` before
  attempting `Gson().fromJson(payload, X::class.java)`, returning `null` on
  type mismatch or `payload == null`.

### 5.2 Payload Types (all in `Packets.kt`)

| Data class | Used by packet type(s) | Key fields |
|---|---|---|
| `PostPayload` | `POST`, embedded in `SyncResponsePayload.posts` | `id, authorId, authorName, authorPublicKey, originNode?, content, timestamp, privacy, hashtags?, signature?, mediaId?, mediaMetadata?, clearnetUrl?, clearnetTitle?, clearnetThumbnailUrl?` |
| `CommentPayload` | `COMMENT` | `postId, comment: CommentData, parentCommentId?` |
| `CommentData` | nested in `CommentPayload` | `id, authorId, authorName, content, timestamp, signature` |
| `ReactionPayload` | `REACTION` | `postId, reactionType, authorId, timestamp, signature, action ("add"\|"remove", default "add")` |
| `ChatReactionPayload` | `CHAT_REACTION` | `messageId, reactionType, authorId, timestamp, signature, action ("add"\|"remove", default "add")` |
| `CommentReactionPayload`| `COMMENT_REACTION`| `commentId, reactionType, authorId, timestamp, signature, action ("add"\|"remove", default "add")` |
| `VotePayload` | `VOTE` | `postId, voteType ("upvote"\|"downvote"), authorId, timestamp, signature, action ("add"\|"remove")` |
| `CommentVotePayload`| `COMMENT_VOTE`| `commentId, voteType ("upvote"\|"downvote"), authorId, timestamp, signature, action ("add"\|"remove")` |
| `EncryptedPayload` | `MESSAGE` | `id, nonce, ciphertext, groupId?` (groupId reserved, unused — see Gap Analysis §3) |
| `PeerHandshakePayload` | `CONNECTION_REQUEST`, `USER_HANDSHAKE` | `id, fromUserId, fromUsername, fromDisplayName, fromHomeNode, fromEncryptionPublicKey?, timestamp, signature?` (unified type per milestone 56 — previously two near-duplicate classes) |
| `SyncRequestPayload` | `SYNC_REQUEST` | `since: Long` |
| `SyncResponsePayload` | `SYNC_RESPONSE` | `posts: List<PostPayload>` |
| `MediaMetadata` | embedded in `PostPayload`, `MediaRelayRequestPayload` | `id, type ("audio"\|"video"\|"file"\|"image"), mimeType, size, chunkCount, accessKey?, filename?, originNode?, ownerId?, thumbnailB64?` |
| `MediaRequestPayload` | `MEDIA_REQUEST` | `mediaId, chunkIndex, chunkSize, accessKey?, hlsFile?` |
| `MediaChunkPayload` | `MEDIA_CHUNK` | `mediaId, chunkIndex, totalChunks, data` (Base64) |
| `MediaRelayRequestPayload` | `MEDIA_RELAY_REQUEST` | `mediaId, originNode?, ownerId?, accessKey?, metadata?` |
| `MediaRecoveryFoundPayload` | `MEDIA_RECOVERY_FOUND` | `mediaId` |
| `MediaPendingPayload` | `MEDIA_PENDING` | `mediaId, chunkIndex` |
| `MediaTransferAckPayload` | `MEDIA_TRANSFER_ACK` | `mediaId` |
| `IdentityUpdatePayload` | `IDENTITY_UPDATE` | `userId, displayName, timestamp, signature` |
| `UserExitPayload` | `USER_EXIT` | `userId, timestamp, signature` |

### 5.3 Field Naming Convention

All JSON wire fields use `snake_case` via `@SerializedName`, while Kotlin
properties use `camelCase`. This matches the convention documented in
`PACKET_SCHEMA.md` and is consistent across all payload classes.

---

## 6. Media Pipeline

### 6.1 Chunking Constants (`MediaManager.kt`)

```kotlin
private const val CHUNK_SIZE = 256 * 1024       // 256 KB
private const val MAX_CONCURRENCY = 4
private const val DOWNLOAD_TIMEOUT_MS = 60000L  // 60s
```
256 KB matches gChat's documented chunk size exactly (`docs/ARCHITECTURE.md`
§2: "Files are split into 256KB chunks").

### 6.2 Storage Layout

`getMediaDirectory(type)` resolves to
`context.getExternalFilesDir(<Pictures|Movies|Music|Downloads>)/NoSlop/`
based on a `type` prefix match (`"image"`, `"video"`, `"audio"`, else
Downloads). Falls back to `context.filesDir` if external storage is
unavailable.

### 6.3 Auto-Download Policy (`MediaManager.checkAndAutoDownload`)

Gated by `MediaSettings` (JSON in `app_settings["media_settings"]`):
- `settings.enabled` — global kill switch.
- Context `"friends"`: requires `settings.autoDownloadFriends` **and** the
  author must be a `peerDao`-known, `isTrusted == true` peer.
- Context `"private"` (DMs): requires `settings.autoDownloadPrivate`.
- `settings.maxFileSizeMB` — if `metadata.size > maxBytes && size > 0`, skip
  (a `size == 0` placeholder, as used by `MediaProxyService`'s synthetic
  metadata, bypasses this check).

### 6.4 Local Streaming Proxy (`MediaProxyService.kt`)

- Binds `ServerSocket(8080, backlog=100, 127.0.0.1)` — a minimal hand-rolled
  HTTP/1.1 server (no framework) used so ExoPlayer/Coil/WebView can request
  `noslop://` content via ordinary `http://127.0.0.1:8080/stream?onion=...&id=...`
  URLs.
- Request handling:
  1. Parse request line manually (`readHttpHeaders` reads bytes until
     `\r\n\r\n`), reject non-`GET`/non-`/stream` with 400/404.
  2. Extract `onion` and `id` query params via naive `split("&")`/`split("=")`
     (no URL-decoding — query values must not contain `&` or `=`).
  3. **Disk-cache fast path**: if `MediaManager.getLocalFile(mediaId)` exists,
     stream it directly via `streamFile()` with a `Content-Length` header.
  4. **Mesh streaming path**: if not cached, calls
     `MediaManager.startDownload(...)` (creating placeholder `MediaMetadata`
     with `chunkCount=999` if no metadata is known yet — a sentinel meaning
     "unknown, terminate on completion flag instead of count"), then:
     - Subscribes to chunk arrivals via `MediaManager.subscribeToChunks`.
     - **Waits for chunk 0 specifically** before sending HTTP headers (so
       `Content-Type` sniffing by ExoPlayer/Coil sees real data from byte 0),
       polling with a 10s timeout per attempt.
     - Once chunk 0 arrives, sends `200 OK` headers with
       `Connection: close`, `Accept-Ranges: none`, `Cache-Control:
       public, max-age=3600` (no `Content-Length` — chunked/streamed
       indefinitely until close).
     - Sequentially writes chunks in order using a `TreeMap<Int,
       ByteArray>` reorder buffer + `LinkedBlockingQueue` for out-of-order
       arrivals, with a **120-second** poll timeout per subsequent chunk.
- `buildProxyUrl(onionAddress, mediaId)` is the single function other layers
  call to get a consumable `http://127.0.0.1:8080/stream?...` URL.

### 6.5 Thumbnail Pipeline (Milestone 49)

Mesh posts with media generate a small, high-compression Base64 thumbnail
(`MediaMetadata.thumbnailB64`) embedded directly in the `POST` gossip packet —
peers can render a preview immediately without waiting for the full chunked
transfer.

---

## 7. Clearnet Aggregator

### 7.1 HTTP Client Separation (`net/HttpClientProvider.kt`)

Per the `01-clearnet-aggregator.md` architecture proposal (now implemented):
- `clearnetClient: OkHttpClient` — no proxy, used by `FeedParser` and all
  `feeds/api/*Client` classes. Configured with `okhttp-dnsoverhttps`
  (Cloudflare `1.1.1.1`) per milestone 51 to avoid DNS resolution failures
  without forcing all traffic through Tor.
- `torClient: OkHttpClient` — SOCKS5 proxy `127.0.0.1:9050`, used exclusively
  by `MeshTransport` and `MediaProxyService`'s mesh-fetch path.

### 7.2 Source Library (`SourceLibrary.kt`)

16 categories: Technology, Privacy & Security, Self-Hosting, Science, World
News, Open Source, Video Platforms, Social Clearnet, Lifestyle, Gaming,
Health, Automotive, Art, Photography, Music, Reddit.

~50 hardcoded RSS/Atom sources (`feedType ∈ {"rss","atom"}`) plus 14
API-backed virtual sources (`feedType = "api"`, `url` field is a service
identifier like `"youtube:trending"`, `"reddit:multi"`, `"nasa:apod"` —
**not** an actual URL).

### 7.3 API Client Roster (`feeds/api/`)

| Client | Auth | Notes |
|---|---|---|
| `InvidiousApiClient` | none | YouTube via Invidious instance pool, replaces official YouTube Data API per architecture proposal §2 |
| `RedditApiClient` | none | `fetchSubreddit(sub, sort)`, `searchReddit(query)` |
| `InternetArchiveClient` | none | `getPopularVideos()`, `searchAudio(query)` |
| `NasaApiClient` | optional (DEMO_KEY works) | `fetchAPOD()`, `searchImageLibrary(query)` |
| `JamendoApiClient` | none (public client ID) | `searchTracks(query)` — CC-licensed music |
| `PexelsApiClient` | user key required | photos/videos, skipped silently if no key |
| `NewsApiClient` | user key required | headlines + search, supports `language` param |
| `GuardianApiClient` | user key required | `searchArticles`, `searchSection` |
| `VimeoApiClient` | user key required | `fetchFeatured` |
| `PodcastIndexClient` | user key required | `searchEpisodes`, supports `language` param |

`PublicApiService.fetchItemsForCategory` is a big `when(category)` dispatcher
— each category calls `safeCall(sourceId, activeApiSourceIds) { ... }` for
each relevant client, where `safeCall` is a no-op if `sourceId` is not in
`activeApiSourceIds` and catches/logs exceptions so one client failure
doesn't block the others. Results are `distinctBy { it.id }` deduplicated.

### 7.4 Feed Sync Pipeline (`NoSlopRepository.refreshFeeds`)

1. Abort early if `isAggregatorEnabled() == false` (`app_settings["enable_aggregator"]`,
   default `true`).
2. Build the merged negative-keyword blocklist:
   `OFFICIAL_NEGATIVE_KEYWORDS` (hardcoded: `nude, porn, murder, rape, gore,
   nsfw, sex, kill`) `+ getUserNegativeKeywords()` (comma-separated string in
   `app_settings["negative_keywords"]`), `.distinct()`.
3. Pick a random language from `getLanguagePreference()` (comma-separated
   codes, default `"en"`) — milestone 79 notes this is randomized per sync
   when multiple languages are selected.
4. **RSS/Atom pass**: for each `feedDao.getActiveSourcesList()` entry where
   `feedType != "api"`, call `FeedParser.fetchAndParse(url, sourceId)`,
   filter items whose `"${title} ${excerpt}"` (lowercased) contains any
   blocklist term, insert survivors via `feedDao.insertItems` (Room
   `OnConflictStrategy.IGNORE` per `Daos.kt` — duplicates by primary key are
   silently dropped), update `source.lastFetchedAt` and `unreadCount`.
5. **API pass**: for each category in
   `(activeSources.mapNotNull{it.category} + userCategories).distinct()`:
   - Load per-category user keywords (`app_settings["keywords_$category"]`),
     prepending selected music/video genres for `"Music"`/`"Video Platforms"`.
   - Determine `categoryApiSourceIds`: explicit API sources the user enabled
     for this category, **or**, if the user selected the category but enabled
     zero API sources for it, **all built-in API sources for that category**
     (milestone 84, "Smart Source Fallback").
   - Call `PublicApiService.fetchItemsForCategory(...)`, apply the same
     negative-keyword filter, insert.

### 7.5 RSS Parsing & Sanitization (`FeedParser.kt`, 484 LOC)

- `resolveRssUrl(url)` (milestone 59): given a bare site URL, checks
  `<link rel="alternate" type="application/rss+xml|atom+xml">` tags in the
  fetched HTML first, then probes a fallback list of well-known paths
  (`/feed`, `/rss`, `/feed.xml`, etc.).
- `stripHtml` (milestone 66, rewritten from regex to `Html.fromHtml()`):
  removes `<code>`/`<pre>` blocks (milestone 35, "no slop") and uses
  Android's native C-backed HTML parser to avoid main-thread ANRs on large
  articles (e.g. ScienceDaily).
- Supports YouTube's `media:group` RSS extension (milestone 33) for
  thumbnail/description extraction without the YouTube Data API.
- Extracts the first content image for article previews (milestone 36).

---

## 8. Clearnet-to-Mesh Bridge

### 8.1 Deterministic Anchor IDs

`NoSlopRepository.reactToFeedItemWithType(item, reactionType)`:
```kotlin
val urlBytes = clearnetUrl.toByteArray()
val hash = SHA3-256(urlBytes)                       // org.bouncycastle SHA3Digest(256)
val anchorId = "clearnet_" + hash.hexString().take(16)
```
This 16-hex-char-suffixed ID is **deterministic per URL** — any node that
shares the same clearnet URL converges on the same `anchorId`, so reactions/
comments from different users on the same article land on the same mesh
`POST` (matches the "canonical post-ID derivation scheme" called out as a
"needs to be built" item in the README's Phase 2 section, but **is** already
implemented in the current `reactToFeedItemWithType`).

### 8.2 Anchor Creation Flow

1. `postDao.hasPost(anchorId)` — if `0` (doesn't exist locally yet):
2. `composeAndBroadcastPost(content = "🔥 Shared Clearnet Post: ${item.title}",
   clearnetUrl, clearnetTitle = item.title, clearnetThumbnailUrl =
   item.thumbnailUrl, postIdOverride = anchorId)` — this both inserts a local
   `MeshPost` row **and** broadcasts a `POST` packet (hops=6) carrying the
   `clearnet_*` fields.
3. `reactToMeshPost(anchorId, reactionType)` — signs and broadcasts a
   `REACTION` packet against `anchorId`, toggling add/remove based on whether
   `reactionDao.getReactionById("${anchorId}_${myPubKey}_${reactionType}")`
   already exists.

Note: step 2 only runs if the anchor doesn't exist **locally** — there is no
network round-trip to check whether some *other* peer has already created
the anchor for this URL before broadcasting a new `POST`. In practice this is
fine because `anchorId` is deterministic, so even if two peers both broadcast
a `POST` with the same `id` for the same URL, `postDao.insertPost` (REPLACE
or IGNORE per the entity's conflict strategy) converges them to one row
either way — but it does mean the same anchor `POST` payload may be
gossiped multiple times by different originating peers.

### 8.3 UI-Level Wiring

- `MainScreen.kt`'s `FullScreenFeedCard` renders Like/Share/Comment overlays
  for both `UnifiedItem.Mesh` and `UnifiedItem.Feed` (clearnet) variants.
- For clearnet items, `onShare = onShareToMesh` opens the "Share to Mesh"
  confirmation dialog (`showShareDialog` state), which calls
  `composeAndBroadcastPost(clearnetUrl=..., clearnetTitle=...)` directly
  (a user-initiated share, separate from the like/comment anchor flow above).
- Mesh posts carrying `clearnetUrl` render a "View on Clearnet" button that
  fires `Intent.ACTION_VIEW` with the original URL.
- Per milestone 90, clearnet feed items themselves have Like/Comment buttons
  **removed** from the unified feed — engagement is funneled through the
  mesh anchor post instead ("Interaction Isolation").

---

## 9. Tor Integration

### 9.1 Embedded Daemon Lifecycle (`TorService.kt`)

State machine: `IDLE -> STARTING -> PROXY_READY -> READY` (or `-> FAILED` from
any state). `IDLE` is the deliberate initial state (a documented fix —
initializing to `STARTING` caused the start-guard to bail on the first cold
launch).

`startTor(context, privateKeyB64?)`:
1. Guard: no-op if already `READY`/`STARTING`/`PROXY_READY` (but always
   updates `currentPrivateKeyB64` even when skipping).
2. `writeTorrc(context)` — writes `ControlPort 9051\nCookieAuthentication 1\n`
   to the `tor-android`-managed torrc path. (Note: `docs/ANALYSiS.md` item 6
   says `CookieAuthentication 0` is used; the code as read sets
   `CookieAuthentication 1`. `registerHiddenService`'s `AUTHENTICATE` command
   sends no cookie/password regardless, which would only succeed under
   `CookieAuthentication 0` — **this is a discrepancy worth re-verifying
   against the actual running torrc**, since `1` would require reading the
   auth cookie file to authenticate successfully.)
3. Registers a `BroadcastReceiver` for `org.torproject.jni.TorService.ACTION_STATUS`,
   mapping `STATUS_ON -> READY` (+ `triggerRegistration()`), `STATUS_OFF ->
   FAILED`, `STATUS_STARTING -> STARTING`.
4. Starts `org.torproject.jni.TorService` via `Intent(ACTION_START)`.
5. **Self-healing bootstrap loop** (coroutine): waits for SOCKS5 (`waitForProxy`,
   45s timeout) → `PROXY_READY`; then up to 20 attempts × 5s delay calling
   `checkTorConnection()` (fetches `https://check.torproject.org/` through the
   SOCKS5 proxy, looks for the string "Congratulations. This browser is
   configured to use Tor.") → `READY` + `triggerRegistration()`. This loop
   exists as a fallback for missed `STATUS_ON` broadcasts.

### 9.2 Hidden Service Registration

`registerHiddenService(privateKeyB64?, onAddressReady)`:
1. `waitForControlPort(10s)` — polls TCP connect to `127.0.0.1:9051`.
2. Opens a raw `Socket` to the control port, sends `AUTHENTICATE\r\n`.
3. Builds key parameter:
   - If `privateKeyB64` provided: `getRawEd25519Seed(privateKeyB64)` →
     `"ED25519-V3:<seed>"` (persistent address tied to identity key).
   - Else: `"NEW:ED25519-V3"` (ephemeral — used during onboarding before an
     identity exists).
4. Sends `ADD_ONION <keyParam> Flags=Detach Port=9999,127.0.0.1:9999`.
5. Reads multi-line response (`250-...` lines until `250 ` or a `5xx` line).
6. Extracts `ServiceID=<...>` → `<ServiceID>.onion`.
7. **Collision handling** (milestone 47): if response contains `"550 Onion
   address collision"` (the hidden service is already registered from a
   previous app session) and `privateKeyB64` is available, **derives** the
   onion address locally via `deriveOnionAddress` and still fires
   `onAddressReady` — so the UI updates even though `ADD_ONION` itself
   didn't return a fresh `ServiceID`.

### 9.3 Onboarding-to-Identity Transition

During onboarding, Tor is started with `privateKeyB64 = null` (ephemeral
onion). Once the user's permanent identity is generated and saved
(`NoSlopRepository.saveLocalIdentity`), `TorService.updateKeyAndRegister(keys.privateKeyB64)`
is called — if `_torState.value == READY`, this immediately
`triggerRegistration()`s again, this time with the persistent key, replacing
the ephemeral onion with the identity-derived one.

---

## 10. Data Model (Room, version 23)

| Entity / Table | Primary Key | Notable Fields | Indices |
|---|---|---|---|
| `feed_sources` | `id` | `url` (unique), `title`, `feedType`, `category`, `lastFetchedAt`, `unreadCount`, `isActive`, `addedDuringOnboarding` | unique on `url` |
| `feed_items` | `id` | `sourceId`, `title`, `url`, `excerpt`, `thumbnailUrl`, `publishedAt`, `isRead`, `isSaved`, `fullContent`, `mediaUrl`, `mediaType`, `apiSource` | on `sourceId` |
| `peers` | `publicKeyB64` | `handle`, `tripcode`, `onionAddress`, `encPublicKeyB64`, `isTrusted`, `lastSeenAt` | — |
| `mesh_posts` | `id` | `authorPublicKeyB64`, `authorHandle`, `authorTripcode`, `content`, `timestamp`, `signature`, `mediaUrl`, `mediaType`, `gossipCount`, `privacy`, `thumbnailB64`, `clearnetUrl`, `clearnetTitle`, `clearnetThumbnailUrl` | — |
| `chat_messages` | `id` | `chatWithPeerPub`, `senderPub`, `ciphertext`, `nonce`, `timestamp`, `isRead`, `mediaId`, `mediaType` | on `chatWithPeerPub`, on `timestamp` |
| `mesh_comments` | `id` | `postId`, `authorPublicKeyB64`, `authorHandle`, `content`, `timestamp`, `signature`, `parentCommentId` | on `postId` |
| `mesh_reactions` | `id` (format `"${postId}_${authorPubKey}_${reactionType}"`) | `postId`, `authorPublicKeyB64`, `reactionType`, `timestamp`, `signature` | — |
| `mesh_votes` | `id` (format `"${postId}_${authorPubKey}_${voteType}"`) | `postId`, `authorPublicKeyB64`, `voteType`, `timestamp`, `signature` | Separates upvotes/downvotes from emoji reactions |
| `comment_votes` | `id` (format `"${commentId}_${authorPubKey}_${voteType}"`) | `commentId`, `authorPublicKeyB64`, `voteType`, `timestamp`, `signature` | Votes scoped to comments |
| `viewed_history` | `id` (auto-gen) | `itemId`, `itemType`, `viewedAt` | `itemId` |
| `swipe_tracker` | `itemId` | `swipeCount`, `lastSwipedAt` | — |
| `app_settings` | `key` | `value` (string, often JSON) | — |

`app_settings` is the catch-all KV store for: identity public data
(`local_*`), onboarding/session flags, `media_settings` (JSON), per-category
keyword lists (`keywords_<Category>`), `selected_categories`,
`selected_music_genres`, `selected_video_genres`, `negative_keywords`,
`language_preference`, `enable_aggregator`, `user_profile` (JSON).

Database is opened with `JournalMode.WRITE_AHEAD_LOGGING` and
`fallbackToDestructiveMigration()` — any schema version bump destroys and
recreates all tables (acceptable for prototyping; **all local mesh history,
peers, and feed cache are lost on a version bump** since there is no
migration path defined).

---

## 11. Background Work

- **`NoSlopForegroundService`** (`mesh/NoSlopForegroundService.kt`): an Android 8+ compliant Foreground Service bound to `TorService`. It posts an ongoing "Mesh Sync" notification to prevent the OS from aggressively killing the Tor daemon and mesh networking listeners when the app goes into the background.
- **`FeedSyncWorker`** (`feeds/FeedSyncWorker.kt`, 20 LOC): a `CoroutineWorker`
  registered via `WorkManager` as a `PeriodicWorkRequest` with a **15-minute**
  interval, constrained to `NetworkType.CONNECTED`. Calls
  `repository.refreshFeeds()`.
- Mesh listener (`MeshTransport.startListening()`) and
  `MediaProxyService.start()` are started once from `NoSlopApp.onCreate()` as
  application-lifetime singletons (milestone 17 — previously these were
  re-initialized per-repository-instance, causing port-rebind exceptions when
  `FeedSyncWorker` spun up a new repository in the background).

---

## 12. Build Configuration

| Setting | Value |
|---|---|
| `applicationId` | `com.noslop.app` |
| `compileSdk` / `targetSdk` | 35 |
| `minSdk` | 24 (build.gradle.kts) — **note**: `docs/BUILD.md` states `minSdk = 26`; the build file as read says 24. Verify which is authoritative before release. |
| `versionCode` / `versionName` | 1 / `0.1.0` |
| ABIs | `armeabi-v7a, arm64-v8a, x86, x86_64` (`useLegacyPackaging = true` for jniLibs — required by `tor-android`) |
| Java/Kotlin target | 11 |
| Compose | enabled, via Compose BOM |

---

## 13. Future Architecture: HUBs / HAI-Net Hub Client

While NoSlop currently operates as a standalone, self-contained node running its own embedded Tor daemon, future iterations of the HAI-Net ecosystem conceptualize a "Local Hub" mesh (e.g., desktops or NAS devices acting as always-on master nodes). 

In this architecture, NoSlop serves as a `SLAVE_FRONTEND` client to a user's **HUB**. This Home HUB acts as the primary sovereign backup for the user's mesh Identity, encrypted data, and media library. Instead of maintaining a full local mesh stack and embedded Tor daemon on mobile, the app connects directly to the user's remote HUB via a private, authenticated onion address. This model aligns with gChat's dual hidden service architecture and is the planned avenue for long-term scalability and data persistence.
| Signing | `release` build type reads `NOSLOP_STORE_FILE`/`NOSLOP_STORE_PASSWORD`/`NOSLOP_KEY_ALIAS`/`NOSLOP_KEY_PASSWORD` Gradle properties; `debug` uses the default debug keystore |
| ProGuard | `release` has `isMinifyEnabled = true`, `isShrinkResources = true`, plus hardened `-keep`/`-dontwarn` rules for `tor-android`, `jtorctl`, `netcipher` (milestone 22) |

### Key Dependencies

- **Tor**: `info.guardianproject:tor-android:0.4.8.16`,
  `info.guardianproject:jtorctl:0.4.5.7`,
  `info.guardianproject.netcipher:netcipher:2.1.0`
- **Crypto**: `org.bouncycastle:bcprov-jdk15to18:1.78.1`
- **Networking**: `okhttp:4.10.0`, `okhttp-dnsoverhttps:4.12.0` (version
  mismatch noted in `docs/ANALYSiS.md`), `gson:2.10.1`
- **Media**: `androidx.media3` 1.3.1 (`exoplayer`, `exoplayer-hls`,
  `exoplayer-dash`, `ui`, `datasource-okhttp`)
- **Security**: `androidx.security.crypto` (alpha, per `libs.versions.toml`)
- **Persistence**: Room (`runtime`, `ktx`, `ksp` compiler)
- **QR**: `mlkit barcode-scanning`, `zxing:core`
- **Camera**: `androidx.camera.*` (`core`, `camera2`, `lifecycle`, `view`)
- **Background**: `androidx.work.runtime.ktx`
- **Testing**: JUnit, MockK, `kotlinx-coroutines-test`, Robolectric

### Permissions (`AndroidManifest.xml`)

`INTERNET`, `ACCESS_NETWORK_STATE`, `POST_NOTIFICATIONS` (API 33+ WorkManager
notifications), `CAMERA` (optional feature, for QR scanning), `RECEIVE_BOOT_COMPLETED`
(WorkManager rescheduling). `android:allowBackup="false"` — OS-level backup is
disabled in favor of the app's own encrypted export. `org.torproject.jni.TorService`
is declared as a non-exported `<service>`. A custom `network_security_config.xml`
is referenced for strict TLS with whitelisted cleartext exceptions (milestone 8).

---

## 13. Known Discrepancies Between Documentation and Code

(Cross-referenced in more detail in the companion gap-analysis document.)

1. README's "End-to-end encrypted DMs" prose says **AES-256-GCM**; the README
   Tech Stack table and the actual `CryptoService.encryptDM` implementation
   use **ChaCha20-Poly1305**. The Tech Stack table and code agree; the prose
   sentence is stale.
2. `CryptoService.kt`'s class KDoc says DM crypto is "ECDH (P-256) ... ->
   SHA-256 -> AES-256-GCM" — the implementation is X25519 → SHA3-256 →
   ChaCha20-Poly1305. The KDoc comment is stale on all three points.
3. `docs/PROJECT_STATUS.md` milestone 14 describes MeshTransport retries as
   "up to 3 times with 2s/4s backoff"; the code implements 5 attempts with
   `attempt*3000ms` backoff.
4. `docs/ANALYSiS.md` item 6 states `CookieAuthentication 0`; `TorService.writeTorrc`
   writes `CookieAuthentication 1`, while `registerHiddenService` authenticates
   with a bare `AUTHENTICATE\r\n` (no cookie) — this combination should be
   verified against the running `tor-android` behavior.
5. `docs/BUILD.md` states `minSdk = 26`; `app/build.gradle.kts` sets `minSdk = 24`.
6. `docs/PACKET_SCHEMA.md`'s `POST` field table omits `clearnet_thumbnail_url`,
   which exists in `Packets.kt`'s `PostPayload` and is actively used (milestone 90).
7. README's Phase 2 / "Clearnet-to-Mesh Broadcast System (Planned)" section
   describes `REACTION` packet support, reaction gossip handling, and
   canonical anchor-ID derivation as **not yet built** — all three are, in
   fact, already implemented (`ReactionPayload`, `MeshPacketHandler.handleReaction`,
   `reactToFeedItemWithType`'s SHA3-256 `anchorId` derivation). The README's
   "What needs to be built" list for items 1, 2, and 6 appears to be out of
   date relative to the code.
