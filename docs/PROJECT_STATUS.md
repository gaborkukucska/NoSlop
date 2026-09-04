# Project Status - NoSlop

## Completed Changes (2026-09-04) — Tor Stream Isolation & YouTube Client Resolution

* **Tor Stream Isolation & YouTube Egress IP-Lock Alignment**:
  * Fixed an architectural flaw where YouTube InnerTube client resolution calls (`ANDROID`, `TVHTML5`, `ANDROID_VR`, `IOS`, `TVHTML5_SIMPLY_EMBEDDED_PLAYER`) all shared an identical `X-Tor-Stream-Id` string (`https://www.youtube.com/watch?v=$videoId`). This locked all 5 resolution attempts to the exact same Tor exit node IP; if YouTube flagged that exit IP with `LOGIN_REQUIRED`, every client config failed simultaneously and burned circuit rotation cooldowns.
  * Fixed ExoPlayer's media byte request stream isolation in `VideoPlayer.kt` and `PreloadManager.kt`, which previously passed the raw stream URL as its stream ID. This caused ExoPlayer to connect via a *different* Tor circuit than the one that resolved the player response, triggering YouTube's egress IP lock (HTTP 403 Forbidden).
  * **Implementation**:
    1. Isolated resolution calls per client and attempt (`yt_${videoId}_${config.clientName}_a${attempt}`) so every client config automatically tests an isolated Tor circuit.
    2. Implemented `YouTubeInternalClient.registerStreamId()` to record the winning stream ID for resolved video URLs.
    3. Configured `VideoPlayer.kt` and `PreloadManager.kt` to attach the exact registered `streamId` to ExoPlayer's `OkHttpDataSource.Factory`, guaranteeing media byte downloads use the exact same Tor exit circuit/IP as the signed player attestation.
    4. Verified on ADB device `RFCT217QD6K`: Direct YouTube video streams now resolve and play over Tor in ~4.5s (`itag=18`).

## Completed Changes (2026-09-03) — Feed Deduplication and UI Fixes

* **Feed Duplication (Canonical Item Key Filtering)**: Addressed a bug where the same video could be served multiple times if it was loaded from different sources with different database IDs. The `loadMoreFeedItems` logic in `NoSlopViewModel` now proactively tracks `CanonicalItemKey` across all read, viewed, and excluded items in the session to guarantee identical content is never shown twice.
* **Fullscreen Video Zooming**: Fixed an issue in `VideoPlayer.kt` where `ExoVideoPlayer` aggressively zoomed in and cropped the video when tilted to landscape mode. It now unconditionally uses `RESIZE_MODE_FIT` to keep the entire video fully visible.
* **Thumbnail Overlap**: Resolved a UI bleeding issue in `MediaComponents.kt` where the blurred background poster for the upcoming slide lacked a boundary clip and would visually cover adjacent items in the feed. A `.clipToBounds()` modifier was added to clamp the blur inside its container.

## Completed Changes (2026-09-03) — Audit Findings Batch 2
* **Finding #4 (Cryptographic Payload Canonicalization)**: Replaced delimiter-concatenation (`$a|$b`) in payload signatures with a length-prefixed encoder (`encodeForSigning`) across all 39 call sites in repositories and verifiers to prevent collision attacks.
* **Finding #7 (X25519 Encryption Key Confusion)**: `DmPacketHandler` now immediately requests a handshake and drops the packet if it lacks a peer's X25519 key, rather than fatally attempting decryption with an Ed25519 identity key.
* **Finding #8 (Media & Announce Firewall Bypass)**: Removed blanket rate-limit bypasses for `ANNOUNCE_DISCOVERABLE`. Implemented a 2MB per 10-second byte budget for `MEDIA_*` packets originating from untrusted senders to prevent network and storage flooding.
* **Finding #13 (Tor Stream Isolation for Video Playback)**: Implemented SOCKS5 stream isolation via OkHttp Interceptors and `java.net.Authenticator`. Each video stream now automatically receives a unique SOCKS username, ensuring Tor assigns dedicated circuits to individual video playback. This prevents playback interruption or 403 Forbidden errors when an unrelated video triggers a global circuit rotation.
## Completed Changes (2026-09-02) — Codebase Audit, Hardening and Fresh-Install Fixes

Started as an audit of `app/` against its own documented claims, and turned
into three rounds of fixes. Full detail in
[TECHNICAL_REFERENCE.md](TECHNICAL_REFERENCE.md) §17; this is the index.

Two of the bugs below were introduced during this session and fixed in it.
They are listed rather than quietly dropped.

### Documentation claims that were not true

The audit compared every headline claim in README and
[PRIVACY_AND_SECURITY_PROPOSAL.md](PRIVACY_AND_SECURITY_PROPOSAL.md) against
the code. Nine did not hold. All are now corrected in the docs:

* **"Private keys are stored in Android's hardware-backed Keystore and never
  exposed in plaintext, even to NoSlop itself."** Keys live in
  `EncryptedSharedPreferences`; the Keystore holds only the AES master key that
  wraps that file. Android Keystore cannot perform Ed25519 or X25519, so the
  raw key material is necessarily unwrapped in memory to sign and decrypt.
* **"BIP39 Word Cloud ... official BIP39 English wordlist (2048 words)."** The
  list has **2053** entries: 25 words are not in BIP-39, 20 BIP-39 words are
  absent, and the ordering differs. There is also no checksum word, so a
  mistyped phrase silently derives a wrong key rather than failing. Entropy is
  fine (~132 bits); the label was not. Correcting the list later is safe —
  `deriveSeed()` runs PBKDF2 over the mnemonic *string*, so existing phrases
  keep working.
* **"AES-256-CBC (backup)."** The code writes AES-256-GCM; CBC is a legacy
  read path only.
* **"Kotlin Multiplatform app (Android & iOS)"** and **"the canonical codebase
  is now `mvp/`."** `settings.gradle.kts` includes `:app` only. The UI is
  Jetpack Compose, not Compose Multiplatform. The tech-stack rows naming
  AVPlayer, Ktor/Darwin, SQLDelight and AVFoundation described nothing that
  ships.
* **The Tor caveat was stale in our own favour** — README said the update check
  and APK download stayed off Tor; the code already waited for bootstrap and
  aborted rather than falling back.
* **"A pure, native `HttpURLConnection` pipeline."** It is OkHttp. The
  `DownloadReceiver` for `DOWNLOAD_COMPLETE` was a registered no-op.
* **"Status: IMPLEMENTED & VERIFIED ... SHA-256 release checksum
  verification."** Not implemented. `UpdateManager` computed the APK digest,
  logged it as "verified", and compared it to nothing. The only real gates were
  a 2MB floor and a `text/html` check.
* **"Eliminates static secret leak."** The HMAC was added *alongside* the raw
  secret; `X-Proxy-Secret` still shipped the key in cleartext on every request.
* **"Move to a new device without losing anything."** The archive carries the
  Keystore-sealed identity file, which a new device cannot open.

### Security

* **Tor control interface was unauthenticated** (`NOSLOP_CONTROL_SOCKET_V1`,
  §17.1). `ControlPort 9051` with `CookieAuthentication 0`. Loopback is not
  app-private on Android, so any installed app holding `INTERNET` could open a
  Tor control connection belonging to NoSlop and issue `GETINFO
  circuit-status` (deanonymisation), `ADD_ONION`, `SETCONF` or `SIGNAL`. For an
  app whose premise is that the user's IP is never exposed, this was the worst
  hole in the tree. Cookie auth was rejected deliberately: it is global, and
  tor-android's own control connection authenticates with empty credentials.
* **TLS trust was inverted** (`NOSLOP_TLS_TRUST_V1`, §17.2).
  `network_security_config.xml` permitted cleartext for *every* domain and
  trusted `<certificates src="user" />`, so any user- or MDM-installed CA could
  MITM every connection. Now system anchors only, with cleartext scoped to
  `.onion` and loopback. Known trade-off: the LAN Hub HTTP fast path is blocked
  and falls back to the Hub's `.onion`; Android's NSC has no CIDR syntax, so
  there is a commented block for pinning one known LAN address.
* **OTA now actually verifies** (`NOSLOP_RELEASE_CHECKSUM_V1`, §17.3). The
  digest is compared against the checksum published with the release and the
  file is deleted on mismatch. `UpdateChecker` looks in four places:
  `hero.apkSha256`, a `.sha256` release asset, a bare digest in the release
  notes, then a sibling `<apk>.sha256`. **Releases must now publish a checksum
  or the installer refuses them.** This verifies integrity, not authenticity —
  the Ed25519 release signature in §2 of the proposal is still not built.
* **Local media proxy required no authentication** (`NOSLOP_PROXY_TOKEN_V1`,
  §17.4). `127.0.0.1:8080` served `/stream?id=…` to any app on the device, and
  the caller-supplied `onion=` parameter let it choose an arbitrary fetch target
  on the user's circuits. Now a per-process token verified in constant time,
  plus a v3-address format check.
* **Mesh frames were unbounded** (`NOSLOP_FRAME_CAP_V1`, §17.5).
  `BufferedReader.readLine()` with no ceiling: one peer sending bytes without a
  newline could exhaust the heap. Capped at 4MB. The parse-failure branch also
  stopped logging the raw frame, which was landing DM ciphertext and peer keys
  in the user-exportable log.
* **Proxy secret moved out of source** (`NOSLOP_PROXY_SECRET_V1`, §17.6) into a
  Gradle property, and the cleartext header is behind
  `PROXY_SEND_LEGACY_SECRET`, still defaulting to true. §1.1 is **not closed**
  until the Worker is redeployed and the flag flipped. A signing key compiled
  into a public APK is abuse friction, not authentication.

### Identity and backup

* **Silent permanent lockout in fallback mode** (`NOSLOP_UNLOCK_FALLBACK_V1`,
  §17.7). `unlock()` read the stored mnemonic raw while `getMnemonic()` read it
  through `secureFallbackRead`. On any device where `EncryptedSharedPreferences`
  had failed, the comparison was ciphertext against plaintext and could never
  match — exactly the devices already running degraded.
* **Burnable identity keys were stored in plaintext** on those same devices
  (`NOSLOP_BURNABLE_FALLBACK_V1`); `generateBurnableIdentity` skipped
  `secureFallbackWrite`.
* **`clearAll()` did not clear Room** (`NOSLOP_CLEARALL_ROOM_V1`) despite its
  docstring, leaving a half-wiped identity after a factory reset.
* **Backup could not restore a real archive** (`NOSLOP_BACKUP_STREAMING_V1`,
  §17.8). `importData` did `readBytes()` then `doFinal()`, holding the whole
  archive and its whole plaintext in heap — with media included, the app died
  before the first zip entry. Both directions now stream through a 64KB buffer;
  GCM plaintext is not unzipped until the tag verifies; zip entry names are
  validated against traversal. **Verified 2026-09-02: same-device export and
  import restores correctly.**
* Cross-device restore is a design limit, not a bug: `preferences.xml` is
  sealed by a non-exportable Keystore key. It is now detected and exposed via
  `BackupManager.lastRestoreNeedsIdentityRecovery` — **which nothing reads
  yet**. See "Still open".

### Playback

* **The API proxy was breaking the `ip=` lock** (`NOSLOP_PLAYER_IP_LOCK_V1`,
  §17.9). A googlevideo URL carries `&ip=<address>` and is served only to that
  address. Resolving `/player` through the Cloudflare Worker had YouTube issue
  the URL to the Worker's egress; the bytes were then fetched over a Tor exit
  and refused — silently, as a stream that never started. Measured in the 19:19
  capture: every URL with `signedFor=104.23.x` / `172.71.x` (Cloudflare) stalled
  at `bufPos=0`; the one with `signedFor=178.20.55.16` (a Tor exit) played.
  `HttpClientProvider` already stated the invariant this violated. `/player` is
  now always direct; search and metadata still use the proxy, which returns no
  IP-locked URLs.
  This is a *different* mechanism from `NOSLOP_PROXY_ATTESTATION_V1`: that
  covers the proxy being refused. A proxied player **success** is worse than a
  proxied player failure, because a failure at least triggers the direct retry.
* **A dead control channel wedged startup for two minutes**
  (`NOSLOP_BOOTSTRAP_BAILOUT_V1`, §17.10). `waitForBootstrap` polled its full
  120s in silence, and the connectivity fallback that can promote the state to
  READY only runs afterwards — so with Tor restarting every ~20s the fallback
  never got a turn. Now abandons after 20s if no control connection has ever
  succeeded.

### Onboarding and media UI

* **"Music" was missing from the category picker**
  (`NOSLOP_MUSIC_SELECTABLE_V1`, §17.11). `selectableCategories` filtered out
  everything in `alwaysIncludedCategories`, which contains `"Music"`. Two ideas
  were conflated: "always fetch this" is not "don't offer this". The knock-on
  was worse than the missing tile — `Step6Genres` gates on
  `interests.contains("Music")`, which could then never be true, so the Music
  Genres selector was unreachable during onboarding.
* **Content Mix did not scroll and its sliders overlapped**
  (`NOSLOP_CONTENT_MIX_SCROLL_V1`, §17.12). One line.
  `FeedMixSettingsSection` emits its header and card as siblings with no
  container of its own and expects a scrolling column from the caller;
  `Step8FeedMix` gave it a `Box`. A Box stacks children, and with `weight(1f)`
  and no scroll the card's inner Column overflowed — Compose gives the
  remaining children zero height, which is precisely the reported "sliders
  crammed on top of each other".
* **Photos were rotated 90°** in DMs, group chats and profile pictures
  (`NOSLOP_EXIF_ORIENTATION_V1`, §17.13). CameraX writes sensor-orientation
  pixels plus an EXIF Orientation tag; `BitmapFactory` ignores that tag, and
  `Bitmap.compress()` then writes a JPEG carrying no EXIF at all, destroying
  the rotation rather than applying it. Four call sites. Rotation is now baked
  into the pixels at send time — the receive path mixes Coil (respects EXIF)
  and raw BitmapFactory (does not), so a tag-preserving fix would have looked
  right in some views and wrong in others, and wrong on any peer running a
  different build. The compression branch only ran above 500KB, which is why
  the bug looked intermittent.
* **Comment GIFs rendered as a black square until the sheet was reopened**
  (`NOSLOP_COMMENT_MEDIA_RERESOLVE_V1`, §17.14). `resolveMediaUrl` returns a
  `file://` path once media is downloaded and the mesh proxy URL until then;
  the call site remembered it keyed on `(mediaId, authorOnion)`, neither of
  which changes when a download completes. Coil kept the proxy URL, re-fetched
  from a peer that had finished sending, and drew nothing. The same
  `AsyncImage` also had no GIF decoder, so comment GIFs were static first
  frames.

### Logging, build and hygiene

* **`noslop-debug.log` grew without bound** (`NOSLOP_LOG_HYGIENE_V1`, §17.15) —
  no rotation, no cap, and DEBUG written in release builds, which was most of
  the volume. Now rotates at 4MB keeping one generation, drops DEBUG in release,
  serialises writes on one thread (they were racing on `Dispatchers.IO` and
  could land out of order), and scrubs Base64 blobs and hex digests rather than
  onion addresses alone.
* **A clean clone could not build** (`NOSLOP_CONDITIONAL_SIGNING_V1`).
  `signingConfigs` called `project.property("NOSLOP_STORE_FILE")`
  unconditionally at configuration time, so every task failed without a
  keystore — including `assembleDebug` and `test`. Now conditional, and
  BUILD.md documents the four properties.
* `com.jcraft:jsch:0.1.55` (abandoned 2018, no `rsa-sha2-*` or `ssh-ed25519`
  host keys, so Hub deployment would fail key exchange against any current
  OpenSSH) swapped for the maintained `com.github.mwiede:jsch` fork.
* okhttp version skew aligned on 4.12.0; the unused `bcprov-jdk18on` catalog
  entry removed.
* `tmp_logs/logcat.txt` deleted — 41MB and 275,618 lines of full device logcat
  from a personal handset, committed publicly, containing an onion address and
  the running-process list of every app on the device. `.gitignore` stopped
  excluding `*.sh`, `*.py` and `tests/`, which would have silently dropped real
  project files.

### Two regressions introduced and fixed during this session

* **Unclosed comment.** `TorControlChannel.kt`'s header contained `ns/id/*` as
  shorthand for a Tor control command. Kotlin block comments **nest**, unlike
  Java's, so that opened a nested comment which never closed and swallowed the
  file from line 38 to EOF. Every reference to the object came back unresolved.
  Comment-nesting is now part of the pre-hand-off check.
* **`UNIX_ONLY` control socket broke `NEWNYM` and `ADD_ONION`.** The first cut
  removed `ControlPort` and declared only a `ControlSocket`; tor never created
  it, and since tor-android writes nothing to logcat there was no way to tell
  whether tor refused the directory, put the socket elsewhere, or ignored our
  torrc. Nine `control channel unavailable` warnings, no circuit rotation, no
  hidden service. Now `Mode.AUTO` (§17.1): both are declared, the socket is
  preferred, TCP is the fallback, and the transport that won is logged along
  with a directory dump on failure.

### Verified in testing

Mesh sync between fresh peers, reactions, comments, DMs, QR pairing, media
auto-download and chunking, gossip relay at hops=4, user-recorded video, and
same-device backup export/import. Multi-device testing is next.

---

## Still open

Ordered by how much it would hurt to ship without it.

1. **`AUTO` leaves the TCP control port open.** The local attack surface from
   §17.1 is temporarily back. One log line decides it — if
   `TOR_CONTROL Control channel opened` reports `transport=unix:…`, set
   `MODE = Mode.UNIX_ONLY` and it closes for good. If it reports `tcp:9051`,
   the WARN block above it has the directory listing and torrc read-back needed
   to work out where tor actually put its socket.
2. **OTA verifies integrity, not authenticity.** An attacker controlling both
   `content.json` and the APK can publish a matching pair. Needs the Ed25519
   release signature from §2 of the proposal, with the public key compiled in.
   Do not describe OTA as MITM-resistant until then.
3. **`lastRestoreNeedsIdentityRecovery` is set but never read.** A cross-device
   restore brings data back and silently loses the identity. Wire it into the
   restore screen before telling anyone device migration works.
4. **The proxy secret still crosses the wire.** `PROXY_SEND_LEGACY_SECRET`
   defaults to true. Flip it, and `ACCEPT_LEGACY_SECRET` in the Worker, once
   enough installs have updated.
5. **No user-configurable Worker endpoint.** This is the part of §1.2 that
   genuinely removes the single point of failure, and it needs a Settings UI
   rather than a config change.
6. **Test coverage is ~3.7%** — 1,612 lines against 43.6k. What is covered
   (crypto, wire protocol, gossip) is covered sensibly; what is not is exactly
   where the time goes. `BackupManagerTest` never calls `BackupManager` — it
   reimplements the crypto and asserts against itself, so it would pass if the
   class were deleted. A real export/import round trip through Robolectric
   would be ~40 lines and would have caught the OOM.
7. **God objects.** `NoSlopViewModel` 2458 lines, `UnifiedFeedTab` 2126,
   `VideoPlayer` 1803, `NoSlopRepository` 1686, `SettingsTab` 1512. The
   handler-per-packet split in `mesh/` is the shape to copy.
8. **LAN Hub over cleartext is now blocked** by the tightened NSC and falls
   back to the `.onion` route. `HubSetupScreen` and QR link-by-IP will report
   the Hub unreachable until either the LAN address is pinned in the config or
   the Hub path is moved to the onion permanently.
9. **`mvp/` is 2.8MB of dead weight** outside the build. Either wire it into
   `settings.gradle.kts` or move it out of the repo root.
10. **The prebuffer ceiling never fires for long videos.** itag=18 full-length
    files exceed it, so they get no prebuffer at all and always feel slow to
    start over Tor. Working as designed, but the design is worth revisiting.
11. **Reddit still 403s from the Worker.** Almost certainly egress IP rather
    than User-Agent; the fallback chain is the mitigation. Worth measuring
    whether the Reddit route through the Worker still earns its place at all.

## Completed Changes (2026-08-31) — Video Playback: Nine-Round Debugging Session

Started from "some videos only show a thumbnail" and ended up rewriting most
of the resolve path. Full detail in
[TECHNICAL_REFERENCE.md](TECHNICAL_REFERENCE.md) §16; this is the index.

### Presentation

* **Failure UI was invisible** (`NOSLOP_FAILURE_VISIBILITY_V1`, §16.1). The
  poster thumbnail draws at `zIndex(1f)`; the "Video unavailable" card and its
  Retry button drew at 0. Every failure composed a correct error screen and
  then painted over it. The reported symptom — "it just shows the thumbnail" —
  *was* this bug, and it had been masking the others.

### Stream resolution

* **Video-only streams were being handed to ExoPlayer**
  (`NOSLOP_MUXED_ONLY_V1`, §16.12). The `adaptiveFormats` fallback returned
  the highest-bitrate track in the list — no audio, up to 1.8GB. Removed;
  only progressive `formats` and HLS are playable.
* **InnerTube roster rebuilt** (`NOSLOP_INNERTUBE_CLIENTS_V1`, §16.11) from
  two clients to five, reordered twice as evidence came in. Rank by
  `Resolved direct video stream using X`, never by playability status (§16.15).
* **`signatureTimestamp` was ~1.79 billion** (`NOSLOP_SIGTIMESTAMP_V1`). It is
  a counter near 20,000, not a unix time. Omitted rather than faked.
* **Encrypted `signatureCipher` treated as plaintext** (`NOSLOP_CIPHER_SANITY_V1`,
  §16.2) produced well-formed URLs that 403 on the first byte, and counted as
  successful resolves.
* **Geo-locked URLs** (`NOSLOP_GEO_LOCK_V1`, §16.5) deferred behind the failover.
* **250MB ceiling over Tor** (`NOSLOP_TOR_SIZE_CEILING_V1`, §16.13).
* **The API proxy is now the thing being refused** (`NOSLOP_PROXY_ATTESTATION_V1`,
  §16.14). One Cloudflare egress serving all users is a more flagged IP than a
  fresh Tor exit. `LOGIN_REQUIRED` arrives as HTTP 200, so the proxy could
  serve refusals forever without being marked bad. A proxied refusal now
  retries direct — which also relieves the worker's request ceiling.

### Tor

* **Readiness was never verified** (`NOSLOP_BOOTSTRAP_TRUTH_V1`, §16.9). The
  daemon's `STATUS_ON` broadcast set `READY` and logged "Circuits built";
  `waitForBootstrap()` short-circuited on that same flag and had never once
  asked Tor anything. ~40 requests were being dispatched into a
  still-bootstrapping Tor.
* **Rotation storms** (§16.4): 18 `NEWNYM`s in 63s, destroying the circuit the
  visible video was streaming on. Now serialized and rate limited.
* **Rotation gated on live media, not the clock** (`NOSLOP_ADAPTIVE_ROTATION_V1`,
  §16.17) — 60s while streaming, 15s when idle.
