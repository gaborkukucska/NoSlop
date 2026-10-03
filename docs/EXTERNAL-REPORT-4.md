# NoSlop legacy Android — external code review

Date: 2026-10-03  
Audience: developing LLMs and maintainers  
Input: `NoSlop-main(3).zip`; ZIP comment identifies snapshot `9550c238be8b0e15f35161ec91adb39b968c1999`  
Archive SHA-256: `285272b6867c7235e4fe094729c21207c9b73363b6392bd7d4a45e50627c5825`  
App configuration: `0.6.5-alpha`, version code 65; Room schema 15

## 1. Overall opinion

**The legacy app contains substantial real implementation, but I would not call this snapshot release-ready for a privacy-sensitive messaging product.** The biggest problems are not mock screens or missing feature counts. They are broken invariants between otherwise substantial components: privacy labels disappear during sync, signatures omit fields that control authorization, backup restoration does not preserve all cryptographic state, and feed synchronization has lifecycle errors.

There is worthwhile engineering here: separated repositories and packet handlers, authenticated backup encryption, device-backed group-message encryption, explicit database migrations, bounded mesh frames, loopback listeners, a token-protected media proxy, signature verification before many relay operations, and route/expiry-aware media caches. These should be preserved. A rewrite would unnecessarily discard working behavior.

The next development cycle should stabilize privacy, identity recovery, and synchronization before adding features or performing broad cosmetic refactoring. Fix each invariant with a focused regression test, then consolidate the duplicated logic that allowed it to diverge.

## 2. Scope, method, and limits

Reviewed the root Android build configuration, `app/`, relevant build documentation, and existing external reports. **Excluded `mvp/` and its implementation, tests, and migration plans.** The Android app's own Hub integration is in scope; the separate Hub server is not included in this archive and was not audited.

Inventory from this snapshot:

| Item | Count |
|---|---:|
| Main Kotlin files | 116 |
| Main Kotlin lines, including comments/blank lines | 50,113 |
| Kotlin files under unit/instrumentation test trees | 20 |
| `@Test` annotations in those files | 125 |
| Language JSON files | 21 |
| Available exported Room schemas | 13, 14, 15 |

Method: source inventory and pattern scans, followed by targeted call-path review of feed refresh, post/group protocol handling, gossip, backup/identity storage, database lifecycle, network settings, media resolution/preloading/proxying, updates, and SSH deployment. Large UI files and individual provider clients received targeted inspection rather than exhaustive line-by-line verification. This is not a penetration test or full cryptographic audit.

No `start_HERE.txt` or `AGENTS.md` was present in the archive. Existing reports were treated as leads, not evidence. Several of their open findings are already fixed in this snapshot; see section 8.

Attempted:

```bash
GRADLE_USER_HOME=/tmp/noslop-review-gradle bash gradlew \
  :app:testGithubDebugUnitTest :app:lintGithubDebug --offline
```

The wrapper attempted to obtain the uncached Gradle 9.6.0 distribution and failed with `java.net.SocketException: Operation not permitted`. An initial attempt also encountered the environment's unwritable default Gradle directory, which was addressed by using the temporary directory above. **Compilation, lint, unit tests, instrumentation tests, and device playback were not completed.** This is an environment limitation, not proof that the project's build fails. No dependency compatibility or current upstream vulnerability claims are made here.

Labels used below:

- **Confirmed:** the defect follows from the inspected source/control flow; not necessarily exercised on-device.
- **Risk:** source establishes the unsafe design, but timing, platform behavior, or end-to-end impact needs a targeted test.
- **P0:** release blocker for privacy/authorization. **P1:** urgent correctness, data preservation, or availability. **P2:** hardening or maintainability.

Paths below are repository-relative. For brevity, `K/` means `app/src/main/java/com/noslop/app/`. Line ranges refer to this ZIP snapshot.

## 3. Priority findings

### F01 — P0 — Sync turns friends-only posts into public posts

**Confirmed.** `K/mesh/SyncPacketHandler.kt:79–104` and the corresponding mapping in `handleInventorySyncRequest` construct `PostPayload` without `privacy`. `K/mesh/Packets.kt:19–28` defaults it to `"public"`. The receive mapping in `SyncPacketHandler.kt:376–393` also omits `privacy` when constructing `MeshPost`, whose entity default is public.

The outgoing `canSharePost` filter does not prevent this: an author can legitimately synchronize a friends-only post to a direct friend, but that friend stores it as public. On a later sync, `canSharePost` permits sharing that stored row with other peers. Receiving a correctly labelled payload from another client still loses the label at the persistence mapping.

