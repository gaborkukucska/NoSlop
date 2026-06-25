# Media over Mesh — iOS/KMP design (Roadmap #5–6)

**Status:** proposal for review (no code yet). Derived from a source map of Android's `MediaManager` /
`GossipService` / media packets and our KMP `BridgeMain` / `SocketTransport`. The roadmap gates Phase 5 on
confirming this relay/cache design first — this doc is that gate.

## The problem in one paragraph

Android peers serve media by being **dialable**: a requester opens a Tor connection straight to the
origin's onion (`meshTransport.sendPacket(originOnion, 9999, MEDIA_REQUEST)`) and the origin streams
`MEDIA_CHUNK`s back. An **iOS leaf has no inbound onion** (ADR‑002) — it can neither be dialed nor dial
arbitrary onions; it only holds one outbound link to the **bridge**. So every byte of peer media must move
through the bridge. That splits cleanly into two directions with very different difficulty:

- **Download (Android media → iOS), Roadmap #5** — iOS asks, an *online* Android origin serves, the bridge
  relays. Pure pass‑through; the bridge needs no storage. **This is the achievable near‑term unlock.**
- **Upload (iOS media → Android), Roadmap #6** — an iOS leaf authors a post with media, then *goes offline*.
  When an Android peer later requests it, the origin is unreachable. The **bridge must have cached the bytes**
  to serve them. This needs the bridge to be a media cache/host, not just a relay. Harder; do it second.

## What we already have working for us

- **The bridge relays type‑agnostically** (`BridgeMain.onReceive`): any packet type flows iOS↔Android,
  re‑encoding only the top‑level routing ids (hex↔base64). `MEDIA_REQUEST`/`MEDIA_CHUNK` will pass today
  without bridge changes — *for the relay direction*.
