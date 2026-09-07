# doc_update.py
import sys

APPLIED = []
FAILED = []

def edit(path, old, new, label):
    try:
        with open(path, "r", encoding="utf-8") as f:
            src = f.read()
    except Exception as e:
        FAILED.append(f"{label}: Could not read {path}: {e}")
        return

    if old not in src:
        FAILED.append(f"{label}: Target string not found in {path}")
        return

    count = src.count(old)
    if count > 1:
        FAILED.append(f"{label}: Target string appears {count} times (expected 1) in {path}")
        return

    src = src.replace(old, new, 1)
    try:
        with open(path, "w", encoding="utf-8") as f:
            f.write(src)
        APPLIED.append(label)
    except Exception as e:
        FAILED.append(f"{label}: Could not write {path}: {e}")

# ---------------------------------------------------------------------------
# 1. PROJECT_STATUS.md: Add documentation for DM fast-lane, outbox & presence
# ---------------------------------------------------------------------------
STATUS_FILE = "docs/PROJECT_STATUS.md"

OLD_STATUS_HEADER = '''# Project Status - NoSlop

## Completed Changes (2026-09-07) — Feed Toggle Stabilization, Position Resume & Mesh Sync Parity'''

NEW_STATUS_HEADER = '''# Project Status - NoSlop

## Completed Changes (2026-09-07) — Direct Message Fast-Lane, Missed Message Catch-up & Presence Stabilization

* **Express Lane Concurrency for DMs (`MeshTransport.kt`)**:
  * Decoupled transport concurrency into dedicated pools: `dmSemaphore(4)` exclusively reserved for real-time user communications (`MESSAGE`, `DELETE_MESSAGE`, `CONNECTION_REQUEST`, `USER_HANDSHAKE`, `DM_SYNC_REQUEST`, `GROUP_*`) and `bulkSemaphore(4)` for bulk feed/media data (`SYNC_RESPONSE`, `MEDIA_*`, etc.).
  * Direct Messages, handshakes, and DM sync now have absolute priority and can never be starved or delayed by concurrent feed sync responses or media chunk downloads.
* **Persistent DM Outbox & Immediate Peer Flush (`MeshSocialRepository.kt`)**:
  * Implemented persistent Outbox storage (`app_settings["pending_dm_outbox"]`) for direct messages that fail direct send when a peer or circuit is temporarily offline, guaranteeing message retention across process restarts.
  * Added event-driven outbox flushing: when a peer connects, announces presence, or Tor finishes bootstrapping, any queued outbox DMs for that peer are transmitted immediately without multi-minute delays.
* **Bi-Directional Missed Message Synchronization (`DM_SYNC_REQUEST`)**:
  * Implemented `DM_SYNC_REQUEST` wire protocol to catch up on missed DMs upon reconnect or app startup.
  * Nodes query `MessageDao.getLatestReceivedTimestamp(peerPub)` and request missed messages since that timestamp; counterparties stream missing `MESSAGE` packets directly over the express lane.
* **Peer Cooldown Auto-Reset & Backoff Cap (`GossipService.kt`)**:
  * Capped exponential cooldown backoff to 2 minutes (120s) max (down from 1 hour) to avoid locking out mobile peers experiencing brief connectivity transitions.
  * Configured incoming authenticated packets (`MESSAGE`, `ANNOUNCE_PEER`, `USER_HANDSHAKE`, `TYPING`) to immediately reset failure counters and clear cooldowns for the sender's onion address.
* **Presence & Typing Indicator Parity (`HandshakePacketHandler.kt`, `DmPacketHandler.kt`, `ChatThreadScreen.kt`)**:
  * Aligned `ANNOUNCE_PEER` signature verification to support both `encodeForSigning` and legacy pipe payloads, fixing the regression where trusted peers were never marked `isOnline = true`.
  * Updated `DmPacketHandler` to refresh `isOnline = true` and `lastSeenAt` on incoming `MESSAGE` and `TYPING` packets.
  * Added 6-second auto-expiration to peer typing state in `NoSlopRepository`, dismissed typing status when a message is delivered, and debounced typing stop (4s idle / on send) in `ChatThreadScreen`.

## Completed Changes (2026-09-07) — Feed Toggle Stabilization, Position Resume & Mesh Sync Parity'''

