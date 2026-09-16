# NoSlop — Verification Report

**Scope:** re-audit of `NoSlop-main` against the previous remediation instructions.
**Snapshot:** versionCode 51, `0.5.1-alpha` (unchanged). 48 files changed, 3 added, 2 deleted.
**Method:** every item re-checked against the code, not against commit messages.

## Headline

All five P0 items are genuinely fixed, and fixed properly rather than papered over. The group-chat rewrite in particular is complete on both the send and receive sides, with the store-and-forward queue actually wired to a flush trigger and an expiry sweep — the parts that are easiest to leave dangling. Dead code dropped from 34 functions to 15. Documentation was corrected honestly, including the parts that were unflattering.

Nine items remain open, most of them deliberately and recorded in the new `docs/FINDINGS.md`, which is the right way to carry them.

Twelve new issues were introduced. One is serious: **`GroupMessageCrypto.encrypt()` silently falls back to storing plaintext** when the Keystore throws, which reopens P0-2 under failure conditions. The rest are small.

---

# 1. Confirmed fixed

## P0-1 — Group chat end-to-end encryption ✅

Verified in full:

- The cleartext `GROUP_MESSAGE` gossip broadcast is gone from `NoSlopRepository.sendGroupMessage()`. Nothing in the app emits that packet type any more.
- The `encPub = peer.encPublicKeyB64.ifBlank { memberPub }` fallback is replaced with a `CONNECTION_REQUEST` and a `continue`, so an Ed25519 key is never fed to X25519.
- `pending_group_messages` exists as a real table with a composite primary key, is populated when a member's onion is unknown, is flushed from `MeshSocialRepository.flushOutboxForPeer()` (line ~157), and is swept by `deleteExpired(sevenDaysAgo)` at line 201. `flushOutboxForPeer` has four live call sites, including `HandshakePacketHandler:332` when a peer's onion changes. The queue is wired end to end.
- `handleGroupMessage()` now checks group membership against `membersJson`, requires a signature, verifies it with `encodeForSigning(groupId, id, content, timestamp, senderId)`, and stores `senderPub = packet.senderId` — the real public key.
- `MeshPacketVerifier` has a matching `GROUP_MESSAGE` case using the identical canonical string.
- `GroupChatThreadScreen` resolves handles at render time from `memberHandlesJson` and the peers table (lines 414–429), so the switch from handle to public key in `senderPub` didn't break display.

## P0-2 — Group bodies encrypted at rest ✅ (with a caveat, see §3.1)

`GroupMessageCrypto` uses an Android Keystore AES-256-GCM key with `setUserAuthenticationRequired(false)`, a per-message IV from the cipher, and an `ENC:GCM:` prefix. Migration 12→13 re-encrypts existing rows and is idempotent via the prefix check. Both write paths (`sendGroupMessage`, `handleGroupMessage`) encrypt; the read path decrypts in `NoSlopRepository.getMessagesWithPeer()`.

I checked whether the change leaked ciphertext into any other surface. It doesn't: `conversations` (the DM list preview) is only matched against `peer.publicKeyB64`, never a groupId, and `syncDmsWithHub()` iterates peers only. No `ENC:GCM:` string can reach the UI or the Hub.

## P0-3 — Tor control port ✅

`MODE = Mode.UNIX_ONLY`. The `ControlPort` fallback inside the `UNIX_ONLY` branch is gone — it now logs an error and emits an empty string. `CookieAuthentication 0` is appended only when the emitted lines contain `ControlPort` (`TorService.kt:625`). And there's a post-bootstrap self-check at `TorService.kt:447` that attempts a TCP connect to the control port and logs `SECURITY ALERT` if it succeeds. That last one wasn't strictly required and is a good addition.

## P0-4 — Media proxy URL ✅

`MainActivity.kt:98` now calls `MediaProxyService.buildProxyUrl(onion, id)`. No hand-built `/stream?` strings remain anywhere.

## P0-5 — Onion derived from a private key ✅

The collision branch now calls a new `CryptoService.getPublicKeyFromPrivateKey()` first. Better still, the underlying hazard is closed: `getEd25519PublicKeyParams` throws on a 48-byte PKCS#8 input and `getEd25519PrivateKeyParams` throws on a 44-byte X.509 input, instead of silently truncating. That was the part that mattered.