**Fix:** create one explicit, complete post-to-wire and wire-to-post mapping; preserve audience and security-relevant metadata in both sync paths. Do not rely on constructor defaults. Consider existing rows already downgraded: they cannot reliably be repaired by guessing their original audience.

**Acceptance:** A sends friends-only post to B through normal and inventory sync; B persists `friends`; neither B's sync to C nor later gossip exports it. Repeat with an existing row and ensure synchronization cannot downgrade privacy accidentally.

### F02 — P0 — Post signatures do not bind privacy or attachments

**Confirmed.** `K/data/MeshSocialRepository.kt:527–540,591–612` signs post ID, author key, content, timestamp, and avatar. `privacy`, media ID/metadata, and clearnet attachment fields are outside the signature. `K/mesh/PostPacketHandler.kt:38–52,80–100,155–192`, `MeshPacketVerifier.kt:110–148`, and the sync verifier accept these narrow signatures while using the unsigned fields.

A node that has a valid signed post/edit can change its audience or attachment without invalidating that signature. In particular, `handleEditPost` applies unsigned `editPay.privacy`. This is separate from F01: preserving fields in serialization alone does not authenticate them.

**Fix:** introduce an explicit protocol/signature version and a canonical signed payload covering every security-relevant semantic field, including audience, stable media identity/integrity metadata, and attachment URLs. Keep genuinely mutable routing information separate. Domain-separate packet types. Apply the same codec to generation, pre-forward verification, handlers, and sync. A permissive old-signature fallback for new payloads would preserve the vulnerability; define a constrained legacy policy.

**Acceptance:** mutate each signed field individually and assert rejection in live-post, edit, and sync paths. Include replay of old-format packets under the migration policy. Do not claim friends-only content is confidential against its intended recipients: recipients can always copy plaintext, but the protocol must not silently authenticate their altered metadata as the author's.

### F03 — P0 — Group updates authorize changes that were never signed

**Confirmed.** `K/mesh/HandshakePacketHandler.kt:700–710` verifies a group update using only group ID, title, candidate signer, and timestamp. `handleGroupUpdate` at lines 816–887 then applies membership additions/removals, bans, permissions, description, avatar, and peer details from that packet.

Reusing a valid admin update signature with altered `addedMembers`, `removedMembers`, or permission fields still resolves the signer as admin. The authorization checks therefore do not establish that the admin approved those particular changes. Group-invite verification has a similar narrow payload at lines 713–729; review it in the same protocol change.

**Fix:** sign the entire canonical group operation, its type, group ID, signer, revision, and mutations. Verify before modifying peer/group state. Define replay and stale-revision handling.

**Acceptance:** change membership, bans, permissions, and peer-detail keys independently while retaining a valid signature; all must be rejected. Cover admin and member operations separately.

### F04 — P0 — GROUP_SYNC trusts the incoming group's own keys

**Confirmed.** `K/mesh/HandshakePacketHandler.kt:1023–1093` builds signature candidates from `packet.senderId`, the incoming admin, and the incoming member list. It does not require the signer to be authorized by the stored group. `GossipService.kt:449–453` treats group controls as connection packets allowed through the ordinary trust firewall.

For a known group ID and recipient public key, a sender can construct a group snapshot containing themselves and the recipient, sign it with their own key, and pass this signature check. For an existing group, the handler merges incoming members and, when the local user is not the admin, copies incoming title/description/avatar/permissions. It does not first establish that the incoming authority matches the stored authority. `syncMemberPeers` also runs before the recipient-membership rejection.

**Fix:** resolve authority from stored group state, verify a signed admin snapshot/revision or a strictly scoped member operation, and only then merge state. Unknown groups need an authenticated invitation/join policy; an incoming self-consistent member list is not authorization. Do not union previously removed/banned members back into an existing group through stale snapshots.

**Acceptance:** an unrelated signing key cannot change a known group's metadata or membership; a stale sync cannot resurrect a removed/banned member; rejected packets cause no peer writes.

### F05 — P1 — A member can remove another member while removing themselves

**Confirmed.** `K/mesh/HandshakePacketHandler.kt:848–852` accepts:

```kotlin
(removed.size == 1 && removed[0] == signer) ||
    (removed.contains(signer) && removed.size <= 2)
```

The second clause permits `[signer, anotherMember]`. The later mutation removes both. Blocking removal of the admin does not protect other members.

**Fix:** require the exact authorized identity set. If the two-entry case was intended for linked main/burnable identities, prove that link from trusted local state rather than accepting any second key.

