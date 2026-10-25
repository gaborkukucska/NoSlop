# NoSlop legacy Android — external review, round 7

Date: 2026-10-04 (Australia/Perth). Scope: legacy Android `app/`, associated build configuration, tests and documentation. MVP implementation and migration are excluded. This is a report-only review; application files were not modified.

Archive: `NoSlop-main(6).zip`. ZIP commit comment: `fb52be40a898d8940338dbe02a2dba592340f21a`. SHA-256: `bc327d07d156cafb430b38fcd439ba2691a06dc5ab2c309d50089d987e3261ce`.

Compared against `NoSlop-main(5).zip` and the previous S01–S15 findings. Throughout this report, `K/` means `app/src/main/java/com/noslop/app/`, and `T/` means `app/src/test/java/com/noslop/app/`. Line references apply to this archive.

## Overall opinion and manual-testing readiness

**Not yet ready for the “obvious concerns addressed” milestone.** This update makes useful progress, but several high-impact defects remain visible without device testing. The most urgent new issue is that restore's secure-storage fallback can report success after restoring only public identity fields. The new rollback restores the database without restoring credentials, and the new group-removal branch is unreachable. A normal text edit of a media post now strips attachment metadata on receiving devices.

The application is substantial, implemented software rather than a mock application. The weakness is still incomplete end-to-end behavior: a local patch often closes the immediate symptom while leaving another caller, compatibility path, persistence invariant, or error path inconsistent. Additional tests are welcome, but some assertions stop before the property the documentation claims they establish.

Address the P0/P1 items below with focused automated tests, then begin the planned manual acceptance pass. Small targeted device checks can support those fixes now; a broad manual acceptance pass should not be used to discover these already-visible defects. A source review cannot certify the absence of every bug, but the listed blockers are concrete enough to close with evidence.

## Evidence and verification limits

- Reviewed all changed legacy production files and relevant test/documentation diffs, then followed affected restore, group, post, feed, routing, and DAO paths. No `AGENTS.md` or `start_HERE.txt` was present in this archive.
- Inventory: 116 production Kotlin files; 19 JVM test Kotlin files with 152 `@Test` annotations; two instrumentation Kotlin files with four annotations. Counts are not execution results.
- Executed the actual updated group-message INSERT against the checked-in Room schema 15 using SQLite. It succeeds and preserves `isRead = 1`. The previous deterministic missing-column failure is closed.
- Attempted `:app:testGithubDebugUnitTest :app:lintGithubDebug :app:assembleGithubDebug`. The environment again failed while downloading Gradle 9.6.0 with `Network is unreachable`, before compilation or tests.
- The earlier user-supplied fresh test run was successful for the previous checkout. It is useful historical evidence, not validation of this new archive. The new documentation claims lint passes; no execution log or test-result XML was included here to independently confirm that claim. This does not mean the claim is false.
- Most findings below are confirmed source/control-flow defects; scheduling and route-revocation cases are identified as integration risks. No current-archive Android device run or full build was performed.

P0 denotes an authentication/privacy boundary requiring immediate work; P1 denotes a serious functional, recovery, or release-validation issue; P2 denotes secondary hardening/maintenance.

## Disposition of the previous report

