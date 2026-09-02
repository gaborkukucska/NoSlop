#!/usr/bin/env python3
"""
NoSlop surgical fix script 10 — document this session.

    python3 noslop_fix_10_document_session.py

Updates:
  * docs/PROJECT_STATUS.md            — new dated section (the index)
  * docs/TECHNICAL_REFERENCE.md       — new §17 (the detail), plus §14 fixes
  * docs/PRIVACY_AND_SECURITY_PROPOSAL.md — control-interface status correction
  * README.md                         — proxy bullet now excludes /player

Records what shipped AND what did not, including the two regressions
introduced and fixed during the session. A status doc that only lists wins is
the same failure mode as a security doc claiming a control it does not have.
"""

import os
import sys

APPLIED, SKIPPED, FAILED = [], [], []


def edit(path, old, new, label, marker=None):
    if not os.path.exists(path):
        FAILED.append(f"{label}: file not found ({path})")
        return
    with open(path, "r", encoding="utf-8") as f:
        src = f.read()
    if marker is not None and marker in src:
        SKIPPED.append(f"{label}: already applied")
        return
    if marker is None and new in src and old not in src:
        SKIPPED.append(f"{label}: already applied")
        return
    count = src.count(old)
    if count == 0:
        FAILED.append(f"{label}: anchor text not found in {path}")
        return
    if count > 1:
        FAILED.append(f"{label}: anchor matched {count} times in {path}, refusing")
        return
    with open(path, "w", encoding="utf-8") as f:
        f.write(src.replace(old, new))
    APPLIED.append(label)


STATUS = "docs/PROJECT_STATUS.md"
TECH = "docs/TECHNICAL_REFERENCE.md"
PROPOSAL = "docs/PRIVACY_AND_SECURITY_PROPOSAL.md"
README = "README.md"


# ---------------------------------------------------------------------------
# 1. PROJECT_STATUS.md — the index entry
# ---------------------------------------------------------------------------

STATUS_SECTION = """# Project Status - NoSlop

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

"""

edit(
    STATUS,
    "# Project Status - NoSlop\n\n",
    STATUS_SECTION,
    "PROJECT_STATUS: session section added",
    marker="## Completed Changes (2026-09-02) — Codebase Audit",
)


# ---------------------------------------------------------------------------
# 2. TECHNICAL_REFERENCE.md — §17 detail, appended before the footer
# ---------------------------------------------------------------------------

