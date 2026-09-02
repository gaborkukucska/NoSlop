# NoSlop <-> HAI-Net Hub Integration Plan

## Context & Architecture
NoSlop is transitioning from a standalone mesh node into an **Active-Passive Client** for a user's HAI-Net Home Hub. 
- **The Home Hub (Rust)** is the `MASTER` node. It runs 24/7, holds the canonical mesh database, processes AI tasks, and maintains the primary public Tor `.onion` address.
- **NoSlop (Android)** is the `CLIENT`. It uses the *exact same Ed25519 Identity Key* as the Hub (Identity Clone model). It acts as a remote control, communicating with the Hub via a private API `.onion` or local IP.
- **Failover:** If the Hub goes offline, NoSlop automatically spins up its embedded `TorService.kt` to reclaim the public `.onion` address and keep the user online, later syncing data back to the Hub.

**LLM INSTRUCTION:** When implementing these phases, do NOT leave placeholder, mock, or simulated code. All implementations must be fully functional. Ensure comments are added and `PROJECT_STATUS.md` is updated upon completion.

---

### Phase 1: Mobile-Assisted Hub Installer & Authentication ✅ FULLY OPERATIONAL

**Goal:** Allow users to deploy `hainet-seed` to their Home Hub hardware directly from NoSlop without using a terminal.

**Status:** Fully operational. The user can deploy HAI-Net from the NoSlop app's HUBs tab over SSH.

1. **Setup UI (`ui/tabs/HubSetupScreen.kt`)** ✅
   - Utilizes `HubDiscoveryService.kt` (Android `NsdManager`) to automatically scan the local network for SSH services (`_ssh._tcp`). Discovered hubs appear as tap-able cards that auto-fill the IP address.
   - Wizard UI utilizes mDNS and Active Subnet Scanning to auto-detect SSH-enabled devices across all local interfaces., SSH Username, SSH Password, Shared Media Folder path (defaults to `~/.hainet/storage`).
   - Deploy button pulls the user's local `IdentityKeys` from `NoSlopViewModel.localKeys` automatically — no manual identity export needed.
   - Upon success, the UI transitions from Setup Wizard to a "Hub Deployed" control panel with status display and a "Reset Hub Connection" button.

2. **SSH Integration (`net/SshDeployer.kt`)** ✅
   - Uses JSch (`com.jcraft.jsch`) for SSH connectivity.
   - Constructs a complete `hub_config.json` via `org.json.JSONObject` containing:
     - `shared_folder` — user-specified media folder path
     - `identity` — full Identity Clone payload (see below)
   - Deployment sequence:
     ```
     git clone https://github.com/gaborkukucska/hai.git || (cd hai && git pull)
     cd hai && echo '<config_json>' > hub_config.json
     cd hai && cargo run --package hainet-seed --bin hainet-seed install -- --config hub_config.json
     cd hai && rm -f hub_config.json
     ```

3. **Identity Clone Injection** ✅
   - The `identity` block in `hub_config.json` contains all 6 components of `CryptoService.IdentityKeys`:
     ```json
     {
       "identity": {
         "public_key": "<Ed25519 pub, Base64 X.509>",
         "private_key": "<Ed25519 priv, Base64 PKCS#8>",
         "enc_public_key": "<X25519 pub, Base64>",
         "enc_private_key": "<X25519 priv, Base64>",
         "onion_address": "<56-char .onion>",
         "display_name": "<handle.tripcode>"
       }
     }
     ```
   - On the Hub side (`hainet-seed`), these are written to `~/.hainet/identity/` with `chmod 600` per file and `chmod 700` on the directory.
   - The Hub derives the same `.onion` address, allowing it to maintain the user's mesh presence 24/7.

4. **HUBs Tab (`ui/HaiNetTab.kt`)** ✅
   - Now passes `viewModel` to `HubSetupScreen` and acts as the primary entry point for infrastructure management.
   - Deployment state persisted via `AppSettingDao` key `hub_deployment_status`.

### Phase 2: Active-Passive Tor Identity & Synchronization ✅ FULLY OPERATIONAL
**Goal:** Implement the "Double Setup" failover networking model and Smart Firewall Relay.

**Status:** NoSlop now dynamically toggles its embedded Tor Hidden Service off when a Hub is connected, using Tor purely as an outbound SOCKS5 proxy to hit the Hub's persistent `.onion` address. The Hub listens on Port 9999, processes mesh traffic through its native `GossipEngine` firewall, and buffers valid packets in memory for the mobile app to pull.

1. **Smart Network State Machine (`NoSlopViewModel.kt` / `NoSlopRepository.kt`)** ✅
   - `invokeHubApi` handles automatic LAN-to-Tor fallback routing.
   - Polling loop introduced: Mobile pushes its `Trusted Peers` list to the Hub every 60s (`sync_push_peers`) and pulls validated mesh packets every 5s (`sync_pull_packets`).