| Previous finding | Current disposition |
| --- | --- |
| S01 missing `isRead` in restore | Fixed. Column included, explicit SQL argument type added, logical-message writes wrapped in a transaction. SQLite reproduction succeeds. |
| S02 invitation/directory authentication | Partial. Shared extended invite encoder added and four-field invites rejected. Seven-field fallback and unsigned member directory remain: U03. |
| S03 legacy post audience downgrade | Still open; receive/sync fallback logic is unchanged: U04. |
| S04 group state freshness/removals | Partial and flawed. `createdAt` used as a revision, removal branch unreachable, stale-member resurrection remains: U05. |
| S05 feed ownership | Structured ramp-up children fixed. Publication/cancellation/cleanup ownership and failure outcomes remain incomplete: U08. |
| S06 restore coordination | Better field checks, signing-key probe, and DB backup added. Whole-state rollback and complete validation are not implemented: U01/U02. |
| S07 invitation permissions/admin contact | Specific inspected emitter bugs fixed. Effective permissions and separate inviter/admin contact data are now used. |
| S08 exact edit state | Signed privacy and clearnet URL now persisted correctly. Attachment retention introduces a regression: U06. Historical edit compatibility remains unresolved. |
| S09 delete-before-create | Tombstone added and sequential test added. The tombstone can reserve another author's post ID: U07. |
| S10 empty API-key backup | Fixed in the inspected normal portable path: empty JSON exported; portable absence also clears keys. Malformed JSON rejects before commit. |
| S11 quarantine recovery | Still incomplete. New flags change onboarding behavior but do not implement recovery: U09. |
| S12 route revocation | New global image loader is installed. Old in-flight/retained-loader lifecycle still requires closure: U10. |
| S13 proxy ranges/deadline | Main range bugs fixed with parser tests; exact deadline and malformed syntax remain: U11. |
| S14 mismatched metadata relayed | Identified mismatch cases now return INVALID before forwarding. Broader malformed-payload policy remains a separate hardening topic. |
| S15 lint dependency conflict | Both concurrent-futures artifacts forced to 1.2.0; lint-related changes added. Resolution claimed fixed by docs, but current execution evidence is needed: U12. |

## Current blockers and actionable findings

### U01 — P1 — New restore fallback reports success without usable private identity

**New regression.** `K/data/BackupManager.kt:658–671` catches any exception from the secure preference restore and clears/writes the fallback preference store. It writes public keys, handle, tripcode, onion, display name, and onboarding flags, but no `ed25519_private_key`, `enc_private_key`, mnemonic, or burnable private keys. It then marks `restoredFallbackIdentity = true`; the later portable flow can return true.

`K/data/IdentityRepository.kt:185–186` requires both private-key fields and returns null when either is absent. A newly constructed repository therefore cannot use this fallback as a restored identity. The normal secure-storage success branch does still write burnable keys; the omission is specifically the new fallback branch.

The catch also covers malformed burnable JSON, not only Keystore unavailability. A bad nested identity can be turned into a superficially successful public-only fallback. A failed secure `commit()` at lines 651–653 also only logs and continues; fallback commit's return value is ignored.

**Fix:** restore through one identity-store API with explicit success, recovery-required, and failure outcomes. If supported encrypted storage cannot persist all required secrets and verify them, abort/roll back or clearly require recovery. Do not solve this by writing plaintext private keys. Validate nested identities before commit and distinguish validation errors from unavailable storage.

**Acceptance:** simulate Keystore creation failure and preference commit failure; restore main plus burnable identity; restart, load both identities, sign/verify and encrypt/decrypt with them. Import must not return ordinary success with missing credentials. Use a separate destination context/store rather than the existing source repository instance.

### U02 — P1 — Restore rollback can pair an old database with a new identity

`K/data/BackupManager.kt:596–609` snapshots only the database. It then replaces credentials and API keys before importing logical messages. On message failure, lines 742 onward restore the old DB but leave the newly written identity/API stores in place. Restoring user B's backup over user A and failing group-message re-encryption can therefore leave A's database paired with B's identity.

Only that message-import catch performs rollback. Failure during replacement, identity handling, API writes, or later media copying does not restore the whole previous state. The DB backup is deleted before media copying completes. This is not transactional replacement of the application state. Old repository/DAO references and workers are still not coordinated by a restore lifecycle.

Validation is also partial: only main Ed25519 key consistency is checked, not encryption-key pairing, burnable identity, identity/DB agreement, or a required archive manifest. Group rows only require three key names, while later reads/encryption can still fail after credentials are committed. Missing required components are not comprehensively rejected. Restore still materializes the whole group JSON string and array. Export decrypt failures still warn/omit rows, and a consistent DB/identity/message snapshot is not established.

