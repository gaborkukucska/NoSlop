# NoSlop — Legacy Android App Code Review (Round 2)

**Audience:** developing LLMs / contributors.
**Scope:** `app/` (legacy Android app), plus root build files, `docs/`, `scripts/`. **`mvp/` ignored.**
**Snapshot:** updated `NoSlop-main.zip` (files dated 2026-10-01), compared against the round-1 snapshot (2026-09-29). 116 main Kotlin files, 20 test files.
**Method:** static review only: a full diff of the two snapshots, a close read of every changed file, and grep/scan passes. **Nothing was built or run** (no Gradle, tests or emulator).

Tags: **[Verified]** = follows directly from code I read. **[Likely]** = strong inference, needs a runtime check. **[Unchecked]** = could not resolve from source.
Large UI files and most feed clients were only pattern-scanned, so "not listed" means "not looked at closely", not "clean".

---

## 1. What changed since round 1

29 files changed (27 code/test, 3 docs). Most of round 1's urgent list was acted on:

| Round-1 item | Status | Notes |
|---|---|---|
| **U1** media-ID path traversal | **Fixed (mostly)** | `MediaManager.isValidMediaId` and `isPathInDirectory` added and applied at all the sinks I found. See §3 for residual gaps. |
| **U2** committed proxy secret | **Partly fixed** | Default is now `""`; `ProxyAuth` skips signing when blank; stale gradle comment fixed. The old value is still public in git history, `scripts/test_yt.sh` and several docs, so it must be rotated server-side. Fallback behaviour is now inconsistent between clients (see N6). |
| **U3** verifier/handler signature drift | **Fixed** | `EDIT_POST` and `REACTION` now accept all formats in both places. `POST` verifier now prefers the length-prefixed format. Tests added. |
| **U4** LAN-Hub cleartext path | **Fixed** | `invokeHubApi` and `HubSyncWorker` now ask `NetworkSecurityPolicy.isCleartextTrafficPermitted(ip)` first, so the doomed request and warning are gone. |
| Dead code (§3.3) | **Partly done** | Removed: `MediaPendingPayload`, `cacheRelayedMedia`, template colours, `startupTimestampMs`, `GEO_LOCK_PATTERN`, `PEER_FAILURE_WINDOW_MS`, `firewallTtlMs`. `MAX_CONCURRENCY` is now used. Others remain (see §5). |
| Greek `el` listed but no file | **Fixed** | Removed from `WELL_KNOWN_LANGUAGES`. |

New feature work in this round: **friends-only privacy enforcement** across gossip, sync, and posting identity; **Tor-readiness gating** for sends; **cooldown/backoff rework**; DM dedup; DM-sync throttle; typing-indicator throttle; backup-prompt mnemonic entry. Several of these introduced the new issues below.

---

## 2. Overall opinion

The team is clearly responsive: the security findings were fixed quickly, with tests for the signing parity. The new friends-only work is the right idea and has two good unit tests.

The concern for this round is that the new privacy and reliability features were bolted on **in many places at once** (gossip firewall, forwarder, broadcaster, three sync handlers, repository) with copy-pasted conditions and magic strings. That creates drift risk, which was the theme of round 1. Two of the changes also weaken earlier hardening (cooldown, DM firewall exemption). I'd call the codebase **healthier on security basics, but with a growing correctness/regression surface** in the mesh layer.

---

## 3. Urgent issues

### N1. Unauthenticated DMs can re-label any contact as "burnable" — **High** — [Verified code path; [Unchecked] end-to-end exploit]

`GossipService.processIncoming` now lets any `MESSAGE` packet addressed to us bypass the trust firewall (`isDirectedMessageForUs`, new). `MESSAGE` was already exempt from rate-limiting (`isCriticalPacket`), and the envelope has no signature (`MeshPacketVerifier` returns `UNVERIFIABLE`; sender is unauthenticated on Tor inbound). In `DmPacketHandler.handleDirectMessage`, **before any decryption or authentication**:

```kotlin
if (myKeys.publicKeyB64 == burnableKeys?.publicKeyB64) {
    db.appSettingDao().insertSetting(AppSetting("contact_identity_${packet.senderId}", "burnable"))
}
```

