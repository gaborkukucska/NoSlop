# NoSlop — Remediation Instructions

**Target:** `NoSlop-main` legacy Android app (`app/`), version `0.5.1-alpha`, versionCode 51
**Audited:** 128 Kotlin files, ~47,700 lines, `app/` module only. `mvp/` ignored by instruction and is not in the Gradle build.
**Audience:** an LLM performing the work, with the repo checked out.

## How to use this document

Work top to bottom. Each item names the file and approximate line, states the change, and states how to verify it. Line numbers are from the audited snapshot and may drift — always confirm by content, not by number.

Two standing rules for whoever executes this:

1. **Deliverable format.** For a scoped change, produce a surgical Python script to be run from the NoSlop project root. Where a file is effectively rewritten, produce the entire file, one file at a time.
2. **Do not "fix" anything in Appendix A.** Those items were checked and are correct as written. Several look wrong at a glance and are not.

Priorities: **P0** ships broken or leaking. **P1** real defect or false claim. **P2** structural. **P3** dead code. **P4** duplication.

---

# P0 — Correctness and privacy

## P0-1. Group chat is not end-to-end encrypted on the default path

**Files:** `data/NoSlopRepository.kt` (~813–905), `mesh/DmPacketHandler.kt` (~256–300), `mesh/MeshPacketVerifier.kt`, `ui/components/GroupChatThreadScreen.kt:72`

`sendGroupMessage()` encrypts per-member for directly-connected peers, then — when `privacy == "public"` **or** any member is unconnected — *also* broadcasts a `GROUP_MESSAGE` gossip packet carrying `content = text` in cleartext at TTL 6. `GroupChatThreadScreen.kt:72` defaults `messagePrivacy` to `"public"`, so this is the normal path, not an edge case. Every relay node within six hops reads the plaintext plus the sender handle, tripcode and group id.

On receipt, `handleGroupMessage()`:
- performs **no signature check** — `GROUP_MESSAGE` has no case in `MeshPacketVerifier.describe()`, so the verifier returns `UNVERIFIABLE` and the gossip gate passes it;
- performs **no membership check** — it only verifies the group row exists locally;
- stores `senderPub = senderDisplay`, a handle string chosen by the sender, not a public key.

Consequence: any trusted peer who learns a group id can inject messages into that group attributed to any handle they choose, and every relay in range reads group traffic in clear.

**Do:**

1. Delete the `GROUP_MESSAGE` gossip broadcast block from `sendGroupMessage()` entirely.
2. Replace the "unconnected members" path with store-and-forward. Add a Room table `pending_group_messages(groupId, memberPub, msgId, ciphertext, nonce, createdAt)` with a composite primary key, persist the per-member `EncryptedPayload` there, and flush from `MeshSocialRepository` when that peer's onion address becomes known. Expire entries after 7 days.
3. Remove the `val encPub = peer.encPublicKeyB64.ifBlank { memberPub }` fallback. Passing an Ed25519 key where an X25519 key is expected cannot succeed. When a member has no `encPublicKeyB64`, send a `CONNECTION_REQUEST` and queue the message instead.
4. If `GROUP_MESSAGE` is retained for one release for wire compatibility, it must gain a `signature` field over a canonical payload signed by `senderId`; `handleGroupMessage()` must reject unless the signature verifies **and** `senderId` appears in the stored group's `membersJson`; and it must store the sender's public key in `senderPub`, resolving the display handle at render time from the `peers` table.
5. Add a `GROUP_MESSAGE` case to `MeshPacketVerifier.describe()` and a matching case to `MeshPacketVerifierTest`.
6. Bump the wire protocol version and record the change in `docs/WIRE_PROTOCOL_REFERENCE.md`.

**Verify:** two devices, a third as relay. Send a "🌐 All Members" message. Confirm the relay's logs show no `GROUP_MESSAGE` packet and no plaintext body. Then craft a `GROUP_MESSAGE` with a forged handle from a trusted-but-non-member peer and confirm it is dropped.

## P0-2. Group message bodies are stored in plaintext at rest

**Files:** `data/Entities.kt:109` (`ChatMessage`), `data/NoSlopRepository.kt:~820`, `mesh/DmPacketHandler.kt:~269`

Both the send and receive paths write `ciphertext = <plaintext>, nonce = ""` for group rows. 1:1 DMs correctly store real ciphertext. The one surface the README calls E2EE is the one kept in the clear on disk.