**Acceptance:** a non-admin self-removal succeeds; adding any unrelated member to that removal fails atomically.

### F06 — P1 — Ordinary POST can overwrite a post or resurrect its tombstone

**Confirmed.** `K/mesh/PostPacketHandler.kt:70–102` inserts a newly constructed row with `isOrphaned = false`, without checking an existing author, tombstone, or timestamp. `K/data/Daos.kt:138–139` uses `OnConflictStrategy.REPLACE`. `handleSyncResponse` checks tombstones but does not enforce existing-author equality or newer revision before replacement.

A replayed old valid POST with a fresh outer packet ID can reset edits or resurrect a deleted post on the direct POST path. A different author can also sign their own payload using an existing post ID; verifying the incoming key alone does not prove ownership of the stored ID.

**Fix:** apply transactional ownership/revision/tombstone rules at the persistence boundary, shared by POST, EDIT_POST, and sync. Preserve deletion state independently of a replaceable post row where needed.

**Acceptance:** stale replay, cross-author ID collision, sync rollback, and resurrection after dedup-cache eviction are rejected; legitimate newer owner edits succeed.

### F07 — P1 — Feed sync can remain permanently locked

**Confirmed.** `K/data/FeedRepository.kt:201–233` sets `isSyncRunning` to true, then returns on network-not-ready or empty-source/category conditions without resetting it. Exceptions during setup/ramp-up also bypass the reset at line 298. All later refreshes on that repository instance return “already in progress.”

**Fix:** own the guard with `try/finally` around the complete operation. Represent skipped/not-ready outcomes explicitly so callers can distinguish a successful refresh from one that should retry.

**Acceptance:** force Tor timeout, no sources, DAO failure, and cancellation; a subsequent refresh must run without restarting the app.

### F08 — P1 — Existing feeds skip up to six creator keywords every refresh

**Confirmed.** `K/data/FeedRepository.kt:258–294,327–334` splits creators into `take(rampUpCount)` and `drop(rampUpCount)`, with six selected when feed items already exist. It fetches the first portion only inside `if (!hasExistingItems)`, then fetches only the remaining portion in the background.

With one to six creator keywords and an existing feed, none of the explicit creator-fetch calls runs. With more keywords, six shuffled entries are omitted each refresh. Other category searches can still return videos, masking the omission.

**Fix:** split off ramp-up creators only when ramp-up actually runs, or track the exact already-fetched set.

**Acceptance:** for both empty and populated databases, each configured creator is fetched exactly once per successful refresh; test 1, 6, and 7 creators.

### F09 — P1 — Background refresh outlives its worker and overlap guard

**Confirmed.** `K/data/FeedRepository.kt:297–340` clears the guard before creating a detached `CoroutineScope(dispatcher + SupervisorJob())`. `K/feeds/FeedSyncWorker.kt:17–20` reports success as soon as that method returns.

Most synchronization therefore runs outside WorkManager's tracked coroutine. A new refresh starts another detached pass; stopping/cancelling the worker does not own that work; process death may discard it after success was already recorded. Video polling can keep those detached jobs alive for a long time.

**Fix:** separate UI first-results notification from task completion. Keep worker work structured and awaited; if app-lifetime refresh is intentional, own exactly one job in an explicit coordinator with cancellation and coalescing. Re-throw coroutine cancellation rather than swallowing it in broad exception handlers.

**Acceptance:** repeated refreshes coalesce, worker completion means the requested pass is done, cancellation stops pending fetches, and disabling the aggregator cancels its outstanding work.

### F10 — P1 — Backup portability omits the group-message storage key

**Confirmed architectural gap.** `K/crypto/GroupMessageCrypto.kt:15–69` encrypts stored group bodies with Android Keystore alias `noslop_group_storage_key`. `K/data/BackupManager.kt:92–298` backs up the raw database, identity/API data, and media, but never exports a portable representation of those decrypted group bodies or rewraps them under a portable backup key.

On a different installation/device, the copied group ciphertext cannot be decrypted with a newly generated device key. Recovering the mnemonic or main identity does not recover this independent random Keystore key. `decrypt` returns the original ciphertext on failure, which can hide the distinction between restored data and readable history.

**Fix:** export logical group-message records through the real decrypt API inside the authenticated backup envelope; on import re-encrypt with the destination device key and the correct AAD. Do not attempt to export Android Keystore secret key bytes.

**Acceptance:** export on installation A, restore on fresh B with unrelated Keystore state, restart, and read old group history. Include main/burnable identities and both supported ciphertext versions.

