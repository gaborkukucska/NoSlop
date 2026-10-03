# NoSlop legacy Android — external review, round 5

Reviewed: 2026-10-03, Australia/Perth  
Input: `NoSlop-main(4).zip`  
Snapshot from ZIP comment: `a75088b57a1c32f0d2b57abbc5466e85a90b9ec8`  
Archive SHA-256: `f6b363e65c62883898f4fa9baa39f2c9e20e142007e577e6be01737135ed8c9a`  
Comparison: `NoSlop-main(3).zip`, snapshot `9550c238be8b0e15f35161ec91adb39b968c1999`  
Audience: developing LLMs and maintainers

## 1. Overall opinion

**This update makes useful progress, but it is not yet a dependable release baseline.** Several direct defects from the previous review are fixed. However, changes to signature construction introduced reproducible inconsistencies between senders, receivers, and persisted posts. Group authorization, backup commit safety, feed-job ownership, and live routing transitions remain incomplete.

The most important observation is that **18 production Kotlin files changed, while every supplied unit and instrumentation test file remained byte-for-byte unchanged**. The changes include wire-signature formats, backup restoration, identity failure handling, concurrency, and local HTTP serving. These need tests of actual production call paths before the findings can be considered closed.

The legacy app is substantial real software, not a collection of mock features. Continue stabilizing it incrementally. Keep the migration paused as requested; no MVP work is needed to address this report. The highest-value next step is a small set of integrated protocol and restore tests, followed by fixes behind those tests—not another broad batch of independent edits labelled with finding numbers.

## 2. Scope and verification limits

MVP implementation and migration work were excluded. Reviewed every changed legacy production-file diff, then traced the relevant surrounding code and unchanged callers, DAOs, packet models, tests, and client ownership. The Android-generated Hub deployment script is in scope; the separate Hub server is not.

There are 116 main Kotlin files containing 50,485 lines including comments and whitespace, and 20 Kotlin test-tree files containing 125 `@Test` annotations. Compared with the previous archive, the non-MVP tree has 18 changed files and one added report, `docs/EXTERNAL-REPORT-4.md`. No non-MVP test, schema, build configuration, or operational documentation changes were found. No `AGENTS.md` or `start_HERE.txt` was present.

Attempted the real Gradle test/lint command again:

```bash
GRADLE_USER_HOME=/tmp/noslop-review-gradle bash gradlew \
  :app:testGithubDebugUnitTest :app:lintGithubDebug --offline
```

The wrapper again needed the uncached Gradle 9.6.0 distribution. Download failed with `java.net.SocketException: Operation not permitted`. **No compilation, Android tests, lint results, or device/network captures were obtained.** This does not establish a build failure in the repository itself. Statements below describe source-level defects and risks, not executed Android test results.

`K/` below abbreviates `app/src/main/java/com/noslop/app/`. References are to this snapshot. P0 means a privacy/authorization release blocker; P1 means urgent functional or data-preservation work; P2 means hardening/maintenance. “Confirmed” means the inspected source establishes the defect. Timing-dependent consequences are explicitly labelled as risks.

## 3. Previous finding status

“Fixed in source” is deliberately narrower than “verified on a device.”

