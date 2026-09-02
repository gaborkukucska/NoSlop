# NoSlop — Technical Reference

**Scope**: This document is a purely technical reference for the NoSlop
Android application as it exists in the codebase (`com.noslop.app`,
versionName `0.4.0-alpha`, Room schema version 11 — see §10, compileSdk/targetSdk
35, minSdk 24). It is intended to complement — not replace — `README.md` and
`docs/PROJECT_STATUS.md`. Where this document and those files overlap, this
document goes deeper into implementation detail (file paths, function names,
data flow, constants).

**Wire protocol detail lives elsewhere**: §4 and §5 below cover mesh
networking and the packet envelope at a *mechanism* level (the gossip
pipeline, dispatch architecture, transport). For the full packet-type
catalog, every payload's JSON field shape, and all signed-string formats,
see [WIRE_PROTOCOL_REFERENCE.md](WIRE_PROTOCOL_REFERENCE.md) — that document
is the single source of truth for "what packet types exist and what's in
them," kept in sync with `Packets.kt` every time a new type ships. This
document's job is everything around that protocol: identity/crypto
derivation, Tor, the clearnet aggregator, media storage, build config.

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
│ UI Layer (Jetpack Compose, tabs/*)                                  │
│  UnifiedFeedTab │ DMsTab │ SettingsTab │ ProfileScreen │ ContentPreferencesScreen  │
└───────────────────────────┬───────────────────────────────────────┘
                             │ NoSlopViewModel (StateFlow)
┌───────────────────────────▼───────────────────────────────────────┐
│ NoSlopRepository                                                    │
│  ├─ IdentityRepository (keys, mnemonic, onion, lock state)          │
│  ├─ MeshPacketHandler  (incoming packet dispatch)                   │
│  ├─ FeedDao / PostDao / PeerDao / MessageDao / CommentDao /         │
│  │   MeshVoteDao / CommentVoteDao (Room, v5)                       │
│  │   ReactionDao / AppSettingDao (Room, v5)                        │
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
└─────────────────────┘      │  -> torClient (SOCKS5 127.0.0.1:TOR_SOCKS_PORT) │
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
│   ├── BackupManager.kt           AES-256-CBC encrypted export/import (DB + media) via SAF streams
│   ├── Daos.kt                    Room DAOs (Feed, Peer, Post, Message, Comment, Reaction, Vote, AppSetting)
│   ├── Entities.kt                Room @Entity data classes
│   ├── IdentityRepository.kt      Identity persistence (EncryptedSharedPreferences + Room)
│   ├── MediaSettings.kt           Auto-download policy: trust-based (friends/public), file exclusion (JSON in app_settings)
│   ├── MeshFilterSettings.kt      Mesh broadcast filter toggles (JSON in app_settings)
│   ├── NoSlopDatabase.kt          Room database, version 5
│   ├── NoSlopRepository.kt        Central data/business logic facade (~1,470 LOC — large; LOC will keep drifting, treat as approximate)
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
│   ├── HttpClientProvider.kt        clearnetClient vs torClient OkHttpClient instances
│   └── SshDeployer.kt               HAI-Net Hub SSH deployment (JSch + JSONObject config injection)
├── tor/
│   └── TorService.kt                Embedded Tor lifecycle, hidden service registration
├── ui/
│   ├── ContentPreferencesScreen.kt  Content filtering/categories/genres/languages/sources/mix ratios
│   ├── HaiNetTab.kt                 Home Hub deployment + control interface (delegates to HubSetupScreen)
│   ├── MeshFiltersScreen.kt         Granular incoming/outgoing mesh filter toggles
│   ├── MediaUtils.kt                Top-level media resolution utility (formerly MainScreen)
│   ├── MediaComponents.kt           Shared media UI helpers
│   ├── NoSlopViewModel.kt            ViewModel exposing repository as StateFlow
│   ├── OnboardingScreen.kt           9-step onboarding flow (includes Content Mix step)
│   ├── ProfileScreen.kt              Standalone profile editor (avatar, name, bio)
│   ├── PreloadManager.kt             ExoPlayer preload pool
│   ├── QRScanScreen.kt / QRShareSheet.kt  CameraX+MLKit QR pairing
│   ├── TorWarningPanel.kt            Tor-not-ready UI card + F-Droid/Orbot deep links
│   ├── UnifiedFeedTab.kt             VerticalPager feed (mesh + clearnet unified)
│   ├── components/                  FeedCard, VideoPlayer, AudioPlayer, ChatThreadScreen,
│   │                                 CommentsBottomSheet, PeerItem
│   ├── tabs/                        ApiKeysScreen, DMsTab, FeedMixSettingsSection, HubSetupScreen, LogsViewerScreen, ReportIssueScreen, SettingsTab
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

- **Lazysodium Primary Path**: By default, `cryptoSignKeypair()` from Lazysodium (libsodium via JNA) is used to generate the Ed25519 keypair. Libsodium produces high-quality keys consistently across all platforms, returning raw 32-byte seed/public keys. These are manually wrapped in standard ASN.1 PKCS#8 / X.509 headers before saving to ensure backwards compatibility with existing mesh peers.
- **Bouncy Castle Fallback**: If JNA fails to load or Lazysodium throws an error, generation falls back to Bouncy Castle's lightweight `Ed25519KeyPairGenerator`.
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

- `sign(payload: String, privateKeyB64)` — Uses Bouncy Castle's lightweight `Ed25519Signer` directly (bypassing the JCA `Signature` API). Signs the **UTF-8 bytes of the literal
  string** `payload`, returns Base64 (no-wrap).
- `verify(payload, signatureB64, publicKeyB64)` — Also uses `Ed25519Signer`; returns
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

### 3.5.1 Group Message Fan-Out

There is no group-wide symmetric key. `NoSlopRepository.sendGroupMessage`
encrypts the message body once per member using **that member's** X25519
public key and the sender's own X25519 private key
(`IdentityKeys.encPrivateKeyB64` — note this is a different key from the
Ed25519 `privateKeyB64` used for signing), then sends N separate `MESSAGE`
packets. Each leg carries the same `EncryptedPayload.groupId` and the same
message `id`.

```
for member in group.members - self:
    ciphertext, nonce = encryptDM(bodyJson, member.encPub, my.encPriv)
    send MESSAGE { id, ciphertext, nonce, groupId, timestamp } -> member
```

The trade-off is the same one gChat makes: O(N) encryption work per message,
in exchange for never having to rotate a shared key when membership changes.

Encryption failure is not silent. `encryptDM` returns `Pair("", "")` rather
than throwing on any failure, so `sendGroupMessage` checks for a blank
ciphertext or nonce and skips that recipient with an error log instead of
transmitting an empty payload.

**Known limitation.** DM encryption is static-static X25519: the shared
secret between any two identities is the same for every message they ever
exchange. There is no forward secrecy and no ratchet, so compromise of one
long-term private key retroactively decrypts that pair's entire history.
Group messages inherit this, once per member.

### 3.5.2 Group Message Deletion

`DELETE_MESSAGE` packets carry an optional `group_id` field. When present,
the handler runs group-specific authorization:

```
if groupId present:
    verify signature against deletePay.authorId
    existing = getGroupChatById(groupId) or reject
    if authorId == message.senderPub:  # author deleting own message
        delete message
    elif authorId == existing.adminPublicKeyB64:  # admin deleting any message
        delete message
    else:
        reject
else:
    # DM path: only the original sender can delete
    if authorId != packet.senderId: reject
    deleteMessageByIdAndSender(messageId, authorId)
```

The sender broadcasts the `DELETE_MESSAGE` packet to every group member
individually (same fan-out pattern as `sendGroupMessage`), not via gossip
broadcast — ensuring only group members receive deletion instructions.

`clearGroupChat()` is a local-only operation: it deletes all messages in a
group thread from the device's database without sending any packets. This
is the "Clear Chat" action in the group menu.

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

> **Critical Implementation Note:** Tor explicitly expects the 64-byte *expanded secret scalar + PRF secret*. It does **not** accept the 64-byte `libsodium` format (`seed || pubkey`). Passing the `libsodium` format to `ADD_ONION` will cause Tor to re-expand the key incorrectly, resulting in a completely mismatched public key and `.onion` address that breaks all peer routing.
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
`BackupManager` for encrypted export/import using Android's Storage Access Framework.

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
    2. Up to **3 attempts** (for critical packets; 2 for background). Each attempt opens a fresh
     `Socket(Proxy(SOCKS, 127.0.0.1:TOR_SOCKS_PORT))`, calls
     `socket.connect(InetSocketAddress.createUnresolved(onionAddress, port), 30000)`
     (30s connect timeout — forces a fast-fail so we can fallback to Gossip Relay if the HSDir hasn't propagated yet), writes one
     line via `PrintWriter(autoFlush=true)`, then closes the socket.
  3. Backoff between attempts: `delay(attempt * 4000L)` ms for critical packets, or `2000L` otherwise.
  4. Returns `true` on first successful write, `false` if all attempts fail.

### 4.2 Gossip Protocol (`GossipService.kt`)

`GossipService` is a Kotlin `object` (process-wide singleton), initialized via
`initialize(peerDao, transport, localPublicKeyB64, getMeshFilterSettings, checkEntityExists)` from
`NoSlopRepository.saveLocalIdentity` and `NoSlopApp.onCreate`.

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
    val metadata: MediaMetadata? = null,
    val establishedAt: Long = System.currentTimeMillis(),
    var lastActivity: Long = System.currentTimeMillis()
)
```
Stored in `relayStates: ConcurrentHashMap<String, RelayState>`. **Has TTL
cleanup**: a periodic 60-second sweeper evicts any entry whose
`lastActivity` is more than 5 minutes stale, and `MediaPacketHandler` (the
extracted media-domain handler, see §4.4) refreshes `lastActivity` on every
`MEDIA_CHUNK`. This closes the unbounded-memory-growth gap noted in
[GAP_ANALYSIS.md §6](GAP_ANALYSIS.md#6-trusted-media-relay--conceptually-present-streaming-semantics-differ).

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
- **Zero-copy chunk forwarding**: once a relay route is established, incoming
  `MEDIA_CHUNK` packets for that `mediaId` are live-forwarded to every
  registered listener via `GossipService.forwardRelayChunk`, in addition to
  the relay node downloading its own copy — see
  [WIRE_PROTOCOL_REFERENCE.md §6.2](WIRE_PROTOCOL_REFERENCE.md#62-zero-copy-chunk-forwarding--implemented)
  for the exact mechanism. This was previously an open gap; it is now
  implemented.

### 4.4 Incoming Packet Dispatch (`MeshPacketHandler.kt`)

`handleIncomingPacket(packet)`:
1. Fetches local identity (`repo.getLocalIdentity()`) — bails (`false`) if no
   identity yet.
2. Delegates to `GossipService.processIncoming(packet)` — if it returns
   `false`, stop here (packet was forwarded-only, deduped, rate-limited, or
   firewalled).
3. `when (packet.type)` dispatches to one of seven single-responsibility
   handler classes (`SyncPacketHandler`, `PostPacketHandler`,
   `CommentPacketHandler`, `ReactionPacketHandler`, `DmPacketHandler`,
   `HandshakePacketHandler`, `MediaPacketHandler`) — `MeshPacketHandler`
   itself only owns steps 1–2 plus the dispatch `when`; the per-type logic
   was extracted into those classes in a "Phase 0, Stage 0.3" refactor (ADR-004,
   method bodies moved verbatim). For the full, current 21-case dispatch
   table (type → payload class → handler class.method → signature format →
   persistence), see
   [WIRE_PROTOCOL_REFERENCE.md §2](WIRE_PROTOCOL_REFERENCE.md#2-full-packet-type-catalog-24-distinct-type-strings).

Notably: **`CONNECTION_REQUEST` and `USER_HANDSHAKE` packets are not
signature-checked** in `HandshakePacketHandler` even though both
`PeerHandshakePayload`s carry a `signature` field populated by the sender
(`NoSlopRepository.sendConnectionRequest`/`acceptConnectionRequest` both call
`CryptoService.sign(...)` before sending). This is a latent verification gap —
the signature is computed and transmitted but never checked on receipt. This
is still an open issue.

### 4.5 Sync Protocol

`INVENTORY_SYNC_REQUEST` (hash-based diffing — `{id, hash}` pairs, only the
diff is returned) is the primary reconciliation mechanism; the older
timestamp-based `SYNC_REQUEST`/`SYNC_RESPONSE` flow is still present and is
used as the *reply vehicle* for both strategies. Full detail — diffing
algorithm, the extended `SyncResponsePayload` with `comments`/`reactions`,
and verification rules — is in
[WIRE_PROTOCOL_REFERENCE.md §4](WIRE_PROTOCOL_REFERENCE.md#4-inventory-based-sync-inventory_sync_request).

### 4.6 Mesh Broadcast Filters

User-configurable content-type filters that gate which packets are pushed to
and pulled from the mesh network. Filters operate **exclusively at the network
sync layer** — all local database writes (reactions, comments, votes, posts)
always succeed regardless of filter state.

**Data model** (`MeshFilterSettings.kt`): 12 boolean flags (6 content types ×
2 directions). Persisted as JSON in `app_settings["mesh_filter_settings"]` via
`SettingsRepository`, exposed reactively as a `StateFlow<MeshFilterSettings>`.

| Filter | Default | Outgoing gate | Incoming gate |
|---|---|---|---|
| Reactions | **off** | `MeshSocialRepository`: wraps `GossipService.broadcast()` call after local `reactionDao`/`voteDao` insert | `GossipService.processIncoming`: step 4.5, checks `allowIncomingReactions` |
| Comments | on | `MeshSocialRepository.composeAndBroadcastComment`: wraps broadcast after `commentDao.insertComment` | `GossipService.processIncoming`: step 4.5, checks `allowIncomingComments` |
| Text Posts | on | `MeshSocialRepository.composeAndBroadcastPost`: wraps broadcast after `postDao.insertPost` (when no `clearnetUrl` and no `mediaMetadata`) | `GossipService.processIncoming`: step 4.5, inspects `POST` payload |
| Clearnet Shares | on | Same as above (when `clearnetUrl != null`) | Same as above (when `postPay.clearnetUrl != null`) |
| Image Posts | on | Same as above (when `mediaMetadata.type == "image"`) | Same as above (when `postPay.mediaMetadata.type == "image"`) |
| Video Posts | on | Same as above (when `mediaMetadata.type == "video"`) | Same as above (when `postPay.mediaMetadata.type == "video"`) |

**"Already shared" exemption** (incoming): When a reaction, vote, or comment
packet targets a post or comment that **already exists** in the local database
(verified via `NoSlopRepository.checkEntityExistsLocally(type, id)` → queries
`postDao.hasPost` / `commentDao.hasComment` / `messageDao.hasMessage`), the
packet is exempt from the incoming filter and is always accepted. This ensures
engagement on tracked content is never silently dropped.

**Clearnet bridging guard** (outgoing): `reactToMeshPost` accepts an
`isBridging: Boolean` parameter. When `reactToFeedItemWithType` calls it for a
newly-created clearnet anchor post (`existingCount == 0`), `isBridging = true`
causes the reaction broadcast to respect `allowOutgoingReactions`. For
reactions on posts already in the database, `isBridging = false` and the
broadcast always fires.

**DM chat exemption**: `CHAT_REACTION` packets are completely excluded from
both the incoming firewall filter check in `GossipService.processIncoming` and
the outgoing gate in `MeshSocialRepository.reactToChat`. Direct messages and
their reactions are never affected by mesh filter settings.

**UI**: `MeshFiltersScreen.kt` (Material 3) — accessible from Settings →
Content tab → Filtering & Content Mix → Mesh Filters button. 6 content-type cards, each with
Incoming/Outgoing toggle columns.

---

## 5. Wire Protocol — `NetworkPacket` and Payloads

The envelope shape, the full 24-type packet catalog, every payload's
JSON field table, and all signed-string formats now live in one place:
[WIRE_PROTOCOL_REFERENCE.md](WIRE_PROTOCOL_REFERENCE.md). That document is
kept in sync with `Packets.kt` directly; this section intentionally doesn't
duplicate it.

Two conventions worth calling out here since they apply project-wide, not
just to the wire protocol:
- `toJson()`/`fromJson()` use a fresh `Gson()` instance each call (no shared
  configured instance — default Gson settings apply).
- All JSON wire fields use `snake_case` via `@SerializedName`, while Kotlin
  properties use `camelCase` — consistent across every payload class.

---

## 6. Media Pipeline

### 6.1 Chunking Constants (`MediaManager.kt`)

```kotlin
const val MIN_CHUNK_SIZE = 128 * 1024        // 128 KB (Tor-optimized minimum)
const val MAX_CHUNK_SIZE = 1024 * 1024       // 1 MB (maximizes per-circuit throughput)
private const val MAX_CONCURRENCY = 2        // Max 2 concurrent SOCKS5 sockets
const val DOWNLOAD_TIMEOUT_MS = 300000L      // 5 minutes (accommodates Tor latency)
```
These values are specifically tuned for Tor Hidden Service circuits. The previous
values (64KB min, 256KB max, 4 concurrent, 120s timeout) caused excessive SOCKS5
handshake overhead and frequent timeouts on high-latency Tor circuits.

**AIMD Congestion Control.** Each download tracks a per-download AIMD state
machine tuned for Tor's characteristics:

- `currentChunkSize` starts at `128 KB`, `currentConcurrency` starts at `1.0`,
  `ssthresh` starts at `2.0`.
- **Slow start**: while `currentConcurrency < ssthresh`, each received chunk does
  `currentConcurrency += 1`.
- **Congestion avoidance**: once `currentConcurrency >= ssthresh`, each received
  chunk does `currentConcurrency += 1 / currentConcurrency` (sub-linear growth).
- **Multiplicative decrease**: on a chunk timeout, `ssthresh = max(1.0,
  currentConcurrency * 0.5)` and `currentConcurrency` resets to `1.0`.
- `currentConcurrency` is capped at `2.0` (the `MAX_CONCURRENCY` constant).
- `currentChunkSize` dynamically grows on success (up to `MAX_CHUNK_SIZE`) and
  shrinks on timeout (down to `MIN_CHUNK_SIZE`).
- New `MEDIA_REQUEST`s for additional chunks are only issued while
  `inflight.size < currentConcurrency.toInt()`.

This mirrors the algorithm documented for `hainet-social/src/congestion.rs`
and directly resolves the gap flagged in
[GAP_ANALYSIS.md §7](GAP_ANALYSIS.md#7-congestion-control-for-media-chunks--absent-in-noslop).
For full detail see
[WIRE_PROTOCOL_REFERENCE.md §5](WIRE_PROTOCOL_REFERENCE.md#5-media-packet-family-6-types).

### 6.2 Storage Layout

`getMediaDirectory(type)` resolves to
`context.getExternalFilesDir(<Pictures|Movies|Music|Downloads>)/NoSlop/`
based on a `type` prefix match (`"image"`, `"video"`, `"audio"`, else
Downloads). Falls back to `context.filesDir` if external storage is
unavailable.

**Temporary Processing Cache.** Media compression and stream copy buffers created
prior to post creation use `context.externalCacheDir ?: context.cacheDir`. Writing
large temporary files (> 20 MB) directly to internal `context.cacheDir` causes
Android's `installd` daemon to purge them during active operations when internal storage
quotas are reached. `externalCacheDir` is exempt from `installd` internal quota purging,
ensuring compressed media persists long enough to be moved to permanent `NoSlop` media directories.

### 6.3 Auto-Download Policy (`MediaManager.checkAndAutoDownload`)

Gated by `MediaSettings` (JSON in `app_settings["media_settings"]`):
- `settings.enabled` — global kill switch (UI label: "Automatic Media Download").
- `metadata.type == "file"` — **file attachments are never auto-downloaded**.
  They must be manually tapped by the user to initiate sync. This guard runs
  before any trust or size checks.
- **Trust-based hierarchy** (replaces the earlier context-string approach):
  1. Look up `repo.peerDao.getPeerByPublicKey(authorId)` to determine trust.
  2. If `peer?.isTrusted == true` (a contact): requires
     `settings.autoDownloadFriends` to be enabled.
  3. If the peer is unknown or untrusted (a public broadcast): requires
     `settings.autoDownloadPublic` to be enabled. Toggling this ON in the
     Settings UI triggers a confirmation dialog warning the user that they
     will download 3rd-party media.
- `settings.maxFileSizeMB` — if `metadata.size > maxBytes && size > 0`, skip
  (a `size == 0` placeholder, as used by `MediaProxyService`'s synthetic
  metadata, bypasses this check). The Settings UI slider allows values from
  1MB to 1000MB (1GB), defaulting to 250MB.

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

The `BlurredImageBackground` composable (in `MediaComponents.kt`) renders
this thumbnail as both a blurred background fill and a centered foreground
image. The `error` and `fallback` painters are explicitly set to the Base64
thumbnail bitmap, ensuring the preview persists permanently if the high-res
clearnet URL is empty or fails to load (common for mesh-native posts).

### 6.6 Download Resume (`MediaManager.startDownload`)

Downloads survive app restarts. When `startDownload()` is called for a media
item that already has an existing `.part` file on disk:

1. If `0 < partFile.length() < totalBytes` — the download resumes from the
   existing byte offset. `contiguousBytes` and `nextRequestOffset` are set to
   `partFile.length()`, and the progress bar immediately shows the correct
   percentage.
2. If `partFile.length() >= totalBytes` — the part file is already complete
   and is finalized immediately without re-downloading.
3. If the part file is zero bytes — it is deleted and the download starts
   fresh.

This replaced the previous behavior where `startDownload()` unconditionally
called `dl.partFile.delete()`, forcing every download to restart from 0%
after an app kill/restart.

### 6.7 Media Compression Pipeline

Media attachments are automatically compressed before being stored in the
mesh media directory and broadcast to peers. This runs in both the feed post
composer (`UnifiedFeedTab.kt`) and the DM composer (`ChatThreadScreen.kt`):

**Video transcoding** (threshold: > 20MB):
- Uses `VideoCompressor.compressVideo()` (Media3 Transformer) to transcode
  the video into a smaller format.
- A "Compressing... X%" progress indicator is shown during transcoding.
- The compressed file replaces the original attachment before
  `copyFileToMediaDirectory` is called.

**Image compression** (threshold: > 500KB):
- The image is decoded into a bitmap and proportionally scaled down to fit
  within a 1280×1280 pixel bounding box.
- Re-compressed as JPEG at 75% quality.
- The compressed file is only used if it is actually smaller than the
  original (failsafe for already-optimized images).

In the DM composer, `buildMediaMetadata()` is a `suspend` function that runs
on `Dispatchers.IO`, with a coroutine launched from `rememberCoroutineScope()`
on the send button click. This prevents the UI from freezing during
compression of large files.

---

## 7. Clearnet Aggregator

### 7.1 HTTP Client Separation (`net/HttpClientProvider.kt`)

Per the `01-clearnet-aggregator.md` architecture proposal (now implemented):
- `rawClearnetClient: OkHttpClient` — no proxy, used for direct network fetches avoiding Tor (such as background update checks). Configured with a cascading DNS (System -> Cloudflare DoH -> Google DoH) to avoid DNS resolution failures.
- `torClient: OkHttpClient` — SOCKS5 proxy `127.0.0.1:TOR_SOCKS_PORT` (9050 for release, 9052 for debug), used by `MeshTransport` and mesh data paths.
- `activeClearnetClient: OkHttpClient` — dynamic proxy client. Returns `torClient` if the user has enabled "Use Tor for Clearnet" (default: true), otherwise returns `rawClearnetClient`. Used by `FeedParser` and `feeds/api/*Client` classes.

*Note on Cloudflare Worker API Proxy:* Because API providers (YouTube, Reddit, Jamendo) actively block Tor exit nodes, requests from `YouTubeInternalClient`, `RedditApiClient`, and `JamendoApiClient` are routed through a Cloudflare Worker proxy (`yt-proxy.megadreamland.workers.dev`) when utilizing `activeClearnetClient`. This bypasses IP blocks over Tor. For details on proxy secret usage, direct fallback behavior, and media stream byte separation, see §15.

### 7.2 Source Library (`SourceLibrary.kt`)

16 categories: Technology, Privacy & Security, Self-Hosting, Science, World
News, Open Source, Video Platforms, Social Clearnet, Lifestyle, Gaming,
Health, Automotive, Art, Photography, Music, Reddit.

~50 hardcoded RSS/Atom sources (`feedType ∈ {"rss","atom"}`) plus 14
API-backed virtual sources (`feedType = "api"`, `url` field is a service
identifier like `"youtube:trending"`, `"reddit:multi"`, `"nasa:apod"` —
**not** an actual URL).

### 7.3 API Client Roster (`feeds/api/`)

`PublicApiService.fetchItemsForCategory` is a big `when(category)` dispatcher
using a `supervisorScope` with `async/awaitAll` — each category launches
concurrent `fetchAsync(sourceId) { ... }` calls, where `fetchAsync` is a
no-op if `sourceId` is not in `activeApiSourceIds` and catches/logs
exceptions so one client failure doesn't block the others. Results are
`distinctBy { it.id }` deduplicated.

**Search recency filtering**: All user-initiated search categories
(`Search Videos`, `Search Audio`, `Search Images`, `Search Articles`, and
the `else` fallback) pass `recentOnly = true` to their respective API
clients, applying native date filters (YouTube: this year via protobuf
`params`; Reddit: `t=year`; NewsAPI/Guardian: last 3 months via date
params). Category-based feeds (Technology, Science, etc.) are unaffected
and continue using natural trending/hot sorting.

| Client | Auth | Notes |
|---|---|---|
| `YouTubeInternalClient` | none | Primary YouTube integration via InnerTube API. Marked `requiresUserKey = false` in `ApiKeyRepository.SERVICES` (InnerTube proxy & direct fallbacks). Bypasses PoToken using TVHTML5 and native Android/iOS client spoofing. Prioritizes HLS streams for native ExoPlayer playback. |
| `InvidiousApiClient` | none | Legacy YouTube fallback via Invidious instance pool. Routed through the Tor SOCKS proxy whenever `HttpClientProvider.useTorForClearnet` is set (`probeClientTor`), falling back to a direct client only when the user has turned Tor routing off. Instances are **raced**, not walked: batches of `RACE_WIDTH` (4) are queried in parallel and the first usable answer wins. Losing racers are cancelled through OkHttp `enqueue`/`Call.cancel` so their Tor circuits are released immediately rather than pinned until the read timeout, and a cancelled racer is not marked failed — otherwise every race would blacklist three healthy instances. |
| `RedditApiClient` | none | `fetchSubreddit(sub, sort)`, `searchReddit(query, recentOnly)` — decodes `&amp;` preview URLs and preserves article classification for link/text posts |
| `InternetArchiveClient` | none | `getPopularVideos()`, `getPopularAudio()`, `searchAudio(query)` — supports keyless MP3/FLAC music and podcast browsing |
| `OpenverseApiClient` | none | `searchAudio(query)`, `searchImages(query)` — CC-licensed audio and photography, 5 min rate-limit cooldown |
| `NasaApiClient` | optional (DEMO_KEY works) | `fetchAPOD()`, `searchImageLibrary(query)` |
| `JamendoApiClient` | none (public client ID) | `searchTracks(query)` — CC-licensed music |
| `PexelsApiClient` | user key required | photos/videos, skipped silently if no key |
| `NewsApiClient` | user key required | headlines + search, supports `language` and `recentOnly` params |
| `GuardianApiClient` | user key required | `searchArticles(recentOnly)`, `searchSection` |
| `VimeoApiClient` | user key required | `fetchFeatured` |
| `PodcastIndexClient` | user key required | `searchEpisodes`, supports `language` param |

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

- `UnifiedFeedTab.kt`'s `FullScreenMeshCardV2` renders Like/Share/Comment overlays
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
- Mesh broadcasts in the composer default to `friends` privacy. Explicitly selecting `public` triggers a warning dialog to educate the user that public posts will be gossiped over daisy-chained peers beyond direct friends.

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
6. **Connectivity blip resilience**: transient network interruptions (e.g.,
   Wi-Fi ↔ mobile data switches) no longer force the Tor state to `FAILED`.
   Previously, the `STATUS_OFF` broadcast handler would set `FAILED` and
   trigger a full daemon restart, which caused `SIGABRT` crashes in
   `libtor.so` (the native Tor library does not tolerate being forcefully
   killed mid-circuit). The handler now allows the daemon to recover
   gracefully on its own, only surfacing `FAILED` if it cannot re-establish
   circuits after the bootstrap loop's timeout.

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

## 10. Data Model (Room, version 8)

| Entity / Table | Primary Key | Notable Fields | Indices |
|---|---|---|---|
| `feed_sources` | `id` | `url` (unique), `title`, `feedType`, `category`, `lastFetchedAt`, `unreadCount`, `isActive`, `addedDuringOnboarding`, `channelCreatedAt` | unique on `url` |
| `feed_items` | `id` | `sourceId`, `title`, `url`, `excerpt`, `thumbnailUrl`, `publishedAt`, `isRead`, `isSaved`, `fullContent`, `mediaUrl`, `mediaType`, `apiSource`, `channelCreatedAt` | on `sourceId` |
| `peers` | `publicKeyB64` | `handle`, `tripcode`, `onionAddress`, `encPublicKeyB64`, `isTrusted`, `lastSeenAt`, `customFolder`, `isTemporary`, `isDiscoverable`, `isCreator`, `fundMeLink`, `bio` | — |
| `mesh_posts` | `id` | `authorPublicKeyB64`, `authorHandle`, `authorTripcode`, `content`, `timestamp`, `signature`, `mediaUrl`, `mediaType`, `gossipCount`, `privacy`, `thumbnailB64`, `clearnetUrl`, `clearnetTitle`, `clearnetThumbnailUrl`, `clearnetMediaType`, `mediaSize`, `deletionBroadcasts` | — |
| `chat_messages` | `id` | `chatWithPeerPub`, `senderPub`, `ciphertext`, `nonce`, `timestamp`, `isRead`, `mediaId`, `mediaType` | on `chatWithPeerPub`, on `timestamp` |
| `mesh_comments` | `id` | `postId`, `authorPublicKeyB64`, `authorHandle`, `content`, `timestamp`, `signature`, `parentCommentId`, `mediaId`, `mediaType` | on `postId` |
| `mesh_reactions` | `id` (format `"${postId}_${authorPubKey}_${reactionType}"`) | `postId`, `authorPublicKeyB64`, `reactionType`, `timestamp`, `signature` | — |
| `mesh_votes` | `id` (format `"${postId}_${authorPubKey}_${voteType}"`) | `postId`, `authorPublicKeyB64`, `voteType`, `timestamp`, `signature` | Separates upvotes/downvotes from emoji reactions |
| `comment_votes` | `id` (format `"${commentId}_${authorPubKey}_${voteType}"`) | `commentId`, `authorPublicKeyB64`, `voteType`, `timestamp`, `signature` | Votes scoped to comments |
| `viewed_history` | `id` (auto-gen) | `itemId`, `itemType`, `viewedAt` | `itemId` |
| `swipe_tracker` | `itemId` | `swipeCount`, `lastSwipedAt` | — |
| `app_settings` | `key` | `value` (string, often JSON) | — |

`app_settings` is the catch-all KV store for: identity public data
(`local_*`), onboarding/session flags, `media_settings` (JSON),
`mesh_filter_settings` (JSON — see §4.6), per-category
keyword lists (`keywords_<Category>`), `selected_categories`,
`selected_music_genres`, `selected_video_genres`, `negative_keywords`,
`language_preference`, `creator_keywords`, `banned_channels` (comma-separated blacklist),
`channel_cutoff_enabled`, `channel_cutoff_year`, `channel_cutoff_month`,
`enable_aggregator`, `user_profile` (JSON), `dm_all_tab_hidden` (`"true"`/`"false"`).

Database migrations (`MIGRATION_1_2` through `MIGRATION_7_8`) safely preserve data across schema updates. `MIGRATION_7_8` adds the optional `channelCreatedAt` timestamp to `feed_items` and `feed_sources`.

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
| `minSdk` | 24 (`app/build.gradle.kts`) — matches [BUILD.md](BUILD.md), which previously stated 26; that doc has been corrected to 24. |
| `versionCode` / `versionName` | 1 / `0.1.0` |
| ABIs | `armeabi-v7a, arm64-v8a, x86, x86_64` (`useLegacyPackaging = true` for jniLibs — required by `tor-android`) |
| Java/Kotlin target | 11 |
| Compose | enabled, via Compose BOM |
| Signing | `release` build type reads `NOSLOP_STORE_FILE`/`NOSLOP_STORE_PASSWORD`/`NOSLOP_KEY_ALIAS`/`NOSLOP_KEY_PASSWORD` Gradle properties; `debug` uses the default debug keystore |
| ProGuard | `release` has `isMinifyEnabled = true`, `isShrinkResources = true`, plus hardened `-keep`/`-dontwarn` rules for `tor-android`, `jtorctl`, `netcipher` (milestone 22) |
| `GITHUB_PAT` | Read from `local.properties` at build time. Exposed as `BuildConfig.GITHUB_PAT`. When non-blank, enables in-app GitHub issue submission via REST API (`POST /repos/gaborkukucska/NoSlop/issues`). When blank, the Submit button in `ReportIssueScreen` is disabled. |
| `GITHUB_ASSIGNEE` | Read from `local.properties` at build time. Exposed as `BuildConfig.GITHUB_ASSIGNEE`. When non-blank, auto-assigns the specified GitHub user to all submitted issues. |

### Key Dependencies

- **Tor**: `info.guardianproject:tor-android:0.4.8.16`,
  `info.guardianproject:jtorctl:0.4.5.7`,
  `info.guardianproject.netcipher:netcipher:2.1.0`
- **Crypto**: `org.bouncycastle:bcprov-jdk15to18:1.78.1`, `com.goterl:lazysodium-android:5.1.0`, `net.java.dev.jna:jna:5.13.0`
- **Networking**: `okhttp:4.10.0` (`gradle/libs.versions.toml`),
  `okhttp-dnsoverhttps:4.12.0` (hardcoded in `app/build.gradle.kts`) — this is
  a genuine, currently-real minor version mismatch between the two OkHttp
  artifacts (confirmed by reading both files directly), `gson:2.10.1`
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
notifications), `CAMERA` (optional feature, for QR scanning),
`RECEIVE_BOOT_COMPLETED` (WorkManager rescheduling), `FOREGROUND_SERVICE` and
`FOREGROUND_SERVICE_DATA_SYNC` (required for `NoSlopForegroundService`, see
§11). `android:allowBackup="false"` — OS-level backup is disabled in favor of
the app's own encrypted export. Two services are declared, both
non-exported: `org.torproject.jni.TorService` and
`.mesh.NoSlopForegroundService` (`foregroundServiceType="dataSync"`). A
custom `network_security_config.xml` is referenced for strict TLS with
whitelisted cleartext exceptions (milestone 8).

---

## 13. Future Architecture: HUBs / HAI-Net Hub Client

While NoSlop currently operates as a standalone, self-contained node running its own embedded Tor daemon, future iterations of the HAI-Net ecosystem conceptualize a "Local Hub" mesh (e.g., desktops or NAS devices acting as always-on master nodes).

In this architecture, NoSlop serves as a `SLAVE_FRONTEND` client to a user's **HUB**. This Home HUB acts as the primary sovereign backup for the user's mesh Identity, encrypted data, and media library. Instead of maintaining a full local mesh stack and embedded Tor daemon on mobile, the app connects directly to the user's remote HUB via a private, authenticated onion address. This model aligns with gChat's dual hidden service architecture and is the planned avenue for long-term scalability and data persistence. The concrete, phased implementation plan for this transition lives in [HUB_INTEGRATION_PLAN.md](HUB_INTEGRATION_PLAN.md); Phases 1 and 2 (Headless Deployment, Authentication, and Active-Passive Tor Routing) are fully implemented:
- **Active-Passive Identity**: NoSlop dynamically disables its local Tor hidden service when a Hub is linked, falling back to treating its embedded Tor daemon purely as an outbound SOCKS5 proxy to reach the Hub's persistent `.onion` API endpoint (`invokeHubApi`).
- **Intelligent Edge Relay**: The Hub is *not* a dumb buffer. NoSlop periodically pushes its Contacts list to the Hub (`sync_push_peers`). The Hub listens on Port 9999, runs incoming traffic through its native `GossipEngine` and `TrustFirewall` (dropping spam and expired TTLs at the edge), and holds only valid packets in memory for the mobile app to securely poll (`sync_pull_packets`).
- **Phase 3 (Active)**: SQLite persistence is now active on the Hub (`social.db`). NoSlop performs bi-directional sync, pushing live mesh packets and pulling historical DMs, Posts, and Contacts down to the mobile client on connect. The mobile app automatically injects an `Admin AI` peer contact mapped to the Hub's node ID, allowing seamless E2EE interaction with the Hub's persona agent.

---

## 14. Known Discrepancies Between Documentation and Code

(Cross-referenced in more detail in [GAP_ANALYSIS.md](GAP_ANALYSIS.md).)

Several discrepancies previously tracked here have since been **resolved in
the code or in the relevant doc** — kept below, struck through, so the fix
is traceable rather than silently disappearing:

1. ~~README's "End-to-end encrypted DMs" prose says AES-256-GCM; Tech Stack
   table says ChaCha20-Poly1305.~~ **Fixed** — the README prose now also says
   ChaCha20-Poly1305; both sections agree.
2. ~~`CryptoService.kt`'s class KDoc says DM crypto is "ECDH (P-256) ... ->
   SHA-256 -> AES-256-GCM".~~ **Fixed** — the KDoc now correctly reads
   "X25519 key agreement -> SHA3-256 -> ChaCha20-Poly1305", matching §3.5.
3. `docs/PROJECT_STATUS.md` milestone 14 describes MeshTransport retries as
   "up to 3 times with 2s/4s backoff"; the code implements 5 attempts with
   `attempt*3000ms` backoff (see §4.1). **Still open** — PROJECT_STATUS.md's
   milestone log is a historical record of what shipped *at the time* and
   isn't being retroactively edited; §4.1 above is the authoritative current
   description.
4. ~~`docs/archived/ANALYSiS.md` item 6 states `CookieAuthentication 0`;
   `TorService.writeTorrc` writes `CookieAuthentication 1`, while
   `registerHiddenService` authenticates with a bare `AUTHENTICATE\r\n` (no
   cookie).~~ **Resolved 2026-09-02** — the torrc did write
   `CookieAuthentication 0`, and the bare `AUTHENTICATE` matched it, which is
   why control worked and why any app on the device could also use it. All
   control access now goes through `TorControlChannel`; see §17.1. Empty
   authentication is retained deliberately, with socket file permissions as the
   access control.
5. ~~`docs/BUILD.md` states `minSdk = 26`; `app/build.gradle.kts` sets
   `minSdk = 24`.~~ **Fixed** — BUILD.md now states 24.
6. ~~`docs/PACKET_SCHEMA.md`'s `POST` field table omits
   `clearnet_thumbnail_url`.~~ **Fixed** — PACKET_SCHEMA.md now includes it
   (and `author_avatar_b64`, which was also missing).
7. README's "Implemented" callout under Clearnet-to-Mesh Broadcasts now
   correctly states that the `REACTION`/anchor-ID pipeline is live — this
   item, previously flagged as the README being out of date, **is fixed**.
8. ~~The `okhttp` (4.10.0) vs `okhttp-dnsoverhttps` (4.12.0) version
   mismatch noted in §12.~~ **Fixed 2026-09-02** — the catalog is aligned on
   4.12.0 and `okhttp-dnsoverhttps` is declared there rather than hardcoded.
9. The `mvp/` tree is not in `settings.gradle.kts` and does not build. README
   previously called it the canonical codebase; that claim is corrected, but
   the directory is still 2.8MB of dead weight in the repo. **Open.**
9. ~~This document's §2 package-layout table and architecture diagram listed
   `NoSlopDatabase.kt` as Room "version 16" / "version 20" in two places,
   while §10 and the header correctly said v5.~~ **Fixed** — both now read
   v5, matching `@Database(version = 5, ...)`.
10. ~~§4.4's dispatch table and §5.2's payload-type table described an
    earlier ~11-handler, single-file version of `MeshPacketHandler` and were
    marked "Superseded" pointing elsewhere for current info, while
    `docs/PACKET_SCHEMA.md` separately duplicated a partial (8-type) field
    catalog.~~ **Fixed** — `docs/PACKET_SCHEMA.md` has been merged into
    `WIRE_PROTOCOL_REFERENCE.md` (now the single complete catalog, 24 types),
    and §4.4/§5.2 here were rewritten as short pointers to it instead of
    maintaining a second, drifting copy. The merge also corrected several
    factual errors carried by both old docs: the `IDENTITY_UPDATE` signed
    string uses the payload's actual `handle` field, not `displayName`;
    `EDIT_POST`/`DELETE_POST` signed strings include `authorId`, which both
    docs previously omitted; `ANNOUNCE_PEER`'s signed string
    (`authorId|timestamp`) is directly resolvable from `MeshSocialRepository`
    and isn't actually "unconfirmed" as previously written; and the presence
    heartbeat *does* actively flip `Peer.isOnline = false` on a 3-minute
    timeout sweep, contrary to a prior claim that Room was never written back
    to on timeout.
11. ~~GAP_ANALYSIS.md §6 stated relay nodes have no zero-copy chunk
    forwarding and would "download the whole file rather than acting as a
    pass-through."~~ **Fixed** — `MediaPacketHandler.handleMediaChunk` calls
    `GossipService.forwardRelayChunk`, which live-forwards each chunk to all
    registered relay listeners. See
    [WIRE_PROTOCOL_REFERENCE.md §6.2](WIRE_PROTOCOL_REFERENCE.md#62-zero-copy-chunk-forwarding--implemented).

---

## 15. Privacy & Security Architecture & Implemented Hardening Measures

An architectural audit of the legacy Android app codebase (`app/`) evaluated the privacy and security guarantees of key components, resulting in comprehensive security hardening implementations across the framework:

### 15.1 Cloudflare Worker API Proxy (`yt-proxy.megadreamland.workers.dev`)
- **Purpose**: API clients (`YouTubeInternalClient`, `RedditApiClient`, `JamendoApiClient`) send search/metadata requests to `https://yt-proxy.megadreamland.workers.dev` to prevent API providers from blocking Tor exit node IP ranges.
- **Dynamic HMAC Request Signing**: Standard requests generate dynamic HTTP headers `X-Proxy-Timestamp` and `X-Proxy-Signature` (`HMAC-SHA256(timestamp:payload, PROXY_SECRET)`), eliminating reliance on raw static secrets.
- **Tor Circuit Cycling & Fallbacks**: If proxy requests return HTTP 403 or 429 rate limit responses over Tor, `YouTubeInternalClient` triggers `TorService.requestNewCircuit()` (`SIGNAL NEWNYM`). When `useTorForClearnet = true`, all direct fallbacks strictly execute through `activeClearnetClient` (routed over Tor SOCKS5).
- **API Metadata vs. Media Stream Bytes**: Cloudflare Worker only proxies API JSON queries. Actual video/audio stream playback bytes (`googlevideo.com`, `jamendo.com`, `v.redd.it`) bypass `yt-proxy` and are fetched directly via `activeMediaClient` (`activeClearnetClient`).

### 15.2 OTA Update Check & Release Integrity Validation
- **Integrity Checks**: `UpdateManager.kt` computes the full SHA-256 digest of the downloaded APK before calling `launchInstaller()`, verifying file checksums before triggering `ACTION_VIEW` package installation.
- **Network Path & Tor Routing**: `UpdateChecker.kt` and `UpdateManager.kt` use `HttpClientProvider.activeClearnetClient` (routed over Tor SOCKS5 when "Route Clearnet via Tor" is enabled, after waiting for Tor bootstrap).
- **User Toggle**: An "Automatic Update Checks" switch in Settings allows users to completely disable automatic update checking.

### 15.3 Identity Key Isolation & Fallback Storage Hardening
- **Primary Storage**: Private Ed25519 signing keys, X25519 encryption keys, and BIP39 mnemonics are stored in `EncryptedSharedPreferences` (AES-256-GCM encrypted, MasterKey in Android Keystore).
- **Hardened Fallback Storage**: On devices where Android Keystore fails (e.g. custom ROMs or broken OEM Keystore implementations), `IdentityRepository.kt` encrypts private key strings and mnemonics in memory with `AES-256-GCM` (`secureFallbackWrite` / `secureFallbackRead` using hardware-derived secret key) before writing to `noslop_identity_fallback` `SharedPreferences`.
- **Authenticated Backup Encryption**: `BackupManager.kt` exports database and preferences using `AES-256-GCM` with a 4-byte `"NSG1"` magic header, a 12-byte random IV, and a 128-bit AEAD tag. On import, `BackupManager.kt` automatically detects `"NSG1"` for GCM decryption while maintaining backward compatibility for legacy `AES-256-CBC` archives.

### 15.4 Mesh Transport & Peer Failure Cooldown
- **Exponential Cooldown Backoff**: `GossipService.kt` enforces exponential backoff (`30s * 2^(failures - 3)`, up to 1 hour) on peer send failures, preventing dead or unreachable onion addresses from continuously consuming Tor circuit permits.
- **Non-Blocking Background Traffic**: `MeshTransport.kt` queues `ANNOUNCE_DISCOVERABLE` alongside `ANNOUNCE_PEER` as non-blocking background traffic, dropping queued background announcements when Tor circuit permits are full.

### 15.5 Strict Tor Isolation & Zero Clearnet Fallback Policy
- **Tor Guard Interceptor**: When `useTorForClearnet = true`, `HttpClientProvider.torClient` and `InvidiousApiClient.probeClientTor` enforce a `torGuardInterceptor`. If `TorService.torState.value != TorState.READY` (Tor is disconnected/starting/failed), all outbound HTTP/HTTPS requests throw an immediate `IOException`, preventing any packet transmission or unproxied socket initialization when Tor is offline.
- **WebView Embed Prohibition**: When `useTorForClearnet = true`, `VideoPlayer.kt` strictly blocks `VideoSource.Embed` (WebView players for YouTube/Vimeo/Archive.org). Android system `WebView` bypasses OkHttp/SOCKS proxies, so embeds are converted to `VideoSource.Unavailable` to prevent direct IP leaks to content platforms.
- **Coil Image Loader Isolation**: All Coil `ImageLoader` instances (app-wide and custom GIF loaders in `ChatThreadScreen.kt` and `GroupChatThreadScreen.kt`) configure `.okHttpClient { HttpClientProvider.activeClearnetClient }`, ensuring thumbnail and image traffic follows Tor routing and strict Tor guard interceptors.
- **Verified Private LAN Guard**: In `NoSlopRepository.kt`, unproxied `rawClearnetClient` is strictly restricted to verified RFC1918 private IPv4 LAN addresses (`192.168.x.x`, `10.x.x.x`, `172.16-31.x.x`, `127.0.0.1`). Any attempt to connect to non-private WAN IP addresses via `rawClearnetClient` is rejected.

---

## 16. Video Playback Failure Modes

Video playback spans four layers — stream resolution, preloading, ExoPlayer,
and the poster overlay — and a fault in any of them surfaced identically to
the user: the slide sat on its thumbnail and never played. The notes below
record why, so the same symptom is not re-diagnosed from scratch.

### 16.1 Poster overlay occluded the error UI (`NOSLOP_FAILURE_VISIBILITY_V1`)

`VideoPlayer` composes its children into a single `Box`:

| Layer | zIndex | Drawn by |
|---|---|---|
| Player / error card | 0 (3 when failed) | `when (source)` branch |
| Poster thumbnail | 1 | `showThumbnail` block |
| "Finding stream" / "Buffering" spinner | 2 | `VideoLoadingOverlay` |

`showThumbnail` previously included `source is VideoSource.Unavailable`, and
`isVideoReady` is never set when playback fails. So on **every** failure path
the app composed a correct "Video unavailable" card with a working Retry
button at zIndex 0 and then painted the thumbnail over the top of it. The
card was reachable by touch but completely invisible.

Additionally, `ExoVideoPlayer` renders its own "Video unavailable / Retry
Playback" card one level deeper in the tree, where `zIndex` cannot outrank a
sibling of its parent.

Fixed by:
- hoisting hard playback failure into the parent via a new `onFailed` callback
  on `ExoVideoPlayer` (raised from a single `LaunchedEffect(hasError)`),
- lifting the `ExoVideoPlayer` subtree to `zIndex(3f)` once it has failed, and
  putting the `Unavailable` card at `zIndex(3f)` directly,
- keeping the poster as a dimmed backdrop (60% `PrimaryBlack` scrim) rather
  than an opaque cover, and
- suppressing the loading spinner once `hasFailed` is true, so it no longer
  spins for the full 45s `loadingTimedOut` window over a slide that is
  already known to be dead.

**Any future overlay added to this `Box` must declare a zIndex** or it will
land underneath the poster.

### 16.2 Un-decipherable signature ciphers (`NOSLOP_CIPHER_SANITY_V1`)

`YouTubeInternalClient.extractFormatStreamUrl()` parses `signatureCipher`
entries from the InnerTube player response. Those carry either:

- `sig` / `signature` — already plaintext, appendable as-is; or
- `s` — the **encrypted** signature, which must be transformed by the
  algorithm embedded in YouTube's player JavaScript before use.

The parser treated `s` as interchangeable with `sig`. The resulting URL is
syntactically perfect and returns **403 on the first byte-range request**.
Critically, this counted as a *successful* resolve: the slide received a
`VideoSource.Direct`, ExoPlayer failed, both `MAX_AUTO_RESOLVE_RETRIES` were
spent re-resolving the identical dead URL, and the slide gave up — never
reaching the next InnerTube client or the Invidious/Piped failover, both of
which return pre-signed URLs.

Formats that only offer `s` are now skipped with a `YT_INTERNAL_API` warning,
so resolution falls through as designed. NoSlop does not, and should not,
execute YouTube's player JS to decipher these.

### 16.3 YouTube IFrame embed never started (`NOSLOP_EMBED_AUTOPLAY_V1`)

The embed's `onReady` handler was gated on `window.NoSlop_isVisible`, a
variable **nothing in the codebase ever assigns**. It was permanently
`undefined`, so:

1. `playVideo()` never ran (`playerVars.autoplay` is `0`),
2. `onStateChange` never reached `PLAYING`,
3. `NoSlopJS.onPlaying()` never fired,
4. `isVideoReady` stayed `false`, and the poster covered the player forever.

The autoplay decision is now injected as a Kotlin-interpolated JS literal
(`$shouldAutoplayEmbed`, from `currentIsVisible`), and `onPlaying()` fires as
soon as the player is *constructed* rather than only once it is playing — so
if autoplay is refused, the poster still lifts and the user can see and press
the embedded play button.

Note this path is unreachable while **Route Clearnet via Tor** is on: §15.5
converts every `VideoSource.Embed` to `Unavailable` to prevent the WebView
leaking the device IP. With Tor routing enabled, a YouTube video that cannot
be resolved to a direct stream is correctly surfaced as unavailable (and, per
§16.1, now visibly so).

### 16.4 The resolver must not sabotage the player

`SIGNAL NEWNYM` is process-wide. It discards **every** circuit, including
the one ExoPlayer is currently streaming through. A capture on 2026-08-31
showed eighteen rotations in sixty-three seconds, and every stalled video in
that window sat at a flat buffer for the whole of it.

The cause was unbounded concurrency meeting unbounded rotation:

- `PreloadManager` warms upcoming slides while `VideoPlayer` resolves the
  visible one, so `resolveStreamUrl` ran ~10 times at once,
- each call did up to `maxAttempts` (4) x `configs` (2) = 8 InnerTube player
  requests, and rotated the Tor circuit between attempts,
- ~80 near-simultaneous requests from a single API-proxy egress IP pushed
  YouTube from `OK` into blanket `LOGIN_REQUIRED`, which triggered *more*
  rotations.

Three constraints now hold, and **new callers must respect them**:

| Constraint | Where | Value |
|---|---|---|
| One rotation at a time, process-wide | `TorService.requestNewCircuit` | mutex, non-blocking `tryLock` |
| Minimum gap between rotations | `TorService.NEWNYM_MIN_INTERVAL_MS` | 60s |
| Concurrent InnerTube player resolves | `YouTubeInternalClient.playerResolveGate` | 2 |

`requestNewCircuit()` returning `false` is a normal outcome, not an error: it
means "this route is what you have, try something else". Do not loop on it.

### 16.5 Geo-locked and IP-locked stream URLs (`NOSLOP_GEO_LOCK_V1`)

A resolved `googlevideo.com` URL carries the identity of whoever requested
it:

- `ip=<addr>` — the address the URL was signed for,
- `gcr=<cc>` — a country restriction, present when the requesting IP was in
  a region-restricted context.

NoSlop resolves through the API proxy (so YouTube does not see a Tor exit)
but fetches the media bytes directly over Tor. Those are different machines
in different countries, so a signed URL can be refused on fetch. In the
2026-08-31 capture the only hard 403 was also the only URL carrying `gcr`;
no URL without `gcr` was refused.

`resolveStreamUrl` now sets a `gcr`-bearing URL aside, tries the remaining
InnerTube clients and the Invidious/Piped failover first, and only returns
the geo-locked URL if nothing else resolved. `describeStreamUrl` reports
`signedFor=` and `geoLock=` so a geo-lock 403 is distinguishable from an
expired URL in the log.

This is a structural consequence of the privacy design (§15.5) and cannot be
fixed by routing the media through the proxy — that would hand a third party
the user's full viewing history. Occasional geo-locked failures are the
correct trade.

### 16.6 Resume positions must reflect real playback (`NOSLOP_RESUME_POISON_V1`)

`ExoPlayer.currentPosition` returns the **pending seek target** while
buffering, and `duration` is `C.TIME_UNSET` until the media prepares. The
old `PlaybackPositionStore.save()` accepted both, so a slide that never
loaded repeatedly saved its own resume offset — and because the
near-the-end cleanup is guarded on `durationMs > 0`, an offset near the end
of a video could never be cleared either. `205swuI0JlY` was pinned at
891343ms of a 946-second video across every visit.

Two rules now:

- `save()` rejects anything with `durationMs <= 0` — no duration means the
  player never prepared, so there is no real progress to record.
- `ExoVideoPlayer` clears the stored position on hard failure when the media
  never prepared, so a retry starts from zero instead of seeking back into
  the byte range that was already failing.

### 16.7 Telling "no network" apart from "broken feature"

Before investigating any media failure, check whether *anything* is reaching
the network. The signature of a dead path is uniform failure across
destinations that share nothing but Tor:

- `.onion` mesh peers timing out (these use no exit node),
- `check.torproject.org` failing **both** the primary and fallback probes,
- unrelated clearnet hosts — RSS feeds, GitHub, archive.org — all timing out
  at the same interval.

When that pattern is present, no amount of stream-resolution work will help.
A 2026-08-31 capture showed exactly this while Tor reported `ON` and
"Circuits built": the SOCKS port accepted connections, but nothing those
circuits carried arrived.

Two changes make this state cheap and visible rather than a silent four-minute
hang:

| Change | Where | Effect |
|---|---|---|
| Connect timeout 60s → 20s | `HttpClientProvider.torClient` | failures surface in seconds, not minutes |
| Failed routing probe sets the status message | `TorService` | user is told, instead of watching "Preparing your feed…" |

`readTimeout` deliberately stays at 60s: media streaming over a slow circuit
needs it, and read time was never what was hanging. And note that the old
"60s for better mesh reliability" rationale was simply wrong —
`MeshTransport` opens raw SOCKS sockets and never touches `torClient`.

### 16.8 Resolve concurrency must be bounded at BOTH ends (`NOSLOP_RESOLVE_BUDGET_V1`)

`playerResolveGate` exists to stop the LOGIN_REQUIRED storm described in
§16.4, and it is correct while the network works. Unbounded, it is actively
harmful when the network does not: with a 60s connect timeout, one resolve
held a permit for `maxAttempts` x `configs` x 60s ≈ four minutes, and every
later slide queued behind it showing nothing at all. Slides that would
previously have failed in parallel instead sat waiting.

Any future throttle on a user-visible path needs all three of these:

| Bound | Constant | Value |
|---|---|---|
| How long a caller waits for a slot | `RESOLVE_QUEUE_WAIT_MS` | 20s, then returns null |
| How long the work may hold the slot | `RESOLVE_BUDGET_MS` | 45s |
| How long one HTTP call may take | `playerClient` `callTimeout` | 20s |

`callTimeout` matters specifically: it is the only OkHttp timeout that bounds
a whole call, and unlike `withTimeout` it actually interrupts the blocking
socket rather than merely abandoning the coroutine while the thread stays
stuck.

A bounded refusal is a *better* outcome than a queue. Returning null makes
the slide show its error card and Retry button (§16.1); queueing makes it
show nothing.

### 16.9 Tor readiness must be proven, never assumed (`NOSLOP_BOOTSTRAP_TRUTH_V1`)

`org.torproject.jni.TorService` broadcasts `STATUS_ON` when the daemon
**process** is running. It carries no information about circuits.
`torStatusReceiver` used to treat it as proof, set `TorState.READY`, and log
"Tor is ON — Circuits built" — a claim the code had not earned. On
2026-08-31 that happened eleven seconds after launch, `torGuardInterceptor`
opened, feed sync dispatched ~40 concurrent requests into a Tor that was
still bootstrapping, and every one of them blocked on a SOCKS CONNECT until
the connect timeout. Onion peers failed alongside clearnet, which is correct
— they need working circuits too — and that uniformity is what made it look
like a dead device network.

The correct check already existed and had never run. `waitForBootstrap()`
began with:

    if (_torState.value == TorState.READY) return@withContext true

so once the broadcast flipped READY, the next poll short-circuited and
returned true without asking Tor anything. `"Tor bootstrap reached 100%"`
appears in none of the captures from that period for exactly that reason.

The rule now:

| Signal | Means | Promotes to |
|---|---|---|
| SOCKS port accepts TCP | a process is listening on localhost | `PROXY_READY` |
| `STATUS_ON` broadcast | daemon process is alive | `PROXY_READY` |
| `PROGRESS=100` from the control port | circuits are actually built | `READY` |
| `checkTorConnection()` succeeds | routing works end to end | `READY` |

Only the bottom two are proof. Nothing else may set `READY`, because `READY`
is what `torGuardInterceptor` and `isNetworkReady` gate every outbound
request on.

`waitForBootstrap` now logs each change in `PROGRESS=`, so a stalled
bootstrap is one obvious line rather than an absence of lines, and the last
phase seen is reported on timeout.

### 16.10 Two installed variants means two Tor daemons

The 2026-08-31 capture had both `com.noslop.app` (SOCKS 9050) and
`com.noslop.app.debug` (SOCKS 9052) running Tor daemons on the same handset,
competing for the same guards and network. The debug APK was also stale — it
logged `Promoting state to READY` on SOCKS-port-open alone (a string no
longer in the source) and threw
`java.net.Socket cannot be cast to javax.net.ssl.SSLSocket`, the
`PassthroughSSLSocket` fault removed in 38931d9.

When collecting logs, uninstall the variant not under test. Two daemons make
timing measurements meaningless and a stale one will contradict the source
you are reading.

### 16.11 InnerTube client roster (`NOSLOP_INNERTUBE_CLIENTS_V1`)

`resolveStreamUrl` walks a list of InnerTube client identities until one
returns a playable response. Which identities are on that list is the single
biggest determinant of whether video works at all: a 2026-08-31 capture
resolved **zero** streams because both entries were being refused —
`LOGIN_REQUIRED` from ANDROID 4/4, `ERROR` from
TVHTML5_SIMPLY_EMBEDDED_PLAYER 4/4.

Ordered by attestation exposure, least-gated first:

| Client | PO token | Notes |
|---|---|---|
| `TVHTML5` | not required | Upstream's standard answer to "PO Token required". No `thirdParty` node — it is not the embedded variant. |
| `ANDROID_VR` | **format 18 only** | Fine because we only ask for itag 18/22. Requires a token for everything else. |
| `IOS` | not required | `osName` must be `iPhone`, not `iOS`. |
| `ANDROID` | **required** | Behind the gate now; succeeded repeatedly hours earlier. Demoted, not removed. |
| `TVHTML5_SIMPLY_EMBEDDED_PLAYER` | not required | Previous fallback, kept last. |

Three things that are easy to get wrong here:

- **`LOGIN_REQUIRED` does not mean the Tor exit is blocked**, despite what the
  log line says. It is the attestation gate. A genuinely blocked exit shows up
  as HTTP errors or timeouts, not as a well-formed playability status.
- **`signatureTimestamp` is not a unix time.** It is a small counter near
  20,000 published inside YouTube's player JavaScript. The old code sent
  `(currentTimeMillis() / 1000) - 86400` — about 1.79 billion — which is very
  likely why TVHTML5 never once succeeded. NoSlop cannot compute the real
  value without executing player JS, so the field is omitted.
- **The `ANDROID_VR` exemption is tied to format 18.** Widening
  `extractUrlFromPlayerResponse`'s format preference silently invalidates it.

**These versions go stale, and that is expected.** yt-dlp's youtube extractor
is the ground truth: it has a community hitting it daily and patched an
August 2026 `android_vr` break within a day of it landing. When video fails
wholesale and §16.7 shows the network is fine, compare this roster against
theirs before investigating anything else. The fix is always to add a client
that is not yet gated, never to try harder against one that is.

A durable escape from the roster treadmill would be generating PO tokens
on-device (BotGuard in `androidx.javascriptengine`, which has no network
access of its own, so every HTTP call stays on OkHttp over Tor — unlike a
WebView, which would bypass it and leak the device IP, cf. §15.5). That
carries its own privacy cost: a PO token is bound to a session identifier, so
reusing one across Tor circuits links them together. Not implemented, and not
to be implemented without deciding that trade deliberately.

### 16.12 Only muxed sources are playable (`NOSLOP_MUXED_ONLY_V1`)

A YouTube player response offers streams in three places, and only two of
them are usable:

| Source | Contains | Usable |
|---|---|---|
| `formats` | video **and** audio in one stream (progressive) | yes |
| `hlsManifestUrl` | muxed, adaptive bitrate | yes — `media3-exoplayer-hls` is on the classpath |
| `adaptiveFormats` | video-only **or** audio-only, by definition | **no** |

`extractUrlFromPlayerResponse` used to end with an `adaptiveFormats` branch
returning `valid.first().first`. That is the highest-bitrate video-only track
with no audio at all. The branch was unreachable while ANDROID still returned
progressive formats; the round-5 roster change made it reachable, and it
immediately produced `itag=299` at **1806MB** with no sound. Every affected
slide sat at `bufPos=0` indefinitely.

Muxing two streams requires a demuxer NoSlop does not have. A client that
offers neither progressive formats nor HLS has not given us anything
playable, and the correct response is to report an unresolved stream so the
caller falls through to the next client and then to the Invidious/Piped
failover, which return muxed URLs.

**`itag=18` is the format to expect.** Every working capture in this
project's history used it: 360p, muxed, small. If a `PLAYBACK_DIAG resolved
DIRECT` line shows itag 137, 248, 299, 303 or similar, something has
regressed here — those are DASH video tracks.

### 16.13 Stream size ceiling over Tor (`NOSLOP_TOR_SIZE_CEILING_V1`)

Progressive candidates over 250MB are rejected outright while
`useTorForClearnet` is on. Three relays deep, a 1.8GB file is not a slow
download but an impossible one, and accepting it burns the entire resolve
budget (§16.8) discovering that. The ceiling is checked against
`contentLength` from the player response, before the URL is ever handed to
ExoPlayer.

This is a safety net, not the primary defence — §16.12 is. It exists because
the primary defence had a gap for months without anyone noticing, and a size
sanity check would have caught it on its own.

### 16.14 The API proxy can be the thing being refused (`NOSLOP_PROXY_ATTESTATION_V1`)

The proxy exists so YouTube does not see a Tor exit IP. As of 2026-08-31 that
premise has inverted: one Cloudflare egress address serving every NoSlop user
is a **more** flagged IP than a fresh Tor exit, and YouTube answers it with
`LOGIN_REQUIRED`.

The 19:11 capture is unambiguous:

| | |
|---|---|
| Successful resolves before `Proxy returned 403 — bypassing it for 5m` | **0** |
| Successful resolves after it | **4** |

and every one of those four was `signedFor` a published Tor exit
(109.70.100.9, 176.65.148.3, 185.220.101.180, 45.66.35.27) — meaning the
request had gone direct. The app only found the working path by accident,
because an unrelated HTTP 403 happened to trip the bypass.

`notePlayerProxyBlocked` only fired on HTTP 403/429/400. `LOGIN_REQUIRED`
arrives as a successful **HTTP 200** carrying a `playabilityStatus`, so the
proxy could serve refusals forever without ever being marked bad. A proxied
`LOGIN_REQUIRED` now re-asks the same client directly over Tor within the
same iteration and marks the proxy degraded for subsequent videos.

Two consequences worth carrying forward:

- **This also relieves the worker's request ceiling.** Player calls that go
  direct do not consume the Cloudflare free tier at all.
- **The `gcr` geo-lock problem in §16.5 largely dissolves on the direct
  path**, because the IP that requested the URL and the IP fetching the bytes
  are then the same circuit rather than two different countries.

### 16.15 Rank clients by playable output, not by `OK`

Round 6 ranked the roster by how often each client returned
`playabilityStatus: OK`, and put IOS first on that basis. It was the wrong
metric. IOS answers `OK` and then offers *only* `adaptiveFormats`, which
§16.12 correctly refuses — so an IOS "success" produces nothing playable and
costs a round trip. Measured by playable muxed URLs, the same capture read:

| Client | Playable URLs |
|---|---|
| `ANDROID` | 3 |
| `TVHTML5` | 1 |
| `IOS` | 0 (OK, but adaptiveFormats only) |

**When re-ranking from a fresh capture, count `Resolved direct video stream
using X`.** Never count playability statuses — a client can pass that check
and still be useless.

### 16.16 The Tor exit lottery (`NOSLOP_EXIT_LOTTERY_V1`)

Resolve durations are bimodal, and that shape is the diagnosis. From the
2026-08-31 19:28 capture:

| Outcome | Durations |
|---|---|
| `DIRECT` | 520ms, 950ms, 1326ms, 3546ms, 3924ms, 3953ms, 4757ms, 5153ms, 5622ms, 8026ms |
| `UNAVAILABLE` | 30557ms, 34061ms, 40488ms, 41894ms, 49562ms, 55061ms, 56477ms, 60003ms, 64054ms |

Two clusters and nothing in between. There is no case of a later client
rescuing an earlier refusal: either the **first** client answers within
seconds, or all five are refused and the full budget is spent proving it.

That is a per-circuit condition, not a per-client or per-video one. All five
clients ask from the same Tor exit, so when YouTube has gated that exit they
all get `LOGIN_REQUIRED` together. Every successful resolve in that capture
was signed for one of a small set of exits.

**Practical rule:** if a capture shows a wide spread of resolve durations
with nothing in the middle, stop looking at client identities (§16.15) and
look at the exit. The client roster only explains failures that vary *by
client*.

`resolveStreamUrl` now stops after `EXIT_BLOCKED_THRESHOLD` (2) refusals in
one attempt and tries to rotate instead.

### 16.17 Rotation is gated on live media, not on the clock (`NOSLOP_ADAPTIVE_ROTATION_V1`)

§16.4 rate-limited `requestNewCircuit()` to once per 60s after a rotation
storm destroyed the circuit a visible video was streaming on. That was right
about the danger and wrong to apply it unconditionally: a fresh exit is the
*only* remedy for §16.16, and the flat gate was blocking it.

The discriminator is whether bytes are moving, not how much time has passed:

| Condition | Minimum interval |
|---|---|
| Buffer advanced within the last 10s | 60s — protect the stream |
| Nothing streaming | 15s |

`VideoPlayer`'s sampler calls `TorService.noteMediaProgress()` whenever the
buffer advances. That call is deliberately just a timestamp write, since it
runs on every sample of every visible video.

**Anything else that streams over Tor for a sustained period should call
`noteMediaProgress()` too**, or a resolve elsewhere in the app may rotate the
circuit out from under it.

### 16.18 Rotation is shared, so its result is shared (`NOSLOP_COOPERATIVE_ROTATION_V1`)

`SIGNAL NEWNYM` is process-wide: a rotation by any caller moves **every**
subsequent connection onto new circuits. The rate limit in §16.4 and §16.17
therefore has a subtlety that cost a whole round to find.

Three concurrent resolves (the §16.8 gate allows 3) share one exit. When that
exit is gated they are all refused together and all request a rotation at the
same instant. One wins; the others are refused by the interval check. Round 8
had callers read that refusal as "the route did not change" and give up —
but their sibling had just moved them onto a brand new exit a fraction of a
second earlier. They were discarding exactly the retry that would have
worked, which is why round-8 successes took 15-26s where round-7 successes
took under a second.

**`requestNewCircuit()` answers "are you on a fresh circuit?", not "did you
perform the rotation?"** Within `CIRCUIT_CONSIDERED_FRESH_MS` (30s) of any
rotation, and with nothing streaming, it returns `true` without issuing a
second NEWNYM. Callers should keep treating `true` as "conditions changed,
retrying is worthwhile".

This does not weaken §16.17: while media is streaming the answer is still a
flat `false`, because the caller genuinely must not get a new circuit then.

### 16.19 Resolve failures auto-retry once (`NOSLOP_AUTO_RETRY_UNAVAILABLE_V1`)

`retryTrigger` historically advanced only on ExoPlayer *playback* errors, so
a slide that failed to **resolve** parked on the Retry button and waited for
a tap. Field reports were that tapping it usually works — which is the app
asking the user to do by hand what it should do itself, and is explained
entirely by §16.16: the first resolve lost the exit lottery, and by the time
a human reacts the circuit has moved on.

`VideoPlayer` now performs one automatic re-resolve ~2.5s after an
`Unavailable`. The delay is deliberate: long enough for a sibling resolve's
rotation to land, short enough that the slide is usually still on screen.
Exactly one attempt — if it fails again the exit is not the problem and the
button is the honest answer.

### 16.20 Retrying only helps if something changed

`requestNewCircuit()` is rate limited (§16.4) and returns `false` when it
refuses. Retrying a YouTube resolve after a refused rotation re-asks the
**same exit** and gets the identical answer — roughly 20s of guaranteed
failure per video, spent holding a resolve permit that other slides are
queued behind. `resolveStreamUrl` now checks the return value and goes
straight to the Invidious/Piped failover when the route did not change.

The general rule for this codebase: **before spending a retry, establish that
some input to the operation is different from last time.** Same URL, same
exit, same client identity means the same result.

### 16.21 Diagnosing from logs

Capture unfiltered — `adb logcat -c && adb logcat -v time > noslop.log` — and
uninstall whichever build variant is not under test (§16.10). Filtering by
tag hides the Tor daemon's own bootstrap output, which §16.9 needs.

Then work down this list. **The order matters**: each step rules out a class
of cause, and skipping ahead is how three rounds of this session were spent
fixing the wrong layer.

| Step | Question | Where to look |
|---|---|---|
| 1 | Is anything reaching the network at all? | §16.7 — `.onion` peers *and* clearnet both timing out means no path; stop here |
| 2 | Did Tor actually finish bootstrapping? | `Tor bootstrap NN%` (§16.9). "Tor is ON" is not proof |
| 3 | Are resolves succeeding? | count `Resolved direct video stream using X` — **never** playability status (§16.15) |
| 4 | If not, is it the client or the exit? | wide spread of durations with nothing in the middle = exit lottery (§16.16). Failures that vary *by client* = roster (§16.11) |
| 5 | Is the proxy the thing being refused? | `signedFor=` on a resolved URL: a Cloudflare address that gets `LOGIN_REQUIRED` is §16.14 |
| 6 | Did we resolve something unplayable? | itag on `resolved DIRECT`. **18 is what you want.** 137/248/299/303 are DASH video-only (§16.12) |
| 7 | Resolved but not playing? | `PLAYBACK_DIAG sample` — `delta=0` with a flat `bufPos` means no bytes; rising `bufPos` with no `FIRST FRAME` means container or codec |

Signals worth knowing by sight:

| Line | Means |
|---|---|
| `N clients refused on the same circuit` | exit lottery lost; a rotation was attempted (§16.16) |
| `Not rotating — another caller rotated Ns ago` | cooperative rotation working (§16.18) |
| `Skipping NEWNYM ... because a video is streaming` | §16.17 protecting a live stream — correct, not a problem |
| `Auto-retrying unavailable resolve` | §16.19 doing what a user would otherwise have to tap |
| `Gave up waiting 20s for a resolve slot` | the §16.8 gate is too tight for current conditions |
| `offered only adaptiveFormats` | §16.12 correctly refusing a video-only stream |
| `Player response ... geoLock=` | §16.5 |

**Timing figures throughout §16 were captured on a ~0.1 Mbps uplink.** The
diagnoses do not depend on that — a gated exit refuses you identically on
fibre — but treat the durations as an upper bound, and be sceptical of any
conclusion that rests on absolute latency rather than on a ratio or a
pattern.

## 17. Audit and Hardening Pass (2026-09-02)

Indexed from [PROJECT_STATUS.md](PROJECT_STATUS.md). This section is the
detail; the status doc is the summary.

### 17.1 Tor control interface — `NOSLOP_CONTROL_SOCKET_V1` / `_V2`

`writeTorrc` used to emit `ControlPort 9051` with `CookieAuthentication 0`, and
every control operation connected to `127.0.0.1:9051` with a bare
`AUTHENTICATE`. On Android, loopback is shared between all installed apps. Any
app holding `INTERNET` could therefore drive our Tor daemon.

Cookie authentication was considered and rejected. It is a global tor setting,
and `org.torproject.jni.TorService` maintains its own control connection that
authenticates with empty credentials; enabling it would very likely break the
library's connection and with it the `STATUS_ON` broadcast, so Tor would never
report READY. Trading a local attack surface for a non-booting app is a bad
trade.

`TorControlChannel` instead centralises every control operation and offers
three modes:

| Mode | torrc | Behaviour |
|---|---|---|
| `UNIX_ONLY` | `ControlSocket` only | Destination. File permissions on the app's private `filesDir` are the access control; no TCP port exists to attack. |
| `AUTO` | both | **Current.** Prefers the socket, falls back to TCP, logs which won plus a directory dump on failure. Leaves 9051 open — diagnostic, not a destination. |
| `TCP_ONLY` | `ControlPort` only | Exact pre-change behaviour, for isolating a startup failure. |

The first cut shipped `UNIX_ONLY` and the socket never appeared. tor-android
writes nothing to logcat, so the failure was invisible: no bootstrap-phase
lines, no hidden service, nine `NEWNYM: control channel unavailable` warnings,
and circuit rotation entirely inert. `AUTO` exists to answer, on the next run,
whether tor refuses the directory, places the socket elsewhere, or ignores our
torrc at all — the last being plausible given `ControlPort 9051` may have been
tor-android's default rather than ours.

Note `start()` returns early on `Tor already in state READY`, so `writeTorrc`
is skipped on a warm start. A torrc change needs a genuine tor restart to take
effect.

### 17.2 TLS trust — `NOSLOP_TLS_TRUST_V1`

`base-config` permitted cleartext for every domain and trusted user
certificates. System anchors only now, cleartext scoped to `.onion` and
loopback. Android's network security config matches hostnames and has no CIDR
syntax, so "any RFC1918 address" is inexpressible — the LAN Hub fast path is
consequently blocked and `invokeHubApi` falls back to the Hub's `.onion`. A
commented block pins one known LAN address if the fast path is wanted back.
Debug TLS interception belongs in a `app/src/debug/res/xml/` overlay, not here.

### 17.3 Release verification — `NOSLOP_RELEASE_CHECKSUM_V1`

The digest is computed while streaming, so the file is never read twice and
there is no window between hashing and installing. Compared in constant time
against the published checksum; the file is deleted on mismatch, on truncation
against `Content-Length`, and on a `text/html` response. With no published
checksum the install is refused unless `allowUnverified = true` is passed from
a UI path that has warned the user.

`UpdateChecker` resolves the expected digest from, in order: `hero.apkSha256`
in `content.json`, a `.sha256` release asset, a bare 64-hex in the release
notes, then a sibling `<apk-url>.sha256`.

This is integrity against whatever the update channel said, not authenticity.
See "Still open" item 2.

### 17.4 Media proxy — `NOSLOP_PROXY_TOKEN_V1`

Per-process token on `/stream`, compared in constant time; an untokenised
request gets 404 rather than 401 so a probing app cannot confirm what the port
is. The `onion=` parameter must match a well-formed v3 address. The token
rotates per process, so in-progress mesh streams do not survive an app restart
in Coil's cache; fully downloaded media is served from `file://` and is
unaffected.

### 17.5 Mesh framing — `NOSLOP_FRAME_CAP_V1`

Manual newline framing with a 4MB ceiling, generous enough for the largest
`MEDIA_CHUNK` and small enough that a peer cannot buffer the heap away.
Connections exceeding it are dropped. The parse-failure branch logs the frame
length rather than the frame.

### 17.6 Proxy credentials — `NOSLOP_PROXY_SECRET_V1`

`PROXY_URL`, `PROXY_SECRET` and `PROXY_SEND_LEGACY_SECRET` are BuildConfig
fields sourced from Gradle properties, so the shipped secret is no longer in
the public repo and rotation is a property change. The Worker accepts either
the legacy header or a valid HMAC during rollout, and reports which via
`X-Proxy-Auth`. Reddit and Jamendo sign the full proxied URL; YouTube signs the
JSON body — the Worker reconstructs both candidates because changing what the
client signs would break every installed copy at once.

### 17.7 Identity fallback — `NOSLOP_UNLOCK_FALLBACK_V1`

`unlock()` now reads through `secureFallbackRead` and normalises whitespace and
case before comparing. Burnable private keys go through `secureFallbackWrite`.
`clearAll()` removes the Room mirror as its docstring always claimed.

### 17.8 Backup — `NOSLOP_BACKUP_STREAMING_V1`

Both directions stream through a 64KB buffer. GCM `update()` emits plaintext
that is not yet authenticated, so the decrypted archive is written to a temp
file and unzipped only after `doFinal()` returns without throwing; a tampered
archive is deleted before a single entry is read. Zip entry names are validated
as plain file names and every destination is confirmed inside its intended
parent by canonical path. The plaintext temp archive is deleted in a `finally`,
success or failure.

`preferences.xml` in the archive is sealed by a non-exportable Keystore key.
`canOpenRestoredIdentity()` probes it after a restore and sets
`lastRestoreNeedsIdentityRecovery` — see "Still open" item 3.

### 17.9 Player IP lock — `NOSLOP_PLAYER_IP_LOCK_V1`

`playerEndpoint()` is unconditionally direct. Confirm with `PLAYBACK_DIAG
resolved DIRECT` lines: `signedFor=` should always be a Tor exit, never
`104.23.x` or `172.71.x`. The Worker's player-response cache is now cold; if
player calls are ever routed back through it, remove that cache first, because
it stores URLs locked to Cloudflare's egress.

### 17.10 Bootstrap bail-out — `NOSLOP_BOOTSTRAP_BAILOUT_V1`

If no control connection has succeeded within 20 polls, `waitForBootstrap`
returns false immediately so the caller's end-to-end routing check runs. Worth
keeping independently of §17.1: an unreachable control interface is knowable in
seconds, and the fallback needs to run while the current bootstrap attempt is
still alive.

### 17.11 Category picker — `NOSLOP_MUSIC_SELECTABLE_V1`

`hiddenFromPicker` (plumbing categories) is now separate from
`alwaysIncludedCategories` (always fetched). `Music` is both always fetched and
a real user choice; filtering the picker by the latter removed it from the UI
and made `Step6Genres`' `interests.contains("Music")` permanently false.

### 17.12 Content Mix layout — `NOSLOP_CONTENT_MIX_SCROLL_V1`

`FeedMixSettingsSection` is a bare composable emitting siblings with no
container, and requires a scrolling column from its caller.
`ContentPreferencesScreen` supplies one; `Step8FeedMix` supplied a `Box`, which
stacked the header over the card and, being height-constrained without a
scroll, collapsed the lower sliders to zero height.

### 17.13 EXIF orientation — `NOSLOP_EXIF_ORIENTATION_V1`

`ui/ExifUtils.kt` decodes with the orientation applied and exposes
`needsRotation()` so small photos that carry a rotation are re-encoded too.
Rotation is baked into pixels rather than preserved as a tag, because the
receive path mixes Coil and raw `BitmapFactory` and peers may run a different
build. Applied at `ChatThreadScreen`, `GroupChatThreadScreen`, `AvatarCropper`
and `GroupSettingsModal`.

CameraX still has no `setTargetRotation()`; the decode-side fix covers both
camera and gallery sources, so it was left alone deliberately.

### 17.14 Comment media — `NOSLOP_COMMENT_MEDIA_RERESOLVE_V1`

`isDownloaded` is now a `remember` key on the resolved URL, so it re-resolves
from proxy URL to `file://` at the moment the file becomes usable. The
`AsyncImage` also gets a GIF-capable `ImageLoader`, matching the one
`ChatThreadScreen` already builds for DM GIFs.

### 17.15 Logging — `NOSLOP_LOG_HYGIENE_V1`

Rotates at 4MB keeping one generation, so worst-case disk use is bounded at
~8MB. Release drops DEBUG. Writes are serialised on a single daemon thread —
the previous implementation launched a coroutine per line onto the
multi-threaded IO dispatcher and called `File.appendText()`, opening and
closing the file per line with no ordering guarantee. Scrubbing covers long
Base64 blobs and 64-hex digests in addition to onion addresses.

Note the log export surfaces only the active file, not the rotated `.log.1`.

---

**Related docs**: [WIRE_PROTOCOL_REFERENCE.md](WIRE_PROTOCOL_REFERENCE.md) for
the complete, authoritative wire-protocol detail (packet catalog, payload
JSON shapes, signed-string formats) that supersedes §4/§5 here ·
[GAP_ANALYSIS.md](GAP_ANALYSIS.md) for the feature backlog vs. gChat/HAI-Net ·
[PROJECT_STATUS.md](PROJECT_STATUS.md) for the milestone-by-milestone change
log · [HUB_INTEGRATION_PLAN.md](HUB_INTEGRATION_PLAN.md) for §13's planned
HUB-client transition in full detail.

