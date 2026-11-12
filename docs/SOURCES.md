# Clearnet Sources and Their Terms

The aggregator reads public feeds and APIs. This file records what each source's own terms say, whether the app follows them, and what is still open. It was first written on 2026-11-11 from the terms pages as they read then. It is a reading by a non-lawyer, not legal advice, and terms change: when you touch a source, check its terms again and update its row.

**Status**
* **OK**: the app's use fits the terms as far as they were read.
* **Fix**: allowed, but the app misses a condition (attribution, User-Agent, a notice). Each one is listed under "Changes needed" below.
* **Conflict**: the app's use goes against the terms as written. The owner decides whether to keep the source, change how it is used, or drop it.
* **Unchecked**: the terms couldn't be read, or weren't.

Code: `feeds/SourceLibrary.kt` (the list), `feeds/PublicApiService.kt` (when each API source runs), `feeds/api/` (one client per API), `feeds/FeedParser.kt` (RSS and Atom).

## API sources

| Source | How the app uses it | What the terms say | Status |
|---|---|---|---|
| **YouTube** (`YouTubeInternalClient`, `InvidiousApiClient`) | Search through the unofficial InnerTube interface (through the proxy Worker when configured), stream URLs resolved from it, played in ExoPlayer without YouTube's player or ads. Invidious public instances as a stream fallback. | YouTube Terms of Service (effective 2023-12-15): you may not access or display the Service except "as expressly authorized by the Service" or with written permission, may not "circumvent, disable … or otherwise interfere with any part of the Service", and may not access it "using any automated means (such as robots, botnets or scrapers)" except public search engines. Showing videos is authorized "through the embeddable YouTube player". The official Data API exists, and its terms also tie playback to YouTube's player. | **Conflict**, kept and disclosed (owner, 2026-11-12) |
| **Reddit** (`RedditApiClient`, subreddit RSS in `SourceLibrary`) | Subreddit RSS, with a desktop Chrome User-Agent. With a proxy set up in Settings, unauthenticated `.json` listings and search through it first, as `android:com.noslop.app:<version> (by /u/NoSlopApp)`. Official builds have no proxy. | Reddit's Data API Terms (2023) and current docs: OAuth is required for the Data API; the free tier is for non-commercial use, 100 queries a minute per OAuth client; the User-Agent must be unique and descriptive, `platform:app_id:version (by /u/username)`, and must never pretend to be something else. RSS is not mentioned in what was read. | **Conflict** (no OAuth; browser User-Agent on RSS), kept and disclosed (owner, 2026-11-12) |
| **Vimeo** (`VimeoApiClient`, `VideoPlayer`) | Search with the user's own API key. Playback reads `player.vimeo.com/video/<id>/config` for a direct stream, and falls back to Vimeo's embedded player. | Vimeo Developer Addendum (updated 2026-03-09): use only "content, data, or metadata made available by Vimeo" through its developer interfaces; don't "modify, obscure, or interfere with" its embeddable player; follow rate limits; don't imply endorsement. Reading the player config isn't a developer interface. | **Conflict** for direct streams, kept and disclosed (owner, 2026-11-12); the embed fallback is **OK** |
| **Jamendo** (`JamendoApiClient`) | Track search with the user's own client id (through the proxy Worker when configured), streamed in the app. | API terms: free for non-commercial use. Credit the artist and Jamendo, and "provide a direct backlink from each Content in the Application to the relevant Content's page". No caching for offline use. Don't share API credentials with third parties. Rate limits may apply. | **OK** since 2026-11-12: each slide names the artist and Jamendo, its source badge opens the track's Jamendo page, and About credits Jamendo |
| **Pexels** (`PexelsApiClient`) | Photo and video search with the user's own key. | API guidelines: "show a prominent link to Pexels" and credit photographers where possible; don't "copy or replicate core functionality of Pexels"; 200 requests an hour and 20,000 a month by default. | **OK** since 2026-11-12: each slide names the photographer, its Pexels badge opens the photo's page on Pexels, and About links to Pexels |
| **The Guardian** (`GuardianApiClient`, Guardian RSS feeds) | Article search with the user's own developer key; four Guardian RSS feeds. | Developer key: non-commercial, 1 call a second and 500 a day (as summarised by third parties; the Guardian's own terms page couldn't be read). | **Unchecked** |
| **Openverse** (`OpenverseApiClient`) | Keyless image and audio search. | Terms of Service: a product built on the API "must prominently indicate that it was made using Openverse but is not endorsed or certified by Openverse"; give proper attribution to CC-licensed works; follow rate limits and don't use several machines to get around them; don't scrape the catalogue. | **OK** since 2026-11-12: About carries the notice; slides show the title, creator, licence and provider, and the badge opens the work's page. Many Tor exits could count as "several machines"; the app doesn't do this on purpose, but say so if asked. |
| **Wikipedia, Wikimedia Commons** (`WikipediaApiClient`, `WikimediaApiClient`) | Keyless search and featured media. | User-Agent policy: a descriptive User-Agent with contact information; generic or browser User-Agents may be blocked. Text is CC BY-SA, media under each file's licence. | **OK**: the User-Agent names the app and a contact address; slides show the author and the file's licence, and the badge opens the file page |
| **Art Institute of Chicago** (`ArtInstituteClient`) | Keyless artwork search, IIIF images. | API docs: send an `AIC-User-Agent` header naming the project and a contact email; anonymous clients get 60 requests a minute per IP. | **OK** since 2026-11-12 (`AIC-User-Agent` names the contact address) |
| **NASA** (`NasaApiClient`) | APOD and the image library, with the user's key or `DEMO_KEY`. | api.nasa.gov: 1,000 requests an hour per key; `DEMO_KEY` is 30 an hour and 50 a day per IP, meant for exploring, and NASA recommends your own key for an app. NASA imagery is generally not copyrighted (check APOD, which often isn't NASA's). | **OK** with a key. `DEMO_KEY` over shared Tor exits will often be over its limit. |
| **Podcast Index** (`PodcastIndexClient`) | Trending and search with the user's key and secret. | Free API with a key; the software is MIT licensed. The API terms themselves weren't read. | **Unchecked** |
| **Internet Archive** (`InternetArchiveClient`) | Advanced search and item metadata, streams from archive.org. Sends a Chrome on Android User-Agent. | Terms weren't read for this file. | **Unchecked** (sends NoSlop's User-Agent since 2026-11-12) |
| **Hacker News** (`HackerNewsApiClient`, HN RSS) | Algolia's HN search API, keyless. | No terms read for this file. | **Unchecked** |

