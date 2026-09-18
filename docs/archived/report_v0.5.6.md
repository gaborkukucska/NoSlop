# NoSlop — Report, v0.5.6-alpha

**Baseline:** `NoSlop-main` at versionCode 56, `0.5.6-alpha`.
**Previous:** versionCode 52, `0.5.2-alpha`.
**Scope:** verification of all open TODO items, plus assessment of the additional work shipped between builds.

---

## Summary

Every substantive item from the previous TODO list is either closed or formally deferred into `docs/FINDINGS.md`. The test suite grew from 15 files to 23 — the new repository-layer tests are the right kind of coverage to have. The version bump accounts for the additional feature work, and the `PROJECT_STATUS.md` changelog is detailed and accurate.

Four small items remain and are described below. None are urgent.

---

# Closed

## Group A — Migration and version

**A-1 — Migration 13→14 ✅**

The migration selects all four required columns (`id, ciphertext, nonce, chatWithPeerPub`), decrypts legacy `ENC:GCM:` rows without AAD, re-encrypts with `ENC:GCM2:` and a fresh IV plus `groupId|msgId` AAD binding, is idempotent via the `CIPHERTEXT_PREFIX_V2` prefix check, and wraps the loop in a logged try/catch so a Keystore fault during migration does not abort the database open. `14.json` is committed.

**A-2 — Version bump ✅**

Jumped to 56 / `0.5.6-alpha`, accounting for the additional feature work. No two builds now share a version identifier.

## Group B — Tests

**B-1 — GroupMessageGate extraction ✅**

`GroupMessageGate.evaluate()` is a pure function taking payload, group row, sender key and signature-validity flag. `DmPacketHandler.handleGroupMessage` calls it and acts on the sealed `Verdict`. The three tests in `GroupMessageSecurityTest` now call real production code directly — membership rejection and signature rejection are actually tested, not approximated with fakes.

**B-2 — androidTest infrastructure ✅**

`app/src/androidTest/` exists with two instrumented tests:

- `MigrationTest.migrate13To14_reEncryptsLegacyGroupMessagesWithAad` seeds a real legacy row, runs the migration via `MigrationTestHelper`, and asserts the `ENC:GCM2:` prefix, a refreshed nonce, correct decryption with matching AAD, and decryption failure with mismatched AAD.
- `PendingGroupMessageDaoTest` exercises `getPendingForMember`, `delete`, and `deleteExpired` against an in-memory Room database, replacing the HashMap fake that was previously the only coverage.

`androidx.room:room-testing` is now on both `testImplementation` and `androidTestImplementation`.

## Group C — Small cleanups

All three closed.

- **C-1** — `lastMediaProgressAtMs` and its assignment are gone from `TorService.kt`. `noteMediaProgress()` is kept for the banner-clear side-effect.
- **C-2** — `CachedSource` header block now documents `streamNonce` and nonce-based circuit invalidation. No mention of `circuitGeneration`.
- **C-3** — `senderPub = groupMsg.senderHandle` is gone from the `NotificationItem` path.

## Group D — Features and decisions

**D-2 — Proxy secret ✅ (formally deferred)**

Added as `docs/FINDINGS.md §7`. `PROXY_SECRET` still defaults to the committed literal and `JamendoApiClient.CLIENT_ID` is unchanged, but both are now tracked in the architectural register rather than left as undocumented gaps. Correct place for it.

**D-3 — Repository hygiene ✅**

`tests/` removed. Files moved to `scripts/` with `.gitignore` entries `!scripts/*.py`, `!scripts/*.sh`, `!scripts/*.kts`. `get-git.sh` removed from repo root.

**D-4 — Dead code reduced**

Down from 13 functions to 4. `editMeshPost` is live and ships with the broadcast editing feature. `getPublicDomainFilms` and `getSourcesForCategory` are wired. Remaining four are in §Remaining below.

**D-5 — Empty catches**

36, up from 34 due to new code. The new catches are logged, which is acceptable. No regression.

---

## Additional work shipped

This build contains significant feature work beyond the fixes. Worth noting:

**Loopback proxy client isolation.** `HttpClientProvider.loopbackClient` uses `Proxy.NO_PROXY` without Tor interceptors. ExoPlayer and `PreloadManager` use it for `127.0.0.1`, so the local `MediaProxyService` is never accidentally routed into Tor's SOCKS daemon. Clean fix for a subtle routing hazard.

**Mesh broadcast editing.** `EDIT_POST` packets are signed with the matching author keypair (main or burnable), gossiped at TTL 6 for public posts, verified in `PostPacketHandler`, and applied to Room. Edit affordance is gated to `isMyPost` on the card. `updatePostContent` in `Daos.kt` was superseded by the richer `updatePostDetails` — the old function is now dead (see below).