| Previous ID | Current status | Assessment |
|---|---|---|
| F01 — sync loses friends-only label | Fixed in source | Both outgoing sync paths share `toPostPayload`; receive mapping persists privacy and media size. Existing incorrectly labelled data is not repaired. |
| F02 — unsigned post fields | Partial; new regression | Audience/media ID/clearnet URL added to POST signing. Metadata remains unsigned; EDIT signatures cannot round-trip through sync. R01, R05. |
| F03 — unsigned group mutations | Still unsafe | New canonical fields are accepted, but unrestricted old signatures remain accepted. Sender/receiver construction also diverges. R02–R04. |
| F04 — group-sync authority | Partial | Arbitrary outsider keys are no longer accepted for an existing group. Ordinary stored members still get admin-like metadata powers. R06. |
| F05 — removing another member during self-removal | Fixed in source | `removed.all { it == signer }` closes the unrelated-second-member loophole. The leave sender now has a signature regression, R02. |
| F06 — post overwrite/replay | Partial | Sequential ownership/timestamp/tombstone guards added; not transactional. R07. |
| F07 — permanently stuck feed flag | Original early-return paths fixed | Timeout/empty-source/setup-error paths now clear the flag. New ownership races remain, R08. |
| F08 — skipped creator keywords | Fixed in source | Existing feeds now fetch all shuffled creator keywords. |
| F09 — detached background refresh | Partial | A shared scope/job and `awaitCompletion` were added, but the worker still does not own the job or its outcome. R08. |
| F10 — group-history backup portability | Partial | Logical decrypted messages are exported and re-encrypted on restore; completeness/failure handling and memory use remain unsafe. R11. |
| F11 — raw XML overwrites portable keys | Main overwrite fixed | Portable JSON wins over raw XML. Legacy detection and replacing an existing identity remain problematic. R10, R12. |
| F12 — legacy confirmation swallowed | Fixed in source | Dedicated exception is rethrown and can reach the ViewModel callback. |
| F13 — non-atomic backup/restore | Partial | Staging added and premature ViewModel DB close removed. Validation/commit/snapshot invariants still fail. R09. |
| F14 — destructive identity recovery | Partial | Quarantine replaces deletion; fallback encryption errors no longer return plaintext. No real recovery state exists. R13. |
| F15 — Tor transition leaves direct clients | Still open | Cache/preload invalidation and an effect key were added, but active playback and Coil are not revoked. R14. |
| F16 — unbounded proxy headers | Partially fixed | 16 KB cap and idle read timeout added; connection admission/deadline remain incomplete. New Range branch is unreachable for normal headers. R15–R16. |
| F17 — PrintWriter hides write failure | Fixed in source | Error-propagating UTF-8 OutputStream writes replace PrintWriter. Delivery acknowledgement remains a separate concept. |
| F18 — unauthenticated liveness reset | Still open | Reset moved after a gate that also accepts `UNVERIFIABLE`; not equivalent to authentication. R17. |
| F19 — Hub secret-file permissions | Partial | Restrictive umask and directory mode added; existing-file modes and relative cleanup still need work. R18. |
| F20 — singleton/migration failures | Singleton fixed; migration partial | Inner singleton recheck is correct; failed decrypt is no longer re-encrypted as plaintext. Failed rows are skipped while schema advances, without a retry mechanism. |

Additional wins: group-invite pre-verifier now returns the actual matching signature encoding; the explicit YouTube/Vimeo exception to the embed-disabled setting was removed; proxy scope can now be recreated after stop; listener failure clears its running flag. These improvements should be retained.

## 4. Current urgent findings and regressions

### R01 — P1 — Edited posts cannot be verified after synchronization

**Confirmed new regression.** `K/data/MeshSocialRepository.kt:525–558` signs an edit with seven fields:

```text
id, author, newContent, timestamp, avatar, privacy, newMediaId
```

That signature replaces the row's `signature`. `K/mesh/SyncPacketHandler.kt:51–77` later serializes that row as a POST; its verifier at lines 327–347 expects eight canonical fields, including `clearnetUrl`, or the old four/five-field formats. The seven-field edit signature matches none of them. A null eighth field still adds `0:` under `CryptoService.encodeForSigning`; it is not omitted.

There are further persistence mismatches: editing without new media signs a null media ID but retains the old media URL; `PostDao.updatePostDetails` does not update `authorAvatarB64`, although the edit signs the current avatar. Merely adding an eighth argument will not fix these cases.

**Fix:** decide whether the database stores a signed complete post state or a signed edit operation. Preserve the exact signed state/version and use one codec for send, live receive, persistence, and sync. Do not solve this by bypassing sync verification.

