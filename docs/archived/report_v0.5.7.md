# NoSlop — Report, v0.5.7-alpha

**Baseline:** `NoSlop-main` at versionCode 57, `0.5.7-alpha`.
**Previous:** versionCode 56, `0.5.6-alpha`.
**Scope:** Verification of all remaining items from `report_v0.5.6.md`, evaluation of the sovereign identity recovery model, and review of the tiered backup and proactive advisory architecture.

---

## Summary

All remaining tasks from `report_v0.5.6.md` have been resolved. In response to the architectural assessment that serverless peer-to-peer identities cannot and should not be recovered through raw mnemonic phrases alone (as mnemonics cannot recover pairwise contact handshakes, group states, secondary creator keys, or local preferences), the recovery architecture was pivoted to **authenticated, tiered encrypted archives** with automated lifecycle prompts.

All dead code identified in the prior audit has been pruned, unit test doubles (`FakeDaos.kt`) were brought into strict interface alignment, and all unit tests pass.

---

# Closed Items

## 1. Dead Code Elimination ✅
All four dead functions identified in `report_v0.5.6.md` were cleanly deleted:
* `waitForPreload` in `ui/PreloadManager.kt`: Superseded by `awaitPlayerReady`.
* `updatePostContent` in `data/Daos.kt`: Superseded by `updatePostDetails`.
* `hasMeshMediaStartedPlaying` in `ui/NoSlopViewModel.kt`: Inlined directly to `playedMeshPostIds.contains()`.
* `restoreIdentityFromWordCloud` in `ui/NoSlopViewModel.kt`: Pruned in favor of sovereign backup archives.

## 2. Sovereign Identity Recovery & Tiered Backups ✅
Rather than promoting an incomplete word-cloud-only recovery path that silently leaves behind contacts and secondary keys, NoSlop now centers identity recovery on **authenticated AES-256-GCM ZIP archives**:
* **`BackupMediaOption.NONE` (IDs & Keys Only):** Generates an ultra-lightweight (~100 KB) encrypted archive containing the complete Room database (`mesh.db`), sovereign identity JSON (primary and creator/burnable keypairs), API keys, and preferences, bypassing media storage entirely. Allows instant backup and recovery of all contacts, groups, and settings.
* **`BackupMediaOption.OWNED_ONLY` (Full Backup, Owned Media Included):** Filters media to bundle only files authored by the local node's primary or creator identities and `.mine` files, excluding heavy multi-gigabyte peer media caches.
* **Proactive Advisory Prompts:** `NoSlopViewModel.BackupPromptReason` triggers an advisory modal educating users on the serverless nature of the mesh upon onboarding completion, discoverability toggling, creator mode activation, and creator identity burning.

## 3. Retirement Schedule for Legacy `ENC:GCM:` Branch ✅
A dated comment was stamped in `GroupMessageCrypto.kt` scheduling the permanent removal of `LEGACY_CIPHERTEXT_PREFIX` and unauthenticated decryption fallback for Room migration 14→15 (target: v0.6.0).

## 4. Test Suite Alignment ✅
`FakeDaos.kt` was updated to implement `getAllReactionsList()`, `getAllVotedPostIds()`, and `updatePostDetails(...)`, restoring 100% pass rate across the unit test suite (`./gradlew testDebugUnitTest`).

---

# Open Items in `docs/FINDINGS.md`

| Finding | Register | Priority | Note |
|---|---|---|---|
| Canonical signing encoder — three formats, ambiguous `|`-join, UTF-16 length | §1 | Medium | Parked for dedicated protocol bump (sigVersion 2) |
| Full database encryption at rest — SQLCipher | §2 | Low | Post-v0.5 release |
| DM forward secrecy / Double Ratchet | §3 | Medium | Planned post-v0.5 |
| ProGuard keep surface — package-level wildcards | §4 | Low | Can replace with `@Keep` annotations |
| Repository / ViewModel decomposition | §5 | Medium | Large refactor |
| LAN Hub TLS pinning | §6 | Low | HTTPS/TLS fast-path |
| User-configurable proxy endpoint & API keys | §7 | Low | Add custom endpoint in `ApiKeysScreen` |

---

# Suggested Next Steps

1. **Test on Physical Device**: Test the new backup export options and advisory popups on an actual handset or emulator to verify file creation via Android Storage Access Framework.
2. **Next Architectural Slice**:
   * **Option A**: Address **FINDINGS §4 (ProGuard Keep Surface)** — Add `@Keep` annotations to DTO models in `Packets.kt`, `Entities.kt`, and `UserProfile.kt` to allow tightening `proguard-rules.pro`.
   * **Option B**: Address **FINDINGS §7 (Custom Proxy & Keys)** — Add user-configurable Worker proxy URL input and custom Jamendo API key in `ApiKeysScreen.kt`.\n