### F11 — P1 — Restore overwrites portable credentials with device-bound XML

**Confirmed ordering defect; precise outcome is platform/timing dependent.** `BackupManager.kt:154–198` puts portable identity/API JSON into the ZIP, followed by raw encrypted preferences. Import reconstructs local credentials at lines 399–465, then overwrites the same preference files at lines 468–486 with the source device's XML.

This mixes asynchronous SharedPreferences writes, cached SharedPreferences instances, and direct file replacement. A same-process probe can observe cached state instead of what survives restart. The fallback at lines 516–562 only re-derives a main v2 identity; it does not preserve a random burnable identity or legacy random identity, and it cannot recover API keys from foreign encrypted XML. A fallback-identity file can also suppress that recovery check, despite the fallback encryption being device-derived.

**Fix:** make portable logical records authoritative in the new backup format; never overlay them with raw device-bound files. For legacy archives, stage and migrate using a separate, explicit path. Commit/verify destination state and validate public/private-key consistency before declaring success.

**Acceptance:** cross-device restore preserves exact main/burnable public keys, API keys, and sign/decrypt capability after process restart. Cover an already-onboarded destination, fallback storage, and legacy identity version 1.

### F12 — P1 — Legacy-backup confirmation is swallowed

**Confirmed.** `BackupManager.kt:349` throws `LegacyBackupConfirmationRequiredException`, but the method's outer `catch (Exception)` at lines 569–571 catches it and returns false. `K/ui/NoSlopViewModel.kt:2107–2108` expects to catch that exception to call `onLegacyDetected`.

The confirmation branch cannot be reached through this API as written. The existing `BackupManagerTest` tests standalone cipher operations rather than calling `BackupManager.importData`, so it does not cover this behavior.

**Fix:** use a typed import result such as `RequiresLegacyConfirmation`, or explicitly rethrow that exception before the general catch. Preserve refusal until the user confirms.

**Acceptance:** a valid old CBC archive triggers confirmation without modifying current data; decline leaves state intact; an explicitly confirmed retry follows the legacy path.

### F13 — P1 — Backup snapshot and restore are not atomic

**Confirmed design gap; data loss depends on concurrency/failure.** Export checks `PRAGMA wal_checkpoint(TRUNCATE)` only by moving its result cursor, then copies the main DB while repositories/workers can still write (`BackupManager.kt:103–120`). A checkpoint is not a durable snapshot lock. Its busy/result fields are not inspected, and even a thrown checkpoint failure only logs a warning.

Import writes `database.db` directly over the live target and then applies entries sequentially (`:388–487`), with no staging/rollback, expected-entry manifest, or schema/integrity validation before commit. Several identity/API errors are logged and import still returns true. `NoSlopViewModel.kt:2089` closes the active Room instance even before the archive is authenticated; a bad password can leave existing repositories holding closed DAOs without a restart or rebuild. Background jobs are not quiesced.

**Fix:** stage and validate the complete restore; impose archive size/entry limits; pause writers; use a supported consistent database snapshot; commit as an explicit restore transaction with rollback/restart ownership. Authenticate and validate before closing the active app database. Return partial/failure states accurately.

**Acceptance:** bad password, truncated ZIP, missing identity, full disk, and failure halfway through import leave the original app usable. Concurrent writes during export yield a consistent snapshot with defined inclusion semantics.

### F14 — P1 — Identity initialization deletes an unreadable key store

**Confirmed.** `K/data/IdentityRepository.kt:43–69` deletes an existing secure preference file after an initialization exception, then tries to recreate it. This converts an inability to read stored credentials into destructive recovery without establishing whether the error is transient or whether recovery material exists.

`secureFallbackWrite` also returns plaintext if fallback encryption throws (`:82–99`), while `secureFallbackRead` returns the encrypted marker/string if decryption fails (`:101–115`). Fallback encryption uses a key derived from Android ID plus a constant, not an independent user secret. It must not be described as equivalent to Keystore protection.

**Fix:** preserve/quarantine unreadable stores, surface an explicit recovery state, fail closed for secret writes, and validate decrypted private-key material before constructing an identity. Keep any intentionally degraded mode explicit and distinguish it from an encryption failure.

**Acceptance:** simulated transient Keystore error does not delete existing identity; fallback encrypt/decrypt failures never silently persist plaintext or return ciphertext as valid private-key material.

### F15 — P1 — Changing the Tor setting does not revoke existing direct clients