**Acceptance:** create → edit → persist → restart → inventory/ordinary sync → verify, for text-only, retained/new media, clearnet share, changed avatar, and friends-only posts.

### R02 — P1 — New leave and member-update signatures do not match the transmitted payload

**Confirmed new regression.** `K/data/NoSlopRepository.kt:1308–1326` signs `existing.allowMemberInvites` and `existing.allowMemberSelfRemove` when leaving a group, then omits both fields from `GroupUpdatePayload`. The receiver at `K/mesh/HandshakePacketHandler.kt:700–721` reconstructs omitted permissions as empty strings. Boolean strings such as `true` or `false` cannot equal an empty string. The old-format verification alternatives also cannot validate this new-format signature.

Consequently, peers reject the leave packet, while the sender deletes the group locally at `NoSlopRepository.kt:1345–1349`. Other members can retain the departed identity in their group.

`updateGroupChat` has the same problem for non-admin updates: lines 1113–1116 sign existing metadata/permissions, but lines 1137–1148 omit those fields for non-admin senders. The permission mismatch alone is sufficient for rejection.

**Fix:** construct the exact wire operation first, then sign its canonical representation. Share that function with the receiver. Define whether omitted fields mean “unchanged” and encode that consistently without borrowing receiver-local state.

**Acceptance:** a member leaves, and a member invites someone when permitted; both are accepted by another device using the real sender and handler. Confirm denied operations still fail.

### R03 — P1 — Adding a group member reuses an UPDATE signature for an INVITE

**Confirmed new regression.** In `K/data/NoSlopRepository.kt:1113–1118`, `signature` covers the expanded group-update fields. At lines 1177–1189, the invite sent to newly added members reuses that signature.

The invite verifier (`MeshPacketVerifier.kt`, `GROUP_INVITE` branch; `HandshakePacketHandler.kt:726 onward`) expects an invite's member list and permissions, or the earlier narrow format—not the update's added/removed/banned fields. New-member invitations generated by this path therefore fail verification. Creation and explicit resend use separate invite signing, so a create-only test will miss it.

**Fix:** sign each final invite payload independently with the invite codec and operation domain. Share a single invite builder across create, add-member, reconnect, and resend paths.

**Acceptance:** create a group, add a previously absent member through Settings, then exercise direct and relayed receipt. The member must get an authentic invitation without requiring a separate manual resend.

### R04 — P0 — Legacy group signatures still authorize unsigned mutations

**Confirmed unresolved security defect.** `K/mesh/HandshakePacketHandler.kt:717–721` still accepts the old group ID/title/signer/timestamp encoding and pipe variant for every group update. Those old signatures do not bind added/removed/banned members, metadata, or permissions. The handler then applies those fields as before.

The new canonical branch therefore does not close the previous vulnerability: a captured legitimate old admin signature can still be reused with changed mutation fields. Invites also retain unrestricted old formats. This is not only historical interoperability: the reconnect invite builder in `HandshakePacketHandler.kt:497` still emits the narrow signature.

Even new group signatures omit peer-detail maps; group sync signs `groupChatJson` but not the separate `memberDetails` that is applied. Review encryption-key/onion metadata as part of the authenticated authority contract.

**Fix:** introduce explicit protocol versions and domain-separated codecs, with a deliberate restricted old-format policy. Do not interpret unsigned old fields as authorized changes. Update all emitters in one change and document mixed-version behavior. Verify metadata keys through an authenticated identity mechanism before updating peer state.

**Acceptance:** take an old valid admin update/invite and change each unsigned field; the modified operation must not mutate state. Test reconnect emitters, not just the main repository builders.

### R05 — P0 — Post hardening leaves downgrade and attachment gaps

**Confirmed.** The new `isLegacySafe` guard in `PostPacketHandler.kt:51`, `MeshPacketVerifier` and `SyncPacketHandler.kt:340` decides whether a legacy signature is safe using the incoming, unsigned values of `privacy`, `mediaId`, and `clearnetUrl`.

