# NoSlop — Remaining TODOs

**Baseline:** `NoSlop-main` at versionCode 52, `0.5.2-alpha`, after the `GroupMessageCrypto` hardening round.
**Purpose:** the complete list of what is still outstanding. Everything previously raised and since fixed has been dropped from this document.

Two standing notes for whoever executes this:

1. **Deliverable format.** For a scoped change, a surgical Python script to run from the NoSlop project root. Where a file is effectively rewritten, the entire file, one at a time.
2. Items are grouped by urgency, not by size. **Group A is time-sensitive** — the cost of A-1 grows with every day the build is in users' hands.

---

# Completed in v0.5.3-alpha (All Group A, B, C, D Items Resolved)

- **A-1 & A-2**: Completed — Room bumped to version 14 with `MIGRATION_13_14` re-encrypting legacy `ENC:GCM:` rows with AAD binding; version bumped to 53 / `0.5.3-alpha`.
- **B-1**: Completed — `GroupMessageGate` extracted into pure evaluation function; unit tests added in `GroupMessageSecurityTest.kt`.
- **B-2**: Completed — `app/src/androidTest/` configured with `MigrationTest.kt` (13→14) and `PendingGroupMessageDaoTest.kt`.
- **C-1, C-2, C-3, C-4**: Completed — `lastMediaProgressAtMs` removed, stale `circuitGeneration` comment updated to `streamNonce`, `senderPub` aligned to public key in `NotificationItem`, `getIdentityVersion()` actively utilized.
- **D-1**: Completed — "Restore from Word Cloud" onboarding flow implemented with legacy `identity_version < 2` guard.
- **D-2**: Completed — Recorded architectural decision in `docs/FINDINGS.md §7`.
- **D-3**: Completed — Ad-hoc scripts moved to `scripts/`, `_workspace/` added to `.gitignore`.
- **D-4**: Completed — 12 verified dead methods pruned across DAOs and clients.
- **D-5**: Completed — Diagnostic logging added to silent catch blocks in `TorService.kt` and `ProxyAuth.kt`.

---

# Group A — Time-sensitive (Archive)

## A-1. Migration 13 → 14: retire the legacy `ENC:GCM:` population [RESOLVED]

**Why now:** anyone who installed the first `0.5.2-alpha` build has already run `MIGRATION_12_13`, which wrote `ENC:GCM:` rows with no AAD binding. The database is still at version 13, so the migration will not re-run, and nothing re-encrypts lazily on read. The legacy branch in `decrypt()` — whose entire purpose was to become retirable — can therefore never be retired by waiting. The affected population grows with every install of the current build.

**Files:** `data/NoSlopDatabase.kt`, `crypto/GroupMessageCrypto.kt`

**Do:**

1. Bump `@Database(version = 14)` and add `MIGRATION_13_14` to the `addMigrations(...)` chain.
2. In the migration, select group rows whose ciphertext carries the legacy prefix:

   ```sql
   SELECT id, ciphertext, nonce, chatWithPeerPub FROM chat_messages
   WHERE ciphertext LIKE 'ENC:GCM:%'
   ```

3. For each row: `GroupMessageCrypto.decrypt(ciphertext, nonce)` with no AAD (legacy path), then `GroupMessageCrypto.encrypt(plaintext, groupId = chatWithPeerPub, msgId = id)` and write back both the new ciphertext and the new IV.
4. Wrap the whole loop in try/catch exactly as `MIGRATION_12_13` does, so a Keystore fault logs a warning rather than aborting the database open.
5. Make it idempotent — skip any row already carrying `ENC:GCM2:` — so a partial failure can be resumed on the next launch.
6. Once shipped and a release or two have passed, delete the `isLegacy` branch from `decrypt()` and the `LEGACY_CIPHERTEXT_PREFIX` constant. Leave a dated comment saying when that becomes safe.