So anyone who knows our Creator ID (public by design) can send a packet with `targetUserId = <our burnable key>` and `senderId = <any key, including a real friend's>`. The result:
- the victim friend is silently re-labelled `burnable`;
- the new friends-only logic then treats that friend as **not** a trusted direct peer, so they stop receiving friends-only posts and sync data;
- our replies to them switch to the burnable identity (an identity-leak/confusion risk in the other direction).

Further, when the sender has no stored X25519 key the handler calls `repo.sendConnectionRequest(...)` for **every** such packet, which is an amplification path (inbound spam causes outbound Tor connections).

**Fix:** (a) move the `contact_identity_*` write to *after* successful decryption; (b) narrow `isDirectedMessageForUs` (e.g. only let it through if the sender has a pending/known handshake, or rate-limit it per sender and globally); (c) put `MESSAGE` under at least a coarse rate limit; (d) rate-limit auto `sendConnectionRequest` per sender.

### N2. Failure tracking and backoff have been mostly disabled — **Medium/High (regression risk)** — [Verified]

Round-1's changelog said the 30-minute exponential backoff was added to stop "238 failed attempts to dead peers" from freezing Tor's SOCKS daemon. This round:
- backoff cap dropped 30 min → **3 min**;
- `recordSendFailure` is now only called when the packet is `ANNOUNCE_PEER`/`ANNOUNCE_DISCOVERABLE` (`MeshTransport.kt:290`), and `recordSendFailure/Success` calls were **removed** from the forward loop, broadcast loop, and both outbox workers;
- cooldown is only *enforced* in `MeshTransport` for announce packets (line 185).

