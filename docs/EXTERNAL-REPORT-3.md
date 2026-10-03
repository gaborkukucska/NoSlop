# NoSlop — Legacy Android App Code Review (Round 3)

**Audience:** developing LLMs / contributors.
**Scope:** `app/` (legacy Android app), plus root build files, `docs/`, `scripts/`. **`mvp/` ignored.**
**Snapshot:** latest `NoSlop-main.zip` (files dated 2026-10-03), diffed against the round-2 snapshot (2026-10-01). 116 main Kotlin files, 20 test files.
**Method:** static review only: full diff of the two snapshots, a close read of every changed file, plus grep/token-count scans. **Nothing was built or run** (no Gradle, tests or emulator). The new tests in particular are unexecuted.

Tags: **[Verified]** = follows directly from code I read. **[Likely]** = strong inference, needs a runtime check. **[Unchecked]** = could not resolve from source.
Large UI files and most feed clients were only pattern-scanned, so "not listed" means "not looked at closely", not "clean".

### Corrections to my round-2 report
- I listed "duplicate YouTube-ID extractors" as open. `VideoPlayer.extractYouTubeId` was **already removed in the round-2 snapshot**; only `YouTubeInternalClient.extractVideoId` remains. That item is closed.
- The "`!!` count is 86" figure was a *line* count. By occurrence there are **100** `!!` in `app/src/main`, and the number is identical in rounds 2 and 3. No change; I should not have implied one.

---

## 1. What changed since round 2

15 files changed (10 code, 2 test, 3 docs). Nearly everything in round 2's "Now" list was addressed:

| Round-2 item | Status | Notes |
|---|---|---|
| **N1** spoofed DM relabels contact "burnable" | **Fixed (core)** | The `contact_identity_*` write now happens only *after* successful decryption. Auto connection requests are rate-limited (1/60 s per sender) and skipped when there's no known onion. Untrusted directed DMs get a 10/60 s limiter with a test. Residual issues in N11 below. |
| **N2** failure tracking disabled | **Fixed** | `recordSendFailure` now fires for all sends except typing/read-receipts. Cooldown is enforced for everything except handshakes and high-priority DMs. The scattered success/failure calls were *not* re-added piecemeal. |
| **N3** `hops == 1` friends-only heuristic | **Fixed (partly)** | Heuristic removed. Logic merged into one `resolvePostContext()` returning `(isFriendsOnly, targetPostAuthor)`; the duplicate `when` block is gone. The firewall and broadcast paths use it once each. See N12 for what remains. |
| **N4** media-ID nits | **Fixed** | Regex hoisted; leading `.` rejected (`^[A-Za-z0-9_-][A-Za-z0-9._-]{0,127}$`); relay lookup now uses `MediaManager.getLocalFile` (fixes the `filesDir/media` directory mismatch); `exportToPublicDownloads` now uses `File(name).name` plus an allow-list. A unit test for `isValidMediaId` was added. |
| **N5** N+1 sync filters | **Mostly fixed** | New `canSharePost()` plus a per-request `postCache`. `contactIdentity` is no longer declared twice. One copy of the predicate remains (N12). |
| **N6** proxy fallback inconsistency | **Mostly fixed** | New `ProxyAuth.isConfigured`; Reddit and Jamendo skip the proxy when it's blank and no longer send unsigned requests. YouTube still tests `PROXY_SECRET.isNotBlank()` directly. |
| **N7** Tor network callback leak | **Fixed** | Uses `applicationContext`, stores the callback, unregisters in the stop path, and re-checks state/`bootstrapJob` before restarting. |
| **N8** unvalidated backup mnemonic | **Fixed (advisory dialog only)** | Typed mnemonic is compared with the active one (case-insensitive) before export. See N13. |
| Small items | **Fixed** | `SettingsRepositoryTest` trailing comma; `isHandshake` double declaration; the misplaced "Track persistent send failures" comment; DM-dedup path no longer calls `recordSendSuccess` on an unauthenticated sender. |

**Not addressed this round** (and not expected to be): the proxy secret rotation, localisation gaps, build/dependency hygiene, god-class splits. See §5.

---

## 2. Overall opinion

This was a strong round. Every high-severity item from round 2 got a code fix, most with tests (`isValidMediaId`, untrusted-DM rate limit, friends-only broadcast), and the fixes were mostly the *right shape*: a single `resolvePostContext`, a single `canSharePost`, moving the state write after authentication, and not re-scattering failure bookkeeping.

The remaining concerns are second-order: the new DM rate limiter shares a bucket with an unrelated limiter, a little dead code was left behind by the consolidation, and the privacy model still depends on `hops == 1` being set by the *originator* (a convention rather than an enforced rule). Nothing I found this round is high severity. The biggest outstanding action is not in code: **the proxy secret still needs rotating server-side**.