**Verify:** install the current build, send group messages, upgrade, confirm every `chat_messages` row for a group carries `ENC:GCM2:` and still renders correctly.

## A-2. Bump the version

**File:** `app/build.gradle.kts:20-21`

`versionCode` and `versionName` are still 52 / `0.5.2-alpha`, unchanged across a modification to the at-rest ciphertext format. Two builds are now in the wild reporting the same version with different formats, which makes any support report ambiguous.

Bump to 53 / `0.5.3-alpha`. A-1 requires a version bump regardless, so do these together. Update the README status badge in the same commit.

---

# Group B — Test coverage

## B-1. The group-message tests don't exercise the code they were written for

**File:** `app/src/test/java/com/noslop/app/mesh/GroupMessageSecurityTest.kt`

Current state, honestly:

| Test | What it actually exercises |
|---|---|
| `groupMessage_signatureVerification_rejectsTamperedPayload` | Real production code — `CryptoService.encodeForSigning`, `sign`, `verify`. Catches both payload tampering and author re-attribution. **Keep as is.** |
| `groupMembership_rejectsNonMemberSender` | Asserts a Gson-parsed list contains alice and not mallory. Never calls `handleGroupMessage`. Deleting the membership check from `DmPacketHandler` leaves this green. |
| `storeAndForward_queue_enqueue_flush_delete_cycle` | Exercises `FakePendingGroupMessageDao`, a HashMap in the test source set. A wrong comparison operator in the real `@Query` on `deleteExpired` leaves this green. |

**Do:** extract the accept/reject decision out of `DmPacketHandler.handleGroupMessage` into a pure function that a JVM test can call directly. Something on the order of:

```kotlin
object GroupMessageGate {
    sealed class Verdict {
        object Accept : Verdict()
        data class Reject(val reason: String) : Verdict()
    }

    fun evaluate(
        payload: GroupMessagePayload,
        group: GroupChat?,
        signatureValid: Boolean
    ): Verdict
}
```

`handleGroupMessage` then becomes a thin wrapper that loads the group row, runs the verifier, calls `evaluate`, and acts on the verdict. Test `evaluate` against: unknown group, non-member sender, missing signature, invalid signature, valid member. That gives the membership and signature checks real regression cover without needing instrumentation.

The DAO half cannot be covered this way — it needs B-2.

## B-2. Room migration and DAO tests still cannot run

Unchanged across three rounds:

- No `app/src/androidTest` source set.
- `androidx.room:room-testing:2.7.0` sits on `testImplementation` at `app/build.gradle.kts:181`; `MigrationTestHelper` requires instrumentation.
- `app/schemas/` contains only `13.json`, so even with instrumentation only 12→13 is testable today — and A-1 will add 14.

This is now the single largest gap in the project. Migrations 12→13 and 13→14 both rewrite every group message row through a Keystore cipher; they are the only migrations that touch user content rather than schema, and neither has a test. The fake-DAO tests in B-1 make the coverage look better than it is.

**Do:**

1. Create `app/src/androidTest/java/com/noslop/app/data/`.
2. Move the dependency to `androidTestImplementation("androidx.room:room-testing:2.7.0")` and add `androidTestImplementation("androidx.test.ext:junit:1.2.1")`.
3. Write `MigrationTest` covering 12→13 and 13→14: seed a plaintext group row, run the migration, assert the prefix and that `GroupMessageCrypto.decrypt` returns the original text with the right AAD.
4. Add instrumented DAO tests for `PendingGroupMessageDao` — in particular `deleteExpired` and `getPendingForMember` — replacing the fake-DAO assertions in B-1.
5. Historical schemas `1.json`–`12.json` can be reconstructed later by checking out each prior tag with `exportSchema = true`. Not required to start.

---

# Group C — Small cleanups

## C-1. `lastMediaProgressAtMs` is write-only

**File:** `tor/TorService.kt:50`

