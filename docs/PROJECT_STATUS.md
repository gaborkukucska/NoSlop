# Project Status - NoSlop

## Feed Playback Rounds 1–3 (2026-11-03): Summary and Work Remaining (v0.7.5-alpha)

These rounds fix the three reports in `isues.txt`, all on the legacy Android app: a swipe that stopped part way for a few seconds while the video kept playing, clearnet video that wasn't ready with Route Clearnet via Tor on, and video that stopped on "Finding stream" after leaving the app and coming back. The MVP is out of scope. No packet, Room schema or archive format changes. These rounds ship as 0.7.5-alpha (`versionCode` 75). Each round has its own entry below, newest first. Design notes: `docs/TECHNICAL_REFERENCE.md` §7.6 and §16.29–§16.33.

* **Verified on device** (owner's phone, with Route Clearnet via Tor, Foreground Service and Background Playback on). Owner sign-off, 2026-11-03: "Working well."
  * **Swipes.** No `JANK` line in the round 1 or round 3 capture. Feed batches took 2.4–4.9 s with about 31,500 stored rows. They now run off the main thread, so they can't hold up a swipe.
  * **Preload.** Round 2 capture: 12 of 12 slides were warm when claimed, 11 of them with 21–45 s buffered. First frames came in 103–581 ms.
  * **Leaving the app.** Round 3 capture: four trips away (36 s, 4 s, 17 s, 59 s). The video kept playing, its position moved with the clock, and there was no "Finding stream" and no stall.
  * **Retention.** First run on the test device: 31,538 rows down to 21,081 in 1.1 s.
  * **Exit rotation.** One blocked circuit now costs about 3 s. A video that needed three rotations resolved in 11.3 s; clean resolves took 1.8–2.6 s.
* **Not yet verified.**
  * **The Gradle unit test run for these rounds** (`./gradlew testGithubDebugUnitTest testPlayDebugUnitTest --rerun-tasks`). Maven and Google were out of reach where these rounds were written, so the pure logic and its new tests were compiled and run with `kotlinc` and stubs. The app itself was compiled on the owner's machine. Until that run passes, the entries below say "New tests", not "Tested".
  * **Coming back through the notification.** The log doesn't show which way a user returned, so the four trips above don't prove this path separately.
  * **Feed batch time after retention.** The round 3 capture has no `Feed batch built` line.
  * Everything under "Verify before release" below.
* **Owner decisions recorded (2026-11-03).**
  * **Retention.** NoSlop has no server, so the device must keep knowing what the user has seen and swiped away. Stored items last a little longer than the two months the Live Feed will show: 75 days. The viewed and swiped records last 90.
  * **Hub.** No Hub is deployed or linked. That repo still needs work, and the focus stays on the app.

### Work Remaining

This is the full list of open work on the legacy app as of 2026-11-03, with every item checked against the code. It replaces the old "Open Architectural & Technical Register" and "Consolidated Master Backlog", now in `docs/archived/PROJECT_STATUS_HISTORY.md`. Entries in this file or the history may say "still open" about items closed since; this list wins.

**Feed and playback**

* **History and Saved still build on the main thread.** Round 1 moved Live Feed and filter appends off it, not `loadHistoryBatch` or the Saved path. A very long list there can still hold up a swipe.
* **Every `feed_items` write reloads the whole table,** `fullContent` included. Retention cut the table by a third, but the reload remains.
* **`setImmersiveFullscreen` runs on every slide** rather than on a change.
* **`AudioPlayer` has no recovery path.** It no longer tears down mid-swipe, but it doesn't re-resolve in place the way `VideoPlayer` does (§16.33, §16.35).
* **A dead slide that reaches the screen still shows the unavailable card.** Dead slides leave the Live Feed once a preload finds them, for that session only (§16.31). A fast swipe can get there first.
* **Slow resolves remain when several exits in a row are blocked.** About 3 s per blocked circuit (§16.32). This is the exit lottery itself; no setting removes it.
* **A video can stop with no sign, and the cause isn't known.** Seen once, on 2026-11-03: no buffering, no pause icon, no error, and tapping didn't restart it. The app log shows the player made no state change for the 3.5 minutes before the owner's own tap and seek. It needs more testing: whether the sound stopped too, and a device logcat from when it happens.
* **The cause of the out-of-sync sound isn't proven.** Round 4 fixed it on the owner's phone, but two changes shipped together and no capture from that build has been read (§16.34). If it returns, read `AV_SYNC` lines first, then try the soft re-sync described there.
* **Decide whether `AV_SYNC` stays.** It writes roughly 15–20 lines per slide watched. It holds no video IDs or URLs apart from one redacted line per player. Keep it until a capture from these builds has been read.
* **A warm player never gets the 60 s back buffer.** ExoPlayer reads the back-buffer settings once, when the player is built, and a warm player is built with none. So rewinding on a warm slide downloads again over Tor. The comment on `AdaptiveTorLoadControl` still says otherwise. Found in round 4, not changed.
* **A warm neighbour can hide a stall.** `bytesDelta` in the stall sampler adds `PreloadManager.networkBytesReceivedInSession`, which counts every warm player. A neighbour preloading can make a stalled stream look alive. The same split means `bytesRecv` in `PLAYBACK_DIAG` samples stays at 0 for a claimed warm player.
* **`NO PROGRESS for 10s` can be wrong.** It also fires when the buffer is creeping forward, as it did in the capture of 2026-11-03 at 12:39. Read `delta` in the samples rather than this line.
* **Log noise.** Repeated send failures to offline mesh peers, and exhausted Invidious "channel joined date" lookups.

**Mesh and security**

* **A group catch-up answer can reach non-members.** `handleGroupQuery` still broadcasts when the asking member has no stored onion address.
* **Users invited by a member get group catch-up late** (G1).
* **Clocks must agree to within 1 hour** (`EnvelopeAuth.MAX_SKEW_MS`).
* **`mediaDigestFor` can block a same-revision v2 upgrade** of someone else's media that was downloaded unverified (D08). Nothing wrong is accepted; the post keeps its older signature.
* **DMs have no forward secrecy.** They use static X25519 keys. A Double Ratchet is planned, not started.
* **Signatures still accept the old pipe form.** `COMMENT`, `EDIT_COMMENT`, `DELETE_COMMENT`, `FOLLOW`, `PEER_REMOVED`, `CONNECTION_REJECTED` and sync check `encodeForSigning` and the pipe string. There is no single versioned encoder yet.
* **The database isn't fully encrypted.** DM and group bodies are. Feed items, history, peers and settings are plain SQLite under Android's file encryption. SQLCipher isn't added.
* **OTA updates are checked, not authenticated.** The APK's checksum and signing certificate are verified, but there's no Ed25519 signature over it and no certificate pinning on the update check. Don't describe OTA as MITM-resistant until that lands.
* **Key storage falls back to plain SharedPreferences** if the hardware Keystore is genuinely unavailable. A PIN-derived AES-GCM fallback isn't built.
* **Rate limits are fixed, per sender.** The adaptive token bucket across all packet types isn't built.
* **The default proxy secret ships in the APK.** Users can set their own proxy and secret, but the default can be extracted, so the Worker needs its own rate limits (audit item 9).
* **Groups have one admin.** Bans exist; a list of admins doesn't.
* **Deferred by the owner:** C27 (persistent per-peer connections) and R4d (handshake budget).

**Architecture**

* **`NoSlopViewModel` (3,782 lines) and `NoSlopRepository` (2,759 lines)** still span several domains. The plan is to split them along existing boundaries (Hub, chat, feed, DMs).

**Hub** (not started: no Hub is deployed or linked, and the app comes first)

* **Packets pulled from a Hub skip the envelope check,** and **the Hub's onion address is not checked against its key.** See Known Gaps in the Review Round 2 summary below.
* **Hub relaying (D04).** A linked Hub relays by its own rules.
* **Creator discovery, community mirroring and Home HUB enhancements.** Proposed, not implemented: `docs/NoSlop-Discovery-Mirroring-Home-HUB-Enhancement-Plan.md`.
* **Hub sync.** Merging DMs and posts after a failover, syncing Saved and Liked, offloading media downloads to the Hub, background Hub sync through WorkManager, and a daily encrypted backup push.
* **LAN fast path** with a self-signed Hub certificate and fingerprint pinning. Hub calls go over the `.onion` address for now.

**Search**

* **Older downvotes still show.** Those made before E5s were stored only as swipes.
* **Search quality** works, but needs more work later.

**Content and UI** (low priority)

* More no-auth image and video sources.
* Ad and tracker blocking in the article WebView. It filters no requests yet.
* User-selectable theme palettes.

**Verify before release**

* The Gradle unit test run above.
* A capture from rounds 4 and 5, from a release build through logcat, as `run-as` is refused there (`docs/SUPPORT.md` §4). Rounds 4 and 5 were signed off by watching, with no capture read.
* The GitHub update check on a device with Route Clearnet via Tor on, and **Open Downloads** on several phones.
* A minified release build, using `docs/BUILD.md` §8.2 (E5). Its media section now covers these rounds.
* Backup export and import on a device (E3).
* The findings the matrix still marks as not device-verified: C06, C07, C11, D05, D09 and D10.
* The P1 security fixes on a device: `GETINFO net/listeners/httptunnel` empty at bootstrap, the first-connect SSH host-key dialog, and two-device replay of comments and reactions. The commits were tested by unit tests; no device result is recorded here.

KMP and iOS work is tracked by the `mvp/` team in `docs/migration/`.

Closed on 2026-11-03 from the old backlog after checking the code: `FOLLOW`/`UNFOLLOW`, group bans, the `Dns.SYSTEM` fallback, the SSH host-key dialog and pin reset, Room schema export and `MigrationTest`, authenticated backups, HMAC request signing, the Tor leak rules, and circuit cycling (now per-stream, §18).

---

## Completed Changes (2026-11-05) — Feed Playback Round 5: Recovery in Place on Screen (v0.7.5-alpha)

* **Report.** A video watched for a long time buffered, showed "Finding stream" for about a minute with no Retry card, then carried on from the same point.
* **Cause.** A stall on screen released the player and resolved a new stream with nothing playing. It also waited 22–24 s to react, and a slow stream could avoid that limit altogether, because a few seconds of play reset the stall counters. The YouTube URL is tied to the exit's IP address, so getting off a bad circuit always needs a new URL.
* **Evidence.** No capture of the owner's event exists. A release build refuses `run-as`, and the capture supplied was from 0.7.4 on 2026-11-03. Its last video shows the same failure: about 14 minutes of normal play, then 8 KB/s where the stream needed about 42 KB/s, then no data at all. Apart from light mesh traffic the app was idle. The owner's answers fit this path: no Retry card, and the same position afterwards.
* **Fix.** A video that has played now recovers in place on screen, keeping its player and last frame. The old stream plays on while the new one resolves, and is kept if it has recovered. Over Tor, recovery starts after 12 s without progress, or 28 s of buffering in a 40 s window, which play bursts can't reset. Details in `docs/TECHNICAL_REFERENCE.md` §16.35.
* **Verified on device.** Owner, 2026-11-06: "The recovery is quicker and does not look for the stream."
* **Testing.** Tested (unit: `PlaybackPolicyTest`). The real `recoverInPlace` was also compiled in a harness and run through 14 scenarios.

## Completed Changes (2026-11-05) — Feed Playback Round 4: A Warm Player Starts with Its Picture (v0.7.5-alpha)

* **Report.** Many warm clearnet videos played with their sound out of sync. Swiping away and back reloaded the slide, and it was then in sync.
* **Found.** The first-frame wait added in `c7d782c` never held: `LaunchedEffect(isVisible, exoPlayer)` set `playWhenReady` right after the claim. Warm players also used `AUDIO_CONTENT_TYPE_MUSIC`, and fresh ones `MOVIE`.
* **Fix.** A warm video keeps its sound back until its first frame is on the slide, for at most 1.5 s, and uses `MOVIE`. `AV_SYNC` logging compares warm and fresh players. Details in `docs/TECHNICAL_REFERENCE.md` §16.34.
* **Verified on device.** Owner, 2026-11-05: "the videos are now all play video and audio in sync."
* **Not proven.** Both changes shipped together, so which one fixed it isn't known. Media3's own code shows nothing warm-specific that could leave the audio clock permanently off.
* **Testing.** Tested (unit: `PlaybackPolicyTest`, `FeedSmoothnessArchitectureTest`).

## Completed Changes (2026-11-03) — GitHub-Only Update Check

Not released yet: this follows 0.7.5-alpha and needs a version bump to reach testers. New tests: `UpdatePolicyTest`.

* **Update check** (`NOSLOP_GITHUB_UPDATES_V1`, §15.2). The GitHub build now reads the APK and checksum from the latest GitHub release and never asks noslop.me. Before, the website's APK link ended the check before it reached GitHub, so the release's file list was never read. With 0.7.5 every check logged `checksum=MISSING`, and every tap ended in "This release publishes no checksum".
  * Over Tor, each call gets three tries on separate circuits, since GitHub limits by exit IP.
  * A check runs at most once an hour rather than on every return to the app, unless a known update still has no checksum.
  * A tap on a known update with no checksum tries once more before refusing.
  * A finished download now shows a message that stays until dismissed, with a green shield for the verified checksum and an **Open Downloads** button (`NOSLOP_UPDATE_READY_V1`). The Settings banner remembers the saved file across restarts, so it stops offering the same download as new. The install itself stays manual, because Play Protect flagged the in-app installer.
  * `Constants.UPDATE_CHECK_URL` and the URL version parsing are gone.

## Completed Changes (2026-11-03) — Release 0.7.5-alpha, Documentation Reorganised (v0.7.5-alpha)

* **Version.** 0.7.5-alpha, `versionCode` 75, for a GitHub release to testers. It carries feed playback rounds 1–3.
* **Privacy policy.** It names the Android app, and its effective date moves to 2026-11-03, as the policy promises for any change. The in-app link opens this file on GitHub, so it stays at `docs/PRIVACY_POLICY.md`.
* **Merged.** `docs/DEBUG.md` is now part of `docs/SUPPORT.md`, and `docs/RELEASE_SMOKE_TEST.md` is now `docs/BUILD.md` §8, with the release steps added.
* **Archived.** The originals of those two, `docs/HANDOFF_GABOR.md` (the iOS merge it describes is done; its ongoing rules moved to `docs/migration/README.md` §5), and this file's entries from 2026-10-06 and earlier, now `docs/archived/PROJECT_STATUS_HISTORY.md`.
* **Backlog.** The old register and master backlog were checked against the code and folded into Work Remaining above.
* `docs/BUILD.md` §6 now gives flavour-aware build commands and paths.

## Completed Changes (2026-11-03) — Feed Playback Round 3: Feed Retention, Faster Exit Rotation (v0.7.5-alpha)

The device had 31,521 feed rows and the table only ever grew. Round 2's capture also showed 25 of 40 circuits fully refused, none of them rescued by a later client. New tests: `FeedRetentionTest`, and additions to `YouTubePlayabilityTest`. On device: see the summary above.

* **Feed retention** (`NOSLOP_FEED_RETENTION_V1`, `data/FeedRetention.kt`, §7.6). Runs at most once a day, 45 s after the view model starts and after each background sync.
  * The windows are ordered so the device's memory stays intact: the Live Feed shows up to 60 days, a dated item stays stored for 75, and the viewed and swiped records last 90.
  * An item fetched in the last 7 days stays, so older items a search brought in aren't removed under the user.
  * Unread items are capped at 10,000, oldest fetched first.
  * Saved items are never removed. Anything in `viewed_history` stays for as long as its record does, which is 90 days.
  * Undated items and items from archival sources have no age filter, so a refetch can bring them back. For those a read row is the memory, and it stays.
  * `FeedDao` gains `deleteStaleItems`, `deleteStaleUnreadItems`, `getRemovableUnreadCount` and `deleteOldestUnreadItems`. `EngagementRepository` and the view model take their windows from `FeedRetention`.
* **Faster exit rotation** (`NOSLOP_FAST_EXIT_ROTATION_V1`, §16.32). ANDROID won 54 of 54 resolves in the app log, and no other client ever rescued a refused circuit. So for the first six attempts, one refusal from the first client moves to the next circuit, and the last client that won goes first. The budget rises to eight attempts over Tor.
* **A video that no longer exists stops sooner.** `ERROR` from the first client on two circuits, with the other clients unable to play it, counts as unplayable (`MAX_ERROR_CIRCUITS`). One such video took six circuits and 30 s.
* **`VIDEO_AWAY` logs** add `wants`, `suppressed` and `userPaused`, so a capture shows why a player isn't moving.

## Completed Changes (2026-11-03) — Feed Playback Round 2: Preload Lookahead, Dead Slides (v0.7.5-alpha)

Owner's report on round 1: only 2 to 3 videos were preloaded, and the following 2 to 3 needed buffering or "Finding stream". That was a design flaw in round 1. New tests: `TorLookaheadTest`, `YouTubePlayabilityTest`.

* **Lookahead no longer waits on a slide** (`NOSLOP_TOR_PRELOAD_BUDGET_V1`, §16.30). Round 1 waited for each slide to be ready, so one dead or slow slide left everything behind it cold. `warmInOrder` gives each slide a 6 s head start instead, ready or not, and up to three slides ahead are warmed.
* **Dead slides** (`NOSLOP_DEAD_SLIDES_V1`, §16.31).
  * `YouTubeInternalClient` remembers videos that can't play over Tor for 30 minutes, so preloads skip them. A tap on Retry always asks again.
  * The Live Feed drops them for the session. It never drops the slide on screen, and it never touches search.
  * A route change forgets all of this.
  * `YouTubePlayability` tells age-gated or private videos from exit-dependent refusals. An exit-dependent refusal tries another circuit, up to twice.
  * Clients that answer `OK` with nothing playable on two circuits count as unplayable.
* **Preloads can't take every resolve slot.** `speculativeResolveGate` holds them to two of the three permits, so the slide on screen always has one.
* **`ReactionAnchor` cache (a round 1 bug).** Its cap of 8,192 was below the 31,521 rows it was walked over, and an LRU smaller than a sequential scan hits 0% of the time. `MAX_CACHED` is now 65,536.
* **Less work on an append.** `feedKeyCache` keeps canonical keys, cheap rejections run first, and a second build is skipped once the list has grown.
* **Logs.** Preload lines redact tags, and the claim line reports state and buffered time.

## Completed Changes (2026-11-03) — Feed Playback Round 1: Swipes, Teardown, Coming Back to the App (v0.7.5-alpha)

Closes the three reports in `isues.txt` as first diagnosed. New tests: `ReactionAnchorTest`, `PreloadTargetsTest`, `PlaybackPolicyTest`, `FeedSmoothnessArchitectureTest`.

* **Swipes no longer wait on feed batches** (`NOSLOP_FEED_BUILD_OFF_MAIN_V1`, §16.29). `beyondViewportPageCount = 2` composes pages near the end of the list on the first frames of a swipe, which fired `loadMoreFeedItems` on the main thread. With 31,521 rows, that took over 3 s. The pager froze while the video kept playing, since its decoder doesn't need the main thread. `loadMoreFeedItems` now dispatches `computeFeedBatch` to `Dispatchers.Default` for appends, and `applyFeedBatch` publishes the result on the main thread after a staleness check. A list built from empty stays synchronous, so there's no empty-state flash. History, Saved and Author stay synchronous too.
* **Player teardown no longer blocks** (`NOSLOP_SMOOTH_TEARDOWN_V1`). `release()` can block the main thread for up to 500 ms and a surface detach for up to 2 s. A slide leaving the screen pauses, stops after 200 ms and unmounts after 450 ms. `PreloadManager.retire` stops a player now and releases it 1.2 s later.
* **Preload follows the list, not the page** (`NOSLOP_PRELOAD_TARGETS_V1`). `selectPreloadTargets` is keyed on the items and the settled page, so a list that changes under a still pager warms the next slide. Warm-ups wait up to 1.5 s while the feed scrolls.
* **Coming back to the app** (`NOSLOP_AWAY_RECOVERY_V1`, §16.33).
  * A player that stalls or fails while the app is away is kept, and a fresh stream is swapped in at the same position, up to three times. The normal retry clears `source`, which released the player and stopped the sound.
  * `canCountAsStall` stops suppressed playback (READY, `playWhenReady` on, not playing) counting as a stall. Those samples were tearing down healthy players.
  * A visible slide keeps the URL it started with (`NOSLOP_HELD_PLAYBACK_URL_V1`), and a retry key is handled once (`NOSLOP_RETRY_ONCE_V1`).
  * `ERROR_CODE_TIMEOUT` counts as recoverable, and `isVideoActive` survives a slide's dispose while another video is playing.
* **Returning from the notification** no longer recreates the task: `NoSlopForegroundService` uses `FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_SINGLE_TOP`. `AndroidManifest.xml` extends `configChanges` so more configuration changes keep the activity.
* **Diagnostics.** `MainThreadStallMonitor` logs `JANK` when the main thread is silent for 600 ms, while the app is started. `VIDEO_AWAY` logs each trip away.
* **Reaction anchors** moved to `data/ReactionAnchor.kt`. The IDs it produces are unchanged.
* **Threading.** `@Volatile` on the view model's caches and concurrent sets for what a background build reads. `viewedHistoryIds` flows on `Dispatchers.Default`.

## Completed Changes (2026-10-21 – 2026-10-30) — Versions 0.7.1 to 0.7.4, P1 Security Fixes (v0.7.4-alpha)

Added to this file on 2026-11-03 from the commit log. The status file had no entries for this work. Detail: `docs/TECHNICAL_REFERENCE.md` §24.

* **0.7.1-alpha (2026-10-21).** Version bump.
* **0.7.2-alpha (2026-10-29).** `tor-android` 0.4.8.16 → 0.4.9.11: the end-of-life daemon exited on a new consensus and killed the app about 10 s after start. `compileSdk` 36 → 37, which 0.4.9.x needs. Adds `scripts/pull_noslop_crash.py`.
* **0.7.3-alpha (2026-10-29).** `tor-android` 0.4.9.11 → 0.4.9.14, since Play Protect flagged the 0.4.9.11 build. `compileSdk` 37.1, which 0.4.9.13+ needs. `targetSdk` stays 36.
* **0.7.4-alpha (2026-10-29).** The `github` flavour saves a verified update to the Download folder instead of installing it, and drops `REQUEST_INSTALL_PACKAGES`: Play Protect flagged the in-app installer (`NOSLOP_SAVE_TO_DOWNLOADS_V1`). The D1 note further down in this file predates this.
* **P1 security fixes (merged 2026-10-30).** New tests: `TorrcTest`, `SshHostKeyPolicyTest`, `ReplayAndOriginP1Test`, `MediaOwnershipAndChunkSecurityTest`, additions to `GossipServiceTest`. Nothing signed or sent on the wire changes.
  * **Tor.** `HTTPTunnelPort 0` closes the unauthenticated HTTP CONNECT proxy that `tor-android` opened on loopback for any local app.
  * **SSH deploy.** The first-connect host-key dialog now appears. A changed key is refused, and with no confirmation callback the deploy fails closed.
  * **Comments and reactions.** A replayed packet can't restore a deleted comment, roll back an edit or undo a newer reaction or vote.
  * **Origin.** `ANNOUNCE_DISCOVERABLE` is refused unless the onion derives from `authorId`, and an unsigned `origin_node` counts only if it is the author's own onion.
  * **Gossip.** Packets buffered by the firewall now reach their handler. Before, they were marked processed and lost. Per-sender state is locked and capped, and dedup includes the payload digest, so a reused id can't burn a real packet.
  * **Media.** An ACL row authorizes only if its author matches every restricted row for that media id. Chunks must come from the addressed sender, at a requested offset. The hash is checked before the part file is renamed, which closes the "any offset" item from Round B.
* **Fork merge.** The iOS port and upstream merges are under `mvp/` and out of scope here.

## Review Round 2 Follow-up (Rounds E1–E5, E5s, E5s2, 2026-10-13): Summary and Known Gaps (v0.7.0-alpha)

These rounds close what was still open from review round 2 (`isues.txt`: D11, D12 and the C-findings they carry) and the leftovers of D04. The legacy Android app only; the MVP is out of scope. Each round has its own entry below, newest first.

* **Verified so far.** Unit tests pass (`./gradlew testGithubDebugUnitTest testPlayDebugUnitTest --rerun-tasks`) after every round. The app was built and installed after every round. On device: search (E5s, E5s2), and video, audio and seeking on Media3 1.11.1 (E5).
* **Not yet verified.**
  * Backup export and restore on a device (E3).
  * A minified release build, using the checklist in `docs/BUILD.md` §8.2 (E5). Unit tests run the debug build, which R8 does not shrink, so they cannot catch a missing keep rule.
* **Owner decisions recorded (2026-10-13).**
  * **C09: hard protocol break.** A packet without a valid envelope signature is dropped. Builds before E2 can no longer talk to E2 builds; both sides must update.
  * **C16:** kept as it is, with the red warning banner. No passphrase option is added.
  * **C27** (persistent per-peer connections) and **R4d** (handshake budget): deferred.
  * **Default creator node:** the same legacy app, without a Hub.
  * **Search:** results depend only on the search terms and the filters in the search modal. They are listed newest first and loaded page by page as the user swipes. Items already seen may appear. Blocked creators, banned channels, banned nodes and anything the user voted down stay hidden.
* **Known gaps** (open, not fixed in these rounds).
  * **Packets pulled from a Hub skip the envelope check.** Hub pulls and Hub web-UI DMs are built inside the app and never cross the mesh socket, which is the only place the envelope is checked (`EnvelopeAuth` in `MeshTransport`).
  * **The Hub's onion address is not checked against its key.** Peers' announced onions are (S2), but the Hub's address is taken as configured.
  * **Clocks must agree to within 1 hour.** A packet whose envelope time is further from the receiver's clock is dropped as stale (`EnvelopeAuth.MAX_SKEW_MS`).
  * **A group catch-up answer can reach non-members.** When the member asking (`GROUP_QUERY`) has no stored onion address, `handleGroupQuery` broadcasts the `GROUP_SYNC` answer. `GossipService.broadcast` sends it to every trusted contact and every group member, so friends who are not in the group receive its member keys, names and onion addresses. Found while writing these docs; not fixed yet.
  * **Users invited by a member get group catch-up late.** A `GROUP_QUERY` is answered only for a key in the answering node's member list (G1). Someone invited by a member, not the admin, is answered only by members whose copy already lists them.
  * **Older downvotes still show in search.** Downvotes made before E5s were stored only as swipes and cannot be told apart, so they are not hidden.
  * **Search quality.** It works, but needs more work later (owner, 2026-10-13).
  * **Hub relaying (D04).** With a Hub linked, broadcasts are handed to the Hub, which applies its own relaying rules.

## Completed Changes (2026-10-13) — Round E5: Media3 1.11.1, Narrower Bouncy Castle Keep Rule, Release Smoke Test (v0.7.0-alpha)

Closes C24 and the Media3 item of D11, and the smoke-test half of C27 (D12). Unit tests: `AdaptiveTorLoadControlE5Test` and `ReleaseBuildRulesE5Test` (new). On device: video swiping, seeking, audio and video attachments work. The minified release build has not been checked yet.

* **Media3 1.3.1 → 1.11.1.** All seven Media3 libraries (`exoplayer`, `transformer`, `effect`, `exoplayer-hls`, `exoplayer-dash`, `ui`, `datasource-okhttp`) move together.
  * **Why the version bump alone would have broken video.** Since 1.4, Media3 calls the `LoadControl` methods that take a `PlayerId` (and, for decisions, a `Parameters` object). The old methods still exist, but their default implementations throw. `AdaptiveTorLoadControl` (`ui/PreloadManager.kt`) implemented only the old ones, so the app would have built and then failed on the first video.
  * **Fix.** `AdaptiveTorLoadControl` now implements the same ten methods as Media3's own `DefaultLoadControl`: `onPrepared`, `onTracksSelected`, `onStopped`, `onReleased`, `getAllocator`, `getBackBufferDurationUs`, `retainBackBufferFromKeyframe`, `shouldContinueLoading`, `shouldStartPlayback` and `shouldContinuePreloading`. Lifecycle calls go to both inner controls (preload and on-screen); decisions go to the active one. The buffer sizes are unchanged.
  * Since 1.9 Media3 needs API 23 or newer; the app's minimum is 24.
* **Bouncy Castle keep rule narrowed.** `-keep class org.bouncycastle.** { *; }` is replaced by keeping only the provider packages: `org.bouncycastle.jce.provider.**`, `org.bouncycastle.jcajce.provider.**` and `org.bouncycastle.pqc.jcajce.provider.**` (plus `-dontwarn org.bouncycastle.**`).
  * The app calls the lightweight API directly (Ed25519, X25519, HKDF, Argon2, ChaCha20-Poly1305, SHA-3, PBKDF2), so R8 can see what it needs and keeps it.
  * Only the provider registered in `NoSlopApp` loads classes by name, and no app code asks it for an algorithm by name.
* **Release smoke test.** New `docs/RELEASE_SMOKE_TEST.md` (now `docs/BUILD.md` §8.2): a step-by-step check of a minified release build on two devices (identity, DMs, backup, media, Tor), with the log strings to look for. Shrinking problems only show up in that build.
* **Tests.**
  * `AdaptiveTorLoadControlE5Test` runs a player through prepare, track selection, stop and release on the wrapper, with a one-item streaming timeline. It checks that the back buffer switches between preload (none) and on screen (60 s).
  * `ReleaseBuildRulesE5Test` fails if any of these comes back: Bouncy Castle kept whole; app code asking the provider for an algorithm by name; Media3 libraries on different versions or older than 1.4; the wrapper missing one of the ten methods, or still overriding an old one.
  * First run: two test-only failures, fixed in E5b. The test passed an empty timeline, which `DefaultLoadControl` cannot handle, and the source check did not allow an override's parameters on the next line.

## Completed Changes (2026-10-13) — Search Round, Part 2 (E5s2): Every Source Pages (v0.7.0-alpha)

Unit tests: `SearchPagingE5s2Test` (new) and `SearchPagingE5sTest` (updated). On device: searches in each tab page through as expected.

* **Images, audio and articles page too.** Part 1 paged only YouTube, Invidious and Reddit. Every source that can page now does, as the user swipes (`feeds/api/SourcePage.kt`).
  * **Images** (Openverse, Wikimedia, Pexels, NASA): by relevance, because these sources have no dates or cannot sort by them.
  * **Articles:** Wikipedia by relevance. Hacker News, NewsAPI and the Guardian newest first.
  * **Audio:** Openverse, and the Internet Archive, which now pages newest first instead of by most downloaded. Podcast Index cannot page, so it only fills the first page.
  * **All:** NewsAPI, Jamendo and Pexels page too, next to YouTube, Invidious and Reddit.
* **Limits.**
  * NewsAPI stops after 5 pages. Its free plan serves only the first 100 results, and every page uses one of its 100 daily requests.
  * A source with no key set, or one that fails 3 times in a row, stops for that search, so it does not hold the search open.
* **Logs.** Jamendo no longer writes search terms to the log (C19).

## Completed Changes (2026-10-13) — Search Round, Part 1 (E5s): Paging Instead of Random Extra Words (v0.7.0-alpha)

Owner decisions: searches depend only on the search terms and the search-modal filters, are listed newest first with no date limit and keep loading as the user swipes; already-seen items may appear; blocked and banned content and downvoted items stay hidden. Unit tests: `SearchPagingE5sTest` (new). On device: search works; it needs more work later.

* **Cause.** After E2b, testers found searches worse: not the most relevant results, and not everything for the terms. E2b was not the cause. The causes were:
  * "All" searches went through the background interest-feed path, which asks YouTube for this year's uploads only.
  * Invidious searches were limited to the last month (`date=month`).
  * When the results ran out, the search ran again with a random extra word ("review", "vlog", …), up to 3 times.
  * Results were sorted videos first.
* **Fix.**
  * **Paging.** A search fetches the next page of the same terms and filter, newest first, with no date limit: YouTube continuation tokens (sorted by upload date), Invidious page numbers (`sort_by=upload_date`, every date), and Reddit listing cursors (`sort=new`, `after`). Where each source has got to is kept in `PublicApiService.SearchCursor`, one per search. The next page starts loading while the user is still swiping through the current results.
  * **When paging stops** (`ui/SearchPaging.kt`): every source has reported its end, or 3 pages in a row added nothing new, or the search has fetched 50 pages.
  * **Order.** Results show page by page, newest first within each page. Videos no longer jump to the front (this replaces the "videos first" sort of §20.6 in TECHNICAL_REFERENCE).
  * **"All" searches** have their own path and no longer use the interest-feed one.
  * Searches made outside the search window send the same requests as before.
* **Hidden from search results.**
  * Blocked creators and banned channels, as before.
  * Anything the user votes down (downvote, slop, vomit, clown, NoSlop), from now on. The keys are stored in the app setting `downvoted_items` (newest 3,000 kept). Downvoted items also stay out of the Videos, Audio, Images and Articles tabs, but still show in History, Liked, Saved and My Content.
  * Posts from banned nodes.
* **Logs (C19).** Reddit no longer logs full URLs, which carried the search terms. YouTube no longer logs response bodies.
* **Known gap.** Downvotes made before this round were stored only as swipes, so they cannot be hidden.

## Completed Changes (2026-10-13) — Round E4: Reactions and Votes as the Post's Identity, Share Permanent Identity, GROUP_QUERY Sender (v0.7.0-alpha)

Closes two D04 leftovers and a group catch-up failure seen on device after E2. Unit tests: `IdentityLeftoversE4Test` (new). App built and installed; tests pass.

* **Reactions and votes (D04 leftover).**
  * **Cause.** Reactions and votes on posts and comments were always made by the personal identity. Relays carried them to creator contacts, which linked the two identities.
  * **Fix.** A reaction or vote is made by the identity that comments on that post (`IdentityRouter.forPostAuthor`): the creator identity on our own creator posts and on posts by peers who know us as a creator, the personal identity otherwise. Taking one back always goes out from the identity that made it, including reactions made before this round.
* **"Share Permanent Identity" in a temporary chat (D04 leftover).**
  * **Cause.** The request was sent as the secondary identity, because the contact was bound to it.
  * **Fix.** The request now comes from the personal identity. The contact stays bound to the secondary identity, so the temporary chat keeps working, until they answer. Only the answer to that request's nonce moves the contact to the personal identity and makes it permanent (`MeshSocialRepository.completeMainIdentityShare`; the nonce is kept in `main_share_nonce_<key>`). While the request is pending, it is probed from the personal identity too.
* **Group catch-up was refused (G1, after E2).**
  * **Cause.** `GossipService.broadcast` re-stamped the sender of every group packet per peer, while the `GROUP_QUERY` payload kept naming the original requester. Since E2 a query is answered only when the requester is the sender, so peers that knew our other identity refused it.
  * **Fix.** `GROUP_QUERY` takes the per-identity route of other broadcasts (`outboundFor`): sent unchanged, as a relay that keeps its sender, or not at all.
* **Still open from D04:** Hub relaying (a linked Hub relays on its own terms).

## Completed Changes (2026-10-13) — Rounds E2b and E3: Release-Build JSON Reads, Streaming NSG2 Backups (v0.7.0-alpha)

Closes C07 and C06 (D12). Unit tests: `BackupArchiveFormatE3Test`, `BackupNsg2E3Test`, `PreferencesRepositoryE2bTest`, `GroupChatParsingE2bTest` (new); `CodeHygieneArchitectureTest`, `BackupImportResultD09Test`, `BackupWordCloudFormsTest`, `WordCloudRuleTest` (updated). App built and installed; tests pass. **Backup export and restore have not been checked on a device yet.**

* **E2b: settings that release builds never read.**
  * **Cause.** In R8 full mode, `-keepattributes Signature` only applies to kept classes, so an anonymous `TypeToken` subclass lost its type argument and threw at run time. In release builds, saved categories, genres, keywords, banned nodes, and group bans and member names were never read back.
  * **Fix.** These values are parsed through array classes or `JsonObject` (`PreferencesRepository`, `GroupChat.parseGroupMemberHandles` / `parseGroupBannedMembers`). Gson's recommended `TypeToken` keep rules are added for libraries. `CodeHygieneArchitectureTest` now fails if app code uses `TypeToken` again.
  * **Logs (C19).** Invidious search, channel-search and resolve request labels no longer carry search terms or video ids. The hygiene test forbids that too.
* **E3: backups are "NSG2" (C07, C06).**
  * **Before.** "NSG1" encrypted the whole file with one AES-256-GCM stream under the first 32 bytes of the Word Cloud seed (the same seed the identity keys come from). A plaintext zip was written to disk on export and on restore, and the group messages went to a plaintext file.
  * **Format** (`data/BackupArchiveFormat.kt`):
    * header = `"NSG2"` | version (1) | salt (16) | nonce prefix (7);
    * key = HKDF-SHA256 of the Word Cloud seed, with the archive's salt and the info `noslop-backup-v2`, so every archive has its own key and the key is never the raw seed;
    * body = the chunk records of the creator identity file (NSC1, D10d): AES-256-GCM over at most 1 MiB each, the chunk number and a last-chunk flag in the nonce, the header as associated data.
  * **Streaming.** The zip is encrypted as it is written and decrypted as it is read, so no plaintext backup, zip or group-message file is written to disk on export or restore. Each chunk is authenticated before its bytes are used.
  * **Refused before anything changes:** a truncated, reordered, modified or extended archive, and an export that failed part way.
  * **Older backups.** NSG1 and pre-NSG1 CBC backups still restore; a CBC backup only after the user confirms, because it is not authenticated. `secureDelete()` (claimed in 2026-10-06, never written) is not needed any more.
  * Tests that read exports as NSG1 now go through `BackupArchiveTestSupport`.

## Completed Changes (2026-10-13) — Round E2: Envelope Signature on Every Packet, ANNOUNCE_PEER v2, GROUP_QUERY for Members Only (v0.7.0-alpha)

Closes C09 and S2 (D12), and most of G1, found while tracing C09. **Hard protocol break** (owner decision): nodes before E2 can no longer talk to E2 nodes. Unit tests: `EnvelopeAuthTest`, `AnnouncePeerAndGroupQueryE2Test` (new); `HandshakeReplyPathTest`, `MeshPacketVerifierTest`, `WireProtocolTest` (extended). App built and installed; tests pass.

* **Envelope signature (C09).**
  * **Cause.** `sender_id` was taken on trust. Anyone who could reach an onion could claim to be one of its contacts for the packet types with no signature of their own (`TYPING`, `READ_RECEIPT`, `SYNC_REQUEST`, `INVENTORY_SYNC_REQUEST`, `DM_SYNC_REQUEST`, `GROUP_QUERY`, `MEDIA_TRANSFER_ACK`). The firewall, the rate limits and the cooldown reset all trusted it.
  * **Fix** (`mesh/EnvelopeAuth.kt`). Every packet carries `env_v`, `env_ts` and `env_sig`: an Ed25519 signature by the identity named in `sender_id` over the packet id, type, sender, target, inner signature, envelope time and a SHA-256 of the payload. Only `hops` is left out, because relays change it.
  * **Sending.** `MeshTransport` signs every packet as the identity that sends it. A relay that keeps the original sender keeps that sender's envelope. A packet that cannot be signed is not sent.
  * **Receiving.** On the listener and on the handshake reply path, a packet that is unsigned, invalid, more than 1 hour from our clock, or a replay is dropped before any handler runs.
* **ANNOUNCE_PEER v2 (S2).**
  * **Cause.** The signature covered only the author and the time, so a relay could replace the onion address in a fresh announce, and receivers stored it.
  * **Fix.** The signature now covers the onion address (`CryptoService.canonicalAnnouncePeerV2`, label `noslop-announce-v2`); v1 announces are refused. Only an onion address derived from the sender's key is stored (`CryptoService.isOnionOf`).
* **GROUP_QUERY for members only (G1).**
  * **Cause.** The answer is the whole group record (member keys, names, onion addresses). Groups that allow member invites answered anyone who knew the group id, former members included.
  * **Fix** (`groupQueryRefusal`). A query is answered only for the authenticated sender, when it is a member or the admin, is not banned, and is also the requester named in the query. The answer goes to the sender's stored onion address; the requester's address is no longer used.
  * **Still open** (found in the docs round, see Known Gaps): when the sender has no stored onion address, the answer is still broadcast, which sends the group record to all trusted contacts as well as to group members.

## Completed Changes (2026-10-13) — Round E1: Smaller Items from D11 (v0.7.0-alpha)

Closes D11, apart from the Media3 upgrade (E5). Released together with E2 (commit `c40654e`). Unit tests: `LoggerRedactTest`, `CodeHygieneArchitectureTest` (new); `MeshSocialRepositoryTest` (extended). App built and installed; tests pass.

* **"Open in Tor Browser" never appeared.** On API 30 and newer, the app cannot see another package without a `<queries>` entry. The manifest now lists `org.torproject.torbrowser` and `org.torproject.torbrowser_alpha`. The button opens whichever is installed; with only the alpha installed, it used to target the stable package and do nothing. It never falls back to the default browser, which would leave Tor.
* **Search terms and video ids in logs (C19).** About 20 INFO/WARN/ERROR logs carried them. Release logs now show `Logger.redact(value)` instead: an 8-character tag and the length, the same within one run. Debug builds that allow sensitive logs keep the value.
* **DMs to a contact without an encryption key.** `sendDirectMessage` used the contact's Ed25519 key in place of a missing X25519 key, so the contact could never decrypt the message. Nothing is stored or sent now, and the user sees "Message not sent: this contact's encryption key is missing. Ask them to reconnect."
* **Coroutine scopes (C25).** Fire-and-forget `CoroutineScope(Dispatchers…)` calls use `AppScopes`. Scopes owned by a class keep their owner and gain a `SupervisorJob` and the logging exception handler, which now logs the stack trace too.
* **Silent catches.** Catches that hid a real failure now log it. Those deliberately kept silent (cleanup, best-effort parsing, expected failures) are listed per file in `CodeHygieneArchitectureTest`, which fails on any new one.
* **Stale comment.** The Invidious mention in `MeshPacketVerifier` (C05 leftover) is gone.
* **Already done before this round:** the two substring membership checks (R3), and the update-certificate check, which logs a WARN when certificates cannot be read.

## Completed Changes (2026-10-13) — Round D10e: Creator Identity Import on the Studio Page, Settings Tab Order (v0.7.0-alpha)

Follow-up to D10d after device testing. Unit tests: `CreatorIdentityArchiveD10dTest` (updated). Not yet verified on device.

* **Import on the Studio page.**
  * A creator node can now import a creator identity file under "Import Creator Identity", next to the backup.
  * A warning comes first: the file's identity replaces this node's creator identity, and that identity's followers lose it for good. The warning has a "Back Up First" option.
  * The user then picks the file and enters its passphrase, and the restore runs, replacing the current creator identity.
  * The Settings restore stays for nodes that are not creators yet.
* **Contacts of a replaced or burned identity.**
  * **Cause.** Replacing or burning a creator identity left its contacts bound to the secondary identity. The new identity would then talk to the old identity's followers and link the two.
  * **Fix.** After the old identity's `USER_EXIT`, those contacts are forgotten (`NoSlopRepository.dropCreatorContacts`). Contacts in the imported file are kept.
* **Settings tab order.**
  * The tabs are now General, Network, Content, Studio, System.
  * Tabs are chosen by name instead of position, so a tab that disappears (Studio, when creator mode is turned off) falls back to General instead of showing an empty page.

## Completed Changes (2026-10-13) — Round D10d: One Profile by Default, Creator Identity Backup and Restore (v0.7.0-alpha)

Part of the D04 follow-up work (creator identity backup, restore and move; burn warnings). Unit tests: `CreatorIdentityArchiveD10dTest` (new) and `IdentityRoutingTest` (updated). Not yet verified on device.

* **One profile by default (user decision, 2026-10-13).**
  * Every identity now uses the personal username, picture and bio.
  * In creator mode, a field set on the Studio page replaces the personal one for the creator identity only. A blank Studio field falls back to the personal one.
  * On a node that is not a creator, the secondary identity (used to follow discovered creators) always uses the personal profile.
  * This replaces D04's separate creator profile, whose default name was "Creator", and D10c's personal-handle-only rule. The rule lives in `resolveSecondaryProfile` and is used by `IdentityRepository`, `IdentityRouter`, `NoSlopRepository.secondaryIdentityProfile` and the discoverable announcement.
  * The Studio card shows which fields come from the personal profile.
  * **Trade-off, accepted by the user.** Anyone who sees both identities can match the fields the Studio leaves blank. Keys are never shared.
* **Creator identity backup and restore** (`CreatorIdentityArchive`).
  * **Back up.** On the Studio page, "Back Up Creator Identity" asks for a passphrase twice (at least 10 characters) and writes one file. It holds:
    * the creator keys, Studio profile and donation link;
    * the contacts bound to the creator identity;
    * the creator's posts and their media files.
  * **Restore.** In Settings, Creator Node, "Restore Creator Identity" installs the file on this device. It turns creator mode on, registers the creator onion with Tor, and adds the contacts, posts and media.
  * **Restoring again is safe.** Existing rows are kept, so a second restore of the same file changes nothing.
  * **What is left out.** A contact this device already knows by its personal identity is not taken over, and is counted in the result.
  * **Existing secondary identity.** A different secondary identity already on the device is replaced only after the user confirms.
  * **Moving to another device.** The result dialog reminds the user to turn creator mode off on the old device, because two devices with one creator identity compete for one onion address.
  * **File format "NSC1".**
    * Argon2id (3 iterations, 64 MiB) derives the key from the passphrase.
    * The body is AES-256-GCM in 1 MiB chunks. Each chunk's nonce carries its number and a last-chunk flag, and the header is the associated data.
    * A large video never has to fit in memory, which the full backup's single GCM stream needs.
    * A truncated, reordered, modified or extended file is refused before anything on the device changes.
    * Rows are written as explicit JSON fields, so R8 renaming cannot break the file.
* **Burn warnings.** Burning the creator identity and turning creator mode off now say that the identity cannot be brought back without a backup. The burn dialog has a "Back Up First" button.
* **Shared checks.** The burnable-identity checks of a full backup restore (`IdentityRepository.burnableIdentityProblem`) are now shared with the creator restore. Their checks and messages are unchanged.
* **Still open from D04.**
  * Reactions and votes are not yet sent as the creator identity.
  * In a temporary chat, "Share Permanent Identity" still sends the request as the secondary identity, because the contact is bound to it (this predates D04).

## Completed Changes (2026-10-11) — Round D10c: Creator Posts, Follower Names, Attachment Identity (v0.7.0-alpha)

Testers saw three problems after D10b fix 2: two older creator broadcasts still never reached followers, even after being edited to public; a follower that connected to a discovered creator with its secondary identity was listed by the creator as "Creator"; and new posts in creator mode defaulted to friends-only. Unit tests: `CreatorPostIdentityD10cTest` (new) and `IdentityRoutingTest` (updated). Not yet verified on device.

* **Older creator posts.**
  * **Cause.** The two posts were written as friends-only. In creator mode, a friends-only post is signed by the personal identity, and editing it to public kept the personal key, because a signed post's author cannot change. D04 never sends personal posts to creator contacts. The creator's log showed "2 withheld" on every inventory sync and "Not sending EDIT_POST …: written by the identity this peer doesn't know".
  * **Fix.** In creator mode, a post's privacy decides its identity, the same rule as when it is written: public posts belong to the creator identity and friends-only posts to the personal identity (`MeshSocialRepository.identityMoveFor`).
  * An edit that crosses that line moves the post (`moveMeshPostToOtherIdentity`). It is published again by the other identity, with a new id and the same time, attachment and clearnet link, and the old copy is deleted. Likes and comments stay with the old copy.
  * The edit sheet explains this. For a personal post made public it offers "Move to creator" (the default) or "Keep personal". A creator post made friends-only always moves.
* **Creator mode posting.** New posts default to Public, and the compose sheet says which identity each privacy setting posts as.
* **"Creator" instead of the follower's name.**
  * **Cause.** D04 named the secondary (burnable) identity after the creator profile on every node. A node that is not a creator has no creator profile, so its secondary identity went by the default "Creator".
  * **Fix.** On a node that is not a creator, the secondary identity goes by the personal handle again, as before D04. It still never carries the personal bio or avatar. On a creator node it keeps the creator handle (`secondaryIdentityHandle`, used by `IdentityRepository` and `IdentityRouter`).
  * `announceCreatorPresence` sends the identity's name (`IDENTITY_UPDATE`, signed by that identity) to the contacts that know it. It sends whenever the name differs from the last one sent and one of those contacts is online, so existing "Creator" rows correct themselves.
* **Identity leak found on the way.** Attachments carried the personal onion and key (`originNode`, `ownerId`) whatever identity sent them, because the UI fills in the personal ones. This included creator posts, comments as the creator, DMs to followers and group messages sent as the creator. Each attachment now names the identity that sends it. These fields are not signed, so nothing else changes on the wire.
* **Known limit.** Turning creator mode on reuses an existing secondary identity. The creators that identity followed knew it by the personal handle, so they can link the new creator identity to that name.

## Completed Changes (2026-10-11) — Round D10b fix 2: Creator Posts Reach Followers (v0.7.0-alpha)

Testers saw that posts by a creator node (a video and an older post) never appeared on a node that followed it, neither in its mesh content nor on the creator's card. Unit tests: `BulkSyncDeliveryTest` (new). Not yet verified on device.

* **Cause 1: sync replies were thrown away.** When the follower synced, the creator answered "sent 1 missing posts", but `MeshTransport.sendPacket` dropped the reply carrying the post. The log said "Dropping bulk SYNC_RESPONSE …: bulk circuits busy". A bulk packet that could not get one of the 4 bulk connection slots within 4 s was dropped, and nothing sent a sync reply again.
* **Fix.**
  * Sync packets (`SYNC_REQUEST`, `SYNC_RESPONSE`, `INVENTORY_SYNC_REQUEST`) now wait up to 90 s for a slot (`MeshTransport.waitsForBulkSlot`).
  * Other bulk packets keep the 4 s limit, and presence announcements are still dropped at once.
  * `SyncPacketHandler` re-sends an undelivered sync reply in the background after 30 s and again after 90 s, with a fresh packet id.
* **Cause 2: pending requests to creators were deleted.** The 15-minute clean-up of inactive discoverable nodes deleted every untrusted row, including a creator we had a pending request with or followed. **Fix:** it now deletes only nodes we merely saw announce themselves (`MeshSocialRepository.isDisposableDiscoverableRow`).
* **Diagnostics.**
  * The inventory log now says how many posts were withheld from the peer (our personal posts to a creator contact, friends-only posts to anyone who isn't a friend).
  * The sync-response log says why posts were not stored: mesh filter, rejected, or already stored.
  * The image, video and text mesh filters used to drop synced posts without any log line.

## Completed Changes (2026-10-11) — Round D10b: Verification by Camera Scan, Automatic Key Changes, 8-Character Tags (v0.7.0-alpha)

Testers found D10's manual "Mark verified" step tedious. This round replaces it: the app verifies contacts itself when people meet and scan each other's QR code. Unit tests: `ContactVerificationD10bTest` (new), `SafetyFingerprintD10Test`, `PeerTrustInvariantTest`, `CryptoDerivationTest`, `CryptoServiceRobolectricTest`. Not yet verified on device.

**Compatibility.** No Room schema change; `pendingEncKey` is no longer written. `CONNECTION_REQUEST` gains an optional signed `intro` field, appended to the v2 signature only when present, so requests without it sign exactly as before. The tag changed from 6 to 8 characters (alpha testers: old tags are accepted when an identity is loaded or restored and rewritten once). `PeerDao.acceptPendingEncKey` and `clearPendingEncKey` (D10) are replaced by `applyEncKeyChange` and `updateTagAndHandle`.

### Verified in person, without a button

* **Scanner.** Scanning someone's QR code with the camera (`QRScanScreen`, source `ContactIntro.QR_CAMERA`) stores that code's safety fingerprint in `peers.verifiedFingerprint` when the request is sent (`MeshSocialRepository.sendConnectionRequest`). It counts only while it equals the contact's current fingerprint, so a different key in their handshake shows as "Changed since you verified it". Scanning a contact you already have, or someone whose request is pending, verifies them only if the code carries the keys already stored.
* **Approver.** The request carries `intro = "qr_camera"` inside the v2 signature, so it can't be added or removed on the way. The receiver stores it as `request_intro_<pub>`. The request dialog, the notification card and the contact card show the requester's name.tag (the tag comes from their key), a check code (the first two groups of the safety fingerprint), and a note that they scanned your code. Accepting there calls `NoSlopRepository.acceptConnectionRequest(…, confirmedByUser = true)`. That marks them verified in person, but only if the request was a camera scan and the keys are still the ones on screen. Creator auto-accept and crossing requests never verify anyone.
* **Not verified.** A code read from a picture, a pasted ID, or a request over the network never verifies anyone. A picture or a pasted ID shows a red warning to continue only if the code came from that person.
* **Warnings.** The scan and approval screens warn when an accepted contact already has the same name, ignoring tags and case (`sameNameContact`). The scan screen also warns when the tag written in the code doesn't belong to its key.
* **UI.** The contact card shows "Verified in person", "Not verified in person" or "Changed since you verified it"; "Compare keys" shows the full fingerprint. The D10 "Mark verified"/"Remove verification" buttons are gone. "My ID" shows your check code above your fingerprint.

### Key changes are applied automatically (C02, D10 c)

* **Cause.** D10 held a changed encryption key in `pendingEncKey` until the user tapped "Accept New Key". The new key is signed by the contact's identity key, so holding it only delayed messages.
* **Fix.** `HandshakePacketHandler` applies a changed key from an `ACCEPTED` contact (a request or a handshake refresh). It clears `verifiedFingerprint` and records `key_changed_at_<pub>`. The same key in a different encoding is not a change (`CryptoService.sameX25519PublicKey`). The chat shows a one-line notice ("…'s security key changed on …") until dismissed (`KeyChangeNotice`), replacing D10's accept/keep banner. Keys still held from D10 are applied once at startup.

### 8-character tags made with Argon2id

* **Change.** `CryptoService.deriveTripcode` is now base32(Argon2id(raw Ed25519 key, salt `noslop-tag-v2`, t=2, m=19456 KiB, p=1, 16 bytes)), first 8 characters (golden vector `wdbuab42` for bytes 0..31, computed with argon2-cffi; Bouncy Castle 1.78.1 gives the same). Finding a key whose tag matches someone else's now takes about 2^40 Argon2 runs, instead of 2^30 SHA3 hashes for the old 6-character tag. Results are cached per key (4096 entries). Only one Argon2 run happens at a time, using about 19 MiB.
* **Old tags.** `deriveLegacyTripcode` keeps the old SHA3 tag (golden `aufeq4`). `isTripcodeOf` accepts either form when a backup is restored (`IdentityRepository.validateIdentityObject` and `restoreAuthoritativeIdentity`). `loadIdentity` rewrites the stored tag and the display name suffix; `getBurnableIdentity` rewrites the stored creator tag. Handshakes, announces and identity updates strip either form from incoming handles (`stripIdentityTag`).
* **Migration.** `NoSlopRepository.migrateIdentityTagsV2`, started by the ViewModel, runs once (setting `identity_tags_v2_done`) and again after a failure. It applies held keys, rewrites stored contacts' tags and handles and post authors' tags and legacy handle suffixes, and reloads both own identities.
* **Cost.** The first tag for each key takes a fraction of a second (about 160 ms on a desktop JVM, cold), and later ones come from the cache. Unit tests that create many identities take a few seconds longer.

### Matching people by key, not name

* `MeshSocialRepository.deletePeer` (two places), `NoSlopViewModel.removePeer` and the contact card's post list used to match posts by handle as well as by key. Anyone can post under the same handle, so removing a contact could delete someone else's posts. They now match by key only.
* `MeshSocialRepository.purgeOrphanedPeerContent` (the 30-minute clean-up, also run after accepting a request) kept a stranger's posts when their handle matched a contact's or our own. It now keeps only posts whose author key is ours or a stored contact's.

## Completed Changes (2026-10-11) — Round D10: Status Honesty, Safety Fingerprints, Key Changes, Unverified Media (v0.7.0-alpha)

This round closes review finding D10 and the "unverified" label left open by D08. A "Tested" label names a test class that must pass with `./gradlew testGithubDebugUnitTest`; from this round on, `StatusDocCitationTest` fails the build if a label names a class that does not exist. Not yet verified on device. *(Round D10b replaced the "Mark verified" button and the key-change banner described below with verification by camera scan and automatic key changes.)*

**Compatibility.** No packet, archive-format or Room schema change. Two new `PeerDao` queries on existing columns (`acceptPendingEncKey`, `clearPendingEncKey`). The fingerprint derivation changed, but nothing displayed or stored a fingerprint before this round.

### Status documents cited tests that never existed (D10 a, e)

* **Cause.** The 2026-10-06 entry labelled items "Tested" with classes that were never written (`IdentityRepositoryTest`, `SyncPacketHandlerTest`, `CryptoServiceTest`, `JamendoApiClientTest`, `LoggerTest`), a composable (`OnboardingScreen`), a Gradle task (`testGithubDebugUnitTest`) and, since R6b, a deleted test (`IdentityViewModelTest`). It also described a `secureDelete()` that was never added and a Bouncy Castle ProGuard change that was never made. The matrix still listed C07 (streaming backup encryption) as resolved.
* **Fix.** Those lines now say what is true: "Implemented (untested)", the test that does cover the item, or a correction note. C07 is back to Open (D12). Two malformed matrix rows (R7a–R8, R3b–R3c) were repaired.
* **Check.** `StatusDocCitationTest` reads `PROJECT_STATUS.md`, `TECHNICAL_REFERENCE.md`, `WIRE_PROTOCOL_REFERENCE.md` and `README.md`. Every "Tested" label must start with a backticked test class, and every backticked name ending in `Test` in it, or in the last column of a findings matrix, must be a class declared under `app/src/test` or `app/src/androidTest`. It checks that cited tests exist, not that they passed; passing is what running the suite shows.

### Safety fingerprints were derived but never shown (C11, D10 b)

* **Fingerprint.** `CryptoService.deriveSafetyFingerprint` now hashes SHA3-256(`"noslop-safety-fp-v1"` ‖ raw Ed25519 public key ‖ raw X25519 public key) and shows 20 Base32 characters in five groups of four. Keys are reduced to their raw 32 bytes first, so raw and X.509/SPKI-wrapped encodings give the same fingerprint. A missing or unreadable key returns `null`; it used to return `0000-0000-0000-0000-0000`, which two broken keys would have "matched".
* **Where it shows.** Your own fingerprint is on the "My ID" card and the Creator Contact Card (`QRShareSheet`). A contact's fingerprint is on their contact card (`ContactCardDialog` → `SafetyFingerprintPanel`) with its state: Verified, Not verified, or "Changed since you verified it".
* **Mark verified.** `NoSlopRepository.setPeerVerified` stores the current fingerprint in `peers.verifiedFingerprint` (column added in 18→19). Only an `ACCEPTED` contact with no key change pending can be marked. A verification goes stale on its own when either key changes, because the UI compares it with the current fingerprint. Verified contacts get a shield next to their name in the chat header.

### A changed encryption key was held but never shown (C02, D10 c)

* **Cause.** Since C02 a changed encryption key from an accepted contact is held in `peers.pendingEncKey` instead of being applied, but no screen showed it, so the contact stayed on the old key with no way forward.
* **Fix.** The chat shows a "Safety Key Changed" banner with the new fingerprint. **Accept New Key** (after a confirmation) applies it with `PeerDao.acceptPendingEncKey`. That update only applies if the pending key is still the one the user saw, and it clears any earlier verification. **Keep Old Key** drops the pending key (`clearPendingEncKey`); if the contact keeps presenting the new key, their next handshake holds it again and the banner returns. The contact card also says when a key change is waiting.

### Media whose digest the author did not sign was not marked (D08 leftover)

* **Fix.** `NoSlopRepository.isMeshPostMediaVerified` re-checks the stored post signature in the v2 (C17) form over each digest recorded for its media. The feed shows an **Unverified media** label (tap for an explanation) on another author's mesh media post when no recorded digest is covered by the author's signature. A digest that is only cached on the owner rows (for example by `mediaDigestFor` from a local file) does not count, so the label stays correct even where that cache holds an unsigned hash. Own posts and posts without mesh media are never labelled.

### Translations: per-language missing-key report (D10 d)

* `TranslationParityTest.otherLanguageAssets_reportMissingKeysPerLanguage` prints, for each language, the number of English keys it lacks (with up to ten examples) and writes the same report to `app/build/reports/translation-parity.txt`. It never fails on missing translations, which fall back to English; unreadable JSON still fails. The English-key check is unchanged.
* New English strings for this round: 19 keys in `content_en.json`.

### Tests added in this round

| Test class | Covers |
|---|---|
| `StatusDocCitationTest` | Every "Tested" label and matrix validation cell in the four documents names an existing test class |
| `SafetyFingerprintD10Test` | Fingerprint shape; same fingerprint for raw and wrapped keys; different per identity and per encryption key; `null` for missing/unreadable keys (no shared placeholder); Mark verified stores the current fingerprint, can be removed, refused for non-accepted contacts and without an encryption key; a key change from a real v2 `CONNECTION_REQUEST` is held, blocks verifying, and Accept applies it and resets verification; Keep Old Key drops it and keeps a still-valid verification; accept/reject with nothing pending change nothing; media covered by a v2 signature is verified, an 8-field post with a digest on record is not, a different recorded digest is not, own posts and posts without mesh media are never flagged |
| `TranslationParityTest` (changed) | Per-language missing-key report (non-failing) |

---

## Completed Changes (2026-10-10) — Round R6: Nothing Changes the Word Cloud (v0.7.0-alpha)

**Diagnosis of the older account (R5c log, 22:04:05).** `identityVersion=1 | typedDerivesIdentity=false | storedDerivesIdentity=false`, 12 of 12 words differ. The account predates Word Cloud–derived keys (version 2, 2026-09-11): its keys are random, so no phrase derives them and the device cannot tell which of the two phrases is the "right" one. The keys, contacts and data are intact. No code in the history rewrites the stored phrase on a running install. A past restore could only bring back the exporting phone's stored phrase, or, before R6, the words typed at that export or restore when the phone had none stored. The paths that replaced keys with Word Cloud–derived ones would have made the account version 2, so they did not run here. Since R5b the account can be backed up with the written-down words ("Back Up With These Words"); that backup opens with those words, and the account keeps its stored phrase.

**Rule (agreed with the user).** The Word Cloud an account keeps is written when the account is created and never changed afterwards. A restore brings back the backed-up account's own phrase; it never substitutes the words typed to open the backup, which only decrypt the file. `WordCloudGuard` (`data/WordCloudGuard.kt`) holds the rule:

* **Restore** (`IdentityRepository.restoreAuthoritativeIdentity` → `WordCloudGuard.decideRestored`): the archive's phrase is kept as it is. The typed words are stored only when the archive carries no phrase **and** they derive the restored keys. Otherwise no phrase is stored (logged as a warning). Before, the typed words were stored whenever the archive had no phrase. A version 2 archive whose phrase does not derive its keys is still kept unchanged, with a warning in the log. The log records the source and booleans only, never words.
* **Export** (`BackupManager.exportData`): the archive carries the account's stored phrase. If it can't be read, the archive carries none. Before, the archive carried the words typed for the export, which a restore then stored.
* **Legacy cross-device rebuild** (`planLegacyRederivation`): the typed words become the account's phrase only when the archive's database names the public key they derive. An archive that records no key used to count as a match; it is now a recovery-required restore.
* **Account creation** (`saveIdentity`) remains the only first write, reached only from `NoSlopViewModel.completeOnboarding`, which derives the new keys from the phrase it stores.
* The old prefs-file restore of pre-portable archives copies the account's own prefs file and is unchanged; the rollback restores the pre-restore snapshot.
* `IdentityRepository.unlock` compares against the stored phrase; an account restored with no phrase could not be unlocked, but no screen calls `logout`/`unlock` today.
* No "show my Word Cloud" screen (user decision).

**Compatibility.** No packet, archive-format or Room schema change. Archives without a `mnemonic` field are valid (the field was always optional on restore).

### Removed: the unused `IdentityViewModel` (C25 leftover, D10 item; R6b)

* **Why it existed.** C25 copied the account code (onboarding, Word Cloud, backup export and restore, creator identity) out of `NoSlopViewModel` into `IdentityViewModel` but never switched the screens over or deleted the originals. `NoSlopViewModel` created an instance on every launch (`val identityViewModel`) that nothing used. A project-wide search found no other reference outside its own test.
* **Why it had to go.** The copy had drifted. Its restore still used the Boolean `importData` without the D09 results, the R5 failure reasons, or the Tor stop before restart. Its onboarding did not reset the tutorial step. It was a second account-creation path for the Word Cloud rule. Its eager flows also read the identity at every start for no use.
* **Change.** Deleted `ui/IdentityViewModel.kt`, its instance in `NoSlopViewModel`, and `IdentityViewModelTest`. That test only covered the copy; the same `generateMnemonic` and backup-prompt state live in `NoSlopViewModel`. No behaviour change: no screen used the copy.

### Tests added in this round

| Test class | Covers |
|---|---|
| `WordCloudRuleTest` | Archive phrase kept when other words opened the backup (version 1 keys), and kept even when the typed words derive the keys; derivation flag; no archive phrase: typed words kept (canonical form) only when they derive the keys, otherwise nothing stored (`null`, blank, `"null"`); export with other words carries the account's own phrase; end-to-end restore with other words keeps the stored phrase; legacy rebuild needs the archive to name the derived key (no key → no match) |
| `WordCloudWritePathsTest` | Source scan: the stored phrase is written only in `saveIdentity`, `restoreAuthoritativeIdentity` (through the guard), `exportData` (the archive copy) and `importBackup` (legacy rebuild, after the key-match gate); `saveIdentity` is reached only via `saveLocalIdentity` ← `NoSlopViewModel.completeOnboarding`, which stores the phrase it derived the new keys from; nothing removes the phrase on its own |

---

## Completed Changes (2026-10-10) — Round R5: Device Feedback after D09 (v0.7.0-alpha)

Device report after D09: the feed tutorial swiped itself away on a new account; a QR image from the gallery was "not a QR"; an older account's backup said the Word Cloud was wrong; after a restore Tor took long to come up and replies arrived minutes late; videos buffered a lot. A "Tested" label names a test class that must pass with `./gradlew testGithubDebugUnitTest`. Not yet verified on device.

**Compatibility.** No packet, archive-format or Room schema change. New backups are encrypted under the canonical Word Cloud; every older archive still opens (see below).

### Feed tutorial swiped itself away (regression from the Mesh Feed round)

* **Cause.** Landing on tutorial slide N recorded step N + 1. The slide list is rebuilt from that step whenever the feed refreshes, which a new account does many times while it fills, so the slide on screen dropped out; the pager settled on the next slide, which recorded the one after, and so on until no tutorial was left. The Mesh Feed round keyed this effect on the item id instead of the page index, which is what let the chain run.
* **Fix.** The slide on screen is recorded (step N), so a refresh never removes it; a slide leaves the list only once the user has moved past it. Reopening the app shows the slide last viewed.
* Not unit-tested (Compose pager state); verify on device with a new account.

### Gallery QR images were not read

* **Cause.** The gallery path made one attempt: full-size RGB, no `TRY_HARDER`, no inverted pass, transparent pixels read as black. Light-on-dark codes, PNGs with a transparent background and small codes in large photos all failed. Nothing was logged.
* **Fix.** `decodeQrFromArgb` composites over white (and black, when the image has transparency), tries full size and halved sizes, each with the hybrid and global-histogram binarizers, normal and inverted. Large images are decoded at most 2048 px on the longest side. The result is logged (`QR_SCAN Gallery image ...`).
* Tested (unit: `QrImageDecodeTest`; existing `QrFrameDecodeTest`).

### A backup said the Word Cloud was wrong

* **Cause.** Export used the Word Cloud exactly as typed in the dialog (only compared case-insensitively with the stored one), so an archive made with an auto-capitalised first word or a trailing space opened only with that exact string. Onboarding also trimmed the input, and showed "Check your mnemonic" for every failure.
* **Fix.** Export encrypts under the canonical form (`MnemonicGenerator.normalize`: trimmed, lower case, single spaces). Restore tries the canonical and the likely as-typed forms (`restoreCandidates`), picking the one whose key turns the first cipher block into a zip header (`firstBlockIsZip`); the full authenticated decryption still decides. Settings and onboarding show the actual reason (`ImportResult.Failed.reason`), e.g. "The Word Cloud does not match this backup".
* If the old account's backup still fails, the log now names the reason; send it.
* Tested (unit: `BackupWordCloudFormsTest`).

### Export refused: "Mnemonic does not match your active Word Cloud!" (R5b, device report on the older account)

* **Cause.** Before exporting, Settings (and the backup advisory) compared the typed Word Cloud with the one stored on the device using `trim()` + `equals(ignoreCase)`: any difference in spacing inside the phrase (double space, line break) failed, and a real difference was a dead end. The older account could not be backed up at all.
* **Fix.** `WordCloudCheck.compare` compares canonical forms (case and spacing ignored). A real difference opens a dialog saying in how many places the words differ and that the backup will open only with the words typed; the user can back up with them or re-enter. The log records the counts only (`differingWords`, `typedWords`, `storedWords`), never the words.
* Resolved in R6 (version 1 account with random keys; see Round R6). Device report: 12 of 12 words differ. No change in this session writes the stored Word Cloud on an existing install (only restores do, and their behaviour is unchanged apart from the D09 legacy re-derivation, which stores the phrase typed at restore). R5c adds to the mismatch log the identity version and whether the typed and the stored phrase derive the account's keys (booleans only).
* Tested (unit: `BackupWordCloudFormsTest`).

### Older backups could not be restored from onboarding

* **Cause.** Onboarding passed no handler for an unauthenticated (pre-GCM) archive, so such a restore spun forever.
* **Fix.** Onboarding asks for confirmation, as Settings does, and restores with it.

### Replies arrived minutes late after a restore

* **Cause (from the logs).** The restore restarts the app; the restored phone's Tor then sat at "Connecting to a relay" for two minutes. Its .onion came back with new introduction points while the other phone's Tor kept the old descriptor; every send to it timed out ("Connect timed out", the app's 10–25 s send timeouts being shorter than Tor's own retry) until 19:35:16, when the backlog arrived at once.
* **Fix.**
  * `GossipService` asks Tor for a fresh descriptor (`TorService.refreshOnionDescriptor`, control command `HSFETCH`) on the second failed send to an onion in a row, and when that peer reaches us while our sends to it were failing; at most once a minute per onion.
  * Before the restore restart, Tor is stopped cleanly (`stopTor`), so it can save its state; a precaution, as the logs cannot show whether the abrupt kill caused the slow bootstrap.
* Tested (unit: `OnionDescriptorRefreshTest`).

### Video buffering

* No defect found in the logs. The buffering burst at 18:42 is the position jumping about 10 s per step (double-tap skipping); the other waits are slow YouTube streams over Tor, and the existing stall escape moved one stream to a fresh circuit. Report a specific video with its log if it keeps happening.

### Tests added in this round

| Test class | Covers |
|---|---|
| `QrImageDecodeTest` | Dark-on-light, light-on-dark, dark and light modules on a transparent background, a code inside a large frame, an image with no code, 2×2 downscale |
| `BackupWordCloudFormsTest` | Canonical form; Word Cloud check ignores case and spacing and counts differing words; export typed with capitals and spaces restores with the plain phrase; archives encrypted (as before this round) under a capitalised phrase or with a trailing space restore; a different phrase is refused with "The Word Cloud does not match this backup"; first-block key probe |
| `OnionDescriptorRefreshTest` | Refresh on the second failure, not the first; at most once a minute per onion; when the peer reaches us after failed sends; not on plain successes; non-onion addresses ignored |

---

## Completed Changes (2026-10-10) — Round D09: Typed Restore Result, Pre-Commit Identity Check, X25519 Reconciliation (v0.7.0-alpha)

This round closes review finding D09. A "Tested" label names a test class that must pass with `./gradlew testGithubDebugUnitTest`.

**Verification (2026-10-10).** Tests passed and the build was installed; export and restore of a new account worked on device. Restoring an older account's backup failed with "wrong Word Cloud" (see Round R5).

**Compatibility.** No archive format, packet or Room schema change. `BackupManager.importData` keeps its Boolean contract for existing callers (tests; `IdentityViewModel` until R6b); the app's restore path uses the new `importBackup`.

### A restore that needed identity recovery reported "Import failed"

* **Cause.** For a legacy archive (keystore-sealed identity prefs, no portable identity JSON) restored on another device, `importData` checked whether the Word Cloud rebuilds the archive's identity only *after* replacing the database, prefs and media. On a mismatch it deleted the sealed prefs, set the quarantine flag and returned `false`, so the UI said "Import failed" and no restart happened, although the device's data had already been replaced. A failed rebuild returned `true` with no quarantine flag, so the app restarted into an ordinary onboarding with no recovery banner. `lastRestoreNeedsIdentityRecovery` was never read.
* **Fix.**
  * `BackupManager.importBackup` returns an `ImportResult`: `Success`, `SuccessIdentityRecoveryRequired`, `Failed(rolledBack, reason)` or `ConfirmLegacy`. `rolledBack` is false only when a failed commit's rollback could not be verified. `ConfirmLegacy` replaces the exception for an unauthenticated archive (the Boolean `importData` still throws it). `lastRestoreNeedsIdentityRecovery` is removed.
  * While staging, before anything is replaced, the archive's handle and public key are read from the staged database (read-only) and the identity is derived from the Word Cloud (`planLegacyRederivation`). Whether that rebuild is *needed* still depends on whether this device can open the restored prefs, which can only be probed once they are in place; a same-device legacy restore keeps working as before.
  * After the commit, a mismatch or a failed rebuild sets `identity_quarantined` and returns `SuccessIdentityRecoveryRequired`. `NoSlopViewModel.importBackupFromUri` restarts the app for it as for a success (without the Hub prompt). `isOnboardingComplete` is false while recovery is needed, so the onboarding screen shows the "Identity Recovery Required" banner.
* Tested (unit: `BackupImportResultD09Test`; existing `BackupManagerTest`).

### The cached X25519 public key was never checked against the private key

* **Cause.** `loadIdentity` reconciled the Ed25519 public key with the private key (C15) but returned the cached X25519 public key unchecked. A diverged cache was advertised in handshakes while DMs were decrypted with the private key.
* **Fix.** `loadIdentity` and `getBurnableIdentity` derive the X25519 public key from the private key (`CryptoService.x25519PublicKeyFromPrivate`, compared encoding-independently by `sameX25519PublicKey`) and repair the caches when they differ.
* Tested (unit: `BackupImportResultD09Test`).

### Tests added in this round

| Test class | Covers |
|---|---|
| `BackupImportResultD09Test` | Portable archive → `Success`; wrong Word Cloud → `Failed(rolledBack = true)`; unauthenticated archive → `ConfirmLegacy` (Boolean form still throws); injected commit failure → `Failed(rolledBack = true)` with the identity intact; legacy archive whose identity the Word Cloud can't rebuild → data restored, `SuccessIdentityRecoveryRequired`, quarantine flag set (Boolean form returns true); legacy archive whose rebuild can't be sealed → recovery required, not a silent success; diverged cached X25519 public key repaired, matching key left as stored |

---

## Completed Changes (2026-10-10) — Round D08: Media Digest Downgrade and Same-Revision Signature Check (v0.7.0-alpha)

This round closes review finding D08. The sync path was already fixed in R2. A "Tested" label names a test class that must pass with `./gradlew testGithubDebugUnitTest`.

**Verification (2026-10-10).** Tests passed and the build was installed on the test devices; editing existing posts syncs correctly between nodes.

**Protocol compatibility.** No packet layout, signature encoding or Room schema change (one new DAO query on the existing `media_owner.sha256` column).

### Live POST and EDIT_POST checked downloads against an unsigned digest

* **Cause.** A media post may carry the C17 v2 signature (canonical fields plus `media_metadata.sha256`) or the 8-field one, which doesn't cover the digest. `PostPacketHandler` passed `media_metadata` to auto-download as received, whichever form verified. A relay could attach the digest of its own substituted file to a post signed in the 8-field form, and the download check would then pass.
* **Fix.** When only the 8-field form verifies, the digest is removed before auto-download (`PostPacketHandler.downloadMetadata`), as sync already did, and a warning is logged. `EDIT_POST` now also records a v2-signed digest, as `POST` does.
* **Own caption edits lost the digest.** Editing only the caption of one's own media post rebuilt the media descriptor without `sha256`, so the edit was signed in the 8-field form and receivers had nothing signed to check the file against. The descriptor now carries the recorded digest (`mediaDigestFor`), so the edit keeps the v2 signature.
* Tested (unit: `PostDigestBindingD08Test`, `OwnPostEditDigestD08Test`).

### Same-revision signature changes ignored signed fields (W07)

* **Cause.** `PostDao.insertPostSafely` let a new signature replace the stored one at the same timestamp when content, privacy, media URL and clearnet URL matched. The avatar and the media digest are signed too and weren't compared. An older 8-field signature for the same revision could also be replayed over a v2 one, and the post was then re-served by sync as unverifiable.
* **Fix.** `signedFieldsMatch` adds the avatar. `signatureKeepsRecordedDigest` allows a same-revision signature change only if the new signature covers the digest already recorded for the post (callers pass `signedMediaHash`, null for the 8-field form). Without a recorded digest, an upgrade from 8-field to v2 is accepted as before. `editPostSafely` refuses the same replay at an unchanged timestamp and media.
* Tested (unit: `PostDigestBindingD08Test`; existing `PostPacketHandlerTest`, `MediaSyncAndAclRestoreTest`).

### Still open (D08)

* The review also asks to show such media as "unverified". Done in Round D10 (2026-10-11): the feed shows an "Unverified media" label when the author's signature covers no recorded digest.
* `mediaDigestFor` caches the hash of a local file when no signed digest is recorded. For someone else's media that was downloaded unverified, that hash can later block a genuine same-revision v2 upgrade (the post keeps its 8-field signature; nothing wrong is accepted).

### Tests added in this round

| Test class | Covers |
|---|---|
| `PostDigestBindingD08Test` | 8-field POST and EDIT_POST with an attached digest: stored, downloaded without the digest; v2 POST and EDIT_POST keep it; same-revision copy with another avatar doesn't replace the signature; an 8-field signature can't replace a v2 one once the digest is recorded; 8-field → v2 upgrade still accepted; `insertPostSafely` and `editPostSafely` digest rules directly |
| `OwnPostEditDigestD08Test` | A caption-only edit of one's own media post is signed over the recorded digest |

---

## Completed Changes (2026-10-10) — Round D05: DM v1 Latch and DM_ACK Domain Separation (v0.7.0-alpha)

This round closes the two open parts of review finding D05. The outbox expiry part was already done in R3. A "Tested" label names a test class that must pass with `./gradlew testGithubDebugUnitTest`.

**Verification (2026-10-10).** `./gradlew testGithubDebugUnitTest --rerun-tasks` passed on the developer machine, including `DmProtocolD05Test`; the build was installed on the test devices and works. The mixed-version cases below were not reported one by one.

**Protocol compatibility.** No Room schema change. `MESSAGE` is unchanged. `DM_ACK` gains two optional fields and `v: 3`. Earlier builds keep working in both directions (see below).

### v1 DMs were accepted from any peer

* **Cause.** `handleDirectMessage` fell back to v1 (`decryptDM`, no AAD) for every non-group message whose v2 decryption failed. v1 binds neither the message id nor the direction, so a relay could reflect a captured v1 message back to its sender under a new id. C08's reflection fix held only for v2 traffic.
* **Fix (latch).** A key is marked as speaking v2 (`app_settings["dm_v2_seen_<key>"]`) on its first v2 message, its first authenticated `DM_ACK`, or a v2-signed `CONNECTION_REQUEST` / `USER_HANDSHAKE`. Since the D07 hard break, every handshake is v2, so every contact that connects on this build is marked. After that, a non-group message from that key that fails v2 is refused. v1 is still read from a key never seen speaking v2 (an older client accepted before D07); it stays flagged `isLegacy`.
* **Why app settings, not a `Peer` column.** The review suggested `Peer.dmV2Seen` with a migration. Some write paths still rewrite peer rows wholesale (D06), and any one of them would reset the latch and reopen v1. A setting keyed by the public key can't be reset that way and needs no migration. It also survives removing and re-adding the contact: the key still speaks v2.
* **Own messages still in v1 form.** Messages written in the Hub web UI were sealed as v1 and pushed to the mesh labelled as plain `MESSAGE`s, and DM sync re-sent stored v1 messages labelled `v: 2`. A v2 peer would now refuse both. `NoSlopRepository.sealHubDm` seals our own Hub messages as v2 (us → peer, same id and timestamp), and the pushed packet says `v: 2`. DM sync re-seals any stored v1 message of ours as v2 before re-sending it (`DmPacketHandler.resendableCiphertext`). Messages *from* the peer pulled from the Hub keep their v1 at-rest form; they are only read locally.
* Tested (unit: `DmProtocolD05Test`).

### DM_ACK could be confused with MESSAGE

* **Cause.** A `DM_ACK` was "ACK" sealed exactly like a `MESSAGE` (same directional key, same AAD label `noslop-dm-v2`, group id empty), and `handleDmAck` never checked the plaintext. A captured message could be re-labelled as an acknowledgement for its own id. An ACK also settled any message id, including one in another chat.
* **Fix.**
  * New AAD label `noslop-dm-ack-v3` (`CryptoService.DM_AAD_ACK`; `canonicalDmAadV2`, `encryptDMV2` and `decryptDMV2` take a `domain`). Messages keep `noslop-dm-v2` (`DM_AAD_MESSAGE`), so they stay readable by earlier builds.
  * `sendDmAck` sends `v: 3` with the acknowledgement under the new label in `ack_nonce` / `ack_ciphertext`. Earlier builds only read `nonce` / `ciphertext`, so the old form rides along in the same packet until the peer has sent us a new-form ACK itself (`app_settings["dm_ack_v3_<key>"]`). From then on the old fields are empty.
  * `handleDmAck` tries the new form first. It accepts the old form only from a peer that has never sent the new one, and only if the plaintext is exactly `"ACK"`. Either form must open to `"ACK"`.
  * An ACK is refused for a message that isn't in the sender's own chat.
* **Why not mark "Sent" final for v1 peers.** The review also proposed skipping the ACK wait for peers known to be on v1. Since D07, such a peer can't read our v2 messages at all, so showing "Sent" would hide a message it never could read. The outbox keeps marking them `FAILED` after 7 days or 50 deliveries (R3), which is honest.
* Tested (unit: `DmProtocolD05Test`).

### Behaviour to watch on device

* DMs and ACKs between two nodes on this build: the first ACK each way carries both forms, later ones only the new form. Delivery ticks should behave exactly as before.
* With one node still on the previous build: its ACKs (old form, "ACK") are accepted; ours carry the old form it reads.
* A contact running a pre-v2 client (accepted before D07) can still send v1 DMs, until it ever speaks v2.

### Tests added in this round

| Test class | Covers |
|---|---|
| `DmProtocolD05Test` | v1 accepted from a never-v2 peer (flagged legacy); latch set by a v2 message, an authenticated ACK and a v2-signed connection request; v1 refused after the latch while v2 still works; old-form ACK accepted from an earlier build; a re-labelled message refused as an ACK (plaintext not "ACK"); "ACK" sealed as a message refused in the ACK field; an ACK-domain ciphertext refused as a message; new-form ACK accepted, after which the old form from that peer is refused; ACK for another chat's message refused; outgoing ACK carries both forms until the peer is known, then only the new one; DM sync re-seals a stored v1 own message as v2 |

---

## Completed Changes (2026-10-09) — Round C: Creator Identity Separation (D04) (v0.7.0-alpha)

This round closes review finding D04: the creator (burnable) identity could be linked to the personal one. It also fixes `IDENTITY_UPDATE` verification, which turned up on the way. A "Tested" label names a test class that must pass with `./gradlew testGithubDebugUnitTest`. Not yet verified on device.

**Protocol compatibility.** No packet layout, signature or Room schema change. Older receivers behave as before. Nodes on this build send fewer packets across identities (see below), accept `IDENTITY_UPDATE`s they used to drop, and get an `ANNOUNCE_PEER` for the creator identity.

### Where the creator identity leaked the personal one

* `getBurnableIdentity().displayName` was the personal handle. Every creator handshake (request, probe, accept) carried it, together with the personal bio and avatar.
* `ANNOUNCE_DISCOVERABLE` (heartbeat and ViewModel) carried the personal handle and bio. The Creator Studio QR card showed the personal handle.
* Creator posts and edits were signed by the creator key but carried the personal handle and avatar. Comments always used the personal identity, including replies on one's own creator posts.
* `GossipService.broadcast` re-stamped every copy of an original per peer. Creator contacts therefore received our personal posts, comments, reactions, `IDENTITY_UPDATE` and the personal `ANNOUNCE_PEER` (personal key and onion) from the creator node. Friends received creator posts stamped with the personal key.
* `forwardPacket` stamped every relay with the personal key, including relays to creator contacts.
* Sync answers sent posts, comments and reactions written by the personal identity to creator contacts. In creator mode they were sent under the creator key to everyone, so friends also dropped the friends-only posts in the answer.
* DM sync answers looked up, and resent as, the personal identity only. A creator contact got nothing back, and every resend named the personal key.
* Typing and follow always used the personal key. Group member handles listed the personal handle for our creator key.
* `MediaManager` and `GossipService` decided the identity by `isTemporary`, other paths by `contact_identity_*`. When the creator identity was gone, callers fell back to the personal identity.

### Fix

* **Creator profile** (`creator_profile` setting): handle, bio and avatar, edited in Creator Studio (a full-screen dialog; the first build drew it in place, where it inherited the host's height and only the Save button showed). The default handle is `Creator`, never the personal handle. *Burn ID* clears it, and changes go to creator contacts as a creator-signed `IDENTITY_UPDATE`.
* **`IdentityRouter`** (`data/IdentityRouter.kt`) is the single rule: binding first, then the addressed identity, then temporary-and-not-accepted. It picks the identity and profile for every per-peer send listed above, and returns nothing (rather than the personal identity) for a creator contact without a creator identity. `MediaManager.requesterIdentity`/`responderIdentity` use the same rule.
* **`GossipService.outboundFor`**: personal-authored originals are not sent to creator contacts. Creator-authored originals reach friends exactly in relay form. Presence, profile, exit, follow and removal packets never cross identities. Relays to creator contacts carry the creator key.
* **Sync** to a creator contact leaves out everything written by the personal identity. Answers use the routed identity.
* **Presence.** The creator identity sends its own `ANNOUNCE_PEER`, plus the discoverable announcement with the creator profile, a random 5–50 s after the personal one.
* Tested (unit: `CreatorIdentityCaptureTest`, `IdentityRoutingTest`; `MediaAccessRoundBTest` fixture now binds its follower to the creator identity, as `acceptConnectionRequest` does).

### `IDENTITY_UPDATE` was never accepted

* **Cause.** Senders sign with `encodeForSigning`, but receivers only verified the older pipe-joined string. Profile changes therefore never reached contacts.
* **Fix.** Both forms are accepted, in the handler and in `MeshPacketVerifier` (the pre-relay signature gate, which had the same pipe-only copy and dropped every update before the handler). A forged update is still refused.
* Tested (unit: `IdentityRoutingTest`, `MeshPacketVerifierTest`).

### Behaviour changes to watch on device

* Friends receive our creator posts in relay form (hop count one lower). Creator contacts no longer receive our personal posts directly; they can still reach them through other relays.
* Comments on our own creator posts, and on posts by creator contacts, are made as the creator identity.
* After creator mode is turned off, DMs, typing and syncs to former creator contacts are no longer sent at all. Before, they went out under the personal key, which those contacts don't know.
* **Creator Studio layout and demo cards.** The AI Approval Queue no longer starts with two hard-coded demo cards; publishing one broadcast fake content to the mesh. It now starts empty until Home Hub auto-tagging supplies items. The studio is shown in two places: a full-screen settings screen and an item inside the Settings list. In the list item the height is unbounded, so the `weight(1f)` queue and empty state collapsed to nothing. Neither uses `weight` now, the queue is a plain keyed `Column`, and only the full-screen host scrolls itself.

### Still open (D04)

* Reactions and votes on posts still use the personal identity: the feed's "my reaction" state is keyed to it. They are not sent directly to creator contacts, but third-party relays can still carry them.
* The capture test covers the packets listed above; group traffic keeps its own identity rule (`groupMemberIdentity`).
* With a Hub linked, broadcasts are handed to the Hub, which relays by its own rules.
* `IdentityViewModel` still held an unused copy of the old discoverable code; deleted in R6b.
* New UI strings are English only, and fall back to English in other languages.

### Tests added in this round

| Test class | Covers |
|---|---|
| `CreatorIdentityCaptureTest` | Posts, comments, reactions, identity updates, DM, DM catch-up, typing, sync answer, handshake to a new follower, creator presence and profile updates: nothing sent to a creator contact contains the personal key, onion, handle, bio or avatar, and every envelope is the creator key. Creator side carries the creator profile. Friends get creator posts in relay form and no creator presence. Relays to a follower carry the creator key |
| `IdentityRoutingTest` | Creator handle on the burnable identity; `knowsUsAsCreator` rules; `forPeer` returns nothing without a creator identity; `forPostAuthor`; every `outboundFor` case; `IDENTITY_UPDATE` accepted when signed as senders sign, forged one refused; nothing sent to a creator contact after creator mode is off |

---

## Completed Changes (2026-10-09) — Mesh Feed: Missing and Repeating Posts; No Startup Handshake Broadcast (v0.7.0-alpha)

Device report: switching between All and Mesh sometimes left new mesh posts out, even after "See Old Posts", and sometimes kept showing old posts. A "Tested" label names a test class that must pass with `./gradlew testGithubDebugUnitTest`.

**Verification (2026-10-09).** `./gradlew testGithubDebugUnitTest --rerun-tasks` passed on the developer machine, including the three new test classes. The build was installed on the test devices and the app is working normally so far; the individual scenarios below were not reported one by one, so a regression in them should still be watched for.

**Protocol compatibility.** No packet layout, signature or Room schema change. Nodes stop sending one packet that every receiver already rejected.

### Posts marked seen that the user never saw

* **Cause.** `UnifiedFeedTab` marked "swiped past" slides by page index (`lastSettledPage` up to the settled page). Whenever the list changed under the pager, that range covered other posts:
  * a mesh post arriving at the top moved the current post down a page, and the new post was marked;
  * a replaced list (mode switch, refresh, the next Mesh batch) followed by a jump to the top marked every post between the top and the old page. `logs-2` shows five posts "swiped away" within 10 ms at 10:02:55.
  * Marked posts are excluded from Mesh by `cachedViewedIds`, so they were gone for good.
* **Fix.** The settled item is tracked by id, with the list's ids at that moment. `itemsSwipedPast` marks everything passed over an unchanged list, only a directly adjacent item after a list change, and nothing after a jump to the top or a mode switch. The 4 s dwell is keyed on the item id too.
* Tested (unit: `FeedSwipeMarkingTest`).

### Mesh infinite scroll replaced the list

* **Cause.** In the specific-filter path of `loadMoreFeedItems` (Mesh, My Content, Videos, Images, Audio, Articles, search) every batch replaced the list and scrolled to the top. With more than one batch of mesh posts, nearing the end swapped in the next batch with no way back, and the jump marked posts as above.
* **Fix.** Only the first batch of a mode fills the list and scrolls to the top; later batches are appended.
* Implemented (untested): ViewModel feed paths have no unit harness.

### A pager load could switch mode halfway

* **Cause.** `loadMoreFeedItems(mode)` for a different mode only emptied the list and set the mode. When the pager's load ran before the screen's `syncFilterMode(mode)`, the latter became a no-op. The All feed's `sessionLoadedIds` then stayed in place and hid mesh posts that had been loaded into All but never reached; "See Old Posts" and the mode flow weren't reset either.
* **Fix.** That branch now does what `syncFilterMode` does: cache the Live Feed, empty the list, clear `sessionLoadedIds`, reset "See Old Posts" for Mesh, update `currentFilterModeFlow`.
* Implemented (untested).

### Image, GIF and audio posts were never marked seen

* **Cause.** `markItemViewed`/`recordItemSwiped` skip a mesh post with media until it is downloaded **and** `markMeshMediaPlaybackStarted` was called. Only `VideoPlayer` called it. Image, GIF and audio posts were never marked seen and came back in Mesh as new every time. A post with a media type but no media id, and file posts that weren't downloaded, were held back for good.
* **Fix.**
  * `FullScreenMeshCardV2` reports a downloaded image or GIF once it is on screen; `AudioPlayer` gains `onPlaybackStarted`, called when audio starts playing.
  * `isMeshMediaUnseen` applies the check only to posts with a mesh media id, and not to file attachments.
  * The "Media shown for mesh post" log line is written once per post per session (it used to repeat every few seconds while a video looped).
* Implemented (untested): Compose UI.

### Text posts that began alike hid each other

* **Cause.** `getCanonicalItemKey` keyed a mesh post without a URL by its first 80 characters (`mesh_title_…`). The feed de-duplicates by that key and stores it with viewed/swiped records, so two test posts with similar wording showed as one, and seeing one hid the other.
* **Fix.** Such posts are keyed by their id. Clearnet shares stay keyed by URL (one link shared by several peers shows once); mesh media posts by their media URL. Existing `mesh_title_…` records are left in place and no longer match anything.
* Tested (unit: `MeshCanonicalKeyTest`).

### Untargeted USER_HANDSHAKE on every start

* **Cause.** `NoSlopRepository.updateOnionAddress` runs on every app start. It broadcast a legacy-signed `USER_HANDSHAKE` with no `targetUserId` (6 hops), whether or not the address had changed. Since C02/D07 every receiver rejects it ("targetUserId null is not a local identity", in every node's log). It only spent Tor circuits right after a restart, when connections are hardest to build.
* **Fix.** Nothing is broadcast. The address is stored only when it changed (and never as blank). Peers learn our onion from the `ANNOUNCE_PEER` heartbeat. Signing `onion_address` in `ANNOUNCE_PEER` remains open (S2).
* Tested (unit: `OnionAddressUpdateTest`).

### Not changed

* Switching back to All restores the cached All feed and position on purpose, so it shows the posts it had before. New mesh posts reach All through the normal interleaving on the next load.

### Tests added in this round

| Test class | Covers |
|---|---|
| `FeedSwipeMarkingTest` | Forward and backward swipes over an unchanged list; a post prepended at the top; a replaced list with a jump to the top; a deleted post above; unknown or missing previous item |
| `MeshCanonicalKeyTest` | Text posts that begin alike get different keys; the same clearnet link shares one key; mesh media posts keyed by media |
| `OnionAddressUpdateTest` | No `USER_HANDSHAKE` broadcast on a start with the current address or with a changed one; the identity flow still fires; a blank address isn't stored |

---

## Completed Changes (2026-10-08) — Round B: Media Access Control, Point-to-Point Media & Non-Contact Replies (v0.6.9-alpha)

This round closes review findings D02 and D03 and regression items R6b and R6c. It also stops the "MEDIA_REQUEST for unknown media" flood seen in every device log. A "Tested" label names a test class that must pass with `./gradlew testGithubDebugUnitTest`. Not yet verified on device.

**Protocol compatibility.** `MEDIA_REQUEST` gains an optional `timestamp` and is now signed. Older builds ignore both. Requests from older builds are still served when the requester is a stored peer; an unsigned request from a non-contact is refused. Media packets are no longer relayed, so a node on an older build that relied on relay routing or `MEDIA_RELAY_REQUEST` forwarding gets no answer through third parties. No Room schema change.

### Media flood — media packets were gossiped mesh-wide

* **Cause.** A `MEDIA_REQUEST` sent without `targetUserId` was treated as a public broadcast: every node processed it ("MEDIA_REQUEST for unknown media", then a `MEDIA_RELAY_REQUEST` delegation) and re-broadcast it to all its peers. A missing file also triggered `MEDIA_RELAY_REQUEST`s that went 6 hops, and `startDownload` sent an `ANNOUNCE_DISCOVERABLE` to every media source.
* **Fix.**
  * Requests are addressed to the source (`targetUserId`, hops=1).
  * `GossipService.processIncoming` treats every `MEDIA_*` packet as point-to-point (`isPointToPointMediaType`): addressed elsewhere → dropped; otherwise processed locally and never forwarded.
  * `startDownload` no longer announces itself to media sources.
* Tested (unit: `MediaAccessRoundBTest`).

### D03 — Relay listeners received chunks without an ACL check; the recovery reply was broken

* **Cause.** Any node could send `MEDIA_RELAY_REQUEST` for any media id, register as a listener and be sent a copy of every chunk that passed through (`forwardRelayChunk`), with no ACL check. The recovery reply chain also answered with the wrong identity's onion.
* **Fix.**
  * Removed: `RelayState`/`relayStates`, `handleRelayRequest`, `handleRecoveryFound` (GossipService), `forwardRelayChunk`, `delegateUnknownMediaRequest`, `removePeerFromRelays`, the stale-route sweeper.
  * Recovery is one hop. `attemptMeshRecovery` asks only direct peers that are trusted or members of the owning group, each as the identity that peer knows. `MediaManager.handleRecoveryRequest` answers `MEDIA_RECOVERY_FOUND` only when it holds the file **and** its own ACL authorises the asker, as the identity the asker knows. The asker downloads from the answering peer's stored onion.
* Tested (unit: `MediaAccessRoundBTest`).

### D02 — Media ACL is deny by default

* **Cause.** After the `media_owner` index allowed or fell through, legacy table scans followed and then "any trusted peer" was allowed. So any contact could fetch friends-only, DM or group media they were not entitled to, given the id.
* **Fix.** `isMediaAuthorizedForSender` decides from the `media_owner` index only:
  * own identities → allowed; BLOCKED peer → refused;
  * public post/comment → anyone; private → author; friends-only → author and direct friends (not temporary, not bound to our burnable identity);
  * DM → the conversation partner; group → members and admin, minus banned members;
  * no index row → refused.
* **`accessKey` not added (decision).** D02 also proposed issuing and requiring a per-media key. The identity check already decides every case, and anyone allowed to see the post receives the key with it, so it adds no protection. It would need new fields in signed payloads, back-fill and breaking older clients. `access_key` in requests is ignored. See TECHNICAL_REFERENCE §6.1.1.
* Tested (unit: `MediaAccessRoundBTest`; existing `MediaManagerSecurityTest`, `MediaSyncAndAclRestoreTest` unchanged).

### R6b — Media requests from non-contacts were never served

* **Cause.** The holder replied only to a requester stored in its peer table. A reader of a re-shared public post usually isn't, so public media from non-contacts never loaded. A forwarded request also arrived with `senderId` re-stamped to the relay.
* **Fix.**
  * `MEDIA_REQUEST` is signed over `noslop-media-req-v1|senderId|targetUserId|mediaId|chunkIndex|chunkSize|byteOffset|byteLength|timestamp`.
  * `MediaManager.replyOnionFor`: a stored peer gets its stored onion. A stranger is answered at the onion **derived from its public key**, and only with a valid signature, a timestamp within ±5 min and no replay. Only the key holder can host that onion, so the reply cannot be aimed at someone else.
  * Requests are never forwarded, so `senderId` is always the real requester.
  * `requesterIdentity` uses the burnable identity (main if none) towards a holder that isn't a contact; `responderIdentity` answers as the identity the request was addressed to.
  * A `MEDIA_CHUNK` for a download in progress is exempt from the 2 MB/60 s cap on untrusted senders, which otherwise dropped most of any larger file from a non-contact or group member.
* Tested (unit: `MediaAccessRoundBTest`).

### R6c — Media requests were dropped silently

* **Fix.** Every refusal (unauthorised, unknown requester, bad signature, missing file) is logged at WARN with the reason. A requester over the 16 MB/60 s sender limit now waits for the window to reset (up to 3 attempts) instead of being dropped.
* Tested (unit: `MediaAccessRoundBTest`).

### Still open

* `handleMediaChunk` accepts any offset from the source of a download in progress; the C17 whole-file SHA-256 check rejects a corrupted result.

### Tests added in this round

| Test class | Covers |
|---|---|
| `MediaAccessRoundBTest` | Friends-only vs temporary contacts; DM media refused to third parties; unindexed media refused; public media allowed except BLOCKED; group members allowed, banned and outsiders refused; `replyOnionFor` (known peer, unsigned, signed, replay, forged, stale, tampered); `responderIdentity`; `requesterIdentity` for strangers; media addressed elsewhere dropped, `MEDIA_RELAY_REQUEST` never forwarded |

---

## Completed Changes (2026-10-08) — Round R3: Group Leave & Catch-up, Group Sender Identity, DM Outbox Limits (v0.6.9-alpha)

This round continues the git-history regression register. It covers the group items R7a–R7c and the DM outbox item R8. While tracing those paths, three more defects turned up in the same code; they're fixed here too. Labels are used as in the entry below: a "Tested" label names a test class that exists and must pass with `./gradlew testGithubDebugUnitTest`. Device-verified 2026-10-08 (three nodes on two devices): group messages, reactions, invites, image/GIF/video media; members no longer listed as discoverable.

**Protocol compatibility.** No packet layout changed. Leaves, `GROUP_SYNC` answers and queued group messages from older builds are handled as before. Receivers on older builds also accept an updated admin's newer `GROUP_SYNC` stamp.

### R7a — Leaving a group never reached anyone (regression from `7de5d96`)

* **Cause.** `leaveGroupChat` signed an 11-field `encodeForSigning`, with `""` for every absent field. Receivers verify only the 13-field `canonicalGroupUpdatePayload`, where absent fields are encoded `ABSENT`. So every member and the admin rejected the leave ("signature matches no current member"), and the leaver stayed in everyone else's copy of the group.
* **Fix.** `NoSlopRepository.groupLeavePayload` builds and signs the leave in exactly the receiver's form.
* **Test fix.** `GroupMessageSecurityTest.groupLeave_canonicalSigning_verified` signed and verified the same 11-field string, so it passed while leaves failed on every device. It now verifies the shipped leave against the receiver's form.
* Tested (unit: `GroupLifecycleRestoreTest`, `GroupMessageSecurityTest`).

### Group sender identity (found while tracing R7a)

* **Cause.** Several group paths chose the sending identity by a rule of their own, so a member could act under a key that isn't in the group. This hit a member who was invited under the main key into an open group and also had a burnable identity (any discoverable user). It affected:
  * non-admin `updateGroupChat` (signature, `senderId`, and the invites it sends);
  * `sendGroupMessage`;
  * `reactToGroupChat`.
  
  Receivers drop group messages from non-members and reject updates no member signed, so all of these were lost.
* **Fix.** All of them now use `NoSlopRepository.groupMemberIdentity`, which picks the key actually listed in the group. "Friends only" group messages still use the main key when it's a member.
* `requestGroupCatchup` parses the member list instead of a substring match (review D11), and no longer sends a `GROUP_QUERY` to our own burnable identity.
* Tested (unit: `GroupLifecycleRestoreTest`).

### R7b — A member who missed a member-initiated change could never catch up

* **Cause.**
  * Only admin updates advance a group's `revision`. Leaves and member invites don't.
  * `GROUP_SYNC` answers were stamped with the state revision.
  * Receivers drop a sync whose stamp isn't newer than their stored revision.
  
  So the admin's answer to a `GROUP_QUERY` was ignored by every member already at that revision. (The freshness gates came in with S04/U05, `ab3333b`/`5ac03bf`; before them, any validly signed sync was applied.)
* **Fix.**
  * The admin stamps its answer with the signing time (`groupSyncTimestamp`).
  * Receivers keep the last applied admin sync time in `group_admin_sync_ts_<groupId>`, so a replayed snapshot can't roll state back.
  * Applying an admin sync sets the stored revision to the state's own revision, not the signing time, so a leave still in flight is accepted afterwards.
  * A member's answer keeps the old stamp, so a stale member copy can't pass the gate.
* Tested (unit: `GroupLifecycleRestoreTest`).

### R7c — Group members hit the stranger-DM limit (regression from `34770ba`/`5b6bf1d`)

* **Cause.** The untrusted directed-DM limiter (10/min per sender, 30/min overall) also applied to group messages from members who aren't contacts. Since D01, that includes the group admin. Group messages carry no ACK, so everything over 10/min was lost silently.
* **Fix.** `GossipService.isFromMemberOfStoredGroup` exempts a directed group `MESSAGE` whose sender is a member or the admin of that stored group. A non-member naming the group is still limited.
* Tested (unit: `GroupLifecycleRestoreTest`).

### Queued group messages went out under the wrong sender (found while tracing R8)

* **Cause.** Group legs queued for members without a known onion were flushed as the main identity and as `v = 1`. The v2 AAD binds the sender key, so every queued message from a burnable-identity sender failed to decrypt.
* **Fix.** `MeshSocialRepository.pendingGroupPacket` uses the local echo's `senderPub`, and `v = 2`.
* Tested (unit: `GroupLifecycleRestoreTest`).

### R8 — DM outbox: limits, forced flushes, group legs (from `3c766cb`)

* **Limits.** Each entry's queue time and number of unacknowledged deliveries are persisted (`pending_dm_outbox_meta`).
  * `expireOutbox` gives up after **7 days**, or after **50 deliveries with no `DM_ACK`**. Failed connection attempts don't count toward the 50.
  * A DM it gives up on is marked `FAILED`. The chat shows a red error icon; tapping it (`retryFailedMessage` → `retryFailedDirectMessage`) re-sends the identical packet.
  * New string: "Not delivered. Tap to retry".
* **Group legs.** Only 1:1 DMs wait for an ACK (`awaitsDmAck`). Group fan-out legs that went through the outbox had waited for an ACK that never comes, and were re-sent forever.
* **Forced flushes.** Event-triggered flushes skip the backoff (15 s minimum spacing): the peer's `ANNOUNCE_PEER`, opening the chat, Tor becoming ready. Before, a DM that had backed off to 5 min waited it out even with the peer online.
* **Ordered saves.** Outbox saves take their snapshot under a lock, so saves can't land out of order.
* Tested (unit: `OutboxExpiryTest`).

### Follow-up from device testing (2026-10-08)

* **Group images/GIFs never downloaded.** In an open group, members (and the admin) are listed under their burnable key. Media requests always went out under the main key, so the owner's media check refused them; a device log showed "Rejected unauthorized MEDIA_REQUEST" on every retry.
  * `MediaManager.requesterIdentity` now picks the identity the media's owner authorises:
    * group media → our member key in that group;
    * DM media → the identity bound to that contact;
    * anything else → as before.
  * It's used for `MEDIA_REQUEST` (including its `origin_onion`) and for `MEDIA_TRANSFER_ACK`.
  * Tested (unit: `GroupLifecycleRestoreTest`).
* **Group GIF/video still failing for one member (second device test).** Two more causes:
  * The group chat screen stamped every attachment with the **burnable** onion and owner whenever a burnable identity existed, even when the message was sent under the main member key. Downloads were then pointed at the burnable onion, which one member could never reach ("Connect timed out" on every attempt), while the sender's main onion worked.
    * Attachments now use the same identity as the message: `NoSlopRepository.groupSenderIdentity` / `groupSenderKeys`, which `sendGroupMessage` also uses.
  * Message media is downloaded from the authenticated sender's known onion first, with `originNode` only as a fallback. Both of a node's onions reach the same app, so this also repairs attachments sent before this fix.
  * Downloading group media no longer sends an `ANNOUNCE_DISCOVERABLE` (as the burnable identity) to the sender. Group members already know each other from the signed group directory.
  * Tested (unit: `GroupLifecycleRestoreTest`).
* **Create Group dialog.** The member list now starts with the creator, ticked, greyed out and labelled "You (admin)". This makes it clear that the "NoSlop · Creator" row below is the official NoSlop creator node, not the group's own creator.
* **Invite not received by one node.** In this test that node (both apps on one emulator) couldn't be reached at all. The queued `GROUP_INVITE` was retried from the outbox every minute and is delivered once the node is reachable. No code change was needed.

### Reviewed, not changed

* **R7d.** After an invite is accepted, the group is stored and catch-up accepts syncs from any member. Only a member-signed sync arriving *before* acceptance is refused, which is harmless.
* **R7e** (deliberate: only the admin can restore a group to a reinstalled member).
* **R7f.** The `GROUP_UPDATE` freshness gate stays before the signer check. Accepting older member updates would let a replayed leave remove a re-added member.

### Tests added or changed in this round

| Test class | Covers |
|---|---|
| `GroupLifecycleRestoreTest` | R7a leave (admin and member receivers; the old form is rejected), member identity, R7b sync heal, replay guard and in-flight leave, R7c limiter, queued group sender |
| `OutboxExpiryTest` | 7-day expiry → FAILED, ACKed DMs untouched, 50-delivery cap, group legs exempt, ACK cleanup, retry re-queues the same bytes, metadata round trip |
| `GroupMessageSecurityTest` (changed) | Leave test now checks the shipped leave against the receiver's form |

---

## Completed Changes (2026-10-07 – 2026-10-08) — Review Round 2 Remediation (D01/D06/D07), Handshake Reply Path & Git-History Regression Restoration (v0.6.9-alpha)

This round has two parts. The first fixes the round-2 review findings D01, D06 and D07, plus groundwork for D02. The second is a review of the last 100 commits, done because several features that used to work had stopped working on devices. It found and fixed the regressions behind the failures seen in two-device testing.

**How the status labels are used.** A "Tested" label names a test class that exists under `app/src/test` and passed with `./gradlew testGithubDebugUnitTest`. Every test runs the real handlers or repositories against an in-memory Room database or a real loopback socket; none of the code under test is mocked. "Verified on device" means the developer confirmed it in two-device testing on this build.

**Verified on device (2026-10-08).** These all work between a trusted peer and a burnable-identity peer:
- DMs, including recorded video and GIFs
- Mesh posts, including video posts
- Reactions
- Comments, including GIF comments
- The keyboard in DMs and comments
- DM video that had been stuck before the fix, which then completed

Under heavy load, GIFs can be slow to arrive, but they do arrive.

> **Note on version naming.** Code comments written in this round call the release "v0.7.0". `versionName` is still `0.6.9-alpha` (versionCode 69).

### Breaking change: the handshake is v2-only (D07)

* **Both sides must update.**
  * `HandshakePacketHandler` now accepts only `CONNECTION_REQUEST` and `USER_HANDSHAKE` packets carrying a `canonicalHandshakePayloadV2` signature. That signature binds the encryption key, the target identity and the nonce. The packet must also have a non-blank `request_nonce`.
  * A request signed with only the legacy encoding is rejected and logged as `legacy (pre-v2) handshake — peer must update NoSlop`.
  * A handshake signed with only the legacy encoding never promotes the peer, even when the nonce matches.
* **The older-version case is shown to the user.**
  * When a key is rejected for speaking the legacy protocol, that key is recorded, and the user gets at most one notification a day: "Older NoSlop Version".
  * `NoSlopRepository.isPeerOnLegacyProtocol(pub)` exposes this state.
* **Sent requests are visible.**
  * The DMs tab has a new **SENT REQUESTS** list (`OutgoingRequestRow`) showing every `OUTGOING_PENDING` row. Each row has **Re-send** and **Cancel Request** actions.
  * When the peer is known to be on the legacy protocol, the row says so.
  * `MeshSocialRepository.cancelOutgoingRequest` removes the pending row.
  * Seven new strings were added to `content_en.json`.
* **Not done.** D07 also asked the migration to map un-trusted rows from the user's own requests to `OUTGOING_PENDING`. That wasn't done; the user re-sends from the Sent Requests list instead.
* Tested (unit: `PeerTrustInvariantTest`: `d07_*`).

### Room schema 18 → 19 (`MIGRATION_18_19`)

* **Changes:**
  * Adds `peers.verifiedFingerprint`.
  * Creates the `media_owner` table (PK `mediaId, ownerType, ownerId`, index on `mediaId`) and back-fills it from every post, comment and message attachment.
  * Repairs trust:
    * Contacts of the burnable identity are forced to `isTemporary = 1`.
    * `isTrusted` is re-derived as `relationship = 'ACCEPTED'` for every row.
    * This demotes every peer that the old startup "heal" promoted without consent, and every group admin that `acceptGroupInvite` had made a friend.
* **Validated against the real schema.** The test builds a real v18 database from `app/schemas/.../18.json`, migrates it, and lets Room validate it against the v19 entities.
* Tested (unit: `Migration18To19Test`).
* No device ran the interim builds 087–089, so the late change to `MIGRATION_17_18` (register item M1) doesn't affect any installed database.

### D01 — Trust has a single source of truth

* **The heal is gone.** The startup "heal" block that promoted burnable-contact peers to trusted on every start was removed. Startup maintenance now lives in `NoSlopRepository.runStartupPeerMaintenance()`, so the regression test can run exactly that code.
* **`PeerRelationship` constants** cover `NONE`, `OUTGOING_PENDING`, `INCOMING_PENDING`, `ACCEPTED` and `BLOCKED`. `ACCEPTED` is the only state that grants trust.
* **`PeerDao.insertPeer`** is now the only write path for peers. It stores `Peer.withNormalizedTrust()`, so `isTrusted == (relationship == ACCEPTED)` holds on every write. The raw REPLACE is `insertPeerRow`.
* **`Peer.isFriend`** (`ACCEPTED && !isTemporary`) is the single "may receive friends-only content" rule. These all use it: `SyncPacketHandler` (sync request, inventory sync and comment/reaction sender checks) and the `GossipService` friends-only inbound and outbound filters.
* **Contact identity is bound only at accept time.**
  * `handleConnectionRequest` records which identity the peer addressed in `requested_identity_<pub>`.
  * `contact_identity_<pub>` is written only in `acceptConnectionRequest`.
* **Specific decisions:**
  * A group admin is a group member (`NONE`), not a friend.
  * The Hub's Admin AI and Hub-restored contacts are `ACCEPTED` on purpose, because the Hub is the user's own node.
  * `togglePeerTrust` was removed.
* **No automatic consent.** Neither a keyless DM sender nor a keyless group member triggers an automatic `CONNECTION_REQUEST` any more. If it did, a crossing request would fake the user's consent.
* Tested (unit: `PeerTrustInvariantTest`: `d01_*`).

### D06 — Peer rows are merged, never rebuilt

* `sendConnectionRequest` merges into the existing row:
  * An `ACCEPTED` friend is never downgraded, and its fields are kept.
  * A `BLOCKED` peer is never contacted.
  * An existing `INCOMING_PENDING` request is simply accepted.
* `BLOCKED` is left only by explicit user action. A request from a blocked peer is ignored.
* **Crossing requests count as mutual consent.** If our row is `OUTGOING_PENDING` and a valid v2 request addressed to us arrives, the row is staged as `INCOMING_PENDING` with *their* nonce and promoted through `acceptConnectionRequest`.
* A request from an accepted friend keeps them `ACCEPTED`. A changed encryption key is held in `pendingEncKey`.
* `rejectConnectionRequest` acts only on `INCOMING_PENDING` rows, so a stale notification can never delete a friend. `CONNECTION_REJECTED` only cancels our own pending request.
* Presence updates touch only the presence columns (`PeerDao.updatePresence`, `markOffline`, `setDiscoverableFlag`). They never re-insert a whole row.
* Tested (unit: `PeerTrustInvariantTest`: `d06_*`).

### D02 (groundwork) — The `media_owner` index

* **DAO-maintained.** The `PostDao`, `CommentDao` and `MessageDao` insert, edit, orphan and delete paths keep `media_owner` up to date themselves. No caller can forget to register an attachment.
* **Visibility rules:**
  * Comment media inherits the visibility of its parent post.
  * Unknown privacy values are stored as `friends` (deny by default).
* **Write-once secrets.** `MediaOwnerDao.attachSecrets` adds the `accessKey` and `sha256` to a row and never overwrites an existing value.
* **Still open** (closed in Round B, see top entry). The index is used by the R2 allow path (below), but the ACL isn't deny-by-default yet. The trailing "any trusted peer" fallback is still there, and no `accessKey` is generated yet.
* Tested (unit: `MediaOwnerIndexTest`).

### Round A2 — Handshake answers come back on the requester's own connection

**The problem.** After a fresh install or an onion change, a newly published onion descriptor takes minutes to propagate. During that window the requester can reach the accepter, but not the other way round. So the accepter's `USER_HANDSHAKE` was lost, and the pairing never completed.

* **Framing:**
  * New `mesh/MeshFraming.kt` holds `ReplyChannel = suspend (NetworkPacket) -> Boolean`, `FrameReader` (the newline framing rules, moved out of the listener unchanged) and `FrameTooLargeException`.
  * Tested (unit: `FrameReaderTest`).
* **Listener:**
  * `MeshTransport.handleIncomingConnection` gives each inbound connection a write-serialised reply channel. It passes that channel through `NoSlopRepository.handleIncomingPacket` and `MeshPacketHandler` to the handlers.
  * Only the `CONNECTION_REQUEST` handler ever uses it.
* **Requester:**
  * When `MeshTransport.exchangeOverSocket` sends a `CONNECTION_REQUEST`, it keeps the socket open for `HANDSHAKE_REPLY_WINDOW_MS` (6 s).
  * It accepts one `USER_HANDSHAKE` or `CONNECTION_REJECTED` reply, and only from the identity it contacted.
* **Accepter rules (`HandshakePacketHandler`):**
  * The reply channel is used only after the request passed the signature, target and freshness checks.
  * It is used at most once per request timestamp (`claimReplySlot`), so a replayed or forged request never collects our handshake.
* **Probing (`MeshSocialRepository.startOutgoingRequestPoll`):**
  * While a request is `OUTGOING_PENDING`, the requester re-sends it every 15 s for up to 10 min. Each probe uses `MeshTransport.probeConnectionRequest`, which has its own 2-slot pool and a 30 s connect timeout.
  * Each probe keeps the **same nonce** but has a fresh timestamp and a fresh packet id.
  * Probing stops when the row leaves `OUTGOING_PENDING`, when its nonce changes, or when the user cancels.
  * Probes never touch the Hub, the outbox, gossip or the peer cooldown.
* **Declined requests:**
  * `rejectConnectionRequest` resolves the addressed identity *before* it removes `requested_identity_*`, so a burnable-identity request is declined by the burnable identity.
  * It stores the declined nonce in `declined_request_<pub>`.
  * Later probes with that nonce are answered with `CONNECTION_REJECTED` (`answerDeclinedRequest`) instead of re-asking the user. A new request (new nonce, from **Re-send**) is asked normally.
* `acceptConnectionRequest(peer, replyVia)` answers on the reply channel when there is one, and over a normal send otherwise.
* Tested (unit: `HandshakeReplyPathTest`: real `MeshTransport` listener on loopback, real repository stack, real Ed25519 keys on the other end of a real TCP socket, no Tor).

### Camera QR scanner

* **Cause.** CameraX delivers the Y plane row-padded (`rowStride > width`) and sometimes with `pixelStride > 1`. Handing that buffer straight to ZXing sheared the image, so the scanner never decoded a code.
* **Fix.**
  * `QRScanScreen.packLuminancePlane` repacks the plane into a tight buffer.
  * The camera is bound once rather than on every recomposition.
* Tested (unit: `QrFrameDecodeTest`: padded rows, pixel stride 2, tightly packed, inverted, no code).

### Keyboard did not open in DMs or comments

* **Cause.** Commit `6f6dffe` changed the base class of the chat/comment input to `AppCompatEditText`. Under the app's platform theme (`android:Theme.DeviceDefault.NoActionBar`), that lost the EditText style: the field wasn't focusable in touch mode, so a tap never focused it.
* **Fix.** `ui/components/AndroidGifTextField.kt` was rewritten around `internal class GifEditText : EditText`, which:
  * sets `focusable`, `focusableInTouchMode` and `clickable` explicitly;
  * refreshes its callbacks (`onValueChange`, `onMediaAttached`, `onSend`, `currentValue`) in `update` so they're never stale;
  * uses `applySendOnEnter` to switch between send-on-Enter and multi-line.
* Keyboard GIF/image insertion (`image/gif`, `image/png`, `image/jpeg`, `video/mp4`) is unchanged.
* Tested (unit: `GifEditTextTest`). Verified on device.

### Recorded video stuck at 9%

* **Cause.**
  * The downloader grows its chunk window by 32 KB after every chunk and allowed it to reach 1 MB.
  * Senders reject any request above `MAX_CHUNK_BYTES` (256 KB, C03).
  * So from the third request (288 KB) onward, every request was rejected and re-queued at the same size. A 5.3 MB video stopped at 2 of 21 chunks.
* **Fix (`MediaManager`):**
  * `MAX_CHUNK_SIZE = MAX_CHUNK_BYTES` (256 KB).
  * `grownChunkSize` caps the window.
  * `splitToServable` splits an oversized pending range into servable pieces with no gap.
  * `chunkRequestRejection` is the single sender-side bounds check, shared with the test.
* The outbound media limit per requester rose from 5 MB to **16 MB per 60 s** (about 270 KB/s). The old limit was below normal Tor throughput, so a single 5 MB+ video tripped it mid-transfer.
* Tested (unit: `MediaChunkWindowTest`). Verified on device.

### Git-history regression review (commits 001–096)

* Every commit in the last 100 was diffed and checked against the current tree. Findings are recorded as a register:
  * **Tier 1** (mesh delivery): R1–R8
  * **Tier 2** (other runtime): V1–V4, F1–F4, H1–H2, B1–B2, D1–D2, L1, U1, S1, M1, C1
  * **New security finding**: S2
* Items marked ✔ were re-verified against the work tree. Fixed items are listed below; open items are under **Still open** at the end of this entry.
* Already resolved, no action needed: D1 (`REQUEST_INSTALL_PACKAGES` is present in the `github` flavour manifest) and M1 (see the schema notes above).

### R1 — Mesh delivery restored

* **Peer cooldown (`GossipService`):**
  * Failures accumulate only within `PEER_FAILURE_WINDOW_MS` (5 min). Since `679a5a1` and `fd958b9` the window had been 2 h, so one failed send after each Tor restart left peers in cooldown for hours.
  * The cooldown is 30 s → 60 s → 120 s max (`cooldownFor`).
  * An authenticated inbound packet from a peer clears its cooldown again, through `recordSendSuccess` on the sender's stored onion. That behaviour came from F18 and was removed in `4579860`.
  * The test clock is injectable (`GossipService.clock`).
* **Transport policy (`MeshTransport` companion):**
  * Only background presence (`ANNOUNCE_PEER`, `ANNOUNCE_DISCOVERABLE`, `USER_EXIT`) is skipped for a peer in cooldown (`respectsPeerCooldown`). Since `439c83b` and `34770ba`, posts, comments, reactions, votes, edits, deletes and sync to such a peer had been silently dropped.
  * `CONNECTION_REQUEST` and `USER_HANDSHAKE` use the priority pool again (`usesPrioritySlot`, `dmSemaphore`, blocking acquire). They had fallen into the 4-permit bulk pool and were dropped after 4 s.
  * `TYPING`, `READ_RECEIPT` and `DM_ACK` failures no longer count toward a peer's cooldown (`countsAsPeerFailure`).
* **DM ACKs.** `DmPacketHandler` sends `DM_ACK` fire-and-forget through `AppScopes.io`. Sending inline had held the inbound handler and a DM slot for the whole Tor send.
* **Outbox.** `startOutboxWorker` no longer pauses while a video player is mounted. `VideoPlayer` now clears `PreloadManager.isVideoActive` when the resolved source isn't a direct stream, or the slide isn't visible. Before, embeds and unavailable sources left the flag stuck on and stalled DMs, handshakes and group messages indefinitely.
* **Tor (`TorService`):**
  * `forgetRegisteredHiddenServices` clears the hidden-service bookkeeping on `STATUS_OFF`, in `stopTor` and on daemon start. A restarted daemon then gets `ADD_ONION` again; before, the node's onion stayed unreachable until the app was killed.
  * `startBlockedInBackground` makes `awaitReady` return `false` immediately when Android refused to start Tor from the background, so the work goes to the outbox instead of waiting out the full timeout.
  * When the network returns while Tor is parked in `PROXY_READY`, `confirmBootstrapThenPromote()` re-checks the bootstrap. A Tor started offline used to stay `PROXY_READY` forever.
* Tested (unit: `PeerCooldownRestoreTest`). Verified on device.

### R2 — Media posts and comment media sync again; comment media is served

* **Sync accepts v2 (C17) signatures.** `SyncPacketHandler.handleSyncResponse` now accepts the v2 signature, which covers the 8 canonical fields plus the media SHA-256, for both posts and comments. It used to accept only the 8-field post signature, so every media post or comment with a hash binding was rejected by sync.
  * When only a legacy signature verifies, the unsigned `sha256` is removed before auto-download.
* **Sync carries the digest.**
  * `toPostPayload` and `toCommentSyncData` (now `suspend`) send `media_metadata.sha256`.
  * `CommentSyncData` now carries `media_metadata`.
* **Digest bookkeeping (`NoSlopRepository`):**
  * `recordMediaDigest` stores a verified digest on every owner row of the media.
  * `mediaDigestFor` returns the stored digest, or hashes the local file and caches the result.
  * The live POST/COMMENT handlers and local post/comment creation record the digest.
  * Comment auto-download metadata keeps `sha256` only when the comment was v2-signed.
* **Re-issue keeps the binding.** `reissueLocalPostsWithCanonicalSignatures` keeps a valid v2 signature, and upgrades a legacy one to v2 when the digest is known. Before, every Tor start re-signed media posts as 8-field and stripped the hash binding.
* **Comment media ACL.** `MediaManager.isAuthorizedByOwnerIndex` now runs first in `isMediaAuthorizedForSender`:
  * public post or comment media → anyone;
  * private → the author only;
  * friends-only → direct friends (not temporary or burnable contacts) and the author;
  * DM → the conversation partner;
  * group → members and the admin.
  
  Comment media had had no allow path since `5f9a127`, so comment GIFs and images never loaded remotely. Anything not matched falls through to the existing checks.
* Tested (unit: `MediaSyncAndAclRestoreTest`). Verified on device.

### Tests added in this round

| Test class | Covers |
|---|---|
| `PeerTrustInvariantTest` | D01, D06, D07 (16 tests) |
| `MediaOwnerIndexTest` | D02 index maintenance |
| `Migration18To19Test` | `MIGRATION_18_19` against the exported v18 schema |
| `FrameReaderTest` | A2 framing |
| `HandshakeReplyPathTest` | A2 reply path, probing, declined requests, replay/forgery |
| `QrFrameDecodeTest` | QR luminance repacking |
| `GifEditTextTest` | Keyboard focus, IME content types, send-on-Enter |
| `MediaChunkWindowTest` | Chunk window vs. sender cap |
| `PeerCooldownRestoreTest` | R1 cooldown window and transport policy |
| `MediaSyncAndAclRestoreTest` | R2 v2 sync, digest, re-issue, comment media ACL |

These existing tests were adjusted to the new APIs: `MeshSocialRepositoryTest`, `GossipServiceTest` and `FakeDaos`.

### Status corrections (D10)

* The 2026-10-06 entry below cites test classes that don't exist in the repository: `IdentityRepositoryTest`, `SyncPacketHandlerTest`, `CryptoServiceTest`, `JamendoApiClientTest` and `LoggerTest`. It also cites `OnboardingScreen`, which is a composable, not a test.
* Those changelog lines are kept as the historical record. The verification matrix now shows what is actually tested.
* Other claims in that entry that don't hold:
  * No `secureDelete()` function exists (C06).
  * `IdentityViewModel` was an unused copy; deleted in R6b (2026-10-11).
  * `deriveSafetyFingerprint()` has no callers, so no screen shows a fingerprint yet (C11).
  * The README sentences about fingerprints, the creator identity, exit affinity and translation coverage were corrected.

### Still open (carried forward)

* **Review round 2:**
  * **D02** — deny-by-default ACL; issue and require `accessKey`.
  * **D03** — relay listeners get chunks without an ACL check; the recovery reply is broken.
  * **D04** — `IdentityRouter`; separate creator profile; capture test.
  * **D05** — v1 DM latch; `DM_ACK` domain separation; outbox expiry.
  * **D08** — the live `POST` and `EDIT_POST` path still passes an unsigned `sha256` to auto-download when only the legacy signature verified. Sync is fixed by R2.
  * **D09** — typed `ImportResult`; check before commit.
  * **D10** — fingerprint UI and `pendingEncKey` banner; status-file test-existence check. (`IdentityViewModel` deleted in R6b.)
  * **D11** — smaller items.
  * **D12** — items still open from round 1.
* **Regression register:**
  * **R4d** — handshake send budget. The A2 reply path covers the main symptom.
  * **R6b** — media requests from non-contacts and public media requests; hub re-stamping.
  * **R6c** — over-cap or unknown media requests are dropped silently.
  * **R7a** — `leaveGroupChat` signs 11 fields, but receivers require the 13-field `canonicalGroupUpdatePayload`, so every leave or non-admin delete is rejected.
  * **R7b–R7f** — `GROUP_SYNC` revision stamping; group rate limit for non-friend members; invitee bootstrap; reinstall recovery; freshness check before signer check.
  * **R8** — DM outbox backoff throttles event-triggered flushes; no expiry.
  * **Tier 2** — V1–V4, F1–F4, H1–H2, B1–B2, D2, L1, U1, S1, C1.
* **S2 (security, new):**
  * The `ANNOUNCE_PEER` signature covers only `authorId|timestamp`. `onion_address` is unsigned, and `handleAnnouncePeer` overwrites the stored onion with it.
  * A replayed fresh announce could therefore redirect a friend's onion.
* **Background Tor.** Tor dies in the background unless `NoSlopForegroundService` is running, which it is only when the background-mesh setting is on. Android then refuses the restart. This isn't a regression; R1 makes callers fail fast instead of waiting.

---

## Older Entries

Entries from 2026-10-06 and earlier, and the old register and backlog, are in [archived/PROJECT_STATUS_HISTORY.md](archived/PROJECT_STATUS_HISTORY.md).

## Security & Codebase Audit Verification Matrix (C01–C26 & W01–W08)

| Finding ID | Severity | Description | Status | Validation / Test Suite |
|---|---|---|---|---|
| **C01** | P0 | USER_HANDSHAKE relationship state machine & nonce exchange (heal side door closed by D01) | **Resolved** | `HandshakeSecurityTest`, `PeerTrustInvariantTest`, `HandshakeReplyPathTest` |
| **C02** | P0 | Handshake encryption key binding & destination auth (`noslop-hs-v2`) | **Resolved** | `HandshakeSecurityTest` |
| **C03** | P0 | MEDIA_REQUEST bounded params, ACL, & coroutine exception isolation | **Resolved** — bounds ✔; deny-by-default ACL (D02, Round B); relay removed (D03); `accessKey` deliberately not used | `MediaManagerSecurityTest`, `MediaChunkWindowTest`, `MediaSyncAndAclRestoreTest`, `MediaAccessRoundBTest` |
| **C04** | P0 | Article WebView Tor leak elimination & external browser gating | **Resolved** | `TorLeakArchitectureTest` |
| **C05** | P0 | Deleted unauthenticated Invidious gossip (video hijacking / SSRF) | **Resolved** | `GossipServiceTest` |
| **C06** | P0 | Plaintext backup external storage leak elimination & streaming | **Resolved** with E3 (2026-10-13; backup and restore not yet checked on device) — NSG2 encrypts the zip as it is written and decrypts it as it is read, so no plaintext zip or group-message file is written on export or restore; `secureDelete()` is not needed and does not exist | `BackupNsg2E3Test`, `BackupArchiveFormatE3Test` |
| **C07** | P1 | Backup AEAD 64KB block streaming & staging isolation | **Resolved** with E3 (2026-10-13; not yet checked on device) — NSG2: 1 MiB AES-256-GCM chunks (chunk number and last flag in the nonce, header as associated data), a per-archive HKDF-SHA256 key instead of `seed[0:32]`; truncated, reordered, modified or extended archives refused before anything changes; NSG1 and confirmed CBC backups still restore | `BackupArchiveFormatE3Test`, `BackupNsg2E3Test` |
| **C08** | P1 | DM v2 directional AAD encryption & replay tracking | **Resolved** with D05 (2026-10-10) — v1 latched per key, ACK has its own AAD label | `CryptoServiceRobolectricTest`, `DmProtocolD05Test` |
| **C09** | P1 | Hop count capping (hops ≤ 6) & parsed group membership | **Resolved** — hops capped; membership checks parse the list (R3); envelope signature on every packet (E2, 2026-10-13; hard protocol break by owner decision): Ed25519 by `sender_id` over everything but `hops`, 1-hour clock window, replay guard | `GossipServiceTest`, `EnvelopeAuthTest`, `HandshakeReplyPathTest` |
| **C10** | P1 | Severable creator identity linkage elimination in sync batches | **Resolved** with D04 (Round C, 2026-10-09) — see D04 | `CreatorIdentityCaptureTest`, `IdentityRoutingTest` |
| **C11** | P1 | 20-character visual safety fingerprint derivation | **Resolved** (Rounds D10/D10b, 2026-10-11; not yet device-verified) — on the contact card and the ID card; verified in person by camera scan plus approval; check code; key changes applied with a notice | `SafetyFingerprintD10Test`, `ContactVerificationD10bTest` |
| **C12** | P1 | Replaced Google ML Kit with pure offline ZXing scanner | **Resolved** | `TorLeakArchitectureTest` |
| **C13** | P1 | Migrated to pure lightweight Bouncy Castle engine (pruned Lazysodium/JNA) | **Resolved** | `CryptoServiceRobolectricTest` |
| **C14** | P1 | Bouncy Castle PBKDF2 seed derivation (API 24–25 compatibility) | **Resolved** | `MnemonicGeneratorTest` |
| **C15** | P1 | Cross-device keypair verification & authoritative derivation | **Resolved** — X25519 public key also reconciled; mismatch reported as recovery-required (D09, 2026-10-10) | `BackupManagerTest`, `BackupImportResultD09Test` |
| **C16** | P1 | Hardware-backed AndroidKeyStore master key for fallback storage | **Implemented (untested)** — the test cited on 2026-10-06 (IdentityRepositoryTest) never existed. Decision (owner, 2026-10-13): kept as it is, with the red warning banner; no passphrase option | — |
| **C17** | P1 | Mesh media SHA-256 integrity hash binding & download verification | **Resolved** with D08 (2026-10-10) — v2 binding via live, sync and re-issue; unsigned digests never used; "Unverified media" label since Round D10 (2026-10-11) | `MediaManagerSecurityTest`, `MediaSyncAndAclRestoreTest`, `PostDigestBindingD08Test` |
| **C18** | P1 | DM outbox delivery ACK & state machine (Sending → Sent → Delivered) | **Resolved** — ACK sent off the inbound path (R1); outbox expiry → FAILED with retry, group legs not held for ACKs (R3) | `CryptoServiceRobolectricTest`, `WireProtocolTest`, `OutboxExpiryTest` |
| **C19** | P1 | Release log history sanitization (origin-only URLs, masked IDs, 72h pruning) | **Resolved** (E1, E2b, E5s, E5s2; 2026-10-13) — search terms and video ids redacted in release logs (`Logger.redact`), request labels never carry them, Reddit, YouTube, Invidious and Jamendo logs fixed. The test cited on 2026-10-06 (LoggerTest) never existed | `LoggerRedactTest`, `CodeHygieneArchitectureTest`, `SearchPagingE5sTest` |
| **C20** | P1 | Removed Jamendo client ID candidate rotation loops | **Implemented (untested)** — the test cited on 2026-10-06 (JamendoApiClientTest) never existed | — |
| **C21** | P2 | Respected NXDOMAIN in cascading DNS (DoH opt-in only) | **Resolved** | `TorLeakArchitectureTest` |
| **C22** | P2 | Click-to-load prompts for remote GIFs & retired cleartext GROUP_MESSAGE | **Resolved** | `TorLeakArchitectureTest` |
| **C23** | P2 | Translation key parity (`content_en.json`), pre-install APK cert verification, docs sync | **Resolved** | `TranslationParityTest` |
| **C24** | P2 | Dependency hygiene (pruned jtorctl/netcipher) & narrowed ProGuard rules | **Resolved** (E5, 2026-10-13; playback checked on device; minified release not yet checked, see `docs/BUILD.md` §8.2) — jtorctl/netcipher pruned, OkHttp rules narrowed; the Bouncy Castle keep-all rule narrowed to its JCA provider packages; Media3 1.3.1 → 1.11.1 | `ReleaseBuildRulesE5Test`, `AdaptiveTorLoadControlE5Test` |
| **C25** | P2 | Shared `Json.gson`, structured `AppScopes`, extracted `IdentityViewModel` | **Resolved** — the unused `IdentityViewModel` copy was deleted (R6b, 2026-10-11); the account code lives in `NoSlopViewModel`; ad-hoc coroutine scopes moved to `AppScopes` (E1, 2026-10-13) | `CodeHygieneArchitectureTest` |
| **C26** | P2 | Canonical presence-encoded signing formats (`ABSENT` / `CLEAR` / `SET:`) | **Resolved** | `GroupMessageSecurityTest` |
| **W01** | P0 | Synchronized Feed sync cancellation deadlock fix | **Resolved** | `FeedRepositoryTest` |
| **W02** | P0 | Eliminated legacy 4-field GROUP_UPDATE bypass | **Resolved** | `GroupMessageSecurityTest` |
| **W03** | P0 | Injective group update canonical encoding & presence flags | **Resolved** | `GroupMessageSecurityTest` |
| **W04** | P1 | Group revision alignment strictly on admin & ban clearing propagation | **Resolved** | `GroupMessageSecurityTest` |
| **W05** | P1 | Synchronous API key commits & rollback atomicity | **Resolved** | `BackupManagerTest` |
| **W06** | P1 | Monotonic author-scoped tombstones (`timestamp > existingTs`) | **Resolved** | `PostPacketHandlerTest` |
| **W07** | P1 | Content-matching signature upgrades on equal timestamps | **Resolved** — avatar and recorded digest also compared since D08 (2026-10-10) | `PostPacketHandlerTest`, `PostDigestBindingD08Test` |
| **W08** | P1 | Asynchronous recovery StateFlow & runBlocking elimination | **Implemented (untested)** | `OnboardingScreen` is a composable, not a test |

### Review round 2 (D01–D12) and git-history regressions — status 2026-10-13

| Finding ID | Severity | Description | Status | Validation / Test Suite |
|---|---|---|---|---|
| **D01** | P1 | Startup "heal" granted trust without consent; one trust rule (`relationship`, `Peer.isFriend`) | **Resolved** | `PeerTrustInvariantTest`, `Migration18To19Test` |
| **D02** | P1 | Media ACL fallback to "any trusted contact"; `accessKey` never issued; `media_owner` index | **Resolved** (Round B) — index-only, deny by default; `accessKey` deliberately not issued (see TECHNICAL_REFERENCE §6.1.1) | `MediaOwnerIndexTest`, `MediaSyncAndAclRestoreTest`, `MediaAccessRoundBTest` |
| **D03** | P1 | Relay listeners receive chunks without ACL; broken recovery reply leaks main onion | **Resolved** (Round B) — relay removed; one-hop recovery behind the ACL | `MediaAccessRoundBTest` |
| **D04** | P1 | Creator (burnable) identity linkable to personal identity | **Resolved** (Round C, 2026-10-09; follow-ups D10c–D10e, 2026-10-11/13, device-tested; E4, 2026-10-13) — `IdentityRouter`, `outboundFor`, sync exclusion; post identity follows privacy, attachments name the sending identity (D10c); one profile by default with Studio overrides (user decision), creator identity backup/import/restore, burn warnings (D10d); burned or replaced identity's contacts forgotten (D10e); reactions and votes made by the post's identity, "Share Permanent Identity" asks from the personal identity (E4). Still open: Hub relaying | `CreatorIdentityCaptureTest`, `IdentityRoutingTest`, `CreatorPostIdentityD10cTest`, `CreatorIdentityArchiveD10dTest`, `IdentityLeftoversE4Test` |
| **D05** | P1 | DM v1 fallback, ACK confusion, infinite outbox retries | **Resolved** (2026-10-10; not yet device-verified) — outbox expiry + FAILED/retry (R3); v1 latch, ACK domain label + "ACK" check (Round D05). "Sent final for v1 peers" deliberately not done (see Round D05) | `OutboxExpiryTest`, `DmProtocolD05Test` |
| **D06** | P2 | Peer rows overwritten wholesale; blocked peers could unblock; crossing requests | **Resolved** | `PeerTrustInvariantTest` |
| **D07** | P2 | v2 handshake compatibility — **decision: hard protocol break**, older-version state shown | **Resolved** (migration remap of own requests not done) | `PeerTrustInvariantTest` |
| **D08** | P2 | Hash downgrade on legacy posts; W07 match ignores signed fields | **Resolved** (2026-10-10; device-verified: post edits sync) — sync (R2), live POST/EDIT, own caption edits, `insertPostSafely`/`editPostSafely`; "Unverified media" label added in Round D10 (2026-10-11) | `MediaSyncAndAclRestoreTest`, `PostDigestBindingD08Test`, `OwnPostEditDigestD08Test`, `SafetyFingerprintD10Test` |
| **D09** | P2 | Cross-device restore mismatch reported after commit | **Resolved** (2026-10-10; not yet device-verified) — typed `ImportResult`, pre-commit identity plan, restart into recovery, X25519 reconciliation | `BackupImportResultD09Test` |
| **D10** | P1 | Status document claims tests/code that don't exist | **Resolved** (Rounds D10/D10b, 2026-10-11; not yet device-verified) — claims corrected and checked by a test, `IdentityViewModel` deleted (R6b), fingerprint UI, verification by camera scan (D10b, replaced "Mark verified"), automatic key changes with a notice, 8-character Argon2id tags, per-language translation report | `StatusDocCitationTest`, `SafetyFingerprintD10Test`, `ContactVerificationD10bTest`, `TranslationParityTest` |
| **D11** | P3 | Smaller items (Tor Browser `<queries>`, log terms, substring membership, scopes, …) | **Resolved** (E1, 2026-10-13; Media3 in E5) — Tor Browser `<queries>`, redacted log terms, AppScopes, logged catches, no Ed25519 key in place of a missing X25519 key; substring membership (R3) and the certificate WARN were already done | `CodeHygieneArchitectureTest`, `LoggerRedactTest`, `MeshSocialRepositoryTest`, `ReleaseBuildRulesE5Test` |
| **D12** | — | Carried from round 1 (C07 streaming AEAD, C09 envelope signature, C06 zip, C27) | **Resolved** except C27 — C07 and C06 (E3), C09 (E2), release smoke-test checklist (E5). C27 persistent per-peer connections: deferred (owner, 2026-10-13) | `BackupNsg2E3Test`, `EnvelopeAuthTest` |
| **A2** | — | Handshake answers on the requester's connection; pending-request probing | **Resolved** | `HandshakeReplyPathTest`, `FrameReaderTest` |
| **QR** | — | Camera QR frames never decoded (row padding / pixel stride) | **Resolved** | `QrFrameDecodeTest` |
| **KB** | — | Keyboard did not open in DMs/comments (`6f6dffe`) | **Resolved** (device-verified) | `GifEditTextTest` |
| **VID** | — | Recorded video stuck at 9% (chunk window above sender cap) | **Resolved** (device-verified) | `MediaChunkWindowTest` |
| **R1–R4c** | — | Cooldown memory, cooldown gate, cooldown clear, blocking ACK, handshake pool, outbox video gate, Tor hidden-service re-registration, background start, offline PROXY_READY | **Resolved** (device-verified) | `PeerCooldownRestoreTest` |
| **R5, R6a** | — | Sync rejected v2-signed media posts/comments; re-issue stripped hash; comment media never served | **Resolved** (device-verified) | `MediaSyncAndAclRestoreTest` |
| **R7a–R7c, R8** | — | Group leave signature; group catch-up after member-initiated changes; group members rate-limited; DM outbox backoff/expiry (plus group sender identity and queued group sender) | **Resolved** (group paths device-verified) | `GroupLifecycleRestoreTest`, `OutboxExpiryTest` |
| **R3b, R3c** | — | Group media requester identity; "You (admin)" row; group attachment origin; sender-onion download; no discoverable announce for group media | **Resolved** (device-verified) | `GroupLifecycleRestoreTest` |
| **R7d–R7f** | — | Invitee bootstrap; reinstall recovery; update freshness gate | **Reviewed, no change** (see 2026-10-08 R3 entry) | — |
| **R6b, R6c** | — | Non-contact media replies (signed requests, derived reply onion); silent media drops; media flood | **Resolved** (Round B) | `MediaAccessRoundBTest` |
| **R4d** | — | Handshake budget | **Deferred** (owner, 2026-10-13) | — |
| **S2** | — | `ANNOUNCE_PEER` signature does not cover `onion_address` | **Resolved** (E2, 2026-10-13) — v2 signature covers the onion, v1 refused; only a key-derived onion is stored | `AnnouncePeerAndGroupQueryE2Test` |
| **F1** | — | Mesh feed: unseen posts marked seen by page index; batches replaced the list; pager load switched mode halfway; image/GIF/audio never marked seen; text posts de-duplicated by wording (2026-10-09) | **Resolved** (unit tests pass; device build working, 2026-10-09) | `FeedSwipeMarkingTest`, `MeshCanonicalKeyTest` |
| **IU1** | — | `IDENTITY_UPDATE` signed with `encodeForSigning` but verified only in pipe form (handler and `MeshPacketVerifier`); never accepted (2026-10-09) | **Resolved** (not yet device-verified) | `IdentityRoutingTest`, `MeshPacketVerifierTest` |
| **HS1** | — | Untargeted, legacy-signed `USER_HANDSHAKE` broadcast on every start (2026-10-09) | **Resolved** (unit tests pass; device build working, 2026-10-09) | `OnionAddressUpdateTest` |
| **G1** | — | `GROUP_QUERY` answered to anyone who knew a member-invite group's id; sender re-stamped per peer so members refused our queries (2026-10-13) | **Partial** (E2, E4) — members and the admin only, requester = sender; `GROUP_QUERY` keeps its sender. Still open: the answer is broadcast to all trusted contacts when the sender has no stored onion | `AnnouncePeerAndGroupQueryE2Test`, `IdentityLeftoversE4Test` |
| **RB1** | — | Release builds never read saved categories, genres, keywords, banned nodes or group bans (Gson `TypeToken` under R8 full mode) (2026-10-13) | **Resolved** (E2b) | `PreferencesRepositoryE2bTest`, `GroupChatParsingE2bTest`, `CodeHygieneArchitectureTest` |
| **SR1** | — | Search: random extra words instead of paging, "All" limited to this year, Invidious to the last month, videos sorted first, downvoted items and banned nodes shown (2026-10-13) | **Resolved** (E5s, E5s2; device-checked) — paged newest first; older downvotes not hidden; quality to improve later | `SearchPagingE5sTest`, `SearchPagingE5s2Test` |
