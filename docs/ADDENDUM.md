# NoSlop Documentation Addendum — gChat/HAI-Net Cross-Reference & Log Findings

**Purpose**: This addendum captures (a) details from gChat and HAI-Net that
are relevant to NoSlop but not yet reflected in NoSlop's own docs, and (b)
findings from the attached `logcat` excerpt cross-referenced against the
current codebase. It is meant to be merged into `GAP_ANALYSIS.md` and
`PROJECT_STATUS.md` rather than read standalone.

---

## A. TECHNICAL_REFERENCE.md is now stale in §4–7 — code has outpaced docs

`docs/GAP_ANALYSIS.md` marks almost every wire-protocol gap as "Done" (✅), but
`docs/TECHNICAL_REFERENCE.md` §4.2, §4.4, §4.5, §5.2 and §6.1 still describe
the *pre-parity* implementation:

- `Packets.kt`'s `type` field comment now lists **19** packet types:
  `POST, MESSAGE, CONNECTION_REQUEST, USER_HANDSHAKE, SYNC_REQUEST,
  SYNC_RESPONSE, INVENTORY_SYNC_REQUEST, COMMENT, REACTION, CHAT_REACTION,
  COMMENT_REACTION, VOTE, COMMENT_VOTE, ANNOUNCE_PEER, IDENTITY_UPDATE,
  USER_EXIT, EDIT_POST, DELETE_POST` (plus the five `MEDIA_*` types) —
  TECHNICAL_REFERENCE.md §5.2 only documents 18 total including media, and is
  missing `INVENTORY_SYNC_REQUEST`, `CHAT_REACTION`, `COMMENT_REACTION`,
  `COMMENT_VOTE`, `VOTE`, `ANNOUNCE_PEER`, `IDENTITY_UPDATE`, `USER_EXIT`,
  `EDIT_POST`, `DELETE_POST` entirely.
- §4.5's dispatch table only lists the original 11 handler types;
  `MeshPacketHandler.handlePacket` now dispatches **10 additional** types
  (`INVENTORY_SYNC_REQUEST`, `ANNOUNCE_PEER`, `IDENTITY_UPDATE`, `USER_EXIT`,
  `EDIT_POST`, `DELETE_POST`, `CHAT_REACTION`, `COMMENT_REACTION`,
  `COMMENT_VOTE`, `VOTE`).
- §4.5 describes `SYNC_REQUEST`/`SYNC_RESPONSE` as the live sync protocol;
  the code now also has `INVENTORY_SYNC_REQUEST` (hash-based diffing per
  gap-analysis §1 item 9). **Important wire detail**: there is **no**
  separate `INVENTORY_SYNC_RESPONSE` packet type — the inventory-diff reply
  is sent as an ordinary `SYNC_RESPONSE` (confirmed: `Packets.kt` defines no
  `INVENTORY_SYNC_RESPONSE` string, and `MeshPacketHandler`'s log message at
  the end of `handleInventorySyncRequest` reads "INVENTORY_SYNC_REQUEST
  handled — sent N missing posts..." immediately before what is effectively
  a `SYNC_RESPONSE` send). `SyncResponsePayload` has therefore been extended
  beyond `posts: List<PostPayload>` to also carry `comments` and `reactions`
  arrays (per milestone 159/172 notes in PROJECT_STATUS.md), but
  TECHNICAL_REFERENCE.md §5.2's `SyncResponsePayload` row still says
  `posts: List<PostPayload>` only.
- §6.1 documents `MediaManager`'s chunking as a flat `MAX_CONCURRENCY = 4`
  with no adaptive behavior, and Gap Analysis §7 lists AIMD congestion
  control as an open item with a recommendation to port
  `hainet-social/src/congestion.rs`. **This has since been implemented**:
  `MediaManager.kt` now has a per-download AIMD state machine
  (`windowSize` starting at 4.0, `ssthresh` starting at 128.0, slow-start
  `windowSize += 1` while `windowSize < ssthresh`, congestion-avoidance
  `windowSize += 1/windowSize` otherwise, multiplicative decrease
  `ssthresh = max(2, windowSize * 0.5); windowSize = 1` on timeout, capped at
  128) plus an RTT EMA, directly mirroring `hainet-social/src/congestion.rs`'s
  documented algorithm. `MAX_CONCURRENCY = 4` remains in the file but now
  only serves as the AIMD initial window value, not a hard cap.