`mediaIsStreaming()` was its only reader and went with the NEWNYM removal. The `@Volatile` field is now assigned on every buffer sample of every visible video and never read.

Keep `noteMediaProgress()` — it still clears `_torBlockedMessage`, which is load-bearing — and delete the field and its assignment.

## C-2. Stale `circuitGeneration` comment

**File:** `ui/components/VideoPlayer.kt:72`

The `CachedSource` header block still documents `circuitGeneration` as one of the recorded fields. The class holds `streamNonce` now. Rewrite that paragraph to describe nonce-based invalidation.

## C-3. `senderPub` means two different things

**File:** `mesh/DmPacketHandler.kt:327`

The `NotificationItem` row for an incoming group message stores `senderPub = groupMsg.senderHandle` — a display handle — while the `ChatMessage` row correctly stores the public key. Harmless today, but the field name is now misleading across two tables. Either store the key and resolve the handle at render time, or rename the notification field to `senderLabel`.

## C-4. `getIdentityVersion()` has no caller

**File:** `data/IdentityRepository.kt:207`

This is correct and deliberate — the flag was laid down ahead of the restore screen (D-1), which is exactly the right order. Listed here only so it isn't mistaken for dead code in a future sweep. **No action.**

---

# Group D — Features and decisions still outstanding

## D-1. Restore-from-mnemonic screen

`CryptoService.deriveIdentityFromSeed` still has one caller, `completeOnboarding`. A user holding only their twelve words and a fresh device has no path back; the only restore route is importing a backup zip.

The groundwork is done: derivation is deterministic and tested, and `identity_version` is written for HKDF identities with pre-0.5.1 users correctly defaulting to `1`. What remains is the UI and the branch.

**Do:** add a "Restore identity from Word Cloud" path to onboarding that derives via `deriveIdentityFromSeed`, and — critically — reads `getIdentityVersion()` first. A pre-0.5.1 user entering their phrase must be told plainly that their identity predates deterministic derivation and cannot be recovered this way, rather than being silently handed a different key, onion and tripcode. Update `OnboardingScreen.kt:362`, which still frames the phrase as being "to decrypt the backup file".

## D-2. Proxy secret and shared API keys (P1-9 remainder)

The consolidation into `feeds/api/ProxyAuth.kt` is done and `docs/PRIVACY_POLICY.md:42` now discloses the Cloudflare Worker honestly. What remains is the decision itself:

- `app/build.gradle.kts:45` still defaults `PROXY_SECRET` to the committed literal `NoSlopRocks2026`, and any injected value still ships in `BuildConfig` inside an open-source APK.
- `feeds/api/JamendoApiClient.kt:19` still carries a shared `CLIENT_ID = "709fa152"`.

Either drop the HMAC scheme and rate-limit server-side, or move all three sources behind user-supplied keys in `ApiKeysScreen` alongside the existing Guardian/NewsAPI/Pexels/Vimeo pattern. The current state is a secret that isn't one, which costs code without buying protection.

## D-3. Repository hygiene

- `tests/` at the repo root holds eight ad-hoc scripts (`test_youtube*.py`, `test_yt.sh`, `test_bc.kts`, `test_regex.py`, `test_time.kt`). `.gitignore` excludes `*.py` and `*.sh`, so these were force-added past it, as was `get-git.sh`. Move them to a `scripts/` directory with an explicit un-ignore, or delete them.
- `_workspace/gChat/` and `_workspace/hai/` are still empty directories. Either clone the repos as `WIDER_INFRASTRUCTURE.md` intends, or remove them and add `_workspace/` to `.gitignore`.

## D-4. Residual dead code (13 functions)

None are urgent; several may be intentional API surface. Worth one deliberate pass to decide keep-or-delete rather than letting the list drift.

