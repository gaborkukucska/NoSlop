# NoSlop — Log Review Notes

Quick triage of the attached logcat. Good news: almost all of this is *not*
NoSlop. Going through it line by line:

## Not NoSlop (ignore)

- `HeatmapThread` (PID 1155) — Samsung/OEM thermal monitoring service.
- `DigitalKey` / `ng.android.dkey` (PID 28570) — Samsung Digital Key (UWB car
  key) trying to init a UWB radio that isn't available; "GENERAL_ERROR" is
  this service's normal idle-state noise.
- `CAE` / `SensorHubParserProvider` (PID 1528) — Samsung sensor-hub service
  parsing logic, unrelated.
- `chromium: tile_manager.cc` (PID 30823) — some other app's WebView/Chrome
  tab running low on GPU tile memory.
- `ExynosCameraBufferManagerVendor` (PID 1093) — camera HAL buffer warning,
  almost certainly from the camera service idling/another app, not NoSlop's
  QR scanner (different PID, and NoSlop wasn't in camera-active state for
  this whole window).

None of these are caused by or affect NoSlop — just normal Android/Samsung
background chatter that happened to be captured in the same logcat window.

## NoSlop-specific lines

### 1. `selfhostedhero.com` — NXDOMAIN (typo, fixed)

```
All DNS resolvers failed for selfhostedhero.com: selfhostedhero.com: NXDOMAIN
```

This domain genuinely doesn't exist — `NXDOMAIN` from system DNS *and* both
DoH fallbacks (Cloudflare + Google) confirms it, not a connectivity issue.
The real site is **`selfhosthero.com`** (no "ed"). `SourceLibrary.kt`'s
`"selfhosted-hero"` entry had the typo baked in since it was added. **Fixed**
— updated the URL to `https://selfhosthero.com/rss` in the attached
`SourceLibrary.kt`.

### 2. `feeds.apnews.com` — NXDOMAIN-style failure (dead source, removed)

```
All DNS resolvers failed for feeds.apnews.com: feeds.apnews.com
```

This one isn't a typo — Associated Press discontinued their `feeds.apnews.com`
RSS feeds entirely (confirmed via multiple sources, including a GitHub repo
specifically built to scrape AP and re-publish it as RSS because AP stopped
offering it natively). The `"ap-top"` source in `SourceLibrary.kt` was
pointing at a domain that no longer exists. **Removed** — World News is still
well covered by Al Jazeera, BBC World, Reuters World, plus the NewsAPI and
Guardian API sources, so no replacement was added; a short comment explains
why it's gone in case anyone wonders later.

### 3. `rssfeeds.webmd.com` — DNS failure (likely transient, not fixed)

```
All DNS resolvers failed for rssfeeds.webmd.com: rssfeeds.webmd.com
```

Unlike the two above, this domain appears to be alive and well — it's
actively listed as a working WebMD RSS endpoint in multiple 2026 feed
directories, and a direct fetch attempt returned a "robots disallowed"
response rather than a DNS/connection error (which means *something*
resolved and answered). My best guess is this was a transient hiccup at the
moment of the sync (the failure happens on both the 00:18 and 00:33 sync
cycles in your log, but that's only ~15 minutes apart — one bad window can
easily repeat once). **Not changed** — if `rssfeeds.webmd.com` keeps failing
across syncs on different days, it's worth a second look, but I'd watch it
rather than rip it out based on one test session.

### 4. XML parse error — now includes the feed URL (fixed)

```
Error parsing XML stream | Unexpected token (position:TEXT  -->@440:50 in java.io.InputStreamReader@1b4bde0)
```

This error doesn't say *which* feed it came from — `parseStream()` never
received the URL, only an internal `sourceId`. Since several feeds were
being fetched around the same time, there's no way to know from this log
alone whether this was selfhostedhero, AP, WebMD, or something else entirely
that just happened to return malformed XML. **Fixed** — `fetchAndParse()` now
passes `feedUrl` through to `parseStream()`, and the error log is now
`"Error parsing XML stream for <feedUrl>"`. Next time this happens, the log
will tell you exactly which source to investigate. (Kept the new parameter
optional/defaulted so any other callers of `parseStream()` aren't broken.)

### 5. NASA APOD timeout — informational, not fixed

```
NASA APOD request failed | timeout
```

This is `NasaApiClient.fetchAPOD()` hitting `api.nasa.gov` with the public
`DEMO_KEY` (used automatically if you haven't set your own NASA API key in
Settings → API Keys). The `DEMO_KEY` is shared globally across everyone who
hasn't registered their own key and is both rate-limited and often slow —
occasional timeouts on it are expected/external, not a NoSlop bug. The
request is wrapped in try/catch and fails gracefully (empty list, no crash).
If this bugs you, getting a free personal key at api.nasa.gov and adding it
in Settings → API Keys will make APOD noticeably more reliable. Not changed.

## One more thing I noticed while in here (not from this log, flagged only)

`FeedParser.resolveRssUrl()` — the RSS auto-discovery helper from milestone
59 (checks `<link rel="alternate">` tags and probes `/feed`, `/rss`, etc. for
a bare site URL) — doesn't appear to be called from anywhere in the app
anymore. It's dead code as far as I can tell. Not related to tonight's logs
and not touched, but worth knowing it's there if "add a custom source by
pasting a site URL" doesn't currently do feed auto-discovery — that's why.

## Existing-install caveat

Because `feed_sources` rows are seeded once and kept via
`OnConflictStrategy.REPLACE` on `id`, an **already-installed app** (like the
one that produced this log) still has the old broken `"selfhosted-hero"` URL
and the dead `"ap-top"` row sitting in its local Room database — the
`SourceLibrary.kt` fix only affects fresh installs or a future re-seed (e.g.
after a DB version bump, or via `recoverSourcesAfterMigration()` if the DB is
ever wiped). For your current test device, you can just remove "Self-Hosted
Hero" and "Associated Press" from the active sources list in Settings if
they're annoying you in the meantime — they'll fail silently (caught
exceptions, logged, no crash) either way.

## Summary

| Item | Status |
|---|---|
| `selfhostedhero.com` → `selfhosthero.com` typo | **Fixed** (`SourceLibrary.kt`) |
| `feeds.apnews.com` (AP dropped RSS entirely) | **Fixed** — removed (`SourceLibrary.kt`) |
| `rssfeeds.webmd.com` DNS failure | Likely transient — watch, not changed |
| XML parse error missing feed URL | **Fixed** (`FeedParser.kt`) |
| NASA APOD `DEMO_KEY` timeout | Informational/external — not changed |
| `resolveRssUrl()` appears unused | Flagged only, out of scope |
