# NoSlop legacy Android — external review, round 9

Date: 2026-10-05  
Reviewed archive: `NoSlop-main(8).zip`  
ZIP commit: `184dd776abb149e074d4ecc78e59ff0c16d86916`  
SHA-256: `b075a3a998f6092acccd114fbfcb5facc536f4401429851ca5344858a829b641`  
Scope: legacy `app/` code, its tests and supporting documentation. MVP excluded. Compared with the previous supplied archive and the V01–V09 findings reproduced in `docs/EXTERNAL-REPORT-8.md`. No application files modified.

## Overall opinion

There is substantive progress: the X25519 consistency check now uses an independent keypair, EDIT_POST no longer accepts the unsafe legacy signature, directory encodings are structurally safer, and local post re-signing no longer rewrites whole rows from stale snapshots.

However, this is **not yet ready for a manual-testing-only phase**. Several fixes remain incomplete, and the new synchronous cancellation introduces a deterministic deadlock. Group update signatures still have paths that authorize different state transitions with the same signature. Restore rollback still cannot justify the documentation's atomicity guarantees.

The main problem is no longer obvious mock functionality. It is inconsistent state-transition rules spread across emitters, verifiers, handlers, persistence, and recovery. The next iteration should prioritize a few complete fixes with handler-level and failure-injection tests over another broad cleanup pass.

## Evidence and limits

This is a source and change review, not a claim that every legacy file or runtime scenario has been exhaustively verified. Findings below identify concrete code paths and regression cases. Line references are relative to this exact archive; Java/Kotlin paths start at `app/src/main/java/com/noslop/app/` unless otherwise stated.

I attempted `bash ./gradlew --stacktrace testGithubDebugUnitTest`. The wrapper could not download Gradle 9.6.0 because networking to the distribution host was unavailable. No compilation or tests ran. This is **not an application build failure**, and earlier successful local Gradle runs do not establish results for this revision. Android/device behavior, Keystore faults, Room concurrency, and process death require execution outside this environment.

## Previous findings: disposition

| Previous finding | Current assessment |
|---|---|
| V01: ineffective X25519 self-probe | Closed at source level: independent ephemeral DH check plus derived tripcode/onion validation now used for main and burnable identities. New mismatched-key test is useful; execution unverified. |
| V02: incomplete restore rollback | Partially fixed: close-before-copy, media snapshots and absent-DB tracking added. W05 remains. |
| V03: directory encoding/authentication | Delimiter ambiguity and current UPDATE/SYNC directory signing fixed; legacy invite handles gate fixed. UPDATE fallback and presence semantics remain: W02–W03. |
| V04: legacy EDIT_POST fallback | Closed at source level in both verifier and handler. |
| V05: group revision/authority | Incoming ordinary-member packets no longer advance revision. Sender and reconciliation rules remain inconsistent: W04. |
| V06: foreign-author tombstones | Known active/orphaned rows now reject foreign overwrites; unknown deletes use author-scoped settings. Tombstone monotonicity regressed: W06. |
| V07: feed failures/cancellation | Helpers now report Boolean outcomes and rethrow cancellation. Joining under the lock introduces W01. |
| V08: signature reissue | Local conditional signature-only SQL is a good fix. Receiver upgrade semantics and inventory discovery remain incomplete: W07. |
| V09: identity recovery | Recovery banner and initial commit check added; state and error propagation still need W08. |

“Closed at source level” is deliberately narrower than “tested and release-ready.” Previously reviewed media route teardown and proxy deadline changes are not reopened without new evidence.

## Findings to action

### W01 — P1: cancelling an active feed sync can deadlock

**Evidence:** `data/FeedRepository.kt:76–86, 400–408, 607–611`.

`cancelSync()` holds `syncMutex`, cancels the active deferred, and waits for `deferred.join()`. The cancelled pass enters its `NonCancellable` finalizer and needs that same mutex before it can complete. The caller waits for the pass; the pass waits for the caller's lock. This happens when cancellation intersects an executing pass, not on every idle cancellation.

Disabling the aggregator during a fetch can hang, and subsequent sync operations can wait indefinitely behind the mutex.

**Required fix:** capture/detach the deferred and update ownership under the lock, then cancel/join outside it. Preserve an explicit stopping/ownership rule so a replacement pass cannot start writing before the previous pass has stopped. Do not solve this merely by deleting the join and reintroducing the previous overlap race.