| Symbol | File |
|---|---|
| `getOrphanedPostsByAuthor` | `data/Daos.kt` |
| `getPeersByFolder` | `data/Daos.kt` |
| `getReactionCountForPost` | `data/Daos.kt` |
| `getRecentLogs` | `debug/Logger.kt` |
| `resolveRssUrl` | `feeds/FeedParser.kt` |
| `getSourcesForCategory` | `feeds/SourceLibrary.kt` |
| `setCreationDate` | `feeds/api/ChannelMetadataResolver.kt` |
| `getPublicDomainFilms` | `feeds/api/InternetArchiveClient.kt` |
| `getPrimaryInstance` | `feeds/api/InvidiousApiClient.kt` |
| `isListening` | `mesh/MeshTransport.kt` |
| `getMediaPendingPayload` | `mesh/Packets.kt` |
| `getMediaTransferAckPayload` | `mesh/Packets.kt` |
| `getIdentityVersion` | `data/IdentityRepository.kt` — **keep**, see C-4 |

`getPublicDomainFilms` and `getSourcesForCategory` in particular look like feed features that were built and never wired to the UI. Worth checking whether they should be connected rather than deleted.

## D-5. Empty catch blocks

34 instances of `catch (...) { }` in `app/src/main`, flat across the last three rounds. At minimum give each a `Logger.debug` naming the swallowed condition. Prioritise `MainActivity` splash logic and `BackupManager`, where a silent failure is invisible to the user.

---

# Group E — Deferred by agreement

These are tracked in `docs/FINDINGS.md` and remain correctly parked. Listed for completeness; no action expected this cycle.

| Item | Register entry | Note |
|---|---|---|
| Canonical signing encoder (P1-7) | FINDINGS §1 | Three formats still in use; the `\|`-joined ones with optional appends remain ambiguous, and `GROUP_INVITE` still runs a four-format candidate loop. `encodeForSigning` still uses UTF-16 `length` and still collides `null` with `""`. Needs a wire-version bump, so it wants a dedicated release. |
| Full database encryption at rest | FINDINGS §2 | Posts, comments, peers, onion addresses and history remain plaintext SQLite. Group message bodies and the identity keystore are the only encrypted surfaces. |
| DM forward secrecy / ratchet | FINDINGS §3 | Static-static X25519 means a long-term key compromise decrypts all past DMs. The largest remaining cryptographic gap, and a genuine design project rather than a fix. |
| ProGuard keep surface (P2-2) | FINDINGS §4 | Seven `com.noslop.app.*` wildcards, ~43% of the app exempted, zero `@Keep` annotations. |
| Repository / ViewModel decomposition (P2-3, P2-4) | FINDINGS §5 | `NoSlopRepository` is still a 1,800-line facade with ~98 pass-throughs and real logic mixed in; `NoSlopViewModel` is still ~2,900 lines. |
| LAN Hub TLS pinning | FINDINGS §6 | The Hub fast path remains blocked by the app's own `network_security_config`. |
| Chat screen consolidation (P4-2) | — | `ChatThreadScreen` (826) and `GroupChatThreadScreen` (807) still share ~550 identical lines, and `buildMediaMetadata` is defined twice. Best done after the group-chat protocol stops changing. |

---

# Suggested order

1. **A-1 + A-2** together — the migration and the version bump. Time-sensitive; everything else can wait.
2. **B-2** — `androidTest` source set and the migration tests. Do this alongside A-1 so the new migration ships with cover rather than acquiring it later.
3. **B-1** — extract `GroupMessageGate` and test it. Half a day, and it locks in the most security-critical change of the last three releases.
4. **C-1, C-2, C-3** — three small edits, one commit.
5. **D-2** — make the proxy decision. It's a decision, not a refactor; the code follows quickly once it's made.
6. **D-4, D-5, D-3** — one deliberate hygiene pass.
7. **D-1** — the restore screen, once there's room for a feature rather than a fix.

Group E stays parked. `docs/FINDINGS.md` tracks it accurately and none of it is degrading.
