# NoSlop — Legacy Android App Code Review

**Audience:** developing LLMs / contributors picking up work on the repo.
**Scope:** `app/` (the legacy Android app) plus root build files, `docs/`, `scripts/`. **`mvp/` was deliberately ignored.**
**Snapshot:** `NoSlop-main.zip`, v0.6.5-alpha (versionCode 65). 116 main Kotlin files (~52k lines incl. tests), 20 test files (~119 `@Test`s), 21 language JSONs.
**Method:** static reading and grep/token-count scans only. **Nothing was built or run** (no Gradle, no emulator, no tests). Every item below is tagged:

- **[Verified]**: I read the code and the claim follows directly from it.
- **[Likely]**: strong inference from the code, but needs a runtime check or a look at code I did not read.
- **[Unchecked]**: a question I could not resolve from the source.

I did not read every one of the 116 files line by line. The large UI files (`UnifiedFeedTab`, `SettingsTab`, `OnboardingScreen`, `ContentPreferencesScreen`, `FeedCard`) and most feed API clients were only pattern-scanned. Treat "not listed" as "not looked at closely", not as "clean".

---

## 1. Overall opinion

This is an ambitious and, in the places I read closely, carefully engineered codebase. Signing, encryption, Tor isolation, backup handling and the network-security config all show evidence of real threat modelling (e.g. `NOSLOP_TLS_TRUST_V1`, `NOSLOP_VERIFY_BEFORE_FORWARD_V1`, the session token on the local media proxy, host-key pinning for SSH, AAD-bound group ciphertext). The comments explain *why*, which is unusually helpful.

The main risks are not carelessness but **accretion**:

1. **Backwards-compat layers have piled up without an exit plan.** Multiple signature formats (`|`-pipe, length-prefixed, "with/without avatar") are accepted side by side, copied into three places, and the copies have already drifted (see §3.2).
2. **A few god-classes** (`NoSlopViewModel` 3.2k lines / 142 funs, `NoSlopRepository` 2.2k lines / ~180 funs, `VideoPlayer` 2.1k, `SettingsTab` 2.0k, `MeshSocialRepository` 1.8k, `UnifiedFeedTab` 2.5k) concentrate most of the change risk.
3. **Docs are heavy and partly stale.** `docs/PROJECT_STATUS.md` is ~290 KB of append-only changelog; `docs/archived/` holds 21 more files. Useful as history, poor as a source of truth.
4. **Two genuine security issues** (§2) that I would fix before any wider release.

Little of it is "mock" code in the sense of fake data pretending to be real. The mock/placeholder count is very low (see §5). The app looks real, not scaffolded.

---

## 2. Urgent issues (fix first)

### U1. Unvalidated media IDs used as file names (path traversal) — **High** — [Verified code path; [Unchecked] reachability from an untrusted peer]

`mediaId` values that arrive from remote peers are joined onto filesystem paths with **no sanitisation anywhere in `mesh/`** (a grep for `..`, `/`, or an ID regex finds nothing in `mesh/` or `data/` except in `BackupManager`).

Serving side (a peer asks *us* for a file):
- `MediaManager.handleMediaRequest` → `findLocalFile(repo, payload.mediaId)` → `File(File(baseDir, "NoSlop"), mediaId)` (`MediaManager.kt` ~L905).
- `baseDir` is `getExternalFilesDir(...) ?: repo.context.filesDir`. On the `filesDir` fallback, `mediaId = "../../databases/…"` resolves into the app's private data directory. The result is then chunked back to the requester.
- `GossipService.kt` ~L658 does the same with `File(mediaDir, mediaId)`.

Writing side (a peer sends us metadata):
- `ActiveDownload.partFile = File(mediaDir, "${metadata.id}.part")` (`MediaManager.kt` L120) and `File(mediaDir, dl.metadata.id)` in `finishDownload` (~L724). `metadata.id` comes straight from a peer's `MediaMetadata`. A crafted ID can write or rename outside the media directory (arbitrary file write inside the app sandbox, e.g. over the Room DB or prefs).