---

## 3. Findings (new this round)

### N11. Untrusted-DM limiter shares a bucket with the announcement limiter — **Low/Medium** — [Verified]

In `GossipService.processIncoming`, the new untrusted-DM check (`>= 10` per 60 s) stores timestamps in `announcementRateLimits`, the **same per-sender list** used by the announcement/follow/identity-update limiter (`>= 5` per 60 s). Consequences:
- 5 DMs from an untrusted sender make that sender's next announcement/follow/identity packet get dropped (list size already 5).
- 5 announcements plus 5 DMs exhaust the DM budget early (size reaches 10).
This is probably benign for real traffic but surprising, and it makes the test for one limiter depend on the other's state. Give DMs their own map (`dmRateLimits`) and add it to `cleanupRateLimitsAndFirewall` and the reset in `initialize`.

Also note the limiter is keyed on the **unauthenticated** `senderId`. An attacker who rotates forged sender IDs sidesteps the per-sender cap; each such packet then costs a dedup read, a peer lookup and an early `return false` (no decrypt because no enc key), so the cost is small, but a coarse **global** cap on untrusted directed DMs would close it. [Likely, low impact]

### N12. Privacy plumbing: leftovers and one remaining duplicate — **Low/Medium** — [Verified]

- `GossipService.getTargetPostAuthor()` (private) now has **no callers** (dead since the consolidation). `isFriendsOnlyPacket()` is a thin wrapper still called at two sites (forward, hub-push), each of which re-resolves the context (a DB lookup per call). Pass the `PostContext` through, or compute it once at the top of `processIncoming`, then delete both wrappers.
- `resolvePostContext` still reads Room via `NoSlopDatabase.getDatabase(ctx)` instead of going through the repository/DAOs injected into `GossipService`.
- `MeshSocialRepository.requestInventorySync` (≈L858-875) still carries its own copy of the "friends-only visible to this peer" predicate and the "older own posts" variant. `canSharePost` is `private` in `SyncPacketHandler`, so it can't be reused. Move it to a shared place (e.g. `mesh/PostVisibility.kt`) and use it from both.
- The privacy mode is still encoded as **`hops = if (privacy == "friends") 1 else 6` repeated ~12 times** in `MeshSocialRepository` (plus the magic strings `"friends"`, `6`). With the `hops == 1` receive-side heuristic gone, a friends-only comment/reaction for a post the receiver doesn't have yet is treated as public by the receiver; it's only kept private because the *sender* originates it with `hops = 1` and the forwarder refuses to forward at ≤ 1. That's workable, but it's a convention, not an invariant. Introduce `enum Audience` / constants (`HOPS_FRIENDS_ONLY`, `DEFAULT_MAX_HOPS`) and a single `fun hopsFor(privacy)`.
- `getOrPut` on a `MutableMap<String, MeshPost?>` (`postCache`) recomputes for *missing* posts every time, because a cached `null` is indistinguishable from "absent". Use `containsKey`, or cache a sentinel. Minor.

### N13. Backup export: validation exists in one dialog only — **Low** — [Verified]

The advisory export path now validates the typed mnemonic, but it does so **after** the user has already picked a destination file (the check lives inside the `CreateDocument` result callback), so a typo costs the user a wasted file-picker step and leaves the empty/aborted destination to them. The primary "Export Identity Backup" dialog (`SettingsTab` ≈L1462) has no equivalent comparison, and since the round-2 change it no longer pre-fills the mnemonic either. [Unchecked] whether `exportBackupToUri` itself validates or confirms the mnemonic. Suggest a shared `validateMnemonicForExport()` called *before* launching the picker, in both dialogs. The mismatch toast string ("Mnemonic does not match your active Word Cloud!") is new and not in `content_en.json`.

### N14. Smaller items — [Verified]

- `ProxyAuth.isConfigured` is defined as "secret is not blank". A user who sets a **custom proxy URL without a secret** (via `setCustomProxy`) will now have the proxy silently ignored by Reddit/Jamendo. Probably intended, but document it, or treat "custom URL set" as configured.
- `YouTubeInternalClient` still checks `ProxyAuth.PROXY_SECRET.isNotBlank()` directly; use `isConfigured` for consistency.
- With cooldown now applying to media packets as well (only handshakes and high-priority DMs bypass), three consecutive chunk failures to a flaky peer pause further chunk requests to it for up to 3 minutes. [Likely] This may stall downloads from the only seeder; consider letting `isMediaPacket` bypass cooldown or shortening it for that class.
- Backoff is capped at 3 minutes (down from 30 in round 1). That's a deliberate trade-off, but make sure the changelog and `TECHNICAL_REFERENCE.md` say so, since earlier docs describe a 30-minute cap.
- `MeshTransport` awaits Tor `READY` for up to 60 s *inside* `sendPacket` for handshakes and DMs while holding a semaphore slot (unchanged from round 2). Consider failing fast to the outbox.

