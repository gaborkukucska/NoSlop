#!/usr/bin/env python3
import sys
import os

dry_run = "--dry-run" in sys.argv

def find_doc(filename):
    if os.path.exists(filename):
        return filename
    docs_path = os.path.join("docs", filename)
    if os.path.exists(docs_path):
        return docs_path
    raise FileNotFoundError(f"Cannot find {filename} or docs/{filename}")

def replace_exact(filepath, old_text, new_text):
    with open(filepath, "r", encoding="utf-8") as f:
        content = f.read()

    count = content.count(old_text)
    if count == 0:
        raise ValueError(f"Target snippet not found in {filepath}:\n{old_text[:120]}...")
    if count > 1:
        raise ValueError(f"Target snippet found {count} times (expected exactly 1) in {filepath}:\n{old_text[:120]}...")

    updated = content.replace(old_text, new_text)
    if dry_run:
        print(f"[DRY-RUN] Would update {filepath}")
    else:
        with open(filepath, "w", encoding="utf-8") as f:
            f.write(updated)
        print(f"[UPDATED] {filepath}")

def replace_between(filepath, start_anchor, end_anchor, replacement):
    with open(filepath, "r", encoding="utf-8") as f:
        content = f.read()

    idx1 = content.find(start_anchor)
    if idx1 == -1:
        raise ValueError(f"Start anchor not found in {filepath}:\n{start_anchor}")
    idx2 = content.find(end_anchor, idx1 + len(start_anchor))
    if idx2 == -1:
        raise ValueError(f"End anchor not found in {filepath}:\n{end_anchor}")

    if content.count(start_anchor) > 1:
        raise ValueError(f"Start anchor appears multiple times in {filepath}:\n{start_anchor}")

    updated = content[:idx1 + len(start_anchor)] + replacement + content[idx2:]
    if dry_run:
        print(f"[DRY-RUN] Would update between anchors in {filepath}")
    else:
        with open(filepath, "w", encoding="utf-8") as f:
            f.write(updated)
        print(f"[UPDATED] {filepath}")

