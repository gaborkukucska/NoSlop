# NoSlop legacy Android — external review, round 6

Review date: 2026-10-04. Scope: legacy Android `app/`, its tests, schema snapshots, and updated documentation. MVP and migration implementation are excluded.

Reviewed archive: `NoSlop-main(5).zip`; ZIP commit comment: `f637e0f0b24e8fe229dcba438fc2d5eb47a61d14`.
Archive SHA-256: `29fd19eca810f1999f326d7a8810d7856224c587db995d9e67c23e7e2e88dfa4`.
Compared with the previous supplied archive, `NoSlop-main(4).zip`, and `docs/EXTERNAL_REPORT_5.md`. References below are repository-relative. `K/` abbreviates `app/src/main/java/com/noslop/app/`; `T/` abbreviates `app/src/test/java/com/noslop/app/`.

## Overall opinion

The legacy application is improving, and this update contains meaningful fixes. Transactional post mutations, consistent eight-field signing in the principal post/edit paths, correctly signed leave operations, independently signed invitations, and signer-based group-sync authorization are substantial progress. The code is a real implementation, not a collection of mock screens or placeholder services.

It is not yet ready to be described as hardened or reliable for identity recovery. A new SQL regression breaks portable group-message restoration. Several security and lifecycle findings remain partially addressed, while the updated status documents describe them as resolved. The recurring problem is that fixes are validated locally within one function instead of across the complete sender → wire → verifier → persistence → replay/restore path.

Keep the focus on the legacy app. Prioritize recovery correctness, authenticated protocol state, and lifecycle integration tests before further feature development or broad restructuring. No application source was modified during this review.

## Review method and limitations

- Compared all changed non-MVP source and documentation files with the previous snapshot, then traced affected callers, handlers, DAO operations, schema definitions, and tests. No non-MVP files were deleted between these snapshots.
- Inventory: 116 production Kotlin files; 18 JVM test Kotlin files with 128 `@Test` annotations; two instrumentation Kotlin files with four annotations. Counts describe source, not passing tests.
- Attempted `bash gradlew :app:testDebugUnitTest :app:lintDebug --no-daemon`. Execution stopped while downloading `gradle-9.6.0-bin.zip` with `java.net.SocketException: Network is unreachable`. At that stage compilation, Android tests, and lint remained unverified; this was an environment blocker, not a demonstrated build defect. Subsequent user-run evidence below confirms fresh GitHub debug compilation and JVM unit-test task success; lint and device tests remain unverified.
- Independently executed the new restore INSERT against the checked-in Room schema 15 using SQLite. It reproducibly fails with `NOT NULL constraint failed: chat_messages.isRead`.
- Remaining findings are source-confirmed control/data-flow defects or explicitly identified integration risks. No emulator, fresh-device restore, live Tor transition, or multi-device mesh test was performed. This is a focused follow-up audit, not a claim of exhaustive correctness across every legacy feature.

Priority: **P0** = security boundary requiring immediate attention; **P1** = serious correctness/recovery/reliability defect; **P2** = hardening, maintenance, or secondary behavior. P0 does not imply a demonstrated remote compromise of a running device.

## Previous finding disposition

