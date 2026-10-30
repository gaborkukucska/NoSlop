# Android → iOS Feature-Port Roadmap

Bringing Gabby's Android app (`app/`, v0.2.1-alpha — the feature reference) to feature parity in the
iOS/KMP app (`mvp/`). Derived from a gap analysis (2026-06-25), corrected for what iOS already has and
for the review punch-list. Wire formats must stay byte-compatible with Android (validate against the
golden `WireProtocolTest` vectors); port social/mesh ops onto our **working** path (`MeshClient` /
`MeshUiState` / `Packets.kt`) — NOT the dead, wire-incompatible `data/MeshSocialRepository.kt` (punch-list M1).

## Where iOS already is
- ✅ Multi-source clearnet feed (aggregator, RSS/API, parsing, images, filters, search-ish)
- ✅ E2E DMs (X25519/ChaCha20) + Android-style chat UI (bubbles, contact list, share-sheet)
- ✅ **Post reactions** (sendReaction/applyReaction + emoji UI) — the agent's roadmap wrongly listed these as missing
- ✅ Mesh posts in feed; signed-packet verification (C1/C2) + thread-safe firewall (H1)
- ✅ The bilingual bridge (iOS leaf ↔ Android peers)

## The gap, by theme (what Android has that iOS doesn't)

### Social / engagement
- ✅ **Comments + threaded replies** — DONE. `sendComment`/`onComment` + threaded `CommentsSheet`/`CommentRow`.
- ✅ **Votes (up/down) on posts + comments** — DONE. Post votes + comment votes (`sendCommentVote`/`voteComment`),
  signed `"$id|$voteType|$authorId|$ts"`, deduped per author with toggle/switch/retract semantics.
- ✅ **Comment + chat-message reactions** — DONE. `ChatReactionPayload`/`CommentReactionPayload` added to mvp
  Packets; `sendChatReaction`/`sendCommentReaction` + aggregation; CHAT_REACTION now mesh-relayed (DM message
  ids keyed on the wire `EncryptedPayload.id` so reactions correlate with Android both directions).
- ✅ **Engagement sync in gossip** — DONE (2026-07-02). Leaf broadcasts `SYNC_REQUEST` (7-day window,
  25 s after first link-up); `SYNC_RESPONSE` batches are per-item verified and fed through the same
  onPost/onComment/onReaction callbacks as live gossip (one dedup path). Votes aren't in Android's sync
  payload (posts/comments/reactions only) — parity preserved.

### Messaging
- ✅ **Message replies** (`replyToMessageId`) + reply-quote UI — DONE. DMs now use Android's
  `{"content":…,"replyTo":…}` envelope (`DmEnvelope.kt`); long-press → ↩︎ Reply, quoted snippet on reply
  bubbles, composer reply banner. Also fixed the latent bug where Android's JSON-wrapped DMs showed raw braces.
- **Chat media attachments** (image/video/GIF in a DM) — depends on media-over-mesh. **[M, blocked]**

### Media over mesh — the big one
- iOS leaves can't host inbound, so all peer media must relay through the **JVM bridge** (cache +
  forward chunks). Needs: `MediaMetadata` handling, a chunked transfer manager (Android's AIMD
  `MediaManager`), `MediaChunkPayload`/`MediaRequestPayload`, expect/actual file I/O (iOS
  NSFileManager / sandbox), auto-download on post receipt, thumbnail extraction, and bridge relay/cache.
  **[L — largest item, split into download pipeline then upload/broadcast]**

### Feed UX / curation
- ✅ **Feed position memory + viewed-history** — DONE (2026-07-02). `FeedSession` keeps stories + pager
  position across tab switches; >5 s dwell records to the SQLDelight viewed-history (5000 cap, Android
  parity), excluded on next load. Swipe-away exclusion N/A to our vertical-pager UX (no dismiss gesture).
- ✅ **Search routing by media type** — DONE (2026-07-02). Search field + All/Videos/Audio/Images/Articles
  chips; `Search *` categories in `PublicApiService` dispatch to Android's source sets (+ Internet Archive
  video for resilience). Creator search/follow still open. **[S]**

### Moderation / safety
- ✅ Post **edit/delete** — DONE (2026-07-02). `EditPostPayload`/`DeletePostPayload` wire + verifier
  (required-signed), receive-side author-match + no-backdating (Android `PostPacketHandler` semantics),
  delete marks orphaned (no resurrection via gossip echo). Send: `sendEditPost`/`sendDeletePost`; 🗑 UI on
  own posts (edit UI still open). ✅ **Peer blocking** — 🚫 hides an author's posts/comments/DMs, persisted.
  Still open: user-exit + connection-rejected announcements. **[S remains]**

## Recommended order (dependency- and value-aware)