**Regression:** start a pass paused inside a fake fetch; disable aggregation; verify cancellation completes within a timeout, no writes occur afterward, and re-enabling permits another completed pass. Exercise cancellation during setup and finalization too.

### W02 — P1 security: legacy GROUP_UPDATE still permits unsigned mutations

**Evidence:** `mesh/HandshakePacketHandler.kt:707–739, 922–946`.

`hasMutations` excludes `memberDetails` and `memberHandles`. A valid old four-field signature can therefore accompany attacker-modified directory maps, which are then passed to `syncMemberPeers()` and merged into the group. The new canonical encoder does not protect this fallback path.

There is also a ban-list case: `bannedMembers = []` produces an empty `sortedBanned`, so it does not trip `hasMutations`. For an otherwise accepted legacy admin update, the receiver treats the non-null empty list as an instruction to clear all bans. An intercepted legacy update newer than the receiver's revision can be modified without the admin signing that mutation.

**Required fix:** remove this fallback if no longer required, or enforce a field-presence allowlist covering every field the handler can mutate. A non-null empty replacement list is an operation, not “no mutation.” Never let a legacy signature authorize attached maps.

**Regression:** submit actual legacy-signed packets through `handleGroupUpdate`, adding directory maps and an empty ban list separately. Assert rejection and unchanged peer/group records. Crypto-helper tests alone cannot detect this bypass.

### W03 — P1 security: canonical GROUP_UPDATE does not distinguish absence from clearing

**Evidence:** `mesh/Packets.kt:326–353`; `mesh/HandshakePacketHandler.kt:709–729, 924, 939–945`; `data/NoSlopRepository.kt:1123–1129, 1170–1179`.

The canonical signing input represents both an absent ban list and an empty ban list as `""`. The receiver distinguishes them: null preserves existing bans; an empty list clears them. Consequently, a newly canonical-signed admin packet with null bans can be changed to an empty list without changing its signed bytes. The reverse can suppress an intended clear. This remains even if W02's legacy fallback is removed.

Nullable strings have the same structural issue: null and empty description/avatar encode identically, while `?: existing` gives them different effects. An empty clear can be suppressed by replacing it with null.

**Required fix:** encode field presence and value, or sign explicit operations such as `Unchanged`, `Clear`, and `Set(value)`. Verification, serialization, and the reducer must share the same semantics. Introduce an explicit signature/protocol version for this incompatible change; retain only compatibility forms whose accepted state transitions are safe.

**Regression:** for every optional mutating field, change null to empty and empty to null on a signed payload. The packet must either fail verification or produce exactly the same state transition by design. Include bans, description, and avatar.

### W04 — P1: group revisions and clearing still diverge between sender and receiver

**Evidence:** `data/NoSlopRepository.kt:1107–1118, 1176`; `mesh/HandshakePacketHandler.kt:877–880, 946, 1047–1066, 1136–1139, 1174–1207`.

Three concrete inconsistencies remain:

1. The local update path always sets `revision = timestamp`, including non-admin changes. Receivers now leave revision unchanged for those same changes. A member whose local clock is ahead can reject subsequent valid admin state that other peers accept.
2. Accepting a member addition/removal changes state without advancing revision, including on the admin's device. Query responses now correctly send the stored revision, but peers reject equal-revision snapshots. If a member update reaches the admin and misses another peer, the admin's resulting snapshot can be rejected by that peer as stale. An admin acknowledgement/revision step or an alternative operation protocol is missing.
3. The sender still serializes an empty admin ban list as null. Receivers interpret null as “keep bans,” so clearing the last ban does not propagate through the live update. Description/avatar clears similarly use null while live receivers preserve the old value. A later equal-revision query can also be skipped.

Non-admin snapshots still union incoming members when invites are enabled. That is not a reliable removal history: a stale snapshot is not evidence of a fresh authorized invitation. Define explicit re-invite semantics rather than treating all snapshot members as new invitations.

**Required fix:** share a group state reducer between local and remote actions. Use admin-authoritative revisions plus explicit member operations/acknowledgements, or another documented ordering model that supports offline catch-up. Fix presence/clear encoding with W03.

**Regression:** three-peer tests for missed member addition, self-removal, last-ban removal, metadata clearing, and a member clock ahead of the admin. Verify convergence after query/sync without requiring an unrelated subsequent admin edit.

### W05 — P1 data integrity: restore rollback still reports success or destroys recovery data incorrectly