TECH_SECTION = """## 17. Audit and Hardening Pass (2026-09-02)

Indexed from [PROJECT_STATUS.md](PROJECT_STATUS.md). This section is the
detail; the status doc is the summary.

### 17.1 Tor control interface — `NOSLOP_CONTROL_SOCKET_V1` / `_V2`

`writeTorrc` used to emit `ControlPort 9051` with `CookieAuthentication 0`, and
every control operation connected to `127.0.0.1:9051` with a bare
`AUTHENTICATE`. On Android, loopback is shared between all installed apps. Any
app holding `INTERNET` could therefore drive our Tor daemon.

Cookie authentication was considered and rejected. It is a global tor setting,
and `org.torproject.jni.TorService` maintains its own control connection that
authenticates with empty credentials; enabling it would very likely break the
library's connection and with it the `STATUS_ON` broadcast, so Tor would never
report READY. Trading a local attack surface for a non-booting app is a bad
trade.

`TorControlChannel` instead centralises every control operation and offers
three modes:

| Mode | torrc | Behaviour |
|---|---|---|
| `UNIX_ONLY` | `ControlSocket` only | Destination. File permissions on the app's private `filesDir` are the access control; no TCP port exists to attack. |
| `AUTO` | both | **Current.** Prefers the socket, falls back to TCP, logs which won plus a directory dump on failure. Leaves 9051 open — diagnostic, not a destination. |
| `TCP_ONLY` | `ControlPort` only | Exact pre-change behaviour, for isolating a startup failure. |

The first cut shipped `UNIX_ONLY` and the socket never appeared. tor-android
writes nothing to logcat, so the failure was invisible: no bootstrap-phase
lines, no hidden service, nine `NEWNYM: control channel unavailable` warnings,
and circuit rotation entirely inert. `AUTO` exists to answer, on the next run,
whether tor refuses the directory, places the socket elsewhere, or ignores our
torrc at all — the last being plausible given `ControlPort 9051` may have been
tor-android's default rather than ours.

Note `start()` returns early on `Tor already in state READY`, so `writeTorrc`
is skipped on a warm start. A torrc change needs a genuine tor restart to take
effect.

### 17.2 TLS trust — `NOSLOP_TLS_TRUST_V1`

`base-config` permitted cleartext for every domain and trusted user
certificates. System anchors only now, cleartext scoped to `.onion` and
loopback. Android's network security config matches hostnames and has no CIDR
syntax, so "any RFC1918 address" is inexpressible — the LAN Hub fast path is
consequently blocked and `invokeHubApi` falls back to the Hub's `.onion`. A
commented block pins one known LAN address if the fast path is wanted back.
Debug TLS interception belongs in a `app/src/debug/res/xml/` overlay, not here.

### 17.3 Release verification — `NOSLOP_RELEASE_CHECKSUM_V1`

The digest is computed while streaming, so the file is never read twice and
there is no window between hashing and installing. Compared in constant time
against the published checksum; the file is deleted on mismatch, on truncation
against `Content-Length`, and on a `text/html` response. With no published
checksum the install is refused unless `allowUnverified = true` is passed from
a UI path that has warned the user.

`UpdateChecker` resolves the expected digest from, in order: `hero.apkSha256`
in `content.json`, a `.sha256` release asset, a bare 64-hex in the release
notes, then a sibling `<apk-url>.sha256`.

This is integrity against whatever the update channel said, not authenticity.
See "Still open" item 2.

### 17.4 Media proxy — `NOSLOP_PROXY_TOKEN_V1`

Per-process token on `/stream`, compared in constant time; an untokenised
request gets 404 rather than 401 so a probing app cannot confirm what the port
is. The `onion=` parameter must match a well-formed v3 address. The token
rotates per process, so in-progress mesh streams do not survive an app restart
in Coil's cache; fully downloaded media is served from `file://` and is
unaffected.

### 17.5 Mesh framing — `NOSLOP_FRAME_CAP_V1`

Manual newline framing with a 4MB ceiling, generous enough for the largest
`MEDIA_CHUNK` and small enough that a peer cannot buffer the heap away.
Connections exceeding it are dropped. The parse-failure branch logs the frame
length rather than the frame.

### 17.6 Proxy credentials — `NOSLOP_PROXY_SECRET_V1`

`PROXY_URL`, `PROXY_SECRET` and `PROXY_SEND_LEGACY_SECRET` are BuildConfig
fields sourced from Gradle properties, so the shipped secret is no longer in
the public repo and rotation is a property change. The Worker accepts either
the legacy header or a valid HMAC during rollout, and reports which via
`X-Proxy-Auth`. Reddit and Jamendo sign the full proxied URL; YouTube signs the
JSON body — the Worker reconstructs both candidates because changing what the
client signs would break every installed copy at once.

### 17.7 Identity fallback — `NOSLOP_UNLOCK_FALLBACK_V1`

`unlock()` now reads through `secureFallbackRead` and normalises whitespace and
case before comparing. Burnable private keys go through `secureFallbackWrite`.
`clearAll()` removes the Room mirror as its docstring always claimed.

### 17.8 Backup — `NOSLOP_BACKUP_STREAMING_V1`

Both directions stream through a 64KB buffer. GCM `update()` emits plaintext
that is not yet authenticated, so the decrypted archive is written to a temp
file and unzipped only after `doFinal()` returns without throwing; a tampered
archive is deleted before a single entry is read. Zip entry names are validated
as plain file names and every destination is confirmed inside its intended
parent by canonical path. The plaintext temp archive is deleted in a `finally`,
success or failure.

`preferences.xml` in the archive is sealed by a non-exportable Keystore key.
`canOpenRestoredIdentity()` probes it after a restore and sets
`lastRestoreNeedsIdentityRecovery` — see "Still open" item 3.

### 17.9 Player IP lock — `NOSLOP_PLAYER_IP_LOCK_V1`

`playerEndpoint()` is unconditionally direct. Confirm with `PLAYBACK_DIAG
resolved DIRECT` lines: `signedFor=` should always be a Tor exit, never
`104.23.x` or `172.71.x`. The Worker's player-response cache is now cold; if
player calls are ever routed back through it, remove that cache first, because
it stores URLs locked to Cloudflare's egress.

### 17.10 Bootstrap bail-out — `NOSLOP_BOOTSTRAP_BAILOUT_V1`

If no control connection has succeeded within 20 polls, `waitForBootstrap`
returns false immediately so the caller's end-to-end routing check runs. Worth
keeping independently of §17.1: an unreachable control interface is knowable in
seconds, and the fallback needs to run while the current bootstrap attempt is
still alive.

### 17.11 Category picker — `NOSLOP_MUSIC_SELECTABLE_V1`

`hiddenFromPicker` (plumbing categories) is now separate from
`alwaysIncludedCategories` (always fetched). `Music` is both always fetched and
a real user choice; filtering the picker by the latter removed it from the UI
and made `Step6Genres`' `interests.contains("Music")` permanently false.

### 17.12 Content Mix layout — `NOSLOP_CONTENT_MIX_SCROLL_V1`

`FeedMixSettingsSection` is a bare composable emitting siblings with no
container, and requires a scrolling column from its caller.
`ContentPreferencesScreen` supplies one; `Step8FeedMix` supplied a `Box`, which
stacked the header over the card and, being height-constrained without a
scroll, collapsed the lower sliders to zero height.

### 17.13 EXIF orientation — `NOSLOP_EXIF_ORIENTATION_V1`

`ui/ExifUtils.kt` decodes with the orientation applied and exposes
`needsRotation()` so small photos that carry a rotation are re-encoded too.
Rotation is baked into pixels rather than preserved as a tag, because the
receive path mixes Coil and raw `BitmapFactory` and peers may run a different
build. Applied at `ChatThreadScreen`, `GroupChatThreadScreen`, `AvatarCropper`
and `GroupSettingsModal`.

CameraX still has no `setTargetRotation()`; the decode-side fix covers both
camera and gallery sources, so it was left alone deliberately.

### 17.14 Comment media — `NOSLOP_COMMENT_MEDIA_RERESOLVE_V1`

`isDownloaded` is now a `remember` key on the resolved URL, so it re-resolves
from proxy URL to `file://` at the moment the file becomes usable. The
`AsyncImage` also gets a GIF-capable `ImageLoader`, matching the one
`ChatThreadScreen` already builds for DM GIFs.

### 17.15 Logging — `NOSLOP_LOG_HYGIENE_V1`

Rotates at 4MB keeping one generation, so worst-case disk use is bounded at
~8MB. Release drops DEBUG. Writes are serialised on a single daemon thread —
the previous implementation launched a coroutine per line onto the
multi-threaded IO dispatcher and called `File.appendText()`, opening and
closing the file per line with no ordering guarantee. Scrubbing covers long
Base64 blobs and 64-hex digests in addition to onion addresses.

Note the log export surfaces only the active file, not the rotated `.log.1`.

---

"""