| Previous ID | Current assessment |
| --- | --- |
| R01 post/edit sync | Principal eight-field, retained-media-ID, and avatar problems fixed. Optional-field persistence and historical compatibility remain: S08. |
| R02 group update/leave signing | Wire omissions and signed strings now align in the inspected emitters/receiver. Preserve this fix and test real round trips. |
| R03 reused invitation signature | Independently signed now. Invitation state still uses old permissions and, for member invitations, misleading admin contact fields: S07. |
| R04 legacy group authorization | Old update mutation guard improved; old invitations and unsigned directory fields remain unsafe: S02. |
| R05 post downgrade/attachments | ID/metadata consistency improved; unsigned legacy audience and semantic metadata remain unresolved: S03. |
| R06 group sync authority | Signer-based metadata permissions fixed; revision/replay/removal semantics remain unresolved: S04. Unknown foreign groups are no longer automatically inserted. |
| R07 atomic post mutation | Room transaction wrappers added correctly for the inspected ingress paths. Deletion-before-creation and optional-field merge limitations remain: S08/S09. |
| R08 feed task ownership | Setup is tracked and outcomes are typed. Detached ramp-up work, cancellation races, and swallowed fetch failures remain: S05. |
| R09 staged restore | Actual quick-check result and some JSON parsing now checked. Whole-archive validation and atomic commit are still absent: S06. |
| R10 unreachable legacy recovery | Flags now set in the raw legacy branches; the previously unreachable branch is reachable. Correct cross-device identity recovery still needs a real device test. |
| R11 portable message backup | Export streams and cleans its temporary file. Restore has a new deterministic SQL failure; restore memory growth and partial-success reporting remain: S01/S06. |
| R12 destination-only secrets | Main preferences are cleared and committed; absent portable API-key entries still leave destination keys: S10. |
| R13 identity quarantine | Rename checked and reinitialization attempted. A clean empty store is not recovery of the quarantined identity: S11. |
| R14 route transition | Active video source is now reset; image memory cache cleared. Image-client/request revocation is not established: S12. |
| R15 proxy ranges | Normal Range prefix now parsed; out-of-bounds ranges still fall through to full responses: S13. |
| R16 proxy resource limits | Atomic admission and complete-header requirement fixed; deadline is bounded approximately, not a strict five seconds: S13. |
| R17 liveness reset | The identified unauthenticated reset is removed. Do not reopen that exact finding. Separate malformed relay behavior: S14. |
| R18 deploy secrets | Absolute cleanup paths and explicit existing-config chmod address the identified defects in source. Actual deployment not exercised. |

## Findings requiring action

### S01 — P1 — Portable group-message restore fails on a required SQL column

**New, independently reproduced.** `K/data/BackupManager.kt:662–691` replaces the previous UPDATE with an INSERT OR REPLACE containing nine columns. It omits `isRead`. Both `K/data/Entities.kt:109–120` and `app/schemas/com.noslop.app.data.NoSlopDatabase/15.json` define `isRead` as non-null; the SQL schema has no default. Kotlin's constructor default does not apply to raw SQL.

The first imported logical group message triggers `NOT NULL constraint failed: chat_messages.isRead`. The surrounding catch logs the error and continues; import later returns true at lines 766–767. On a fresh device, the copied raw ciphertext remains encrypted with the source device's group key, so logical re-encryption has not repaired that history.

**Fix:** use a complete typed entity/DAO or include every required column, preserving read status when replacing an existing message. Re-encrypt in staging or a controlled transaction. Propagate failure to a structured restore result; never describe this as a complete import.

**Acceptance:** restore one and many group messages to a fresh installation and over an existing installation; restart and decrypt every body. Inject one bad row/key failure and verify rollback or an explicit incomplete result. Run against real Room schema, not only a fake DAO.

### S02 — P0 — Legacy invitations still authenticate unsigned group state and directory data

`K/mesh/HandshakePacketHandler.kt:734–755` still accepts four-field and pipe invitation signatures, although those signatures do not cover members or permissions. Reconnect actually emits this format at lines 479–498. A captured invitation can retain a valid old signature while its membership/permission fields are changed. The recipient-membership check at lines 764–773 checks the modified list, not an authenticated list. Pending-invite consent limits automatic joining but does not make the displayed group state authentic.

The new seven-field invite representation also omits description, avatar, member details, and admin onion/encryption-key fields. GROUP_SYNC signs the group JSON but not the separate `memberDetails`, then calls `syncMemberPeers` at line 1113. The legacy-update mutation guard at lines 709–729 does not address those directory fields either. Treat directory authentication as a distinct unresolved requirement.

