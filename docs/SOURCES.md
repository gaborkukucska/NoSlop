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
| **YouTube** (`YouTubeInternalClient`, `InvidiousApiClient`) | Search through the unofficial InnerTube interface (through the proxy Worker when configured), stream URLs resolved from it, played in ExoPlayer without YouTube's player or ads. Invidious public instances as a stream fallback. | YouTube Terms of Service (effective 2023-12-15): you may not access or display the Service except "as expressly authorized by the Service" or with written permission, may not "circumvent, disable … or otherwise interfere with any part of the Service", and may not access it "using any automated means (such as robots, botnets or scrapers)" except public search engines. Showing videos is authorized "through the embeddable YouTube player". The official Data API exists, and its terms also tie playback to YouTube's player. | **Conflict** |
| **Reddit** (`RedditApiClient`, subreddit RSS in `SourceLibrary`) | Unauthenticated `.json` listings and search through the proxy Worker, falling back to RSS. The RSS request sends a desktop Chrome User-Agent; the proxy request sends `android:com.noslop.app:<version> (by /u/NoSlopApp)`. | Reddit's Data API Terms (2023) and current docs: OAuth is required for the Data API; the free tier is for non-commercial use, 100 queries a minute per OAuth client; the User-Agent must be unique and descriptive, `platform:app_id:version (by /u/username)`, and must never pretend to be something else. RSS is not mentioned in what was read. | **Conflict** (no OAuth; browser User-Agent on RSS) |
| **Vimeo** (`VimeoApiClient`, `VideoPlayer`) | Search with the user's own API key. Playback reads `player.vimeo.com/video/<id>/config` for a direct stream, and falls back to Vimeo's embedded player. | Vimeo Developer Addendum (updated 2026-03-09): use only "content, data, or metadata made available by Vimeo" through its developer interfaces; don't "modify, obscure, or interfere with" its embeddable player; follow rate limits; don't imply endorsement. Reading the player config isn't a developer interface. | **Conflict** for direct streams; the embed fallback is **OK** |
| **Jamendo** (`JamendoApiClient`) | Track search with the user's own client id (through the proxy Worker when configured), streamed in the app. | API terms: free for non-commercial use. Credit the artist and Jamendo, and "provide a direct backlink from each Content in the Application to the relevant Content's page". No caching for offline use. Don't share API credentials with third parties. Rate limits may apply. | **Fix** (check the backlink and credit on every Jamendo slide) |
| **Pexels** (`PexelsApiClient`) | Photo and video search with the user's own key. | API guidelines: "show a prominent link to Pexels" and credit photographers where possible; don't "copy or replicate core functionality of Pexels"; 200 requests an hour and 20,000 a month by default. | **Fix** (check the Pexels link and photographer credit on slides) |
| **The Guardian** (`GuardianApiClient`, Guardian RSS feeds) | Article search with the user's own developer key; four Guardian RSS feeds. | Developer key: non-commercial, 1 call a second and 500 a day (as summarised by third parties; the Guardian's own terms page couldn't be read). | **Unchecked** |
| **Openverse** (`OpenverseApiClient`) | Keyless image and audio search. | Terms of Service: a product built on the API "must prominently indicate that it was made using Openverse but is not endorsed or certified by Openverse"; give proper attribution to CC-licensed works; follow rate limits and don't use several machines to get around them; don't scrape the catalogue. | **Fix** (add the notice; check CC attribution). Many Tor exits could count as "several machines"; the app doesn't do this on purpose, but say so if asked. |
| **Wikipedia, Wikimedia Commons** (`WikipediaApiClient`, `WikimediaApiClient`) | Keyless search and featured media. | User-Agent policy: a descriptive User-Agent with contact information; generic or browser User-Agents may be blocked. Text is CC BY-SA, media under each file's licence. | **OK** (User-Agent names the repository). Check that slides credit the author and licence. |
| **Art Institute of Chicago** (`ArtInstituteClient`) | Keyless artwork search, IIIF images. | API docs: send an `AIC-User-Agent` header naming the project and a contact email; anonymous clients get 60 requests a minute per IP. | **Fix** (the header has no contact email) |
| **NASA** (`NasaApiClient`) | APOD and the image library, with the user's key or `DEMO_KEY`. | api.nasa.gov: 1,000 requests an hour per key; `DEMO_KEY` is 30 an hour and 50 a day per IP, meant for exploring, and NASA recommends your own key for an app. NASA imagery is generally not copyrighted (check APOD, which often isn't NASA's). | **OK** with a key. `DEMO_KEY` over shared Tor exits will often be over its limit. |
| **Podcast Index** (`PodcastIndexClient`) | Trending and search with the user's key and secret. | Free API with a key; the software is MIT licensed. The API terms themselves weren't read. | **Unchecked** |
| **Internet Archive** (`InternetArchiveClient`) | Advanced search and item metadata, streams from archive.org. Sends a Chrome on Android User-Agent. | Terms weren't read for this file. | **Unchecked** (and see the User-Agent change below) |
| **Hacker News** (`HackerNewsApiClient`, HN RSS) | Algolia's HN search API, keyless. | No terms read for this file. | **Unchecked** |

## RSS and Atom feeds

About 45 publisher feeds in `SourceLibrary`, from Ars Technica to NME, plus Flickr's public photo feed. Publishers offer RSS for feed readers, and the app uses it that way: it shows the feed's own title, summary and image, and links to the article. The article reader can also read the page's description meta tag (`ArticleMetadataResolver`). Publishers' terms often limit feeds to personal, non-commercial use and require a link back; none was read one by one for this file.

| Source | Notes | Status |
|---|---|---|
| Publisher feeds (news, tech, science, culture) | `FeedParser` sends a desktop Chrome User-Agent to every feed. That is the one clear problem: it pretends to be a browser. | **Fix** (User-Agent); terms **Unchecked** one by one |
| Flickr public feed (`flickr-explore`) | The Flickr API terms don't cover feeds. They do say to respect each owner's rights and licence, and not to replicate Flickr. The app keeps each photo's author and the link to its photo page. | **OK** as far as read |
| Reddit subreddit RSS | See Reddit above. | **Conflict** (User-Agent) |

## Changes needed

These are the code changes that follow from the table. None is made yet.

1. **Honest User-Agents.** `FeedParser`, `RedditApiClient.fetchRss`, `InternetArchiveClient` and the YouTube clients send browser User-Agents. Send `NoSlop/<version> (+https://github.com/gaborkukucska/NoSlop)` everywhere except where a site's own rule asks for a format (Reddit's `android:…:… (by /u/…)`). Some feeds behind bot protection may then refuse, so test the whole list on a device before shipping, and record any that stop.
2. **AIC contact.** Add a contact email to `AIC-User-Agent`.
3. **Notices.** A "Sources" screen (or the About section) that lists each API source with its required line: "Made using Openverse but not endorsed or certified by Openverse", a link to Pexels, and credit to Jamendo.
4. **Per-slide credit.** Check that Pexels, Jamendo, Openverse and Wikimedia slides show the creator, the licence where there is one, and a link to the item's own page.
5. **Owner decisions.** YouTube (unofficial InnerTube, playback without YouTube's player), Reddit (no OAuth) and Vimeo (direct streams) go against those sites' terms as written. The options for each: keep it and say so plainly in the README and the source list; make it opt-in with that explanation; switch to the official route (YouTube and Vimeo embedded players, Reddit OAuth, which shares one 100-a-minute limit across every install); or drop it.

## Adding or changing a source

See "Add a source" in [CONTRIBUTING.md](../CONTRIBUTING.md). Add the source's row here in the same pull request.