A legacy friends-only text post can be relabelled `public` with null attachment IDs, making this guard true while preserving its valid old signature. This does not authenticate the original audience. Old friends/media posts left unchanged are now rejected, creating a separate compatibility problem. There is no protocol-version field, migration of stored signatures, or updated wire documentation in this archive.

For new-format posts, `mediaMetadata` still lies outside the signed string. A packet can also retain `mediaId == null` while carrying non-null `mediaMetadata`; the legacy guard allows that combination and `PostPacketHandler.kt:163–170` passes the metadata to automatic downloading. This does not imply unrestricted file access—MediaManager has its own checks—but it does mean the signature does not authenticate the attachment action.

**Fix:** bind semantic metadata and audience in a versioned representation; enforce consistent media ID/metadata relationships; define an explicit trust policy for pre-upgrade records. Do not infer authenticated audience from old unsigned fields. Plan how legitimate historical content is retained or reissued without widening trust.

**Acceptance:** old friends→public mutation, old attachment removal, null-ID/metadata injection, and new metadata tampering are tested alongside supported historical-content behavior.

### R06 — P0 — GROUP_SYNC still lets a member apply admin-only metadata and membership changes

**Confirmed partial fix.** `K/mesh/HandshakePacketHandler.kt:1053–1067` now verifies against stored admin/member keys for known groups, closing the simple outsider-key attack. However, `signerIsAdmin` is computed at lines 1093–1094 and never used. The later branch checks whether the **recipient** is admin, not whether the **signer** is admin.

An ordinary stored member can therefore sign a snapshot that changes title, description, avatar, or permissions on other non-admin recipients. Incoming members are unioned into stored members regardless of the invite permission. Banned keys are now filtered, but removed non-banned members can still be resurrected by stale sync. There is no authoritative revision/order check.

For unknown groups, trusting the incoming admin's self-signature and automatically inserting the group is still not an invitation-consent model. Keep that separate from the known-group exploit above.

**Fix:** preserve the verified signer identity, authorize fields against stored authority, and distinguish authoritative admin snapshots from limited member hints. Use revisions/removal records rather than unconditional membership union. Apply peer details only after their authority is established.

**Acceptance:** ordinary member sync cannot change admin-only state or add members when disabled; stale snapshots cannot resurrect removed members; an admin revision is applied consistently across recipients.

### R07 — P1 — Post guards are labelled transactional but are not atomic

**Confirmed concurrency gap; impact needs an interleaving test.** `K/mesh/SyncPacketHandler.kt:356–391` and `PostPacketHandler.kt:79–119` read a row, validate it, then separately call `insertPost`. No enclosing Room transaction or conditional DAO mutation was added. `Daos.kt:138–139` still uses REPLACE.

A deletion can land between the check and insert and be overwritten; two concurrent first inserts with the same ID can both observe no existing owner. Similarly, timestamp ordering checks can be invalidated by another operation before the write. The sequential checks are improvements, but the new “Transactional checks” comment overstates them.

**Fix:** one transactional/conditional persistence operation shared by live POST, EDIT, DELETE, and sync. Keep tombstones and author ownership durable.

**Acceptance:** coordinate concurrent first insertion, deletion-versus-update, and newer-versus-older sync with deterministic barriers; the invariant must survive every interleaving.

### R08 — P1 — Feed job ownership still races, and worker results can be false successes

**Confirmed source-level lifecycle flaws.** `K/data/FeedRepository.kt:62–70,210–240,310–381` separates an atomic running flag from a non-atomic job reference and an app-owned scope.

Specific cases:

1. During setup/ramp-up, the flag is true but `activeSyncJob` is null. A worker calling with `awaitCompletion = true` joins nothing and returns before synchronization completes.
2. Cancelling a caller waiting on `bgJob.join()` reaches the outer catch, clears the flag/reference, but does not cancel the job launched in `syncScope`. A later refresh can overlap it.
3. `cancelSync()` clears state without waiting for completion. An older job's `finally` can later clear the state of a replacement job. Cancelling during setup cannot cancel that setup because it is not represented by `activeSyncJob` yet.
4. `join()` observes completion, not a typed success outcome. Setup errors are logged and swallowed; network-not-ready returns normally. `FeedSyncWorker` then reports success. Inner fetch helpers still swallow broad exceptions, including cancellation, even though new outer catches rethrow cancellation.