def main():
    print(f"Applying documentation updates (dry-run={dry_run})...\n")

    project_status_file = find_doc("PROJECT_STATUS.md")
    tech_ref_file = find_doc("TECHNICAL_REFERENCE.md")
    wire_ref_file = find_doc("WIRE_PROTOCOL_REFERENCE.md")

    # 1. Update PROJECT_STATUS.md
    replace_exact(
        project_status_file,
        """# Project Status - NoSlop

## Completed Changes (2026-09-09)""",
        """# Project Status - NoSlop

## Completed Changes (2026-09-10) — Peer Mesh Content In Modals, Creator Studio Severable ID, Tor 40% Recovery & Dual-Identity Broadcasts

* **Peer Mesh Content List in User Info Modals (`PeerItem.kt`, `FeedCard.kt`, `DMsTab.kt`)**:
  * Implemented `PeerMeshContentList`: User Info / Contact Card modals across Feed and DMs now render a scrollable list of the user's mesh broadcasts with rich media thumbnails (Base64 bitmaps, Coil web/clearnet images, or category type icons), timestamps, and two-line body excerpts.
  * Added full Markdown rendering to broadcast body excerpts in `PeerMeshContentList` using `MarkdownUtils.parseMarkdown(...)`.
* **Direct Filtered Author Feed Navigation & Scroll Positioning (`UnifiedFeedTab.kt`, `NoSlopViewModel.kt`)**:
  * Extracted an O(1) `Author:$authorPub` fast-path loader at the top of `loadMoreFeedItems()` in `NoSlopViewModel.kt` (matching `History` and `Saved`), bypassing discovery deduplication and viewed-ID purges so all broadcasts by the author load chronologically.
  * Tapping any broadcast in `PeerMeshContentList` dismisses the modal, navigates to the Feed tab, populates the author's feed, and scrolls directly to the tapped post index via `_restoreScrollPositionEvent`.
  * Displayed active filter label as `@handle` with 1-tap dismissal restoring the default Live Feed, and guarded `forceScrollToTop` against clobbering author slide positioning.
* **Creator Mode Severable Identity Architecture & Studio Sharing (`CreatorStudioTab.kt`, `SettingsTab.kt`, `QRShareSheet.kt`)**:
  * Added a dedicated **Creator ID 🪪** action to `CreatorStudioTab.kt`. Creators can now inspect, copy, and share their severable burnable identity (`burnableKeys`) via `QRShareSheet` without exposing their personal primary identity.
  * Un-nested Creator Node controls from `if (isDiscoverableEnabled)` in `SettingsTab.kt`, making Creator Node settings independently accessible when Discoverability is turned off.
  * Added a confirmation warning dialog before disabling Creator Mode, educating that peers who connected via the secondary Creator ID will permanently lose connectivity.
  * Preserved Creator Identity when Discoverability is toggled off in `NoSlopViewModel.kt`, suppressing premature `USER_EXIT` broadcasts while Creator Mode remains active.
* **Built-in Official NoSlop Creator Node & In-App Browser Reader (`NoSlopRepository.kt`, `DMsTab.kt`, `PeerItem.kt`, `FeedCard.kt`)**:
  * Seeded the official NoSlop creator node into `PeerDao` (`ensureDefaultDiscoverableNode`) using actual cryptographic keys and dynamically derived tripcodes (`fnozdo...`).
  * Filtered out the creator's own node from their own "DISCOVERABLE NODES" list.
  * Removed hardcoded Stripe donation links from default seeding (`fundMeLink = null`), ensuring donation links populate dynamically from authentic node broadcasts.
  * Added a clickable `$` coin badge and donation link across User Info modals that opens the donation URL in the in-app `ArticleWebViewDialog`.
* **Tor Daemon Recovery & 40% (`loading_keys`) Hang Fix (`TorService.kt`)**:
  * Implemented `stopTor(context)` sending `SIGNAL HALT` to the Tor control channel (terminating the native C `libtor.so` binary) and `ACTION_STOP` intent to the Android service, verifying socket release on port 9050 before restarting.
  * Added reliable cache clearing on `forceRestart`, wiping stale consensus and authority certificates from `app_TorService/data/`.
  * Appended `ClientPreferIPv6ORPort 0` to `torrc` to prioritize IPv4 directory authority and relay connections on mobile carriers.
* **Handshake Signature Verification Parity & Creator Auto-Accept (`HandshakePacketHandler.kt`)**:
  * Upgraded `handleConnectionRequest` and `handleUserHandshake` in `HandshakePacketHandler.kt` to dual-mode signature verification (supporting length-prefixed `CryptoService.encodeForSigning` alongside legacy pipe fallbacks).
  * Aligned the creator node setting key to `"is_creator_enabled"`, restoring automated handshake acceptance for creator nodes.
  * Fixed `handleAnnounceDiscoverable` regression that improperly flipped temporary contacts to permanent upon receiving presence heartbeats.
* **Dual-Identity Targeted Broadcast Routing (`MeshSocialRepository.kt`, `GossipService.kt`, `UnifiedFeedTab.kt`)**:
  * Configured `composeAndBroadcastPost` to author and sign posts with the Creator identity (`getBurnableIdentity()`) when Creator Mode is active.
  * Added recipient-aware `senderId` stamping in `GossipService.broadcast`, stamping `burnable.publicKeyB64` for followers and `main.publicKeyB64` for personal friends so broadcasts pass firewalls for both peer types.
  * Updated `isOwnPost` in `UnifiedFeedTab.kt` to recognize both main and burnable identities under "My Content".

## Completed Changes (2026-09-09)"""
    )

    # 2. Update TECHNICAL_REFERENCE.md
    replace_between(
        tech_ref_file,
        """## 21. Non-Admin Group Chat Parity, Tri-Channel Transport & Video Buffer Resilience (2026-09-09)""",
        """---
---""",
        """## 21. Non-Admin Group Chat Parity, Tri-Channel Transport & Video Buffer Resilience (2026-09-09)

(See previous section for group chat, tri-channel transport isolation, and video buffering details.)

## 22. Peer Mesh Content Lists, Severable Creator Architecture & Tor Daemon Teardown (2026-09-10)

### 22.1 Peer Mesh Content List & Direct Filtered Feed Navigation
Previously, User Info modals in `FullScreenMeshCardV2` only displayed a minimal text list for temporary contacts, while `ContactCardDialog` in `PeerItem.kt` and the Discoverable Node modal in `DMsTab.kt` lacked mesh post listings entirely.
* **`PeerMeshContentList` Composable**: Renders a scrollable list of an author's mesh broadcasts with 52dp rounded media thumbnails (Base64 bitmaps, Coil web/clearnet URLs, or media icons), timestamps, and two-line body excerpts parsed with `MarkdownUtils.parseMarkdown(...)`.
* **Author Feed Fast-Path Loader**: In `NoSlopViewModel.kt`, added a dedicated `Author:$authorPub` fast-path at the top of `loadMoreFeedItems()` (matching `History` and `Saved`), populating all broadcasts by that author from `allMeshes` chronologically without being pruned by discovery deduplication or `cachedViewedIds`.
* **Scroll Positioning & Guarding**: Tapping any post in `PeerMeshContentList` switches `selectedTab = 0`, sets `filterMode = "Author:$authorPub"`, and immediately scrolls to the tapped post index via `_restoreScrollPositionEvent`. In `UnifiedFeedTab.kt`, `forceScrollToTop` is guarded against `filterMode.startsWith("Author:")` to prevent scroll collisions.

### 22.2 Creator Mode Severable Identity Architecture
To maintain sovereign identity separation between personal direct messages and public mesh broadcasts:
* **Creator ID Sharing in Studio (`CreatorStudioTab.kt`)**: Added a **Creator ID 🪪** action to Creator Studio. Tapping it opens `QRShareSheet` with the node's `burnableIdentity` (the severable secondary Tor `.onion` address) and `isCreator = true` in the QR JSON payload, ensuring followers connect to the secondary identity.
* **Decoupled Creator Controls & Disconnect Warning (`SettingsTab.kt`)**: Un-nested the Creator Node switch from Discoverability so it remains accessible at all times. Toggling Creator Mode OFF triggers an `AlertDialog` warning that all connections established via the Creator ID will be lost.
* **Discoverability Deactivation Guard**: `setDiscoverableEnabled(false)` in `NoSlopViewModel.kt` only sends `USER_EXIT` if Creator Mode is also disabled, keeping the Creator Identity active on Tor.
* **Dual-Identity Targeted Broadcast Routing (`MeshSocialRepository.kt`, `GossipService.kt`)**: When `is_creator_enabled == true`, `composeAndBroadcastPost` authors posts using `getBurnableIdentity()`. During `GossipService.broadcast()`, packets are dynamically stamped with `senderId = burnable.publicKeyB64` for followers (`contact_identity_$peerPub == "burnable"`) and `main.publicKeyB64` for personal friends, allowing both peer types to pass firewall validation.

### 22.3 Official NoSlop Creator Node Seeding & In-App Browser Reader
* In `NoSlopRepository.ensureDefaultDiscoverableNode()`, seeded the official NoSlop creator node into `PeerDao` using `OFFICIAL_CREATOR_PUBKEY` and `OFFICIAL_CREATOR_ONION` (`fnozdo...`), with dynamic tripcode derivation.
* Excluded the creator's own device from rendering itself in "DISCOVERABLE NODES".
* Cleared hardcoded Stripe links (`fundMeLink = null`) so donation links are populated dynamically from live `ANNOUNCE_DISCOVERABLE` broadcasts.
* Added a clickable `$` coin badge and donation link across User Info modals opening `ArticleWebViewDialog`.

### 22.4 Tor Daemon Teardown & 40% (`loading_keys`) Hang Fix (`TorService.kt`)
* **Process Teardown (`stopTor`)**: Sends `SIGNAL HALT` to the Tor control channel, issues `ACTION_STOP` intent (`"org.torproject.android.intent.action.STOP"`) to the Android service, and polls port 9050 until closed before executing restarts.
* **Cache Purge on Force Restart**: Once the Tor process is terminated, safely purges `app_TorService/data/` (clearing corrupted `cached-consensus` and authority certs) so Tor performs a clean bootstrap.
* **IPv4 Directory Authority Preference**: Added `ClientPreferIPv6ORPort 0` to `torrc` to prevent authority certificate drops over IPv6.

### 22.5 Handshake Signature Verification Alignment & Creator Auto-Accept
* Updated `handleConnectionRequest` and `handleUserHandshake` in `HandshakePacketHandler.kt` with dual-mode signature verification, accepting length-prefixed `CryptoService.encodeForSigning` alongside legacy pipe payloads.
* Aligned creator setting lookup to `"is_creator_enabled"` so incoming connection requests are automatically accepted on creator nodes.
* Fixed `handleAnnounceDiscoverable` to preserve `isTemporary = peer.isTemporary`, preventing temporary contacts from flipping to permanent upon presence heartbeats.

"""
    )

    # 3. Update WIRE_PROTOCOL_REFERENCE.md
    replace_exact(
        wire_ref_file,
        """- Rows 3–4 (`CONNECTION_REQUEST`/`USER_HANDSHAKE`) verify the signature
  on receipt covering `fromUserId|fromUsername|fromHomeNode|timestamp`
  (+ optional `authorAvatarB64` and `bio`). The verification gap has been closed.""",
        """- Rows 3–4 (`CONNECTION_REQUEST`/`USER_HANDSHAKE`) verify signatures
  on receipt using dual-mode verification: length-prefixed `CryptoService.encodeForSigning`
  as primary, with fallback to legacy pipe-delimited strings (`fromUserId|fromUsername|fromHomeNode|timestamp`
  + optional `authorAvatarB64` and `bio`).
- On multi-identity Creator Nodes, outbound `POST` broadcasts dynamically stamp `senderId`
  with either the burnable identity public key (for temporary follower contacts) or main identity public key
  (for personal contacts), ensuring recipient firewalls accept the packet."""
    )

    print("\nAll project documentation successfully updated!")

if __name__ == "__main__":
    main()
