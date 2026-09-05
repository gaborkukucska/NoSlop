#!/usr/bin/env python3
import os
import sys

APPLIED = []
FAILED = []

def edit(path, old, new, label):
    if not os.path.exists(path):
        FAILED.append(f"{label}: file not found {path}")
        return
    with open(path, "r", encoding="utf-8") as f:
        src = f.read()
    if old not in src:
        FAILED.append(f"{label}: anchor not found in {path}")
        return
    if src.count(old) != 1:
        FAILED.append(f"{label}: anchor matched {src.count(old)} times, expected 1 in {path}")
        return
    with open(path, "w", encoding="utf-8") as f:
        f.write(src.replace(old, new, 1))
    APPLIED.append(label)

STATUS_FILE = "docs/PROJECT_STATUS.md"

OLD_BLOCK = """*   **11. The database is not encrypted at rest.** (Encrypt group bodies or use SQLCipher).
*   **12. ProGuard keeps essentially the whole app.** (Remove wildcards, annotate models).

### 6. General Enhancements (Legacy Status Log)"""

NEW_BLOCK = """*   **11. The database is not encrypted at rest.** (Encrypt group bodies or use SQLCipher).
*   **12. ProGuard keeps essentially the whole app.** (Remove wildcards, annotate models).
*   **13. Video playback over Tor lacks stream isolation (ACTIVE BLOCKER - 2026-09-06).** Resolve and playback use the current global Tor circuit. Attempting custom headers or global circuit rotation breaks streaming. Full SOCKS5 stream authentication isolation (`IsolateSOCKSAuth`) with custom `SocketFactory` and Tor bandwidth budgeting is required before Clearnet-over-Tor video playback is stable.

### 6. General Enhancements (Legacy Status Log)"""

edit(STATUS_FILE, OLD_BLOCK, NEW_BLOCK, "PROJECT_STATUS.md: Re-add item 13 as active blocker in master backlog")

print("\n=== DOCUMENTATION UPDATE REGISTER ===")
for item in APPLIED:
    print(f"  [APPLIED] {item}")

if FAILED:
    for item in FAILED:
        print(f"  [FAILED]  {item}")
    sys.exit(1)
else:
    print("\nDocumentation backlog updated successfully!")