---

## 4. Test status

Added or updated this round: untrusted-DM rate limit; `isValidMediaId` (valid IDs, `.`, `..`, leading dot, traversal, slash, backslash, empty); a more robust friends-only broadcast test (polls instead of a fixed `delay(100)`). Combined with round 2's friends-only firewall/broadcast tests and the dual-mode verifier tests, the mesh-security surface now has useful coverage. **None of it was run by me.**

Still missing, in priority order:
1. DM firewall behaviour for a **trusted** sender and for the shared-bucket interaction (N11).
2. `resolvePostContext` for engagement packets (comment/reaction/vote/delete) with a known and an unknown target post.
3. `SyncPacketHandler` friends-only filtering (requests, inventory and response sides), including the `postCache` behaviour.
4. `MediaManager.getLocalFile` for each media directory, and `isPathInDirectory` with symlinks.
5. Cooldown behaviour for each packet class after the N2 rework (dead peer not retried on every broadcast; DMs/handshakes still allowed).
6. `ProxyAuth.isConfigured` with and without a custom proxy.
7. Backup mnemonic validation (both dialogs).

---

## 5. Carry-over findings (still open, unchanged unless noted)

**Security / config**
- **Proxy secret rotation.** The literal `NoSlopRocks2026` is no longer a build default, but it still appears in `scripts/test_yt.sh`, `docs/PROJECT_STATUS.md` and four files under `docs/archived/`, and remains in git history. It must be rotated on the Cloudflare Worker and scrubbed from the tree. Because the value is public, treat the HMAC as abuse-throttling only and add server-side rate limits.
- Public InnerTube API key hardcoded in `YouTubeInternalClient.kt` (a public web key; centralise it).
- Jamendo: a five-ID rotation pool (`CLIENT_ID_CANDIDATES`, includes the already-suspended `709fa152`); `CLIENT_ID` (L34) is unused; the "namesearch" comment is stale.
- Legacy signature formats (pipe-delimited) are still accepted everywhere with no counter and no sunset marker. Parity now holds, so the next step is a single canonical signing module with a "legacy matched" counter.
- `encodeForSigning` uses UTF-16 `String.length`; document this in `WIRE_PROTOCOL_REFERENCE.md` and add cross-language test vectors before any non-Kotlin client relies on it.

**Code health**
- God-classes: `NoSlopViewModel` (~3.2k lines), `NoSlopRepository` (~2.2k), `VideoPlayer` (~2.1k), `SettingsTab` (~2.0k), `MeshSocialRepository` (~1.8k), `UnifiedFeedTab` (~2.5k). `GossipService` and `SyncPacketHandler` have grown with the privacy work.
- **Unused symbols** (token scan, 15 remain; confirm with compiler warnings): `CryptoService.rawXPub`, `MnemonicGenerator.deriveSeedB64`, `Entities.iconUrl`, `NoSlopRepository.trustedPeers` (L641), `Logger.getAllLogsText`, `JamendoApiClient.CLIENT_ID`, `GossipService.RelayState.establishedAt`, `Packets.hashtags`, `HttpClientProvider.activeMediaClient` and `repRsv`, `NoSlopViewModel.sessionStartTimeMs / savedItems / isPrioritySource / triggerBackupPrompt`, `UnifiedFeedTab.firstPreloadDelayMs`. (`getTargetPostAuthor` joins this list per N12.)
- `OkHttpClient.Builder()` is still used outside `HttpClientProvider` in `InvidiousApiClient.kt:32` and `TorService.kt:711,734` (the latter may be intentional for bootstrap probes).
- Hub status is still stored and parsed as a display string (`"Active at <ip>"` / `"Active (Legacy Connection)"`) in `NoSlopRepository.invokeHubApi` and `HubSyncWorker`, with the new `canCleartextLan` snippet copy-pasted into both. Extract a `HubEndpoint.parse(status)` value type.
- ~100 `!!` occurrences (TorService ~10, AvatarCropper ~13, …), ~20 empty `catch` blocks, `runBlocking` in `BackupManager`, `Thread.sleep(200)` in `TorService`.
- `MediaManager` still runs a 2 s polling loop for the app's lifetime; `MediaProxyService` still replies `Accept-Ranges: none`.
- Foreground-service notification icon is still `android.R.drawable.ic_dialog_info` ("for now").
- Tor/video watchdog tunables are still scattered; collect them into one config object.

**Localisation** (unchanged)
- 58 distinct `"…".tr` literals are not in `content_en.json` (was 55 in round 1, 58 since round 2). `content_en.json` has 779 keys versus 790 in the other language files. README still says "21+ languages" while 21 files exist (English + 20). Add a CI script: extract `.tr` literals → diff against `content_en.json` → diff each language against English.

