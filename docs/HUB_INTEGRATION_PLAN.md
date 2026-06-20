# NoSlop <-> HAI-Net Hub Integration Plan

## Context & Architecture
NoSlop is transitioning from a standalone mesh node into an **Active-Passive Client** for a user's HAI-Net Home Hub. 
- **The Home Hub (Rust)** is the `MASTER` node. It runs 24/7, holds the canonical mesh database, processes AI tasks, and maintains the primary public Tor `.onion` address.
- **NoSlop (Android)** is the `CLIENT`. It uses the *exact same Ed25519 Identity Key* as the Hub. It acts as a remote control, communicating with the Hub via a private API `.onion` or local IP.
- **Failover:** If the Hub goes offline, NoSlop automatically spins up its embedded `TorService.kt` to reclaim the public `.onion` address and keep the user online, later syncing data back to the Hub.

**LLM INSTRUCTION:** When implementing these phases, do NOT leave placeholder, mock, or simulated code. All implementations must be fully functional. Ensure comments are added and `PROJECT_STATUS.md` is updated upon completion.

---

### Phase 1: Mobile-Assisted Hub Installer (Zero-Terminal Setup)
**Goal:** Allow users to deploy `hainet-seed` to their Home Hub hardware directly from NoSlop without using a terminal.

1. **Setup UI (`ui/tabs/HubSetupScreen.kt`)**
   - Create a wizard UI requesting target IP, Username, Password, and target Drive Path (e.g., `/media/hai-drive`).
   - Include visual 3-step guides on how to enable OpenSSH for Windows, macOS, and Linux targets.
2. **SSH Integration (`build.gradle.kts` & `net/SshDeployer.kt`)**
   - Add a lightweight SSH client (e.g., `com.hierynomus:sshj` or equivalent).
   - Establish an SSH connection to the target device.
3. **Headless Configuration Injection**
   - Generate a `hub_config.json` containing the user's setup parameters AND their exported `IdentityKeys` (Ed25519, X25519, Mnemonic).
   - Upload this JSON via SCP.
   - Execute the deployer via SSH: `hainet-seed --config /tmp/hub_config.json`.
4. **State Update (`IdentityRepository.kt`)**
   - Save the newly established Hub's local IP and private API `.onion` address to `AppSettingDao`.

### Phase 2: Active-Passive Tor Identity & Synchronization
**Goal:** Implement the "Double Setup" failover networking model.

1. **Network State Machine (`NoSlopViewModel.kt` / `MeshTransport.kt`)**
   - Define states: `STANDALONE` (No Hub), `HUB_CONNECTED` (Hub reachable), `HUB_UNREACHABLE` (Fallback).
2. **Tor Daemon Toggling (`TorService.kt`)**
   - In `HUB_CONNECTED` mode, the embedded Tor service is only used as a SOCKS5 proxy to hit the Hub's private API. The public hidden service registration is skipped.
   - If pinging the Hub fails (timeout), transition to `HUB_UNREACHABLE`. NoSlop immediately calls `TorService.registerHiddenService` to bind the user's primary `.onion` address to the phone.
3. **Reconciliation Sync (`MeshPacketHandler.kt`)**
   - Upon reconnecting to the Hub (transition back to `HUB_CONNECTED`), trigger an `INVENTORY_SYNC_REQUEST`. Diff the local Room database against the Hub's database to merge any DMs or Posts received/sent during the failover period.
4. **Clearnet Aggregation Sync (`FeedSyncWorker.kt`)**
   - NoSlop continues to fetch RSS/API feeds locally.
   - Create an API call to sync the "Saved" and "Liked" clearnet states with the Hub's parallel local database.

### Phase 3: Sovereign Data Backup to Hub
**Goal:** Treat the Hub as the permanent master vault, treating mobile storage as an ephemeral cache.

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
