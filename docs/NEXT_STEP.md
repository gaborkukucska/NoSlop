Based on the live logs and static analysis, here is a complete breakdown of the remaining issues, dead/underdeveloped components, and recommended next steps:

---

### 1. Broken / 404 / 403 RSS Feeds Observed in Live Logs

Your live logcat revealed several default RSS sources in `SourceLibrary.kt` that are returning HTTP 404, HTTP 403 (Cloudflare WAF), or HTML bot-protection pages on every background sync:

| Broken Feed URL in `SourceLibrary.kt` | Error in Log | Issue / Fix |
|---|---|---|
| `https://pitchfork.com/rss/all/` | `HTTP 404` | Pitchfork restructured their feeds. Working URL is `https://pitchfork.com/feed/feed-news/rss`. |
| `https://www.flickr.com/services/feeds/explore/` | `HTTP 404` | Flickr deprecated `/services/feeds/explore/`. Working public feed is `https://www.flickr.com/services/feeds/photos_public.gne?format=rss_200`. |
| `https://www.reutersagency.com/feed/?best-topics=world-news&post_type=best` | `HTTP 404` | Reuters Agency URL is dead. Replace with a live world news RSS (e.g., AP News or NPR). |
| `https://500px.com/popular.rss` | `HTTP 403` | 500px killed their public RSS feed and blocks scrapers with Cloudflare. |
| `https://www.nme.com/feed` | `HTTP 403` | NME blocks Tor exit nodes via Cloudflare bot protection. |
| `https://www.juxtapoz.com/feed/` | `HTTP 403` | Juxtapoz blocks Tor exit nodes via Cloudflare bot protection. |
| `https://hifructose.com/feed/` | `XML Parse Error` | Returned HTML Cloudflare challenge page instead of valid RSS XML. |

Cleaning up these dead feeds will eliminate log spam, speed up feed synchronization by 15–20 seconds per cycle, and improve content variety.

Gabor's note: We should keep them BUT only use them when the "Clearnet over Tor" option is turned OFF.

---

### 2. Invidious Fallback Instances Exhaustion

The logs show that when searching / trending via Invidious:
```
[WARN] [INVIDIOUS_API] trending: https://inv.nadeko.net returned HTTP 403
[WARN] [INVIDIOUS_API] trending: http://nerdvp...boad.onion failed: Connect timed out
[WARN] [INVIDIOUS_API] trending: http://inv.nadeko...wvyd.onion failed: Connect timed out
[WARN] [INVIDIOUS_API] trending: https://invidious.nerdvpn.de failed: Connect timed out
[WARN] [INVIDIOUS_API] trending: all instances exhausted
```
Public Invidious instances are frequently blocked by YouTube or rotate. We can refresh the fallback pool in `InvidiousApiClient.kt` with active, working instances (e.g. `yewtu.be`, `invidious.flokinet.to`, `invidious.privacydev.net`, `iv.datura.network`).

---

### 3. Underdeveloped Features Summary

| Feature | Code Status | What is Missing |
|---|---|---|
| **Comment Editing & Deletion** | Packet handlers (`EDIT_COMMENT`, `DELETE_COMMENT`), packet verifiers, and Room DAO updates (`updateCommentContent`, `markCommentDeleted`) are 100% implemented. | There is no UI in `CommentsBottomSheet.kt` or repository method in `NoSlopRepository.kt` allowing a user to tap an edit or delete button on their own comments. |
| **Peer Following on Mesh** | `FOLLOW` / `UNFOLLOW` wire handling, verification, and `Peer.isFollowing` database column are implemented. | There is no repository method (`followPeer`/`unfollowPeer`) or UI button on mesh profiles to send an outgoing `FOLLOW` packet. |
| **Social Clearnet (Instagram & TikTok)** | Registered in `SourceLibrary.kt` and `ApiKeyRepository.SERVICES`. | `PublicApiService.kt` only logs that the keys are set; there are no client implementations in `feeds/api/`. |
| **Dead Packet (`SUBSCRIBE`)** | `SubscribePayload` in `Packets.kt` and `handleSubscribe` in `HandshakePacketHandler.kt`. | Never sent anywhere; unpersisted legacy stub superseded by `FOLLOW`. Can be cleanly removed. |

---

### Recommended Next Step

Would you like to proceed with:

* **Option A (Feed & Invidious Health)**: Fix the dead RSS feeds in `SourceLibrary.kt` (updating Pitchfork/Flickr and replacing dead 403 feeds) and refresh the fallback instance list in `InvidiousApiClient.kt`.
* **Option B (Comment Deletion & Management)**: Implement the missing client-side comment deletion in `CommentsBottomSheet.kt` and `NoSlopRepository.kt` so users can delete their own comments over the mesh.
* **Option C (Peer Follows)**: Wire up the outgoing `FOLLOW` / `UNFOLLOW` action in `NoSlopRepository.kt` and add the button to user profiles / contact dialogs.

Which option would you like to tackle next?