**Fix:** represent the entire pass, including setup, as one synchronized/coalesced task with a typed result. Distinguish observing a shared task from owning it. Clear state only if it still belongs to that task; cancellation must have defined ownership. Propagate retryable outcomes to WorkManager.

**Acceptance:** a second waiter during setup waits; cancellation does not leave untracked work; disable/re-enable cannot overlap passes; a Tor timeout or setup error produces the intended retry outcome. Retain the now-correct creator split.

### R09 — P1 — Restore staging still commits before validating the whole backup

**Confirmed.** `K/data/BackupManager.kt:494–515` runs `PRAGMA quick_check` but only calls `moveToFirst`; it does not inspect whether the result is `ok`. Database corruption reported as a result row rather than an exception will not be rejected by that check.

The live DB is overwritten before identity JSON is parsed at lines 519–522, before required fields are checked, before API JSON is parsed, and before group-message re-encryption succeeds. Missing required archive entries are not rejected. There is no rollback for a partial commit or disk-full failure; `copyTo(overwrite = true)` is not an atomic multi-file restore. Writers/workers are not paused and their existing DAO references are not rebuilt on failure.

Export now logs checkpoint result columns, but still proceeds when checkpointing is busy/fails and does not hold a consistent snapshot across DB copy, identity export, and group-message enumeration. A later logical group snapshot can disagree with the earlier database copy.

**Fix:** validate an authenticated manifest, required entries, complete JSON schemas, DB integrity/schema compatibility, identity consistency, and size limits before touching live state. Use a consistent export snapshot and a controlled commit/rollback lifecycle. Treat checkpoint failure as an actionable result, not merely a log line.

**Acceptance:** corrupt DB with a non-`ok` result, valid DB plus malformed identity, empty/missing-entry archive, mid-commit disk-full failure, and concurrent export writes leave either the complete old state or complete validated new state.

### R10 — P1 — Legacy cross-device identity recovery is now unreachable

**Confirmed new regression.** `K/data/BackupManager.kt:432–433` initializes `restoredKeystoreSealedIdentity` and `restoredFallbackIdentity` false. In the portable branch, the former is set true, but legacy raw preference copies at lines 564–574 set neither flag.

The later recovery condition at line 637 requires both `!hasPortableIdentity` and `restoredKeystoreSealedIdentity`. The only assignment to the latter is in the opposite portable branch. The automatic legacy cross-device recovery code can therefore never execute through this flow. Import may return true after copying preferences the new device cannot decrypt.

**Fix:** replace these loosely coupled booleans with explicit parsed archive/identity states. Restore old archives through a dedicated validated migration, preserving the distinction between deterministic and non-derivable identities. Report recovery-required status accurately.

**Acceptance:** fresh-device restore of an archive with only device-bound identity preferences either safely recovers the correct identity or explicitly requires recovery; it must not report an ordinary complete restore with an unusable identity.

### R11 — P1 — Portable group-message backup can silently omit history and exhaust memory

**Confirmed design defects; resource impact depends on history size.** `K/data/BackupManager.kt:170–214` collects all decrypted group messages in a `JSONArray`, then creates a whole JSON string. Restore reads the entire JSON and parses it into another array. This introduces memory growth proportional to all chat plaintext despite the outer backup being streamed.

Rows for which `decryptOrNull` returns null are silently omitted. Export exceptions only warn. Restore re-encryption failures at lines 611–613 only log; import still returns true. Only `UPDATE ... WHERE id = ?` is used, so a message included in logical JSON but absent from the earlier raw DB snapshot is not inserted, and no affected-row count is checked.