## P1-1 — Word Cloud ✅ (with a gap, see §3.5)

Option A taken. `CryptoService.deriveIdentityFromSeed(seed, handle)` derives the Ed25519 seed via `HKDF-SHA512(seed, info="noslop-identity-ed25519-v1")` and the X25519 key via the `x25519-v1` info string, exactly as specified. `completeOnboarding()` now calls it, so new identities are derivable from the phrase. `SUPPORT.md:19` was rewritten accurately, the "24-word" error is gone, and the "(BIP39)" label is removed.

## P1-2 — Burnable identity ✅

`NoSlopRepository.clearBurnableIdentity()` now builds and broadcasts a signed `USER_EXIT` from the burnable key, calls `TorService.unregisterBurnableHiddenService()`, clears the Keystore entries, and emits on `_identityUpdateFlow`. `setCreatorEnabled(false)` calls it. The identity is now genuinely severable.

## P1-4 / P1-5 ✅

`WAKE_LOCK` is declared. `ExoVideoPlayer` sets audio attributes with `handleAudioFocus = true` at `VideoPlayer.kt:1196`. No lifecycle-pause handler was added, so background playback still works as intended.

## P1-6 — Gossip firewall ✅

A dedicated `announcementRateLimits` map gives `ANNOUNCE_DISCOVERABLE`, `IDENTITY_UPDATE` and `USER_EXIT` a 5-per-60s per-sender budget, with matching cleanup. `payloadSize` uses `toByteArray(Charsets.UTF_8).size`. The media budget comment now correctly says 60s.

## P1-11 — Documentation ✅ (one miss, see §3.7)

The README was corrected on every point: the Tor badge now reads "Tor Default (Toggleable)", the group-chat bullet describes pairwise X25519 fan-out and the store-and-forward outbox, the tripcode is now "visual display disambiguator", the persistence row names Keystore message encryption, and the `HUB_INTEGRATION_PLAN.md` link points at `docs/archived/`. All ten README doc links resolve. `docs/FINDINGS.md` was created and is referenced from `MeshPacketVerifier.kt` as that file expected. `PRIVACY_POLICY.md:42` now names the Cloudflare Worker by hostname and explains that with Tor disabled the request reaches it over the user's ISP connection — a straightforward disclosure.

## P3 — Dead code ✅ mostly

Down from 34 functions to 15. `ui/TorWarningPanel.kt` deleted, `res/drawable/ground_zero_qr.png` deleted, the audio-recording functions deleted, and no dangling references to any removed symbol.

One judgement call to flag as **correct**: `RECORD_AUDIO` was kept in the manifest. I'd suggested it might be droppable once the dead audio functions went, but `startVideoRecording` calls `withAudioEnabled()` and checks the permission at `MediaCaptureManager.kt:139`. Keeping it is right.

## P4 — Duplication ✅ mostly

`ProxyAuth.kt` and `JsonUtils.kt` created and adopted by all three API clients. `CryptoService.base32()` extracted. The bitmap downscale block now exists once, in `MediaUtils.kt:59`. The Hub deploy call sites went from four to two.

---

# 2. Still open