**Fix:** one versioned, domain-separated invite/update/sync codec; bind all state consumed as authoritative. Restrict or retire old invitations, update reconnect emitters, and authenticate peer encryption/onion bindings independently before accepting directory hints. Do not authorize an existing group's policy using a signer selected solely from a newly supplied member list.

**Acceptance:** mutate each unsigned field of a valid old/new invitation and sync packet; unauthorized changes must not enter accepted group or peer state. Exercise create, add-member, reconnect, resend, accept, and relayed receipt using actual builders.

### S03 — P0 — Public-only legacy fallback does not authenticate the original audience

`K/mesh/MeshPacketVerifier.kt:118–134`, `K/mesh/PostPacketHandler.kt` POST/EDIT legacy guards, and `K/mesh/SyncPacketHandler.kt` still decide legacy eligibility from incoming `privacy == "public"` and absent attachments. An old signature does not cover privacy. Relabeling an old friends-only text post as public satisfies this guard without changing its signed bytes. Tightening the metadata-null check closes one injection path, not this downgrade.

New canonical signatures cover the media ID but not metadata type, size, thumbnail, or origin. Matching IDs alone does not authenticate all fields used by media handling. Conversely, historical friends/media posts and seven-field edits from the immediately previous version have no documented re-signing/migration policy.

**Fix:** introduce an explicit compatibility/trust policy for old records; do not infer original audience from unsigned incoming fields. Bind semantic attachment data or validate it against an authenticated content descriptor. Preserve legitimate historical data through a deliberate local-author reissue/migration where possible.

**Acceptance:** replay an old friends signature with public privacy; alter metadata while retaining the ID; synchronize pre-upgrade posts and edits. Verify both rejection of unauthorized changes and the documented historical-content behavior.

### S04 — P1 — Group snapshots have authority but no freshness or removal ordering

`K/mesh/HandshakePacketHandler.kt:1059–1150` verifies a signed timestamp but does not compare it with persisted group revision/state. An old authentic admin snapshot can replace newer membership and settings. A non-admin member snapshot still unions members when invites are enabled, resurrecting removed but unbanned members. Existing bans are filtered, but incoming admin bans are not persisted by `mergedGroup.copy`.

The membership gate at lines 1083–1088 also rejects an authoritative snapshot that excludes the recipient, preventing that snapshot from informing the removed device of its removal. Immediate GROUP_UPDATE may work; missed-update reconciliation remains incomplete.

**Fix:** persist an authoritative revision and removal/ban semantics. Reject stale admin state, restrict member hints, and explicitly handle a valid removal of the local identity. Avoid wall-clock ordering as the sole distributed-state contract.

**Acceptance:** reorder old/new admin snapshots, deliver a stale member snapshot after removal, miss a removal update then reconnect, and synchronize a ban change. All peers should converge without resurrecting members or reverting permissions.

### S05 — P1 — Feed cancellation can leave detached work and lose ownership of a replacement pass

`K/data/FeedRepository.kt:68–80,216–253,311–335,389–398` improves coalescing but launches ramp-up tasks with `syncScope.async`, making them siblings of the active deferred instead of children. Cancelling the pass does not cancel those tasks. `cancelSync()` clears the shared reference outside `syncMutex`, and a pass's finally clears it unconditionally rather than checking ownership. Depending on scheduling/cancellation, an old pass can clear a replacement's reference, or its cancellable lock acquisition can prevent cleanup. The invariant is still not reliable.

Fetch helpers at lines 434,464,519,574 still catch broad exceptions; per-source failures can be logged while the pass returns Success. Worker cancellation is also caught as a generic exception in `FeedSyncWorker`. Tor/setup failures now map to retries correctly, but typed results do not yet describe all meaningful failures.