**Fix:** stage and validate the complete replacement, including logical re-encryption, before publishing it. Coordinate writers and repository restart. Back up and restore every mutated store or publish one coherent generation with rollback; propagate all failed writes. Retain old state until the full commit succeeds. Stream large histories and surface incomplete exports.

**Acceptance:** seed distinct A/B identities, DB markers and API keys; fail each commit phase and verify every store still belongs to A or completely belongs to B. Include unreadable group rows, malformed burnable fields, inconsistent encryption keys, missing DB, disk-full media copy, and concurrent writers.

### U03 — P0 — Expanded group signatures retain an unsigned compatibility bypass

`K/mesh/HandshakePacketHandler.kt:739–769` and `K/mesh/MeshPacketVerifier.kt` now accept the shared 12-field representation, but also accept the old seven-field representation without restricting fields it did not sign. That older signature does not bind the advertised admin public key separately from the signer, description, avatar, admin onion, or admin encryption key. A valid seven-field invitation can still be replayed with those unsigned fields changed. Rejecting four-field invites is progress; it does not close this remaining branch.

The new canonical helper in `K/mesh/Packets.kt:300–328` still omits `memberDetails` and `memberHandles`. GROUP_SYNC also carries a separately unsigned member directory. `NoSlopRepository.syncMemberPeers` now avoids changing trusted contacts' blank keys, but still creates unknown peers using those supplied encryption/onion bindings (`815–835`) and fills untrusted peers. Group sending uses peer encryption/contact data; “untrusted” is not itself an authenticated key binding.

**Fix:** make compatibility explicit and prevent old signatures from authorizing fields they do not cover. Bind authoritative directory data or require an authenticated peer identity exchange before using directory hints as encryption destinations. Share a versioned codec and authorization contract across all emitters/receivers; do not merely add another fallback.

**Acceptance:** submit an old seven-field invite with altered admin identity/contact/description; submit canonical invites and sync packets with modified member encryption keys. Exercise the real handler and accepted peer state, not only `CryptoService.verify` against the new helper. Modified unsigned data must not become authoritative.

### U04 — P0 — Legacy friends-to-public downgrade remains unchanged

`K/mesh/PostPacketHandler.kt:62–69,198–203`, the stateless verifier, and `K/mesh/SyncPacketHandler.kt:351 onward` still infer legacy safety from incoming public privacy and missing attachments. The old signature does not cover privacy. Relabeling a captured old friends-only text post as public still satisfies the guard without changing signed bytes.

New post signatures bind media ID but not the semantic media descriptor. Historical private/media posts and previous seven-field edits also still lack an explicit migration/reissue policy. The documentation's claim that the guard eliminates downgrade remains incorrect.

**Fix/acceptance:** define a safe trust policy for old records, version the protocol, and avoid inferring authenticated audience from unsigned fields. Add real receive/sync tests for old friends → public mutation, metadata tampering, and supported historical content. Do not weaken verification to make old records sync.

### U05 — P1 — Group removal fix is unreachable and revision semantics remain inconsistent

`K/mesh/HandshakePacketHandler.kt:1095–1097` immediately rejects `!meInGroup`. The newly added branch at 1129 checks `signerIsAdmin && !meInGroup`; it cannot execute. An authoritative snapshot removing the recipient is still rejected.

The new freshness check compares snapshot send time with `existing.createdAt`, and successful admin sync rewrites `createdAt` (1123,1164). GROUP_UPDATE does not advance this value. Thus an older signed snapshot can still undo a more recent update if its send time exceeds the original creation time. Creation time is now serving two incompatible roles, including its use by resend-invite code.

Ordinary member snapshots still union membership when invites are enabled (1145–1146), so non-banned removals can be resurrected. Ban lists are unioned, preventing a later authoritative empty list from clearing a previously synced ban. Null description/avatar still mean retain-old in sync, so clear operations cannot converge either.