edit(
    TECH,
    """---

**Related docs**: [WIRE_PROTOCOL_REFERENCE.md](WIRE_PROTOCOL_REFERENCE.md) for""",
    TECH_SECTION + """**Related docs**: [WIRE_PROTOCOL_REFERENCE.md](WIRE_PROTOCOL_REFERENCE.md) for""",
    "TECHNICAL_REFERENCE: §17 added",
    marker="## 17. Audit and Hardening Pass (2026-09-02)",
)

edit(
    TECH,
    """4. `docs/archived/ANALYSiS.md` item 6 states `CookieAuthentication 0`;
   `TorService.writeTorrc` writes `CookieAuthentication 1`, while
   `registerHiddenService` authenticates with a bare `AUTHENTICATE\\r\\n` (no
   cookie) — this combination should still be verified against the running
   `tor-android` behavior. **Still open** (ANALYSiS.md is archived/historical
   and not being edited; flagging here is the live tracking mechanism).""",
    """4. ~~`docs/archived/ANALYSiS.md` item 6 states `CookieAuthentication 0`;
   `TorService.writeTorrc` writes `CookieAuthentication 1`, while
   `registerHiddenService` authenticates with a bare `AUTHENTICATE\\r\\n` (no
   cookie).~~ **Resolved 2026-09-02** — the torrc did write
   `CookieAuthentication 0`, and the bare `AUTHENTICATE` matched it, which is
   why control worked and why any app on the device could also use it. All
   control access now goes through `TorControlChannel`; see §17.1. Empty
   authentication is retained deliberately, with socket file permissions as the
   access control.""",
    "TECHNICAL_REFERENCE §14: item 4 resolved",
    marker="**Resolved 2026-09-02** — the torrc did write",
)