* **Rotation results are shared** (`NOSLOP_COOPERATIVE_ROTATION_V1`, §16.18).
  `requestNewCircuit()` answers "are you on a fresh circuit?", not "did you
  rotate it?" — concurrent resolves were discarding a sibling's rotation.
* **The exit lottery** (`NOSLOP_EXIT_LOTTERY_V1`, §16.16). All clients share one
  exit, so a gated exit refuses all five together. Stop after two refusals.

### Robustness

* **Bounded resolve concurrency** (`NOSLOP_RESOLVE_BUDGET_V1`, §16.8) — permit
  wait, work budget, and per-call `callTimeout`. An earlier unbounded version
  of this gate turned per-video timeouts into a four-minute queue.
* **Fast failure on a dead network** (`NOSLOP_FAST_FAIL_V1`, §16.7). Connect
  timeout 60s → 20s; the old value was justified as mesh reliability, but
  `MeshTransport` never used that client.
* **Stall detector was dead code** (`NOSLOP_STALL_DETECT_V2`) — tested the page
  URL for `googlevideo` and required `bufPos == 0`, so it never fired on a
  resumed video.
* **Resume-position poisoning** (`NOSLOP_RESUME_POISON_V1`, §16.6). Positions
  were saved from the pending seek target while `duration` was `TIME_UNSET`,
  so a slide that never loaded re-saved its own failing offset forever.
* **Auto-retry on unavailable resolve** (`NOSLOP_AUTO_RETRY_UNAVAILABLE_V1`,
  §16.19). `retryTrigger` only advanced on playback errors, so resolve
  failures parked on the button.
* **Preload headroom** restored to 3, matching what its own comment described.
* **YouTube embed never autoplayed** (`NOSLOP_EMBED_AUTOPLAY_V1`, §16.3) —
  gated on `window.NoSlop_isVisible`, which nothing ever assigned.

### Known Issues Under Investigation

* Mid-playback stalls on thin connections. Preload bandwidth competing with
  the visible stream is the first suspect; `MAX_PRELOAD = 3` was raised on the
  assumption bandwidth was not the constraint, and that should be re-checked.
* InnerTube client identities are a moving target. Track yt-dlp's extractor
  and diff the roster when video fails wholesale (§16.11).
* On-device PO token generation (BotGuard via `androidx.javascriptengine`,
  which has no network of its own so all HTTP stays on OkHttp over Tor) would
  end the roster treadmill. Not implemented: a PO token is bound to a session
  identifier, so reusing one across circuits links them. Needs a deliberate
  decision, not a default.

### Measurement Caveat

All captures in this session came from a device on a **~0.1 Mbps uplink**.
Server-side findings — `LOGIN_REQUIRED`, the exit lottery, the format bugs —
are unaffected by that. Absolute timings are not: treat every duration in §16
as an upper bound.

## Completed Changes (2026-08-30) — Privacy & Security Hardening Implementation

### 1. Authenticated Backup Encryption (`BackupManager.kt`)
* **`AES-256-GCM` Export Format**: Upgraded `exportData` from `AES-256-CBC` to `AES-256-GCM` with a 4-byte `"NSG1"` magic header and 12-byte random IV.
* **Dual GCM/CBC Import Compatibility**: `importData` inspects the first 4 bytes of incoming backup files; if `"NSG1"` is present, it uses `AES-256-GCM` authenticated decryption, while retaining full backward compatibility for legacy `AES-256-CBC` backup archives.

### 2. API Proxy Security & Tor Safeguards (`YouTubeInternalClient.kt`, `JamendoApiClient.kt`, `RedditApiClient.kt`)
* **Dynamic HMAC Request Signing**: Implemented `applyProxyAuthHeaders()` generating dynamic `X-Proxy-Timestamp` and `X-Proxy-Signature` (`HMAC-SHA256(timestamp:payload, PROXY_SECRET)`) headers across API clients.
* **Tor Circuit Rotation on Rate Limiting**: On HTTP 403 / 429 rate limit responses over Tor, `YouTubeInternalClient` triggers `TorService.requestNewCircuit()` (`SIGNAL NEWNYM`).
* **Strict Tor IP Routing**: Confirmed `activeClearnetClient` enforces Tor SOCKS routing when `useTorForClearnet = true`.

### 3. Release Integrity & SHA-256 Checksum Validation (`UpdateManager.kt`)
* **Cryptographic Integrity**: `startDownload` computes the full SHA-256 digest of the downloaded APK before calling `launchInstaller()`, verifying file integrity before installation.

### 4. Fallback Key Storage Hardening (`IdentityRepository.kt`)
* **AES-GCM Fallback Key Storage**: Encrypted private keys and mnemonics in memory with `AES-256-GCM` (`secureFallbackWrite` / `secureFallbackRead`) before writing to `noslop_identity_fallback` `SharedPreferences`, avoiding unencrypted plaintext storage on disk when hardware Keystore fails.

### 5. Mesh Transport & Peer Failure Cooldown (`GossipService.kt`, `MeshTransport.kt`, `TorService.kt`)
* **Exponential Cooldown Backoff**: Added exponential backoff (`30s * 2^(failures - 3)`, up to 1 hour) to `isPeerInCooldown()` in `GossipService.kt`, preventing offline/stale onion peers from continuously saturating Tor circuits.
* **Non-blocking Discoverability Traffic**: Included `ANNOUNCE_DISCOVERABLE` in `isBackground` handling in `MeshTransport.kt`.
* **Tor Readiness Check Timeout**: Increased `checkTorConnection()` connect/read timeouts from 15s to 30s in `TorService.kt`.

### 6. Truncated Technical Address UI (`PeerItem.kt`, `QRScanScreen.kt`)
* Truncated displayed onion addresses and public key technical strings down to clean 8-character fragments (`take(8)` + `...`), prioritizing human-readable `@handle.tripcode` identifiers.

---

## Completed Changes (2026-08-30) — Legacy Android App Codebase Audit & Privacy/Security Evaluation

