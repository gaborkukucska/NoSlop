#!/usr/bin/env python3
"""Quick diagnostic to test lenovo (192.168.0.15) NFS mount issue"""
import sys
sys.path.insert(0, '/home/tom/NoSlop')

from seed.ssh_manager import SSHManager
from seed.credential_store import CredentialStore
import logging

logging.basicConfig(level=logging.INFO, format='%(levelname)s - %(message)s')

store = CredentialStore()
cred = store.get_credential("192.168.0.15")

if not cred:
    print("ERROR: No credentials for 192.168.0.15")
    sys.exit(1)

print(f"Testing lenovo with user: {cred['username']}\n")

ssh_mgr = SSHManager()
client = ssh_mgr.create_ssh_client("192.168.0.15", username=cred['username'])

if not client:
    print("ERROR: Could not create SSH client")
    sys.exit(1)

print("✓ SSH connected\n")

# Test sudo with password
print("Test: sudo mkdir with 30s timeout")
code, out, err = ssh_mgr.execute_command(
    client,
    "sudo mkdir -p /tmp/noslop_mkdir_test",
    timeout=30,
    sudo_password=cred['password']
)
print(f"Exit code: {code}")
if code != 0:
    print(f"ERROR: {err}")
else:
    print("✓ mkdir succeeded")
    ssh_mgr.execute_command(client, "sudo rm -rf /tmp/noslop_mkdir_test", timeout=10, sudo_password=cred['password'])

client.close()
print("\nDone")
