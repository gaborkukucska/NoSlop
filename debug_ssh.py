
import os
import logging
import paramiko
from seed.ssh_manager import SSHManager

# Setup detailed logging to stdout
logging.basicConfig(level=logging.DEBUG)
logging.getLogger("paramiko").setLevel(logging.DEBUG)

def test_ssh():
    print("Initializing SSH Manager...")
    try:
        ssh_manager = SSHManager()
    except Exception as e:
        print(f"Failed to init SSHManager: {e}")
        return

    ip = "192.168.0.30"
    username = "tomanderson"
    
    print(f"\nAttempting to connect to {username}@{ip}...")
    
    # Try using exact same method as installer
    client = ssh_manager.create_ssh_client(ip, username=username)
    
    if client:
        print("SUCCESS: Connection established.")
        stdin, stdout, stderr = client.exec_command("whoami")
        print(f"Remote user: {stdout.read().decode().strip()}")
        client.close()
    else:
        print("FAILURE: Connection returned None.")

if __name__ == "__main__":
    test_ssh()