**Fix:** structured child tasks within the pass; serialize cancel/start and await termination where required; clear the reference only for its owning generation. Define shared-waiter cancellation separately from task ownership. Rethrow cancellation inside helpers and aggregate source outcomes.

**Acceptance:** cancel during ramp-up, immediately restart, disable/re-enable, cancel a worker waiter, and fail all sources. No untracked work or overlapping pass may remain; results must distinguish success, partial failure, retry, and cancellation.

### S06 — P1 — Restore validation is incomplete and commit remains destructive on failure

`K/data/BackupManager.kt:509–579` now checks quick_check properly, but identity validation only checks four key names. Later lines 604–619 require handle, tripcode, onion, display name, and burnable fields after the live DB has been replaced. Valid JSON with four keys but missing `handle` passes staging and fails during commit. Public/private-key consistency and DB/identity agreement are not checked.

Malformed API JSON only logs a warning (549–557); group validation only parses the outer array (560–568), leaving row schemas to the commit loop. Missing required archive components are not rejected by a manifest. DB replacement uses `copyTo(overwrite = true)` after deleting WAL/SHM, without rollback or coordination of existing repository/worker references. A failed identity commit is logged rather than aborting the restore.

Group restore still reads the entire file into a String and JSONArray (471–474,564). Export now streams, but failed decryptions and export exceptions still only warn (216–225). Export snapshot/checkpoint consistency remains unproven; logical rows and raw DB are read at different times.

**Fix:** validate complete schemas, identity relationships, required entries, counts, bounds, and DB compatibility before live mutation. Stage re-encryption too. Use a restore coordinator with quiescence, rollback, verified commit results, and repository restart. Export from a consistent snapshot; report incomplete history explicitly. Stream restore as well as export.

**Acceptance:** missing identity fields, malformed inner group row, corrupt API JSON, missing DB, key mismatch, disk-full commit, concurrent writers, and large history. Each must preserve the old state or produce a complete validated replacement, with an accurate result.

### S07 — P1 — Add-member invitations carry stale permissions and member-as-admin contact details

`K/data/NoSlopRepository.kt:1191–1214` signs the new invite independently, but uses `existing.allowMemberInvites` and `existing.allowMemberSelfRemove`, while the group update uses effective new settings. Adding someone and changing permissions in one operation sends different state to existing and new members.

For an ordinary member, `adminKeys` at line 1061 falls back to that member's own identity. The invitation names the stored admin at line 1203 but supplies the member's onion/encryption key as `adminOnion`/`adminEncPublicKey` at 1213–1214. Acceptance can use those fields to create a missing admin peer (`NoSlopRepository.kt:875–897`).

**Fix:** construct the final invitation from effective group state, separate inviter signing credentials from authenticated administrator contact data, and reuse this builder for every invite path.

**Acceptance:** change permissions while adding a member; invite as an ordinary permitted member to a recipient without an admin peer. Verify matching permissions and correct admin identity/contact binding.

### S08 — P1 — Edit verification and stored complete-post state can still diverge

`K/mesh/PostPacketHandler.kt:186–228` verifies missing privacy as public but persists missing privacy as the existing value. Missing media ID similarly preserves an existing attachment. The newly signed `clearnetUrl` is never passed to the DAO; `K/data/Daos.kt:173–206` does not update it. A valid canonical packet can therefore produce a stored row different from its signed state, causing later POST sync verification failure.

Current main sender behavior avoids some cases by sending retained ID/URL and explicit privacy. The receiver contract still permits them, and historical null-field edits remain problematic. Fallback-derived fields are also read outside the transaction, so their values can become stale before the guarded write.

**Fix:** explicitly choose complete signed state versus partial signed operation. For complete state, validate and persist exactly every signed field; for partial operations, do not reuse their signature as a full POST signature. Perform any authorized merge inside the transaction. Do not weaken sync verification.

**Acceptance:** edit → persist → restart → sync with omitted privacy, retained/replaced/removed media, changed/cleared avatar, existing/changed clearnet URL, and concurrent edits. Verify reconstructed bytes, not only displayed text.

