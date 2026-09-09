#!/usr/bin/env python3
import os
import sys

WIRE_FILE = "docs/WIRE_PROTOCOL_REFERENCE.md"

if not os.path.exists(WIRE_FILE):
    print(f"Error: {WIRE_FILE} not found")
    sys.exit(1)

with open(WIRE_FILE, "r", encoding="utf-8") as f:
    src = f.read()

# 1. Add GROUP_MESSAGE to Section 2 Full Packet Type Catalog
OLD_ROW = "| 26 | `DELETE_MESSAGE` | `DeleteMessagePayload` | `messageId\\|authorId\\|timestamp` | `DmPacketHandler.handleDeleteMessage` | DM: `messageDao.deleteMessageByIdAndSender`; Group (if `group_id` set): `messageDao.deleteMessageById` after verifying author is message sender or group admin |"
NEW_ROW = """| 26 | `DELETE_MESSAGE` | `DeleteMessagePayload` | `messageId\\|authorId\\|timestamp` | `DmPacketHandler.handleDeleteMessage` | DM: `messageDao.deleteMessageByIdAndSender`; Group (if `group_id` set): `messageDao.deleteMessageById` after verifying author is message sender or group admin |
| 27 | `GROUP_MESSAGE` | `GroupMessagePayload` | Masked identity mesh broadcast | `DmPacketHandler.handleGroupMessage` | `messageDao.insertMessage`; local notification shown, triggers media auto-download |"""

if OLD_ROW in src and "GROUP_MESSAGE" not in src:
    src = src.replace(OLD_ROW, NEW_ROW, 1)
    print("[APPLIED] Added GROUP_MESSAGE to Section 2 catalog")

# 2. Update GROUP_INVITE in Section 3 to include allow_member_invites & allow_member_self_remove
OLD_INVITE_TABLE = """| `avatar_b64`? | String | Group picture |
| `description`? | String | Group description |
| `timestamp` | Long | Epoch milliseconds |"""

NEW_INVITE_TABLE = """| `avatar_b64`? | String | Group picture |
| `description`? | String | Group description |
| `allow_member_invites`? | Boolean | Whether non-admin members are permitted to invite peers (default: true) |
| `allow_member_self_remove`? | Boolean | Whether members may voluntarily leave the group (default: true) |
| `timestamp` | Long | Epoch milliseconds |"""

if OLD_INVITE_TABLE in src:
    src = src.replace(OLD_INVITE_TABLE, NEW_INVITE_TABLE, 1)
    print("[APPLIED] Updated GROUP_INVITE field table with permission flags")

# 3. Update GROUP_UPDATE in Section 3 to include allow_member_invites & allow_member_self_remove
OLD_UPDATE_TABLE = """| `added_members`? | Array\\<String\\> | Members added by this update (a delta, not the full list) |
| `removed_members`? | Array\\<String\\> | Members removed by this update |
| `timestamp` | Long | Epoch milliseconds |"""

NEW_UPDATE_TABLE = """| `added_members`? | Array\\<String\\> | Members added by this update (a delta, not the full list) |
| `removed_members`? | Array\\<String\\> | Members removed by this update |
| `allow_member_invites`? | Boolean | Updated invite permission flag (admin only) |
| `allow_member_self_remove`? | Boolean | Updated self-remove permission flag (admin only) |
| `timestamp` | Long | Epoch milliseconds |"""

if OLD_UPDATE_TABLE in src:
    src = src.replace(OLD_UPDATE_TABLE, NEW_UPDATE_TABLE, 1)
    print("[APPLIED] Updated GROUP_UPDATE field table with permission flags")

# 4. Add GROUP_MESSAGE to Section 3 Payload Details
OLD_DELETE_MSG_SECTION = """**Group mode** (`group_id` present): the handler loads the group, checks
that `authorId` is either the message's `senderPub` (author deleting own
message) or the group's `adminPublicKeyB64` (admin purging any message).
The message is deleted via `deleteMessageById` (no sender constraint)."""

NEW_DELETE_MSG_SECTION = """**Group mode** (`group_id` present): the handler loads the group, checks
that `authorId` is either the message's `senderPub` (author deleting own
message) or the group's `adminPublicKeyB64` (admin purging any message).
The message is deleted via `deleteMessageById` (no sender constraint).

### GROUP_MESSAGE
**Type:** `GROUP_MESSAGE` · class `GroupMessagePayload`

| Field | Type | Description |
|---|---|---|
| `id` | String | Unique message ID (UUID) |
| `group_id` | String | Target group identifier |
| `sender_handle` | String | Display name/handle of the author |
| `sender_tripcode`? | String | Short tripcode fingerprint |
| `content` | String | Plaintext message body |
| `timestamp` | Long | Epoch milliseconds |
| `privacy` | String | `"public"` (relayed mesh-wide, hops=6) or `"friends"` (direct contacts only, hops=1) |
| `media_id`? | String | Attached media ID, if present |
| `media_type`? | String | MIME category (`image`, `video`, `file`, `audio`), if present |
| `media_metadata`? | Object (`MediaMetadata`, §5) | Media descriptor object |
| `reply_to`? | String | ID of the message being replied to |

Delivers group messages across multi-hop mesh relays to non-connected members
in open groups without exposing the sender's raw public keys or onion addresses."""

if OLD_DELETE_MSG_SECTION in src and "### GROUP_MESSAGE" not in src:
    src = src.replace(OLD_DELETE_MSG_SECTION, NEW_DELETE_MSG_SECTION, 1)
    print("[APPLIED] Added GROUP_MESSAGE payload details to Section 3")

# 5. Add GROUP_MESSAGE to Section 7 Cryptographic Signed-String Formats
OLD_SEC7_ROW = """| `DELETE_MESSAGE` | `messageId\\|authorId\\|timestamp` — DM: only message author; Group (if `group_id` set): author or admin |"""
NEW_SEC7_ROW = """| `DELETE_MESSAGE` | `messageId\\|authorId\\|timestamp` — DM: only message author; Group (if `group_id` set): author or admin |
| `GROUP_MESSAGE` | *(identity-masked mesh broadcast; routing validated by group membership)* |"""

if OLD_SEC7_ROW in src and "GROUP_MESSAGE" not in src.split("## 7.")[1]:
    src = src.replace(OLD_SEC7_ROW, NEW_SEC7_ROW, 1)
    print("[APPLIED] Added GROUP_MESSAGE to Section 7 table")

with open(WIRE_FILE, "w", encoding="utf-8") as f:
    f.write(src)

print("\nFinished updating docs/WIRE_PROTOCOL_REFERENCE.md!")