**Do:** add an app-scoped AES-256-GCM key in the Android Keystore (`MasterKey`, `setUserAuthenticationRequired(false)`), encrypt group message bodies on insert and decrypt on read. Add Room migration 12 → 13 that re-encrypts existing rows in place, made idempotent (detect an already-encrypted row by a version byte prefix) so a partial failure can be resumed. Leave the 1:1 DM path untouched.

**Verify:** pull the DB with `adb` (or via the app's export) and confirm no group message body is readable.

## P0-3. Unauthenticated Tor control port is open on loopback

**Files:** `tor/TorControlChannel.kt:112` (`val MODE = Mode.AUTO`), `tor/TorService.kt:~584` (`writeTorrc`)

`Mode.AUTO` emits both `ControlSocket` and `ControlPort 9051`, and `writeTorrc()` appends `CookieAuthentication 0`. Loopback is not app-private on Android, so any other installed app can open a control connection and issue `ADD_ONION`, `DEL_ONION`, `GETINFO`, `SETCONF`, `SIGNAL NEWNYM`.

This is a **deliberate, documented diagnostic state**, not an oversight — `TorControlChannel`'s own comment says so, after an initial `UNIX_ONLY` attempt failed to open the channel at all. Treat it as a decision waiting on evidence.

**Do:**

1. First, read the device logs for a `TOR_CONTROL` line reporting `transport=unix:...`. If present, the socket works and the whole issue closes by setting `MODE = Mode.UNIX_ONLY`.
2. If absent, debug the socket path before changing anything — `configure()` places it at `filesDir/tor/ControlSocket`, and `candidateSocketPaths()` also probes the tor-android service dir. The one-time on-disk diagnostic dump in `TorControlChannel` is the intended tool.
3. Once on `UNIX_ONLY`, remove the `socketFile == null` fallback that emits `ControlPort` — log an error and return an empty string, so tor starts with no control interface rather than an unauthenticated TCP one.
4. In `writeTorrc()`, only append `CookieAuthentication 0` when the emitted control lines actually contain `ControlPort`.
5. Add a post-bootstrap self-check that attempts a TCP connect to `127.0.0.1:${Constants.TOR_CONTROL_PORT}` and logs `Logger.error` if it succeeds.
6. Correct the `writeTorrc()` header comment, which currently states the unix-socket switch as complete.

**Verify:** `netstat -tlnp` in Termux with NoSlop running — nothing on 9051 (release) or 9053 (debug).

## P0-4. Splash-screen media pre-warm can never succeed

**File:** `MainActivity.kt:95`

```kotlin
"http://127.0.0.1:8080/stream?onion=${onion}&id=${id}"
```

Two faults. The port is hardcoded to 8080 while debug builds set `MEDIA_PROXY_PORT = 8081`. And there is no `&t=` session token, which `MediaProxyService.tokenMatches()` requires — requests without it are answered 404 by design.

The finished helper already exists: `MediaProxyService.buildProxyUrl(onion, id)` at `mesh/MediaProxyService.kt:78`, already used by `ui/MediaUtils.kt:32`.

**Do:** replace the inline construction with `com.noslop.app.mesh.MediaProxyService.buildProxyUrl(onion, id)`. Then grep for any other hand-built `127.0.0.1:8080` or `/stream?` string and route it through the same helper.

**Verify:** open the app on a feed whose first item is mesh-hosted media; confirm no `MEDIA_PROXY` 404 during splash.

## P0-5. Wrong `.onion` reported on the address-collision path

**File:** `tor/TorService.kt:~893`

```kotlin
val pubBytes = android.util.Base64.decode(privateKeyB64, ...)
val derived = CryptoService.deriveOnionAddress(pubBytes)
```

`privateKeyB64` is the PKCS#8 Ed25519 **private** key. `deriveOnionAddress` calls `getEd25519PublicKeyParams`, whose `PublicKeyFactory.createKey` fails, whose catch block then takes the last 32 bytes of the private encoding and treats them as a public key. The result is a syntactically valid but wholly wrong address, reported to the UI via `onAddressReady`. Peers given it can never reach the node.

**Do:**

1. Use the stored `IdentityKeys.onionAddress`, or query `GETINFO onions/current`, instead of deriving from a private key.
2. Harden the primitive: make `getEd25519PublicKeyParams` and `getEd25519PrivateKeyParams` **throw** on an input whose length matches the wrong key type (48-byte PKCS#8 passed as a public key, 44-byte X.509 passed as a private key) rather than silently truncating. The silent truncation is what let this through.

**Verify:** unit test that `deriveOnionAddress(privateKeyBytes)` throws.

---

# P1 — Defects and false claims

## P1-1. The Word Cloud cannot restore an identity

**Files:** `crypto/MnemonicGenerator.kt`, `data/BackupManager.kt:155,194,308`, `ui/OnboardingScreen.kt:362,485,530`, `docs/SUPPORT.md:19`

`deriveSeed()` has exactly two call sites, both in `BackupManager`, both for archive encryption. The identity is a fresh random keypair from `CryptoService.generateIdentity()`. `docs/SUPPORT.md:19` tells the user the mnemonic is "the ONLY way to recover your identity if you lose your device". It is not a way at all.

Pick one option and complete it.

**Option A — honour the promise.** Add `CryptoService.deriveIdentityFromSeed(seed: ByteArray)` deriving the Ed25519 seed as `HKDF-SHA512(seed, info = "noslop-identity-ed25519-v1")` and the X25519 key as `HKDF-SHA512(seed, info = "noslop-identity-x25519-v1")`. Wire it into the onboarding restore path behind an identity-version flag so existing random-key users are not broken, and state plainly in the UI that identities created before this version cannot be recovered from the phrase.

**Option B — correct the copy.** Change `docs/SUPPORT.md:19`, the `BackupManager` comment at line 308, and the onboarding text to describe it as a backup password.

**Either way, fix these:**
- `OnboardingScreen.kt:362` says "24-word"; `generateMnemonic()` emits 12.
- `OnboardingScreen.kt:530` labels it "(BIP39)"; the `MnemonicGenerator` class header explicitly documents that it is not BIP-39 (2053-word list, no checksum word). Remove the label.

## P1-2. The "severable / burnable" creator identity is never severed

**Files:** `ui/NoSlopViewModel.kt:2433` (`setCreatorEnabled`), `data/IdentityRepository.kt:285` (`clearBurnableIdentity`, dead), `tor/TorService.kt:527` (`unregisterHiddenServices`), `ui/tabs/SettingsTab.kt:965`

`setCreatorEnabled(false)` writes an app setting and nothing else. It does not call `clearBurnableIdentity()` (which has no callers anywhere), does not `DEL_ONION` the burnable hidden service, and does not regenerate the key. `unregisterHiddenServices()` is only invoked on the Hub-deployment path.

Meanwhile `SettingsTab.kt:965` tells the user that peers "will permanently lose connection". The onion stays registered and the keys stay on disk. The README headline "ephemeral/burnable secondary identity" is not implemented.

**Do:** make `setCreatorEnabled(false)` call `TorService.unregisterHiddenService(activeBurnableServiceId)` and `IdentityRepository.clearBurnableIdentity()`, then broadcast a `USER_EXIT` from the burnable key so followers can clean up. Add a distinct "Burn Creator ID" action that additionally regenerates, so disabling and burning are separate. If you choose not to implement the burn, change the README and the dialog text to match.

## P1-3. Circuit rotation is dead, and a shipped fix depends on it

**Files:** `tor/TorService.kt:143` (`requestNewCircuit`), `tor/TorService.kt:121` (`_circuitGeneration`), `ui/components/VideoPlayer.kt:102`

`requestNewCircuit()` — 52 lines carrying `newnymMutex`, the adaptive `NEWNYM_MIN_INTERVAL_MS` / `NEWNYM_IDLE_INTERVAL_MS` cooldown, and the `_circuitGeneration++` — has **no callers anywhere in the app**.

`_circuitGeneration` is incremented only inside it. So `TorService.circuitGeneration` is permanently `0`, and the staleness check at `VideoPlayer.kt:102-103`:

```kotlin
val generationNow = TorService.circuitGeneration
if (circuitGeneration == generationNow) return null
```

always returns `null`. The entire "cached stream URL is invalidated when the circuit rotates" protection is inert. This was a shipped audit fix whose trigger was never wired up.

Note that the README's "Graceful Exit Hopping — nonce-bumping hops Tor exits" claim **is** correctly implemented, by a different mechanism: `YouTubeInternalClient` bumps `videoStreamNonces` (~line 889), which changes the SOCKS username and therefore the isolated circuit. That is fine and should be left alone.

**Do:** decide which mechanism is authoritative.
- If nonce-bumping is the design, delete `requestNewCircuit()`, `_circuitGeneration`, `circuitGeneration`, `newnymMutex`, both NEWNYM interval constants, `mediaIsStreaming()` if it becomes unused, and the generation half of `CachedSource.stalenessReason()` in `VideoPlayer.kt`. Replace the staleness check with one keyed on the stream nonce, so cached URLs are still invalidated when the exit changes.
- If NEWNYM is wanted, wire `requestNewCircuit()` to the `EXIT_BLOCKED_THRESHOLD` branch in `YouTubeInternalClient` and to a manual Settings action.

Do not leave both half-present.

## P1-4. `WAKE_LOCK` permission is missing

**Files:** `AndroidManifest.xml`, `mesh/NoSlopForegroundService.kt:56`, `mesh/MediaManager.kt:173`

`android.permission.WAKE_LOCK` is not declared. `NoSlopForegroundService.onCreate()` calls `acquire()` inside a try/catch, so it fails silently. `MediaManager.updateWakeLock()` calls `acquire(10 * 60 * 1000L)` with no catch.

This does **not** affect background media playback — Android keeps the CPU awake while an AudioTrack is active, which is why playback with the screen off works today. What it breaks is mesh sync and mesh media transfer with the screen off.

**Do:** add `<uses-permission android:name="android.permission.WAKE_LOCK" />`.

## P1-5. Video playback does not handle audio focus

**File:** `ui/components/VideoPlayer.kt:1216`

The `ExoPlayer.Builder` in `ExoVideoPlayer` omits `setAudioAttributes(..., handleAudioFocus = true)`. `AudioPlayer.kt:106` and `PreloadManager.kt:344` both set it correctly. So video does not pause for an incoming call and will play over other apps' audio.

**Do:** add to the builder at line ~1216:

```kotlin
.setAudioAttributes(
    androidx.media3.common.AudioAttributes.Builder()
        .setUsage(androidx.media3.common.C.USAGE_MEDIA)
        .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE)
        .build(),
    true
)
```

**Do not** add a lifecycle observer that pauses on `ON_STOP`. Background playback works deliberately through the absence of one, and that behaviour is wanted.

**Optional, separate:** add `androidx.media3:media3-session` and a `MediaSessionService` with `android:foregroundServiceType="mediaPlayback"` to gain lock-screen and headset transport controls. That is a feature addition; the README bullet is accurate without it.

## P1-6. Remaining gossip firewall gaps

**File:** `mesh/GossipService.kt:~342–400`

- `ANNOUNCE_DISCOVERABLE`, `IDENTITY_UPDATE` and `USER_EXIT` bypass both the trust firewall and the rate limiter. They are signature-verified at step 4.4, but an unknown sender can still consume relay capacity. Give them a dedicated budget of 5 per 60s per sender, separate from the 20-per-10s limit.
- The media budget comment says "2MB per 10s window" but `senderMediaBytes.clear()` runs inside `cleanupRateLimitsAndFirewall()`, which fires on a 60s loop (line ~179). Either clear on the 10s cadence the comment claims, or correct the comment and constant to reflect 60s.
- `payloadSize` is `packet.payload?.toString()?.length` — UTF-16 char count, not bytes. Use `?.toByteArray(Charsets.UTF_8)?.size`.
- Update the README sentence claiming untrusted senders are "dropped outright".

## P1-7. One canonical signing encoder

**Files:** `crypto/CryptoService.kt:encodeForSigning`, `mesh/MeshPacketVerifier.kt`, all packet handlers

Three incompatible formats are in use: length-prefixed `encodeForSigning` for most types; `|`-joined with optional appends for `COMMENT`, `EDIT_COMMENT`, `IDENTITY_UPDATE`, `FOLLOW`; and `:`-joined for `ANNOUNCE_DISCOVERABLE`. The `|` formats with optional appends are genuinely ambiguous — "content X, no avatar" and "content X|Y with avatar Y" produce the same signing string. `GROUP_INVITE` verification tries four formats in a loop against every candidate key.

**Do:** introduce `CanonicalEncoder.encode(vararg fields: Pair<String, String?>)` emitting sorted-key JSON with explicit nulls, sign over its UTF-8 bytes, and migrate every sender, handler and verifier case in one commit. Add a `sigVersion` field to `NetworkPacket` so old and new peers interoperate for one release. Delete the four-format try-loop in the `GROUP_INVITE` case. `MeshPacketVerifierTest` must continue to restate each format independently.

While there: `encodeForSigning` uses UTF-16 `String.length` rather than byte length, and encodes both `null` and `""` as `"0:"`. Neither is exploitable in a Kotlin-only mesh, but both are hazards for the iOS/KMP port. Use `"-1:"` for null and `toByteArray(UTF_8).size` for the prefix.

## P1-8. SSH host-key confirmation is not wired to the UI

**Files:** `ui/tabs/HubSetupScreen.kt:258, 627, 728, 761`; `net/SshDeployer.kt:71, 204`

`deployHaiNetHub`'s `onHostKeyPrompt` parameter defaults to `null` and is passed at none of the four call sites, so first contact is trust-on-first-use — and that is the connection that carries the SSH password and the full private identity. `SshDeployer.clearPinnedHostKey()` exists and has no callers.

**Do:** pass a callback at all four sites that suspends on a dialog showing the fingerprint, and add a Settings entry invoking `clearPinnedHostKey()`. Three of the four call sites (621, 722, 755) are near-identical copies — consolidate them into one helper while you are there (see P4-1).

## P1-9. Proxy secret and shared API keys

**Files:** `app/build.gradle.kts:~30`, `feeds/api/{YouTubeInternalClient,RedditApiClient,JamendoApiClient}.kt`

`NOSLOP_PROXY_SECRET` defaults to the committed literal `NoSlopRocks2026`, and any injected value ships in `BuildConfig` inside an open-source APK. The HMAC is keyed on that same non-secret. `JamendoApiClient.kt:19` carries a shared `CLIENT_ID = "709fa152"`.

**Do:** decide the position and make the code match.
- If the Cloudflare Worker stays: remove `PROXY_SECRET`, `PROXY_SEND_LEGACY_SECRET` and `applyProxyAuthHeaders` from all three clients, rate-limit server-side, and state in `docs/PRIVACY_POLICY.md` that YouTube/Reddit/Jamendo requests traverse a project-operated endpoint.
- If it goes: move all three behind user-supplied keys in `ApiKeysScreen`, following the existing Guardian/NewsAPI/Pexels/Vimeo pattern, and surface "no key configured" in the feed rather than silently dropping the source.

`YouTubeInternalClient.API_KEY` is the public InnerTube web key published by Google and used by every YouTube frontend. It is not a secret and can stay.

## P1-10. Backup import silently accepts an unauthenticated format

**File:** `data/BackupManager.kt:~199–220`

If the `NSG1` magic is absent, import falls back to legacy `AES/CBC/PKCS5Padding` with no authentication tag. The format is therefore attacker-selectable: strip the magic and the archive decrypts unverified.

**Do:** keep the legacy path for genuine old archives but require explicit user confirmation, with copy stating the archive predates authenticated backups and its contents cannot be verified.

## P1-11. Documentation truth pass

- `README.md` links `docs/HUB_INTEGRATION_PLAN.md`; the file is at `docs/archived/HUB_INTEGRATION_PLAN.md`.
- `mesh/MeshPacketVerifier.kt` references `FINDINGS.md`, which does not exist. Create `docs/FINDINGS.md` from the still-open items in `docs/archived/AUDIT_2026_09_03.md`.
- README: qualify the group-chat E2EE bullet until P0-1 and P0-2 land; note the user-facing Tor opt-out alongside the "100% Tor Only" badge; describe the tripcode as a display disambiguator rather than a verification fingerprint (see Appendix A-4); state that the Room database is not encrypted at rest; note that the private identity is transmitted during Hub SSH deployment.
- `res/xml/network_security_config.xml` already documents that the LAN Hub fast path is blocked by its own policy. Make the README and `HubSetupScreen` agree, or give the Hub a self-signed certificate and pin it.

---

# P2 — Structural

## P2-1. Room schema safety

**Files:** `data/NoSlopDatabase.kt:49-50,168`, `app/build.gradle.kts`

`version = 12`, `exportSchema = false`, eleven hand-written migrations, no `app/schemas/`, no `app/src/androidTest` source set. For an app with no cloud backup, a bad migration is unrecoverable data loss. This is the largest structural risk in the project.

**Do:** set `exportSchema = true`; add `ksp { arg("room.schemaLocation", "$projectDir/schemas") }`; commit `app/schemas/`; create the `androidTest` source set; add `androidx.room:room-testing`; write one `MigrationTestHelper` test per step 1→2 through 11→12 plus a full 1→12 chain test. Remove the stale `fallbackToDestructiveMigration()` discussion from the `NoSlopDatabase` header (lines 25–28) — the builder no longer calls it.

## P2-2. Reduce the ProGuard keep surface

**File:** `app/proguard-rules.pro`

Five `com.noslop.app.*` package wildcards keep `data` (6,389 lines), `mesh` (5,895), `feeds` (5,185), `net` (1,386) and `util` (893) — 19,748 of 45,822 lines, about 43% of the app. The `ui` package (23,372 lines) is not kept, so R8 does still shrink the largest layer; this is not a no-op, but 43% is a lot to exempt.

**Do:** remove the wildcards one at a time, starting with `util` and `net` (fewest Gson-reflected types), annotating the reflected model classes `@Keep` instead: `mesh/Packets.kt`, `util/UpdateChecker.kt` (`UpdateInfo`, `ContentJson`, `HeroBlock`), `data/UserProfile.kt`, `ui/QRScannedPeer`. Fix `IdentityRepository.isEncryptionActive()` first — it currently depends on `-keep class androidx.security.crypto.**` to work at all. After each removal, build release and exercise onboarding, QR scan, a mesh handshake, a DM, and an update check.

## P2-3. `NoSlopRepository` is a 1,829-line facade with logic mixed in

**File:** `data/NoSlopRepository.kt`

159 functions, of which 98 are thin (≤4 line) pass-throughs to `MeshSocialRepository`, `FeedRepository`, `SettingsRepository`, `PreferencesRepository`, `IdentityRepository` or `EngagementRepository`. The remaining ~60 hold real logic — including `sendGroupMessage` (90 lines) and `invokeHubApi`.

**Do:** expose the sub-repositories on `NoSlopRepository` as public properties, have `NoSlopViewModel` call them directly, and delete the pass-throughs. Move the genuine logic that remains into the sub-repository it belongs to — `sendGroupMessage` into `MeshSocialRepository`, `invokeHubApi` into a `HubRepository`. Do this **after** P0-1, so the group-chat rewrite is not done twice.

## P2-4. `NoSlopViewModel` is 2,948 lines

Split along the sub-repository boundaries that already exist beneath it, once P2-3 lands.

## P2-5. Error handling hygiene

41 empty catch blocks (`catch (...) { }`) in `app/src/main`, plus 92 `!!` and 2 `printStackTrace`. At minimum give every empty catch a `Logger.debug` naming the swallowed condition. Prioritise the ones in `MainActivity` splash logic and `BackupManager`, where a silent failure is invisible to the user.

---

# P3 — Dead code to remove

All 34 items below were confirmed to have **no call site anywhere** in `app/src/main` or `app/src/test`, counting qualified calls, trailing-lambda Composable invocations, and `::` references. Approximately 555 lines. Remove them unless an item is being wired up under an earlier instruction.

**Do not remove these three without first reading the linked instruction:**

| Symbol | File | Why it needs a decision |
|---|---|---|
| `requestNewCircuit` (52 lines) | `tor/TorService.kt:143` | See **P1-3**. Removing it also means removing `_circuitGeneration` and rewriting the `VideoPlayer` staleness check. |
| `clearBurnableIdentity` | `data/IdentityRepository.kt:285` | See **P1-2**. Should be *wired up*, not deleted. |
| `clearPinnedHostKey` | `net/SshDeployer.kt:35` | See **P1-8**. Should be *wired up*, not deleted. |

**Safe to delete:**

| Symbol | File | Lines |
|---|---|---|
| `TorWarningPanel` | `ui/TorWarningPanel.kt` | ~144 (delete the whole file) |
| `editMeshPost` | `data/MeshSocialRepository.kt` | ~35 |
| `editMeshComment` | `data/MeshSocialRepository.kt` | ~37 |
| `deleteMeshComment` | `data/MeshSocialRepository.kt` | ~30 |
| `FullScreenImage` | `ui/components/FeedCard.kt` | ~30 |
| `startAudioRecording` | `mesh/MediaCaptureManager.kt` | ~25 |
| `stopAudioRecording` | `mesh/MediaCaptureManager.kt` | ~14 |
| `cacheCreatorMedia` | `mesh/MediaManager.kt` | ~19 |
| `discardFeedItem` | `ui/NoSlopViewModel.kt` | ~14 |
| `injectMeshClearnetToFeed` | `ui/NoSlopViewModel.kt` | ~13 |
| `addCustomFeedSource` | `ui/NoSlopViewModel.kt` | ~12 |
| `prebufferCeilingBytes` | `ui/PreloadManager.kt` | ~11 |
| `stopListening` | `mesh/MeshTransport.kt` | ~10 |
| `isSourceCached` | `ui/components/VideoPlayer.kt` | ~10 |
| `isMeshListening` | `ui/NoSlopViewModel.kt` | ~8 |
| `proxyIsCoolingDown` | `feeds/api/YouTubeInternalClient.kt` | ~6 |
| `advanceFeedTutorial` | `ui/NoSlopViewModel.kt` | ~5 |
| `exceedsTorSizeCeiling` | `feeds/api/YouTubeInternalClient.kt` | ~4 |
| `checkLockStatus` | `ui/NoSlopViewModel.kt` | ~3 |
| `deleteFeedSource` | `ui/NoSlopViewModel.kt` | ~3 |
| `clearPeerCooldown` | `mesh/GossipService.kt` | ~3 |
| `touchRelayState` | `mesh/GossipService.kt` | ~3 |
| `declaredContentLength` | `ui/PreloadManager.kt` | ~2 |
| `sendTestPost` | `ui/NoSlopViewModel.kt` | ~1 |
| `clearApiItems`, `clearApiSources`, `deleteCommentsForPost`, `deleteExpiredItems`, `deleteReactionsForPost`, `deleteVotesForPost`, `updatePeer` | `data/Daos.kt` | ~27 total |

### Notes on three of these

**`isSourceCached`** is worth a moment. It was repaired by a shipped audit patch (it had been looking up a bare URL against entries keyed `"$url||$quality"`), and it turns out nothing calls it. The fix was applied to an orphan.

**`editMeshPost` / `editMeshComment` / `deleteMeshComment`** mean the app can *receive and apply* `EDIT_POST`, `EDIT_COMMENT` and `DELETE_COMMENT` packets — the handlers and verifier cases all exist — but can never *send* them. Either wire these to the UI (post and comment editing is a reasonable feature and the protocol already supports it) or delete them and note in `WIRE_PROTOCOL_REFERENCE.md` that NoSlop is receive-only for those three types.

**`startAudioRecording` / `stopAudioRecording`** are unreachable while `android.permission.RECORD_AUDIO` is declared in the manifest. Either wire them into `MediaCaptureManager`'s UI or delete both and drop the permission. Shipping an unused microphone permission in a privacy-first app is a bad look independent of the code.

### Dead resources and repo files

- `res/drawable/ground_zero_qr.png` (4.4 KB) — no reference in any `.kt` or `.xml`. Delete.
- `_workspace/gChat/` and `_workspace/hai/` are empty directories. Either clone the repos as `WIDER_INFRASTRUCTURE.md` intends, or remove them and add `_workspace/` to `.gitignore`.
- `tests/` at the repo root holds eight ad-hoc scripts (`test_youtube*.py`, `test_yt.sh`, `test_bc.kts`, `test_regex.py`, `test_time.kt`). `.gitignore` excludes `*.py` and `*.sh`, so these were force-added past it, as was `get-git.sh`. Decide: either move them into a `scripts/` directory and un-ignore that path explicitly, or delete them. The current state — ignored by pattern, committed by force — is the worst of both.

---

# P4 — Duplication to consolidate

## P4-1. `applyProxyAuthHeaders` is byte-identical in three files

`feeds/api/JamendoApiClient.kt`, `feeds/api/RedditApiClient.kt`, `feeds/api/YouTubeInternalClient.kt` — the function bodies hash identically. Each also carries its own `PROXY_URL` / `PROXY_SECRET` constants.

**Do:** extract to a single `feeds/api/ProxyAuth.kt`. If P1-9 removes the HMAC scheme, this deletes itself instead.

## P4-2. `ChatThreadScreen` and `GroupChatThreadScreen` share 582 identical lines

850 and 836 lines respectively, 0.69 similarity. Both also carry their own copy of `buildMediaMetadata`.

**Do:** extract the shared message list, bubble rendering, reply-preview, reaction bar and composer into `ui/components/chat/` composables parameterised over a thread-type sealed class. Do this **after** P0-1, since the group path is changing.

## P4-3. Bitmap downscale-and-encode duplicated three times

`ui/UnifiedFeedTab.kt:1868`, `ui/components/ChatThreadScreen.kt:180`, `ui/components/GroupChatThreadScreen.kt:191` — the same 12-line `val width = bitmap.width` block. Extract to `ui/MediaUtils.kt` alongside the existing helpers and have all three call it.

## P4-4. The Hub deploy block is copied three times

`ui/tabs/HubSetupScreen.kt:621, 722, 755` are near-identical `isDeploying = true` blocks. Consolidate into one private helper before wiring the host-key callback in P1-8, so the callback is added once rather than four times.

## P4-5. Smaller repeats

- `feeds/api/YouTubeInternalClient.kt:211` and `:348` — the same proxied `/youtubei/v1/search` request builder, including the identical `.removeHeader("X-Proxy-Timestamp")` sequence.
- `net/SshDeployer.kt:372` and `:662` — the same `b64_str = "$expandedSeedB64"` key-injection script fragment.
- `mesh/SyncPacketHandler.kt:64` and `:191` — the same comment-to-entity mapping.
- `crypto/CryptoService.kt` — `deriveTripcode` and `deriveOnionAddress` each contain an identical inline base32 encoding loop. Extract one `private fun base32(bytes: ByteArray): String`.
- `str(...)` JSON helper duplicated in `HackerNewsApiClient`, `OpenverseApiClient`, `WikipediaApiClient`; `stripHtml` duplicated in `FeedParser` and `GuardianApiClient`. Extract both to a shared `feeds/api/JsonUtils.kt`.

---

# Appendix A — Verified correct. Do not "fix" these.

These were checked against the code and are right. Several were flagged incorrectly in an earlier pass over this codebase; they are listed so the mistake is not repeated.

**A-1. Background playback works, by design.** Nothing observes the activity lifecycle to pause the player, Compose's composition survives `onStop`, and `NoSlopForegroundService` keeps the process alive. Playback with the screen off is intended behaviour. Do not add an `ON_STOP` pause handler. (The separate audio-focus gap is P1-5.)

**A-2. Tor v3 onion derivation is correct.** `deriveOnionAddress` computes `checksum = SHA3-256(".onion checksum" || pubkey || version)[:2]` and encodes `pubkey || checksum || version` in base32 — exactly the rend-spec-v3 construction. The `while (sb.length < 56)` pad is unreachable (35 bytes is exactly 56 base32 chars) but harmless.

**A-3. `getRawEd25519Seed` is correct.** SHA-512 of the seed with the standard clamping is the right expansion for `ADD_ONION ED25519-V3`.

**A-4. The tripcode is recomputed, not trusted.** `HandshakePacketHandler:76` derives it from the sender's public key rather than reading it off the wire, and every other use is display-only — peers are keyed by `publicKeyB64` throughout. It is not a forgeable trust token. The only real point is that 6 base32 chars is 30 bits, so a user comparing it by eye can be fooled by a grinded collision; that is a copy problem (P1-11), not a vulnerability.

**A-5. Backup encryption is sound.** AES-256-GCM, random 12-byte IV via `SecureRandom`, PBKDF2-HMAC-SHA512, streamed, and the tag verified by `doFinal()` before anything is unzipped. (The separate CBC-downgrade concern is P1-10.)

**A-6. `MediaProxyService` is correctly hardened.** Bound to `127.0.0.1`, gated by a per-process random token compared in constant time, with a strict `^[a-z2-7]{56}\.onion$` allowlist on the fetch target, answering 404 rather than 401 so a probing app learns nothing.

**A-7. SOCKS5 stream isolation is real and correct.** The hand-rolled `TorSocksSocket` implements RFC 1929 username auth and RFC 1928 `CONNECT` with `ATYP 0x03`, and the torrc carries `IsolateSOCKSAuth KeepAliveIsolateSOCKSAuth`. `TorDns` correctly defers resolution to the exit node.

**A-8. There is deliberately no clearnet media fallback.** The `NOSLOP_TOR_CIRCUIT_V1` comment in `HttpClientProvider` explains why, and `activeMediaClient` genuinely shares one client with resolution so the `ip=` lock matches the resolving exit. Leave it.

**A-9. `network_security_config.xml` is good.** System trust anchors only, no user CAs, TLS required, cleartext confined to `.onion` and loopback.

**A-10. Verify-before-forward in `GossipService` is correct,** as is the dedup check/record split that stops a forged `packet.id` from displacing the real one.

**A-11. OTA update handling is correct.** Downloads to `filesDir/updates` with a matching `<files-path>` entry, digests while streaming, refuses a release with no published SHA-256 unless explicitly overridden. Note that the checksum and the APK URL come from the same source, so it provides integrity rather than authenticity — Android's own APK signature check is what actually prevents a substituted build. Worth a line in the README, not a code change.

**A-12. "Graceful exit hopping" is implemented,** via `videoStreamNonces` bumping in `YouTubeInternalClient`, not via NEWNYM. Leave that mechanism alone; the dead NEWNYM path is P1-3.

---

# Appendix B — Suggested sequence

1. **P0-4, P0-5** — small, isolated, immediately verifiable. Good warm-up.
2. **P0-1, P0-2** — the group-chat rewrite. Do these together; they touch the same code.
3. **P0-3** — read the logs first, then flip or debug.
4. **P1-4, P1-5** — two-line fixes.
5. **P1-1, P1-2, P1-3** — each needs a product decision before code.
6. **P3** — delete the dead code, minus the three items deferred to P1.
7. **P4-1, P4-3, P4-4, P4-5** — mechanical consolidation.
8. **P2-1** — schema export and migration tests, before any further schema change.
9. **P1-7, P2-3, P2-4, P4-2** — the large refactors, last.
10. **P1-11** — documentation pass, after the behaviour it describes has settled.