**Recommendation**: TECHNICAL_REFERENCE.md §4–7 need a pass to bring them in
line with the code; GAP_ANALYSIS.md's "Done" checkmarks are accurate for the
*code*, but the cross-references into TECHNICAL_REFERENCE.md that say "see
§4.5 for the dispatch table" etc. now point at incomplete tables.

---

## B. gChat/HAI-Net details still not reflected anywhere in NoSlop docs

### B.1 `EncryptedPayload.groupId` is no longer purely "reserved, unused"

TECHNICAL_REFERENCE.md §5.2 describes `EncryptedPayload.groupId` as
"reserved, unused — see Gap Analysis §3". Group chats (`GROUP_*` packets,
`Group` Room entity) are still **not implemented** in NoSlop (Gap Analysis
§12 checklist correctly leaves this unchecked) — this is *not* a discrepancy,
just a reminder that `groupId` remains dead weight in the wire format until
group chat lands. No action needed beyond keeping the checklist item open.

### B.2 gChat's `FOLLOW`/`UNFOLLOW` asymmetric relationship model

Still unimplemented (Gap Analysis §1, correctly left unchecked). Worth noting
for NoSlop's roadmap: gChat's binary trust model (`Peer.isTrusted`) is what
NoSlop also uses, and the firewall in `GossipService.processIncoming` (§4
TECHNICAL_REFERENCE) hard-requires `isTrusted == true` for almost all packet
types. If `FOLLOW`/`UNFOLLOW` is ever added, it would need to either (a) be
layered *on top of* the existing trust gate as a UI-only "subscribe to this
trusted peer's posts" filter, or (b) NoSlop would need a second, weaker trust
tier — the latter is a more invasive change to `GossipService`'s firewall
logic than the former.

### B.3 gChat's Plugin & Theme Engine — re-confirm theming is still the only feasible half

Gap Analysis §8 already concludes plugin loading is impractical on Android
but theming is feasible. No code changes found for a theme system since that
analysis — `ui/theme/Color.kt` remains a single hardcoded palette. Still
correctly an open, low-priority item.

### B.4 HAI-Net's Admin AI / Persona layer (`hainet-portal`, `admin_bridge.rs`, `hainet-persona`)

This is the largest piece of HAI-Net with **no corresponding mention** in any
NoSlop doc, and it's relevant because of §13 of TECHNICAL_REFERENCE.md
("Future Architecture: HAI-Net Hub Client"). Specifically:

- `hainet-core/src/admin_bridge.rs` bridges a Tauri/web frontend
  (`hainet-portal`) to an `AdminAgent` — a local LLM-backed assistant with
  its own `ChatMessage`/`ChatResponse` IPC schema, session history
  persistence (`sessions_dir`), STT (`stt_handler.rs`) and TTS
  (`tts_handler.rs`) handlers, and MCP tool access
  (`hainet-persona::tools::mcp::MCPClientManager`).
- The HAI-Net "Local Hub" (per `helperfiles/1_THE_IDEA.md`) is explicitly the
  intended **identity and data store** plus **local LLM host** for a user —
  i.e., the same role NoSlop's `IdentityRepository` currently plays
  standalone on-device, plus a much larger compute/AI layer NoSlop has no
  equivalent of.
- **What this means for NoSlop's docs**: TECHNICAL_REFERENCE.md §13 already
  sketches "NoSlop as `SLAVE_FRONTEND`" connecting to a hub's private onion
  for mesh routing. The addendum here is that such a hub, per HAI-Net's own
  architecture, would *also* be running an `AdminBridge`-style local AI
  assistant — meaning a future "HaiNetTab" in NoSlop (which currently exists
  as a stub per `ui/HaiNetTab.kt` and the "HAI-Net hub coming soon" onboarding
  screenshot) could eventually be a thin client for `admin_bridge.rs`'s
  `ChatMessage`/`ChatResponse` IPC schema, not just a mesh-peer list. This is
  purely a forward-looking architecture note, not a current gap, but it's the
  kind of detail that would otherwise only live in the HAI-Net repo and be
  invisible to NoSlop contributors.