**Evidence:** `data/BackupManager.kt:529–600, 626–698, 736–758, 808–824`; `data/ApiKeyRepository.kt:64–65`; `data/NoSlopDatabase.kt:226–244`; `ui/NoSlopViewModel.kt:2080–2119`.

Media snapshots and closing Room before snapshotting are worthwhile improvements. Remaining defects are concrete:

- Cleanup has no `commitSucceeded` state. A normal successful restore over an existing database retains snapshots and logs that rollback could not be verified. Conversely, `!targetDb.exists()` or `(dbBackupFile == null && securePrefsBackup == null)` can trigger cleanup even after rollback failed in another store. Those conditions do not prove recovery succeeded.
- Preference rollback ignores `commit()` results. Media/database deletions ignore Boolean failure results. `rollbackCompleted = true` can therefore be set after incomplete recovery, followed by snapshot deletion.
- API key import still calls `setKey()`, which uses asynchronous `apply()`. The new documentation's “verified synchronous commit” claim is false for this path.
- Closing the singleton is not a whole-application maintenance barrier. `getDatabase()` can reopen it, existing repositories retain closed DAO/database references, and the import caller does not stop writers before replacement. Only successful import triggers process restart; a failed import leaves the existing app graph using the closed instance.

**Required fix:** separate commit-success and rollback-success outcomes; preserve recovery assets on every uncertain failure; check persistence/deletion results; coordinate application writers and rebuild or restart the database-dependent graph after failure as well as success. Define a recovery journal if process-death atomicity is promised. Do not claim cross-store atomicity merely because exceptions are caught.

**Regression:** inject failure after at least one media overwrite and one media creation, then verify original bytes and file absence. Also inject rollback commit failure, deletion failure, and copy failure; verify snapshots survive. Test a successful restore cleans snapshots, failed restore leaves the app usable, and active workers cannot reopen/write during replacement.

The newly added `BackupManagerTest.testImport_mediaRollback_restoresOverwrittenMediaAndCleansCreatedMedia` injects a group-message encryption failure in commit phase 4. Media is only copied in phase 5. Its media assertions therefore do not prove undoing a media mutation. Move the failure point after actual media writes.

### W06 — P1: older deletes can lower an unknown post's tombstone

**Evidence:** `data/Daos.kt:173–177, 197–203, 232–245`.

`deletePostSafely()` unconditionally replaces the author-scoped tombstone timestamp. For a post not yet present locally, delivering valid same-author deletes at timestamps 100 then 50 leaves 50 stored. A delayed valid post at 75 is then accepted even though deletion at 100 should suppress it. These are relative example timestamps; the defect is delivery ordering, not clock units.

**Required fix:** transactionally retain the maximum deletion revision for the `(author, post)` pair, and document whether deleting a post ID is permanent or permits later recreation. Current known-orphaned rows reject all replacement timestamps, while unknown-row settings permit newer posts; align these policies deliberately. Evaluate upgrade treatment for historical orphaned placeholders rather than assuming the new settings fix old rows automatically.

**Regression:** real Room DAO tests with reordered/repeated deletes and delayed posts; retain foreign-author isolation tests. A fake that simply repeats the production logic is not independent evidence of SQL/transaction behavior.

### W07 — P2: post signature migration is safer locally but incomplete across peers

**Evidence:** `data/NoSlopRepository.kt:2153–2181`; `data/Daos.kt:179–180, 185–206`; `data/MeshSocialRepository.kt:895–899`; `mesh/SyncPacketHandler.kt:213–220`.

The new local conditional SQL only changes the signature and skips orphaned rows: keep that improvement.

On receipt, however, matching timestamps with different signatures permit `insertPost(post)` to replace the whole row. No comparison enforces the claimed “matching timestamps and content” condition. Different same-author signed states at the same timestamp can replace one another by arrival order; this is not a signature-only upgrade.

Inventory hashing still covers only ID, author, content, and timestamp. A peer holding the old signature advertises the same hash after the author re-signs, so inventory reconciliation does not discover the upgrade. Allowing an update at the DAO is insufficient if it is never requested/sent.

**Required fix:** define a strict signature-only migration path that verifies identical canonical state and updates only the signature. Define deterministic conflict behavior for genuinely different equal-revision states. Version inventory hashes or add an explicit migration/resend mechanism so historical peer copies actually converge.

