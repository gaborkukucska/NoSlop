# NoSlop legacy Android — external review, round 8

Date: 2026-10-04, Australia/Perth. Scope: legacy Android `app/`, tests, database migration, build configuration and updated documentation. MVP excluded. Application source was not modified.

Archive: `NoSlop-main(7).zip`; ZIP commit comment: `088ca44e6bcc363d8392746c45e3f850ec31c364`.
SHA-256: `4e74c2d083afa2b95fc735add944ab301eda60f4241c3c941511868ec4963cce`.

Compared with `NoSlop-main(6).zip` and round 7's U01–U12 findings. Paths below are repository-relative: `K/` = `app/src/main/java/com/noslop/app/`; `T/` = `app/src/test/java/com/noslop/app/`.

## Opinion and readiness

**There are still obvious concerns to address before broad manual acceptance testing.** The update contains worthwhile fixes: authoritative identity restore now writes private credentials, ordinary POST/sync ingress rejects old audience-unsigned signatures, retained attachments have descriptors again, group removal is reachable, and HTTP header reads use a remaining timeout budget.

The remaining problems are concentrated in identity validation/rollback, protocol authentication and state ordering. Some changes satisfy the previous narrow regression case while introducing another failure: foreign tombstones no longer block an authentic author, but foreign POSTs can now replace an established author's deleted row. A dedicated group revision exists, but ordinary members can advance the same revision used to reject admin state.

The codebase is a substantial working implementation. The recurring weakness is treating comments, helper-level checks, or a single happy-path test as proof of an end-to-end invariant. More patching without adversarial state-transition tests is likely to continue this cycle. The findings below specify those tests and distinguish fixes that are complete in source from unresolved defects.

## Review evidence and limits

- Inspected changed legacy production files, affected callers, tests, schema 16 and documentation against the previous archive. No `AGENTS.md` or `start_HERE.txt` was found.
- Inventory: 116 production Kotlin files, 19 JVM test Kotlin files with 160 `@Test` annotations, and two instrumentation files with four annotations. These are source counts, not executed test counts.
- Independently reproduced two algorithmic problems: an unrelated X25519 public/private pair passes the new self-encryption probe, and distinct member directory maps produce identical canonical strings. These small Python checks reproduce the algorithms; they are not Android runtime tests.
- Executed the new `ALTER TABLE group_chats ADD COLUMN revision INTEGER NOT NULL DEFAULT 0` against checked-in schema 15 using SQLite; it succeeds. This is not a substitute for Room's migration validation/instrumentation.
- Attempted the correct GitHub-flavor unit-test, lint and debug-build tasks. Gradle download again failed with `Network is unreachable` before project execution. No current-archive compilation, lint or Android test result is claimed.
- Earlier user-supplied successful JVM execution applies to an older checkout. Current documentation's lint-success claims need current revision-associated logs; their absence here does not prove those claims false.

Priority: P0 = authentication/privacy boundary; P1 = serious correctness/recovery/release blocker; P2 = secondary maintenance. Source-confirmed defects are distinguished from runtime validation still needed.

## Previous findings: current disposition

| Round 7 ID | Current disposition |
| --- | --- |
| U01 public-only restore fallback | Specific omission fixed. New identity API writes main/burnable secrets, checks commit and reloads. X25519 validation is ineffective: V01. |
| U02 rollback mismatch | Identity/API snapshots and wider catch added. Snapshot consistency, media rollback, rollback failure handling remain unsafe: V02. |
| U03 group signature gaps | Invitations now include directories and restrict seven-field fallback. Serialization collisions and unsigned UPDATE/SYNC directories remain: V03. |
| U04 legacy privacy downgrade | POST and sync fallback removed. EDIT still permits old pipe signatures despite the claimed strict policy: V04. |
| U05 group removal/revision | Unreachable removal fixed; dedicated revision and schema migration added; admin sync clears nullable fields/bans. Authority/order defects remain: V05. |
| U06 attachment metadata loss | Normal text-only edit sender now includes retained metadata, and receiver preserves it. Close the reported normal-path loss; descriptors still are not signed beyond their ID. |
| U07 foreign tombstone reservation | Narrow test fixed by permitting foreign replacement. This is not author-scoped tombstone storage and creates V06. |
| U08 feed ownership/results | Immutable generation and non-cancellable cleanup added. Async cancel ordering and swallowed failures remain: V07. |
| U09 quarantine recovery | Persistent flag/marker resolution added. Unreadable identity now routes to generic onboarding, without a distinct recovery flow: V08. |
| U10 route revocation | Old loader shutdown and direct-client cancellation added. Close the specific missing-revocation source finding; validate retained UI consumers/network behavior during manual testing. |
| U11 proxy syntax/deadline | Reported missing-hyphen/extra-part cases fixed; remaining budget is applied before reads and checked afterward. Close source-level findings; monotonic clock/socket tests remain useful P2 work. |
| U12 build/test evidence | New assertions and fake-avatar semantics improved. Current build/lint/migration evidence still required. |