Net effect: posts, comments, reactions and sync requests are broadcast to every trusted peer on every event with a 15 s connect timeout and no penalty for dead peers. The only `ANNOUNCE_PEER` builder I found is one site in `MeshSocialRepository` (L350), so the failure counters are rarely fed at all, and the remaining `isPeerInCooldown` checks (`MeshSocialRepository` L158/251/429, `GossipService` L909) will almost never trip. The intent (don't let user traffic get locked out) is reasonable, but the two goals conflict and this resolves it by dropping the storm protection.

**Fix:** keep user-traffic delivery, but track failures for *all* sends and use a **short, capped** backoff for user traffic (e.g. skip a peer for N seconds after K consecutive failures *unless* the packet is a DM or handshake). Add a test that a dead peer is not retried on every broadcast.

### N3. Friends-only detection relies on a `hops == 1` heuristic and DB lookups per call — **Medium** — [Verified code; [Likely] impact]

`GossipService.isFriendsOnlyPacket`:
- For engagement packets whose target post isn't in the local DB, it falls back to **`packet.hops == 1` ⇒ friends-only**. `hops` is a TTL counter, not a privacy flag. Any social packet that arrives with `hops == 1` for a post we haven't seen is treated as friends-only, so it is dropped if the sender is non-direct (including `DELETE_POST`/`DELETE_COMMENT`, which are otherwise firewall-exempt), and never forwarded.
- It is `suspend`, hits Room via `NoSlopDatabase.getDatabase(ctx)` (bypassing the repository layer), and is called up to **4 times per packet** (firewall, forward, hub-push, broadcast). `getTargetPostAuthor` repeats the same `when` block and the same lookups.
- A friends-only comment/reaction for an unknown post is considered public and will be forwarded.

**Fix:** carry an explicit `privacy`/`audience` field in the signed payloads of comments/reactions/edits (or resolve once and cache the result on the packet for the duration of `processIncoming`); remove the `hops == 1` heuristic; merge the two `when` blocks.

### N4. `isValidMediaId` residual gaps — **Low/Medium** — [Verified]

U1 is well fixed. Remaining nits:
- `Regex("^[A-Za-z0-9._-]+$")` is **compiled on every call**, and the validator runs per chunk packet (`handleMediaChunk`, `forwardRelayChunk`, `findLocalFile`, `getLocalFile`, …). Hoist it to a `private val`.
- A lone `"."` passes the validator (`..` is rejected, a single dot is not). `deleteMediaFiles` does `File(dir, id).takeIf { exists }.delete()` without `isPathInDirectory`, so `"."` could delete an empty media directory. Reject IDs that start with `.`.
- `exportToPublicDownloads` sanitises `fileName` with `replace("..", "")`; this is weak (e.g. `"...//"` patterns). Prefer `File(name).name` plus an allow-list.
- No unit tests for any of this (grep of `src/test` and `src/androidTest` finds no references to `isValidMediaId`).
- `GossipService.handleRelayRequest` looks for the file at `filesDir/media/<id>`, but `MediaManager` stores media under `<external files>/<Pictures|Movies|Music|Downloads>/NoSlop`. [Likely, unchanged from round 1] The relay "do we have it?" check can therefore never succeed for normally downloaded media. Use `MediaManager.getLocalFile`.

---

## 4. Other new findings

**N5. Sync-handler filtering is N+1 and copy-pasted six times.** `SyncPacketHandler` now repeats the same "friends-only visible to this peer" predicate in `handleSyncRequest` (posts, comments, reactions), `handleInventorySyncRequest` (posts, older own posts, comments, reactions), plus `MeshSocialRepository.requestInventorySync`. Each comment/reaction does `postDao.getPostById(...)` inside `filter {}` (5 call sites in the file); with a 365-day inventory window this is potentially thousands of single-row queries per sync request. Extract `fun canShare(post, peer): Boolean`, load the visible post-id set once, and filter comments/reactions with `in`. Replace the `"friends"` string literal (43 occurrences across the app) with a constant/enum. `handleInventorySyncRequest` now also declares `contactIdentity` twice (outer and inner scope; harmless but confusing).

**N6. Proxy fallback is inconsistent across clients.** With the blank default:
- `YouTubeInternalClient` skips the proxy and goes direct (good);
- `RedditApiClient` and `JamendoApiClient` still send the request to the proxy **unsigned** first (a wasted round trip that also reveals the query to the proxy), then fall back to direct.
Add `ProxyAuth.isConfigured` and use it in all three; better, one shared `fetchWithProxyFallback()` helper (this also removes three copies of the try/catch/fallback structure).

**N7. Tor network callback is never unregistered.** `TorService.startTor` registers `registerDefaultNetworkCallback(...)` (guarded by a boolean, so once per process) and captures `context`. [Likely] If that `context` is an Activity this leaks it; either way there's no `unregisterNetworkCallback` path. Use `applicationContext` and unregister in the stop path. Also, `onAvailable` calls `startTor(... forceRestart = false)` after `delay(1000)` without checking `bootstrapJob`/`STARTING`, so two quick network events can race (a partial guard was added in `confirmBootstrapThenPromote`).

**N8. Backup advisory dialog now collects the mnemonic as free text.** In `SettingsTab` the advisory export flow used to fetch the active mnemonic itself (`viewModel.getActiveMnemonic()`); it now asks the user to type it, and the main export dialog's auto-fill (`LaunchedEffect(isExporting)`) was removed. That is arguably safer (mnemonic not auto-filled), but:
- there's no validation of the typed words against the stored mnemonic, so a typo produces a backup encrypted with a **wrong** password with no warning;
- `advisoryLauncher`'s callback reads `advisoryMnemonicInput` from a lambda captured at composition, so check it isn't stale (it's state, so probably fine);
- two new strings ("Choose File Location", "Enter Word Cloud (Mnemonic)", "12 recovery words...") need translations (see N9).

**N9. Localisation got slightly worse.** Distinct `"…".tr` literals not present in `content_en.json`: **58** (was 55). `content_en.json` is still 779 keys vs 790 in other languages. README still says "21+ languages" while 21 files exist (English + 20); correct that or add more. Add a CI script that diffs `.tr` literals against `content_en.json` and every language against English.

**N10. Smaller items**
- `GossipService`: a stray mis-indented comment on the `// 4. Firewall` line, and the `getTargetPostAuthor`/`isFriendsOnlyPacket` helpers are placed *between* the failure-tracking comment and its fields (`// Track persistent send failures…` now sits above `isFriendsOnlyPacket`). Tidy while consolidating.
- `MeshTransport.send` computes `isHandshake` twice (L153 and L230) and now has an `awaitReady(60 s)` inside the send path for handshakes/DMs. This holds a semaphore slot for up to 60 s per packet during bootstrap; consider queueing to the outbox instead.
- `DmPacketHandler` dedup path calls `recordSendSuccess(<senderId's onion>)` based on an unauthenticated `senderId`, so spoofed duplicates can reset a victim's cooldown. Low impact, but easy to drop.
- `SettingsRepositoryTest` now has `MediaSettings(enabled = false, maxFileSizeMB = 50,)` (trailing comma after the removed argument). It compiles but is leftover.
- `ChatThreadScreen`: the typing throttle looks right; `typingStopJob` cancels on every keystroke and the 6 s resend window versus the 4 s stop timer means a long continuous typing session sends `onTyping(true)` every 6 s. Fine; just confirm that matches the receiver's TTL.

---

## 5. Carry-over findings from round 1 (still open)

Confirmed unchanged unless noted:

- **God-classes:** `NoSlopViewModel` (3.2k lines), `NoSlopRepository` (2.2k), `VideoPlayer` (2.1k), `SettingsTab` (2.0k), `MeshSocialRepository` (1.8k), `UnifiedFeedTab` (2.5k). The new privacy logic made `GossipService` and `SyncPacketHandler` bigger too.
- **Legacy signing formats** are still accepted everywhere, now with no counter and no sunset marker. Parity now holds, so the next step is the single canonical module (C1) and a "legacy matched" counter.
- **Remaining dead/unused symbols** (token scan): `MnemonicGenerator.deriveSeedB64`, `Logger.getAllLogsText`, `NoSlopViewModel.triggerBackupPrompt`, `NoSlopViewModel.savedItems / isPrioritySource / sessionStartTimeMs`, `UnifiedFeedTab.firstPreloadDelayMs`, `HttpClientProvider.activeMediaClient / repRsv`, `JamendoApiClient.CLIENT_ID`, `CryptoService.rawXPub`, `Entities.iconUrl`, `Packets.hashtags`, `GossipService.establishedAt`. Confirm with compiler warnings, then delete.
- **Jamendo ID rotation pool** (5 hardcoded IDs, one already suspended) and the stale "namesearch" comment.
- **InnerTube API key** hardcoded in `YouTubeInternalClient.kt:26` (public web key; centralise it).
- **Duplicate YouTube-ID extractors** (`VideoPlayer.extractYouTubeId` vs `YouTubeInternalClient.extractVideoId`).
- **OkHttp clients built outside `HttpClientProvider`** (`InvidiousApiClient`, `TorService`).
- **Hub status stored as a display string** (`"Active at <ip>"` / `"Active (Legacy Connection)"`) and parsed with `substringAfter` in three places (`NoSlopRepository.invokeHubApi`, `HubSyncWorker`, others). The new `canCleartextLan` snippet was copy-pasted into two of them; extract `HubEndpoint.parse(status)`.
- **`!!` count** is 86 (TorService 10, AvatarCropper 13, …); ~20 empty `catch` blocks; `runBlocking` in `BackupManager`; `Thread.sleep(200)` in `TorService`.
- **`MediaManager` 2 s polling loop** runs forever; **`MediaProxyService` has `Accept-Ranges: none`**.
- **Notification small icon** is still `android.R.drawable.ic_dialog_info` ("for now").
- **Build:** inline dependency versions (Media3 1.3.1 ×7, Gson, JNA, Lazysodium, JSch fork…), `security-crypto 1.1.0-alpha06` (deprecated upstream), static "Build: Passing" badge, x86/x86_64 ABIs in release, redundant `defaultConfig.applicationId`.
- **Repo clutter:** `scripts/test_*` experiments (and `test_yt.sh` still contains `NoSlopRocks2026`), empty `_workspace/` dirs, 21 files in `docs/archived/`, `docs/PROJECT_STATUS.md` ~290 KB append-only.
- **Docs now stale again:** `PROJECT_STATUS.md` still claims full parity/audit coverage; check it reflects the friends-only and cooldown changes (the diff touched `PROJECT_STATUS`, `TECHNICAL_REFERENCE` and `WIRE_PROTOCOL_REFERENCE`, which I did not re-read line by line).

---

## 6. Tests

- Added: friends-only firewall test (temp/creator/burnable blocked, direct friend allowed), friends-only broadcast-target test, `EDIT_POST`/`REACTION` dual-mode verifier tests. Good.
- **Still missing:** `isValidMediaId` / path containment; `isFriendsOnlyPacket` for engagement packets (comments/reactions/deletes, the `hops == 1` branch); sync filtering (`SyncPacketHandler` friends-only paths); DM firewall bypass and the N1 scenario; cooldown/backoff behaviour after the rework; `ProxyAuth` with a blank secret; Tor-ready gating in `MeshTransport`.
- Highest-value new tests: (1) a spoofed `MESSAGE` to the burnable key must **not** write `contact_identity_*`; (2) a table-driven test that every legacy/new signing format verifies identically in the verifier **and** its handler; (3) friends-only sync returns nothing to a temporary/burnable peer.

---

## 7. Consolidation opportunities (updated priority)

1. **One `PacketAudience`/privacy resolver** (replaces N3 + parts of N5): `resolveAudience(packet): Audience` computed once, cached on the packet, used by firewall, forward, hub push, broadcast and sync; `fun canShare(post, peer)` shared by all sync paths.
2. **One canonical signing module** (C1 from round 1) with a legacy-match counter and per-format sunset markers.
3. **Central peer-delivery policy** (replaces the scattered `recordSendSuccess/Failure` and `isPeerInCooldown` calls): one `PeerHealth` class with explicit rules per packet class (DM / handshake / user content / background / media), so N2 is a one-place decision.
4. **Shared proxy-fallback helper** for Reddit, Jamendo and YouTube search (N6) + `ProxyAuth.isConfigured`.
5. **`HubEndpoint` value type** instead of parsing the status string in 3 places.
6. God-class splits (round-1 C3), then API-client interface (C6) and tunables file (C7).

---

## 8. Suggested next steps

**Now**
1. **N1**: move the `contact_identity_*` write after decryption, restrict/rate-limit the DM firewall exemption, rate-limit auto connection requests; add the spoof test.
2. **N2**: re-introduce failure tracking for all sends with a short capped backoff for user traffic; test it.
3. **U2 follow-through**: rotate the proxy secret server-side; scrub `scripts/test_yt.sh` and docs; make Reddit/Jamendo honour a blank secret (N6).
4. **N4**: hoist the regex, reject leading `.`, fix the relay lookup directory, add tests.

**Next**
5. **N3 + N5**: audience resolver, remove the `hops == 1` heuristic, de-duplicate and batch the sync filters.
6. **N7**: `applicationContext` + unregister the network callback; guard against concurrent `startTor`.
7. **N8**: validate the typed mnemonic against the stored one (or at least confirm-twice) before exporting.
8. Localisation CI check; sync `content_en.json`; update README language count.
9. Round-1 build/dependency items (version catalog, Media3 bump, `security-crypto` replacement, real CI badge, minified-release smoke test).

**Later**
10. God-class splits, `FeedSource` interface, tunables file, `ARCHITECTURE_CURRENT.md`, `DEPRECATIONS.md`, docs/archive tidy.

---

## 9. Notes for the next LLM

- **Friends-only is now a cross-cutting rule.** If you add a packet type that references a post, update `isFriendsOnlyPacket`/`getTargetPostAuthor` and the sync filters in the same change, until the audience resolver exists.
- **Never trust `packet.senderId` for `MESSAGE`**: it is unauthenticated. Don't write per-sender state before the AEAD decrypt succeeds.
- **Don't re-add `recordSendFailure` calls piecemeal.** Change the delivery policy in one place (see §7.3).
- **Signing:** edits to any `Signed(...)` payload must be made in the handler, `SyncPacketHandler`, and `MeshPacketVerifier` together; extend `MeshPacketVerifierTest`.
- **Never build a `File` from peer-supplied text** without `MediaManager.isValidMediaId` + `isPathInDirectory`.
- **Don't trust status docs over code**; verify against source.
- **Before committing:** `./gradlew testGithubDebugUnitTest` (or the `play` flavour), plus `androidTest` migration tests if Room changed. (Inferred from flavour names; I could not run them.)

---

*Static review of the 2026-10-01 snapshot. Line numbers refer to that snapshot and may shift.*