### S09 — P2 — Delete-before-create has no durable tombstone

`K/data/Daos.kt:211–216` returns false when no post exists. `PostPacketHandler.kt:257–261` still returns true. A valid delete arriving before its post leaves no durable deletion record; later POST or sync can insert that post. The new transactions fix races around existing rows, not out-of-order network delivery.

**Fix/acceptance:** store an authenticated author/ID deletion tombstone independent of post existence and retain an ordering policy. Deliver DELETE then POST and DELETE then delayed sync; the deleted content must not appear.

### S10 — P1 — A backup with no API keys does not clear destination API keys

Export only writes `api_keys_backup.json` when the object is non-empty (`K/data/BackupManager.kt:230–242`). Restore clears missing service entries only when that JSON exists and parses (`644–652`). A normal backup containing zero keys takes the other branch, leaving destination keys in place. This contradicts full replacement semantics and the documentation's cleansing claim.

**Fix/acceptance:** export an explicit empty key collection in a versioned manifest and define legacy absence semantics. Restore a zero-key backup over a destination with keys and verify the intended empty state after restart. Malformed key JSON must not silently select merge behavior.

### S11 — P1 — Quarantine/reinitialization is not identity recovery

`K/data/IdentityRepository.kt:48–77` now checks rename and attempts a fresh encrypted store. It does not recover the quarantined secrets or expose a dedicated recovery-required state. If initialization succeeds, callers receive an empty store; if it fails, they receive a different fallback store. The existence of old key material on disk is not a working recovery flow.

**Fix/acceptance:** distinguish first install, recovered identity, quarantined identity awaiting user recovery, and unavailable secure storage. Preserve identity continuity and do not silently treat an unreadable existing identity as a fresh account. Exercise initialization failure with an existing main/burnable identity and confirm an actionable recovery path.

### S12 — P1 integration risk — Clearing image cache does not establish route revocation

The active video early-return defect is addressed by `K/ui/components/VideoPlayer.kt:665–679`. `invalidateAllOnRouteTransition` now clears Coil's memory cache, but does not replace its image loader/client or cancel its in-flight requests. `K/NoSlopApp.kt:38–69` builds an image client from `activeClearnetClient`; no route-generation rebuild is added in this update.

Do not claim that clearing cached image bytes revokes old network activity. Verify the lifecycle of the existing loader and retained calls. The active-video fix itself also needs an on-device transition test before asserting zero direct traffic.

**Fix/acceptance:** coordinate route-generation changes across players, resolvers, preloaders, image clients, and in-flight calls. Begin direct video and image downloads, enable Tor, then inspect subsequent requests and socket activity; no newly scheduled request should use an obsolete direct route. Distinguish buffered playback from continued network fetching.

### S13 — P2 — Proxy edge ranges and exact deadline claims remain incorrect

`K/mesh/MediaProxyService.kt:301–351` leaves start=0/end=last when the requested start is outside the file. For a 100-byte file, `Range: bytes=100-` therefore becomes a full 200 response instead of the implemented 416 path. End-before-start ranges also fall back to an unintended range. Empty-file behavior needs explicit treatment.

The header loop at 392–419 checks its deadline only after blocking read returns. With a five-second socket timeout, a read started just before the deadline can overshoot it. Admission and incomplete-header rejection are materially improved; “strict five-second total deadline” is too strong.

**Fix/acceptance:** parse ranges into explicit valid/unsatisfiable/unsupported outcomes; test suffix, open-ended, oversized start, reversed range, zero-length file, and multiple ranges. Use remaining-budget socket timeouts or a cancellable read mechanism for a strict deadline; test a slow trickle followed by a stall.

### S14 — P2 — Invalid media descriptors bypass the pre-forward rejection verdict