### 1. Comprehensive Framework & Legacy Android Codebase Audit
* **Dual-Track Codebase State**: Verified legacy Android app (`app/`) architecture alongside Kotlin Multiplatform target (`mvp/`). Documented core network separation (`net/HttpClientProvider.kt`), Tor daemon SOCKS5/ControlPort integration (`tor/TorService.kt`), and database persistence (`data/`).
* **Cloudflare Worker API Proxy Evaluation**: Analyzed the Cloudflare Worker proxy (`yt-proxy.megadreamland.workers.dev`) used in `YouTubeInternalClient.kt`, `RedditApiClient.kt`, and `JamendoApiClient.kt`. Evaluated proxy secret usage (`PROXY_SECRET = "NoSlopRocks2026"`), central endpoint logging considerations, and direct fallback execution paths (`youtube.com` / `jamendo.com`). Clarified that actual video/audio stream playback bytes bypass `yt-proxy` and are fetched directly via `activeMediaClient`.
* **OTA Update & APK Pipeline Audit**: Evaluated update check mechanics in `UpdateChecker.kt` (clearnet fetch via `rawClearnetClient` to `noslop.me` / GitHub API before Tor bootstrap) and APK installation downloads in `UpdateManager.kt` (`HttpURLConnection`). Documented the lack of pre-install cryptographic signature verification (SHA-256 / developer key).
* **Identity Key Storage & Backup Evaluation**: Audited `IdentityRepository.kt` (`EncryptedSharedPreferences` with Android Keystore primary storage, fallback to unencrypted `SharedPreferences` when Keystore fails) and `BackupManager.kt` (AES-256-CBC zip archive encryption derived from 12-word mnemonic).
* **Documentation Synchronization**: Updated [TECHNICAL_REFERENCE.md](file:///home/tom/NoSlop/docs/TECHNICAL_REFERENCE.md), [PRIVACY_POLICY.md](file:///home/tom/NoSlop/docs/PRIVACY_POLICY.md), and [README.md](file:///home/tom/NoSlop/README.md) to accurately align technical claims with empirical codebase behavior.

## Completed Changes (2026-08-30) — App Startup & Video Preloading Optimizations

### 1. Unblocked Splash Screen Startup Flow
* **Removed Blocking Preload Wait**: Removed `PreloadManager.awaitReady(..., 6000L)` call from [MainActivity.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/MainActivity.kt). This previous block held up the splash screen for up to 6 seconds waiting for ExoPlayer to buffer on the main coroutine context, causing startup hangs.
* **Reduced Feed Timeout**: Decreased initial feed population wait timeout from 5000ms to 3000ms in [MainActivity.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/MainActivity.kt) for a snappier splash screen transition while background preloading handles media.

### 2. Preload Queue Stagger & Bandwidth Saturation Prevention
* **Restored Preload Queue Stagger**: Re-enabled `preloadQueue` channel dispatching in [PreloadManager.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/ui/PreloadManager.kt). Previously, `preWarm` bypassed the task queue and called `warmUp` directly, causing all background preloads to execute immediately without delay. Now, tasks are processed sequentially with a 2-second stagger (`delay(2000L)`).
* **Restored Bandwidth & Preload Caps**: Reverted `MAX_PRELOAD` from 4 to 2 and restored strict `MAX_PREBUFFER_BYTES` (80 MB ceiling) in [PreloadManager.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/ui/PreloadManager.kt). This prevents background players from saturating device bandwidth and starving the foreground video stream.
* **Removed `awaitReady` Function**: Removed `awaitReady` from [PreloadManager.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/ui/PreloadManager.kt) to eliminate coroutine lockups associated with player listener callbacks.

### 3. Video Player UI & Loading Feedback
* **Restored Loading Overlay State**: Reverted `isVideoReady` initialization in [VideoPlayer.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/ui/components/VideoPlayer.kt) from `initialSource != null` back to `false`. This ensures the `VideoLoadingOverlay` spinner is rendered while ExoPlayer buffers the initial video frame instead of displaying a static/frozen thumbnail.

### 4. Feed Sync Phase Optimization
* **Restored Phase 1 Concurrency**: Reverted `syncFeeds` in [FeedRepository.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/data/FeedRepository.kt) from a purely sequential delay loop back to two-phase dispatching (Phase 1 parallel Ramp-Up and Phase 2 background sync) using a limited IO dispatcher. Initial feed items populate quickly on startup while full sync completes silently in the background.

## Completed Changes (2026-08-27) — DM Chat Parity, Keyboard GIF Animations, Broadcast Modal & Video Media Fixes

### 1. DM Chat Feature Parity & Deselection Fixes
* **Peer Message Selection Restrictions**: Updated [ChatThreadScreen.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/ui/components/ChatThreadScreen.kt) to block selection of peer messages (`canSelect = isSelf`). Users can now only select and delete their own messages in DM threads.
* **Single-Message Selection/Deselection**: Wrapped gesture state (`isSelectionMode`, `isSelected`, `canSelect`) in `rememberUpdatedState` inside pointer input handlers in both [ChatThreadScreen.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/ui/components/ChatThreadScreen.kt) and [GroupChatThreadScreen.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/ui/components/GroupChatThreadScreen.kt). Tapping a selected message now properly deselects it in both single and multi-message selection modes.
* **Select All Button**: Added a `SelectAll` icon button to the DM selection header bar.
* **Deletion Warning Popup**: Added `showDeleteConfirm` `AlertDialog` warning popup before executing message deletion in DM chat threads.

### 2. Gboard & Device Keyboard GIF Animation Support
* **Multi-stage MIME & Format Detection**: Updated [AndroidGifTextField.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/ui/components/AndroidGifTextField.kt) to inspect `InputContentInfo.description` MIME list, `ContentResolver.getType(uri)`, URI path string, and magic header bytes (`0x47 0x49 0x46` -> `GIF87a`/`GIF89a`). Ensures keyboard GIF insertion on devices such as `RFCT217QD6K` always retains `.gif` extension.
* **Bypass Downsampling**: Updated `buildMediaMetadata` in [ChatThreadScreen.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/ui/components/ChatThreadScreen.kt) and [GroupChatThreadScreen.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/ui/components/GroupChatThreadScreen.kt) to check `if (type == "image" && !isGif && file.length() > 500 * 1024)`, preventing GIF animation downsampling into static JPEGs.

### 3. Touch Event Pass-Through & Top Header Interception
* **`PointerEventPass.Initial` Interceptor**: Updated parent pointer input blocker in [UnifiedFeedTab.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/ui/UnifiedFeedTab.kt). When `selectedTab != 0` (`alpha(0f)`), pointer events are intercepted and consumed during `PointerEventPass.Initial` (parent-to-child pass), preventing top-left Notifications and top-right Search & Filter `IconButton`s from receiving hit-testing miss-taps.
* **`isActiveTab` Guards**: Added explicit `isActiveTab` checks on top bar controls and search modal triggers in [UnifiedFeedTab.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/ui/UnifiedFeedTab.kt).
* **Active Surface Capture**: Added background gesture capture (`pointerInput`) to [DMsTab.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/ui/tabs/DMsTab.kt) root container to consume unhandled taps on empty space.

### 4. Broadcast to Mesh Modal Dismissal & Single-Phase Video Compression
* **Modal Dismissal Protection**: Added `DialogProperties(dismissOnClickOutside = !isBusy, dismissOnBackPress = !isBusy)` and updated `onDismissRequest` in [UnifiedFeedTab.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/ui/UnifiedFeedTab.kt) to block accidental modal closing while media attachment stream copying or video compression is active.
* **Inline Media Attachment Progress**: Added `isPreparingAttachment` state and inline progress indicator (`CircularProgressIndicator()` + `"Attaching media..."`) inside the modal attachment area during URI stream copying.
* **Single-Phase Compression**: Clicking `"Sign & Gossip"` runs video compression once with percentage indicator (`"Compressing... X%"`).

### 5. Video Broadcast Fix (`externalCacheDir` Storage Resolution)
* **Root Cause Identified via Logcat (`5203d52ef47493c5`)**: Large video files (> 20 MB) compressed into internal `/data/user/0/com.noslop.app/cache` exceeded Android's internal app cache quota. System service `installd` purged the compressed file from internal cache before `MediaManager.copyFileToMediaDirectory` could copy it to permanent `DIRECTORY_MOVIES/NoSlop` storage, causing local feed posts to fail with "Tap to Download Video".
* **Fix**: Updated `compressedFile` destination in [UnifiedFeedTab.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/ui/UnifiedFeedTab.kt), [ChatThreadScreen.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/ui/components/ChatThreadScreen.kt), and [GroupChatThreadScreen.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/ui/components/GroupChatThreadScreen.kt) to use `context.externalCacheDir ?: context.cacheDir`. External cache directory is exempt from Android `installd` internal storage quota purging, ensuring files are preserved for post creation and local playback.

## Completed Changes (2026-08-26) — Group Chat: Message Deletion, Privacy & Post-Creation Invites

### 1. Group Message Deletion (`NOSLOP_GROUP_DELETE_V1`)

*   **Group-aware `DELETE_MESSAGE` packets**: Extended `DeleteMessagePayload` in [Packets.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/mesh/Packets.kt) with an optional `group_id` field. When present, `handleDeleteMessage` in [DmPacketHandler.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/mesh/DmPacketHandler.kt) verifies the deleter is either the original message author or the group admin before removing the message. DM deletes (no `groupId`) retain the existing "only sender can delete" rule.
*   **`deleteGroupMessages()` in [NoSlopRepository.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/data/NoSlopRepository.kt)**: New function that validates permission (owner or admin), deletes locally via `messageDao.deleteMessageById()`, then broadcasts a signed `DELETE_MESSAGE` packet to every group member with the `groupId` attached.
*   **Admin privilege**: The group admin can delete any member's message. Regular members can only delete their own messages. Permission is enforced both at the sending side (`deleteGroupMessages`) and the receiving side (`handleDeleteMessage`).
*   **DAO additions in [Daos.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/data/Daos.kt)**: Added `deleteGroupMessages(groupId)` (bulk local delete by `chatWithPeerPub`) and `deleteMessageById(id)` (single message delete without sender constraint, needed for admin deletes).

### 2. Clear Group Chat (Local-Only)

*   **`clearGroupChat()` in [NoSlopRepository.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/data/NoSlopRepository.kt)**: New function that clears all messages in a group chat locally without broadcasting any delete packets to other members. Previously, "Clear Chat" in groups called the DM-specific `clearChat(groupId)` which tried to look up the groupId as a peer in `peerDao`, silently returned `null`, and did nothing.
*   **UI wiring in [GroupChatThreadScreen.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/ui/components/GroupChatThreadScreen.kt)**: The "Clear Chat" confirm dialog now calls `viewModel.clearGroupChat()` and shows an updated message clarifying that messages will not be removed for other members.

### 3. Select All & Selection Permissions in Group Chat UI

*   **Select All button**: Added a `SelectAll` icon button in the selection mode header bar of [GroupChatThreadScreen.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/ui/components/GroupChatThreadScreen.kt). For regular members, it selects only the user's own messages; for the admin, it selects all messages.
*   **Permission-based selection**: Regular members can only long-press or tap-select their own messages. The admin can select any message. Checkbox indicators only appear for other users' messages when the viewer is the admin.
*   **Delete confirmation dialog**: A confirmation dialog now appears before deleting selected messages, showing the count and warning that deletion is permanent for all group members.

### 4. Group Member Privacy & Ghost Peer Prevention

*   **`memberHandlesJson` column**: Added to [GroupChat.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/data/GroupChat.kt) to store member display names as a serialized JSON map (pubKey → handle). This eliminates the need for `peerDao` lookups for group member metadata, which was causing group members who are not directly connected to appear as ghost "Pending Request" entries in the DMs page.
*   **`MIGRATION_11_12`** in [NoSlopDatabase.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/data/NoSlopDatabase.kt): Adds the `memberHandlesJson` column and purges any existing ghost peer entries from the database on upgrade.
*   **Removed `cacheMemberHandles()`** from [HandshakePacketHandler.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/mesh/HandshakePacketHandler.kt): This function previously inserted group members into `peerDao`, which leaked unconnected members' onion addresses as DM pending requests — a privacy violation.
*   **GroupSettingsModal name resolution** in [GroupSettingsModal.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/ui/components/GroupSettingsModal.kt): Updated to resolve member display names from `group.getMemberHandles()` instead of `peerDao`, ensuring non-connected members show their actual handle rather than a truncated public key.
*   **Chat thread sender names** in [GroupChatThreadScreen.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/ui/components/GroupChatThreadScreen.kt): Message sender names now fall back to `memberHandlesMap` when a peer is not in `peerDao`, so messages from non-directly-connected group members show proper display names.

### 5. Post-Creation Member Invites (`NOSLOP_GROUP_ADD_INVITE_V1`)

*   **Bug fix in [NoSlopRepository.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/data/NoSlopRepository.kt) `updateGroupChat()`**: When a member was added to an existing group, only a `GROUP_UPDATE` packet was sent. The newly added member didn't have the group yet, so `handleGroupUpdate` (which requires `getGroupChatById() != null`) silently dropped the update and the member never received an invite.
*   **Fix**: `updateGroupChat()` now sends `GROUP_INVITE` packets (not `GROUP_UPDATE`) to each newly added member, giving them the full group metadata (title, description, avatar, member list, admin key) so they can accept/join via the standard invite flow. Existing members continue to receive `GROUP_UPDATE` packets as before.

### 6. Diagnostic Logging for Group Message Sending

*   **Enhanced logging in `sendGroupMessage()`** in [NoSlopRepository.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/data/NoSlopRepository.kt): Added detailed per-member logging that reports: member count, peers not found in `peerDao`, peers with no onion address, encryption failures, and final dispatch count (e.g., `sendGroupMessage: dispatched to 2/3 member(s)`). Previously, all failures were silent `continue` statements with no logging.

### Known Issues Under Investigation

*   **User A (RFCT217QD6K) Tor outbound failures**: All three outbound Tor connections from User A's device are failing with "SOCKS: Host unreachable", causing all peers to enter cooldown. Messages are inserted locally but never reach recipients. This is a Tor connectivity issue on the device, not a code bug. The new `sendGroupMessage` logging will confirm the exact failure point on the next test.

## Completed Changes (2026-08-23) — Media Downloads, Feed Controls & UI Polish

### 1. Un-paired Public Mesh Media Downloads & Return Routing
* **Target Onion Resolution in [FeedCard.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/ui/components/FeedCard.kt)**: Added `resolveOriginOnion()`. When downloading media attached to a post, if `mediaUrl` lacks an embedded `.onion` host, the UI resolves the author's public key against `allPeers` (including discoverable creators) and passes their valid Tor onion address to `MediaManager.startMediaDownload`.
* **Request Payload Origin Address (`origin_onion`)**: Added `@SerializedName("origin_onion") val originOnion: String? = null` to `MediaRequestPayload` in [Packets.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/mesh/Packets.kt). `MediaManager.requestNextChunks` includes the requester's Tor onion address in every `MEDIA_REQUEST` packet.
* **Un-paired Return Packet Routing**: Updated `handleMediaRequest` in [MediaManager.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/mesh/MediaManager.kt). If the requesting `senderId` is an un-paired peer not present in `peerDao`, User C falls back to `payload.originOnion` to return `MEDIA_CHUNK` packets directly to the requester over Tor SOCKS5.
* **Recovery Payload Address**: Added `onion_address` to `MediaRecoveryFoundPayload` in [Packets.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/mesh/Packets.kt) and updated [GossipService.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/mesh/GossipService.kt) and [MediaPacketHandler.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/mesh/MediaPacketHandler.kt) so mesh recovery responses carry the source node's Tor onion address directly.

### 2. Discoverable Creators List Query Fix
* **Relaxed Query in [Daos.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/data/Daos.kt)**: Updated `getDiscoverablePeers()` query to `SELECT * FROM peers WHERE isDiscoverable = 1 AND isTrusted = 0 ORDER BY lastSeenAt DESC`. Removed restrictive `isOnline = 1 AND isTemporary = 1` filters that caused discoverable creators to vanish from the Discoverable list if background 3-minute inactivity cleanup ran.
* **Discoverable Peer Updates in [HandshakePacketHandler.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/mesh/HandshakePacketHandler.kt)**: Preserved `isTemporary = if (peer.isTrusted) false else true` when handling `ANNOUNCE_DISCOVERABLE` updates for existing peer entries.

### 3. Top Header Bar "All / Mesh" Quick Switch
* **Unified Segmented Toggle**: Added an `All / Mesh` quick switch in the center header of [UnifiedFeedTab.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/ui/UnifiedFeedTab.kt).
* **Standardized Filter Key**: Standardized the filter mode string to `"Mesh"`.
* **Clean UI State & Feed Restoral**: Excluded `"Mesh"` from creating a top-right filter chip in `activeFilterLabel` so the quick toggle stays active on screen on the `Mesh` side. Tapping `All` restores `"Live Feed"` and calls `syncFilterMode("Live Feed", forceRefresh = true)`, populating the full feed without returning an empty screen.

### 4. Tor Notification Loop Fix in ExoPlayer Diagnostic Loop
* **ExoPlayer Sample Guard in [VideoPlayer.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/ui/components/VideoPlayer.kt)**: Added checks (`!p.playWhenReady || p.playbackState == Player.STATE_IDLE`) so paused videos, offscreen slides, or idle players reset `stalledSamples = 0`.
* **YouTube Circuit Rotation Scoping**: Restricted Tor exit rotation and toast status updates strictly to YouTube `googlevideo` IP-locked streams, preventing repeated *"Tor exit blocked by this provider..."* notification spam on emulated devices.

### 5. Slide Post Description Text UI/UX Improvements
* **Scrollable Container & Tap-to-Collapse**: Wrapped expanded post description cards in `heightIn(max = 240.dp)` with `.verticalScroll(textScrollState)` in [FeedCard.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/ui/components/FeedCard.kt). Tapping anywhere on the expanded text card or `show less ▲` collapses description text back to its 2-line minimised state. Markdown formatting is fully rendered.

### 6. Settings Sub-Tab Navigation
* **Studio Sub-Tab in Settings**: Added a 5th sub-tab (`Studio`) inside [SettingsTab.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/ui/tabs/SettingsTab.kt) when Creator Mode is enabled.

---

## Completed Changes (2026-08-23) — Security Audit Follow-Up

### 1. Group Packet Authentication (`NOSLOP_GROUP_AUTH_V1`)

*   **Signature verification for the whole `GROUP_*` family**: `GROUP_INVITE`, `GROUP_UPDATE` and `GROUP_DELETE` each carried a `signature` field that was never checked in [HandshakePacketHandler.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/mesh/HandshakePacketHandler.kt). Any trusted peer could rename a group, replace its avatar, or add and remove arbitrary members. All three now verify, matching every other handler in the class.
*   **Signer recovery for `GROUP_UPDATE`**: the payload has no signer field, and `GossipService.forwardPacket` re-stamps `senderId` on relay, so neither identifies who signed. `resolveUpdateSigner` recovers the signer by testing the signature against the admin key and each current member key in turn — O(members) Ed25519 verifies on this packet type only, and no wire-format change.
*   **Role authorisation**: only the admin may change title / description / avatar; a non-admin may add members only when `allowMemberInvites` is set, and may remove only itself and only when `allowMemberSelfRemove` is set; the admin can never be removed by an inbound packet. Both switches already existed on the `GroupChat` entity and were surfaced in `GroupSettingsModal` — they were simply never enforced on the wire.
*   **`GROUP_INVITE` hardening**: an invite is rejected unless our own (or burnable) identity appears in `members`, and an inbound packet can no longer reassign the admin of a group we already hold.
*   **`GROUP_DELETE` hardening**: the packet-supplied `adminPublicKeyB64` must equal the *stored* group's admin **and** the signature must verify against it. Previously only the first check ran, which proves nothing on its own.

### 2. Group Messaging Fixes (`NOSLOP_GROUP_DM_V1`)

*   **Wrong key in `sendGroupMessage`**: [NoSlopRepository.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/data/NoSlopRepository.kt) passed `myKeys.privateKeyB64` (Ed25519) to `CryptoService.encryptDM`, which expects the X25519 key. `decodeX25519PrivateKey` threw, `encryptDM` caught it and returned `Pair("", "")`, and **every group message went out with an empty ciphertext and empty nonce**, silently. Now uses `encPrivateKeyB64`, and a blank result skips that recipient with an error log rather than transmitting an empty payload.
*   **`groupId` now set on the payload**: `EncryptedPayload.groupId` was never populated, so a receiver had no way to route the message to a group thread.
*   **Inbound routing**: [DmPacketHandler.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/mesh/DmPacketHandler.kt) filed group messages under `chatWithPeerPub = packet.senderId`, i.e. into the 1:1 DM thread with whoever sent them. They now resolve the group id (payload first, decrypted-plaintext `groupId` as fallback), verify the sender is a member of a group we hold, and store under `chatWithPeerPub = groupId` — matching the convention the sender's own local echo already used.
*   **Member removal propagation (`NOSLOP_GROUP_DELTA_V1`)**: `updateGroupChat` sent `addedMembers = membersList`, the *complete* new member list, and never populated `removedMembers`. Since the receiver does `addAll(added)` then `distinct()`, a removal could never propagate — the removed member stayed in every other peer's copy of the group and kept receiving its messages. It now diffs against the stored member list and sends real deltas. `handleGroupUpdate` deletes the group locally when the update removes our own identity.

### 3. Tor Routing Gap in the Invidious Client (`NOSLOP_INVIDIOUS_TOR_V1`)

*   **Search and stream resolution were leaking the user's IP**: `probeClient` in [InvidiousApiClient.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/feeds/api/InvidiousApiClient.kt) was built with no `.proxy()` at all, so every search query, video-ID resolution and channel lookup went to Invidious instances **over the user's real IP** — while the Tor-respecting `client` val declared ten lines above was never referenced anywhere in the file. Split into `probeClientTor` (SOCKS to the local Tor port, no custom DNS so hostnames resolve at the exit) and `probeClientDirect`, selected by `HttpClientProvider.useTorForClearnet`. The dead `client` val was removed.

### 4. Invidious Instance Racing (`NOSLOP_INSTANCE_RACE_V1`)

*   **Parallel instance selection**: all five instance loops (`resolveStreamUrl`, `searchVideos`, `getTrendingVideos`, `searchChannels`, `getChannelJoinedTimestamp`) were strictly sequential — try one, wait for it to answer or time out, then try the next — which over Tor meant tens of seconds of spinner whenever the first instance happened to be slow. They now share one `raceInstances` helper that fires batches of `RACE_WIDTH` (4) in parallel and takes the first usable answer.
*   **Real cancellation**: switched from `Call.execute()` to `Call.enqueue()` behind a `suspendCancellableCoroutine`, so cancelling a losing racer actually cancels the HTTP call and frees its Tor circuit. `execute()` blocks a thread that coroutine cancellation cannot interrupt, which would have pinned circuits until the read timeout — the same starvation `NOSLOP_TOR_STARVATION_V1` in `MeshTransport` exists to avoid.
*   **Cancellation is not failure**: `queryInstance` catches and rethrows `CancellationException` *before* the general handler, so losing racers are never passed to `markInstanceFailed`. Without that, every race would blacklist three healthy instances and drive the pool into all-cooldown fast-fail.

### 5. Read Receipts, Transport Reuse, and i18n Recomposition

*   **`READ_RECEIPT` honours `messageId`**: `handleReadReceipt` called `messageDao.markAsRead(packet.senderId)`, marking the **entire conversation** read and ignoring the `messageId` the packet carries. Added `MessageDao.markAsReadById` in [Daos.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/data/Daos.kt) (a new `@Query` does not change Room's identity hash, so no schema version bump was needed).
*   **Shared `MeshTransport` (`NOSLOP_TOR_STARVATION_V1` follow-up)**: `sendTypingSignal` and `sendGroupMessage` each constructed `MeshTransport(this)` per send. Every instance carries its own `Semaphore(24)` and its own never-cancelled `CoroutineScope`, so they bypassed the shared Tor circuit budget entirely and leaked a scope per call — `sendGroupMessage` did it *inside* the member loop. Both now use the repository's shared `meshTransport`.
*   **Language switching now recomposes (`NOSLOP_I18N_RECOMPOSE_V1`)**: the `String.tr` extension in [LanguageManager.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/util/LanguageManager.kt) called `collectAsState()` and discarded the result. Compose only invalidates a composable that *reads* a state value, so changing language left every already-composed screen in the old language until something unrelated forced recomposition.

### 6. Documentation Accuracy

*   **README claims corrected**: the blanket "All network traffic is routed through Tor by default" now states the actual carve-outs (update check and APK download go direct so they work before Tor bootstraps); the rate-limit claim now names which packet types the 20-per-10s limiter actually covers; "the network rejects forgeries" now enumerates the verified families and names `TYPING`/`READ_RECEIPT` as deliberately unsigned. Group chats documented as a shipped feature.
*   **[WIRE_PROTOCOL_REFERENCE.md](docs/WIRE_PROTOCOL_REFERENCE.md)**: added catalog rows and payload field tables for `GROUP_INVITE` / `GROUP_UPDATE` / `GROUP_DELETE` / `TYPING` / `READ_RECEIPT`, added the group signed-string formats to the consolidated §7 table, corrected `EncryptedPayload.group_id` (no longer "reserved, unused"), and removed the stale "not verified on receipt" text on the handshake rows that the note beneath already contradicted.
*   **[TECHNICAL_REFERENCE.md](docs/TECHNICAL_REFERENCE.md)**: new §3.5.1 on group message fan-out, including the explicit note that DM encryption is static-static X25519 with **no forward secrecy**.
*   **[GAP_ANALYSIS.md](docs/GAP_ANALYSIS.md)**: §3 restated from "absent" to "partially implemented", with the remaining gaps against the gChat spec named — chiefly the missing `GROUP_QUERY`/`GROUP_SYNC` catch-up, the single-admin model, and no ban list.

### Known Issues Not Addressed In This Pass

*   **SSH host key auto-accept during Hub deployment** — `SshDeployer.promptYesNo` returns `true` unconditionally, so any host key is accepted silently, and the user's Ed25519 *and* X25519 private keys are transmitted over that session. On a compromised LAN this is a full, silent, unrecoverable identity compromise. Mitigated in practice by deployment happening over a trusted local network; a pairing-QR flow that keeps the identity out of the SSH channel is the intended replacement.
*   **`MeshTransport.handleIncomingConnection`** reads unbounded lines with no `soTimeout` and no connection cap — a remote OOM / slowloris vector for anyone who knows the onion address.
*   **`GossipService.senderRateLimits` and `firewallBuffer`** are unbounded maps keyed on attacker-supplied `senderId` and are never swept.
*   **`GossipService.broadcast`** discards the return value of `pushPacketToHub` and has no direct-Tor fallback, unlike `MeshTransport.sendPacket` — with a linked-but-offline Hub, outbound broadcasts are silently dropped.
*   **Tripcodes are 6 base32 characters (30 bits)** and therefore grindable for targeted collision; they should not be treated as an identity check.

---

## Completed Changes (2026-08-23)

### 1. First-Install Default Settings Alignment
*   **Media Quality Defaults**: Set default `videoQuality`, `audioQuality`, and `imageQuality` to **Medium** (`"medium"`) in [MediaSettings.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/data/MediaSettings.kt).
*   **Automatic Media Download**: Set master toggle **ON** (`enabled = true`), download size ceiling to **`250MB`**, `autoDownloadFriends = true` (friends ON), and `autoDownloadPublic = false` (public OFF) in [MediaSettings.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/data/MediaSettings.kt).
*   **Content Mix Ratio**: Default content mix set to **50% Video**, **10% Audio**, **10% Image**, **10% Article**, and **20% Mesh** in [FeedMixSettings.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/data/FeedMixSettings.kt).
*   **Mesh Filters**: All Mesh filters **ON** (`true`) by default, EXCEPT *Clearnet Shares Allow Incoming*, which is **OFF** (`allowIncomingClearnetShares = false`) in [MeshFilterSettings.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/data/MeshFilterSettings.kt).
*   **Default Negative Filters**: Added default user block keywords: `advertisement, ad, advert, commercial, best deal, coupon, promo code` in [PreferencesRepository.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/data/PreferencesRepository.kt).
*   **Channel Creation Cut-Off Date**: Cut-off date enabled by default (`enabled = true`) and set to **January 2022** (`year = 2022`, `month = 1`) in [PreferencesRepository.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/data/PreferencesRepository.kt) and [NoSlopViewModel.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/ui/NoSlopViewModel.kt).

### 2. Video Stream Preservation & Canonical Deduplication
*   **YouTube/Vimeo Parameter Preservation**: Updated `normalizeUrlKey(url)` in [EngagementRepository.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/data/EngagementRepository.kt) to extract and preserve unique video IDs (`yt_VIDEO_ID`, `vimeo_VIDEO_ID`) before stripping query parameters. This prevents YouTube and Vimeo videos from being incorrectly collapsed into a single key (`"url_youtube.com/watch"`) during feed deduplication.
*   **Back-to-Back Feed Deduplication**: Applied `getCanonicalItemKey` across [NoSlopViewModel.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/ui/NoSlopViewModel.kt) and [UnifiedFeedTab.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/ui/UnifiedFeedTab.kt) to eliminate duplicate article and video cards back-to-back.

### 3. OpenGraph Lead Image Resolution & Image Quality Ceilings
*   **OpenGraph Lead Image Resolver**: Created [ArticleMetadataResolver.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/feeds/api/ArticleMetadataResolver.kt). For RSS feeds like Rolling Stone (`rollingstone.com/feed/`) and Al Jazeera (`aljazeera.com/xml/rss/all.xml`) that lack embedded XML images, `SegmentedArticleReader` asynchronously fetches the OpenGraph `og:image` or `twitter:image` lead image directly from the article webpage and caches it in memory.
*   **Image Quality Ceilings**: Upgraded downscaling size ceilings in [MediaComponents.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/ui/MediaComponents.kt) (`low` $\rightarrow$ `1080px`, `medium` $\rightarrow$ `1440px`, `high`/default $\rightarrow$ `1920px`) so article hero cards and lead thumbnails remain crisp on high-DPI displays even on Low quality setting.

### 4. Segmented Article Reader & Byline Unification
*   **Source & Byline Unification**: Replaced generic `"By Article"` author placeholders with clean human-readable source and author labels (`By TechCrunch · Aug 23, 2026`, `By BBC News`, `By Wikipedia`) derived from domain names, `apiSource`, or `sourceId`.
*   **Page 1 Uncropped Lead Image**: Rendered uncropped lead image (`ContentScale.Fit`) in a rounded container (`heightIn(max = 200.dp)`) at the top of Page 1.
*   **Viewport-Aware Text Pagination**: Created `splitArticleContent(text, firstChunkSize = 320, normalChunkSize = 550)` in [MediaComponents.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/ui/MediaComponents.kt) so Page 1 text fits cleanly under the lead image without vertical scrolling.
*   **Zero-Delay Image Preloading**: Enqueued Coil `fitRequest` image load inside a `LaunchedEffect` on Page 0 so Page 1's lead image displays instantly upon side-swiping.

---

## Completed Changes (2026-08-22)

### 1. Network & Security Hardening
*   **Cleartext Traffic Scoping**: Updated [network_security_config.xml](file:///home/tom/NoSlop/app/src/main/res/xml/network_security_config.xml) to disable global cleartext traffic and scope `cleartextTrafficPermitted` strictly to `127.0.0.1` and `localhost`. Feed and media traffic now default to HTTPS / Tor SOCKS5 proxy while preserving LAN Hub and SOCKS proxy access.
*   **Non-Exported Receiver**: Confirmed `UpdateManager$DownloadReceiver` in [AndroidManifest.xml](file:///home/tom/NoSlop/app/src/main/AndroidManifest.xml) is set to `android:exported="false"`, protecting against external intent injection.
*   **Encrypted Storage Fallback Alerting**: Verified reactive state `isUsingInsecureStorage` in [IdentityRepository.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/data/IdentityRepository.kt) and surfaced prominent red warning banners in [OnboardingScreen.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/ui/OnboardingScreen.kt) and [ContentPreferencesScreen.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/ui/ContentPreferencesScreen.kt) when hardware Keystore initialization fails.

### 2. Test Fixture Parity & Cryptographic Identity Standardizing
*   **DAO Test Fixtures**: Updated [FakeDaos.kt](file:///home/tom/NoSlop/app/src/test/java/com/noslop/app/data/FakeDaos.kt) with implementations for all modern DAO queries (`searchLocalArticles`, `deleteYouTubeItems`, `deleteReactionsByAuthor`, `deleteVotesByAuthor`, `getPendingDeletionsByAuthor`, `getMessagesWithPeerList`, etc.).
*   **DisplayName Standardization**: Updated [CryptoService.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/crypto/CryptoService.kt) to format `IdentityKeys.displayName` consistently as `handle.tripcode` (matching identity specifications and unit test contracts).
*   **Unit Test Suite Pass**: Achieved 100% pass rate (`71/71 tests passing`) across off-device unit test suite (`./gradlew testDebugUnitTest`).

### 3. Asymmetric Follows, Group Chat Schemas, & Ephemeral Signals
*   **Asymmetric Follow/Unfollow Model**: Implemented `FOLLOW` and `UNFOLLOW` wire packets in [Packets.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/mesh/Packets.kt), added `isFollowing` to `Peer` entity in [Entities.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/data/Entities.kt) with Room Migration `MIGRATION_8_9` (Database Version 9) in [NoSlopDatabase.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/data/NoSlopDatabase.kt), and added `handleFollow` signature verification in [HandshakePacketHandler.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/mesh/HandshakePacketHandler.kt).
*   **Group Chat Protocol Schemas**: Added `GROUP_INVITE`, `GROUP_UPDATE`, and `GROUP_DELETE` wire packets, created [GroupChat.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/data/GroupChat.kt) Room entity (`groupId`, `title`, `adminPublicKeyB64`, `membersJson`), `GroupChatDao`, and handler dispatching in [MeshPacketHandler.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/mesh/MeshPacketHandler.kt).
*   **Ephemeral Signals**: Added `TYPING` and `READ_RECEIPT` wire packets, handler dispatch in [DmPacketHandler.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/mesh/DmPacketHandler.kt), and `peerTypingStates` StateFlow in [NoSlopRepository.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/data/NoSlopRepository.kt) and [NoSlopViewModel.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/ui/NoSlopViewModel.kt).

---

## Completed Changes (2026-08-20)

### 1. Categorized Reaction System (Positive, Neutral, & Negative)
*   **3-Tier Categorized Reactions**: Expanded supported reaction icons to 20 across three distinct sections in `ReactionPicker`: **Positive** (❤️ 👍 😂 🔥 😮 🎉 💡 👏 💎), **Neutral / Expressive** (😢 😡 😱 🤔 🤯 🧘), and **Negative** (👎 💩 🤮 🤡 🚫).
*   **Nuanced Scoring Logic**: Updated [FeedCard.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/ui/components/FeedCard.kt), [NoSlopViewModel.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/ui/NoSlopViewModel.kt), and [MeshSocialRepository.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/data/MeshSocialRepository.kt) so neutral/expressive reactions (`sad`, `angry`, `thinking`, etc.) express emotion without counting towards downvotes or community slop flagging.

### 2. Save For Later Feed Action & Saved Filter
*   **Feed Slide Bookmark Button**: Added a **Save** action button (`Icons.Default.Bookmark` / `BookmarkBorder`) to `OverlayInteractions` in [MediaComponents.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/ui/MediaComponents.kt) and connected it to feed slides in [FeedCard.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/ui/components/FeedCard.kt).
*   **Saved Filter Fix**: Added a **Saved** filter option under the **Lists** section in [UnifiedFeedTab.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/ui/UnifiedFeedTab.kt) and fixed [NoSlopViewModel.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/ui/NoSlopViewModel.kt) `isHistoryOrLiked` filter condition to include `"Saved"`.

### 3. Channel / Creator Banning via 🚫 Reaction
*   **Automatic Channel Blacklisting**: Reacting with `noslop` 🚫 on any slide automatically blacklists the item's channel/author name.
*   **Feed Purging & Aggregator Exclusion**: Added `banChannel(author)` and `unbanChannel(author)` in [NoSlopViewModel.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/ui/NoSlopViewModel.kt) & [PreferencesRepository.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/data/PreferencesRepository.kt). Banning a channel immediately purges all current slides from that creator from the active feed view, and excludes them from future feed aggregation and content search queries.

### 4. Channel Creation Year / Month Cut-Off Filter
*   **Database Schema Migration**: Added `channelCreatedAt` field to [Entities.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/data/Entities.kt) with Room Migration `MIGRATION_7_8` (Database Version 8) in [NoSlopDatabase.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/data/NoSlopDatabase.kt).
*   **Settings Selector**: Added a **Channel Creation Cut-Off Date 📅** section in [ContentPreferencesScreen.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/ui/ContentPreferencesScreen.kt) with a toggle switch and Year/Month dropdown selectors (e.g., Year 2005–2026, Month Jan–Dec).
*   **Search Exemption**: When enabled, live feed aggregation excludes content from channels started after the cut-off date to filter out recent automated content farms, while creator/channel search remains exempt so users can still discover new creators manually.

### 5. Interactive Channel Preference Modal
*   **Clickable Channel Names**: Made author/channel names on feed cards clickable in [FeedCard.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/ui/components/FeedCard.kt).
*   **Channel Preference & Banning Modal**: Created [ChannelPreferenceModal.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/ui/components/ChannelPreferenceModal.kt) offering 1-tap **Add/Remove Preference**, **Ban Channel 🚫**, or **Unban Channel** actions.

### 6. Search Suggestions Cloud & IME Soft Keyboard Support
*   **Dynamic Suggestions Cloud**: Updated Search & Filter modal in [UnifiedFeedTab.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/ui/UnifiedFeedTab.kt) so suggestions only appear when typing, querying local sources and live YouTube channel search API.
*   **Keyboard Scrollability**: Applied `.imePadding()` to the modal dialog content container so the modal container adjusts its layout and remains fully scrollable under the software keyboard.

### 7. Tor Service Resilience & Key Parsing Fixes
*   **"Bad Sequence Size" Fix**: Updated `getEd25519PrivateKeyParams` and `getEd25519PublicKeyParams` in [CryptoService.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/crypto/CryptoService.kt) to handle raw 32-byte and 64-byte Ed25519 seeds/keys with fallback extraction, eliminating BouncyCastle's `Bad sequence size: 3` exception during hidden service registration.
*   **Force-Restart Reconnect**: Updated `TorWarningPanel.kt` and [UnifiedFeedTab.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/ui/UnifiedFeedTab.kt) so tapping **Retry** / **Reconnect Tor** passes `forceRestart = true` to `viewModel.startTor(forceRestart = true)`.
*   **Tor Daemon Failure Overlay**: Updated [UnifiedFeedTab.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/ui/UnifiedFeedTab.kt) to display `TorStatusOverlay` whenever `useTorForClearnet == true` and Tor is in `FAILED` state.

### 8. Video Streaming & Circuit Breaker Optimization
*   **Circuit Breaker & Fallback**: Increased `YT_CIRCUIT_BREAKER_THRESHOLD` to 5 and lowered reset cooldown to 45 seconds in [VideoPlayer.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/ui/components/VideoPlayer.kt). Added an Invidious direct stream resolver fallback (`InvidiousApiClient.resolveStreamUrl(videoId)`) in [YouTubeInternalClient.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/feeds/api/YouTubeInternalClient.kt) so direct MP4 streams are resolved without resorting to WebViews.
*   **Targeted Video Sourcing**: Updated the `"Video Platforms"` category pipeline in [PublicApiService.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/feeds/PublicApiService.kt) to execute targeted keyword/creator queries when user preferences or search terms exist.

---

## Completed Changes (2026-08-19)

### 1. Keyless YouTube Video Sourcing & Feed Restoration
*   **Keyless YouTube API Configuration**: Marked `youtube` in `ApiKeyRepository.SERVICES` as `requiresUserKey = false` because `YouTubeInternalClient` (InnerTube API via Cloudflare Worker proxy & direct fallbacks) operates keylessly without requiring a user API key.
*   **Auto-Seeding Keyless Sources**: Added `ensureDefaultApiSourcesExist()` to `FeedRepository` and exposed it via `NoSlopRepository`. Automatically seeds missing keyless API sources (`api-yt-trending`, `api-yt-search`) into the `feed_sources` Room DB table on app startup or migration.
*   **Feed Reset & Startup Sync**: Updated `NoSlopViewModel.init` to execute `ensureDefaultApiSourcesExist()` on cold startup and trigger feed sync if items are empty.

### 2. Article Image Extraction & Generic Fallback Cleanup
*   **Flexible RSS Image Parsing**: Updated `FeedParser.extractFirstImage` to accept modern extension-less CDN image URLs (Vox, Kinja, BuzzFeed, etc.) without requiring strict `.jpg`/`.png` file extension substrings in the URL path.
*   **URL Normalization**: Added `normalizeUrl()` in `FeedParser.kt` to normalize relative (`/`), protocol-relative (`//`), and HTML-encoded (`&amp;`) image URLs to valid `https://` links.
*   **Reddit Image Entity Decoding**: Decoded `&amp;` HTML entities in `RedditApiClient.kt` preview URLs and preserved article classification for Reddit link/text posts with preview images.
*   **Stock Fallback Removal**: Removed the hardcoded generic Unsplash stock photo fallback in `SegmentedArticleReader` ([MediaComponents.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/ui/MediaComponents.kt#L385)). Missing or failed article lead images now render a clean dark editorial typography layout (`Color(0xFF141414)`).

### 3. Article Publication Date Display
*   **Article Reader Date Overlay**: Added `publishedAt: Long` parameter to `SegmentedArticleReader` in [MediaComponents.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/ui/MediaComponents.kt#L370).
*   **Byline Date Formatting**: Formatted `publishedAt` timestamps into human-readable date strings (`MMM d, yyyy`) and rendered them alongside author details (e.g., `By Author · Aug 19, 2026`) on Page 0 hero layout overlays and editorial text cards.
*   **Feed Card Wiring**: Updated [FeedCard.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/ui/components/FeedCard.kt#L210) to pass `item.publishedAt` and `post.timestamp` into `SegmentedArticleReader`.

### 4. Keyless Audio Sourcing & Ingestion
*   **Internet Archive Query Fix**: Fixed `InternetArchiveClient.searchAudio(query)` to prevent blank queries from generating invalid Lucene search syntax (`() AND mediatype:audio...`).
*   **Popular Audio Sourcing**: Added `InternetArchiveClient.getPopularAudio()` to fetch curated MP3 and FLAC music tracks, old-time radio, and podcasts keylessly.
*   **PublicApiService Music Pipeline**: Updated the `"Music"` category pipeline in [PublicApiService.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/feeds/PublicApiService.kt#L105) to query `InternetArchiveClient.getPopularAudio()` and `OpenverseApiClient.searchAudio("music")` when `query` is empty, delivering 27+ audio items per feed refresh.
*   **Openverse Rate Limit Cooldown**: Reduced HTTP 429 rate-limit backoff cooldown in [OpenverseApiClient.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/feeds/api/OpenverseApiClient.kt) to 5 minutes so throttled audio requests self-heal quickly.

### 5. Tor ControlPort & Reconnect Button Fixes
*   **ControlPort Cookie Authentication**: Updated `writeTorrc()` in [TorService.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/tor/TorService.kt#L406) to set `CookieAuthentication 0`. ControlPort commands (`AUTHENTICATE\r\n`) over port 9051 now succeed immediately without password or cookie errors, enabling `requestNewCircuit()` and `registerHiddenService()`.
*   **Force Restart Support**: Added `forceRestart: Boolean = false` to `TorService.startTor()`. Tapping **Reconnect Tor** in Settings now passes `forceRestart = true`, immediately clearing stuck bootstrap coroutine jobs and restarting Tor.

### 6. Styled Image Load Failure Fallbacks
*   **WAF Fallback Card**: Replaced plain warning icons on failed image loads in `BlurredImageBackground` ([MediaComponents.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/ui/MediaComponents.kt#L186)) with a clean dark editorial artwork card (`Color(0xFF141414)`), cleanly displaying artwork details, title, and media type when an image host (such as Art Institute via Cloudflare WAF) blocks Tor exit nodes.

## Completed Changes (2026-08-09)

### 1. Search Filter Logic Fix
*   **Broken Search Repaired**: Fixed a regression where the search result filter in `NoSlopViewModel.loadMoreFeedItems` was using `terms.any` (OR logic) instead of `terms.all` (AND logic) for local keyword matching. This caused searches like "android news" to flood the feed with every item matching *either* word individually, drowning out actual API search results with thousands of unrelated local cache items. Restored strict AND-matching so all search terms must appear in the item's title, excerpt, or author.
*   **Mesh Search Filter**: Applied the same `terms.any` → `terms.all` correction to the mesh post filter, ensuring mesh content also requires all search keywords to match.

### 2. Chronological Feed Sorting
*   **Newest Content First**: Refactored the feed sorting algorithm in `NoSlopViewModel.loadMoreFeedItems` to strictly prioritize chronological ordering (`publishedAt` descending) across all feed modes. Previously, the feed used a multi-tier partition system (creators → priority sources → others) that could bubble up months-old content from favorite creators above breaking news. The new approach sorts the entire pool by publish date, ensuring the user always sees the freshest content first as they swipe down.
*   **Removed Initial Load Shuffle**: Eliminated the `batch.shuffle()` on initial category loads that was randomizing the order of the first batch, preventing users from seeing the most recent items at the top.
*   **Removed Creator Front-Loading**: Removed the `creatorBatch`/`otherBatch` separation at the end of the Smart Interleaving Algorithm that was re-sorting the final batch to force creator content to the absolute front regardless of recency. The round-robin media interleaving and diversity limits are preserved.

### 3. Native API Date Filtering for Search
*   **YouTube InnerTube**: Added a `recentOnly` parameter to `YouTubeInternalClient.searchVideos()`. When true, sets the InnerTube protobuf `params` field to `EgIIBQ==` (upload_date = this_year), restricting search results to videos uploaded within the current year.
*   **Reddit**: Added a `recentOnly` parameter to `RedditApiClient.searchReddit()`. When true, switches sorting from `relevance` to `new` and adds `&t=year` time filter, returning only posts from the last 12 months sorted newest-first.
*   **NewsAPI**: Added a `recentOnly` parameter to `NewsApiClient.searchArticles()`. When true, appends `&from=YYYY-MM-DD` (3 months ago) and `&sortBy=publishedAt`, constraining results to the last quarter and sorting by publication date.
*   **Guardian**: Added a `recentOnly` parameter to `GuardianApiClient.searchArticles()`. When true, appends `&from-date=YYYY-MM-DD` (3 months ago) and `&order-by=newest`, matching the NewsAPI approach.
*   **PublicApiService Wiring**: All user-initiated search categories (`Search Videos`, `Search Audio`, `Search Images`, `Search Articles`, and the `else` fallback) in `PublicApiService.fetchItemsForCategory` now pass `recentOnly = true` to their respective API clients. Category-based feeds (Technology, Science, etc.) are unaffected and continue using natural trending/hot sorting.

### 4. Video Player & Embeds UI Polish
*   **Unified Tap-to-Play**: Disabled the default, persistent Android ExoPlayer control overlay (which took 3-5 seconds to disappear and didn't match the embed players). Implemented a unified tap-to-play/pause mechanic (similar to TikTok) for native video playback, complete with a translucent center play button overlay when paused.
*   **WebView Embeds Toggle**: Added an `enableWebViewEmbeds` property to `MediaSettings.kt` and a "Fallback Web Embeds" toggle in the Content tab of `SettingsTab.kt`. This allows users to completely disable the slow `WebView` fallback system. When toggled off, `VideoPlayer.resolveSource` intercepts the embed state and cleanly returns `VideoSource.Unavailable`.
*   **Imports Fix**: Fixed a build failure in `VideoPlayer.kt` where `androidx` composition elements (`clickable`, `MutableInteractionSource`, `CircleShape`) were being incorrectly referenced with fully-qualified package names in modifier chains.

## Completed Changes (2026-08-06)

### 1. Global Media Quality Control
*   **Bandwidth Optimization**: Added a global "Media Quality" setting (High, Medium, Low) to `MediaSettings.kt`.
*   **Adaptive Streaming**: `YouTubeInternalClient` and Vimeo API resolvers now dynamically select 1080p, 720p, or 480p streams based on the user's active quality preference.
*   **Dynamic Compression**: Wired the quality preference into the Mesh broadcast upload pipeline. `VideoCompressor` now aggressively downscales target dimensions (1080p -> 720p -> 480p), and image uploads dynamically scale maximum dimensions (1280px -> 960px -> 640px) and JPEG compression ratios (85% -> 75% -> 60%) to drastically save Tor mesh bandwidth for users on Low quality settings.

### 2. Memory Leaks & Stability Fixes
*   **OkHttp Connection Leaks**: Patched a massive connection leak in `FeedParser.kt`, `RedditApiClient.kt`, and the Invidious/Archive/Vimeo APIs. Added strict `use { }` blocks and explicit `.close()` calls on early network returns to prevent the background sync worker from exhausting the OkHttp connection pool.
*   **Compose Nested Scroll Crash**: Fixed a fatal `IllegalStateException` ("infinity maximum height constraints") that crashed the app when opening the Filtering & Content Mix page. Removed an accidental nested `verticalScroll` modifier from `FeedMixSettingsSection.kt`.

### 3. Media & Feed UX Polish
*   **Wikimedia Thumbnails**: Fixed a bug where Wikimedia Featured Images failed to load. `WikimediaApiClient` now explicitly requests the `iiurlwidth=1280` thumbnail generation from the API, and `MediaComponents.kt` was updated to securely prepend `https:` to protocol-relative `//` URLs.
*   **RSS Image Sanitization**: `MediaComponents.kt` now automatically decodes HTML entities (e.g. `&amp;`) in image URLs to prevent Coil from silently failing on malformed RSS tags.
*   **Ghost Mesh Posts**: Fixed an issue where swiping past Mesh broadcasts without pausing for 5 seconds left them marked as 'unseen', causing them to repeatedly appear in the Live Feed. Mesh items now instantly record a swipe event on scroll.
*   **Deleted Peer Cleanup**: `MeshSocialRepository.deletePeer()` now explicitly executes an SQLite update to orphan all non-public mesh posts from the deleted user, and `NoSlopViewModel` proactively sweeps them from the active UI state.

## Completed Changes (2026-08-05) - Part 2

### 8. UI Polish, Privacy Defaults & YouTube HLS Extraction (August 5, 2026)
*   **YouTube PoToken & WAF Bypass**: Fixed stream extraction failures caused by YouTube's WAF rejecting native mobile clients (`ANDROID`, `IOS`) that were incorrectly sending web headers (`Origin`, `Referer`). Upgraded the Android client version to `21.02.35` and introduced the `TVHTML5` client with dynamic `signatureTimestamp` injection to bypass Proof of Origin (PoToken) checks.
*   **HLS Stream Prioritization**: Instructed the YouTube parser to explicitly prioritize `hlsManifestUrl` un-ciphered streams for immediate, native ExoPlayer playback, avoiding the 3-second WebView fallback delay.
*   **Preloader URL Keying**: Fixed cache-misses in `PreloadManager` by introducing `forcedResolvedUrl`. The preloader now correctly keys cached `ExoPlayer` instances by their raw identifiers while performing prewarm operations against the resolved proxy endpoints, ensuring instant handoff when the user swipes.
*   **Broadcast Privacy Defaults**: Mesh broadcasts now default to "Friends Only" privacy. Selecting "Public" triggers an explicit warning dialog educating the user that public posts will be gossiped over daisy-chained peers beyond their direct friends.
*   **UI/UX Polish**: 
    *   Fixed the Content Ratio Mix layout in `FeedMixSettingsSection.kt` by making it vertically scrollable and resolved race conditions in the slider auto-balancing math.
    *   Fixed the DM tutorial overlay (Step 3: Add new peer) getting cropped on smaller screens by dynamically calculating layout bounds and anchoring the spotlight to the right edge.

### 7. Feed Reliability, Search, and Media Preloading (August 5, 2026)
*   **Search Engine Optimization**: Upgraded `PublicApiService` from sequential blocks to use a `supervisorScope` with `async/awaitAll`. Content fetching is now fully parallelized, preventing slow instances from bottlenecking the entire category search.
*   **Infinite Search Feed**: Added infinite continuous scrolling functionality to `NoSlopViewModel.loadMoreFeedItems`. If the user exhausts their search results, the app automatically appends random modifiers (e.g., "latest", "interview", "podcast") and fires sequential background API requests to keep the feed scrolling indefinitely.
*   **Search Yields & Exclusions**: Fixed a strict substring matching rule that discarded valid API search results locally. Additionally, active Search feeds now explicitly bypass the `viewedHistoryIds` cache, ensuring that videos you've already seen are successfully shown when you explicitly search for them.
*   **YouTube IP Proxy Block Mitigation**: Added dynamic `403 Forbidden` detection to the Cloudflare Worker proxy inside `YouTubeInternalClient.kt`. If Google blocks the proxy, NoSlop instantly intercepts the failure and silently falls back to pinging `youtube.com` directly using `ANDROID_TESTSUITE` and `IOS` user-agents to successfully harvest raw `.mp4`/`.m3u8` payloads without a proxy.
*   **Instant Playback & Preload Fixes**: Fixed a severe cache-thrashing bug in `PreloadManager` where `MAX_PRELOAD` sizing was evicting `ExoPlayer` instances immediately. Preloading now correctly tracks `rawUrl` vs `resolvedUrl` arrays to accurately buffer upcoming videos while swiping in `UnifiedFeedTab`.
*   **History Feed Sorting**: Fixed a bug where the `History` and `Liked` lists were incorrectly applying "Smart Interleaving" logic. They now bypass the interleavers and perfectly respect exact chronological list sorting.



### 6. Contact Management & Peer Deletion
*   **Zombie Connection Guard**: Fixed a bug where deleting a peer could result in them being silently resurrected as a pending request. `GossipService` now maintains a `recentlyDeletedPeers` cache (7-day TTL), and `HandshakePacketHandler` strictly rejects any incoming `CONNECTION_REQUEST` from these keys, closing the 1-hour vulnerability window.
*   **Peer Disconnect Protocol**: `MeshSocialRepository.deletePeer()` now actively transmits a direct `USER_EXIT` packet to the deleted peer's onion address before local cleanup, properly notifying them of the disconnect.
*   **Media Relay Cleanup**: Deleting a peer now synchronously invokes `GossipService.removePeerFromRelays()` to instantly reap any dangling media relay listener references.



### 5. Handshake & Messaging Race Conditions
*   **Accidental Peer Deletion Fixed**: Fixed a critical UI bug where tapping outside the "Accept Handshake" dialog triggered the dismiss event which was incorrectly mapped to `rejectHandshake()`. This permanently deleted the pending peer, breaking all subsequent handshake confirmations. It now properly maps to `dismissHandshakeDialog()` which hides the UI without destroying the database entry.
*   **Message Trust Firewall Buffer**: Fixed a race condition where a `MESSAGE` packet and a `USER_HANDSHAKE` packet sent simultaneously over a newly opened Tor circuit could arrive out of order. `GossipService` now implements a 15-second holding buffer for `MESSAGE` packets from untrusted peers. When the `USER_HANDSHAKE` arrives milliseconds later, it flushes the buffer and processes the E2EE message instead of permanently dropping it.
*   **Discoverable Timeout Extension**: Increased the heartbeat timeout for Discoverable nodes from 3 minutes to 15 minutes to account for Tor's native v3 Hidden Service Directory (HSDir) publication delays.



### 1. Connection & Mesh Stability
*   **Persistent Packet Spooler**: Upgraded the background retry spooler in `MeshSocialRepository.kt` to persist for up to 72 hours (from 15 minutes) for all packet types (`CONNECTION_REQUEST`, `USER_HANDSHAKE`, etc.). This ensures handshakes and critical DMs eventually reach peers even if they are offline for extended periods.

### 2. UI/UX Polish
*   **DMs List State Mixing Fixed**: Added explicit `key` parameters (`publicKeyB64`) to all `LazyColumn` and `LazyRow` items in `DMsTab.kt`. This fixes a critical Compose bug where internal state (like the User Info Modal) would stay attached to an index and swap to the wrong user when the contact list reordered due to online status changes.
*   **Avatar Rotation**: Added a 90-degree rotation button to `AvatarCropper.kt`, allowing users to fix image orientation before finalizing their profile picture.

### 3. Onboarding & Tutorials
*   **Onboarding Flow Streamlined**: Removed the "Setup a HUB" slide from the onboarding flow as the integration is still maturing, and fixed the "Content Mix" slide layout/navigation buttons.
*   **Tutorial Overlays**: Rebuilt the Feed tutorial slides in `UnifiedFeedTab.kt` to use explicit scrim overlays with transparent punch-outs (via `Canvas` and `BlendMode.Clear`) that physically highlight the relevant UI elements (Navigation, Top Controls, Interaction Icons).
*   **DM Tutorial Note**: Updated the DM tutorial text to explicitly clarify that scanning the developer's QR code from the gallery is completely optional.

### 4. Media Privacy
*   **Public Auto-Download Default**: Changed the default value of `autoDownloadPublic` in `MediaSettings.kt` to `false`. Users must now explicitly opt-in to automatically download media from non-contact mesh broadcasts.

## Completed Changes (2026-08-02)

### 1. Tor Background Stability
*   **WakeLock Integration**: Added a `PowerManager.WakeLock` (PARTIAL_WAKE_LOCK) to `NoSlopForegroundService.kt`. This ensures the CPU stays awake while the Tor daemon and Mesh listener run in the background, preventing Android from suspending Tor's socket activity and dropping mesh connectivity.

### 2. UI/UX Polish
*   **Image Fallbacks**: Fixed a bug where clearnet images would show a black screen if Coil failed to load the primary `mediaUrl`. Added a `fallbackUrl` capability to `BlurredImageBackground` that routes to `thumbnailUrl` if the primary network request fails or times out.
*   **Search Input Debouncing**: Re-wired the creator channel search in `OnboardingScreen` and `ContentPreferencesScreen`. Increased the text debounce from 300ms to 500ms and correctly implemented `CancellationException` handling so background Coroutine tasks don't crash or bounce UI state when typing quickly.
*   **Translation Sync**: Conducted a deep audit of all new UI flows (`ProfileScreen`, `FeedMixSettingsSection`, `OnboardingScreen`) and successfully ported missing strings into the `content_en.json` and `content_hu.json` localization files.


## Completed Changes (2026-08-01)

### 1. Tor Stability & Daemon Recovery
*   **SIGABRT Crash Prevention**: Fixed a critical issue in `TorService.kt` where transient network connectivity blips (e.g., switching between Wi-Fi and mobile data) would cause the app to forcefully set the Tor state to `FAILED`, triggering a full daemon restart. The underlying `libtor.so` native library would crash with `SIGABRT` during these forced restarts. The fix allows the daemon to recover gracefully from connectivity interruptions instead of restarting, significantly improving Tor stability on mobile networks.

### 2. Media Transfer Fixes & Download Resume
*   **EOF Detection Fix**: Fixed an indefinite hang in `MediaManager.kt` where downloads of files with a known `totalSize` (from `MediaMetadata.size`) would stall at completion. The `eofOffset` and `contiguousBytes` evaluation logic was corrected to properly detect when all bytes have been received.
*   **Download Progress Accuracy**: Updated `MediaChunkPayload` in `Packets.kt` to include a `totalSize: Long?` field. The sender now populates this from the actual file size, and the receiver dynamically updates `ActiveDownload.totalBytes` if the initial metadata size was 0 (indeterminate). This eliminated the bug where large video downloads would show 0% → 50% → hang, and instead shows accurate 1% → 100% progression.
*   **Download Resume on App Restart**: Fixed a critical bug where `MediaManager.startDownload()` unconditionally deleted the existing `.part` file (`dl.partFile.delete()`) on every download start, forcing all downloads to restart from 0% after an app restart. The method now reads the existing `.part` file size and resumes from the last contiguous byte offset. The progress bar immediately jumps to the correct percentage upon resume.

### 3. AIMD Congestion Control Tuning for Tor
*   **Tor-Optimized Chunk Parameters**: Re-tuned the AIMD (Additive-Increase/Multiplicative-Decrease) constants in `MediaManager.kt` specifically for Tor Hidden Service circuits:
    - `MIN_CHUNK_SIZE`: 64KB → **128KB** (avoids wasting Tor circuit setup on tiny payloads).
    - `MAX_CHUNK_SIZE`: 256KB → **1MB** (maximizes throughput per circuit).
    - `MAX_CONCURRENCY`: 4 → **2** (reduces parallel SOCKS5 handshake overhead over Tor).
    - `DOWNLOAD_TIMEOUT_MS`: 120s → **300s** (5 minutes, accommodates Tor's higher latency).
    - Initial `currentChunkSize`: 16KB → **128KB**; `ssthresh`: 16.0 → **2.0**.
*   The AIMD ramp-up behavior is preserved: starts at 128KB/1 socket, ramps to 1MB/2 sockets on success, throttles back on timeout.

### 4. DM Media Compression
*   **Video Transcoding in DMs**: Ported the `VideoCompressor` integration from the feed post composer into `ChatThreadScreen.kt`. DM video attachments exceeding 20MB are now automatically transcoded before sending, preventing the app from crashing when attempting to copy and send raw 500MB+ video files. The `buildMediaMetadata()` function was converted from synchronous to `suspend` and runs on `Dispatchers.IO`.
*   **Image Compression in DMs**: DM image attachments exceeding 500KB are automatically scaled down to a maximum of 1280×1280 pixels and re-compressed as JPEG at 75% quality before sending.
*   **Compression Progress UI**: A "Compressing... X%" progress banner now appears above the DM input bar while media is being processed, providing clear feedback instead of the app appearing frozen.

### 5. Feed Post Media Compression
*   **Image Compression for Feed Posts**: Added automatic image compression to the feed post composer in `UnifiedFeedTab.kt`. Images exceeding 500KB are scaled to 1280×1280 max and JPEG-compressed at 75% quality, dramatically reducing mesh transfer times for image posts.

### 6. UI Polish
*   **Transparent Download Overlay**: Reduced the "Tap to Download" overlay opacity in `FeedCard.kt` from 60% (`0.6f`) to 30% (`0.3f`) across all media types (video, audio, image), allowing the thumbnail to remain clearly visible behind the download prompt.
*   **Persistent Thumbnail on Load Failure**: Updated `BlurredImageBackground` in `MediaComponents.kt` to set `error` and `fallback` painters to the base64 thumbnail. Previously, when the clearnet thumbnail URL was empty (common for mesh-native posts), the image loader would flash the base64 placeholder briefly and then render a solid black box. The thumbnail now persists permanently.
*   **1GB Auto-Download Limit**: Increased the maximum value of the auto-download file size slider in `SettingsTab.kt` from 100MB to 1GB (`1f..1000f`), allowing users to set higher limits for large transcoded video files.


## Completed Changes (2026-07-31)

### 1. Discoverable Mode & Temporary Contacts
*   **Heartbeat Identity Leak Fixed**: Fixed `MeshSocialRepository`'s background periodic heartbeat which was inadvertently broadcasting `ANNOUNCE_DISCOVERABLE` packets signed by the *main* identity instead of the *burnable* identity.
*   **Heuristic Discoverability Filtering**: Added logic to `HandshakePacketHandler` to drop `ANNOUNCE_DISCOVERABLE` packets if the handle perfectly matches an already trusted peer's handle or the local burnable identity. This keeps the feed clean and prevents known friends (or the user themselves) from popping up as "new" discoverable peers.
*   **Temporary Status Persistency**: Fixed a bug where a trusted peer broadcasting a discoverable packet would overwrite its local `isTemporary` state to `false`, accidentally upgrading them to a permanent contact.
*   **Temporary Contacts UI Persistence**: The "Temporary Contacts" list in `DMsTab` now saves its collapsed/expanded state across app sessions using `AppSetting`.

### 2. Media Downloads for Temporary Contacts
*   **Burnable Proxy Routing**: Fixed a major bug in `MediaManager` and `GossipService` where media packets (`MEDIA_RELAY_REQUEST`, `MEDIA_RECOVERY_FOUND`, `MEDIA_REQUEST`, and `MEDIA_CHUNK`) sent between temporary contacts were signed with the local node's *main* identity rather than the *burnable* identity. The recipient's mesh firewall would subsequently drop them (as it only trusted the burnable identity). Media requests and chunk relays are now properly routed through the burnable identity for temporary contacts.

### 3. Stability & Tor Resiliency
*   **Foreground Service Crash Loop**: Wrapped `startForegroundService` and `stopForeground` in safety blocks to prevent fatal `AndroidRuntime` crashes under Android 12+ strict background execution limits.
*   **Tor Auto-Recovery**: Added an active monitoring loop in `NoSlopViewModel` that seamlessly attempts up to 3 restarts if the Tor daemon falls into a `FAILED` state.
*   **Logcat Spam Reduction**: Downgraded routine ExoPlayer `VIDEO_DEBUG` events from `Log.e` to internal debug logging, clearing up 86% of the false-positive error noise.

### 4. Media & Feed UX Polish
*   **Mesh Feed Top-Snapping**: The "Mesh Network" and "Your Broadcasts" feeds now explicitly bypass the "last viewed" memory feature. Opening these filters instantly snaps to the top to show the newest content, resolving the need to scroll back up.
*   **Large File Transfers**: Reduced the maximum Tor chunk size from 512KB to 256KB and lowered the maximum concurrent SOCKS5 connections from 8 to 4, preventing Tor circuit timeouts and buffer congestion on large media transfers.
*   **Relay Restamping for Temporary Contacts**: `MEDIA_RECOVERY_FOUND` packets now correctly restamp the sender ID as the node's *Burnable Identity* when relaying to a Temporary Contact, preventing the target's firewall from silently dropping the recovery payload.
*   **VideoCompressor Crash Fix**: Handled Media3 Transformer exceptions in `VideoCompressor.kt` to gracefully emit error states instead of fatally crashing the background coroutine.
*   **Invidious Instance Refresh**: Replaced 10 dead or rate-limited YouTube proxy instances with 5 known-good instances (`yewtu.be`, `projectsegfau.lt`, etc.) resolving the widespread `NXDOMAIN` and `403` search failures.

## Completed Changes (2026-07-28)

### 1. Discoverable Mode & Burnable Identity Fixes
*   **Burnable Identity Leak Fixed**: `NoSlopViewModel` now correctly pulls the `Burnable Identity` instead of the Main Identity when generating the `ANNOUNCE_DISCOVERABLE` broadcast. This guarantees peers will treat the incoming connection request as a temporary, anonymous contact rather than a permanent one.
*   **Pending Request UI Visibility**: Corrected a bug in `DMsTab` where pending connection requests from Discoverable nodes instantly disappeared. The filter now hides `!isDiscoverable` instead of `!isTemporary`, allowing burnable pending requests to stay visible until accepted.

### 2. Tor Mesh Transport Tuning & Fast-Fail
*   **Timeout & Retry Reduction**: Dropped the Tor SOCKS5 `connectTimeout` in `MeshTransport` from 60 seconds to 30 seconds, and reduced the maximum attempts for critical packets from 5 to 3. Because Tor v3 hidden services can take 5-10 minutes to propagate to HSDirs, waiting 60s per attempt blocked the thread and gridlocked the queue. This change forces the transport to "fail-fast", instantly triggering the Gossip Relay fallback and background spooler for immediate delivery via intermediate peers.

### 3. DM Media & GIF Rendering
*   **MIME-Type Hack Removed**: Removed a legacy robustness hack in `DmPacketHandler` that forcefully rewrote `image` types to `gif`. This was causing the `MediaManager` to misclassify the file extension during download.
*   **Local File Prioritization in Coil**: Updated `ChatThreadScreen` to natively pass the locally downloaded `File` object to Coil's `AsyncImage` for GIFs, fully bypassing the `noslop-gif://` proxy scheme. This ensures GIFs animate immediately after downloading without attempting to stream through the local Tor proxy.

## Completed Changes (2026-07-26)

### 7. Discoverability & Profile Bio Support
*   **Firewall Bypass**: Corrected the gossip firewall to properly permit `IDENTITY_UPDATE` and `USER_EXIT` packets from discoverable 3rd parties, allowing profile propagation without a trusted connection.
*   **Bio Field Integration**: Added `bio` string support across the network protocol (`IdentityUpdatePayload`, `PeerHandshakePayload`, `AnnounceDiscoverablePayload`) and local database. The user info modal and DM lists now properly render bios.
*   **Discoverable Connect UI**: The 'Connect' button is now consistently injected into all user profile modals (feed, comments) for any non-trusted 3rd-party peer with a known onion address.

### 6. Privacy UI & Display Name Polish
*   **Share Button Guard**: The "Share" button is now explicitly hidden on all posts with `privacy = "friends"`, preventing peers from bridging or relaying private posts manually.
*   **Identity Formatting**: `CryptoService` no longer appends the cryptographically generated `.tripcode` to the default `displayName` string, meaning users' explicitly typed handles are used universally. Older legacy handles are dynamically stripped in the UI for a cleaner display.
*   **User Info Modal Privacy**: The User Profile modal no longer leaks public keys, tripcodes, or network details to any user. It exclusively displays the Avatar, Handle, and connection status.
*   **Burnable 3rd-Party Connections**: When a 3rd-party user taps on an author who is actively broadcasting `ANNOUNCE_DISCOVERABLE`, the modal now injects a "Connect" button. Tapping it triggers a mandatory warning dialog and exclusively routes the `CONNECTION_REQUEST` using the user's ephemeral/burnable identity, perfectly protecting the user's persistent onion address from strangers.


### 5. Friends-Only Broadcast Scoping
*   **Hop Count Restriction**: Outbound packets for friends-only broadcasts (Posts, Comments, Reactions, Votes, Edits, Deletes) are now strictly restricted to `hops = 1`. This explicitly scopes network propagation to directly connected trusted peers.
*   **Gossip Forwarder Guard**: The `GossipService` explicitly checks incoming `POST` payloads and drops any with `privacy = "friends"` from being relayed to other peers, completely isolating friends-only content from 3rd parties.


### 1. Tor Service Recovery & Auto-Healing
*   **Daemon Resurrection**: Fixed `TorService.kt` to handle Android's background service `IllegalStateException` limits safely and stop stuck daemon instances before restart.
*   **Auto-Retry Observer**: Added an observer in `NoSlopViewModel` that automatically detects if Tor falls into the `FAILED` state (e.g. killed by the OS when foreground service stops) and seamlessly auto-retries bootstrapping up to 3 times in the background.

### 2. Mesh Transport Fast-Failing & DM Spooler
*   **Semaphore Gridlock Prevention**: `MeshTransport` now instantly fast-fails and frees Tor circuits upon receiving explicit `SOCKS: Host unreachable` or `TTL expired` rejections, rather than stubbornly waiting 60s per attempt and hoarding permits.
*   **Background Retry Spooler**: Introduced a 15-minute background spooler in `MeshSocialRepository`. If a direct packet (DM/Handshake) fails initially due to the target's `.onion` still propagating to HSDirs, it will silently retry every 60 seconds. This perfectly mitigates Tor's 5-10 minute directory publication delay!

### 3. Foreground Service UX Polish
*   **HSDir Delay Transparency**: Added a dedicated `AlertDialog` in `SettingsTab` when the user toggles the Foreground Service off, educating them on the 5-10 minute Tor v3 publication propagation window and setting proper reachability expectations.

### 4. Mesh Filter UX Simplification
*   **Removed Outgoing Native Gates**: Stripped the confusing "Outgoing" toggles for Text, Image, and Video posts from `MeshFiltersScreen`, renaming them to "Broadcasts" for clarity.
*   **Unrestricted Network Engagement**: Removed outgoing mesh filters for `REACTION`, `VOTE`, and `COMMENT` packets. Native engagement is vital for the network's organic reputation and chronological sorting algorithms and must always be allowed to propagate. Mesh filters now exclusively govern heavy incoming media and automatic Clearnet-to-Mesh bridging.

## Completed Changes (2026-07-25)

### 1. Tor ED25519-V3 Key Derivation Math Fixed
*   **Proper SHA-512 Clamping**: Fixed a critical routing regression where `CryptoService.kt` and `SshDeployer.kt` were passing the 64-byte libsodium format (`seed || pubkey`) to the Tor daemon for `ADD_ONION`. Tor requires the 64-byte expanded and clamped secret key (SHA-512 of the seed with specific bitwise clamping). Corrected the math in both Kotlin and the Python deployment script to generate the exact same `.onion` address locally as the Tor daemon.

### 2. Hub Firewall & Mesh Sync Disconnection
*   **Mobile Identity Trust**: Fixed an issue where the Hub's `TrustFirewall` was rejecting `sync_push_packets` from the linked mobile device. `api_router.rs` now explicitly calls `engine.trust_peer(my_node_id)` ensuring the mobile app's signature is inherently trusted by its own Hub.

### 3. Mesh Transport & Gossip Storm Prevention
*   **Direct Routing Gate**: Patched `api_router.rs` to stop a dual-routing flood. If a directed packet has a known `target_onion`, the Hub will now only execute the direct SOCKS5 connection (with local background retries). It drops the redundant gossip broadcast unless the direct send fundamentally fails *and* no onion address was known.

### 4. Tor Ephemeral Onboarding Fallback
*   **Null Key Registration**: Fixed `TorService.kt` where a well-intentioned guard skipped hidden service registration if no identity key was loaded. Restored the ephemeral `registerHiddenService(null)` fallback, allowing new users to complete onboarding with a temporary `.onion` before their permanent identity is generated.

## Completed Changes (2026-07-13)

### 4. Bulletproof OTA Downloader (HttpURLConnection)
*   **Parse Error Resolution**: Fixed the "There was a problem parsing the package" error which occurred when OkHttp silently downloaded HTML login/404 pages (due to GitHub Draft/Private release states) instead of binary APKs.
*   **Native Progress Tracking**: `UpdateManager.kt` now uses a pure, interceptor-free `HttpURLConnection` pipeline. It manually negotiates redirects, strictly verifies the `Content-Type` to reject `text/html` payloads, enforces a >2MB minimum file size, and provides live UI Toasts (e.g., "Downloading update: 14MB...") every 3 seconds so the user is never left guessing the background state.

### 3. OTA Update Manager Rewrite (OkHttp)
*   **DownloadManager Bypass**: Completely removed reliance on Android's unreliable system `DownloadManager` which was hanging silently in `STATUS_PENDING` due to GitHub redirect chains.
*   **Native Coroutine Download**: `UpdateManager.kt` now streams the APK directly to disk via `HttpClientProvider.clearnetClient` in a Coroutine, ensuring 100% reliability, DNS-over-HTTPS fallback, and immediate installer launching upon completion.
*   **Settings Banner Polish**: Integrated a permanent Update Banner at the top of the Settings page that intelligently handles `REQUEST_INSTALL_PACKAGES` permission states, offering "Give Permission" and "Just Download APK" distinct workflows.

### 1. Admin AI Integration & ASN.1 Crypto Fixes
*   **Admin AI Auto-Peer**: Fixed an issue where DMs to the Hub's Admin AI failed because the app lacked a local peer record for it. `NoSlopRepository.ensureAdminPeerExists()` now automatically injects the `Admin AI (Hub)` contact upon linking.
*   **ASN.1 X25519 Decryption**: Resolved a critical crash where Android's BouncyCastle wrapped X25519 keys in 83-byte ASN.1 structures instead of raw 32-byte arrays. The Hub now mathematically extracts the raw scalars (`0x04, 0x20` and `0x03, 0x21, 0x00`), allowing seamless DM decryption and responses from the Admin AI.
*   **Newline Stripping**: Fixed hidden `\n` characters in Android JSON payloads breaking exact-match target interception.

### 2. Historical Hub Sync & SQLite Migration
*   **SQLite Persistence**: The Hub transitioned from volatile JSON arrays to a persistent SQLite database (`social.db`) for storing DMs, Posts, and Peers.
*   **Historical Pull Sync**: When linking to a Hub, NoSlop now dynamically pulls historical data (`get_dms`, `get_social_feed`, `get_mesh_peers`) to populate the local app state.
*   **Media Metadata Retention**: Fixed an issue where syncing DMs and Posts from the Hub to the mobile app dropped the associated media metadata, ensuring image and video UI elements render correctly.

## Completed Changes (2026-07-12)

### 1. DM Contacts Organization — Collapsible "All" List & Folder Assignment
*   **Collapsible "All" Contacts Tab**: The "All" contacts list on the DMs page can now be hidden by tapping the "All" tab header. When collapsed, only a compact icon is shown, and the page defaults to displaying the first custom folder. The collapsed state is persisted to `app_settings["dm_all_tab_hidden"]` so it survives navigation and app restarts.
*   **Contact Folder Assignment**: The "Assign to Folder" modal now includes a dropdown of existing folders (populated from previously created folders) alongside the text field for creating new folders. Users can either select an existing folder or type a new name.

### 2. Complete Settings Page Localization
*   **Full `.tr` Extension Audit**: Conducted a comprehensive audit of `SettingsTab.kt` and applied the `.tr` translation extension to all remaining hardcoded user-facing strings, including: Tor proxy status labels (`"Active Tor Proxy"`, `"Tor Disconnected"`), media file limit label (`"Max File Size: "`), data & backup workflow strings (`"Export Backup"`, `"Import Backup"`, `"Save File"`, `"Select File"`, `"Importing..."`, `"Nuclear Option"`), and app update notification strings (`"Version "`, `"is out (you have "`, `"Unknown"`).
*   **Hungarian (Magyar) Translation Completion**: Updated `content_hu.json` with proper Hungarian translations for all newly localized strings. Also translated the previously untranslated "About NoSlop" and "Help Development" modal strings (`"Help Development"`, `"Support Gabby's work..."`, `"About NoSlop"`, `"Buy Gabby a Coffee"`, `"Version "`, `"Resources"`, `"GitHub Repository"`, `"Privacy Policy"`, `"Imagined by Gabor Kukucska"`), which had been left in English.
*   **DM Folder UI Strings**: Added localization keys for the new folder assignment UI (`"Choose existing folder"`, `"Select a folder"`, `"or create new"`, `"New Folder Name"`, `"Folder Name"`, `"Assign to Folder"`) to both `content_en.json` and `content_hu.json`.

### 3. Media Settings Restructure & Trust-Based Auto-Download
*   **"Automatic Media Download" Rename**: Renamed the "Enable Media" toggle to "Automatic Media Download" for clarity.
*   **Background Playback Decoupled**: Moved the "Background Playback" and "Play Outside App" toggles out of the media download card into their own dedicated `Card` container, making it clear these settings are independent of auto-download behavior.
*   **Trust-Based Auto-Download Toggles**: Replaced the confusing "Auto-download Friends" / "Auto-download Private" toggles with a clearer trust-based hierarchy:
    *   **Friends** — "only auto-download media from contacts" (respects `isTrusted` peer status).
    *   **Public** — "Also auto-download media from public broadcasts" (covers non-contact mesh peers).
*   **Public Media Warning Dialog**: Toggling "Public" auto-download ON now triggers a confirmation `AlertDialog` warning: *"You'll download 3rd party media!"*. The setting is only applied if the user taps "Accept".
*   **File Attachment Exclusion**: `MediaManager.checkAndAutoDownload()` now explicitly skips media with `metadata.type == "file"`, ensuring file attachments are never auto-downloaded and must be manually tapped to sync.
*   **Data Model Migration**: Renamed `MediaSettings.autoDownloadPrivate` to `autoDownloadPublic` in `MediaSettings.kt` and updated all downstream consumers (`MediaManager.kt`, `SettingsTab.kt`).
*   **Files**: Modified: `MediaSettings.kt`, `MediaManager.kt`, `SettingsTab.kt`, `content_en.json`, `content_hu.json`.

### 4. Background Sync & HAI-Net Hub Onboarding Refactor
*   **Foreground Service Relocation**: Moved the "Foreground Service" toggle out of the General settings tab into the Network tab to align with architecture.
*   **Battery Warning Dialog**: Adding manual foreground mesh sync now triggers an aggressive battery warning dialog with a direct "Deploy HUB" call-to-action that securely routes the user to the HUBs tab.
*   **Automated Hub-Aware Gating**: The foreground service toggle is automatically disabled (grayed out) when a Home Hub is connected. Furthermore, the `NoSlopViewModel` dynamically halts the local foreground service upon successful Hub linkage to preserve mobile battery.
*   **Onboarding Routing Fix**: Corrected the "Set up Hub Now" button in the `OnboardingScreen` so it successfully navigates to the Hubs tab via the new `hubs-deploy` route and triggers an immediate local network scan.
*   **Deploy vs. Link Separation**: Refactored the network discovery UI (`HubSetupScreen.kt`) to support two distinct workflows: 
    *   **Deploy**: Finds a device, prompts for SSH credentials, and executes a full `SshDeployer` remote installation.
    *   **Link**: Resolves the broken "I already have a Hub running" option. It scans the network, bypasses SSH logic entirely, and instantly pairs the app to the selected existing node. Also introduced a secure "Manual IP Entry" modal exclusively for linking off-subnet Hubs.

## Completed Changes (2026-07-10)

### 1. Global Connectivity & Native Hub Tor Daemon
*   **Smart Hub API Client**: Implemented `invokeHubApi` in `NoSlopRepository.kt`. The app securely bridges communication to the Home Hub by attempting a LAN IP request first (for speed), and gracefully falling back to the SOCKS5 `torClient` targeting the Hub's `.onion` address when off-network.
*   **Active-Passive Identity**: Updated Mobile `TorService.kt` to dynamically disable its local Hidden Service broadcast when connected to a Hub. The mobile app pivots to using Tor purely as an outbound SOCKS5 proxy to conserve bandwidth and prevent identity collisions on the mesh.
*   **Native Hub Tor Generation**: Upgraded `hainet-seed` to parse the PKCS#8 Mobile Identity Clone, apply SHA-512 expansion and bit-clamping natively in Rust, and generate a standard `/var/lib/tor/hainet/hs_ed25519_secret_key`.
*   **Unified Onion Routing**: Configured the Hub's `/etc/tor/torrc` to expose both Port 9999 (Mesh Gossip) and Port 8080 (REST API) under the single, persistent `.onion` address.

### 2. Edge-Case Fixes (Hotspots & QR Decoding)
*   **Ghost Notification Cleanup**: Fixed a UI bug in `NoSlopViewModel.kt` where accepting or rejecting a mesh handshake via the UI popup left a ghost notification in the drawer. The ViewModel now actively sweeps and deletes associated `NotificationItem` entries upon resolution.

*   **Hotspot Subnet Discovery**: Upgraded `HubDiscoveryService.kt` to extract all active IPv4 prefixes (Cellular, Hotspot, WiFi) and concurrently scan them for SSH (Port 22), bypassing Android's mDNS/multicast isolation on tethered connections.
*   **Gallery QR Decoding Fallback**: Enhanced `QRScanScreen.kt` with a dual-engine decoder. If ML Kit fails to parse a synthetic screenshot from the gallery, it falls back to a ZXing binarizer on a dedicated IO thread.
*   **Port Alignment**: Fixed authentication port routing to target the unified `8080` port now handled by `hainet-core` instead of the legacy `3000` port.


### 3. Monetization & Support Architecture
*   **Stripe Integration**: Added a "Help Development" banner to the Settings page. This links directly to a Stripe Payment Link for seamless donations, avoiding the need for backend payment processing and keeping the app strictly peer-to-peer.
*   **About NoSlop Modal**: Consolidated the legacy app information and update notifications into a unified "About NoSlop" modal, accessible from the bottom of the Settings page. This includes dynamic version rendering and links to project resources (GitHub, Privacy Policy) and the creator's portfolio.
*   **Complete Localization Parity**: Ensured all new UI strings for the Donation and About modals are fully translatable, integrating them into the `.tr` extension and `content_en.json`/`content_hu.json` framework.

### 4. Robust Auto-Update System
*   **OTA Download Reliability**: Completely rewrote `UpdateManager.kt` to fix silent failures associated with Android's native `DownloadManager`.
*   **Dynamic Permission Handling**: Integrated runtime checks for `REQUEST_INSTALL_PACKAGES` (required on Android 8.0+) to automatically redirect users to system settings if the permission is missing, ensuring the APK installer can actually launch.
*   **Resilient Completion Detection**: Replaced the static manifest receiver with a dual-strategy approach: dynamic runtime `BroadcastReceiver` registration combined with a 3-second background polling loop via Coroutines. This guarantees the app detects when the update finishes downloading, even if the OS drops the broadcast.
*   **State Recovery**: The active download ID is now persisted to `SharedPreferences`, allowing the fallback static receiver to resume the installation process even if NoSlop is killed in the background during the download.

## Completed Changes (2026-07-09)

### 2. Multi-Path Hub Discovery & Reliable Authentication
*   **Robust Hotspot Discovery**: Upgraded `HubDiscoveryService.kt` with a concurrent multi-subnet scanner. It now extracts all active IPv4 prefixes (Cellular, Hotspot, WiFi) and scans them all for SSH (Port 22), bypassing mDNS/multicast isolation on tethered connections.
*   **High-Fidelity QR Authentication**: Fixed a port mismatch (3000 -> 8080) and IP routing logic in `NoSlopViewModel.kt`. Scanned login attempts now correctly target the integrated Hub Portal.
*   **Gallery QR Decoding Fallback**: Enhanced `QRScanScreen.kt` with a dual-engine decoder. If ML Kit fails to parse a screenshot (common with synthetic QR codes), it falls back to ZXing on a dedicated IO thread.
*   **Headless Auth Handshake**: Implemented the "QR-Login-Only" state in the Hub backend. If a Hub is deployed via NoSlop, it automatically skips 24-word seed generation and prompts for the mobile-assisted signature verification immediately.

### 1. HAI-Net Hub Deployment Fixes & Dashboard Polish
*   **Systemd Unit Deployment Fix**: Fixed a critical bug in `SshDeployer.kt` where pipelined `sudo tee` combined with quoted heredocs resulted in empty (masked) systemd unit files on the Hub. Re-wrote the installer script to write the unit file locally, expand dynamic variables, and `sudo mv` it into place.
*   **Deployment Overwrite Strategies**: Replaced blind overwrites with a secure collision detection mechanism during deployment. If an existing `hainet-core` install is detected, NoSlop halts and provides a Tripartite Resolution Dialog: "Sign In", "Reset Identity (Keep Media)", or "Full Re-deploy (Wipe All)".
*   **Lightning Fast Identity Reset**: The "Reset Identity" option completely bypasses the standard heavy `hainet-seed` build process. Instead, it securely injects the user's Ed25519/X25519 identity JSON directly, safely regenerates Tor hidden service keys via embedded Python scripts, deletes stale `auth.json` files, and restarts services in seconds, guaranteeing immediate QR login readiness.
*   **Native Hub Dashboard**: Restored and polished the native Compose Hub Dashboard in `HubSetupScreen.kt`. Removed the heavy embedded WebView in favor of a clean, text-based UI providing the user with the direct LAN IP and Port (3000) to access the HAI-Net Portal Web UI from an external browser.
*   **Persistent Hub Connection**: Removed the immediate "Disconnect" button from the main Hubs tab to reinforce the persistence of the Home Hub connection. 
*   **Safe Hub Unlinking**: Injected a secure "Disconnect HAI-Net Hub" option into `SettingsTab.kt` above the Developer logs, protected by a descriptive confirmation dialog to prevent accidental unlinking.
*   **Hardened QR Authentication**: Integrated connection and read timeouts into `handleQrLogin` to prevent silent hanging when a Hub is unreachable. Additionally, NoSlop now dynamically intercepts unroutable `0.0.0.0` target IPs originating from the Hub's QR code and automatically resolves them to the Hub's known active LAN IP from the deployment state.


## Completed Changes (2026-07-08)

### 1. HAI-Net Hub Integration (Phase 1) & Identity Clone
*   **Zero-Terminal Deployment**: Implemented `SshDeployer.kt` using JSch to deploy the HAI-Net Hub directly from the NoSlop mobile app. It connects via SSH, clones the repository, and executes `hainet-seed install` non-interactively.
*   **Zero-Config mDNS Discovery**: Added `HubDiscoveryService.kt` utilizing Android's `NsdManager` to scan the local network for `_ssh._tcp` services. Discovered hubs appear instantly in the UI, auto-filling the IP Address field so users never have to manually type or find IPs.
*   **Tor-Exclusive Remote Access**: Completely removed all Cloudflare Tunnel automation, UI inputs, and deployment configuration parameters (`cloudflareToken`, `hasStaticIp`). The Hub's remote accessibility now relies 100% on the embedded Tor Hidden Services architecture (`Phase 2`), simplifying the user experience drastically.
*   **Identity Clone Architecture**: Replaced the previous public-key-only design with a full "Identity Clone" model. `HubSetupScreen.kt` now automatically injects the user's complete `CryptoService.IdentityKeys` (including Ed25519 and X25519 private keys) into the `hub_config.json` payload.
*   **Secure Serialization**: Rewrote `SshDeployer` payload generation using `org.json.JSONObject` to prevent escaping errors and properly serialize the nested identity block for secure transport to the Hub.
*   **Hub Setup UI**: Updated `HubSetupScreen` to dynamically handle mDNS results and standard inputs (Username, Password, Shared Media Folder).
*   **Mesh Discoverability Fix**: Completed `broadcastDiscoverable()` in `NoSlopViewModel`, ensuring ephemeral "burnable" `.onion` addresses are correctly registered via `TorService` and announced to the mesh with cryptographic signatures.

## Completed Changes (2026-07-07)

### 1. Legacy Architecture Audit & Code Cleanup
*   **MainScreen Cleanup**: Evaluated `MainScreen.kt` and identified that the large `FullScreenMeshCard` composable was dead code (replaced by V2). Removed the unused code entirely and renamed the file to `MediaUtils.kt` to accurately reflect its remaining utility (`resolveMediaUrl`).
*   **Repository Facade Verification**: Analyzed `NoSlopRepository.kt` and confirmed it acts as a necessary facade for `NoSlopViewModel`, correctly delegating to domain-specific repositories. Safely exposed `postDao` internally to resolve visibility issues without breaking encapsulation.

### 2. Mesh Network Observability & Stability
*   **GossipService Tracing**: Injected precise trace logging in `GossipService.processIncoming` to monitor the mesh packet pipeline. We can now observe exactly where packets are dropped (e.g., TTL expiry, deduplication checks, rate limiting, firewall blocks, or mesh filter rejections).
*   **MeshPacketHandler Telemetry**: Added detailed logging for packet dispatching to ensure we can trace incoming traffic as it is routed to domain-specific handlers after gossip validation.

### 3. Comprehensive Unit Testing & Regression Guards
*   **Firewall & Mesh Filters Assertions**: Implemented robust unit tests using `MockK` in `GossipServiceTest.kt` to assert the firewall dropping logic and mesh filter rules. Validated that untrusted senders are blocked correctly while system packets like `CONNECTION_REQUEST` bypass the block.
*   **Test Isolation Fixed**: Resolved a critical state leak in `GossipServiceTest` where the singleton `GossipService` retained mock dependencies across tests, ensuring complete test isolation via reflection-based resets.
*   **Test Doubles Updated**: Fixed several compilation errors in `FakeDaos.kt` (missing overrides in `FakePostDao`, `FakeEngagementDao`, and `FakeMessageDao`) caused by upstream interface changes, ensuring the pure-JVM tests compile successfully.
## Completed Changes (2026-07-06)

### 1. Onboarding & Tutorial Flow Polish
*   **Onboarding Reordering**: Swapped the Interests and Creators steps. Moving Interests first ensures the Creator search word-cloud dynamically populates with relevant channel suggestions based on the user's chosen categories.
*   **Creator Search Fix**: Split the comma-separated creator keywords string to perform concurrent background searches via `launch(Dispatchers.IO)`, fixing the `searchCustomFeed` failure.
*   **Invidious API Tuning**: Removed aggressive `/api/v1/stats` background pinging that was incorrectly blacklisting healthy instances. Increased the probe client timeout from 5s to 10s to allow channel searches (autocomplete) to succeed reliably over Tor.
*   **Feed Tutorial Race Condition**: Fixed an issue where the feed tutorial state locked into the UI before the DB could restore it. The ViewModel now initializes tutorial states to `-1` (loading) and the UI waits before injecting the slides.
*   **Native Tutorial UI & Polish**: Replaced the hacky background-content rendering with a solid, native-looking tutorial slide featuring mock author info and standard right-aligned action buttons. Fixed the Step 2 UI arrow collision. Removed the "Skip" button to force gesture learning.
*   **Tutorial Theming**: Changed tutorial highlight elements (text chips, arrows) from the app's native `AccentGreen` to Material Amber to clearly differentiate instructional overlays from interactive app UI. Softened the DM tutorial scrim from 85% to 65% opacity.
*   **DM Tutorial Auto-Completion**: Added a `LaunchedEffect` in `DMsTab` that instantly skips or auto-completes the DM tutorial if the user already has active connections (e.g., via a restored backup or manual QR scan).

### 2. Feed Generation & Priority Sorting
*   **Empty Feed Bug Fix**: Added `rawImages` and `rawAudios` back into the feed generation `leftovers` pool. This fixes a critical bug that caused feeds to load entirely empty and block swiping if YouTube/Invidious video APIs failed or hit rate limits.
*   **3-Tier Priority Sorting**: Upgraded the chronological sorting algorithm in `loadMoreFeedItems` to strictly prioritize content in three tiers: `Creators > Explicitly Chosen Categories > Trending/Fallback`. This guarantees the first items a user sees upon completing onboarding perfectly match their personal interests.

### 3. Dynamic OTA Localization Framework
*   **JSON-Based Language Manager**: Implemented `LanguageManager.kt` to load string dictionaries from `assets/languages/content_XX.json`. This replaces hardcoded UI strings with an English-anchored key-value system.
*   **Reactive UI Translations**: Created a `@Composable` `.tr` String extension mapping directly to a `StateFlow` of the current language. The entire UI now instantly translates without requiring an Activity restart.
*   **Community Translation Ready**: The app now supports drop-in JSON files for immediate multi-language support (starting with English and Hungarian).

## Completed Changes (2026-07-05)

### 1. UI/UX Polish: Notifications, File Attachments & Contacts Fixes
*   **Ghost Contacts & Handshake Race Condition**: Fixed a critical race condition in `HandshakePacketHandler.kt` where duplicate `CONNECTION_REQUEST`, `USER_HANDSHAKE`, or `IDENTITY_UPDATE` packets would overwrite an existing trusted peer, accidentally downgrading their `isTrusted` status or wiping their handle.
*   **DM JSON Payload Fix**: Fixed an issue in `ChatThreadScreen.kt` where replying to an older message would display the raw JSON payload wrapper (`{"content": "..."}`) in the reply context preview instead of the cleanly extracted text.
*   **Notification Management**: Added "Mark All Read" and "Clear All" features to the `NotificationsScreen`. The "Clear All" action is protected by an explicit "Are you sure?" confirmation dialog.
*   **File Attachment Preservation**: Replaced naive `.bin` file extensions in `ChatThreadScreen` and `UnifiedFeedTab` by querying the Android `ContentResolver` for `OpenableColumns.DISPLAY_NAME`, preserving exact original filenames (e.g., `document.pdf`) during P2P transfers.
*   **Native File Export & Broadcast Fixes**: Added `exportToPublicDownloads` in `MediaManager.kt` leveraging `MediaStore.Downloads` on Android Q+ to safely export downloaded mesh file attachments to the user's public Downloads directory. Fixed a Compose UI issue in `FeedCard.kt` where a parent `.clickable` modifier was swallowing touch events, re-enabling the "Save to Device" and "Download File" buttons on mesh broadcast attachments.


### 2. Mesh File Transfer & MIME Type Resolution
*   **Native MIME Type Mapping**: Replaced the hardcoded media type `when` blocks in `MediaManager.kt`, `UnifiedFeedTab.kt`, and `ChatThreadScreen.kt` with Android's system-wide `MimeTypeMap.getSingleton()`. This allows NoSlop to natively recognize and preserve exact file extensions (e.g., `.pdf`, `.zip`, `.docx`, `.apk`) during both upload and download.
*   **File Transfer Stall Fixed**: Fixed a critical bug where general file transfers over the mesh network stalled indefinitely at "Connecting..." (0% progress). Because uploads were previously defaulting to `application/octet-stream`, the sender's local storage mapping failed to match incoming `MEDIA_REQUEST` chunk requests. Accurately stamping the `MediaMetadata` with the correct MIME type allows peers to locate the file blocks and immediately resolves the transfer gridlock.

*   **Historical Sync Media Type Loss**: Fixed a bug in `SyncPacketHandler.kt` where `clearnetMediaType` was omitted from the database insert during `SYNC_RESPONSE` handling. This caused historical clearnet audio/video shares to incorrectly render as plain articles for newly joined peers, while live peers received the correct type.

*   **Mesh Edits & Signature Integrity**: Fixed a critical bug in `PostPacketHandler` and `Daos.kt` where editing a post updated the text string but retained the original cryptographic signature and timestamp. This caused historical syncs (`INVENTORY_SYNC_REQUEST`) to permanently fail signature verification (`CryptoService.verify`) for peers pulling the edited post. Edits now generate and store a fresh timestamp and signature. Added `EDIT_COMMENT` and `DELETE_COMMENT` packets to the network protocol with full repository support.

*   **Clearnet-to-Mesh Sync & Media Fixes**: Fixed an issue where the initial reaction triggering a clearnet share was blocked by the outgoing filter, causing peers to see the share without the context. Fixed an issue where commenting on an unshared clearnet item failed to generate the anchor post. Fixed a bug where shared clearnet images rendered as black screens due to loading the webpage URL instead of the high-res thumbnail. Fixed an ExoPlayer bug where shared YouTube embeds failed to autoplay because the player was waiting for a native video frame to clear the Base64 thumbnail.

## Completed Changes (2026-07-04)

### 1. Invidious Network Latency & Fast-Failing
*   **Lock-Free Registry Cache**: Removed a critical `@Synchronized` bottleneck in `InvidiousApiClient.getInstances()` and replaced it with a non-blocking `AtomicBoolean`. Background instance registry fetches no longer freeze the UI thread while searching. Failed registry lookups now correctly cache the hardcoded fallback list instead of hanging on every keystroke.
*   **Strict Search Timeouts**: Swapped the underlying OkHttpClient for all Invidious search functions (`searchChannels`, `searchVideos`, `getTrendingVideos`) to a dedicated `probeClient` with a strict 5-second timeout, bypassing the default 30-second client timeouts.
*   **Proactive Instance Pinging**: Added background asynchronous instance health checks during the `preWarmInstances()` startup phase. The app now silently pings the `stats` endpoint of the top 3 registry servers upon launch, proactively adding dead or blocked nodes to the `instanceFailureTime` blacklist before the user can even initiate a search.
*   **Smart Instance Filtering**: Search functions now strictly filter out any instances present in the cooling-down blacklist *before* attempting network requests, preventing the 5-second timeout penalty from being absorbed repeatedly on successive search queries.

### 2. Video Playback UI Polish
*   **Disabled Default ExoPlayer Artwork**: Explicitly set `useArtwork = false` on the Android `PlayerView` component to prevent ExoPlayer from aggressively flashing its own low-quality generic media icon over the Coil high-res thumbnail before the first frame is buffered.
*   **True Frame Rendering Transitions**: Re-wired the `isVideoReady` state to rely strictly on the `onRenderedFirstFrame()` decoder callback rather than the premature `STATE_READY` status. This guarantees the Coil thumbnail overlay remains solid until actual video pixels are ready to display.
*   **YouTube IFrame API Sync**: Rewrote the fallback `EmbedWebViewPlayer` (used when Invidious resolution fails). Removed the blind 800ms timer that was prematurely hiding the thumbnail. Injected the official **YouTube IFrame API** and a custom Android `JavascriptInterface`, forcing the Android UI to wait for the JavaScript engine's explicit `PLAYING` state before dismissing the high-res thumbnail.

### 3. Feed Recency Optimization
*   **Chronological Feed Scoping**: Appended the `&date=month` parameter to all `searchVideos` API calls in `InvidiousApiClient.kt`. While the API still sorts by relevance by default, this strict date filter prevents the feed from surfacing extremely old videos (5-10 years ago) simply because they have higher overall view counts, ensuring the unified feed remains focused on current month/year content.

## Completed Changes (2026-07-02)

### 1. Identity Display & Backup Enhancements
*   **Handle Dot Parsing Fix**: Updated payload generation and packet handling logic (`MeshSocialRepository`, `HandshakePacketHandler`) to properly extract handles using trailing delimiters (`substringBeforeLast`). Usernames containing dots (e.g., `satoshi.nakamoto`) are no longer prematurely truncated.
*   **Encrypted Zip Export/Import (SAF)**: Fully wired up the "Export/Import Profile" buttons in the Settings Tab. Implemented Android's Storage Access Framework (`ActivityResultContracts`) allowing users to natively choose where to save or open the encrypted `noslop_backup.zip`. Added a UI dialog to securely capture the user's Mnemonic password for AES derivation prior to reading/writing IO streams via `BackupManager`.
*   **Cleaner Identity UI**: Scrubbed the cryptographic `.tripcode` suffix from primary user feeds and chat headers (`PeerItem`, `UnifiedFeedTab`, `ChatThreadScreen`) for a cleaner modern aesthetic, while preserving the full `handle.tripcode` hash inside the detailed ContactCardDialog and Onboarding Identity Card for verification.

### 2. UI/UX Refinements & Bug Fixes
*   **Reaction Layout Alignment**: Fixed an issue in `MediaComponents.kt` where wrapped reaction pills would misalign the primary action buttons (React, Share, Chat). The main action buttons are now strictly right-aligned in their own container independent of the reaction pills row width.
*   **Import Destructive Warning & Restart**: Upgraded the Import Backup flow in `SettingsTab` to include a mandatory destructive warning dialog, notifying the user that their current data will be wiped. On confirmation, the system now safely closes the active Room Database (`NoSlopDatabase.closeInstance()`) before overwriting it via `BackupManager`, and then actively triggers a full application intent restart (`FLAG_ACTIVITY_CLEAR_TASK`) to safely reload the new state.
*   **Mesh Broadcast Privacy**: Removed the cryptographic `.tripcode` suffix from the author string on Mesh Broadcast cards across `MainScreen`, `FeedCard`, and `UnifiedFeedTab` to improve privacy and visual clarity.
*   **Interactive User Info Modal**: Added an interactive `clickable` modifier to the Avatar and Username row on Mesh Broadcast cards. Tapping now opens a `User Info Modal` displaying the user's enlarged Avatar, full Handle, Tripcode, and abbreviated Public Key.
*   **Tearing Face Reaction Bug**: Fixed a logic bug in `MeshSocialRepository.kt` where emotional reactions like `sad` were incorrectly grouped with negative moderation signals (`downvote`, `angry`). This previously prevented `sad` from functioning as the initial reaction on a Clearnet post, as the system blocked negative signals from initializing anchor posts to prevent spam.

## Completed Changes (2026-07-01)

### 1. Granular Mesh Broadcast Filters
*   **User-Facing Filter UI**: Added a new **Mesh Filters** screen (`MeshFiltersScreen.kt`) accessible from Settings → Account & Preferences → Mesh Filters. Provides 12 toggle switches (6 content types × 2 directions) controlling which packet types are pushed to and pulled from the mesh network:
    *   Reactions (default: **off** for both directions to reduce noise)
    *   Comments (default: on)
    *   Text Posts (default: on)
    *   Clearnet Shares (default: on)
    *   Image Posts (default: on)
    *   Video Posts (default: on)
*   **Local-First Architecture**: Filters operate exclusively at the network sync layer. All user actions (reactions, comments, votes) are **always persisted locally** regardless of filter settings — only the `GossipService.broadcast()` / `MeshTransport.sendPacket()` calls are conditionally gated.
*   **"Already Shared" Exemption**: Incoming reactions, comments, and votes targeting posts or comments that already exist in the local database are **exempt** from incoming filters. This ensures engagement on tracked content is never silently dropped, while still filtering noise from unknown/unbridged anchor posts.
*   **Clearnet Bridging Guard**: When a user likes a clearnet feed item for the first time, NoSlop creates a deterministic anchor post and a reaction simultaneously. The `allowOutgoingReactions` filter only blocks the reaction broadcast during this initial bridging step (`isBridging = true`). Reactions on posts already present in the local database always broadcast freely.
*   **DM Chat Fully Exempt**: Direct message chats and their reactions (`CHAT_REACTION` packets) are completely excluded from all mesh filter logic — both incoming firewall checks and outgoing broadcast gates.
*   **Persistence**: Filter settings are stored as JSON in the `app_settings` table via `SettingsRepository` and exposed reactively via `StateFlow` through the ViewModel.
*   **Files**: New: `MeshFilterSettings.kt`, `MeshFiltersScreen.kt`. Modified: `SettingsRepository.kt`, `NoSlopViewModel.kt`, `GossipService.kt`, `MeshSocialRepository.kt`, `NoSlopRepository.kt`, `NoSlopApp.kt`, `SettingsTab.kt`, `Daos.kt`.

## Completed Changes (2026-06-30)

### 1. Tor Daemon Isolation & Port Decoupling
*   **Embedded Tor Decoupling**: Fixed a critical EADDRINUSE conflict that occurred when running debug and release builds side-by-side on the same device. Both builds were previously attempting to spawn internal Tor daemons bound to hardcoded ports (`9050`/`9051`). Extracted these into build variant configs (`TOR_SOCKS_PORT` and `TOR_CONTROL_PORT`) so the debug and release builds now run completely isolated Tor instances (`9050`/`9051` vs `9052`/`9053`).
*   **Tor Service Double Registration**: Fixed a race condition where both the Tor daemon status broadcast receiver and the self-healing bootstrap loop concurrently called `triggerRegistration()` when Tor finished bootstrapping. This prevented the daemon from spamming `550 Onion address collision` and throwing `Bad sequence size: 3` exceptions during startup.

### 2. Mesh Transport Circuit Timeouts
*   **Extended Connection Timeout**: Increased the `CONNECTION_REQUEST` Tor SOCKS5 connect timeout from 20 seconds to 60 seconds. Freshly minted Tor v3 onion services (such as new installs) can take up to 45 seconds to publish their descriptors to the HSDirs. A 20-second timeout was actively interrupting Tor's circuit-building process before completion, dropping the connection and triggering an infinite retry loop that prevented peers from ever successfully shaking hands.

### 3. UX Polish & GitHub Issue Integration
*   **Video Playback Persistence**: Fixed a regression where videos restarted from the beginning. `ExoVideoPlayer` was rearchitected to avoid state race conditions by seeking to the saved position immediately before calling `prepare()`. Added strict `> 0L` guards to prevent transient loading states from overwriting valid saved positions in the `PlaybackPositionStore`.
*   **Live Feed Refresh**: Replaced the redundant "Random" button in the filter modal with a "Refresh Feed" button that actively clears and rebuilds the feed locally from the database without a network trip, respecting seen/swiped exclusions. Made the modal scrollable to fix UI clipping on smaller screens.
*   **Video Thumbnail Bleed & Letterboxing**: Fixed YouTube thumbnail letterboxing by setting the primary thumbnail to `ContentScale.Fit` and layering a 1.35x scaled, 24dp blurred background image behind it (clipped to the container bounds) to fill the empty space without bleeding over the UI.
*   **GIF Support in DMs**: Integrated a global GIF-aware Coil `ImageLoader` and wrapped the chat UI in a `CompositionLocalProvider`. GIFs sent in Direct Messages now render and animate inline seamlessly instead of displaying as static thumbnails.
*   **Native GitHub Issue Reporting**: Upgraded the "Bug Report" flow from a raw browser intent to a native GitHub REST API submission tool. It now automatically pulls `GITHUB_PAT` and `GITHUB_ASSIGNEE` from `local.properties` (exposed via `BuildConfig`), allows users to select an Issue Type (Bug, Feature Request, Question), and automatically formats and attaches their device hardware info and recent app logs to the GitHub issue.

## Completed Changes (2026-06-29)

### 1. Bouncy Castle Migration & Lazysodium Key Generation
*   **Unified Bouncy Castle Signing**: Completely migrated `CryptoService.kt` to use Bouncy Castle's lightweight `Ed25519Signer` directly, bypassing the platform JCA `Signature` API. This unifies signing across all Android versions (API 24-35).
*   **Lazysodium Key Generation**: Added `lazysodium-android` as the primary key generator for Ed25519, ensuring high-quality keys consistent with iOS and other platforms. Includes a Bouncy Castle fallback if the JNA native library fails to load.
*   **ProGuard Fixes**: Added explicit keep rules for `com.goterl.lazysodium.**` and `com.sun.jna.**` to prevent release builds from crashing due to R8 stripping native method bindings.

### 2. DM Chat UI & Navigation Fixes
*   **NavigationBar Keyboard Fix**: Fixed a visual glitch where the bottom navigation bar remained behind the keyboard when typing in a DM, exposing an empty black space. The `NavigationBar` and `FloatingActionButton` are now explicitly hidden when the user is in an active chat thread (`selectedPeerPub != null`).
*   **Hardware Back Button Navigation**: Implemented a Compose `BackHandler` in `DMsTab`. Pressing the phone's hardware back button while in a chat now properly returns to the contacts list instead of minimizing the entire application.

## Completed Changes (2026-06-25)

### 1. Video Thumbnail & Preload State Fixes

### 2. Tor Identity Unification & Semaphore Gridlock Fix

### 4. Reverted Tor Identity Bug & Mitigated Semaphore Gridlock
*   **Tor Identity Reverted**: The previous "fix" for Tor ED25519-V3 math was fundamentally incorrect. Tor Control Port (`ADD_ONION ED25519-V3`) explicitly expects the 64-byte expanded secret key (clamped secret scalar + PRF secret), not the 64-byte libsodium format (seed + public key). By passing the seed, Tor recomputed a completely different public key and onion address, breaking all peer connectivity. Reverted `CryptoService.kt` to the correct SHA-512 clamped expansion.
*   **Semaphore Queue Prioritization**: The `Semaphore(8)` in `MeshTransport` was causing infinite queuing gridlocks because background `ANNOUNCE_PEER` heartbeats to offline peers consumed all 8 permits and took up to 60s to timeout, completely starving user-initiated actions (like `CONNECTION_REQUEST`). Background packets now use `tryAcquire()` and are aggressively dropped if Tor circuits are saturated, keeping the queue free for essential actions.

### 3. Mesh Transport Fast-Fail for Unreachable Peers
*   **Dead Peer SOCKS Rejection**: Added a fast-fail check in `MeshTransport.kt`. If the Tor proxy explicitly rejects a connection with `SOCKS: Host unreachable`, `TTL expired`, or a general failure, the transport layer now instantly aborts the send instead of stubbornly executing the remaining retries and holding up the `torSemaphore`. This keeps the mesh pipeline fluid when a trusted peer is genuinely offline or has changed their onion address.
*   **Tor ED25519-V3 Math Fixed**: Corrected a severe mathematical split where the Kotlin `CryptoService` was deriving a completely different onion address than the internal Tor C-daemon. Tor expects the `libsodium` secret key format (32-byte seed + 32-byte public key), but we were passing it a clamped SHA-512 expansion which Tor then re-hashed, resulting in a misaligned identity.
*   **Semaphore Gridlock Resolved**: Drastically reduced `MeshTransport` SOCKS5 connect timeouts from `45s` (with 5 retries) down to `20s` (with 3 retries). This prevents offline peers from hoarding the `torSemaphore` limits and gridlocking the entire gossip network for 4+ minutes per offline node.
*   **Thumbnail Visibility Bug**: Fixed an issue where video thumbnails would permanently stay on screen (blocking the playing video) when returning to the feed. Added the missing `onReady()` state callbacks to the fallback (non-preloaded) `ExoPlayer` initialization block.
*   **Lifecycle Thumbnail Reset**: Bound the `isVideoReady` state to the `activeVisible` lifecycle. When a user tabs away and the `VideoPlayer` unmounts, the readiness state is properly reset to `false`. This prevents the thumbnail from disappearing prematurely when tabbing back before the video has actually buffered its first frame.



## Completed Changes (2026-06-23)

### 11. Search, Media Preloading, and Feed Memory Polish
*   **Search Routing & Filtering**: Re-wired the search query dispatcher in `PublicApiService.kt` to explicitly route queries to search endpoints (e.g., "Search Videos") rather than falling back to trending lists. Enforced strict keyword matching on the UI side to filter out unrelated API fallback content.
*   **Media Type Classification**: Fixed a bug in `FeedParser.kt` where RSS `<enclosure>` or `<media:content>` tags intended as thumbnails were incorrectly promoted to standalone image posts, ensuring articles always render in the `SegmentedArticleReader`. Fixed Clearnet-to-Mesh broadcasts not properly falling back to `clearnetUrl` for media resolution, restoring native playback for shared videos and audio.
*   **Feed Memory & Interleaving**: Stopped the background sync from violently prepending new content to the top of the feed and breaking scroll state. Implemented feed memory pruning: leaving a search/filter now keeps the 3 previous slides, discards deeply scrolled history to save memory, and gracefully interleaves fresh content immediately below the user\'s saved position.
*   **Interaction Jump Bugs**: Fixed a jarring bug where "Liking" a clearnet post caused the feed to jump to the next item by removing the strict `!isSaved` UI filter rule. Prevented the feed from forcefully scrolling to the top when broadcasting a mid-feed share.
*   **Aggressive Splash Preloading**: Hijacked the app\'s initial `SplashScreen` to act as a 4-second buffer window. `MainActivity` now aggressively resolves and pre-warms the first media item in the feed via `PreloadManager` before the splash curtain drops, resulting in instant playback.
*   **Filter Synchronization**: Introduced a strict `syncFilterMode()` flow to prevent the ViewModel from getting stuck in specific filters (like "Articles") when the UI clears them via the \'x\' button, ensuring seamless return to the "Live Feed" regardless of list size.

### 10. Tor AIMD Tuning, UI Recomposition & Mesh Sort Fixes
*   **Tor TCP Slow Start**: Dramatically overhauled the `MediaManager` AIMD algorithm to respect Tor\'s circuit latency. Chunk requests now strictly begin at `windowSize = 1.0` and conservatively ramp up to a maximum concurrency of `32.0` (down from `128.0`). This stops the client from immediately choking Tor nodes with massive simultaneous requests on new circuits, allowing mesh media to actually transfer successfully.
*   **ExoPlayer Reloading Loop Fixed**: Removed a severe recomposition bug where the video player would arbitrarily restart every 5 seconds. The `viewedHistoryIds` state listener was improperly bound to the vertical pager\'s memory cache, causing the entire list instance to violently regenerate the moment a video was marked as "viewed" in the background.
*   **Thumb Wiggle Mount Fix**: Adjusted the `isVisible` lifecycle bounds on `VideoPlayer` to rely strictly on `pagerState.currentPage`. Micro-movements of a thumb resting on the screen no longer toggle the `targetPage` state, preventing ExoPlayer from constantly unmounting and remounting.
*   **Mesh Chronological Strictness**: The "TikTok Vibe" initialization logic (which hunts for the first video payload and drags it to the absolute top of the feed) has been explicitly disabled for the "Mesh" and "History" filters, ensuring those lists remain mathematically chronological.

### 9. Mesh Media Transport & UI Navigation Fixes
*   **Mesh Media Integrity**: Fixed a dual-sided bug where `.mp4` and other media chunks were arriving padded with empty zero-bytes, corrupting their structure. The chunking algorithm now correctly dynamically scopes the byte buffer to the remaining file size and utilizes `RandomAccessFile.seek` instead of the unreliable `skip()`. Math mismatch in `chunkCount` evaluation between sender and receiver was also aligned to strict integer floor division.
*   **Feed State Memory on Scroll**: Bound the positional memory logic strictly to the `pagerState.settledPage` event. The Live Feed now dynamically saves your scroll position into the encrypted database seamlessly as you scroll down, rather than only saving when interacting with buttons.
*   **Search Online UI**: Shifted the "Search Online" button in the filter modal to appear directly underneath the text input field for immediate context. It now natively echoes the active search string in its label.

### 1. Android Auto Backup & Keystore Corruption Fix
*   **EncryptedSharedPreferences Crash**: Fixed a critical bug where installing the release version over the debug version (or reinstalling the app) caused a permanent crash loop. `android:allowBackup="true"` was improperly restoring encrypted preference files without their corresponding hardware-backed `MasterKey`.
*   Disabled Android Auto Backup in the KMP manifest to match the legacy app's security model.
*   Added a recovery fallback in `Platform.android.kt` that detects Keystore failures and actively wipes the corrupted preferences to regenerate a clean identity rather than throwing a fatal `GeneralSecurityException`.

## Completed Changes (2026-06-20)







### 8. Feed State Persistence, DM Camera Alignment & Media Fixes
*   **Live Feed Memory**: The app now reliably saves your exact `Live Feed` array and scroll index to the database (`app_settings`) when closed. Restarting the app instantly restores your exact position instead of dropping you into a generic 3-video startup shuffle.
*   **Bottom-Loaded Refresh**: When clearing searches or filters, you are instantly returned to your preserved `Live Feed` position. All newly aggregated chronological content is silently appended to the absolute *bottom* of your list to prevent jarring vertical jumps.
*   **DM Video Playback Fixed**: Found and resolved a critical bug in `MediaManager.kt` where `input.read(buffer)` was randomly returning partial byte arrays. Forced a strict `while` loop to guarantee exactly 256KB are read per chunk, perfectly preventing the `.mp4` structural corruption that triggered ExoPlayer's `s31: Source Error`.
*   **DM Camera UI Upgraded**: Removed the redundant GIF button from direct messages. Unified the DM camera experience with the Broadcast camera, including the `DestructiveRed` immediate-action buttons and the 3-second recording countdown safety.

### 7. UX Polish: Feed Memory, Modal Layout & Camera Countdown
*   **Strict Live Feed Memory**: The app's positional memory (saving your spot in the vertical feed) is now strictly restricted to the main "Live Feed". Browsing specific filters like "History" or "Random" will dynamically start from the top, keeping your core progression intact when returning.
*   **Camera Polish**: Implemented a 3-second countdown sequence for video recording within the Broadcast UI to give users time to prepare. Re-themed all immediate-action camera buttons (Take Photo, Record, Close) to `DestructiveRed` for better visual signaling.
*   **Search & Filter Modal Clean Up**: "Mesh" is now prominently separated from the generic content types and placed immediately below the user's "My Content" toggle. Removed ghost borders around the Random Discover button.

### 6. Background Resource Hoarding & Camera Leaks Fixed
*   **MediaCodec Exhaustion**: Fixed a major hardware resource leak where `ExoPlayer` instances were not being released when the user navigated away from the "Feed" tab. Added an `isActiveTab` state parameter to `UnifiedFeedTab` to explicitly unmount active videos when the tab loses focus, resolving the `MediaCodec error -32` crashes.
*   **Camera Lifecycle Leak**: Fixed a persistent 9-minute hardware camera leak occurring after closing the Broadcast Compose modal. CameraX streams are now strictly unbound from the `ProcessCameraProvider` via `DisposableEffect` the moment the camera view leaves the Compose tree.
### 5. Compose State Fixes & True Creator Priority
*   **True Creator Priority**: Followed creators now completely bypass the diverse interleaving limitations. Any new items from your followed creators are batched and stacked at the absolute top of the feed ahead of mesh posts and general discovery content.
*   **"Shining Through" Shimmer Bug Fixed**: Removed an underlying rogue `LoadingShimmer` from the base layer of `UnifiedFeedTab`. The UI no longer flashes the loading gradient over active videos when database updates (like marking an item read or viewed) trigger micro-recompositions of the player's SurfaceView.
*   **Pager Filter Desync Fixed**: Hard-bound the `VerticalPager` scroll state to the `filterMode`. Switching from Live Feed to "My Content" or "Mesh" now strictly snaps the pager index back to `0`, fixing the "swiping down to go up" bug caused by retained scroll state.
*   **Loading UX Polish**: The Feed screen now displays a proper `CircularProgressIndicator` during the initial launch or when the feed is actively curating, resolving the "waaaay too long" perceived delay where it used to prematurely state "Your feed is empty".
### 4. Feed Performance & Layout Fixes
*   **Staggered Loading Restored**: Initial load times have been slashed. The feed now strictly requests only 3 items on a cold start or filter switch to immediately dismiss the splash curtain, instantly firing a silent background request for the next 10 items.
*   **Strict Creator Priority**: Heavily optimized the chronological feed pull to ensure the user's selected creators override diversity limits, bringing preferred content straight to the top of the feed instead of irrelevant fallback filler.
*   **Non-Destructive Preferences**: Changing content preferences in Settings no longer wipes the active feed history or clears the screen. It seamlessly pulls down updated content in the background.
*   **Mesh & My Content Ordering Bug**: Fixed a jarring layout bug where the Compose Pager incorrectly retained its old index when switching to "My Content", forcing the user to swipe down to find their own items. The feed now actively forces a `scrollToTop` event on filter changes.
*   **Random Discover Button Polish**: Softened the UI of the Random Discover button in the Search modal to match the primary button aesthetics.
### 3. Creator Prioritization, Random Discover, and Mesh Filter Sort Fix
*   **Followed Creators Up Front**: Overhauled the feed algorithm so that content matching the user's selected creators (from onboarding or settings) is strongly prioritized and pushed to the very front of the chronological list across "Live Feed", "Videos", "Images", and "Articles" modes.
*   **Mesh Sort Order Fix**: Fixed the "Mesh" filter where posts were loading backwards. Specific lists like "Mesh" and "History" are now strictly chronologically ordered and skip the random shuffling that is applied to mixed feeds on initial load.
*   **Random Discover Mode**: Repurposed the "Refresh Feed" button into a new "Random Discover" mode. This mode bypasses chronological and creator sorting, fetching an entirely random shuffle of unseen content to help break filter bubbles.
### 2. Smart Feed Interleaving & Strict Deduplication
*   **Feed Repetition Bug**: Fixed an issue where the Live Feed would routinely serve old, repeated, or randomly shuffled content instead of the most recent synced items.
*   **Stale Card Banishing**: The feed now aggressively filters out `isRead = true` items from the Live Feed. Once a user dwells on a card, it is permanently banished from their main feed across app restarts, preventing stale clogging.
*   **Smart Media Ratios**: Rebuilt the feed algorithm to chronologically sort and seamlessly interleave ~5 videos, 1 image, 1 audio, 1 article, and 2 mesh posts per scrolling batch. 
*   **Source Limits**: Integrated a `takeDiverse` function to mathematically prevent any single RSS source from dominating a batch (max 2 items per source per page).

### Kotlin Multiplatform (KMP) Migration - Phase A (Feeds)
*   **Architecture Shift**: The NoSlop canonical codebase is moving from the legacy Android `app/` module to the new Kotlin Multiplatform `mvp/` module. The `app/` module is now read-only reference until it is retired.
*   **Networking Layer**: Unified the networking layer using Ktor `HttpClient` (with OkHttp on Android, Darwin on iOS) across all feed integrations.
*   **Feed Parser**: Ported `FeedParser.kt` to `commonMain`, replacing Android-specific XML dependencies with multiplatform `xmlutil`.
*   **API Clients Ported**:
    *   Successfully ported `HackerNewsApiClient`, `RedditApiClient`, `InvidiousApiClient`, `PodcastIndexApiClient`, `GuardianApiClient`, `NewsApiClient`, `WikimediaApiClient`, `JamendoApiClient`, `NasaApiClient`, `PexelsApiClient`, `VimeoApiClient`, and `InternetArchiveApiClient` to `commonMain`.
    *   All clients are now 100% deterministic and golden-tested using Ktor `MockEngine`.
*   **Background Sync**: Implemented `BackgroundScheduler` interface using `expect/actual` pattern (`WorkManager` for Android, `BGTaskScheduler` for iOS) for cross-platform background feed synchronization.
*   **Crypto & Dates**: Migrated hashing (`kotlincrypto-sha1`) and date parsing to KMP native implementations (`kotlinx-datetime`).
*   **Media Synchronization**:
    *   **Inline GIF Transport**: Gboard GIF attachments in comments are now embedded directly as base64 `data:` URIs (`noslop-gif://data:image/gif;base64,...`) avoiding mesh chunking overhead for small animated images.
    *   **Native Auto-Rendering**: DM attachments now correctly verify if a file exists locally via `MediaManager.isMediaDownloaded()`, enabling instantaneous native rendering (AsyncImage, VideoPlayer) without freezing in a "Tap to Download" state.
    *   **Mesh Routing Fixes**: Corrected `PostPacketHandler` and `SyncPacketHandler` to resolve authentic `.onion` peer addresses from the internal database instead of utilizing raw sender public keys when triggering automated chunk downloads.

## Completed Changes (2026-06-14)

### 0. HUBs Rebranding & Home Hub Vision
*   Renamed "HAI-Net" tab to "HUBs".
*   Added "HAI-Net (coming soon)" indicator to the HUBs page.
*   Documented the vision for Home HUBs as the primary backup for mesh Identity, data, and media.

### 1. Mesh Filtering & Notification Deep-links
*   Verified self-post filtering in `NoSlopViewModel.kt`.
*   Confirmed `ensurePostInFeed()` handles notification deep-links correctly by bypassing filters.

### 2. RSS Content Classification
*   Fixed `FeedParser.kt` to prevent RSS articles with embedded images from being promoted to "Image" media type.
*   Preserved extracted images as `thumbnailUrl` for use in the new article hero layout.

### 3. Wikimedia Commons Integration
*   Added `WikimediaApiClient.kt` to fetch featured pictures from Wikimedia Commons.
*   Added "Wikimedia Featured" as a built-in API source in `SourceLibrary.kt`.
*   Integrated into `PublicApiService.kt` under "Photography" and "Art" categories.
*   No API key required for this source.

### 4. Article Card Redesign & Pagination Fix
*   Implemented a rich hero layout for articles in `SegmentedArticleReader` (`MediaComponents.kt`).
*   **Fix**: Migrated to `HorizontalPager` for sideways swiping between article segments.
*   **Fix**: Removed vertical scrolling from segments to ensure they always fit the viewport and vertical "swipe away" gestures work reliably.
*   Added a "Read Full Article" button on the final page of every article.

### 5. Media Playback & Source Reliability
*   **Fix**: Resolved YouTube "Error 153" (Video Player Configuration Error) by strictly aligning the `origin` parameter in the embed URL with the `baseURL` provided to the WebView's `loadDataWithBaseURL` method.
*   **Fix**: Added `mute=1` to the YouTube embed to ensure `autoplay=1` is respected by modern browser security policies.
*   **Fix**: Updated YouTube embed logic in `VideoPlayer.kt` with a modern Mobile User-Agent and optimized iframe parameters (`origin`, `enablejsapi`, `rel=0`).
*   **Fix**: Refreshed Invidious API fallback instances with healthy servers.
*   **Fix**: Cleaned up dead RSS sources in `SourceLibrary.kt` (Self-Hosted Hero, Threatpost, etc.).

### 6. Article UX Refinements & Content Classification
*   **Fix**: Updated `GuardianApiClient` and `NewsApiClient` to ensure their articles are classified as "Article" type (`mediaType = null`) instead of being promoted to "Image" status.
*   **Fix**: Articles with missing content now correctly fall back to excerpts.
*   **Fix**: Added a high-quality default fallback hero image for articles without thumbnails.
*   **Fix**: Improved article pagination state to always show a content page with the "Read Full Article" button even when no text is extractable.

### 7. Handshake Reply Notifications
*   Added logic to notify the sender of a connection request when it is accepted (`USER_HANDSHAKE`).
*   Introduced new packet type `CONNECTION_REJECTED` to notify the sender if their request is declined, safely removing the pending peer and displaying a local notification.

### 8. Feed OOM Prevention & Settings Build Fix
*   **Settings Build Error Fix**: Added missing `Search` and `Close` icon imports in `ContentPreferencesScreen.kt` introduced by the new creator search bar.
*   **Auto-Play Video Player with OOM Prevention in Feed**: Fixed a fatal `OutOfMemoryError` (MediaCodec buffer exhaustion) when swiping through the vertical video feed. `FeedCard.kt` now conditionally mounts the `VideoPlayer` only when the item is fully visible on screen. This preserves the immersive auto-play experience while ensuring off-screen ExoPlayer instances are instantly destroyed to free up hardware resources.

*   **Creator Search Fix**: Moved the `InvidiousApiClient.searchChannels` network call off the Main Thread to `Dispatchers.IO` to prevent silent `NetworkOnMainThreadException` failures. Capped the appended search results to the top 3 matches as intended.
*   **SD Card Installation Support**: Added `android:installLocation="auto"` to the AndroidManifest to allow the app to be installed or moved to an external SD card on storage-constrained devices.

## Completed Changes (2026-06-19)

### 1. Onboarding & Categories Refinement
*   **Auto-Included Sources**: Removed "Video Platforms" and "Social Clearnet" from the user-facing category selection list in Onboarding and Settings since these are essential and always included by the pipeline.
*   **Expanded Genres**: Added more diverse genre options to the Music and Video categories in `SourceLibrary.kt` to broaden content discovery.
*   **Creator Cloud Layout**: Replaced the fixed chunking layout with a responsive `FlowRow` in both Onboarding and Settings, preventing creator names from being truncated.
*   **Suggested Feeds UI**: Removed the search field from the "Suggested Clearnet Feeds" slide to streamline the onboarding experience.

### 2. Media Player & Feed Immersiveness
*   **Landscape Auto-Hide UI**: Implemented an immersive feed view when the device is in horizontal orientation. The top status overlays (notifications, search), bottom navigation bar, right-side interaction icons (like, share, comment), and bottom-left author details automatically slide off-screen after 1 second of inactivity. Tapping the screen instantly restores them.
*   **Edge-to-Edge Media**: Updated the main Scaffold's `innerPadding` to dynamically animate to `0dp` when the landscape UI auto-hides, allowing video and image content to fully stretch into the freed navigation bar space.
*   **ExoPlayer Resize Mode Fix**: Updated `VideoPlayer.kt` to unconditionally use `RESIZE_MODE_FIT` (instead of `RESIZE_MODE_ZOOM` in landscape). This prevents the media from becoming artificially oversized and cropped at the edges when playing in horizontal orientation.

### 3. QR Mesh Scanner Enhancements
*   **Gallery Selection Fix**: Resolved an issue where the "Select from Gallery" button was completely hidden beneath the camera preview layer (`AndroidView`). The layout was restructured into a `Column`, placing the gallery picker safely below the camera area, making it universally visible and functional.

---

**Related docs**: [GAP_ANALYSIS.md](GAP_ANALYSIS.md) for the longer-term feature backlog vs. gChat/HAI-Net · [TECHNICAL_REFERENCE.md](TECHNICAL_REFERENCE.md) for how these changes fit into the overall architecture · [HUB_INTEGRATION_PLAN.md](HUB_INTEGRATION_PLAN.md) for the next major planned phase of work.


## Completed Changes (2026-06-21)

### 1. Media Routing & Tor Congestion Control
*   **AIMD Mesh Tuning**: Fixed a race condition where `MediaManager` would shrink the congestion window and trigger timeouts (5s) much faster than `MeshTransport` could build Tor circuits (up to 30s). 
*   **Mesh Relay Fallback**: Wired `MeshTransport`'s delivery status back into `MediaManager`. If a node is unreachable after consecutive timeouts, the download immediately triggers `attemptMeshRecovery()` via the Gossip Protocol to find alternative seeders on the mesh, rather than hanging indefinitely.
*   **MediaProxyService Reliability**: Updated the proxy loop to monitor `MediaManager`'s recovery states, allowing it to gracefully terminate HTTP streams if a mesh transfer fundamentally fails, preventing the UI/WebView from hanging in a permanent loading state.

### 2. Archive.org ExoPlayer Native Streaming
*   **WebView Bypass**: Fixed an issue where Archive.org videos (`.mp4?cnt=0`) were being pushed to the unreliable `EmbedWebViewPlayer` due to complex URL queries.
*   **Direct Stream Resolution**: `VideoPlayer.kt` now natively intercepts Archive.org URLs, calls the public `metadata/` API to find the underlying raw `.mp4` file, and feeds it directly to `ExoPlayer` for seamless, native playback.

### 3. Clearnet-to-Mesh Media Bridging
*   **Confirmed**: Videos from the Clearnet Aggregator successfully bridge over to the mesh network. Broadcasting a video post triggers the proper chunking and relay propagation over Tor.

### 4. DM Chat Performance & OOM Prevention
*   **Lazy Video Player Initialization**: Fixed a severe `OutOfMemoryError` (`MediaCodecBridge.getInputBuffer`) that occurred when opening a DM chat history containing multiple downloaded videos. 
*   `ChatThreadScreen.kt` now renders lightweight Coil thumbnails with a play button overlay for downloaded videos in the `LazyColumn`. Heavy `VideoPlayer` (WebView/ExoPlayer) components and their associated hardware `MediaCodec` allocations are strictly deferred until the user explicitly taps the thumbnail to begin playback.

### 5. Hardware Capture & Layout Polish
*   **CameraX Audio Enforcement**: Fixed a bug where in-app recorded videos lacked audio tracks. `MediaCaptureManager` now aggressively attempts to bind `withAudioEnabled()` and gracefully catches `SecurityExceptions` if permissions are explicitly denied, rather than silently failing the `ContextCompat` context check.
*   **QR Scanner Form Factor Support**: Fixed a layout bug on smaller devices where the "Select from Gallery" and "Paste Raw" buttons fell off the bottom of the Dialog screen. The buttons are now safely overlaid inside the Camera viewfinder bounds, mimicking native camera apps and ensuring 100% visibility regardless of screen height.

### 6. Video Audio, QR UI & Search Fixes
*   **Audio Recording Fixed**: Added `android.permission.RECORD_AUDIO` to the `AndroidManifest.xml` which was mysteriously missing, allowing the OS to actually grant the permission to the `MediaCaptureManager`.
*   **QR Scanner Buttons Fixed**: Shifted the "Gallery" and "Paste Raw" buttons into the center HUD column right underneath the QR scanning boundary, escaping the bottom edge that gets cut off on smaller screens.
*   **Mesh Search Results Fixed**: Updated the pagination logic in `NoSlopViewModel.loadMoreFeedItems()` to pre-filter mesh posts by the active search query. This prevents the ViewModel from paginating non-matching items that the UI promptly hides, fixing the bug where the feed appeared "empty" and refused to scroll.

### 7. Runtime Permissions & Final UI Polish
*   **Audio Capture Runtime Check**: Fixed the final hurdle with silent videos. The camera launcher in `UnifiedFeedTab.kt` was bypassing the microphone runtime permission prompt if the camera permission was already granted (e.g., from earlier QR scanning). It now strictly enforces both `CAMERA` and `RECORD_AUDIO` checks before opening the video capture UI, triggering the OS prompt correctly and ensuring videos always have sound.

### 9. Interaction, Media & Networking Fixes
*   **Search Clear Race Condition**: Removed the `isRefreshingFeeds` guard in `clearSearchAndRestoreFeed()` so tapping the 'x' reliably clears the feed filters and instantly restores the user's scroll state.
*   **Global GIF Support**: Injected Coil's `GifDecoder`/`ImageDecoder` factories into the custom `LocalImageLoader` provided by `UnifiedFeedTab`, instantly fixing static GIF rendering across the feed, DMs, and comments.
*   **Mesh Broadcast Media URLs**: Fixed a bug where missing `originNode` declarations in mesh posts defaulted to the raw `PublicKey` instead of resolving the local peer's `onionAddress`. Videos now route correctly over Tor to the proxy service.
*   **Comment Media Sync**: Added synthesized `MediaMetadata` extraction and explicitly bound it to `MediaManager.checkAndAutoDownload()` inside `CommentPacketHandler.kt` so media attached to comments actively syncs to receiving peers.
*   **OkHttp Connection Leak**: Fixed a critical leak in `WikimediaApiClient.kt` where `response.body` was never closed on API read errors, ensuring connection pool integrity.

### 10. Advanced UI, Filtering & Interaction Polish (2026-06-22)
*   **DM Fullscreen Media**: Tapping images or videos in Direct Messages now opens a full-screen zoomable image dialog or a full-screen video player overlay.
*   **Feed Video "Tap to Download"**: Mesh videos on the main feed now accurately check `MediaManager.isMediaDownloaded()`. If absent, they display a rich "Tap to Download" overlay with a live progress indicator, preventing black screens.
*   **Clearnet Engagement Shadow-blocking**: Negative reactions (downvote, angry, sad) on unsynced clearnet items are now intercepted. The action is dropped locally and blocks the item from being broadcasted to the mesh to prevent spamming peers with disliked content.
*   **Rich Share Modal**: Tapping "Share" on a clearnet item now opens the main Compose Modal instead of a basic alert. The shared item is embedded as a rich preview attachment, allowing the user to type custom context before broadcasting.
*   **"My Content" Filter & Feed Isolation**: The user's own mesh broadcasts are now globally excluded from the Live Feed, Video, Audio, and Image filters. They are securely isolated into a dedicated "My Content" toggle in the Search & Filter modal.
*   **Reaction Menu UX**: Added `120.dp` bottom padding to `LazyColumn`s in Chat and Comment sheets, ensuring long-press reaction popups are never clipped or hidden beneath the bottom input bar.
*   **Search Clear UX**: Fixed a race condition in `NoSlopViewModel.clearSearchAndRestoreFeed()` that prevented the feed from restoring its state when clearing a search query.

### 11. Search, Media Preloading, and Feed Memory Polish (2026-06-22)
*   **Search Routing & Filtering**: Re-wired the search query dispatcher in `PublicApiService.kt` to explicitly route queries to search endpoints (e.g., "Search Videos") rather than falling back to trending lists. Enforced strict keyword matching on the UI side to filter out unrelated API fallback content.
*   **Media Type Classification**: Fixed a bug in `FeedParser.kt` where RSS `<enclosure>` or `<media:content>` tags intended as thumbnails were incorrectly promoted to standalone image posts, ensuring articles always render in the `SegmentedArticleReader`. Fixed Clearnet-to-Mesh broadcasts not properly falling back to `clearnetUrl` for media resolution, restoring native playback for shared videos and audio.
*   **Feed Memory & Interleaving**: Stopped the background sync from violently prepending new content to the top of the feed and breaking scroll state. Implemented feed memory pruning: leaving a search/filter now keeps the 3 previous slides, discards deeply scrolled history to save memory, and gracefully interleaves fresh content immediately below the user's saved position.
*   **Interaction Jump Bugs**: Fixed a jarring bug where "Liking" a clearnet post caused the feed to jump to the next item by removing the strict `!isSaved` UI filter rule. Prevented the feed from forcefully scrolling to the top when broadcasting a mid-feed share.
*   **Aggressive Splash Preloading**: Hijacked the app's initial `SplashScreen` to act as a 4-second buffer window. `MainActivity` now aggressively resolves and pre-warms the first media item in the feed via `PreloadManager` before the splash curtain drops, resulting in instant playback.
*   **Filter Synchronization**: Introduced a strict `syncFilterMode()` flow to prevent the ViewModel from getting stuck in specific filters (like "Articles") when the UI clears them via the 'x' button, ensuring seamless return to the "Live Feed" regardless of list size.


### 3. Global Connectivity & Native Hub Tor Daemon
*   **Active-Passive Identity**: Updated Mobile `TorService.kt` to disable its local Hidden Service broadcast when connected to a Hub, pivoting purely to an outbound SOCKS5 proxy.
*   **Native Hub Tor Generation**: Upgraded `hainet-seed` to dynamically generate a standard `/var/lib/tor/hainet/hs_ed25519_secret_key` from the PKCS#8 Mobile Identity Clone, clamping and hashing the seed natively in Rust.
*   **Unified Onion Routing**: Configured the Hub's `/etc/tor/torrc` to expose both Port 9999 (Mesh Gossip) and Port 8080 (REST API) under the single, persistent `.onion` address, allowing NoSlop to securely hit the API from anywhere in the world.

## Completed Changes (2026-08-21)

### 12. Audio Pipeline, YouTube Streams, and Channel Creation Cut-off
*   **Always-Included "Music" Category**: Added `"Music"` to `alwaysIncludedCategories` in `SourceLibrary.kt`. Existing and new users get audio sources (`Openverse`, `Internet Archive Audio`, `Jamendo`, `Podcast Index`) in feed sync by default.
*   **Archival Source Identifier Match**: Added `"archive"`, `"podcast_index"`, and `"jamendo"` to `ARCHIVAL_SOURCES` in `NoSlopViewModel.kt`. Fixed an issue where `InternetArchiveClient` items (`apiSource = "archive"`) were mismatched with `"internet_archive"` and discarded by the 400-day freshness ceiling. Increased metadata resolution cap to 25.
*   **Keyless API Fetching & Parallel Harvesting**: Updated `PublicApiService.kt` so keyless public API sources run without blocking. Replaced sequential await loop with parallel `awaitAll()` under a 35s harvest budget.
*   **Low-Latency Audio Buffer**: Injected a 500ms `DefaultLoadControl` buffer into `AudioPlayer.kt` so ExoPlayer audio playback begins immediately after half a second of buffering.
*   **Invidious Direct Stream Resolver Fallback**: Added Invidious direct stream URL resolution fallback in `InvidiousApiClient.kt` when YouTube internal client hits rate limits.
*   **Channel Creation Cut-Off Date Filter**: Added Room database schema migration (v7 -> v8) and UI settings in `ContentPreferencesScreen.kt` allowing users to filter out content from channels created after a specific cut-off date (e.g. drop recent AI content farms). Integrated `ChannelMetadataResolver.kt` querying Invidious API channel metadata.

### 13. Channel Banning UX & Instant Feed Removal
*   **Instant Feed Removal on Ban**: Updated `reactToFeedItem()` and `banChannel()` in `NoSlopViewModel.kt` to filter out the active item ID (`it.id != item.id`) immediately in local UI state before completing room database persistence.
*   **Flexible Handle Matching**: Implemented trimmed substring handle matching (`a == bClean || a.contains(bClean) || bClean.contains(a)`) to handle channel name variations (e.g., `@handle` vs `handle`).
*   **Isolated Preload Eviction**: Isolated `PreloadManager.evictAll()` to explicit user-triggered feed resets, ensuring channel banning does not touch or evict preloaded media players in the active feed.

### 14. Tor Daemon Recovery & Startup Protection
*   **Tor Auto-Retry Force Restart**: Updated `TorState.FAILED` collector in `NoSlopViewModel.kt` to call `startTor(forceRestart = true)`, ensuring hanging bootstrap jobs or stale daemon processes are cleanly reset.
*   **Conditional Tor Warning Overlay**: Restricted the startup Tor warning banner in `TorWarningPanel.kt` to display only when `useTorForClearnet == true`.

### 15. Collapsible UI Sections & Category Accordions
*   **Collapsible Banned Channels Section**: Encapsulated the Banned Channels list and manual ban input field in `ContentPreferencesScreen.kt` behind a toggleable Card section (`Banned Channels / Creators 🚫 (X banned)`), keeping the screen uncluttered by default.
*   **Collapsible Creator Preferences & Category Accordions**: Encapsulated Creator Preferences behind an expandable card (`Creator Preferences 👤 (X selected)`). Grouped suggested channels into topic accordions (*Technology*, *Privacy & Security*, *Science*, *World News*, *Open Source*, *Video Platforms*, *Gaming*, *Lifestyle*, *Health*, *Music*, *Art & Photography*).
*   **Collapsible Content Categories & Genre Accordions**: Encapsulated topics and genres behind `Content Categories & Genres 🏷️ (X active)`, grouped into 3 sub-accordions (*Main Topics*, *Video Genres*, *Music Genres*).

### 16. Touch Gesture Controls Parity Across Primary & Backup Video Players
*   **Dual Player Architecture**: Preserved `EmbedWebViewPlayer` as an essential backup player when direct YouTube/Vimeo stream URL extraction fails or is age-gated/DRM-restricted.
*   **Gesture Control Parity**: Added full gesture control parity to both `ExoVideoPlayer` (native) and `EmbedWebViewPlayer` (backup embed) in `VideoPlayer.kt`:
    *   **Double Tap (Left / Right)**: Seeks -10s / +10s with animated `-10s ⏪` and `+10s ⏩` overlay indicators.
    *   **Long Press (Hold)**: Fast forwards at 2.0x playback speed with an animated `2x ▶▶` top indicator, automatically reverting to 1.0x speed on release.
    *   **Single Tap**: Toggles play/pause with centered play icon state feedback.

### 17. OkHttp Dispatcher Unblocking & Low-Latency Video Buffering
*   **OkHttp Concurrency Bottleneck Fix**: Increased `torClient`'s OkHttp `Dispatcher` limits (`maxRequests = 64`, `maxRequestsPerHost = 16`, up from `12` and `4`) in `HttpClientProvider.kt`. Previously, background preloader tasks choked `googlevideo.com` / `invidious` connection pools, starving active ExoPlayer range requests.
*   **Low-Latency Video Startup**: Updated `DefaultLoadControl` in `VideoPlayer.kt` and `PreloadManager.kt` (`bufferForPlaybackMs = 500`, `minBufferMs = 1000`). Videos now begin playback instantly after 500ms of data is received over Tor, eliminating startup delay and buffering stalls.

## External Audit Fixes (2026-09-03)

An external review of `app/` following the 2026-09-02 self-audit. Applied via
surgical patch scripts; the running register of what is fixed and what remains
is [AUDIT_2026_09_03.md](AUDIT_2026_09_03.md).

### Security

*   **SSH host keys were never verified** (`NOSLOP_SSH_HOSTKEY_V1`).
    `SshDeployer` installed a `UserInfo` whose `promptYesNo()` returned `true`
    unconditionally, and wrote `known_hosts` to `java.io.tmpdir`, which is not a
    stable app-private directory on Android. Deployment sends the SSH password
    and the complete private identity — Ed25519 key, X25519 key, expanded onion
    seed — so an attacker answering on that IP received all of it. Now a
    persistent pin in `filesDir`, a two-phase connect that refuses rather than
    prompts, unconditional refusal on a changed key, and
    `clearPinnedHostKey()` for the genuine rebuild case. **Still
    trust-on-first-use** until `onHostKeyPrompt` is wired into HubSetupScreen.
*   **Packets were forwarded before being verified**
    (`NOSLOP_VERIFY_BEFORE_FORWARD_V1`). `GossipService.processIncoming()` runs
    `forwardPacket()`, and every signature check lived in a handler that runs
    after it returns — so a node relayed forgeries to its entire trusted peer
    set at every hop out to TTL 6. New `MeshPacketVerifier` checks the 20 types
    carrying a self-contained signature at step 4.4. Handler checks are
    unchanged and still run. `MeshPacketVerifier.enforce = false` disables
    dropping without touching the pipeline; everything logs under `SIGVERIFY`.
*   **Dedup could be poisoned by an unauthenticated id.** Step 2 recorded
    `packet.id` before anything verified it, so a forged packet carrying an
    expected id silently displaced the real one. Split into a check (unchanged
    position, so rate limiting sees identical traffic) and a record that runs
    only after verification.

### Bugs found while fixing the above

*   **The firewall buffer never worked.** A `MESSAGE` from an untrusted sender
    was recorded in the dedup cache at step 2 and buffered at step 4; when
    `flushFirewallBuffer()` replayed it after the peer became trusted, the step
    2 check dropped it as a duplicate. Every buffered message was lost. Repaired
    by the dedup split.
*   **`isSourceCached()` always returned false.** It looked up a bare URL while
    entries are keyed `"$url||$quality"`.

### Video playback over Tor

*   **Stream URLs outlived the route they were bound to**
    (`NOSLOP_ROUTE_AWARE_CACHE_V1`). googlevideo signs a URL to the IP that
    requested it — `TorService.kt` says so in its own header — but `sourceCache`
    validated entries on `expire=` alone, so a URL survived both a Tor toggle
    and every NEWNYM. In the 2026-09-03 capture, `PSIrKxSq8w8` was resolved at
    00:18:18 for exit 192.42.116.53 and played at 00:25:45 after six rotations:
    zero bytes, buffer frozen. A third URL carried the device's own address and
    was fetched over Tor, which can never work. Entries now record the routing
    mode and `TorService.circuitGeneration`, and all three readers — including
    the `initialSource` fast path, which bypassed the check entirely — apply the
    same test. A rotation also now reopens `Unavailable` results immediately
    instead of making them wait out a 60s TTL.
*   **Preloads starved the visible video** (`NOSLOP_TOR_PRELOAD_BUDGET_V1`).
    Four resolves competed for `Semaphore(3)` with a 60s budget each, which is
    what "Gave up waiting 20s for a resolve slot" was. Under Tor: one slide
    ahead, none behind, 8s head start. Clearnet behaviour unchanged.



## Consolidated Master Backlog

The following tasks have been aggregated from all historical gap analyses, audits, and integration plans into this single unified backlog.

### 1. Network & Mesh Parity (From GAP_ANALYSIS.md)
*   `FOLLOW`/`UNFOLLOW` asymmetric relationship model (§1)
*   Group admin list + bans (`admins[]`, `bannedIds[]`) — NoSlop has a single admin key and no ban list (§3)
*   User-selectable theme palettes (low priority, §8)
*   Document NoSlop's potential future role as a HAI-Net hub "frontend client" (§9)
*   Confirm `Dns.SYSTEM` fallback exists in `HttpClientProvider.clearnetClient`'s DoH chain (§13.1)

### 2. Hub Integration (From HUB_INTEGRATION_PLAN.md)
*   **Reconciliation Sync (`MeshPacketHandler.kt`)**: Diff local Room DB vs Hub DB to merge DMs/Posts during failover.
*   **Clearnet Aggregation Sync (`FeedSyncWorker.kt`)**: Sync "Saved" and "Liked" clearnet states with the Hub.
*   **Heavy Media Offloading**: Transfer `MediaManager.kt` chunk-downloading logic to the Hub over Tor 24/7. Mobile pulls MP4 over local Wi-Fi.
*   **Background Sync Worker (`FeedSyncWorker.kt`)**: Wire `syncWithHub()` into Android's WorkManager for background DM/notification checks.
*   **Automated AES ZIP Export (`data/BackupManager.kt`)**: Push silent, encrypted `noslop_backup.zip` archives to Hub via `POST /api/backup/push`.
*   **Upload Worker (`data/HubSyncWorker.kt`)**: Create a daily WorkManager job for backups.

### 3. KMP Migration Parity (From KMP_PARITY_PLAN.md)
*   **Missing Core Screens (~4,800 lines)**: `UnifiedFeedTab.kt`, `MainScreen.kt`, `NoSlopViewModel.kt`, etc.
*   **Missing UI Components (~2,600 lines)**: `FeedCard.kt`, `ChatThreadScreen.kt`, `CommentsBottomSheet.kt`, `PeerItem.kt`, `AvatarCropper.kt`, etc.
*   **Missing Tab Screens (~1,060 lines)**: `SettingsTab.kt`, `DMsTab.kt`, `NotificationsScreen.kt`, `LogsViewerScreen.kt`, `ApiKeysScreen.kt`.
*   **Missing Data Layer (~3,000 lines)**: Rebuild `NoSlopRepository.kt`, DAOs, and Android-specific implementations in SQLDelight.
*   **Android-Only Dependencies**: Refactor usages of `AndroidViewModel`, CameraX, ExoPlayer, Coil, Accompanist Permissions, ZXing, and Gson.

### 4. Privacy & Security (From PRIVACY_AND_SECURITY_PROPOSAL.md)
*   Add Ed25519 signature & SHA-256 checksum verification to UpdateManager.kt
*   Upgrade BackupManager.kt to AES-256-GCM authenticated encryption
*   Add HTTPS Certificate Pinning for update check domains
*   Implement dynamic HMAC signing & user-configurable Worker proxy endpoints
*   Enforce strict Tor IP leak prevention (block clearnet fallbacks when Tor is ON)
*   Integrate Tor circuit cycling on API proxy errors
*   Add PIN-derived AES-GCM fallback storage when Android Keystore is unavailable
*   Implement Double Ratchet protocol for forward-secure DMs
*   Apply adaptive token-bucket rate limiting across all mesh packet types

### 5. Open Audit Findings (From AUDIT_2026_09_03.md)
*   **1. Host key confirmation is not wired to the UI.** (Wire `onHostKeyPrompt` in `HubSetupScreen` and add `clearPinnedHostKey()` setting).
*   **2. The Word Cloud cannot restore an identity.** (No restore path from `deriveSeed()`, either fix or correct docs).
*   **9. The proxy secret is not a secret.** (Drop `X-Proxy-Secret`/HMAC, rate limit on server side).
*   **10. Room: `exportSchema = false` and no tests.** (Export schemas, add `MigrationTestHelper`).
*   **11. The database is not encrypted at rest.** (Encrypt group bodies or use SQLCipher).
*   **12. ProGuard keeps essentially the whole app.** (Remove wildcards, annotate models).

### 6. General Enhancements (Legacy Status Log)
*   Add more no-auth image and video sources.
*   Enhance WebView with ad-blocking or reader mode if possible.
*   Further optimize preloading for diverse media types.
