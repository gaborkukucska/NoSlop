# Implement Active Hub Mesh Relaying & NoSlop Fixes

In accordance with the new direction, instead of bypassing the Hub on the mobile app, we will build out the missing **Tor outbound relaying capabilities** directly into the HAI-Net backend. This ensures the Hub acts as a true stand-in for the mobile app, handling all mesh traffic directly while keeping the mobile app lightweight.

## User Review Required

> [!IMPORTANT]  
> The Hub will now require a local Tor daemon running a SOCKS5 proxy on `127.0.0.1:9050` to send outbound packets. Since `hainet-seed` sets up Tor for the hidden service, this should already be present, but this feature explicitly relies on the SOCKS proxy.

## Open Questions

> [!NOTE]  
> The `mesh_peers` table in the Hub currently stores `public_key`, `is_trusted`, and `handle`. To send packets, the Hub needs the target's `.onion` address. I will add an `onion_address` column to the Hub's SQLite database and update NoSlop to push it during `sync_push_peers`. Is the Hub's SOCKS proxy port definitively `9050` across all deployments?

---

## Proposed Changes

### Component 1: NoSlop Android - Peer Sync & Fixes

#### [MODIFY] [NoSlopRepository.kt](file:///home/tom/NoSlop/app/src/main/java/com/noslop/app/data/NoSlopRepository.kt)
Update `syncPeersWithHub()` to include the peer's `onion_address`. Without this, the Hub cannot resolve where to send targeted Tor packets.

#### [MODIFY] API Clients (PodcastIndex, Reddit, Vimeo, Wikimedia)
Fix the `activeClearnetClient` capturing issue. They currently capture the `val` at class initialization, making the Tor toggle fail.
Change `private val client = HttpClientProvider.activeClearnetClient` to a dynamic getter: `private val client: OkHttpClient get() = HttpClientProvider.activeClearnetClient`.

#### [MODIFY] [network_security_config.xml](file:///home/tom/NoSlop/app/src/main/res/xml/network_security_config.xml)
Scope `cleartextTrafficPermitted` to `127.0.0.1` and `localhost` (and optionally the Hub's LAN IP if needed) as defined in MustFixBeforeRelease #5.

---

### Component 2: HAI-Net Core - Tor SOCKS5 Sender

#### [MODIFY] [Cargo.toml](file:///home/tom/NoSlop/_workspace/hai/hainet-core/Cargo.toml)
Add `tokio-socks = "0.5.1"` dependency to allow raw TCP streams over the local Tor SOCKS proxy.

#### [MODIFY] [main.rs](file:///home/tom/NoSlop/_workspace/hai/hainet-core/src/main.rs)
**1. Startup Trust Hydration:**
After initializing `social_db`, query `mesh_peers WHERE is_trusted = 1` and inject them into `gossip_engine.trust_peer()`. This prevents the Hub Firewall from dropping packets after a restart.

**2. Database Schema Update:**
Update the `SocialDb::new` creation logic to include `onion_address TEXT` in the `mesh_peers` table creation.

**3. The Tor Mesh Sender:**
Create a new asynchronous dispatch mechanism. When the Hub determines a packet needs to be sent to specific peers (either from the mobile app's `sync_push_packets` or a forwarded broadcast), it will:
- Lookup the target peer's `onion_address` from SQLite.
- Use `tokio_socks::tcp::Socks5Stream::connect("127.0.0.1:9050", format!("{}:9999", onion)).await`.
- Serialize the `NetworkPacket` to JSON and send it over the stream.

#### [MODIFY] [api_router.rs](file:///home/tom/NoSlop/_workspace/hai/hainet-core/src/api_router.rs)
**1. Update `sync_push_peers`:**
Extract `onion_address` from the mobile sync payload and insert it into the SQLite `mesh_peers` table.

**2. Update `sync_push_packets` (Outbound Mesh Relay):**
When the mobile app pushes an outbound packet (e.g., a DM or a POST):
- Evaluate `target_user_id`. If present (Targeted packet), query the peer's onion address and spawn the Tor SOCKS sender task.
- If it's a broadcast (no target), query ALL trusted peers' onion addresses and spawn sender tasks for each.

---

## Verification Plan

### Automated Tests
- Run `cargo check` in `hainet-core` to verify `tokio-socks` integration.
- Run Android unit tests `./gradlew test`.

### Manual Verification
1. **Hub Firewall Persistence**: Restart the Hub, send an inbound packet from a peer, verify it is NOT dropped.
2. **Outbound Hub Relaying**: From the mobile app (with Hub Connected), send a DM to a peer. Verify via Hub logs that it successfully opens a `Socks5Stream` and transmits the packet over Tor.
3. **Tor Toggle**: Verify the API feed clients correctly respect the "Route Clearnet via Tor" toggle in real-time.