`K/mesh/MeshPacketVerifier.kt:110–112,140–141` returns null for inconsistent media descriptors. `verify` converts null to UNVERIFIABLE (78–83), and `K/mesh/GossipService.kt:604–615` drops only INVALID. Forwarding can occur before the handler rejects the same malformed POST/EDIT. Malformed signed packets are therefore not rejected at the intended relay boundary.

**Fix/acceptance:** distinguish unsupported/stateful verification from malformed known signed payloads. Return INVALID for known structural violations; preserve a deliberate policy for AEAD/state-dependent packets. Submit a mismatched-ID packet with an invalid signature and assert that no forwarding occurs.

## Documentation corrections

The new prose is useful as a change log, but must not be used as proof that all R01–R18 requirements are complete.

| Location | Required correction |
| --- | --- |
| `docs/PROJECT_STATUS.md:3–30`; `docs/TECHNICAL_REFERENCE.md` §17.22 | Replace categorical “resolved/eliminating/closing” claims for R04/R05/R08/R09/R11/R12/R13/R14/R15 with tested behavior and remaining limitations above. |
| Same sections | `canonicalGroupInvitePayload` is named as an implementation helper, but no such symbol exists under `app/`. Either implement the shared builder or describe the actual inline encoder. |
| `docs/WIRE_PROTOCOL_REFERENCE.md` §2 and POST/EDIT/GROUP entries | Only `clearnet_url` was added in this update. Signature tables still describe old pipe/four-field strings. Document exact canonical field order, defaults, legacy acceptance, signer resolution, and pending-invite behavior. |
| Status heading `v0.6.9-alpha` | `app/build.gradle.kts:24–25` remains versionCode 65 / `0.6.5-alpha`. Label planned/documentation version separately or update release metadata when appropriate. |
| `MeshPacketVerifier.kt:28–38`; `FeedRepository.kt` opening comment | Stale comments claim no canonical encoder and a verbatim behavior-preserving move. Update them to describe current code and remaining duplicated reconstruction. |
| `docs/EXTERNAL_REPORT_5.md` | Preserve as historical evidence, with a pointer to this follow-up and explicit finding statuses. Do not rewrite past findings as though they described the current tree. |

For each closure claim, record the test name, environment, observed result, and supported old-version behavior. A code comment containing an audit ID is not evidence of closure.

## Duplication, mocks, and consolidation

No broad production mock implementation or obvious `TODO`/`NotImplemented` scaffold was found in the inspected legacy paths. Most “placeholder” matches are legitimate UI hint text or fallback metadata. Test fakes are appropriate, but they must reflect production semantics: `T/data/FakeDaos.kt:170–190` retains an old avatar when the supplied value is null, whereas production SQL assigns null. This can hide avatar-clear/signature defects. Fake DAO execution also does not provide Room transaction isolation.

The new group-update and leave tests in `T/mesh/GroupMessageSecurityTest.kt` sign and verify the same manually constructed string; they do not call the repository emitter or receiver. They cannot catch sender/receiver drift. Wire round-trip tests and post handler tests are useful additions, but do not replace full post-edit-sync or restore-schema tests.

Recommended consolidation, after urgent fixes:

1. **Protocol codecs/builders:** share typed canonical encoders and complete payload builders across repositories, reconnect paths, verifier, handlers, sync, and tests. Keep authorization policy separate from byte encoding.
2. **Restore coordinator:** separate manifest/schema validation, snapshot export, staged re-encryption, commit/rollback, and user-facing result. `BackupManager.kt` currently mixes all of these responsibilities.
3. **Group state reducer:** one authority/revision policy for invites, updates, snapshots, removal, and bans. Today each path independently interprets similar state.
4. **Route lifecycle owner:** one generation-aware cancellation/rebuild contract for HTTP/media/image clients instead of UI-driven cache clearing as the main control.
5. **Repository boundaries:** `NoSlopRepository.kt` remains 2,287 lines, `VideoPlayer.kt` 2,138, `HandshakePacketHandler.kt` 1,154, and `BackupManager.kt` 852. Extract cohesive behavior with integration tests; avoid a cosmetic file split that leaves duplicated policies intact.