### B.5 HAI-Net `hainet-vault` (CONSTITUTION.md / GOVERNANCE.md / DECLARATION.md)

HAI-Net's "Constitutional Framework" (per `1_THE_IDEA.md`'s Core Principles —
"Core principles decided by the network and enforced through code") lives in
`hainet-vault/`. NoSlop has no equivalent governance documentation and
arguably doesn't need one as a standalone app, but if NoSlop is ever
positioned as "the mobile client for HAI-Net," its README/onboarding flow
(which already markets privacy-by-design language similar to HAI-Net's Core
Principles) could reference `hainet-vault/DECLARATION.md` as the shared
values document across the ecosystem. Flagged for awareness only.

### B.6 `hainet-social`'s additional modules without NoSlop analogues

Beyond what GAP_ANALYSIS.md already covers (relay, congestion, groups,
presence), `hainet-social/src/lib.rs` also declares `firewall`, `dedup`, and
`feed` as separate modules. NoSlop's equivalents
(`GossipService`'s firewall checks, `processedPacketIds` dedup set, and the
Room-backed feed tables) are functionally present but live inline inside
`GossipService.kt`/`NoSlopRepository.kt` rather than as separate units — this
is purely a code-organization difference (Rust crate modularity vs. Kotlin
object/class), not a feature gap, and doesn't need a docs change beyond
noting the mapping for anyone porting code between the two.

---

## C. Findings From the Attached Test-Run Log

The log spans roughly `00:18:27` to `00:34:04` and shows three NoSlop-tagged
issue classes, cross-checked against current code:

### C.1 DNS failures for `selfhostedhero.com`, `feeds.apnews.com`, `rssfeeds.webmd.com`

```
NoSlop/DNS: All DNS resolvers failed for selfhostedhero.com: NXDOMAIN
NoSlop/FEED_PARSER: Network exception fetching feed https://selfhostedhero.com/rss
NoSlop/DNS: All DNS resolvers failed for feeds.apnews.com
NoSlop/DNS: All DNS resolvers failed for rssfeeds.webmd.com
```

All three hosts are real `BuiltInSource` entries in `SourceLibrary.kt`
(`selfhosted-hero`, `ap-top`, `webmd`). `HttpClientProvider.clearnetClient`
already documents and implements a fix for a *related* DoH issue (the
`1.1.1.1`-cert-mismatch bug, switched to `cloudflare-dns.com` +
`dns.google` chained resolvers per the inline comment). However, the log
shows **`NXDOMAIN`**, not a TLS/cert error — meaning even the corrected DoH
chain (Cloudflare → Google) returned no records for these three hostnames at
test time. Two non-exclusive explanations:

1. **The hostnames may simply no longer resolve** — `selfhostedhero.com` and
   `feeds.apnews.com` are exactly the kind of independent/legacy feed
   endpoints that get retired or migrated (AP in particular has changed its
   public RSS infrastructure multiple times). This would be a **content
   gap**, not a code bug — the fix is to update/remove these
   `BuiltInSource` entries in `SourceLibrary.kt`, not to touch
   `HttpClientProvider`.
2. **The test device's network environment** (emulator/sandbox DNS) may not
   have had egress to `cloudflare-dns.com`/`dns.google` at all, in which case
   *all* DoH lookups would fail closed with `NXDOMAIN` rather than falling
   back to system DNS — worth confirming `DnsOverHttps` is configured with a
   fallback to `Dns.SYSTEM` (the import `okhttp3.Dns` is present in
   `HttpClientProvider.kt` but it's not visible from the excerpt whether a
   `Dns.SYSTEM`-chained fallback exists after the Google DoH resolver).

**Recommendation**: (a) spot-check `selfhostedhero.com` and
`https://feeds.apnews.com/rss/TopNews` for current liveness and prune/replace
in `SourceLibrary.kt` if dead; (b) confirm `HttpClientProvider.clearnetClient`
has a final `Dns.SYSTEM` fallback after the DoH chain so a DoH-hostile network
doesn't fail every clearnet request.