| # | Item | Effort | Why here |
|---|------|--------|----------|
| ✅ 1 | **Comments + threaded replies** | M | DONE — `sendComment`/`onComment` + threaded `CommentsSheet`. High social value, wire format stable, no media dependency. |
| ✅ 2 | **Votes + comment/chat reactions** | M | DONE — post votes (`sendVote`/`vote`), comment votes (`sendCommentVote`/`voteComment`), comment reactions (`sendCommentReaction`), and DM chat reactions (`sendChatReaction`, real CHAT_REACTION replacing the local-only `dmReactions`). All signed Android-byte-compatible; verifier + tests added. |
| ✅ 3 | **Message replies** (text only) | S–M | DONE — adopted Android's `{"content":…,"replyTo":…}` DM envelope (`DmEnvelope.kt`) + reply UI; fixed the latent JSON-braces display bug. |
| ✅ 4 | **Sign iOS posts** (punch-list follow-up) | S | DONE — `publish()` signs `"$id|$authorId|$content|$ts"` (authorId = our Android b64 id); verifier now requires POST signatures (closes the C1 lenient gap). Android already rejected our unsigned posts, so this is what makes them land. |
| ✅ 5c | **Video/audio download + local playback** | S | DONE (2026-07-02) — tap-to-download any media type (auto stays images ≤5 MB); downloaded video/audio play via `VideoPlayer`/`AudioPlayer` from a `file://` URL (`VideoSource` resolves `file://` as Direct). Progressive/streaming playback of larger files still open. |
| ✅ 7 | **Feed memory + viewed-history** | S | DONE (2026-07-02) — see Feed UX section. |
| ✅ 8 | **Search routing / media typing** | S–M | DONE (2026-07-02) — media-type search chips; creator follow still open. |
| ✅ 9 | **Moderation (edit/delete/block) + sync** | M | DONE (2026-07-02) — edit/delete wire + author-match apply + 🗑/🚫 UI; author blocking; engagement sync on connect. Edit UI + exit/rejected announcements still open. |
| 🔄 5 | **Media over mesh — download pipeline** | L | IN PROGRESS. **5a DONE** — inline thumbnails: `Base64Image` renders an Android post's in-band `thumbnail_b64` with zero fetch (no deanon); media posts now show their preview. **5b DONE** (2026-07-01) — image downloader: Android-wire media payloads in `Packets.kt`, `MediaDownloadManager` (AIMD 16→64 KB / conc 4, timeout-retry, recovery broadcast, EOF/short-read, `.part`→rename, ACK), expect/actual `MediaFiles` (NSFileHandle / RandomAccessFile), firewall `MEDIA_*`/`SYNC_*` rate-limit exemption (GossipService parity), auto-download images ≤ 5 MB + tap-to-load for bigger, `LocalFileImage` renders the downloaded file in the feed card. Requests broadcast via the bridge (design option A — zero bridge changes). **5c NEXT** (video/audio + streaming playback). |
| ✅ 6 | **Media over mesh — upload/broadcast** | M | DONE (2026-07-03) — posts can carry an image both directions. The bridge **pulls** leaf-published media with the same requester-driven protocol (reused `MediaDownloadManager`), caches it (`BridgeMediaCache`, 2 GB/5-day LRU), and serves/recovers it for Android peers — posts advertise the bridge onion as `origin_node`, so media outlives the phone's session. Leaf serves MEDIA_REQUESTs from disk (`buildMediaChunk`, shared serve half); iOS PHPicker bridge + 🖼 composer attach. Chat media (images in DMs) still open — same machinery, needs the DM envelope's media fields. |
| 7 | **Feed memory + viewed-history** | S | Independent UX win; can slot in anytime. |
| 8 | **Search routing / media typing** | S–M | Independent feed polish. |
| 9 | **Moderation (edit/delete/block) + sync** | M | After the content types exist to moderate. |

## Cross-cutting notes
- **Wire-format parity:** every new payload must match Android byte-for-byte; add golden-vector tests.
- **Don't extend `data/MeshSocialRepository.kt`** (dead/incompatible) — either fold social ops into
  `MeshClient`/`MeshUiState` or stand up a clean shared `social` layer; delete the dead repos (M1).
- **Media-over-mesh is the architectural hard part** — confirm the bridge relay/cache design before
  Phase 5; iOS can't receive peer media directly.
- Each phase ships as its own compile-tested, installable increment (same cadence as the feed work).

## Protocol v2 parity with Android 0.7.1-alpha (2026-10-25)

Upstream made two hard wire breaks after our June merge: v2-only handshakes (D07, 2026-10-07) and an
Ed25519 envelope signature on every packet (E2, 2026-10-13). Until this port, current Android builds
silently dropped everything the iOS leaf sent. Merged upstream `main` (`bb30d7f`) and ported:

- **Envelope** (`Envelope.kt`): signed in `MeshNode.broadcast` over the Android form of the ids, so it
  survives the bridge's hex→base64 re-encoding; the payload digest hashes a Gson-identical rendering
  (`gsonCanonicalJson`). The bridge verifies envelopes on everything from Android and signs its own
  media-cache replies.
- **Handshakes v2**: nonce + target + DM key under `encodeForSigning("noslop-hs-v2", …)`; the app keeps
  one nonce per request (`MeshContact.pendingNonce`) and probes every 15 s for 10 min like upstream.
- **DM v2**: HKDF-SHA256 directional key + AAD (needed an AAD parameter on `DmCrypto` and the CryptoKit
  bridge); v1 kept only as a receive fallback. We now send DM_ACK v3 so Android stops re-sending.
- **Canonical signatures** for posts (v2 with media SHA-256), edits, deletes, votes and reactions; the
  verifier accepts both the new and the old forms.
- SHA-256/HMAC/HKDF in pure common Kotlin (`Sha256.kt`), so iOS needed no new dependency.

Verified both ways: `ProtocolV2Test` checks vectors produced by upstream's own `EnvelopeAuth` /
`CryptoService` (`jvmTest/resources/android-v2-vectors.json`), and upstream's `EnvelopeAuth.verify`,
`MeshPacketVerifier.verify` and `decryptDMV2` accepted every packet type the KMP side emits (run as a
scratch Robolectric test in `app/`, not committed). `jvmTest` 128/128, `testDebugUnitTest` 102/102,
iOS `xcodebuild` succeeded. Not yet verified end to end against a live Android phone.
