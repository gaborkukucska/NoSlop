# NoSlop — Two-Device Test Protocol (v0.1)

## Devices
Device A: [your device]
Device B: [second device]
Both must have Orbot NOT installed — testing embedded Tor only.

## Prerequisites
- Both devices: NoSlop installed (debug APK)
- Both devices: connected to internet (mobile data or different WiFi networks — not LAN)
- adb connected to both for log streaming

## Step 1 — Fresh start
Uninstall and reinstall NoSlop on both devices to clear any stale state.

## Step 2 — Onboarding
Complete onboarding on both devices. Record:
- Device A handle + tripcode:
- Device B handle + tripcode:

## Step 3 — Confirm Tor ready
On both devices, open Debug screen. Confirm:
[ ] TorState = READY (green pill)
[ ] .onion address shown (non-empty, ends in .onion)
[ ] Port 9999 listening = true
Allow up to 90 seconds for Tor bootstrap.

## Step 4 — QR pair
On Device A: open QR Share sheet, show QR.
On Device B: scan QR from Device A.
Expected: Device A appears in Device B peer list within 10 seconds.
Expected: Device B receives USER_HANDSHAKE from Device A.
Record result: PASS / FAIL

## Step 5 — Gossip post
On Device A: tap "Send test gossip post" in Debug screen.
On Device B: wait up to 60 seconds.
Expected: test post appears in Device B feed.
Record result: PASS / FAIL
Note: if fail, check adb logcat -s GOSSIP MESH_TRANSPORT TOR on both devices.

## Step 6 — Direct message
On Device B: send a DM to Device A from the peer list.
On Device A: confirm DM received and decryptable.
Record result: PASS / FAIL

## Logs to capture on failure
adb logcat -s TOR MESH_TRANSPORT GOSSIP CRYPTO REPOSITORY IDENTITY_REPO
Save full log from both devices.