### C.2 XML parse error on `selfhostedhero.com/rss`

```
NoSlop/FEED_PARSER: Error parsing XML stream | Unexpected token
  (position:TEXT  -->@440:50 in java.io.InputStreamReader@...)
```

This occurs **immediately after** the DNS failure for the same host in both
log windows (`00:18:27`→`00:18:33` and `00:33:26`→`00:33:32`), at the same
byte position (`@440:50`) both times. Given the DNS resolution for this host
just failed, this is almost certainly **not** a fresh network response — it's
consistent with `FeedParser` falling through to a cached/stale response body
(e.g. an ISP captive-portal HTML page, or a cached error page from a previous
successful-DNS-but-dead-feed state) being fed to the XML parser, which chokes
at a fixed offset because the cached body is identical between runs. This
reinforces explanation (1) in C.1 — the feed's URL likely now redirects to an
HTML error/landing page rather than serving RSS, and `FeedParser`'s
`resolveRssUrl` auto-discovery (TECHNICAL_REFERENCE.md §7.5) either isn't
being reached for this source or isn't finding a valid feed link on whatever
page is actually being served.

### C.3 NASA APOD request timeout

```
NoSlop/NASA_API: NASA APOD request failed | timeout
```

`NasaApiClient.fetchAPOD` uses `HttpClientProvider.clearnetClient`, which per
§A above is built on a DoH-chained `OkHttpClient` with `connectTimeout` /
`readTimeout` = 10s on the **bootstrap** client used for DoH lookups
themselves, and 30s on the two named clients at lines 71 and 103 of
`HttpClientProvider.kt`. A single `api.nasa.gov` request timing out is
consistent with either (a) the same DNS/DoH issue from C.1 delaying
resolution of `api.nasa.gov` past the configured timeout, or (b) NASA's
`DEMO_KEY` rate limit (30 req/hour, per the inline KDoc in
`NasaApiClient.kt`) — though a rate-limit response would normally return an
HTTP 429 quickly rather than time out, so (a) is more likely the same root
cause as C.1/C.2 rather than a separate issue.

**Overall C.1–C.3 assessment**: all three NoSlop-tagged errors in this log
excerpt appear to trace back to a **single underlying cause** — DNS/network
resolution failures in the test environment (or genuinely dead upstream feed
endpoints) — rather than three independent bugs. The `HeatmapThread`,
`chromium` tile-memory, `DigitalKey`/`uwb`, and `ExynosCameraBufferManager`
lines interleaved in the log are unrelated Android system/OEM noise, not
NoSlop-emitted.

---

## D. Updated Summary Checklist (delta vs. GAP_ANALYSIS.md §12)

- [x] Sync TECHNICAL_REFERENCE.md §4.5/§5.2/§6.1 packet-dispatch and payload
      tables with the current 19-type `Packets.kt` (this addendum, §A)
- [x] Document that `INVENTORY_SYNC_REQUEST` replies use `SYNC_RESPONSE`
      (extended with `comments`/`reactions`), not a separate
      `INVENTORY_SYNC_RESPONSE` type (§A)
- [x] Document the implemented AIMD congestion controller in
      TECHNICAL_REFERENCE.md §6 (§A) — Gap Analysis §7/§12 should be marked
      done
- [ ] Spot-check and prune dead `BuiltInSource` feed URLs
      (`selfhostedhero.com`, `feeds.apnews.com/rss/TopNews`, possibly
      `rssfeeds.webmd.com`) (§C.1)
- [ ] Confirm `Dns.SYSTEM` fallback exists in `HttpClientProvider.clearnetClient`'s
      DoH chain (§C.1)
- [ ] `FOLLOW`/`UNFOLLOW` asymmetric relationship model — still open (§B.2)
- [ ] Group chats (`Group` entity + `GROUP_*` packets) — still open
- [ ] User-selectable theme palettes — still open (§B.3)
- [ ] Forward-looking: note HAI-Net's `admin_bridge.rs`/Admin AI persona layer
      as the likely eventual backend for NoSlop's `HaiNetTab` stub (§B.4)
