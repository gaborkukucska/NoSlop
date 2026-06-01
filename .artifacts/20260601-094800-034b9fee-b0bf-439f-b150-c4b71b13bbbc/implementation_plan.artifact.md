# Implementation Plan - Tor Fix and Contacts UI Enhancement

This plan addresses the connection issues between devices by fixing the Tor hidden service registration and enhances the Contacts/DM UI to align with the gChat experience.

## Goal
1. Fix Tor hidden service registration (Root cause of connection failure).
2. Fix "Tor disconnected" status in the app.
3. Enhance the DMs page to serve as a comprehensive Contacts and Messaging hub.
4. Improve database performance to prevent locking errors.

## Proposed Changes

### Tor Connectivity Fixes

#### [AndroidManifest.xml](file:///home/tom/NoSlop/app/src/main/AndroidManifest.xml)
- Correct the Tor service class name from `org.torproject.android.service.TorService` to `org.torproject.jni.TorService`.

#### [TorService.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/tor/TorService.kt)
- Add `writeTorrc` function to enable `ControlPort 9051` and `CookieAuthentication 0`.
- Call `writeTorrc` before starting the Tor service.
- Implement a `BroadcastReceiver` to listen for `org.torproject.android.intent.action.STATUS` to accurately track Tor readiness (when circuits are BUILT).
- Add more robust logging for bootstrap progress.

### UI Enhancements (Contacts & DMs)

#### [MainScreen.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/ui/MainScreen.kt)
- Update `DMsTab` to include:
    - "My Identity" card at the top for quick access to QR sharing.
    - "Add Contact" button (Scan QR) integrated into the header.
    - Separate sections for "Pending Requests" (untrusted peers) and "My Contacts" (trusted peers).
    - Add "Trust" and "Remove" actions to peer items.
    - Improve peer list item styling with tripcodes and status.

#### [NoSlopViewModel.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/ui/NoSlopViewModel.kt)
- Add `removePeer(peerPub: String)` function.
- Ensure `torReadyState` is updated from the new `BroadcastReceiver` logic in `TorService`.

### Database Optimization

#### [NoSlopDatabase.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/data/NoSlopDatabase.kt)
- Enable WAL (Write-Ahead Logging) mode to prevent SQLite locking errors during concurrent feed syncs and UI updates.

---

## Verification Plan

### Automated Tests
- No new automated tests are proposed as this is primarily an integration/UI task.

### Manual Verification
1. **Tor Status**:
   - Run the app and check if "Tor disconnected" changes to "Active Tor Proxy" after bootstrap.
   - Check logs for "Hidden service registered: xyz.onion".
2. **QR Pairing**:
   - Scan a peer QR code.
   - Verify the peer appears in the "Pending Requests" or "Contacts" list.
   - Verify the onion address is correctly received in the scan data.
3. **Messaging**:
   - Send a message to a paired peer.
   - Verify the message is stored and sent via `MeshTransport`.
4. **UI**:
   - Verify the new layout of `DMsTab`.
   - Test "Trust" and "Remove" actions.
5. **Database**:
   - Perform a manual "Refresh Feeds" while navigating the app to ensure no "Database locked" errors occur.
