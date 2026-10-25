# NoSlop — Report, v0.5.8-alpha

**Baseline:** `NoSlop-main` at versionCode 58, `0.5.8-alpha`.
**Previous:** versionCode 57, `0.5.7-alpha`.
**Scope:** Verification of decentralized group directory synchronization, mesh node blacklisting and trust firewall enforcement, notification invite-to-chat single-tap UX, ProGuard R8 reflection preservation, and localization alignment.

---

## Summary

Release `v0.5.8-alpha` delivers significant enhancements to mesh networking privacy, decentralized group chat reliability, node moderation, and build minification safety.

1. **Decentralized Group Member Directory Synchronization**: Groups now exchange member directory descriptors (`GroupMemberInfo`) containing onion addresses and X25519 public keys without exposing or forcing members to become 1:1 trusted DM contacts.
2. **Mesh Node Blacklisting & Firewall Enforcement**: Users can blacklist malicious or spammy nodes directly from feed cards or contact dialogs. The gossip firewall drops all packets originating from banned nodes before deduplication or routing.
3. **Notification UX & Group Navigation Parity**: Group invite notifications now accept and transition to an interactive `"Joined • Open Chat"` state in a single tap, cleanly resolving route UUID truncation.
4. **ProGuard R8 Reflection Hardening**: All payload models across `Packets.kt` and Room entities carry `@Keep` annotations, and `proguard-rules.pro` retains generic type signatures for Gson `TypeToken` deserialization.
5. **Full Localization Parity**: English (`content_en.json`) and Hungarian (`content_hu.json`) language dictionaries are 100% synchronized with all newly introduced UI labels and dialog prompts.

---

## Completed Implementations in v0.5.8-alpha

### 1. Decentralized Group Directory Sync (`Packets.kt`, `NoSlopRepository.kt`, `HandshakePacketHandler.kt`)
* Added `GroupMemberInfo(handle, encPublicKey, onionAddress)` mapped in `GroupInvitePayload`, `GroupUpdatePayload`, and `GroupSyncPayload`.
* `syncMemberPeers` records member descriptors in `peerDao` as non-contact group participants (`isTrusted = false`), eliminating ghost pending connection requests while enabling pairwise X25519 E2EE messaging across the group.
* `requestGroupCatchup` (`GROUP_QUERY` / `GROUP_SYNC`) automatically synchronizes missing member descriptors and state updates.
* Group reactions (`CHAT_REACTION` with `groupId`) are gossiped to all group members across the mesh.

### 2. Mesh Node Blacklist & Firewall Dropping (`PreferencesRepository.kt`, `GossipService.kt`, `ContentPreferencesScreen.kt`)
* Added `BannedNode` persistence in `PreferencesRepository` (`app_settings["banned_nodes"]`).
* `GossipService.processIncoming` evaluates `repo.isNodeBanned(senderId)` and drops packets from banned nodes at step 1.
* Added "Ban Node 🚫" buttons in `PeerItem.kt` and `FeedCard.kt`, and an expandable management card in Content Preferences.
* Self-heals upon authentic `USER_EXIT` or `PEER_REMOVED` packet receipt.

### 3. Notification Invite-to-Chat Flow (`NotificationsScreen.kt`, `UnifiedFeedTab.kt`)
* Accepting group invites purges invite notifications and transitions the card into `"Joined • Open Chat"`.
* Fixed regex route parsing to prevent truncating UUID hyphens.
* Added fallback loading UI in `DMsTab.kt` to prevent empty screen lockouts during group loading.

### 4. ProGuard & Build Minification Hardening (`proguard-rules.pro`, `Packets.kt`)
* Annotated all packet models and Room entities with `@Keep`.
* Added `-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*` to protect Gson reflection.

---

## Verification & Status

* **Unit Tests**: All unit tests passing (`./gradlew testDebugUnitTest`).
* **Localization**: `content_en.json` and `content_hu.json` verified and aligned with Compose UI strings.
* **Documentation**: `README.md`, `PROJECT_STATUS.md`, `TECHNICAL_REFERENCE.md`, `WIRE_PROTOCOL_REFERENCE.md`, and `FINDINGS.md` updated.