**Fix:** stream logical records from the same stable snapshot as the database; verify counts and identities; fail or explicitly report incomplete export/restore. Re-encrypt in staging/a controlled transaction, and verify every expected row before success. Clean plaintext temporary files on all exceptions; the new fixed-name group JSON file currently has no local `finally` cleanup.

**Acceptance:** large history remains within a bounded memory budget; unreadable source rows and destination-key failures are visible; concurrent new messages cannot be silently discarded; every restored body decrypts after restart on a fresh installation.

### R12 — P1 — Restoring over an existing identity can retain destination-only secrets

**Risk established by preference lifecycle.** `K/data/BackupManager.kt:524–560` deletes a backing preference file, recreates an encrypted wrapper with the same name, and edits keys without clearing the preference store. Android SharedPreferences instances may already be cached in-process. When the backup contains no burnable identity, no `.remove()` calls clear existing burnable keys. API-key restoration sets only keys present in the archive.

The raw XML overlay bug is fixed, but deleting the file is not a safe replacement contract for an already-open preference store. A destination's old burnable identity/API keys may survive or combine with the incoming main identity. Writes still use asynchronous `apply()` before a delayed process kill rather than an explicit verified commit result.

**Fix:** define full replacement versus merge semantics; use an isolated validated destination store or explicit clear-and-commit under the restore coordinator. Validate exact main/burnable key identity and deliberately handle absent keys. Do not rely on file deletion to invalidate caches.

**Acceptance:** restore a backup with no burnable identity/API key over a destination that has both; after restart only the intended backup state remains. Also test commit failure and private/public-key mismatch.

### R13 — P1 — Quarantining identity data does not provide a recovery path

**Confirmed incomplete implementation.** `K/data/IdentityRepository.kt:49–65` renames the secure file, ignores `renameTo`'s boolean result, and switches to fallback preferences. `recovered` is initialized null and never assigned, making its success branch dead code. No code in this change discovers or restores the quarantine file or enters a dedicated recovery state.

Preserving bytes is better than deleting them, but a transient secure-store error can still strand the user in a separate empty/stale store. The fallback-secret design is unchanged. New fail-closed encryption/decryption behavior is a real improvement and should stay.

**Fix:** distinguish transient unavailability, corruption and unsupported storage; preserve the authoritative identity; present explicit recovery/retry without silently changing identity stores. Check file-operation results and validate private/public keys before loading an identity.

**Acceptance:** a transient secure-store failure followed by restart/retry recovers the same identity; no new identity or stale fallback is silently substituted; quarantined data remains reachable by the recovery flow.

### R14 — P1 — Route changes still leave active direct playback and image clients alive

**Confirmed control-flow gap; traffic leak requires device verification.** `K/ui/components/VideoPlayer.kt:120–124` clears source/preload caches. The new `isTorRouting` effect key at line 662 is followed by the existing early return when `retryTrigger == 0 && isVideoReady && source != null`. That is the normal stable-playback case, so the effect can run and immediately leave the direct player untouched.

Claimed players have been removed from PreloadManager's cache; evicting preloads does not release the active player. The app's Coil loader construction remains unchanged and can retain a client created under the earlier routing mode. Existing direct embeds and in-flight calls are not comprehensively revoked.

**Fix:** a route-generation/session owner must release incompatible active players/embeds, cancel calls, rebuild affected image clients, and invalidate caches before resuming. Do not use the stable-playback early return across a routing change.

**Acceptance:** start direct video, embed and image loads; enable Tor and capture traffic. No subsequent external direct requests occur under the new policy. Test toggling back and startup hydration too.

### R15 — P1 — Normal Range headers never activate the new range-serving branch

**Confirmed new bug.** `K/mesh/MediaProxyService.kt:169–170` passes the full header line, for example `Range: bytes=100-199`, into `streamFile`. At line 305, `streamFile` checks whether that string starts with `bytes=`. It does not, so normal requests always receive the full 200 response while the server now advertises `Accept-Ranges: bytes`.