| Item | State | Notes |
|---|---|---|
| **P1-7** canonical signing | Not started | Three formats still in use. `MeshPacketVerifier` lines 130, 136, 178, 214, 226, 238–239 still build ambiguous `\|`-joined strings with optional appends; the `GROUP_INVITE` multi-format candidate loop is intact. `encodeForSigning` still uses UTF-16 `length` and still collides `null` with `""`. Tracked honestly in `FINDINGS.md §1`. |
| **P2-2** ProGuard | Not started | All seven `com.noslop.app.*` wildcards remain; zero `@Keep` annotations in the codebase. Tracked in `FINDINGS.md §4`. |
| **P2-3 / P2-4** decomposition | Not started | Tracked in `FINDINGS.md §5`. |
| **P1-3** circuit rotation | Half done | The `VideoPlayer` staleness check is correctly re-keyed on `streamNonce`, which was the important half. But `requestNewCircuit()` (52 lines), `_circuitGeneration` and `circuitGeneration` are still present in `TorService` and still have zero callers outside that file. The instruction was to pick one mechanism and delete the other; the deletion wasn't done. |
| **P1-8** SSH host key | Half done | `onHostKeyPrompt` is wired at `HubSetupScreen.kt:342`. The other deploy call site — `HubSetupScreen.kt:258`, the `UPDATE_HUB` path — still passes no callback, so that connection is still trust-on-first-use while carrying the SSH password. `SshDeployer.clearPinnedHostKey()` still has no caller, so there's no way to re-pin after a legitimate host change. |
| **P1-9** proxy secret | Half done | `ProxyAuth.kt` consolidates the three copies, and the privacy policy now discloses the proxy. But `PROXY_SECRET` still defaults to the committed `NoSlopRocks2026` and still ships in `BuildConfig`, and `JamendoApiClient.CLIENT_ID = "709fa152"` is unchanged. The disclosure is the bigger half; the shipped-secret theatre remains. |
| **P2-1** Room schema safety | Half done — see §3.6 | |
| **P2-5** empty catches | Marginal | 41 → 34. |
| **P4-2** chat screens | Not started | `ChatThreadScreen` (820) and `GroupChatThreadScreen` (807) still share 553 identical lines, and `buildMediaMetadata` is still defined twice. |

---

# 3. New issues introduced

## 3.1 `GroupMessageCrypto.encrypt()` silently degrades to plaintext — **fix this one**

```kotlin
} catch (e: Exception) {
    Logger.error("CRYPTO", "Group message encryption failed: ${e.message}")
    Pair(plaintext, "")
}
```

On any Keystore failure — key invalidated by a lock-screen change, an OEM Keystore quirk, a `KeyPermanentlyInvalidatedException` — the function returns the plaintext with an empty nonce, the caller inserts it, and `decrypt()` passes it straight through because it lacks the `ENC:GCM:` prefix. The message is stored in clear and nothing downstream can tell. This is precisely the failure shape the original audit flagged as item #3, when `sign()` returned an empty string instead of throwing; it was fixed there and has now reappeared here.

**Fix:** throw. `sendGroupMessage` and `handleGroupMessage` should catch, log, and refuse to insert the row rather than insert it unprotected. If a soft-fail is genuinely wanted, store a sentinel the UI renders as "could not be secured on this device" — never the plaintext.

## 3.2 No AAD binding

The GCM encryption isn't bound to the message id or group id. Anyone with database write access can move a ciphertext+nonce pair from one row to another and it will decrypt cleanly under the wrong identity. Pass `cipher.updateAAD("$groupId|$msgId".toByteArray())` on both sides.

## 3.3 `getOrCreateKey()` runs on every call

Both `encrypt()` and `decrypt()` do a full `KeyStore.getInstance("AndroidKeyStore").load(null)` plus `getEntry()`. Opening a 500-message group thread means 500 Keystore round-trips on the flow's collector. Cache the `SecretKey` in a `@Volatile` field, invalidating on `KeyPermanentlyInvalidatedException`.

## 3.4 `peerPub.contains("-")` is now load-bearing

`NoSlopRepository.getMessagesWithPeer()` decides whether to decrypt by testing whether the chat key contains a hyphen, and migration 12→13 uses `chatWithPeerPub LIKE '%-%'` for the same purpose. It works — `Base64.NO_WRAP` emits `+` and `/`, never `-` — but that's an undocumented invariant now guarding the decryption path. If anything ever switches to URL-safe Base64, group messages silently stop decrypting and DM rows start getting mangled. Add an explicit `isGroup` column, or resolve against `groupChatDao()`, and at minimum comment the invariant.

## 3.5 There is still no restore-from-mnemonic screen

`deriveIdentityFromSeed` has exactly one caller: `completeOnboarding`. So the phrase now *determines* a new identity, but a user holding only their twelve words and a fresh device still has no path back — the only restore route is importing a backup zip, and `OnboardingScreen.kt:362` still describes the phrase as being "to decrypt the backup file".

Two consequences worth planning for now rather than later:

- The recovery promise is half-built. The cryptography is there; the screen isn't.
- No identity-version flag was added. When that screen arrives, a pre-0.5.1 user entering their phrase will be handed a *different* identity than the random one they actually own, silently. Add the version marker to the stored identity before shipping the restore UI, not after.

## 3.6 Room migration tests can't actually run