2. **Tor Daemon Toggling (`TorService.kt`)** ✅
   - Added `skipHiddenServiceRegistration` flag. In `HUB_CONNECTED` mode, the public hidden service registration is skipped to prevent identity collisions on the mesh network.

1. **Network State Machine (`NoSlopViewModel.kt` / `NoSlopRepository.kt`)**
   - Implemented `invokeHubApi` to handle automatic LAN-to-Tor fallback routing.
   - NoSlop successfully hits the Hub's API locally and remotely.
   - Define states: `STANDALONE` (No Hub), `HUB_CONNECTED` (Hub reachable), `HUB_UNREACHABLE` (Fallback).
2. **Tor Daemon Toggling (`TorService.kt`)** ✅
   - Added `skipHiddenServiceRegistration` flag. In `HUB_CONNECTED` mode, the embedded Tor service is only used as an outbound SOCKS5 proxy. The public hidden service registration is skipped.
   - In `HUB_CONNECTED` mode, the embedded Tor service is only used as a SOCKS5 proxy to hit the Hub's private API. The public hidden service registration is skipped.
   - If pinging the Hub fails (timeout), transition to `HUB_UNREACHABLE`. NoSlop immediately calls `TorService.registerHiddenService` to bind the user's primary `.onion` address to the phone.
3. **Reconciliation Sync (`MeshPacketHandler.kt`)**
   - Upon reconnecting to the Hub (transition back to `HUB_CONNECTED`), trigger an `INVENTORY_SYNC_REQUEST`. Diff the local Room database against the Hub's database to merge any DMs or Posts received/sent during the failover period.
4. **Clearnet Aggregation Sync (`FeedSyncWorker.kt`)**
   - NoSlop continues to fetch RSS/API feeds locally.
   - Create an API call to sync the "Saved" and "Liked" clearnet states with the Hub's parallel local database.

### Phase 3: Deep Data Sync, Media Offloading & Sovereign Backup (ACTIVE)
**Goal:** Evolve the Hub from an in-memory relay into a persistent master vault, populating the HAI-Net Web Portal and offloading heavy bandwidth tasks from the phone.

1. **Rust SQLite Persistence (The Missing Link)** ✅
   - *Context:* The HAI-Net Web Portal is currently empty because the Hub only holds incoming packets in a temporary `RwLock<Vec<Value>>` buffer before passing them to the phone.
   - *Action:* Implement a Rust SQLite schema in `hainet-social` mirroring NoSlop's Room database to permanently store Posts, Comments, Reactions, and DMs. (Completed: `social.db` implemented, Hub UI updated, NoSlop bi-directional sync operational).
2. **Heavy Media Offloading**
   - Transfer the `MediaManager.kt` chunk-downloading logic to the Hub. The Hub should download 50MB video chunks over Tor 24/7. When complete, the mobile app pulls the entire MP4 file instantly over local Wi-Fi.
3. **Background Sync Worker (`FeedSyncWorker.kt`)**
   - Wire `syncWithHub()` into Android's WorkManager so the phone can wake up in the background, check the Hub for new DMs, and trigger native Android notifications while the app is closed.
4. **Automated AES ZIP Export (`data/BackupManager.kt`)**
   - Push silent, encrypted `noslop_backup.zip` archives to the Hub via `POST /api/backup/push`.

1. **Automated Export (`data/BackupManager.kt`)**
   - Extend the AES-256-CBC backup logic to output a silent background zip archive.
2. **Upload Worker (`data/HubSyncWorker.kt`)**
   - Create a daily `WorkManager` job.
   - Upload `noslop_backup.zip` to the Hub via `POST /api/backup/push` over the authenticated SOCKS5 Tor connection.
3. **Mnemonic Restoration Flow (`ui/OnboardingScreen.kt`)**
   - Add "Restore from Home Hub" to onboarding. 
   - User inputs the Hub's IP/Onion and their 12-word mnemonic.
   - Fetch the backup zip from `/api/backup/pull`, decrypt it, and restore the local database.

### Phase 4: Creator Media Studio (Approval Queue)
**Goal:** Provide an interface for Creators to review and publish existing media that the Hub's AI has auto-tagged.

1. **Studio UI (`ui/tabs/CreatorStudioTab.kt`)**
   - Create a UI to query the Hub's `/api/studio/queue` endpoint.
   - Display a list of media items with AI-generated metadata (Title, Description, Tags) and AI-extracted Thumbnails.
2. **Metadata Editing**
   - Allow the user to edit the text fields.
3. **Publishing**
   - Add a "Publish to Mesh" button. This sends a `POST /api/studio/publish` request to the Hub.
   - The UI must reflect the user's "Creator Badge" and show a link to their dedicated "Channel Onion" (managed by the Hub).