**Regression:** seed two peers with matching historical content/timestamps but old/new signatures; run inventory reconciliation and verify the old peer receives and persists the upgrade. Separately verify a same-time content/privacy change cannot enter through the signature-only path.

### W08 — P2: recovery UI and result propagation still use different state definitions

**Evidence:** `ui/OnboardingScreen.kt:291–295`; `data/IdentityRepository.kt:35–73, 201–223, 427–440`.

The banner only checks missing identity plus the database flag. `needsIdentityRecovery()` also recognizes in-memory quarantine and marker files. A missing/unwritten database flag can therefore route the app into recovery without showing the new banner. The banner also performs blocking `runBlocking` work during composition and freezes the result with `remember`.

`resolveQuarantine()` now returns a Boolean, but save/restore callers ignore it. It also swallows failure to remove the database flag. A failed archival can coexist with an overall success result; the code and documentation do not agree on that contract.

**Required fix:** expose one asynchronous recovery state from the ViewModel and use it consistently. Decide whether unsuccessful marker archival is a recoverable warning or a failed operation; propagate that outcome instead of silently ignoring it. Retain the verified initial credential commit.

**Regression:** recovery with marker only, flag only, preference commit failure, marker rename failure, and restart after successful recovery. Assert UI state and returned outcome agree.

### W09 — P2: remediation documentation and tests overstate closure

**Evidence:** `docs/PROJECT_STATUS.md:42–51`; corresponding V01–V09 section in `docs/TECHNICAL_REFERENCE.md`; tests cited above.

The documents now describe useful changes, but still declare whole-state atomic rollback, verified API preference commits, strictly admin-only revision advancement, and content-matching signature upgrades more strongly than the implementation supports. Correct those claims using W01–W08. In the wire reference, document accepted legacy paths and field-presence semantics, not only the newest canonical form.

Directory tamper tests that call `CryptoService.verify()` prove the helper binds a field. They do not prove the handler rejects an unsafe fallback. Use the actual handler plus persisted-state assertions for security acceptance tests.

Keep historical remediation notes as history, but maintain one current open/closed/validated matrix. Mark each closure with source revision, executable test name, and observed result; avoid copying optimistic completion prose into multiple large documents.

## Consolidation and mock-code assessment

No newly introduced production mock implementation was established in the reviewed changes. Test fakes and UI/media placeholders are not inherently unfinished production code. The meaningful consolidation opportunities are:

- One group protocol model covering presence, signature version, authority, ordering, and application of changes. Emitter/receiver asymmetry is currently causing real defects.
- One identity validator returning a validated object. `validateIdentityObject()` and `restoreAuthoritativeIdentity()` repeat substantial schema/cryptographic validation and can drift.
- One restore coordinator with explicit state, checked persistence operations, and injectable failure points; the growing inline backup function is difficult to reason about reliably.
- One canonical post-state representation reused by signing, signature-only migration checks, and versioned inventory hashing.

Do not make a broad architectural rewrite or resume MVP migration a prerequisite for these fixes. Consolidate the specific rules involved while preserving working legacy features.

## Suggested next steps and exit criteria

1. Fix W01 first with a bounded cancellation regression test.
2. Resolve W02–W04 together: fallback policy, signed presence semantics, and a convergent group reducer must agree.
3. Complete W05 with failure injection after mutations and during rollback; verify the application remains usable afterward.
4. Fix W06, then finish W07–W08 and correct W09's documentation claims.
5. Run fresh flavour-specific tests on the user's Kubuntu setup. No device logs are needed to establish the source defects above.

From the repository root:

```bash
./gradlew --stacktrace testGithubDebugUnitTest --rerun-tasks
./gradlew --stacktrace testPlayDebugUnitTest --rerun-tasks
```

Run after adding the relevant regressions. Return the failing test output and XML reports under `app/build/test-results/` if either fails; the HTML report under `app/build/reports/tests/` is useful for inspection. A successful test task does not substitute for the missing cases listed above.

Then begin focused manual testing: disable/re-enable aggregation during an active fetch; three-device offline group reconciliation; backup/restore across devices with main and burnable identities, group messages and media; and recovery after an interrupted import. Include GitHub and Play flavour behavior and a release build on a device, since debug unit tests do not exercise release shrinking or real Keystore behavior.

The stopping condition is not zero hypothetical concerns. It is closure of the concrete state-integrity/security defects here, passing targeted regressions and fresh flavour tests, and documentation that accurately distinguishes verified behavior from pending device validation.
