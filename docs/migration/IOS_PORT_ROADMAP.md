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
- **Engagement sync in gossip** — comments/reactions/votes carried in the sync response so a joining
  peer catches up. **[M]**

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
- **Feed position memory + viewed-history / swipe exclusion** (don't re-show seen items). **[S]**
- **Search routing by media type** (Search Videos/Audio/Images/Articles) + creator search/follow. **[S–M]**

### Moderation / safety
- Post **edit/delete** (`EditPostPayload`/`DeletePostPayload`), user-exit + connection-rejected
  announcements, peer blocking. **[M]**

## Recommended order (dependency- and value-aware)

| # | Item | Effort | Why here |
|---|------|--------|----------|
| ✅ 1 | **Comments + threaded replies** | M | DONE — `sendComment`/`onComment` + threaded `CommentsSheet`. High social value, wire format stable, no media dependency. |
| ✅ 2 | **Votes + comment/chat reactions** | M | DONE — post votes (`sendVote`/`vote`), comment votes (`sendCommentVote`/`voteComment`), comment reactions (`sendCommentReaction`), and DM chat reactions (`sendChatReaction`, real CHAT_REACTION replacing the local-only `dmReactions`). All signed Android-byte-compatible; verifier + tests added. |
| ✅ 3 | **Message replies** (text only) | S–M | DONE — adopted Android's `{"content":…,"replyTo":…}` DM envelope (`DmEnvelope.kt`) + reply UI; fixed the latent JSON-braces display bug. |
| ✅ 4 | **Sign iOS posts** (punch-list follow-up) | S | DONE — `publish()` signs `"$id|$authorId|$content|$ts"` (authorId = our Android b64 id); verifier now requires POST signatures (closes the C1 lenient gap). Android already rejected our unsigned posts, so this is what makes them land. |
| ⏭️ 5 | **Media over mesh — download pipeline** | L | NEXT (the big one) — the biggest unlock; gated on the bridge relay design. iOS can't host inbound, so peer media must relay+cache through the JVM bridge. |
| 6 | **Media over mesh — upload/broadcast + chat media** | M | Builds on #5; enables sharing images/video/GIFs in posts + DMs. |
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