## RSS and Atom feeds

About 45 publisher feeds in `SourceLibrary`, from Ars Technica to NME, plus Flickr's public photo feed. Publishers offer RSS for feed readers, and the app uses it that way: it shows the feed's own title, summary and image, and links to the article. The article reader can also read the page's description meta tag (`ArticleMetadataResolver`). Publishers' terms often limit feeds to personal, non-commercial use and require a link back; none was read one by one for this file.

| Source | Notes | Status |
|---|---|---|
| Publisher feeds (news, tech, science, culture) | `FeedParser` sends NoSlop's User-Agent, and retries once as a browser only when a site refuses it (HTTP 403 or 406), remembering that host until the app restarts. The log names such hosts. | User-Agent **OK** since 2026-11-12 (NoSlop's, with one browser retry for a site that refuses it); terms **Unchecked** one by one |
| Flickr public feed (`flickr-explore`) | The Flickr API terms don't cover feeds. They do say to respect each owner's rights and licence, and not to replicate Flickr. The app keeps each photo's author and the link to its photo page. | **OK** as far as read |
| Reddit subreddit RSS | See Reddit above. | **Conflict** (User-Agent) |

## Changes made, and what is left

Done on 2026-11-12 (`NOSLOP_HONEST_USER_AGENT_V1`, `NOSLOP_SOURCE_CREDIT_V1`):

1. **Honest User-Agents.** `net/NoSlopUserAgent.value`, `NoSlop-Android/<version> (+https://github.com/gaborkukucska/NoSlop; noslop.me@proton.me)`, for every client except YouTube, Invidious and Reddit, and for Wikimedia and Art Institute images (other media downloads, images and streams, keep a browser User-Agent, since many hosts refuse other clients). `FeedParser` and `InternetArchiveClient` retry once as a browser only when refused.
2. **AIC contact.** `AIC-User-Agent` names the contact address.
3. **Notices.** Settings › About NoSlop lists the sources with Openverse's required line, Pexels and Jamendo credits, and a link here.
4. **Per-slide credit.** Media slides name the creator and source; the source badge opens the item's own page; Openverse and Wikimedia slides show the licence.

Kept as they are (owner, 2026-11-12), and said plainly in the README: **YouTube** (unofficial InnerTube search and playback without YouTube's player), **Reddit** (unauthenticated JSON through a proxy someone sets up, RSS with a browser User-Agent) and **Vimeo** (direct streams from the player config, the embedded player as fallback). The official routes, if that changes: YouTube's Data API for search with the user's own key and its embedded player for playback; Vimeo's embedded player; Reddit OAuth with a registered app.

Still open: the terms marked **Unchecked** above, and a device check that every feed still loads with NoSlop's User-Agent.

## Adding or changing a source

See "Add a source" in [CONTRIBUTING.md](../CONTRIBUTING.md). Add the source's row here in the same pull request.