## Findings requiring implementation

### V01 — P1 — Encryption-key consistency probe accepts mismatched keys

`K/data/IdentityRepository.kt:240–245,269–273` calls encryptDM and decryptDM with the same supplied public/private pair. Both operations derive the same shared secret from that pair, whether or not the public key belongs to the private key. Successful decryption therefore proves repeatable derivation, not keypair consistency.

This was independently reproduced using two unrelated X25519 keys, SHA3-256 and ChaCha20-Poly1305, matching the algorithm in `K/crypto/CryptoService.kt:296–350`. The mismatched pair still round-trips. Real peers encrypting to the advertised public key will not agree with the restored unrelated private key.

**Fix:** derive the public key from the private key and compare canonical public bytes, or use a fresh third-party keypair and verify agreement from both sides. Validate both main and burnable identities before live replacement. Validate relevant onion/tripcode/mnemonic relationships under the supported identity-version policy; do not infer correctness from successful parsing.

**Acceptance:** mix public key A with private key B for main and burnable encryption identities. Restore rejects before live mutation. With a valid pair, a separate peer encrypts and the restored identity decrypts after restart.

### V02 — P1 — “Atomic whole-state rollback” still loses recovery guarantees

`K/data/BackupManager.kt:570–581` copies the DB, WAL and SHM separately while the live database is still open; it closes the database only at commit (668–669). These files are not a consistent snapshot if writers/checkpoints run between copies. Snapshotting preference caches/files separately also lacks a quiescence boundary.

Concrete remaining failure paths:

- Media is overwritten at 758–768 but never snapshotted/restored. If the second media copy fails, rollback can restore the old DB/credentials while retaining already overwritten media.
- On a previously absent target DB, rollback does not remove the newly installed DB when there is no `dbBackupFile` (635–638).
- `performFullRollback` catches/logs its own failure. The outer finally then deletes all snapshot files regardless (772–783), including after rollback fails. This can discard the remaining recovery copy.
- Preference rollback ignores `commit()` results. API writes still use `ApiKeyRepository.setKey` with asynchronous `apply()`, so import success does not verify durable API-key persistence.
- Existing repository/DAO references and background writers are not coordinated. Closing the singleton is not equivalent to stopping all users of it.

Full nested validation still happens partly in the post-replacement identity API. Missing required archive components, export snapshot consistency, skipped unreadable export rows and unbounded logical-message restore memory remain unresolved from earlier reviews.

**Fix:** acquire an application restore boundary, quiesce writers, create a consistent snapshot, stage validation/re-encryption, and publish a coherent generation. Cover every modified store including media and the “previously absent” state. Preserve snapshots and return a distinct rollback-failed result if rollback cannot be verified. Do not log “full rollback completed” without checking every write.

**Acceptance:** separate A/B DB markers, identities, API keys and same-name media files; inject failure at each commit/rollback operation. Test empty destination and concurrent writers. Restart and verify all state is entirely A or entirely B, or explicitly recoverable from retained snapshots.

### V03 — P0 — Signed directory encoding is ambiguous; other group directory paths stay unsigned

`K/mesh/Packets.kt:300–314` joins fields with `:` and entries with `;`, without escaping or length-prefixing each component. Handles can contain delimiters. Distinct maps can therefore have identical signed bytes. For example, using symbolic sorted keys A/B:

```text
Map 1: A -> (enc=E1, onion=O1, handle="x;B:E2:O2:y")
Map 2: A -> (enc=E1, onion=O1, handle="x"),
       B -> (enc=E2, onion=O2, handle="y")
Both encode: A:E1:O1:x;B:E2:O2:y
```

Actual valid public-key strings can replace A/B; choose their ordering accordingly. The outer `encodeForSigning` only prefixes the already-ambiguous whole string. It cannot distinguish the maps. `canonicalMemberHandlesString` has the same problem. The simple mutation test added this round does not cover structural collisions.

Also, GROUP_UPDATE's canonical representation still excludes memberDetails/handles, and GROUP_SYNC still signs only group JSON/timestamp while applying separate `sync.memberDetails`. Restricting invite fallback does not authenticate those paths. The seven-field invitation empty-field gate additionally omits `memberHandles`, allowing unsigned names through that compatibility branch.

**Fix:** use an injective, versioned encoding for every map entry and field, with canonical key order, unambiguous boundaries and explicit null semantics. Bind or independently verify directory data in UPDATE and SYNC too. Include every unsupported unsigned field in restricted legacy policy.

**Acceptance:** collision pairs containing `:`/`;` in handles, changed map boundaries, unsigned handles on seven-field invites, and modified UPDATE/SYNC encryption keys. Exercise real receive handlers and peer persistence. Two semantically different authenticated directories must never share signing bytes.

### V04 — P0 — Legacy EDIT still permits an unsigned audience change

`K/mesh/PostPacketHandler.kt:187–202` and `MeshPacketVerifier.kt:152–160` still accept an old pipe EDIT signature when incoming privacy is public and attachments/clearnet URL are absent. Privacy is not covered by that signature. The handler then persists public privacy.

Thus the claimed strict eight-field policy is not implemented for EDIT. A captured eligible old edit, delivered against an older stored post owned by that signer, can be relabelled public while preserving its signature. The stored row also receives a legacy edit signature that cannot pass the new canonical-only POST sync verifier.

**Fix/acceptance:** remove the legacy EDIT bypass or define a genuinely restricted compatibility operation that cannot change unsigned state or be persisted as a full-state signature. Test old friends edits relabelled public through both stateless verification and the handler; verify subsequent sync. Preserve the now-correct canonical-only POST/sync behavior.

### V05 — P1 — Ordinary members can advance the revision that blocks admin updates

`K/mesh/HandshakePacketHandler.kt:1120 onward` compares incoming `sync.timestamp` to `existing.revision`, then writes that timestamp into revision in both admin and non-admin branches (1190,1197). An authorized ordinary member can sign a snapshot with a far-future timestamp, advancing the revision and causing legitimate admin UPDATE/SYNC operations to be ignored. A faulty member clock can have the same effect. Non-admin UPDATE also advances the shared revision.

The new `group.revision` inside the signed snapshot is not used as the authoritative state version; query responses stamp current send time instead (`1039–1043`). Member snapshots still union membership when invites are enabled, so stale state reissued with a fresh timestamp can resurrect a removed non-banned member. GROUP_UPDATE still unions bans and interprets null metadata as unchanged, while admin SYNC now uses replacement/clear semantics.

Migration initializes existing revisions to zero and invite-accept construction does not consistently seed a trusted state revision. Adding a column alone does not establish ordering or authority. Same-millisecond concurrent operations also need a defined tie policy.

**Fix:** separate admin-owned state revision from member requests/hints and transport send time. Only authenticated authoritative transitions advance that revision. Apply a transactional reducer shared by local mutation, UPDATE, invite acceptance and SYNC. Use a deliberate version/tie/removal policy rather than trusting arbitrary peer clocks.

**Acceptance:** member future timestamp then admin removal; stale member snapshot resent after removal; UPDATE ban/unban versus SYNC; migrated and newly joined groups; concurrent changes. Admin authority and convergence must survive every ordering.

### V06 — P1 — Tombstone workaround allows author replacement after deletion

`K/data/Daos.kt:177–192` now permits any incoming author to overwrite an orphaned row belonging to another author. This fixes foreign unknown tombstone → authentic post, but also permits:

1. A's legitimate post is stored.
2. A deletes it, creating an orphaned row.
3. B signs a new POST using the same ID.
4. The new branch accepts B and replaces the established A row.

Post-ID-based comments/reactions/references can now point at another author's content. Unknown tombstones also still share a single primary-key slot, so independent authors' deletion records cannot coexist durably.

**Fix:** use separate `(author, postId)` deletion records, retain established post ownership independently, or introduce author-bound IDs with a migration policy. Do not repair reservation attacks by allowing arbitrary author replacement of known deleted posts.

**Acceptance:** retain the new foreign-tombstone test and add known A post → A delete → B POST, multiple author-scoped tombstones, and restart/sync. B must not acquire A's established post identity.

### V07 — P1 — Feed failure counters count swallowed errors as successes; cancellation is asynchronous

`K/data/FeedRepository.kt:357–407` increments sourcesSucceeded after each fetch helper returns. The helpers at 457,487,542 catch exceptions and return normally. Consequently an all-failed network pass can still count every attempted source as successful. Ramp-up tasks are not included in these counters, so a ramp-up-only failure may return Success with zero attempts. Broad catches also still swallow cancellation at helper boundaries.

The generation token fixes the old owner-capture bug. However, `cancelSync()` now schedules a non-cancellable coroutine and returns immediately (76–86). A caller can disable/re-enable/start before that coroutine acquires the mutex; the delayed cancellation can cancel the replacement task. It cancels but does not join old work before permitting another pass. Old finally still clears global status outside the generation check.

**Fix:** make cancellation an awaitable serialized transition; use a stable request/generation contract, cancel and await owned work as appropriate, and scope status changes to the owning generation. Return typed outcomes from helpers; rethrow cancellation and include ramp-up in aggregation.

**Acceptance:** failing RSS/API/creator sources through their real helpers; ramp-up-only failure; delayed cancel followed by new refresh; cancellation during insertion. Verify outcomes and that old work cannot cancel or overwrite status of a new generation.

### V08 — P1 — New signature reissue bypasses guarded post mutation and does not converge existing replicas

`K/data/NoSlopRepository.kt:2144–2182` loads all posts, signs the read snapshot, and calls raw `updatePostDetails` with its content/timestamp/media fields. A concurrent edit or deletion between read and update can have its state overwritten by the old snapshot. This bypasses the guarded DAO methods introduced to solve precisely that race. The query includes orphaned posts too.

Reissue keeps the same timestamp. Receivers with an existing old-signature row reject the replacement because `insertPostSafely` rejects equal timestamps. Inventory hashing uses ID/author/content/timestamp rather than signature/version, so a signature-only change need not be requested at all. Fresh peers can accept a reissued post while existing replicas keep unsyncable old signatures.

**Fix:** make signature migration a transactional compare-and-update of the exact version read, excluding tombstones. Define a protocol/signature revision and synchronization repair path for equal-content historical rows; do not overwrite content to update only a signature.

**Acceptance:** pause reissue after read and concurrently edit/delete; then resume. No content regression is allowed. Upgrade an author and two peers holding old signatures; all intended replicas must converge to verifiable canonical state without changing privacy.

### V09 — P1 — Quarantine still lacks a distinct user recovery flow

`IdentityRepository.needsIdentityRecovery()` is used only within `isOnboardingComplete`; it is not exposed to a dedicated recovery navigation state. Returning false now sends the user into ordinary onboarding. `IdentityRestoreResult.RecoveryRequired` is declared but never returned. This avoids the prior broken normal-app state but still conflates first install with an unreadable existing identity.

`saveIdentity` calls commit without checking its result, then resolves quarantine markers. Marker rename failure is also ignored. A failed recovery write can therefore be treated as resolved. Valid recovered keys should be durably verified before archiving the recovery-required marker.

**Fix/acceptance:** expose recovery-required to the UI and credential-dependent services; preserve old identity material and distinguish recover versus explicitly start fresh. Check persistence and marker transitions. Test failed commit, restart while quarantined, successful recovery, and explicit reset. Do not describe generic onboarding as a completed recovery workflow.