**Fix:** resolve stored authority first, then handle authoritative local removal before the ordinary membership gate. Add a persisted state revision distinct from creation time, used consistently by UPDATE and SYNC. Specify removal, unban and nullable-field clearing semantics. Apply state atomically rather than as independent read/merge/write steps.

**Acceptance:** admin removes local recipient via sync; newer UPDATE then older SYNC; member snapshot after removal; ban then unban; clear avatar/description. Verify deterministic convergence using real handler calls and persisted revisions.

### U06 — P1 — Text edits of attached posts erase remote attachment metadata

**New normal-path regression.** `K/data/MeshSocialRepository.kt:525–548` retains the existing media ID when no replacement media is supplied, but sends `mediaMetadata = mediaMetadata`, which is null for a text-only edit. It retains local media type, thumbnail, and size.

The changed receiver at `K/mesh/PostPacketHandler.kt:208–215` retains the ID/URL but sets media type and thumbnail to null and size to zero when metadata is absent. Editing the caption of an existing video therefore leaves peers with different attachment state. `SyncPacketHandler.toPostPayload` later defaults a null media type to `image`, spreading the incorrect descriptor through sync.

**Fix:** emit a complete authenticated descriptor for retained attachments, or define a validated retain operation that merges the unchanged descriptor transactionally. Keep complete-state versus partial-operation semantics explicit. Null attachment ID should mean removal only when the sender and versioned contract agree.

**Acceptance:** create image/video → edit only text → live receipt → restart → sync. Type, size, thumbnail, media ID, playback, and signed representation must remain consistent across sender and recipients. Also test replacement and removal.

### U07 — P1 — Unknown-post tombstones let another signer reserve a post ID

`K/data/Daos.kt:211–238` now inserts an orphaned MeshPost for an unknown ID using whichever author signed DELETE. Signature verification proves who signed the deletion, not who owns an unseen post ID. If B learns A's post ID and B's self-signed deletion arrives at a recipient before A's post/sync, B's tombstone permanently blocks the authentic A post: `insertPostSafely` rejects the orphaned row before accepting it.

The new delete-before-create test only covers the same author. The intended ordering behavior is fixed, but the global ID reservation introduces a different ownership failure. An attacker needs knowledge of the ID and delivery to an eligible recipient; this is not an attack on Ed25519.

**Fix:** store unknown tombstones scoped by `(author, postId)` or use IDs cryptographically bound to authors. Preserve authenticated deletion data and apply tombstones only to matching-author content. A foreign author's tombstone must not occupy the legitimate post's primary-key slot.

**Acceptance:** B signs DELETE for A's not-yet-seen post ID, then A's valid POST arrives; A's content remains eligible. A's own DELETE before POST must still suppress it. Cover restart and delayed sync.

### U08 — P1 — Feed ownership is still inferred from a racy shared reference

Structured `coroutineScope` children are a correct improvement. However, `K/data/FeedRepository.kt:235–239` starts the async task before publishing it as `activeSyncDeferred`; `executeSyncPass` captures `activeSyncDeferred` at line 257 as its owner. A task can capture null/an older task, or an old task scheduled after cancellation/replacement can capture the newer task. The finally identity check is therefore not guaranteed to compare against itself.

`cancelSync()` still clears the reference outside the mutex and does not await completion. It can miss a concurrently created pass or allow old/new work to overlap. Cleanup's mutex acquisition remains cancellable. Inner fetch helpers still catch broad exceptions, and an all-source failure can produce Success. Worker cancellation handling has not changed.

**Fix:** assign an immutable generation/task token before execution, publish/start under one synchronization policy, and serialize cancellation with start. Use structured cleanup that cannot clear another pass; define whether cancelling a waiter cancels shared work. Propagate cancellation and aggregate source results.

**Acceptance:** deterministically pause before async publication, race cancel/restart, cancel ramp-up, and fail all fetches. Assert one tracked generation, no lingering source writes, and accurate results. Add a coroutine lifecycle test rather than relying on timing-heavy device reproduction.

### U09 — P1 — Quarantine state skips onboarding without providing recovery