- **No line‑length cap.** `SocketTransport` frames one JSON packet per line via `readUTF8Line()` (default
  `max = Int.MAX_VALUE`). A 64 KB binary chunk → ~88 KB base64 → ~88 KB line reads fine. Framing is not a
  blocker. (We'll still keep chunks modest for memory/latency — see "Chunk size".)
- **Thumbnails travel inside the post.** `MediaMetadata.thumbnail_b64` is a ~90px JPEG already embedded in
  the `media_metadata` of a POST. We can render it **with zero fetching** — the cheapest first increment.
- **Store‑and‑forward exists** in the bridge (per‑target backlog, 200‑packet bound) — a starting point for
  the Phase‑6 cache, though media needs a byte cache, not a packet queue.

## Wire types to port (add to `Packets.kt`, byte‑compatible with Android)

| Type string | Payload | Key fields (`@SerializedName`) |
|---|---|---|
| `MEDIA_REQUEST` | `MediaRequestPayload` | `media_id`, `chunk_index`, `chunk_size`, `byte_offset`, `byte_length`, `access_key`, `hls_file` |
| `MEDIA_CHUNK` | `MediaChunkPayload` | `media_id`, `chunk_index`, `total_chunks`, `byte_offset`, `data` (base64) |
| `MEDIA_RELAY_REQUEST` | `MediaRelayRequestPayload` | `media_id`, `origin_node`, `owner_id`, `access_key`, `metadata` |
| `MEDIA_RECOVERY_FOUND` | `MediaRecoveryFoundPayload` | `media_id` |
| `MEDIA_TRANSFER_ACK` | `MediaTransferAckPayload` | `media_id` |
| (`MEDIA_METADATA_RESPONSE`) | metadata reply to a `chunk_size==0` probe | confirm shape in Android |

`MediaMetadata` already exists in our `Packets.kt` and matches (`id,type,mime_type,size,chunk_count,access_key,
filename,origin_node,owner_id,thumbnail_b64`). **None of these are signed** on Android (integrity rides on
Tor; `media_metadata` is *not* part of the POST signed string `id|authorId|content|timestamp`) — so treat
metadata as untrusted attacker input (size caps, type allow‑list, never auto‑open).

## Download pipeline (Roadmap #5) — the requester‑driven model

Android's transfer is **pull, requester‑dictated**: the receiver chooses byte ranges and an AIMD window; the
server just reads `[byte_offset, byte_length)` off disk and returns it. We port the receiver in commonMain.

```
iOS leaf                         bridge                    Android origin
  │  POST arrives w/ media_metadata (+ thumbnail_b64 shown immediately)
  │  MEDIA_REQUEST(media_id, byte_offset, byte_length) ─────────────────►   reads range from disk
  │  ◄──────────────────────── MEDIA_CHUNK(byte_offset, data) ──────────    streams it back
  │  write at offset → .part ; advance contiguous ; AIMD++ ; request next
  │  …loop until contiguous == size…
  │  rename .part→final ; MEDIA_TRANSFER_ACK ─────────────────────────►
```

Components (all commonMain except file I/O):

1. **`MediaDownloader`** (commonMain) — port of `ActiveDownload`: sparse `writtenOffsets`, `inflight` with
   120 s timeout + retry queue, contiguity tracking, EOF when a chunk returns `< requested` length, AIMD
   (start 16 KB / concurrency 1, +16 KB & +1 on success to caps 64–128 KB / 8, halve on timeout). Drives
   `requestNextChunks()` on a ~2 s tick.
2. **`expect MediaStore`** (actual: JVM `RandomAccessFile`; iOS `FileHandle`/`NSFileManager` seek+write) —
   random‑access write into `<mediaDir>/<id>.part`, rename on completion, read range (for Phase‑6 serving).
3. **`MeshClient` wiring** — `requestMedia(metadata)`, `onMediaChunk` sink, and on POST/DM receipt with
   `media_metadata`, kick off (or queue) a download per policy.
4. **UI** — feed/DM media cell: show `thumbnail_b64` instantly, a tap/auto trigger to download, progress,
   then render the local file (reuse `NetworkImage`/`VideoPlayer` pointed at a *local* path, not clearnet).

### Routing through the bridge — the one real design choice for #5

A `MEDIA_REQUEST` on Android is addressed to a specific origin onion. Our leaf can't address onions; it
broadcasts to the bridge. Two options:

- **(A) Bridge broadcasts the request to all Android peers; only the origin (which has the file) answers.**
  Zero new bridge state; works immediately because chunks return via the existing Android‑inbound→bridge→leaf
  path. Cost: every Android peer sees every request (noise, minor). **Recommended for #5 (ship it).**
- **(B) Bridge learns `media_id → origin` and unicasts.** Less noise, but needs the bridge to parse media
  packets and track origins. **Defer** — only worth it if (A)'s noise actually bites.

Set `MediaRequestPayload.origin_node`/`owner_id` from the post so a future smart bridge (B) can unicast
without protocol change.

## Upload / serve (Roadmap #6) — bridge as media cache

When an iOS leaf authors media, it must still be fetchable after the phone is offline. Plan:

1. On publish, the leaf **uploads the bytes to the bridge** (chunked, same packets, reversed) and the bridge
   **caches** them keyed by `media_id` under an LRU (mirror Android: 2 GB / 5‑day cap).
2. The post's `media_metadata.origin_node` advertises the **bridge's** onion as the origin, so Android peers
   request from the bridge, which serves from cache (handshake‑less Android‑inbound path already exists).
3. Reuse Android's **recovery** path (`MEDIA_RELAY_REQUEST` → `MEDIA_RECOVERY_FOUND`) so a peer that finds the
   origin offline can discover the bridge cache.

This makes the bridge a participating media host, not just a relay — the architectural step the roadmap
flagged. It's strictly after #5.

## Phasing (each its own compile‑tested, installable increment)

- **5a — Inline thumbnails (cheap, no transfer).** Port `MediaMetadata` rendering: show `thumbnail_b64` in
  feed/DM cells. Immediately makes Android media posts look right on iOS. **Do first.**
- **5b — Image download.** `MediaDownloader` + `MediaStore` + request/chunk wiring, images only (small,
  single‑shot). Auto‑download images under a size cap; render from local file.
- **5c — Video/audio + AIMD streaming.** Larger files, progressive download, wire to `VideoPlayer`/
  `AudioPlayer` from a local path; full AIMD window.
- **6 — Upload + bridge cache + recovery.** Outbound media + bridge becomes a cache/host.

## Open decisions for you (before 5b)

1. **Auto‑download policy.** Auto‑fetch images ≤ N MB over the bridge (Tor, so no clearnet deanon), but
   require a tap for video and for anything over the cap? (Untrusted size/type — needs a guard.) Proposed:
   auto images ≤ 5 MB, tap‑to‑load video.
2. **Chunk size.** Keep Android's 16→128 KB AIMD, or cap lower (e.g. 64 KB) for iOS memory/Tor latency?
   Proposed: cap 64 KB initially, revisit.
3. **Scope of this pass.** Ship **5a (thumbnails) now** as the safe inline win, and design‑review 5b before I
   build the downloader? That keeps each step installable and lets you test thumbnails with Gabby first.

## Risks / notes

- `media_metadata` is unauthenticated (not in the POST signature) — enforce size caps + MIME allow‑list;
  never auto‑open a downloaded file; write under our sandbox only.
- All media moves over Tor via the bridge — no clearnet fetch, so it does **not** reintroduce the C3 deanon
  vector (we still must not fetch any `clearnet_*` URL automatically).
- Keep `ktor` pinned (3.0.3); the downloader is pure Kotlin + expect/actual file I/O, no new deps.
- Bridge cache (Phase 6) adds disk + memory pressure to the JVM bridge host — bound it (LRU) and document it.