`exportSchema = true`, `room.schemaLocation` and the committed `13.json` are all correct. But:

- `androidx.room:room-testing` was added as `testImplementation`, not `androidTestImplementation`. `MigrationTestHelper` needs instrumentation.
- There is no `app/src/androidTest` source set at all.
- Only `13.json` exists, because earlier versions were never exported. Even with instrumentation, only 12→13 is testable; the 1→12 chain has no reference schemas.

Migration 12→13 is the riskiest one ever written here — it touches every group message row — and it's currently untested. Create the `androidTest` source set, move the dependency, and write the 12→13 test at minimum. The historical schemas can be reconstructed by checking out each prior tag and building with export on, if you want the full chain.

## 3.7 `WIRE_PROTOCOL_REFERENCE.md` is now stale

Line 124 still describes `GROUP_MESSAGE` as "Masked identity mesh broadcast", and line 688 as "*(identity-masked mesh broadcast; routing validated by group membership)*". Neither is true any more: the app never sends the packet, and receipt requires a member signature. Update both, note that NoSlop is receive-only for this type during the compatibility window, and bump the documented protocol version.

## 3.8 `burnCreatorIdentity()` is new dead code

Added at `NoSlopViewModel.kt:2411` with no UI caller. The separate "Burn Creator ID" action it was meant to back doesn't exist in `SettingsTab` or `CreatorStudioTab`. Either add the button or drop the function.

## 3.9 No tests for the new security-critical paths

`CryptoDerivationTest`, `GossipServiceTest` and `WireProtocolTest` are welcome additions — the golden-vector tripcode and onion tests are exactly right. But nothing covers:

- `deriveIdentityFromSeed` determinism, or that the same mnemonic yields the same onion across runs.
- `handleGroupMessage` rejecting a non-member, rejecting a missing signature, rejecting a forged one.
- `GroupMessageCrypto` round-trip, or the prefix-based idempotence the migration depends on.
- The store-and-forward enqueue → flush → delete cycle.

`MeshPacketVerifierTest` has no `GROUP_MESSAGE` case, despite the verifier gaining one.

## 3.10 Legacy backup import is a dead end

`allowLegacyUnauthenticated` is threaded correctly from `BackupManager` through the ViewModel to `SettingsTab`, but `SettingsTab.kt:1392` hardcodes `false`, and `onLegacyDetected` only sets `importStatus = "Legacy archive detected. Requires confirmation."` There is no second dialog through which the user can give that confirmation. So legacy CBC archives are now unimportable, and the message tells the user a path exists when it doesn't. Either add the confirm dialog, or change the copy to say the archive format is no longer supported.

## 3.11 Two cosmetic inconsistencies

- `DmPacketHandler.handleGroupMessage` stores `senderPub = groupMsg.senderHandle` on the `NotificationItem` row. Harmless — it's a notification, not a message — but the field name now means two different things in two tables.
- `TorService.waitForControlPort()` still carries the doc comment "Wait for the ControlPort (9051) to be ready." It goes through `TorControlChannel.open()` now and no longer touches 9051.

---

# 4. Suggested next pass

1. **§3.1** — the plaintext fallback. Small, and it's the one thing that partially undoes P0-2.
2. **§3.2, §3.3** — AAD and key caching, same file, same sitting.
3. **§3.10** — either the dialog or the copy. Ten minutes either way.
4. **P1-8 remainder** — the `UPDATE_HUB` call site at `HubSetupScreen.kt:258`, plus a Settings entry for `clearPinnedHostKey()`.
5. **P1-3 remainder** — delete `requestNewCircuit`, `_circuitGeneration`, `circuitGeneration`, `newnymMutex` and the NEWNYM interval constants. The nonce mechanism has fully replaced them.
6. **§3.6** — `androidTest` source set, move `room-testing`, write the 12→13 migration test.
7. **§3.9** — group-message rejection tests. These are cheap, and they lock in P0-1.
8. **§3.7, §3.8** — wire protocol doc, and the burn button or its removal.
9. **§3.5** — plan the restore screen and add the identity-version flag before it ships.

P1-7, P2-2, P2-3 and P2-4 are fine where they are. `FINDINGS.md` tracks them accurately and they're the kind of work that wants a quiet week, not a patch.