**Playback-gated seen tracking.** Mesh posts with media are no longer marked viewed or swiped until the file is on disk and playback has started. `playedMeshPostIds` is a `ConcurrentHashMap`-backed set recorded on `onRenderedFirstFrame`. This prevents unread broadcasts from being quietly buried.

**Splash buffer readiness.** `awaitPlayerReady` holds the splash curtain until ExoPlayer reaches `STATE_READY`, replacing the old 3-second timeout. `waitForPreload` is now superseded and dead.

**Test coverage.** Eight new test files: `PostPacketHandlerTest`, `MnemonicGeneratorTest`, `BackupManagerTest`, `SettingsRepositoryTest`, `PreferencesRepositoryTest`, `EngagementRepositoryTest`, `MeshSocialRepositoryTest`, `FeedRepositoryTest`. Total: 23 test files, 2,153 lines.

---

# Remaining

## 1. Four dead functions

All safe to delete in one commit.

| Function | File | Note |
|---|---|---|
| `waitForPreload` | `ui/PreloadManager.kt:135` | Superseded by `awaitPlayerReady`. `MainActivity` already calls the new one. |
| `updatePostContent` | `data/Daos.kt:174` | Superseded by `updatePostDetails`, called by `PostPacketHandler` and `MeshSocialRepository`. Old narrow signature has no callers. |
| `hasMeshMediaStartedPlaying` | `ui/NoSlopViewModel.kt:2420` | `playedMeshPostIds.contains()` is called directly at lines 2252 and 2284 rather than through this function. Never called outside the VM. Delete, or inline. |
| `restoreIdentityFromWordCloud` | `ui/NoSlopViewModel.kt:1907` | Fully implemented and correct, but has no UI caller. See §2 below. |

## 2. Restore-from-mnemonic needs a UI entry point

`restoreIdentityFromWordCloud` in the ViewModel is complete and correct, including the version guard that blocks pre-0.5.1 users from silently receiving a wrong identity:

```kotlin
if (localIdentity != null && currentVersion < 2) {
    onError("Existing identity on this device is legacy (pre-v0.5.1) ...")
    return@launch
}
```

What is missing is a screen to call it. `Step1Welcome` has a "Restore from Backup Archive" path that decrypts a backup zip using the mnemonic as a passphrase — but no "Restore identity from Word Cloud alone" path, which is what a user on a fresh device with no backup file needs.

Add a second button or tab to `Step1Welcome` that collects handle and mnemonic, calls `viewModel.restoreIdentityFromWordCloud(handle, mnemonic, onSuccess, onError)`, and handles both callbacks. The copy should state upfront that this path works only for identities created in v0.5.1 or later — the ViewModel already enforces this; the UI should say so before the user types twelve words. Once wired, the function exits the dead-code list and D-1 is fully closed.

## 3. Schedule retirement of the `ENC:GCM:` legacy branch

The legacy branch in `GroupMessageCrypto.decrypt()` is correct to keep for now — a failed 13→14 migration leaves legacy rows on disk, and those rows need to be readable. After `0.5.6-alpha` has been in the field for a release or two, a 14→15 migration can assert no `ENC:GCM:` rows remain, and `LEGACY_CIPHERTEXT_PREFIX` plus its `isLegacy` branch can be deleted.

Add a dated comment in `GroupMessageCrypto.kt` now so the intention is on record and the cleanup doesn't get forgotten.

---

# Formally deferred (FINDINGS.md)

Tracked accurately in `docs/FINDINGS.md`. No action this cycle.

| Finding | Register |
|---|---|
| Canonical signing encoder — three formats, ambiguous `\|`-join, UTF-16 length | §1 |
| Full database encryption at rest — posts, comments, peers, history | §2 |
| DM forward secrecy / ratchet | §3 |
| ProGuard keep surface — 43% exempted, no `@Keep` annotations | §4 |
| Repository / ViewModel decomposition | §5 |
| LAN Hub TLS pinning | §6 |
| Proxy secret and shared API keys | §7 |

---

# Suggested order

1. **§2** — wire the restore screen. The ViewModel function is already written; this is only a `Step1Welcome` UI change.
2. **§1** — delete the four dead functions in one commit.
3. **§3** — add the dated retirement comment to `GroupMessageCrypto.kt`, then retire the legacy branch in a future release once the population has had time to migrate.

Items in FINDINGS.md stay parked. None are getting worse, and several (§1, §5) want a dedicated release rather than a patch.