`MediaProxyService` is token-gated and is not the problem. The problem is the mesh path.

**Fix:** one shared validator, e.g. `^[A-Za-z0-9._-]{1,128}$`, and reject `.`/`..`; enforce it at packet-parse time (`MediaPacketHandler`) **and** defensively inside every `File(dir, id)` site. After building the `File`, also check `file.canonicalPath.startsWith(dir.canonicalPath + File.separator)`. Add unit tests with `../` IDs. Also check whether `MEDIA_REQUEST` is allowed from untrusted senders in the `GossipService` firewall; if so, severity is higher.

### U2. Shared proxy secret committed with a literal default — **Medium/High** — [Verified]

`app/build.gradle.kts` L49: `NOSLOP_PROXY_SECRET` defaults to the literal `"NoSlopRocks2026"`. The value is also in `scripts/test_yt.sh` and repeatedly in `docs/`. Anything in `BuildConfig` ships inside an open-source APK, so this cannot be a secret in any case; the project's own archived reports already note "Static secret leak NOT yet eliminated". Consequences: anyone can drive traffic through the Cloudflare Worker (`yt-proxy.megadreamland.workers.dev`) and burn its quota.

**Fix:** treat the HMAC as abuse-throttling, not authentication. Rate-limit and quota server-side, rotate the current value (it is public), and remove the literal default so a clean clone fails loudly or runs in "direct / no proxy" mode. Also fix the stale comment above `PROXY_SEND_LEGACY_SECRET` ("Keep it true until…") — the default is already `false`.

### U3. Signature-verification drift between `MeshPacketVerifier` and the handlers — **Medium** — [Verified]

`MeshPacketVerifier` runs *before forwarding* and drops on `INVALID`. Its own header says the handlers are the authority and the two must stay in step. They are not fully in step:

- **`EDIT_POST`**: `PostPacketHandler.handleEditPost` accepts length-prefixed **or** legacy pipe (`postId|authorId|content|timestamp`). The verifier's `describe()` only builds the length-prefixed form. A legacy-signed edit is therefore **dropped pre-forward** even though the local handler would accept it.
- **`REACTION`**: `ReactionPacketHandler` (L26-31) accepts encoded **or** pipe. The verifier accepts only the encoded form. Same effect.
- The verifier's `POST` branch tries pipe formats first and falls back to length-prefixed. That is the wrong preference order (prefer the safe format, and log when the legacy one is used).
- `describe()` performs up to 3-4 `Ed25519.verify` calls to *choose* a payload, then `verify()` does another. The handler then repeats the whole thing. A single POST can cost ~8 signature verifications per hop.

Whether legacy-signed edits/reactions still exist on the network is the real question; if they do, users on older versions silently lose reach.

**Fix:** see C1 (one canonical verifier with an explicit, time-boxed legacy list).

### U4. LAN Hub fast-path is dead code and fails noisily — **Medium** — [Verified]

`NoSlopRepository.invokeHubApi()` (L154+) tries `http://<lan-ip>:8080/api/invoke` first. `network_security_config.xml` now forbids cleartext except `.onion`/`localhost`/`127.0.0.1`, so that request always throws and falls back to Tor. Per the config comment this is a known trade-off, but the code still (a) attempts it every call, (b) logs a WARN each time, and (c) adds up to the 2-second connect timeout. The Hub API requests also carry no visible per-request authentication (the body is `{cmd,args}`) — [Unchecked] whether the Hub side authenticates.

**Fix:** either remove the LAN branch or gate it behind an explicit "allow LAN cleartext" setting plus a scoped network-security domain entry. Confirm the Hub authenticates callers.

---

## 3. General findings

### 3.1 Security / privacy notes (lower urgency)