## Documentation, tests and consolidation

The new status/technical-reference section is useful, but several closure claims are false or too broad:

| Claim | Required correction |
| --- | --- |
| X25519 keypair consistency validated | Current probe does not validate pairing; V01. |
| Atomic rollback across all stores | Open-file snapshot, media omission and failed-rollback cleanup remain; V02. |
| Canonical EDIT ingress enforced | Pipe fallback still exists; V04. |
| Tombstones scoped to authors | Storage is still one MeshPost row per ID; V06. |
| Aggregated source failure detection | Exceptions are swallowed before counters observe them; V07. |
| Quarantine presents recovery | Only onboarding boolean changes; V09. |
| Complete authenticated attachment descriptor | Descriptor now transmitted, but the eight-field signature binds its ID, not type/size/thumbnail/origin. |

`WIRE_PROTOCOL_REFERENCE.md` still describes legacy POST fallback in its signing table even though POST/sync now reject it; earlier group tables remain stale. It also fails to document the actual retained EDIT fallback and safe mixed-version upgrade behavior. Invitations moved from 12 to 14 encoded fields without an explicit protocol version; prior 12-field invites are not accepted by the new canonical or seven-field paths. Document deliberate incompatibility and upgrade/reissue handling rather than silently adding more fallback branches.

Positive test changes: main/burnable signing after restore, a rollback failure injection, foreign tombstone ingress, retained-media edits, and the fake-avatar null behavior. These should be preserved.

Test weaknesses to correct:

- New rollback test keeps User A's marker in User B's exported DB, so marker survival alone does not prove DB rollback. Create genuinely distinct stores/markers, and assert B-only data is absent after failure. Include media and rollback-failure injection.
- New identity test verifies signing, not encryption with a distinct peer and destination key environment. Add the mismatched-key case from V01.
- Directory tampering test changes an individual key but not serialization structure. Add the demonstrated collision and real handler tests.
- The seven-field rejection test verifies only that a seven-field signature fails the new helper; it does not call the handler's fallback gate.
- No new group revision/migration or feed-generation integration test closes V05/V07. Execute Room migration validation, not only the SQL ALTER statement.

No broad mock implementation was found in the reviewed production paths. There is now some dead/redundant code: legacy POST payload strings remain constructed after fallback removal; `RecoveryRequired` has no producing path. Remove obsolete signing reconstruction only after the version policy is finalized.

Consolidation should target the actual duplicated invariants: a versioned injective protocol codec; a shared authorized group reducer; author-scoped deletion storage; a signature-only migration operation; one restore coordinator/identity store; and a feed task owner with typed fetch results. Avoid cosmetic file splits while these policies remain duplicated.

## Suggested implementation order and handoff criteria

1. Fix V01/V02 and add genuine source/destination recovery and rollback-failure tests.
2. Close V03/V04; encode protocol version and compatibility policy explicitly.
3. Fix group authority/order V05 and post ownership/reissue V06/V08 together with real DAO tests.
4. Finish feed ownership/outcomes V07 and recovery navigation V09.
5. Correct documentation, then provide current GitHub debug JVM/lint/build and Room migration results. Check Play and minified release variants before release. The previously documented lock-resolution issue remains an evidence gate until current logs establish it is resolved; this archive does not change build configuration.

The source-level U10/U11 fixes can move to targeted manual validation: switch direct/Tor during image/video activity, inspect cancellation and continued UI operation, and test slow/incomplete HTTP headers. Do not keep those exact findings open merely because no device test has been run here; record their runtime status separately.

After V01–V09 are resolved with the stated tests, a broad manual acceptance pass is appropriate. Current evidence does not support declaring that milestone reached.

Reproduction command after fixes, from the repository root:

```bash
mkdir -p review-results
set -o pipefail
./gradlew :app:testGithubDebugUnitTest :app:lintGithubDebug :app:assembleGithubDebug --rerun-tasks --stacktrace --console=plain 2>&1 | tee review-results/legacy-round8-gradle.log
```

Attach the exact commit and test-result XML with the log. A passing existing suite is a baseline; closure requires the newly identified failure sequences above.