After fixing that interface mismatch, the parser still needs defined behavior for suffix ranges, malformed/multiple ranges and unsatisfiable offsets. Dynamic incomplete-file streaming still advertises no ranges.

**Fix:** parse header name/value once; implement and test the supported single-range forms and correct status/length handling. Advertise only the capability actually provided for that resource.

**Acceptance:** `bytes=0-99`, `bytes=100-`, suffix range, EOF/out-of-bounds, empty file and invalid input. Check status, Content-Range, length and exact bytes, not just playback appearance.

### R16 — P2 — Proxy connection bound and “deadline” remain incomplete

**Confirmed.** `MediaProxyService.kt:91–98` performs separate `get()` and `incrementAndGet()` operations. Concurrent handlers can all observe capacity and exceed the limit. Admission also happens after launching handler coroutines rather than before scheduling work.

`soTimeout = 5000` is an idle read timeout, not a five-second total header deadline. A client can send a byte more frequently than every five seconds and retain a slot much longer. `readHttpHeaders` catches timeout and returns whatever partial text was read instead of requiring a complete CRLF-CRLF terminator. Header memory is now capped at 16 KB, so the original unbounded-allocation issue is improved.

**Fix:** atomic admission before handler scheduling, total header deadline, complete-header validation, and owned socket closure on stop. Review MeshTransport's analogous admission and listener lifecycle races at the same time.

**Acceptance:** concurrent and slow-byte clients never exceed the configured active-handler count or header deadline; stopping/restarting releases accepted sockets and leaves one listener.

### R17 — P2 — Moving liveness reset did not make it authenticated

**Confirmed.** `K/mesh/GossipService.kt:604–626` only rejects `INVALID` verdicts under enforcement. `UNVERIFIABLE` still reaches `recordSendSuccess`, including packets whose actual authentication happens later in a handler. A bogus directed MESSAGE claiming a known sender can therefore reset that peer's cooldown before AEAD verification.

Valid relayed content also does not demonstrate that the claimed author's own onion endpoint is reachable. The new comment “survived authentication” overstates this gate.

**Fix:** distinguish transport endpoint reachability, authenticated content origin, and successfully decrypted local messages. Update each health signal only from the corresponding evidence; preserve rate/dedup protections without treating unverified packets as authenticated.

**Acceptance:** invalid AEAD, malformed/unverifiable payloads, and relayed signatures do not clear unrelated endpoint failures.

### R18 — P2 — Hub secret cleanup still depends on current working directory

**Confirmed residual hardening gap.** `K/net/SshDeployer.kt:306–313` adds cleanup for `hai/hub_config.json` as a relative path. The deployment later executes `cd hai` at line 567. An early exit from there makes the trap target `hai/hai/hub_config.json`, leaving the actual secret file. The normal explicit removal at line 585 is helpful but not an all-exit guarantee.

The new `umask 077` protects newly created files; it does not tighten an existing permissively-modeed `hub_config.json` or private-key file opened for truncation. This matters for retries/upgrades from earlier deployments. `/var/lib/hainet` is now correctly restricted to 700.

**Fix:** use an absolute private deployment directory and explicit owner-only modes for every secret file; use those absolute paths in the trap. Test failures after directory changes and reusing files from an old installation.

**Acceptance:** umask 022 host, existing 0644 secret file, and injected failure inside the repository leave no readable residual secret artifacts.

## 5. Code quality, duplication and unfinished work

No new mock production subsystem appeared in this update. The main issue is incomplete integration between real components. In particular, a comment containing `Fxx` is not evidence that the complete acceptance criterion was met.

The remaining highest-value consolidation is now very specific:

| Consolidation | Immediate reason |
|---|---|
| One versioned codec per signed operation/state | R01–R05 are caused by constructing nearly identical signing strings independently. Build the packet first, sign exactly its canonical semantics, and reuse the same codec on receipt and sync. |
| One group-authority policy | Invite, update and sync still confer different authority on admin/member signatures. Keep verified signer identity instead of a boolean “any signature passed.” |
| One atomic post mutation boundary | Read/check/REPLACE scattered across handlers cannot enforce concurrent ownership, revision and tombstone invariants. |
| One feed-task owner and result type | The flag, job, setup coroutine and worker currently have different lifetimes. |
| One backup transaction coordinator | Parsing, key migration, database replacement, preferences, workers and process restart must share a completion/failure contract. |
| One media routing-session owner | Clearing caches is not equivalent to shutting down active direct network resources. |

New dead or misleading remnants worth removing when the behavior is corrected:

- `signerIsAdmin` is computed but unused in group sync.
- `IdentityRepository.recovered` has no assignment after initialization.
- Restore's legacy identity flags cannot reach the recovery branch.
- “Transactional checks,” “5-second deadline,” and “survived authentication” comments claim stronger properties than the implementation provides.

The large ViewModel/repositories/player/UI files remain. Splitting them is secondary to these contracts. Preserve the existing extracted packet handlers and repositories; move shared policies into small testable units instead of creating another broad utility singleton.

Unchanged carry-over work remains valid: typed Hub endpoint configuration instead of parsing display strings; recipient-specific sender identity for all sync batch types; backup-password validation at the service boundary; bounded archive entries/expanded size; early-schema upgrade fixtures; localization key/placeholder checks; build-guide/toolchain reconciliation; dependency declaration consolidation; and removal of verified unused symbols/assets. Do not treat those as newly introduced regressions.

## 6. Verification required before another “fixed” pass

The unchanged tests are the largest process gap in this round. Existing verifier tests mainly exercise legacy public text signatures. BackupManagerTest reconstructs cipher operations rather than executing the production import/export lifecycle. Those tests can stay green while the current integration defects remain.

Recommended order:

1. **Repair protocol regressions first:** create/edit/sync post round-trip; group create/add-member/leave/member-update/reconnect round-trip using real builders and verifiers. These should expose R01–R03 before any broader refactor.
2. **Close authorization gaps:** old-format mutation tests, metadata tampering, member-versus-admin sync, removal/bans, and mixed-version policy. Do not remove verification to restore interoperability.
3. **Prove atomic persistence and task ownership:** deterministic interleaving tests for post mutations and feed setup/cancel/replacement/waiters; verify worker results reflect retry/failure.
4. **Prove restore on a fresh installation and over an existing account:** main/burnable identity, API keys, group history, malformed files, disk-full, missing entries, old formats, Keystore failure and restart. Require exact key identities and readable history, not just a true return value.
5. **Verify live networking/media:** route toggles with traffic capture; Range byte tests; bounded proxy connections/deadlines; restart/listener cleanup; foreground/background and decoder pressure.
6. **Run both flavors and a minified release:** compile, tests, lint, connected tests and actual device smoke tests. Then update current protocol/build/security documentation with evidence and remaining limits.

Suggested Android-toolchain commands:

```bash
./gradlew :app:assembleGithubDebug :app:assemblePlayDebug
./gradlew :app:testGithubDebugUnitTest :app:testPlayDebugUnitTest
./gradlew :app:lintGithubDebug :app:lintPlayDebug
./gradlew :app:connectedGithubDebugAndroidTest
./gradlew :app:assembleGithubRelease :app:assemblePlayRelease
```

These commands are proposed gates, not results obtained in this review. Release assembly can be unsigned when signing properties are absent; installation/runtime testing is separate.

## 7. Handoff decision

The app has improved at individual failure checks, but the current signature changes make some normal group and sync flows less reliable. **Do not close the previous security/recovery findings solely because corresponding code was added.** Close the now-correct narrow fixes in section 3, keep partial findings open, and prioritize the protocol round-trip regressions plus group authority and safe restore.

No application source, tests, build files or MVP files were modified during this review. This report is the deliverable; it intentionally does not claim that the app is perfect, that tests passed, or that unexamined code is defect-free.