## Suggested implementation order and release gates

1. Fix S01 and add the real-schema restore regression test immediately. Make all import/export failures visible and complete S06/S10 before trusting backups as recovery artifacts.
2. Close S02/S03 with explicit protocol versions and compatibility decisions. Correct the wire reference in the same change; retain security regression fixtures from old formats.
3. Add authoritative group revisions and fix invitation construction (S04/S07). Test multiple devices with reordered and missed packets.
4. Finish signed post-state persistence and tombstones (S08/S09), then run create/edit/restart/sync/deletion tests through real Room.
5. Fix feed task ownership (S05) using deterministic cancellation/start barriers; verify retry and partial-failure results.
6. Validate route transitions and identity recovery on devices (S11/S12), then close proxy/verifier edge cases (S13/S14).
7. Run JVM tests, instrumentation tests, lint, debug build, and a minified release smoke test in an environment with the required Gradle/JDK/Android dependencies. Publish actual results before marking the release hardened.

## Local Gradle evidence update — 2026-10-04

The supplied `legacy-gradle.log.txt` reaches project configuration but fails during task selection: `:app:testDebugUnitTest` is ambiguous between `testGithubDebugUnitTest` and `testPlayDebugUnitTest`. No requested tests, lint checks, or APK build executed. The earlier suggested command omitted the distribution flavor; this is a correction to the review instructions, not evidence of an application compilation or test failure. `app/build.gradle.kts` declares both `github` and `play` flavors.

S01–S14 and their priorities are unchanged. Begin actioning the confirmed findings while obtaining the corrected baseline below. The log also reports deprecated Android options and legacy variant APIs; track their migration as P2 maintenance, but they did not cause this failure. The printed Java runtime is Temurin 25.0.4; this log does not establish the daemon/toolchain versions or a Java compatibility defect.

## Second local Gradle evidence update — 2026-10-04

The supplied `legacy-github-gradle.log` uses the correct GitHub tasks. It fails while preparing `:app:generateGithubDebugAndroidTestLintModel`, resolving `githubDebugAndroidTestCompileClasspath`. The outer configuration-cache error wraps a concrete dependency constraint conflict:

- `androidx.concurrent:concurrent-futures` is locked strictly to 1.1.0, but the resolved `androidx.test.ext:junit:1.3.0` requires 1.2.0.
- `androidx.concurrent:concurrent-futures-ktx` is locked strictly to 1.1.0, but `androidx.test.espresso:espresso-core:3.7.0` requires 1.2.0.

This is a lint/instrumentation-classpath build gate defect in the user's current checkout, not an assertion failure from the JVM unit tests. Disabling configuration caching alone does not reconcile the conflicting dependency versions. The reviewed ZIP has no `app/gradle.lockfile`; obtain the actual lock configuration before choosing an update. Do not indiscriminately delete locks or downgrade test dependencies to hide the conflict.

**S15 — P1 verification blocker:** reconcile the current Android test dependency locks with the intended dependency graph, review the narrow lockfile diff, then rerun GitHub debug lint and build. Check Play and release variants before release. This local-environment finding supplements S01–S14 without changing their disposition.

The separately pasted `./gradlew --stacktrace testGithubDebugUnitTest` output reports BUILD SUCCESSFUL, with all 33 tasks UP-TO-DATE and configuration cache reused. Record that as a successful up-to-date invocation; it does not show fresh test execution, test counts, or lint/APK success. No failing unit test is demonstrated by either supplied result.

To obtain a fresh JVM baseline independently of lint:

```bash
mkdir -p review-results
set -o pipefail
./gradlew --stacktrace --console=plain :app:testGithubDebugUnitTest --rerun-tasks 2>&1 | tee review-results/legacy-github-unit-tests.log
```