**Risk with a concrete lifecycle gap.** `K/data/SettingsRepository.kt:34–38` changes a flow and global boolean; `K/ui/NoSlopViewModel.kt:447–448` adds no transition cleanup. `K/NoSlopApp.kt:45–62` captures `activeClearnetClient` when constructing the Coil client's base. Already-created ExoPlayers and WebViews likewise own their existing network resources.

`VideoPlayer.kt:619–664` keys source/player preparation around URL, quality, visibility, and retry, not the routing setting. Resolution blocks new WebView embeds when Tor is enabled (`:339–343`), but that alone does not shut down an already running direct player/embed or recreate the app's image loader.

**Fix:** implement a routing-policy transition that cancels incompatible calls, releases active/preloaded direct players and embeds, rebuilds affected clients/loaders, invalidates route-bound cache entries, and then resumes through the chosen route. Alternatively, explicitly require a restart before claiming the setting has taken effect.

**Acceptance:** start images/video/embed with direct mode, enable Tor, and inspect outbound traffic; no subsequent external direct request should occur under the new policy. Also test persisted direct mode during startup hydration. This report did not capture device traffic.

### F16 — P1 — Loopback media proxy reads unbounded headers before authentication

**Confirmed.** `K/mesh/MediaProxyService.kt:57–68,85–125,307–320` accepts a socket, starts a coroutine, and reads until CRLF-CRLF into an unbounded `StringBuilder` before checking the token. No socket read timeout or connection cap is set on that path.

The token prevents unauthorized stream access, but another app on the device can connect to loopback and consume resources without knowing it. The exposure is local-app denial of service, not an Internet-accessible listener.

**Fix:** enforce header byte/line limits and a short read deadline, cap accepted connections, close rejected sockets promptly, and test slow/unterminated requests. Separately, add HTTP Range support: the dynamic stream advertises `Accept-Ranges: none`, and the cached-file response also ignores requested offsets. That limitation can degrade seeking and retry efficiency.

**Acceptance:** oversized/slow requests are terminated within bounded memory/time; valid playback remains available under local connection pressure; Range tests cover 206, invalid offsets, and incomplete media.

### F17 — P1 — Transport can report a failed write as success

**Confirmed API-use defect.** `K/mesh/MeshTransport.kt:248–256` writes through `PrintWriter`, flushes, waits 300 ms, and unconditionally records success. It never checks `PrintWriter.checkError()`. PrintWriter records underlying I/O errors rather than propagating them like a raw OutputStream write.

Even a successful socket write is only local transmission, not remote processing; the existing boolean and cooldown update must not be interpreted as durable acknowledgement.

**Fix:** use an error-propagating UTF-8 write or check the writer error state. Define separate queued/sent/acknowledged outcomes and keep durable outbox state until the protocol's actual delivery criterion is satisfied.

**Acceptance:** inject an OutputStream failure and assert a failed send with retry retained; verify that a remote processing failure is not presented as acknowledged delivery.

### F18 — P2 — Unauthenticated packets reset peer failure state

**Confirmed.** `K/mesh/GossipService.kt:390–393` looks up `packet.senderId` and calls `recordSendSuccess` before TTL, dedup, firewall, or signature validation. This remains despite an older report noting that an equivalent DM-handler reset had been removed.

**Fix:** update liveness only after authentication tied to the relevant peer/route. A relayed author's signature alone is not proof that their own onion endpoint is reachable.

**Acceptance:** malformed, duplicate, forged, or expired packets claiming a known sender do not clear its cooldown.

### F19 — P1 — Android-generated Hub deployment script exposes identity material to permissive file modes

**Confirmed script behavior; host permissions determine exposure.** `K/net/SshDeployer.kt:237–252` includes private signing/encryption keys in `configB64`. The generated shell writes `hai/hub_config.json` at line 550 without `umask 077` or a restrictive mode; reset-identity Python writes private-key files at lines 371–379 the same way. The script explicitly runs `chmod 777 /var/lib/hainet` at line 556.

With a common 022 umask, new secret files are readable by other local users. The cleanup trap around lines 303 onward cleans helper scripts/environment but does not comprehensively remove secret config/reset files on every early failure.

**Fix:** set a restrictive umask before any secret file creation, use a private temporary directory, apply owner-only permissions, remove world-writable service storage, and clean secret artifacts on every exit. Deploy stable versioned server code and document which identity is transferred.

**Acceptance:** execute the generated script in a disposable multi-user host under umask 022; private files stay owner-only on both success and injected failure. The Hub server's own behavior was not reviewed.