`K/data/IdentityRepository.kt:25–31,270–277` now returns onboarding-complete when a quarantined preference file exists. Searches of the app show no recovery UI consuming `isQuarantined`/`hasQuarantinedIdentity`; these names are used only within IdentityRepository. The user can reach normal app state while credentials are unavailable, instead of receiving an actionable recovery flow.

`saveIdentity` clears the in-memory flag and removes an app setting, but does not resolve/archive the `.corrupt_` marker detected by `hasQuarantinedIdentity`. The code removes/reads `identity_quarantined` without introducing a corresponding write. File existence can keep the quarantine condition true after successful recovery or account reset.

**Fix/acceptance:** expose a distinct recovery-required state to navigation and credential-dependent services. Preserve original material, require successful key validation before normal operation, and mark recovery resolved persistently without losing the backup. Test restart, successful recovery, failed recovery, and explicit reset with a retained quarantine file.

## Remaining hardening and validation work

### U10 — P1 integration risk — Replacing the global image loader does not prove old calls stop

`K/ui/components/VideoPlayer.kt:119–134` clears caches and installs a new global Coil loader. It does not explicitly shut down the old loader or cancel/rebind image requests already using it. Existing consumers may retain the prior loader/client. Rebuilding the global default is progress but not evidence of route revocation. Clearing disk cache is also unrelated to cancelling sockets and adds work during a route transition.

Coordinate retained image requests, resolvers, preloads, and players using an explicit route generation. Verify with a controlled in-flight download that switching to Tor stops obsolete direct network work and that existing visible image consumers use the new route. Treat this as an unresolved lifecycle risk, not a demonstrated current-device leak.

### U11 — P2 — Header deadline still does not bound blocking read; parser accepts malformed ranges

`K/mesh/MediaProxyService.kt:408–430` now checks remaining time before `input.read()`, but does not apply that remaining budget to the socket timeout. A read started just before the deadline can still block for the original five seconds. The last header byte can also complete the header after the deadline without a subsequent time check.

The extracted range parser correctly handles the previous out-of-bounds, reversed and empty-file cases. It still accepts malformed forms such as `bytes=5` as open-ended and ignores extra split components in `bytes=5-10-20`. These are secondary to the recovery/security work.

Use a monotonic deadline and remaining-budget read timeout/cancellation; strictly validate range grammar and explicitly handle unsupported multi-ranges. Add socket-level deadline tests and actual HTTP response checks, not just parser result assertions.

### U12 — P1 release gate — Current dependency/lint success is not yet independently evidenced

`app/build.gradle.kts:16–21` forces both concurrent-futures artifacts to 1.2.0 for every configuration. This is a resolution override, not an updated reviewed lockfile. The earlier local failure involved strict 1.1.0 locks; no corresponding lockfile is supplied in this archive. Verify the actual lock configuration and avoid assuming that a force statement alone establishes reproducibility.

The lint changes are concrete: opt-ins, custom view superclass, receiver registration, indentation, and a project lint configuration. The documentation claims zero lint errors, but no current log is included. Record the exact revision, commands and results. Do not remove valid checks or globally suppress unrelated findings to obtain a green gate. Existing successful tests for the prior ZIP do not close this gate.

## Documentation accuracy and testing quality

`docs/PROJECT_STATUS.md` and `docs/TECHNICAL_REFERENCE.md` §17.22 still overstate completion in several places:

| Claim | Current evidence/correction |
| --- | --- |
| Complete identity schema/keypair validation | Main fields and Ed25519 probe improved; burnable/encryption-key/DB relationships remain unchecked. U01/U02. |
| Transactional DB replacement with rollback on restore failures | Only logical-message SQL is transactional; a limited DB-only rollback leaves other stores changed. U02. |
| Preserved quarantine recovery | Flags exist, but no recovery navigation/state lifecycle. U09. |
| Public legacy fallback eliminates downgrade | Original audience remains unsigned. U04. |
| Five-second total header deadline | Remaining budget is not applied to blocking reads. U11. |
| All routing leaks eliminated | Requires retained-request cancellation and current device/network evidence. U10. |
| Real-database restore proves recovery | Test checks row/read status, not restored private identity or new-device decryption. |