edit(
    TECH,
    """8. The `okhttp` (4.10.0) vs `okhttp-dnsoverhttps` (4.12.0) version mismatch
   noted in §12 is **still real** — not a doc error, an actual dependency
   skew worth aligning at some point.""",
    """8. ~~The `okhttp` (4.10.0) vs `okhttp-dnsoverhttps` (4.12.0) version
   mismatch noted in §12.~~ **Fixed 2026-09-02** — the catalog is aligned on
   4.12.0 and `okhttp-dnsoverhttps` is declared there rather than hardcoded.
9. The `mvp/` tree is not in `settings.gradle.kts` and does not build. README
   previously called it the canonical codebase; that claim is corrected, but
   the directory is still 2.8MB of dead weight in the repo. **Open.**""",
    "TECHNICAL_REFERENCE §14: item 8 fixed, mvp/ tracked",
    marker="**Fixed 2026-09-02** — the catalog is aligned",
)


# ---------------------------------------------------------------------------
# 3. PRIVACY_AND_SECURITY_PROPOSAL.md — correct the control-interface status
# ---------------------------------------------------------------------------

edit(
    PROPOSAL,
    """| **Tor control interface** | `ControlPort 9051` with `CookieAuthentication 0`. Any app holding INTERNET could open an unauthenticated Tor control connection and issue `GETINFO circuit-status` (deanonymisation), `ADD_ONION`, `SETCONF` or `SIGNAL`. | **Fixed.** The control interface is now a unix socket in the app's private filesDir with no TCP port at all. Cookie auth was rejected deliberately — it is global, and tor-android's own control connection authenticates with empty credentials. See `TorControlChannel`. |""",
    """| **Tor control interface** | `ControlPort 9051` with `CookieAuthentication 0`. Any app holding INTERNET could open an unauthenticated Tor control connection and issue `GETINFO circuit-status` (deanonymisation), `ADD_ONION`, `SETCONF` or `SIGNAL`. | **PARTIAL.** All control access is centralised in `TorControlChannel`, which supports a private unix `ControlSocket`. The first `UNIX_ONLY` build could not open that socket — tor logs nothing to logcat, so it failed invisibly, taking `NEWNYM` and `ADD_ONION` with it. Currently `Mode.AUTO`: socket preferred, **TCP 9051 still declared as fallback**, so the hole is not yet closed. Cookie auth was rejected deliberately — it is global, and tor-android's own control connection authenticates with empty credentials. See TECHNICAL_REFERENCE §17.1. |""",
    "PRIVACY_AND_SECURITY_PROPOSAL: control interface status is accurate",
    marker="**PARTIAL.** All control access is centralised",
)

edit(
    PROPOSAL,
    """## Backup — updated 2026-09-01""",
    """## Player URL IP lock — added 2026-09-02

Not a proposal item, but it belongs with the proxy discussion in §1. A
googlevideo URL carries `&ip=<address>` and is served only to that address.
Resolving `/player` through the Cloudflare Worker had YouTube issue the URL to
the Worker's egress; the bytes were then fetched over a Tor exit and refused
silently, as a stream that never started. `/player` is now always direct
(`NOSLOP_PLAYER_IP_LOCK_V1`); search and metadata still use the proxy, which
returns no IP-locked URLs.

Worth noting alongside §1.2: this is the proxy actively harming a request it
succeeded at, which is a stronger argument for user-configurable endpoints than
the refusal case.

## Backup — updated 2026-09-01""",
    "PRIVACY_AND_SECURITY_PROPOSAL: records the player IP lock",
    marker="## Player URL IP lock — added 2026-09-02",
)


# ---------------------------------------------------------------------------
# 4. README.md — the proxy no longer touches /player
# ---------------------------------------------------------------------------

edit(
    README,
    """Media stream bytes never go through it.""",
    """Media stream bytes never go through it, and neither does stream resolution: a
googlevideo URL is IP-locked to whoever requested it, so resolving through the
proxy produced URLs that could not be fetched over Tor. Search and metadata
only.""",
    "README: proxy bullet excludes player resolution",
    marker="neither does stream resolution",
)


print("\n=== APPLIED ===")
for x in APPLIED:
    print("  +", x)
print("\n=== SKIPPED ===")
for x in SKIPPED:
    print("  =", x)
if FAILED:
    print("\n=== FAILED ===")
    for x in FAILED:
        print("  !", x)

sys.exit(1 if FAILED else 0)