### F20 — P2 — Room singleton creation and encryption migrations need stronger failure handling

**Confirmed.** `K/data/NoSlopDatabase.kt:216–228` checks `INSTANCE` before entering `synchronized`, but not again inside it. Concurrent first callers can sequentially construct multiple Room instances; later `closeInstance()` closes only the last recorded one.

Migration 13→14 calls `GroupMessageCrypto.decrypt`, which returns ciphertext on failure, then encrypts that return value as if it were plaintext (`NoSlopDatabase.kt:190–199`). A failed decryption can therefore be wrapped into a new ciphertext rather than stopping the migration. Both encryption migrations broadly catch exceptions, allowing the schema transition to finish after an incomplete data transformation.

**Fix:** recheck the singleton inside the lock; use explicit decrypt-success/failure results; make migration failure/retry behavior intentional and testable. Do not mark a cryptographic migration complete when protected rows remain unconverted.

**Acceptance:** simultaneous initialization returns one instance; wrong/missing group key never double-encrypts ciphertext as plaintext; failed migration preserves recoverable original data. Add 14→15 validation: the supplied migration test covers 13→14 only.

## 4. Additional correctness and hardening work

These are smaller than the release blockers above, but useful follow-ups:

| Area | Evidence / issue | Suggested action |
|---|---|---|
| Group invite verifier parity | `MeshPacketVerifier.kt:333–341` tries four candidate encodings, but returns `Signed(encCand, ...)` even when a different encoding matched. `verify()` then verifies that returned encoding. The handler accepts the actual matching variant. | Return the matched canonical bytes/encoding or one verification result; test all legacy variants and member-issued invites. |
| Backup export validation | `NoSlopViewModel.exportBackupToUri` passes mnemonic text straight to export; export does not compare it with the active mnemonic. Validation in one Settings dialog is not a service contract. | Define whether this is an identity mnemonic or an arbitrary backup password; normalize/validate or confirm consistently before creating the destination. |
| Media proxy lifecycle | `MediaProxyService.stop()` cancels a singleton `val scope`; `start()` never creates a replacement scope. No current stop call was found in main-source search. | Treat as latent restart bug; fix before wiring restart/stop to UI or service lifecycle. |
| Mesh listener lifecycle | `MeshTransport.startListening()` sets `isRunning = true`; failure clears `listening` but not `isRunning`. | Allow retry after bind failure, and own listener/inbound sockets through an explicit close lifecycle. |
| Main identity disclosure in sync | `SyncPacketHandler` chooses an effective/burnable sender for post batches but uses `localKeys.publicKeyB64` for comment/reaction batches. | Apply one recipient-specific identity policy to every response batch; assess linkability with burnable contacts. |
| Snapshot metadata loss | Sync mappings omit `mediaSize` and recreate metadata with size/chunk count 0, in addition to the audience omission in F01. | Separate genuinely unknown metadata from accidental loss; round-trip test the full persisted post model. |
| Embed preference exception | `VideoPlayer.kt:344–350` still permits YouTube/Vimeo embeds when `enableWebViewEmbeds` is false. | Make the setting's label and implementation agree; test the off state across providers. |
| Secret hygiene | A previously exposed proxy credential remains in older reports/status/archive documents. The current build default is blank, and `scripts/test_yt.sh` no longer contains that literal. | Do not copy the credential into new reports. Verify server-side revocation/rotation; removal from source cannot prove rotation. Treat APK-embedded credentials as extractable. |
| Database asset | `app/src/main/assets/noslop_db.db` is zero bytes; no `createFromAsset` use/reference was found. | Remove it after build/reference verification; it is not the live Room database. |

## 5. Duplicate, mock, and underdeveloped code assessment

### Mock code

The targeted review found **no basis to characterize the legacy app as predominantly mocked**. Network clients, Room persistence, Tor transport, crypto, media playback, and deployment are substantial implementations. `FakeDaos.kt`, MockK collaborators, and the group-key override are testing facilities, not evidence of fake production functionality. `HaiNetTab.kt` is a nine-line delegation to `HubSetupScreen`, not an unimplemented screen. The media proxy's placeholder metadata represents unknown download metadata, not fake media.

There are nonetheless misleading completeness signals. `BackupManagerTest.kt` has two tests that reconstruct AES operations without invoking the production backup manager. They do not validate restore ordering, confirmation handling, portability, or data preservation. `FeedRepositoryTest.kt` explicitly excludes `refreshFeeds` and `searchCustomFeed`, leaving the main refresh bugs outside its coverage. Test names and a static build badge must not be mistaken for verified end-to-end behavior.