**Build / repo hygiene** (unchanged)
- Dependency versions are still partly inline (Media3 1.3.1 ×7, Gson, JNA, Lazysodium, JSch, etc.) rather than in `libs.versions.toml`; `security-crypto 1.1.0-alpha06` is an alpha and deprecated upstream; the README "Build: Passing" badge is static; x86/x86_64 ABIs ship in release; `defaultConfig.applicationId` is redundant with the flavours; no minified-release (R8 + Gson) smoke test.
- `scripts/test_*` experiments, empty `_workspace/` directories, 21 files under `docs/archived/`, and a ~290 KB append-only `docs/PROJECT_STATUS.md`.
- The three docs touched this round (`PROJECT_STATUS`, `TECHNICAL_REFERENCE`, `WIRE_PROTOCOL_REFERENCE`) were not re-read line by line; verify they describe the new cooldown policy and friends-only rules.

---

## 6. Consolidation opportunities (updated)

1. **`PostVisibility` module** (replaces N12 leftovers): `resolvePostContext`, `canSharePost`, `Audience` enum, `hopsFor(privacy)`, and constants for `"friends"` / TTLs. Used by `GossipService`, `SyncPacketHandler`, `MeshSocialRepository`, and the repository send paths.
2. **Canonical signing module** with a legacy counter and per-format sunset markers (still the most valuable cleanup for the mesh layer).
3. **`PeerHealth` policy class** to own cooldown rules per packet class. The current rules are now correct but live across `GossipService`, `MeshTransport` and `MeshSocialRepository`.
4. **Shared proxy-fallback helper** for Reddit, Jamendo and YouTube search (`isConfigured` → proxy → direct), removing three near-identical try/close/fallback blocks.
5. **`HubEndpoint` value type** instead of string parsing.
6. Then the larger items: god-class splits, a `FeedSource` interface for the ~15 feed clients, a tunables file.

---

## 7. Suggested next steps

**Now**
1. **Rotate the proxy secret** on the Worker; scrub `scripts/test_yt.sh` and the docs; add server-side rate limiting.
2. **N11**: give untrusted-DM limiting its own map; consider a global cap.
3. **N12**: delete `getTargetPostAuthor`; pass `PostContext` through instead of re-resolving; route `MeshSocialRepository.requestInventorySync` through the shared predicate.
4. **Run the test suite** (`./gradlew testGithubDebugUnitTest`, or the `play` flavour). Several new tests (rate limit, broadcast, media-ID) have not been executed by me.

**Next**
5. **N13**: validate the mnemonic *before* launching the file picker, in both export dialogs.
6. **N14**: decide whether media packets should bypass cooldown; use `isConfigured` in YouTube; document the 3-minute backoff.
7. Introduce `Audience` / hops constants (N12) so the privacy rule is explicit instead of conventional.
8. Add the missing tests listed in §4.
9. Localisation CI check; sync `content_en.json`; fix the README language count.
10. Build hygiene: version catalog, Media3 bump, `security-crypto` replacement plan, real CI badge, minified-release smoke test.

**Later**
11. Canonical signing module with legacy sunset; `PeerHealth`; god-class splits; `FeedSource` interface; tunables file; `ARCHITECTURE_CURRENT.md` and `DEPRECATIONS.md`; tidy `docs/archived/` and `scripts/`.

---

## 8. Notes for the next LLM

- **Friends-only is cross-cutting.** A new post-referencing packet type must be added to `resolvePostContext` and to the sync filters in the same change, until the `PostVisibility` module exists.
- **`MESSAGE` envelopes are unauthenticated.** Don't write per-sender state before the AEAD decrypt succeeds (this is the lesson of N1).
- **Don't scatter `recordSendFailure`/`recordSendSuccess`.** Failure tracking now lives in `MeshTransport.sendPacket`; change delivery policy there, or in a future `PeerHealth` class.
- **Signing:** an edit to any `Signed(...)` payload must be made in the handler, `SyncPacketHandler` and `MeshPacketVerifier` together, with `MeshPacketVerifierTest` updated.
- **Never build a `File` from peer-supplied text** without `MediaManager.isValidMediaId` + `isPathInDirectory`.
- **Prefer `MediaManager.getLocalFile`** over hand-built media paths; the relay lookup bug came from a hand-built path.
- **Don't trust status docs over code**, and verify against source. (My own round-2 report had one stale item; treat reports the same way.)
- **Before committing:** run the unit tests (flavour name inferred from the build file; I could not run them) and `androidTest` migration tests if Room changed.

---

*Static review of the 2026-10-03 snapshot. Line numbers refer to that snapshot and may shift.*