For the developer fixing S15, inspect the exact dependency paths before updating locks:

```bash
./gradlew :app:dependencyInsight --configuration githubDebugAndroidTestCompileClasspath --dependency androidx.concurrent:concurrent-futures --no-configuration-cache --console=plain
./gradlew :app:dependencyInsight --configuration githubDebugAndroidTestCompileClasspath --dependency androidx.concurrent:concurrent-futures-ktx --no-configuration-cache --console=plain
```

## Fresh JVM test evidence — 2026-10-04, final verification update

The supplied `Pasted text.txt` records the requested `:app:testGithubDebugUnitTest --rerun-tasks` invocation. It completed **BUILD SUCCESSFUL in 1m 22s**, with **33 actionable tasks: 33 executed**. Production Kotlin/Java compilation, test compilation, and `:app:testGithubDebugUnitTest` all ran. This establishes a fresh successful GitHub debug JVM test run in the user's checkout, superseding the earlier up-to-date-only evidence. The output does not print individual test counts or skipped-test counts; do not equate 33 Gradle tasks with 33 tests or claim that every source annotation was executed.

No compiler errors or test failures appear. S01–S14 remain actionable because this suite does not demonstrate the missing restore/protocol/lifecycle integration guarantees. S15 remains open: this command does not run lint or resolve the failing Android instrumentation-test lint classpath. APK assembly, Play/release variants, instrumentation tests, and on-device behavior are not established by this result. The local checkout's exact commit/working-tree identity was not captured, so attribute this result to the supplied checkout rather than claiming a cryptographically matched ZIP build.

Warnings worth retaining in the maintenance backlog:

- `BackupManager.kt:684`: inferred intersection type in the SQL argument array; use an explicit supported argument type when repairing S01. This compiler warning is separate from the missing `isRead` SQL-column defect.
- Experimental coroutine API opt-ins, deprecated Android/media/Gradle APIs, unchecked casts, and constant conditions need targeted cleanup after correctness fixes. Do not automatically remove defensive wire-payload checks solely because Kotlin's static types make them appear redundant; deserialization boundaries require validation.
- The WorkManager initializer manifest-removal warning is non-fatal in this run; verify intended initialization behavior before changing manifest declarations.

**Action:** no further unit-test rerun is needed for the present baseline. Proceed with the report's implementation order, add the specified regression tests, reconcile S15, and rerun affected checks after those changes. The commands below are retained as reproduction instructions, not a request to repeat the already successful baseline.

## Requested local verification (Kubuntu)

Run from the repository root. This checks only the legacy `:app` module and captures both output streams. Keep `pipefail` so a failing Gradle invocation is not hidden by a successful `tee`.

```bash
mkdir -p review-results
set -o pipefail
bash ./gradlew :app:testGithubDebugUnitTest :app:lintGithubDebug :app:assembleGithubDebug --no-daemon --stacktrace --console=plain 2>&1 | tee review-results/legacy-github-gradle.log
```

This selects the GitHub distribution of the legacy app. Please provide `review-results/legacy-github-gradle.log` first. If tasks execute, useful generated evidence is `app/build/test-results/testGithubDebugUnitTest/` and `app/build/reports/lint-results-githubDebug.html` (or the corresponding lint report path printed by Gradle). Existing tests passing will not disprove the uncovered integration defects; most need the regression tests described above.

No device logs are required yet. For a subsequent targeted restore test, use a disposable test profile/installation with a known-good backup; retain the original data. Capture the specific failure window rather than an entire device log, and remove recovery words, private keys, API credentials, and personal message content before sharing. The primary expected current failure is `NOT NULL constraint failed: chat_messages.isRead` during portable group-message import.

The next review should be able to verify these end-to-end invariants from tests and accurate documentation, rather than infer completion from individual patched functions.