- **Public InnerTube API key** hardcoded in `YouTubeInternalClient.kt:26` (`AIzaSy…`). This is the well-known public web client key and not a credential, but it will show up in secret scanners and can be rotated by Google at any time. Move it to a named constant with a comment, ideally in one place.
- **Jamendo client IDs**: a hardcoded rotation pool of five IDs (`JamendoApiClient.kt:19-25`), including one that was already suspended (`709fa152`). Rotating through other people's / project IDs on error code 11 is fragile and may breach Jamendo's terms. Prefer a single project-owned ID plus the user-supplied key that already exists (`userClientId` / `ApiKeyRepository`). The stale comment at L52 still says "namesearch" although the code uses `search=`.
- **Legacy pipe-delimited signing** remains accepted indefinitely. Concatenating user-controlled free text with `|` is a canonicalisation weakness (the repo's own "Finding #4"). The length-prefixed encoder exists, but while the legacy path is still accepted the fix is only partial.
- **`encodeForSigning` uses `String.length`** (UTF-16 code units), not UTF-8 byte length. Fine inside one Kotlin implementation, but a different-language client (the MVP is KMP; a Hub or future iOS client) must reproduce the same counting rule exactly. Document it in `WIRE_PROTOCOL_REFERENCE.md` and add cross-language test vectors.
- **Hub SSH deployment** takes a plaintext password (`deployHaiNetHub(... pass: String ...)`) and runs `sudo` scripts. Host-key pinning is implemented, which is good. Check that the password is never logged or persisted (`Logger` has a redaction path; I did not audit every call).
- `allowBackup="false"`, TLS-only base config, system-CA trust only, and a token on the loopback proxy are all good and should be kept.

### 3.2 Duplicated / doubled-up code

| Area | Where | Notes |
|---|---|---|
| Signature payload reconstruction | `PostPacketHandler`, `CommentPacketHandler`, `ReactionPacketHandler`, `SyncPacketHandler` (L289-295, 350-356, 391-393), `HandshakePacketHandler`, `MeshPacketVerifier` | Same formats copied 3+ times; already drifted (U3). ~29 `CryptoService.verify` calls in `HandshakePacketHandler` alone. |
| YouTube ID extraction | `VideoPlayer.kt:381 extractYouTubeId` vs `YouTubeInternalClient.kt:63 extractVideoId` (+ `isYouTubeUrl` in `VideoPlayer`) | Two implementations. The project already fixed one real bug caused by calling the wrong one on a googlevideo URL (see PROJECT_STATUS 2026-09-23). Keep only `YouTubeInternalClient.extractVideoId`. |
| OkHttp client construction outside `HttpClientProvider` | `InvidiousApiClient.kt:32`, `TorService.kt:668,691` | Bypasses the central proxy/isolation policy. `TorService` probing may be intentional (bootstrap checks); Invidious should almost certainly use the provider. |
| Ghost-peer cleanup | Already consolidated into `cleanupOrphanedGroupPeers` (2026-09-29) | Good. Use as the model for other consolidations. |
| Hub sync polling | Already pruned from repo `hubSyncJob` | Good. Verify only one poller remains. |
| `NoSlopRepository` as a facade | It constructs `IdentityRepository`, `PreferencesRepository`, `EngagementRepository`, `FeedRepository`, `SettingsRepository`, `MeshSocialRepository` and re-exposes their methods | ~180 funs, a lot of pure pass-throughs. Every UI change forces edits in three layers (Screen → ViewModel → Repository). |
| `HaiNetTab.kt` | 9-line wrapper that just calls `HubSetupScreen` | Inline it or rename `HubSetupScreen`. |
| Per-type `MeshPacketHandler` split | `MeshPacketHandler.kt` (107 lines) + one handler per domain | Sensible, but `MediaPacketHandler` says "verbatim move" — check for leftover dead members in the dispatcher. |

### 3.3 Unused / dead / placeholder code — [Verified via a token-count scan; each item should be confirmed by the compiler's unused warnings]

- `mesh/Packets.kt:112` `class MediaPendingPayload` — never referenced.
- `data/MediaSettings.kt:10` `cacheRelayedMedia` — never read.
- `data/Entities.kt:16` `iconUrl` — never read.
- `mesh/Packets.kt:29` `hashtags` — declared, never used.
- `crypto/MnemonicGenerator.kt:272` `deriveSeedB64`, `debug/Logger.kt:236` `getAllLogsText`, `ui/NoSlopViewModel.kt:1906` `triggerBackupPrompt` — functions referenced only at their definition.
- Constants/vals with a single occurrence: `GossipService.PEER_FAILURE_WINDOW_MS`, `firewallTtlMs`, `establishedAt`; `MediaManager.MAX_CONCURRENCY` (declared `= 2`, never used, so download concurrency is governed elsewhere); `YouTubeInternalClient.startupTimestampMs`, `GEO_LOCK_PATTERN`; `HttpClientProvider.activeMediaClient`, `repRsv`; `NoSlopViewModel.savedItems`, `isPrioritySource`, `sessionStartTimeMs`; `UnifiedFeedTab.firstPreloadDelayMs`; `JamendoApiClient.CLIENT_ID`; `CryptoService.rawXPub`.
- `ui/theme/Color.kt`: `Purple80/PurpleGrey80/Pink80/Purple40/PurpleGrey40/Pink40` — untouched Android Studio template colours.
- `NoSlopForegroundService.kt:137`: notification uses `android.R.drawable.ic_dialog_info` with the comment "Using a system icon for now" — an under-finished detail visible to every user.
- `FeedTutorialSlide` (`UnifiedFeedTab.kt` ~L2300-2340) builds "Mock Author Bar" / "Mock Interaction Icons". This is an intentional tutorial overlay, not fake data. Left alone.

Not found: no `TODO`/`FIXME`/`NotImplementedError` markers in `app/src/main`. That is genuinely unusual and worth noting positively, but it also means unfinished work is hidden in comments/status docs rather than tracked.

### 3.4 Under-developed or fragile areas

- **Unbounded/legacy compat paths** (pipe signatures, `ENC:GCM:` without AAD, legacy AES-CBC backups, `"Active (Legacy Connection)"` hub status, "placeholder peer from earlier development" cleanup in `NoSlopRepository` ~L1798). Each is individually reasoned, but none has a removal date. See C2.
- **Hub status stored as a display string** (`"Active at <ip>"`, `"Active (Legacy Connection)"`) and parsed with `substringAfter` (`invokeHubApi` L156-157). Fragile; model it as a small sealed type or separate settings keys.
- **`!!` usage**: 86 occurrences. Clusters worth a look: `TorService` (10: `activeMainServiceId!!` etc. on mutable fields that other threads can null; `line!!` inside `while` loops), `AvatarCropper` (13), `SettingsTab` (8), `HubSetupScreen` (7), `GossipService` (5).
- **Empty `catch` blocks**: ~20, mostly `socket.close()` (fine) but also `UnifiedFeedTab.kt:1372`, `NoSlopViewModel.kt:2302`, `MediaComponents.kt:730`, `WikipediaApiClient.kt:100`, `InvidiousApiClient.kt:411,474`, `RedditApiClient.kt:180`, `VimeoApiClient.kt:57`. Add at least a `Logger.debug` so failures are diagnosable.
- **`runBlocking` in `BackupManager`** (L122, L208) inside export/import. Confirm these are never called on the main thread.
- **`Thread.sleep(200)`** in `TorService.kt:193`; confirm it runs on `Dispatchers.IO`.
- **Aggressive polling in `MediaManager`**: a `while(isActive)` loop runs `maintainDownloads()`, `updateWakeLock()` and `enforceStorageLimits()` every 2 s for the app's whole life, even with no downloads. Make it event-driven or back off when idle.
- **`MediaProxyService`** replies `Accept-Ranges: none`, so seeking in mesh-hosted video cannot use range requests (ExoPlayer must re-stream). Query parsing uses `split("=")` and silently drops values containing `=`.
- **`PreloadManager` / `VideoPlayer`** contain a lot of watchdog tuning constants that have been retuned in nearly every recent changelog entry (stall thresholds 8s → 22s → 50s, buffer sizes several times). Collect them into one config object with rationale, so the next tuning pass is one edit and not a hunt.
- **Feed sync gating** uses a polling loop, `while (PreloadManager.isVideoActive || ...) delay(2000L)` (`FeedRepository`). A `StateFlow` + `first { }` would be cleaner and cancel-friendly.

### 3.5 Localisation

- `LanguageManager` lists Greek (`el`) but there is **no `content_el.json`**; README claims "21+ languages" and 21 files exist (English + 20). Either add Greek or drop it from `WELL_KNOWN_LANGUAGES`.
- `content_en.json` has 779 keys; every other file except `hu` has 790. So the source-of-truth file is *behind* the translations by 11 keys, and 1 key that English has is missing elsewhere.
- Of 653 distinct `"…".tr` string literals in code, ~55 are **not present in `content_en.json`** at all (e.g. "Trust & Pin", "Edit Broadcast", "Caught Up on Creators!", "Break & Exit Reminders", the backup-legacy warnings). They render in English in every language. Automate a CI check: extract `.tr` literals → diff against `content_en.json` → diff every language against English.
- Several languages carry many keys identical to English (de 48, fr 45, it 43, nl 43), which may be untranslated leftovers or legitimate loanwords. Needs a human pass.
- `.tr` calls with string templates (`"…$x…".tr`) can never match a static key; those need placeholder-style keys (some already exist, e.g. `{value}`).

### 3.6 Build, dependencies, tooling

- Media3 is pinned inline at `1.3.1` for seven artifacts, Gson (`2.10.1`), `exifinterface`, `webkit`, `room-testing`, `jna`, `lazysodium` and `jsch` are also inline strings, while everything else is in `libs.versions.toml`. Move them all to the catalog for a single upgrade surface. Media3 1.3.1 is well behind current releases.
- `androidx.security:security-crypto:1.1.0-alpha06` is an alpha and the library is deprecated upstream; plan a migration path (Keystore-backed storage) for the identity private key.
- Compose BOM `2024.09.00` and `coil 2.7.0` are dated; AGP 9.4.1 / Kotlin 2.2.10 / KSP 2.3.6 are very new — confirm this combination actually builds in CI (the README badge "Build: Passing" is a **static** shields.io image, not a CI status).
- `abiFilters` include `x86` and `x86_64` for release builds, which enlarges the APK (Tor + libsodium natives). Fine for emulators; consider splitting or dropping x86 from release.
- Two flavors (`play`, `github`) differ only by `applicationId` and the updater flag. Both `defaultConfig` and each flavor set `applicationId` (`com.noslop.me.app` in default, overridden in both flavors) — the `defaultConfig` value is redundant.
- The `debug` build type sets `applicationIdSuffix = ".debug"`; the manifest `<queries>` and `FileProvider` authorities use `${applicationId}`, so verify the cross-variant install detection still works for debug (it queries the two release IDs only).
- `proguard-rules.pro` exists at `app/`, but R8 has already caused one runtime crash (anonymous Gson `TypeToken`). There is no instrumented smoke test on a **minified release** build. Add one, since Gson reflection is used broadly on packet payloads.
- Repo clutter: `scripts/` holds ad-hoc `test_*.py/.kt/.kts/.sh` experiments (not tests), `_workspace/` contains empty dirs plus `WIDER_INFRASTRUCTURE.md`, `docs/archived/` has 21 files. Consider moving experiments to `scripts/experiments/`, deleting empty dirs, and adding an `docs/archived/README.md` index.

### 3.7 Tests

- ~119 tests exist and cover crypto, mnemonic, wire protocol, verifier, gossip, group message security, preferences, backup and a couple of repositories. That is good coverage for the security-critical core.
- **No tests** for: `MediaManager`/`MediaProxyService` (where U1 lives), `FeedRepository` beyond a small test, the ViewModel, Hub SSH/`invokeHubApi`, `LanguageManager`, `UpdateChecker.isNewer` (marked `internal`, so it is testable), any Compose UI.
- `MeshPacketVerifierTest` deliberately restates the formats independently; extend it with **cross-handler parity tests** (feed each legacy/new format into both the verifier and the handler and assert they agree) — this would have caught U3.
- Room has a `MigrationTest` and schemas are exported (good). Make sure every version bump adds a migration test.

### 3.8 Documentation

- `docs/PROJECT_STATUS.md` is ~290 KB and append-only; several entries contradict later ones (e.g. Jamendo fixes, watchdog thresholds retuned repeatedly). Keep it as a changelog but add a short, **current-state** `docs/ARCHITECTURE_CURRENT.md` (maybe one page per subsystem) that is edited in place.
- It states "Inspected all 110 Kotlin source files … 100% code parity". The tree now has 116 main files, and I found parity gaps (U3), so that claim should not be relied on.
- Docs and archived reports repeat the proxy secret literal (see U2). Scrub after rotating.

---

## 4. Consolidation opportunities (ordered by value)

**C1. One canonical signing/verification module.** Create `mesh/PacketSigning.kt` with, per packet type: `canonical(payload)` (the only writer format), a `legacyFormats(payload): List<String>` (read-only), and a single `verify(payload, sig, signer): Match` returning `CANONICAL | LEGACY | NONE`. Then:
- handlers and `MeshPacketVerifier` both call it (no more copies);
- verify each candidate at most once per packet and cache the verdict on the packet (or in the LRU that already dedups ids), so hop-time verification is not repeated in the handler;
- log/count `LEGACY` matches so you know when it is safe to delete each legacy format (see C2).

**C2. Give every legacy path a sunset.** For each of: pipe signatures, `ENC:GCM:` no-AAD, AES-CBC backups, `Active (Legacy Connection)`, old-placeholder-peer cleanup — add a `// REMOVE AFTER vX.Y` marker, a counter, and a line in a `docs/DEPRECATIONS.md`. Then delete on schedule.

**C3. Split the god-classes along seams that already exist.**
- `NoSlopViewModel` (142 funs / 38 `MutableStateFlow`s): feed, mesh/chat, hub, settings, onboarding ViewModels sharing the repository.
- `NoSlopRepository`: stop re-exporting sub-repositories; let the ViewModels take `FeedRepository`, `MeshSocialRepository` etc. directly (or expose them as properties). Keep the facade only for genuinely cross-cutting operations.
- `VideoPlayer.kt` (2.1k): extract the stall watchdog, the cached-source/staleness logic, and the ExoPlayer factory into separate files.
- `SettingsTab.kt` (2.0k) and `UnifiedFeedTab.kt` (2.5k): one file per settings section / feed sub-screen (`FeedMixSettingsSection.kt` is already the right pattern).

**C4. Central network policy.** All OkHttp instances should come from `HttpClientProvider`; add a lint or unit test that greps for `OkHttpClient.Builder()` outside it (allow-list `TorService` bootstrap checks).

**C5. One place for external-API constants and keys.** InnerTube key, Jamendo IDs, proxy URL, User-Agent strings (currently repeated in `MediaComponents`, `NoSlopApp` Coil interceptor, `RedditApiClient`, `InternetArchiveClient`, `WikimediaApiClient`) → `feeds/ApiConfig.kt`.

**C6. Feed API clients.** ~15 clients (`Reddit`, `HackerNews`, `Wikipedia`, `Wikimedia`, `Pexels`, `Nasa`, `Openverse`, `Jamendo`, `InternetArchive`, `ArtInstitute`, `Guardian`, `News`, `Vimeo`, `PodcastIndex`, `Invidious`, `YouTubeInternal`) each re-implement request → parse → map to `FeedItem` with their own try/catch and logging. A tiny `FeedSource` interface + shared `safeFetch` helper (timeout, logging, empty-on-error) would remove the repeated empty-catch blocks and make adding/removing sources cheap.

**C7. Tunables in one file.** Preload/stall/timeouts/thresholds (see §3.4).

---

## 5. Mock / placeholder inventory (for completeness)

| Item | Verdict |
|---|---|
| `FeedTutorialSlide` mock bars/icons | Intentional UI for onboarding tutorial. Keep. |
| `MediaProxyService` `placeholderMetadata` (`chunkCount = 999`, `size = 0`) | A deliberate "auto-discover EOF" hack. Works, but the magic `999` and `size = 0` sentinel should be named constants or a proper `UNKNOWN_SIZE`. |
| `CommentPacketHandler` `fakeMeta` (L62) | Builds a synthetic `MediaMetadata` for comment media. Rename to something honest (`syntheticMeta`) and document why. |
| `SshDeployer` "dummy channel" keep-alive | Legitimate NAT keep-alive; comment already explains it. |
| `HttpClientProvider` "dummy InetAddress" | Legitimate trick to pass the hostname to the SOCKS proxy without local DNS. |
| Android Studio template colours in `Color.kt` | Dead. Delete. |
| Test fakes (`FakeDaos.kt`) | Test-only; fine. |

No hardcoded fake feed data, stub repositories or canned responses were found in `app/src/main`.

---

## 6. Suggested next steps

**Now (this week)**
1. **U1** — media-ID validator + canonical-path check at all `File(dir, id)` sites; tests with `../` IDs; check the firewall allow-list for `MEDIA_REQUEST`.
2. **U2** — rotate the proxy secret, remove the literal default, add server-side rate limiting, scrub docs/scripts, fix the stale gradle comment.
3. **U3** — add the missing legacy formats to the verifier for `EDIT_POST` and `REACTION` (or drop legacy from the handlers if you decide it is dead), and add a handler/verifier parity test.
4. Delete confirmed-dead items in §3.3 (start with `MediaPendingPayload`, template colours, `cacheRelayedMedia`, `MAX_CONCURRENCY`) and let the compiler's unused warnings confirm the rest.

**Next (this month)**
5. **C1** canonical signing module, with a `LEGACY` counter; then **C2** deprecation schedule.
6. Decide the LAN Hub path (U4): remove or make it explicit and authenticated.
7. Localisation CI check (§3.5); add or remove Greek; sync `content_en.json`.
8. Move all inline dependency versions to `libs.versions.toml`; bump Media3; plan the `security-crypto` replacement; add a CI workflow that actually builds and runs unit tests, and swap the static "Build: Passing" badge for the real one.
9. Add a minified-release smoke test (R8 + Gson reflection).
10. Replace the 2-second polling loop in `MediaManager` with an event/backoff design; fix `Accept-Ranges` in the proxy if mesh video seeking matters.

**Later**
11. **C3** god-class splits, one file at a time, behind the existing tests.
12. **C4-C7** networking policy, API constants, `FeedSource` interface, tunables file.
13. Write `docs/ARCHITECTURE_CURRENT.md` and `docs/DEPRECATIONS.md`; index `docs/archived/`; tidy `scripts/` and `_workspace/`.

---

## 7. Guidance for the next LLM working in this repo

- **Do not trust status docs over code.** `PROJECT_STATUS.md` is a log of intent; verify against source.
- **Signing formats are load-bearing.** Any edit to a `Signed(...)` payload must be made in *every* place (handler, sync handler, verifier) until C1 lands, and `MeshPacketVerifierTest` / `WireProtocolTest` must be updated in the same commit.
- **Never build a `File` from a peer-supplied string** without the U1 validator.
- **Avoid adding more legacy-compat branches** without a counter and a removal marker.
- **Prefer editing existing files over adding wrappers**; the project already has enough thin pass-through layers.
- **Anything touching Tor timing** (`PreloadManager`, `VideoPlayer` watchdog, `MeshTransport` timeouts) has been retuned many times based on emulator logs; read the last three changelog entries before changing a number, and put new values in a shared constants file.
- **Run before committing:** `./gradlew testGithubDebugUnitTest` (or the `play` equivalent) and, for anything touching Room, the `androidTest` migration tests. (I could not run these; the commands are inferred from the flavor names.)

---

*Generated by static review. Line numbers refer to the zip snapshot and may shift.*