The version mismatch is corrected: build metadata now says versionCode 69 / `0.6.9-alpha`. The shared `canonicalGroupInvitePayload` symbol now exists. These prior documentation defects are closed.

The wire reference's later signing table is improved, but earlier packet tables/descriptions still describe old signatures. The seven-field invitation fallback is omitted from the new canonical description. The phrase “legacy unauthenticated fallback” is imprecise: content has an old signature, but important fields are not authenticated. Document exact versions, covered fields, null/clear semantics, permitted legacy behavior, and signer authority once, with consistent references throughout.

Test improvements are real: range cases, invalid-identity rejection, empty API-key restore, complete edit fields, and sequential tombstones are now covered in source. Remaining gaps explain why obvious defects survive:

- `BackupManagerTest.testExportAndImport_withRealDatabase_preservesGroupMessagesAndIsRead` uses the same test group key on export/import and checks only `isRead`. It never reloads identity or decrypts the restored body with a separate destination key. The corrupt-identity test checks false, but not a before/after DB-and-credential snapshot.
- New group tamper tests mostly verify strings through the canonical helper. They do not exercise the handler's seven-field fallback or unreachable removal path.
- `FakePostDao.updatePostDetails` still retains the old avatar when a null avatar is supplied, while production SQL writes null. Fix this semantic mismatch; fake DAO calls also do not test Room transaction isolation.
- `MediaProxyServiceTest` checks parser results rather than proving a complete HTTP 416 response or strict header deadline.

No broad production mock subsystem was found in the reviewed paths. `resetForTesting` hooks are test support, not evidence of a mock app. Keep them isolated and ensure reset cancels owned jobs where required; do not change production recovery behavior simply to accommodate Robolectric's Keystore limitations.

## Consolidation priorities

1. One identity-store and restore coordinator with coherent failure outcomes, instead of direct preference writes in multiple fallback branches.
2. Versioned protocol codecs plus separate authority/state-transition validation shared by live receive and sync. A shared encoder alone cannot authorize fields.
3. A group reducer with dedicated revisions, tombstones and explicit clearing semantics; remove duplicate ad hoc merges.
4. Complete post-state/attachment construction shared by local edits, live packets and sync serialization.
5. A feed task owner with a stable generation token and defined cancellation semantics.

Prefer these focused consolidations over a broad rewrite. Do not restart MVP migration as part of this remediation.

## Required next steps and handoff gate

1. Fix U01/U02 and add separate-source/destination, failure-injection recovery tests. Preserve the now-working SQL column fix.
2. Close U03/U04 with an explicit legacy protocol policy and tests through actual handlers.
3. Fix group reconciliation U05, media edits U06, and foreign-author tombstones U07.
4. Complete U08/U09; verify route lifecycle U10 and finish U11.
5. Correct closure claims and attach current test/lint/build evidence for U12. Record any deliberately deferred P2 item with a reason and bounded impact.

Once these source-visible P0/P1 blockers are closed and regression checks pass, the manual pass should cover fresh-device backup recovery (main/burnable identities and chat decryption), multi-device group add/remove/unban/reconnect, text/media edit synchronization, feed cancellation/restart, and direct-to-Tor switching during active media/image requests. Include both relevant distribution variants and a minified release smoke test before release.

For current GitHub debug evidence, run from the repository root after the fixes:

```bash
mkdir -p review-results
set -o pipefail
./gradlew :app:testGithubDebugUnitTest :app:lintGithubDebug :app:assembleGithubDebug --rerun-tasks --stacktrace --console=plain 2>&1 | tee review-results/legacy-round7-gradle.log
```

Supply the log and test-result XML if a task fails or tests are skipped. Passing existing tests alone does not close U01–U10; the new acceptance cases above are the criteria for closure.
