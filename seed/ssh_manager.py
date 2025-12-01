# START OF FILE seed/ssh_manager.py
"""
SSH Management Module for NoSlop Seed.

Handles SSH key generation, distribution, and connection management.
"""

import os
import logging
import subprocess
import getpass
from pathlib import Path
from typing import Optional, Dict, List
from dataclasses import dataclass

try:
    import paramiko
    PARAMIKO_AVAILABLE = True
except ImportError:
    PARAMIKO_AVAILABLE = False
    logging.warning("paramiko not available. SSH operations will be limited.")

logger = logging.getLogger(__name__)


@dataclass
class SSHCredentials:
    """SSH credentials for a device."""
    ip_address: str
    username: str
    password: Optional[str] = None
    key_path: Optional[str] = None
    port: int = 22


class SSHManager:
    """
    Manages SSH key generation and distribution for NoSlop deployment.
    
    Provides passwordless SSH authentication setup for all nodes.
    """
    
    def __init__(self, key_dir: Optional[str] = None):
        """
        Initialize SSH manager.
        
        Args:
            key_dir: Directory to store SSH keys (default: ~/.noslop/ssh)
        """
        if key_dir is None:
            key_dir = os.path.expanduser("~/.noslop/ssh")
        
        self.key_dir = Path(key_dir)
        self.key_dir.mkdir(parents=True, exist_ok=True)
        
        self.private_key_path = self.key_dir / "noslop_installer"
        self.public_key_path = self.key_dir / "noslop_installer.pub"
        
        logger.info(f"SSH manager initialized. Key directory: {self.key_dir}")
    
    def generate_key_pair(self, force: bool = False) -> bool:
        """
        Generate Ed25519 SSH key pair for the installer.
        
        Args:
            force: Overwrite existing keys if True
            
        Returns:
            True if keys were generated, False if they already exist
        """
        if self.private_key_path.exists() and not force:
            logger.info("SSH key pair already exists")
            return False
        
        logger.info("Generating Ed25519 SSH key pair...")
        
        try:
            # Generate key using ssh-keygen
            result = subprocess.run(
                [
                    "ssh-keygen",
                    "-t", "ed25519",
                    "-f", str(self.private_key_path),
                    "-N", "",  # No passphrase
                    "-C", "noslop-installer"
                ],
                capture_output=True,
                text=True,
                timeout=30
            )
            
            if result.returncode == 0:
                logger.info(f"SSH key pair generated: {self.private_key_path}")
                
                # Set proper permissions
                os.chmod(self.private_key_path, 0o600)
                os.chmod(self.public_key_path, 0o644)
                
                return True
            else:
                logger.error(f"Failed to generate SSH keys: {result.stderr}")
                return False
                
        except Exception as e:
            logger.error(f"Error generating SSH keys: {e}")
            return False
    
    def get_public_key(self) -> Optional[str]:
        """
        Read the public key content.
        
        Returns:
            Public key string or None if not found
        """
        try:
            if not self.public_key_path.exists():
                logger.error("Public key not found. Generate keys first.")
                return None
            
            with open(self.public_key_path, 'r') as f:
                public_key = f.read().strip()
            
            logger.debug(f"Read public key: {public_key[:50]}...")
            return public_key
            
        except Exception as e:
            logger.error(f"Error reading public key: {e}")
            return None
    
    def distribute_key(
        self, 
        credentials: SSHCredentials,
        interactive: bool = True
    ) -> bool:
        """
        Distribute public key to a remote device.
        
        Args:
            credentials: SSH credentials for the target device
            interactive: If True, prompt for password if needed
            
        Returns:
            True if key was successfully distributed
        """
        logger.info(f"Distributing SSH key to {credentials.ip_address}...")
        
        public_key = self.get_public_key()
        if not public_key:
            return False
        
        try:
            # Connect using paramiko
            client = paramiko.SSHClient()
            client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
            
            # Determine authentication method
            if credentials.password:
                password = credentials.password
            elif interactive:
                password = getpass.getpass(
                    f"Enter SSH password for {credentials.username}@{credentials.ip_address}: "
                )
            else:
                logger.error("No password provided and interactive mode disabled")
                return False
            
            # Connect
            client.connect(
                hostname=credentials.ip_address,
                port=credentials.port,
                username=credentials.username,
                password=password,
                timeout=10
            )
            
            logger.debug(f"Connected to {credentials.ip_address}")
            
            # Create .ssh directory if it doesn't exist
            stdin, stdout, stderr = client.exec_command(
                "mkdir -p ~/.ssh && chmod 700 ~/.ssh"
            )
            stdout.channel.recv_exit_status()  # Wait for command to complete
            
            # Add public key to authorized_keys
            command = f'echo "{public_key}" >> ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys'
            stdin, stdout, stderr = client.exec_command(command)
            exit_status = stdout.channel.recv_exit_status()
            
            if exit_status == 0:
                logger.info(f"✓ SSH key distributed to {credentials.ip_address}")
                client.close()
                return True
            else:
                error = stderr.read().decode()
                logger.error(f"Failed to add key to authorized_keys: {error}")
                client.close()
                return False
                
        except paramiko.AuthenticationException:
            logger.error(f"Authentication failed for {credentials.ip_address}")
            return False
        except paramiko.SSHException as e:
            logger.error(f"SSH error for {credentials.ip_address}: {e}")
            return False
        except Exception as e:
            logger.error(f"Error distributing key to {credentials.ip_address}: {e}")
            return False
    
    def test_connection(
        self, 
        ip_address: str, 
        username: str = "root",
        port: int = 22
    ) -> bool:
        """
        Test passwordless SSH connection to a device.
        
        Args:
            ip_address: IP address of the device
            username: SSH username
            port: SSH port
            
        Returns:
            True if connection successful
        """
        logger.info(f"Testing SSH connection to {username}@{ip_address}...")
        
        try:
            client = paramiko.SSHClient()
            client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
            
            # Load private key
            private_key = paramiko.Ed25519Key.from_private_key_file(
                str(self.private_key_path)
            )
            
            # Connect using key
            client.connect(
                hostname=ip_address,
                port=port,
                username=username,
                pkey=private_key,
                timeout=10
            )
            
            # Test command
            stdin, stdout, stderr = client.exec_command("echo 'NoSlop SSH Test'")
            output = stdout.read().decode().strip()
            
            client.close()
            
            if output == "NoSlop SSH Test":
                logger.info(f"✓ SSH connection successful to {ip_address}")
                return True
            else:
                logger.warning(f"Unexpected output from {ip_address}: {output}")
                return False
                
        except Exception as e:
            logger.error(f"SSH connection failed to {ip_address}: {e}")
            return False
    
    def create_ssh_client(
        self, 
        ip_address: str, 
        username: str = "root",
        port: int = 22
    ) -> Optional["paramiko.SSHClient"]:
        """
        Create an authenticated SSH client for a device.
        
        Args:
            ip_address: IP address of the device
            username: SSH username
            port: SSH port
            
        Returns:
            Connected SSHClient or None if connection fails
        """
        try:
            client = paramiko.SSHClient()
            client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
            
            # Load private key
            private_key = paramiko.Ed25519Key.from_private_key_file(
                str(self.private_key_path)
            )
            
            # Connect
            client.connect(
                hostname=ip_address,
                port=port,
                username=username,
                pkey=private_key,
                timeout=10
            )
            
            logger.debug(f"Created SSH client for {ip_address}")
            return client
            
        except Exception as e:
            logger.error(f"Failed to create SSH client for {ip_address}: {e}")
            return None
    
    def execute_command(
        self,
        client: "paramiko.SSHClient",
        command: str,
        timeout: int = 60
    ) -> tuple[int, str, str]:
        """
        Execute a command on a remote device.
        
        Args:
            client: Connected SSH client
            command: Command to execute
            timeout: Command timeout in seconds
            
        Returns:
            Tuple of (exit_code, stdout, stderr)
        """
        try:
            logger.debug(f"Executing: {command}")
            
            stdin, stdout, stderr = client.exec_command(command, timeout=timeout)
            exit_code = stdout.channel.recv_exit_status()
            
            stdout_str = stdout.read().decode().strip()
            stderr_str = stderr.read().decode().strip()
            
            if exit_code == 0:
                logger.debug(f"Command succeeded: {command}")
            else:
                logger.warning(f"Command failed (exit {exit_code}): {command}")
            
            return exit_code, stdout_str, stderr_str
            
        except Exception as e:
            logger.error(f"Error executing command: {e}")
            return -1, "", str(e)
    
    def transfer_file(
        self,
        client: "paramiko.SSHClient",
        local_path: str,
        remote_path: str
    ) -> bool:
        """
        Transfer a file to remote device using SFTP.
        
        Args:
            client: Connected SSH client
            local_path: Local file path
            remote_path: Remote file path
            
        Returns:
            True if transfer successful
        """
        try:
            logger.info(f"Transferring {local_path} to {remote_path}...")
            
            sftp = client.open_sftp()
            sftp.put(local_path, remote_path)
            sftp.close()
            
            logger.info(f"✓ File transferred successfully")
            return True
            
        except Exception as e:
            logger.error(f"File transfer failed: {e}")
            return False
    
    def transfer_directory(
        self,
        client: "paramiko.SSHClient",
        local_dir: str,
        remote_dir: str
    ) -> bool:
        """
        Transfer entire directory to remote device using SFTP.
        
        Args:
            client: Connected SSH client
            local_dir: Local directory path
            remote_dir: Remote directory path
            
        Returns:
            True if transfer successful
        """
        try:
            logger.info(f"Transferring directory {local_dir} to {remote_dir}...")
            
            sftp = client.open_sftp()
            local_path = Path(local_dir)
            
            # Create remote directory
            try:
                sftp.mkdir(remote_dir)
            except IOError:
                pass  # Directory might already exist
            
            # Transfer all files recursively
            for item in local_path.rglob('*'):
                if item.is_file():
                    relative_path = item.relative_to(local_path)
                    remote_file = f"{remote_dir}/{relative_path}".replace('\\', '/')
                    
                    # Create parent directories
                    remote_parent = '/'.join(remote_file.split('/')[:-1])
                    try:
                        sftp.mkdir(remote_parent)
                    except IOError:
                        pass
                    
                    # Transfer file
                    sftp.put(str(item), remote_file)
                    logger.debug(f"Transferred: {relative_path}")
            
            sftp.close()
            logger.info(f"✓ Directory transferred successfully")
            return True
            
        except Exception as e:
            logger.error(f"Directory transfer failed: {e}")
            return False
    
    def create_remote_directory(
        self,
        client: "paramiko.SSHClient",
        path: str
    ) -> bool:
        """
        Create a directory on remote device.
        
        Args:
            client: Connected SSH client
            path: Remote directory path
            
        Returns:
            True if directory created or already exists
        """
        try:
            command = f"mkdir -p {path}"
            exit_code, stdout, stderr = self.execute_command(client, command)
            
            if exit_code == 0:
                logger.debug(f"Created remote directory: {path}")
                return True
            else:
                logger.error(f"Failed to create directory {path}: {stderr}")
                return False
                
        except Exception as e:
            logger.error(f"Error creating remote directory: {e}")
            return False
    
    def collect_credentials(
        self, 
        ip_addresses: List[str],
        default_username: str = "root"
    ) -> Dict[str, SSHCredentials]:
        """
        Interactively collect SSH credentials for multiple devices.
        
        Args:
            ip_addresses: List of IP addresses
            default_username: Default SSH username
            
        Returns:
            Dictionary mapping IP to SSHCredentials
        """
        credentials_map = {}
        
        print("\n" + "="*60)
        print("SSH Credentials Collection")
        print("="*60)
        print("\nPlease provide SSH credentials for each device.")
        print("(Press Enter to use default username)")
        
        for ip in ip_addresses:
            print(f"\n📡 Device: {ip}")
            
            username = input(f"  Username [{default_username}]: ").strip()
            if not username:
                username = default_username
            
            password = getpass.getpass(f"  Password: ")
            
            credentials = SSHCredentials(
                ip_address=ip,
                username=username,
                password=password
            )
            
            credentials_map[ip] = credentials
        
        print("\n" + "="*60)
        return credentials_map


def main():
    """Test SSH management."""
    logging.basicConfig(
        level=logging.INFO,
        format='%(asctime)s - %(levelname)s - %(message)s'
    )
    
    manager = SSHManager()
    
    print("\n🔑 NoSlop SSH Manager Test\n")
    
    # Generate keys
    print("1. Generating SSH key pair...")
    if manager.generate_key_pair():
        print("   ✓ Keys generated successfully")
    else:
        print("   ℹ Keys already exist")
    
    # Show public key
    print("\n2. Public key:")
    public_key = manager.get_public_key()
    if public_key:
        print(f"   {public_key}")
    
    # Test connection to localhost (if SSH is available)
    print("\n3. Testing connection to localhost...")
    if manager.test_connection("127.0.0.1", os.getenv("USER", "root")):
        print("   ✓ Connection successful")
    else:
        print("   ✗ Connection failed (this is normal if SSH is not configured)")
    
    print("\n" + "="*60)


if __name__ == "__main__":
    main()