### Consolidation priorities

| Priority | Consolidation | Why / boundary |
|---|---|---|
| 1 | Canonical, versioned packet signing/verification | Signing logic is repeated in repositories, `MeshPacketVerifier`, handlers, and sync. Centralize signed semantic fields and legacy policy; preserve handler authorization checks. |
| 1 | Shared post codec + visibility policy | Normal sync, inventory sync, POST and EDIT mappings already diverge. Move `canSharePost` out of a handler companion into a domain policy and make audience exhaustive. |
| 1 | Group authority/revision service | Invite, update, query, and sync must use the same trusted authority and membership rules, rather than accepting different notions of a signer. |
| 1 | Backup format/restore coordinator | Separate serialization, portable crypto state, DB snapshotting, archive validation, storage commit, and restart. Replace boolean/global-flag error signaling with explicit outcomes. |
| 2 | FeedSyncCoordinator + injectable fetchers | One owner for completion, cancellation, deduplication and retry; UI progress should observe it instead of controlling lifetime. |
| 2 | Media route/session ownership | Resolver/cache, preloader, active player, Coil and network policy need a shared route-generation/lifecycle contract. Keep provider-specific resolution separate from Compose controls. |
| 2 | RecipientIdentity and PeerHealth policies | Centralize which identity is exposed per recipient and what counts as verified reachability or delivery success. |
| 3 | Typed Hub endpoint/configuration | `NoSlopRepository` and `HubSyncWorker` parse human-readable deployment-status strings into routing decisions. Store endpoint, transport, credentials and status separately. |
| 3 | Provider result/error model | Keep provider implementations distinct, but share timeout/cancellation, proxy fallback, error reporting, and bounded retries. Avoid a generic abstraction that erases provider-specific requirements. |

Large-file hotspots:

| File | Lines |
|---|---:|
| `K/ui/NoSlopViewModel.kt` | 3,250 |
| `K/ui/UnifiedFeedTab.kt` | 2,468 |
| `K/data/NoSlopRepository.kt` | 2,239 |
| `K/ui/components/VideoPlayer.kt` | 2,119 |
| `K/ui/tabs/SettingsTab.kt` | 2,048 |
| `K/data/MeshSocialRepository.kt` | 1,850 |

The repository extractions are a useful start, but global `NoSlopApp.repository`, service singletons, direct Room lookups, UI calls from repository logic, and detached coroutine scopes still couple the layers. Prefer incremental extraction after behavior is covered; do not split files solely to reduce line counts.

Potential dead symbols with only declaration hits in a main-source text search include `HttpClientProvider.activeMediaClient`, `UnifiedFeedTab.firstPreloadDelayMs`, `NoSlopViewModel.sessionStartTimeMs`, and `triggerBackupPrompt`. Confirm with compiler/IDE references before removal. Do not classify `InvidiousApiClient` as unused: current source contains real search, stream fallback, channel metadata, and mesh-announcement callers.

## 6. Build, documentation, localization, and test gaps

- Root settings include only `:app`; the legacy review/build can remain isolated from the excluded project.
- `docs/BUILD.md` says JDK 17 while `gradle/gradle-daemon-jvm.properties` selects JetBrains JDK 21. Document the daemon requirement separately from source bytecode target 11. Verify the pinned Gradle/AGP/Kotlin/KSP combination in a clean environment rather than guessing compatibility.
- The build guide gives `app/build/outputs/apk/debug/app-debug.apk`, but the build defines `play` and `github` flavors and changes the archive name. Document real flavor-specific output from a successful build.
- The build guide's GitHub PAT submission instructions are stale relative to the current build fields and should be reconciled with `ReportIssueScreen`. Do not revive shipping a privileged personal token just to match old documentation.
- Dependencies are partly version-catalog managed and partly inline. Consolidate declarations when deliberately upgrading, with release/R8 and native-library smoke tests. Age alone is not evidence of a vulnerability.
- No CI workflow YAML was found in the supplied legacy/root tree. A ZIP may omit infrastructure outside the repository; this review cannot establish external CI status. Replace static success claims with evidence from an actual build gate.
- Only schemas 13–15 are supplied, though migrations begin at 1. Early migration 1→2 references table `meshPost` while later schemas use `mesh_posts`; without the old schema this is a compatibility question, not a proven upgrade failure. Obtain actual supported old-version databases and test each upgrade path.
- Localization scan found 58 distinct simple non-interpolated `"...".tr` literals absent from English. This is a regex-based lower-bound check, not a complete Kotlin parser. English and Hungarian have 779 JSON keys each; the other 19 language files have 790. Do not repeat the old claim that every non-English file has 790. Add extraction/key parity/format-placeholder validation to CI.
- Comments frequently retain historical claims that no longer describe the code: group legacy-cipher retirement was scheduled for migration 14→15, but that migration only adds `bannedMembersJson`; preloader capacity commentary disagrees with `MAX_PRELOAD = 4`; network DNS commentary disagrees with its fallback branch. Replace historical fix essays with concise current invariants and linked change history.

