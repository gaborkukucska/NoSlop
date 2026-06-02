# Architecture Proposal: Clearnet Aggregation & Alternative Frontends

## Background
Currently, NoSlop routes all traffic (both mesh P2P gossip and content feed aggregation) through the embedded Tor SOCKS5 proxy (`127.0.0.1:9050`). Additionally, the public API pipeline relies on official APIs (YouTube, NewsAPI, etc.), which requires users to configure their own API keys to avoid rapid rate-limit exhaustion. 

The content aggregator is designed as a "hook" to provide immediate content to new users before their mesh network expands. It does not need the same level of cryptographic anonymity as direct peer-to-peer messaging.

## Objectives
1. **Sever Aggregation from Tor:** Route all RSS and public API fetching over the clearnet (standard internet) to dramatically improve refresh speeds and eliminate Tor exit-node blocking (e.g., Cloudflare CAPTCHAs).
2. **Alternative Frontends:** Replace official API clients with unauthenticated, privacy-respecting alternative frontends (e.g., Invidious for YouTube) that support automatic instance failover, removing the need for user API keys.
3. **Feed Prioritization:** Modify the feed algorithm to heavily favor mesh posts (P2P gossip) over aggregated clearnet content.

---

## 1. Severing Aggregation from Tor

Currently, `FeedParser` and the various `*ApiClient` classes utilize an `OkHttpClient` configured with a `Proxy.Type.SOCKS` pointing to port `9050`.

**Implementation Plan:**
1.  **Refactor HTTP Clients:** Create a centralized `HttpClientProvider.kt` that exposes two distinct OkHttp clients:
    *   `val clearnetClient: OkHttpClient` (Standard, no proxy)
    *   `val torClient: OkHttpClient` (SOCKS5 proxy on `127.0.0.1:9050`)
2.  **Route Separation:**
    *   Update `FeedParser.kt` to use `clearnetClient`.
    *   Update `feeds/api/*ApiClient.kt` to use `clearnetClient`.
    *   Ensure `MeshTransport.kt` and `MediaProxyService.kt` continue exclusively using `torClient`.
3.  **Media Loading:** Update Coil's `ImageLoader` and ExoPlayer's `HttpDataSource.Factory` to ensure that media fetched from the aggregator uses the clearnet, while media fetched via `noslop://` URLs continues routing through Tor via the `MediaProxyService`.

---

## 2. Alternative Frontends & Instance Failover

To eliminate the need for API keys, we will replace official API clients with scrapers or alternative frontend clients.

**Implementation Plan for YouTube (Invidious):**
1.  **Remove `YouTubeApiClient`:** Delete the existing YouTube Data API client.
2.  **Create `InvidiousApiClient`:**
    *   Implement an API client that queries the Invidious REST API (`/api/v1/search?q=query`).
    *   **Instance Pool:** Hardcode or dynamically fetch a list of known, reliable Invidious instances (e.g., `vid.puffyan.us`, `invidious.snopyta.org`).
    *   **Failover Logic:** Implement a round-robin or retry mechanism. If a request to Instance A times out or returns a 5xx error, immediately catch the exception and retry the request against Instance B.
3.  **Video Playback:** Invidious provides direct MP4 streams. Instead of using the YouTube WebView embed, we can parse the MP4 stream URL from `/api/v1/videos/{id}` and feed it directly into the native ExoPlayer.

**Other Alternative Frontends to evaluate:**
*   **SearXNG:** For general web and news searches, though JSON API access on public instances is often restricted to prevent bot abuse.
*   **Piped:** An alternative to Invidious for YouTube.
*   **Nitter (Archive):** For Twitter/X (though public instances are increasingly unstable due to X's API changes).

---

## 3. Feed Prioritization (Mesh Over Aggregation)

Currently, `UnifiedFeedTab.kt` sorts all `UnifiedItem` objects strictly by `timestamp` descending. This results in the feed being flooded with clearnet content, burying organic mesh posts.

**Implementation Plan:**
1.  **Weighting Algorithm:** Instead of a strict timestamp sort, implement a scoring algorithm for `UnifiedItem`.
    *   *Base Score:* The timestamp epoch.
    *   *Mesh Multiplier/Boost:* Artificially inflate the timestamp of `UnifiedItem.Mesh` objects by a factor (e.g., adding 24-48 hours to their effective sort time), ensuring recent mesh posts always float to the top of the feed.
    *   *Alternative:* Create a visually distinct "Priority Inbox" or "Mesh Highlights" horizontal carousel at the top of the feed for unread mesh posts, leaving the vertical pager below for chronological aggregation.
2.  **Pagination Adjustments:** Ensure the modified sort order logic plays well with future database pagination (e.g., Room Paging3), likely requiring the weighting logic to be moved to a custom SQL query in `Daos.kt`.