edit(STATUS_FILE, OLD_STATUS_HEADER, NEW_STATUS_HEADER, "docs/PROJECT_STATUS.md: Document DM fast lane and presence fixes")

# ---------------------------------------------------------------------------
# 2. TECHNICAL_REFERENCE.md: Add Section 19 documenting DM Priority & Sync
# ---------------------------------------------------------------------------
TECH_FILE = "docs/TECHNICAL_REFERENCE.md"

OLD_TECH_END = '''### 18.5 Micro-Seek Elimination
ExoPlayer range requests over Tor incur 10-15s latency round trips when seeking away from byte 0. `PlaybackPositionStore` now ignores offsets under 8,000ms, preserving preloaded initial frame buffers and allowing claimed preloaded players to render in 200-300ms on swipe.

---'''

NEW_TECH_END = '''### 18.5 Micro-Seek Elimination
ExoPlayer range requests over Tor incur 10-15s latency round trips when seeking away from byte 0. `PlaybackPositionStore` now ignores offsets under 8,000ms, preserving preloaded initial frame buffers and allowing claimed preloaded players to render in 200-300ms on swipe.

## 19. Direct Message Fast-Lane & Missed Message Catch-up (2026-09-07)

### 19.1 Concurrency Pool Isolation (`dmSemaphore` vs `bulkSemaphore`)
Previously, `MeshTransport` funneled all outbound Tor sockets through a single `torSemaphore(4)`. When background inventory sync ran or peers exchanged broadcasts, bursts of `SYNC_RESPONSE` and media chunks exhausted all permits. Real-time DMs and typing signals were forced into an unconstrained FIFO queue behind 15-second Tor handshakes.
- `dmSemaphore` (4 permits): Strictly reserved for `MESSAGE`, `DELETE_MESSAGE`, `CONNECTION_REQUEST`, `USER_HANDSHAKE`, `DM_SYNC_REQUEST`, and `GROUP_*` packets.
- `bulkSemaphore` (4 permits): Confines bulk inventory sync, media chunk transfers, and general gossip.
- Guarantees that feed and media activity can never monopolize circuits or delay direct messaging.

### 19.2 Persistent DM Outbox (`MeshSocialRepository`)
In-memory coroutine retry loops failed to survive app kills or system reboots. Undelivered DMs are now serialized to `app_settings["pending_dm_outbox"]`.
- When a direct send fails, the packet is placed into the persistent queue.
- Reconnect triggers (Tor reaching `READY`, peer `ANNOUNCE_PEER` receipts, or opening a chat thread) flush pending outbox DMs immediately to the target onion address.

### 19.3 Bi-directional Message Synchronization (`DM_SYNC_REQUEST`)
Upon establishing peer presence or application start, nodes query `MessageDao.getLatestReceivedTimestamp(peerPub)` and dispatch a `DM_SYNC_REQUEST(since: Long)`.
The recipient queries `MessageDao.getMessagesSentAfter(...)` and replays any missing `MESSAGE` packets directly over the fast-lane. The existing idempotency of `messageDao.insertMessage` (`OnConflictStrategy.REPLACE`) ensures zero duplicate message creation.

### 19.4 Peer Cooldown Dynamic Reset & Verification Alignment
- Peer failure cooldown is capped at 120s max (preventing 1-hour lockout traps).
- Incoming authenticated packets from a peer immediately clear any failure cooldown on the peer's onion address.
- `ANNOUNCE_PEER` signature verification accepts both `CryptoService.encodeForSigning` and legacy pipe payloads, ensuring peers are accurately marked `isOnline = true`.
- Typing indicators feature a 6-second auto-expiration guard, immediate dismissal upon message delivery, and a 4-second client-side idle debounce.

---'''

edit(TECH_FILE, OLD_TECH_END, NEW_TECH_END, "docs/TECHNICAL_REFERENCE.md: Document Section 19 DM Priority and Outbox")

print("\n=== DOCS UPDATE RESULTS ===")
for item in APPLIED:
    print(f"  [APPLIED] {item}")
if FAILED:
    print("\nErrors occurred:")
    for item in FAILED:
        print(f"  [FAILED]  {item}")
    sys.exit(1)
else:
    print(f"\nAll documentation updates applied successfully!")