Highest-value tests are in sections F01–F20. Supplement them with a device matrix covering cold launch, background/foreground, network loss, foreground-service toggling, rapid video swipes, decoder/resource pressure, low-storage restore, and release minification. Count-based test inventory is not coverage.

## 7. Suggested execution order for developing LLMs

1. **Establish the baseline.** Build both flavors and run existing tests/lint in an Android-capable environment. Record exact toolchain, commands and failures. Do not claim passing tests from this report.
2. **Block privacy/authorization regressions.** Add focused failing tests for F01–F06, then repair sync mapping, signed-field coverage, group authority, self-removal, and ownership/tombstone rules. Treat wire-format changes as a compatibility rollout, not a silent signature edit.
3. **Repair feed synchronization.** Address F07–F09 together with injected fetchers/clock/network readiness. This is a bounded reliability change with immediate user benefit.
4. **Make recovery trustworthy.** Address F10–F14 and F20. Define a new portable backup schema with an authenticated manifest, stage/validate/commit restore, and prove it on a fresh installation. Retain safe legacy migration and rollback behavior.
5. **Close network and deployment gaps.** Address F15–F19; test routing transitions with traffic capture and Hub file permissions in a disposable host. Keep server operations outside source-only review assumptions.
6. **Consolidate behind tested contracts.** Extract codecs, group authority, feed coordination and media-session ownership; then smaller UI/repository modules. Avoid mixing large moves and protocol changes in one patch.
7. **Reconcile documentation and release gates.** Update build instructions, current architecture/security limitations, localization checks and deprecation policy. Preserve useful historical reports but make the current status unambiguous.

Proposed verification commands, to be run where the Android toolchain and dependencies are available:

```bash
./gradlew :app:assembleGithubDebug :app:assemblePlayDebug
./gradlew :app:testGithubDebugUnitTest :app:testPlayDebugUnitTest
./gradlew :app:lintGithubDebug :app:lintPlayDebug
./gradlew :app:connectedGithubDebugAndroidTest
./gradlew :app:assembleGithubRelease :app:assemblePlayRelease
```

Release signing is optional in this build configuration; absent signing properties, release artifacts are unsigned. A successful release assembly alone does not constitute installation/playback verification.

## 8. Findings in older reports that should not be reopened blindly

Rechecked against the attached snapshot:

- Untrusted directed DMs now have a separate `dmRateLimits` map and a global cap; the previous shared-announcement-bucket finding is resolved.
- `SyncPacketHandler.canSharePost` is now reused by `MeshSocialRepository.requestInventorySync`; the previous predicate duplication has been reduced. The serialization bug in F01 remains independent of that improvement.
- Sync post-cache lookups now use `containsKey`, so cached missing posts are not repeatedly fetched through nullable `getOrPut`.
- Media transfers now bypass transport cooldown.
- Tor readiness waiting in `MeshTransport.sendPacket` occurs before semaphore acquisition; do not repeat the old claim that this wait occupies a send slot.
- `GossipService.getTargetPostAuthor` is gone.
- The old proxy secret is absent from `scripts/test_yt.sh` and the build default is empty. Server-side rotation remains unverified, and historical documents still contain it.
- Media-ID validation, loopback token protection, authenticated archive decryption before extraction, and signature enforcement are real protections. The new findings concern gaps around those protections, not their absence.

## 9. Handoff rules

- This deliverable is a review only; application source was not modified.
- Prioritize verified data-flow defects over grep counts, TODO counts, or broad dependency upgrades.
- Every privacy change must cover live delivery, relay, sync, persistence and restart—not just the screen where the setting is chosen.
- A valid signature establishes only the authenticity of the bytes actually signed. Separately prove authorization against trusted stored state.
- Do not silently drop legacy compatibility, destroy unreadable identity stores, or re-enable unsafe fallback paths to make tests pass.
- Mark fixes complete only with the named regression scenarios and build/runtime evidence. Reassess existing potentially affected user data as part of remediation.
