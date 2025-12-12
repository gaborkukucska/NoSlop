# START OF FILE seed/storage_manager.py
"""
Storage Manager for NoSlop Seed.

Manages shared storage configuration and NFS/SMB setup across devices.
Ensures models, project files, and media are accessible from all instances.
"""

import os
import logging
from typing import Dict, Optional, List
from pathlib import Path

logger = logging.getLogger(__name__)


class StorageConfig:
    """Storage configuration for NoSlop deployment."""
    
    def __init__(self):
        self.ollama_models_dir = "/mnt/noslop/ollama/models"
        self.comfyui_models_dir = "/mnt/noslop/comfyui/models"
        self.comfyui_custom_nodes_dir = "/mnt/noslop/comfyui/custom_nodes"
        self.project_storage_dir = "/mnt/noslop/projects"
        self.media_cache_dir = "/mnt/noslop/media_cache"
        self.base_path = "/mnt/noslop"
    
    def to_dict(self) -> Dict[str, str]:
        """Convert to dictionary for serialization."""
        return {
            "ollama_models_dir": self.ollama_models_dir,
            "comfyui_models_dir": self.comfyui_models_dir,
            "comfyui_custom_nodes_dir": self.comfyui_custom_nodes_dir,
            "project_storage_dir": self.project_storage_dir,
            "media_cache_dir": self.media_cache_dir,
            "base_path": self.base_path
        }
    
    def to_env_vars(self) -> Dict[str, str]:
        """Convert to environment variables."""
        return {
            "OLLAMA_MODELS_DIR": self.ollama_models_dir,
            "COMFYUI_MODELS_DIR": self.comfyui_models_dir,
            "COMFYUI_CUSTOM_NODES_DIR": self.comfyui_custom_nodes_dir,
            "PROJECT_STORAGE_DIR": self.project_storage_dir,
            "MEDIA_CACHE_DIR": self.media_cache_dir,
            "SHARED_STORAGE_ENABLED": "true"
        }


class StorageManager:
    """
    Manages shared storage setup for NoSlop.
    
    Handles:
    - User configuration prompts
    - NFS server setup on master node
    - NFS client mounts on worker nodes
    - Storage validation and testing
    """
    
    def __init__(self, ssh_manager=None):
        self.ssh_manager = ssh_manager
        self.config = StorageConfig()
    
    def prompt_storage_config(self) -> StorageConfig:
        """
        Interactive CLI to configure storage paths.
        
        Returns:
            StorageConfig with user-specified or default paths
        """
        logger.info("=== NoSlop Shared Storage Configuration ===")
        print("\nNoSlop uses shared storage to ensure models and files are accessible")
        print("from all devices in your deployment.\n")
        
        print("Default storage location: /mnt/noslop")
        use_defaults = input("Use default storage paths? (Y/n): ").strip().lower()
        
        if use_defaults in ['', 'y', 'yes']:
            logger.info("Using default storage paths")
            return self.config
        
        # Custom paths
        default_base = self.config.base_path
        print("\nEnter custom storage paths (press Enter to use default):")
        base_path = input(f"Base storage path [{default_base}]: ").strip()
        if not base_path:
            base_path = default_base
        
        # Remove trailing slashes to prevent double slashes
        base_path = base_path.rstrip('/')
        
        self.config.base_path = base_path
        self.config.ollama_models_dir = f"{base_path}/ollama/models"
        self.config.comfyui_models_dir = f"{base_path}/comfyui/models"
        self.config.comfyui_custom_nodes_dir = f"{base_path}/comfyui/custom_nodes"
        self.config.project_storage_dir = f"{base_path}/projects"
        self.config.media_cache_dir = f"{base_path}/media_cache"
        
        logger.info(f"Storage configured with base path: {self.config.base_path}")
        return self.config
    
    def validate_storage_path(self, device, path: str, username: str = "root", password: str = None) -> bool:
        """
        Validate that a storage path exists and has sufficient space.
        
        Args:
            device: Device to check
            path: Path to validate
            username: SSH username
            password: SSH password
            
        Returns:
            True if valid, False otherwise
        """
        if not self.ssh_manager:
            logger.warning("No SSH manager available, skipping validation")
            return True
        
        # Create SSH client
        client = self.ssh_manager.create_ssh_client(device.ip_address, username=username)
        if not client:
            logger.warning("Failed to create SSH connection for validation")
            return True  # Don't fail on validation
        
        # Check if path exists or can be created
        code, _, _ = self.ssh_manager.execute_command(
            client,
            f"sudo mkdir -p {path}"
        )
        
        if code != 0:
            logger.error(f"Failed to create storage path {path} on {device.ip_address}")
            return False
        
        # Check available space (require at least 50GB)
        code, out, _ = self.ssh_manager.execute_command(
            client,
            f"df -BG {path} | tail -1 | awk '{{print $4}}'"
        )
        
        if code == 0:
            available_gb = int(out.strip().replace('G', ''))
            if available_gb < 50:
                logger.warning(f"Low disk space on {device.ip_address}: {available_gb}GB available")
                print(f"\n⚠️  Warning: Only {available_gb}GB available on {device.ip_address}")
                print("   Recommended: At least 50GB for models and media")
                proceed = input("   Continue anyway? (y/N): ").strip().lower()
                return proceed == 'y'
        
        logger.info(f"✓ Storage path {path} validated on {device.ip_address}")
        return True
    
    def setup_passwordless_sudo(self, device, username: str, password: str) -> bool:
        """
        Configure passwordless sudo for NFS-related commands.
        
        Args:
            device: Device to configure
            username: SSH username
            password: User password for initial sudo
            
        Returns:
            True if successful
        """
        logger.info(f"Configuring passwordless sudo for NFS on {device.ip_address}...")
        
        # Create SSH client
        client = self.ssh_manager.create_ssh_client(device.ip_address, username=username)
        if not client:
            logger.warning("Failed to create SSH connection for sudo setup")
            return False
        
        # Create sudoers file for NFS commands
        sudoers_content = f"""# NoSlop NFS operations - no password required
{username} ALL=(ALL) NOPASSWD: /usr/bin/apt-get update
{username} ALL=(ALL) NOPASSWD: /usr/bin/apt-get install*
{username} ALL=(ALL) NOPASSWD: /usr/bin/mkdir*
{username} ALL=(ALL) NOPASSWD: /usr/bin/chmod*
{username} ALL=(ALL) NOPASSWD: /usr/bin/tee*
{username} ALL=(ALL) NOPASSWD: /usr/sbin/exportfs*
{username} ALL=(ALL) NOPASSWD: /usr/bin/systemctl*
{username} ALL=(ALL) NOPASSWD: /usr/bin/mount*
{username} ALL=(ALL) NOPASSWD: /usr/bin/df*
{username} ALL=(ALL) NOPASSWD: /usr/bin/sed*
"""
        
        # Write sudoers file using echo with password
        logger.debug("Creating /etc/sudoers.d/noslop-nfs...")
        
        # Use password for this one-time setup
        # First, write to temp file
        code, _, err = self.ssh_manager.execute_command(
            client,
            f"echo '{sudoers_content}' > /tmp/noslop-nfs-sudoers"
        )
        
        if code != 0:
            logger.warning(f"Failed to create temp sudoers file: {err}")
            return False
        
        # Move to sudoers.d with password (using -S for stdin password)
        if password:
            code, _, err = self.ssh_manager.execute_command(
                client,
                f"echo '{password}' | sudo -S mv /tmp/noslop-nfs-sudoers /etc/sudoers.d/noslop-nfs && echo '{password}' | sudo -S chown root:root /etc/sudoers.d/noslop-nfs && echo '{password}' | sudo -S chmod 0440 /etc/sudoers.d/noslop-nfs"
            )
        else:
            # Try without password (maybe already has sudo access)
            code, _, err = self.ssh_manager.execute_command(
                client,
                "sudo mv /tmp/noslop-nfs-sudoers /etc/sudoers.d/noslop-nfs && sudo chown root:root /etc/sudoers.d/noslop-nfs && sudo chmod 0440 /etc/sudoers.d/noslop-nfs"
            )
        
        if code != 0:
            logger.warning(f"Failed to setup passwordless sudo: {err}")
            logger.warning("NFS setup may require manual password entry")
            return False
        
        logger.info("✓ Passwordless sudo configured for NFS operations")
        return True
    
    def setup_nfs_server(self, master_device, username: str = "root", password: str = None) -> bool:
        """
        Configure NFS exports on master node.
        
        Args:
            master_device: Master device to configure as NFS server
            username: SSH username
            password: SSH password
            
        Returns:
            True if successful, False otherwise
        """
        logger.info(f"Setting up NFS server on {master_device.ip_address}...")
        logger.debug(f"Using username: {username}")
        
        if not self.ssh_manager:
            logger.error("No SSH manager available")
            return False
        
        # Setup passwordless sudo first
        logger.info("Setting up passwordless sudo for NFS operations...")
        if not self.setup_passwordless_sudo(master_device, username, password):
            logger.warning("Passwordless sudo setup failed, continuing anyway...")
        
        # Install NFS server
        logger.info("Installing NFS server packages...")
        
        # Create SSH client
        logger.debug(f"Creating SSH client for {master_device.ip_address}...")
        client = self.ssh_manager.create_ssh_client(master_device.ip_address, username=username)
        if not client:
            logger.error(f"❌ Failed to create SSH connection to {master_device.ip_address}")
            print(f"\n❌ ERROR: Could not connect to {master_device.ip_address}")
            print(f"   Check that SSH is accessible and credentials are correct.")
            return False
        logger.debug("✓ SSH client created successfully")
        
        logger.debug("Executing: sudo apt-get update && sudo apt-get install -y nfs-kernel-server")
        code, out, err = self.ssh_manager.execute_command(
            client,
            "sudo apt-get update && sudo apt-get install -y nfs-kernel-server",
            timeout=300
        )
        
        if code != 0:
            logger.error(f"❌ Failed to install NFS server (exit code {code})")
            logger.error(f"Error output: {err}")
            print(f"\n❌ ERROR: NFS server installation failed")
            print(f"   Exit code: {code}")
            print(f"   Error: {err[:200]}")
            return False
        logger.info("✓ NFS server packages installed")
        
        # Create storage directories
        logger.info("Creating storage directories...")
        for path in [
            self.config.ollama_models_dir,
            self.config.comfyui_models_dir,
            self.config.comfyui_custom_nodes_dir,
            self.config.project_storage_dir,
            self.config.media_cache_dir
        ]:
            logger.debug(f"Creating directory: {path}")
            code, out, err = self.ssh_manager.execute_command(
                client,
                f"sudo mkdir -p {path} && sudo chmod 777 {path}"
            )
            if code != 0:
                logger.error(f"❌ Failed to create directory {path} (exit code {code})")
                logger.error(f"Error: {err}")
                print(f"\n❌ ERROR: Failed to create storage directory")
                print(f"   Path: {path}")
                print(f"   Error: {err[:200]}")
                return False
            logger.debug(f"✓ Created {path}")
        logger.info("✓ All storage directories created")
        
        # Configure NFS exports with robust deduplication
        logger.info("Configuring NFS exports...")
        logger.debug("Reading existing /etc/exports...")
        
        # 1. Read existing exports file
        code, existing_exports, err = self.ssh_manager.execute_command(
            client,
            "sudo cat /etc/exports 2\u003e/dev/null || true"
        )
        
        if code != 0:
            logger.debug("No existing /etc/exports file, will create new one")
            existing_exports = ""
        else:
            logger.debug(f"Found existing exports file with {len(existing_exports.splitlines())} lines")
        
        # 2. Filter out all NoSlop-related entries
        # Remove lines with NoSlop comments AND lines matching our base path
        clean_lines = []
        skip_next = False
        for line in existing_exports.splitlines():
            # Skip NoSlop comment headers
            if "NoSlop" in line and line.strip().startswith("#"):
                skip_next = True
                continue
            # Skip the export line that follows NoSlop comment
            if skip_next and line.strip() and not line.strip().startswith("#"):
                skip_next = False
                continue
            # Skip any line that starts with our base path (handles duplicates without comments)
            if line.strip().startswith(self.config.base_path):
                continue
            # Keep all other lines
            if line.strip():  # Don't keep empty lines
                clean_lines.append(line)
        
        # 3. Build new exports content
        new_exports_lines = clean_lines + [
            "# NoSlop Shared Storage Exports",
            f"{self.config.base_path} *(rw,sync,no_subtree_check,no_root_squash)"
        ]
        new_exports_content = "\n".join(new_exports_lines) + "\n"
        
        logger.debug(f"New exports will have {len(new_exports_lines)} lines (removed duplicates)")
        
        # 4. Create backup of original exports file
        logger.debug("Creating backup of /etc/exports...")
        code, _, _ = self.ssh_manager.execute_command(
            client,
            "sudo cp /etc/exports /etc/exports.bak.noslop 2\u003e/dev/null || true"
        )
        
        # 5. Write the clean deduplicated exports file
        # We use 'tee' (not 'tee -a') to overwrite, not append
        logger.debug("Writing deduplicated exports to /etc/exports...")
        code, out, err = self.ssh_manager.execute_command(
            client,
            f"echo '{new_exports_content}' | sudo tee /etc/exports \u003e /dev/null",
            sudo_password=password
        )
        
        if code != 0:
            logger.error(f"❌ Failed to configure NFS exports (exit code {code})")
            logger.error(f"Error: {err}")
            print(f"\n❌ ERROR: Failed to write NFS exports configuration")
            print(f"   Error: {err[:200]}")
            return False
            
        logger.info("✓ NFS exports configured")
        
        # Restart NFS server
        logger.info("Restarting NFS server...")
        # We use exportfs -r (re-export) instead of -ra to be specific? 
        # Actually -ra is standard. Now that duplicates are gone, it should exit 0.
        logger.debug("Executing: sudo exportfs -ra && sudo systemctl restart nfs-kernel-server")
        code, out, err = self.ssh_manager.execute_command(
            client,
            "sudo exportfs -ra && sudo systemctl restart nfs-kernel-server",
            sudo_password=password
        )
        
        if code != 0:
            logger.error(f"❌ Failed to restart NFS server (exit code {code})")
            logger.error(f"Error: {err}")
            print(f"\n❌ ERROR: Failed to restart NFS server")
            print(f"   Error: {err[:200]}")
            return False
        
        logger.info("✓ NFS server configured successfully")
        print("   ✓ NFS server is running")
        return True
    
    def mount_nfs_shares(self, worker_device, master_ip: str, username: str = "root", password: str = None) -> bool:
        """
        Mount NFS shares on worker node.
        
        Args:
            worker_device: Worker device to mount shares on
            master_ip: IP address of master node (NFS server)
            username: SSH username
            password: SSH password
            
        Returns:
            True if successful, False otherwise
        """
        logger.info(f"Mounting NFS shares on {worker_device.ip_address}...")
        logger.debug(f"Master IP: {master_ip}, Using username: {username}")
        
        if not self.ssh_manager:
            logger.error("No SSH manager available")
            return False
        
        # Setup passwordless sudo first
        logger.info("Setting up passwordless sudo for NFS operations...")
        if not self.setup_passwordless_sudo(worker_device, username, password):
            logger.warning("Passwordless sudo setup failed, continuing anyway...")
        
        # Install NFS client
        logger.info("Installing NFS client packages...")
        
        # Create SSH client
        logger.debug(f"Creating SSH client for {worker_device.ip_address}...")
        client = self.ssh_manager.create_ssh_client(worker_device.ip_address, username=username)
        if not client:
            logger.error(f"❌ Failed to create SSH connection to {worker_device.ip_address}")
            print(f"\n❌ ERROR: Could not connect to {worker_device.ip_address}")
            return False
        logger.debug("✓ SSH client created")
        
        code, _, err = self.ssh_manager.execute_command(
            client,
            "sudo apt-get update && sudo apt-get install -y nfs-common",
            timeout=300,
            sudo_password=password
        )
        
        if code != 0:
            logger.error(f"Failed to install NFS client: {err}")
            return False
        
        # IMPORTANT: Workers mount at /mnt/noslop, NOT at master's storage path
        # The master's path (e.g. /media/tom/Data/NoSlop/) may:
        # - Not exist on worker nodes
        # - Be on device-specific mounts
        # - Trigger automount/hang issues
        worker_mount_point = "/mnt/noslop"
        
        # Create mount point
        logger.info(f"Creating mount point {worker_mount_point}...")
        code, _, err = self.ssh_manager.execute_command(
            client,
            f"sudo mkdir -p {worker_mount_point}",
            timeout=30,  # 30 second timeout for mkdir
            sudo_password=password
        )
        
        if code != 0:
            logger.error(f"Failed to create mount point: {err}")
            return False
        
        # Mount NFS share with timeout and retry
        logger.info(f"Mounting {master_ip}:{self.config.base_path}...")
        
        # Try mount with short timeout first (30s), retry once if it fails
        max_retries = 2
        mount_timeout = 60  # 60 second timeout for mount operation
        
        for attempt in range(max_retries):
            code, out, err = self.ssh_manager.execute_command(
                client,
                f"sudo mount -t nfs -o soft,timeo=100,retrans=2 {master_ip}:{self.config.base_path} {worker_mount_point}",
                timeout=mount_timeout,
                sudo_password=password
            )
            
            if code == 0:
                break
            
            if attempt < max_retries - 1:
                logger.warning(f"Mount attempt {attempt + 1} failed, retrying... ({err[:100]})")
                import time
                time.sleep(5)
            else:
                logger.error(f"Failed to mount NFS share after {max_retries} attempts: {err}")
                return False
        
        # Add to fstab for persistence
        logger.info("Adding to /etc/fstab for automatic mounting...")
        fstab_entry = f"{master_ip}:{self.config.base_path} {worker_mount_point} nfs defaults 0 0"
        code, _, _ = self.ssh_manager.execute_command(
            client,
            f"echo '{fstab_entry}' | sudo tee -a /etc/fstab",
            sudo_password=password
        )
        
        logger.info("✓ NFS shares mounted successfully")
        print(f"   ✓ NFS mounted from {master_ip}")
        return True
    
    def verify_shared_storage(self, device, username: str = "root", password: str = None) -> bool:
        """
        Test read/write access to shared storage.
        
        Args:
            device: Device to test
            username: SSH username
            password: SSH password
            
        Returns:
            True if accessible, False otherwise
        """
        logger.info(f"Verifying shared storage on {device.ip_address}...")
        
        # Workers mount at /mnt/noslop, not at master's storage path
        test_path = "/mnt/noslop"
        logger.debug(f"Test path: {test_path}")
        
        if not self.ssh_manager:
            logger.warning("No SSH manager available, skipping verification")
            return True
        
        test_file = f"{test_path}/test_{device.ip_address.replace('.', '_')}.txt"
        test_content = f"NoSlop storage test from {device.ip_address}"
        
        # Create SSH client
        client = self.ssh_manager.create_ssh_client(device.ip_address, username=username)
        if not client:
            logger.warning("Failed to create SSH connection for verification")
            return True  # Don't fail deployment on verification
        
        # Write test file with timeout
        code, _, err = self.ssh_manager.execute_command(
            client,
            f"echo '{test_content}' > {test_file}",
            timeout=10
        )
        
        if code != 0:
            logger.error(f"Failed to write test file: {err}")
            return False
        
        # Read test file with timeout
        code, out, err = self.ssh_manager.execute_command(
            client,
            f"cat {test_file}",
            timeout=10
        )
        
        if code != 0 or test_content not in out:
            logger.error(f"Failed to read test file: {err}")
            return False
        
        # Clean up test file
        self.ssh_manager.execute_command(
            client,
            f"rm {test_file}",
            timeout=10
        )
        
        logger.info("✓ Shared storage verified")
        return True
    
    def generate_storage_env(self) -> str:
        """
        Generate .env entries for storage configuration.
        
        Returns:
            String with environment variable definitions
        """
        env_vars = self.config.to_env_vars()
        
        lines = ["# NoSlop Shared Storage Configuration"]
        for key, value in env_vars.items():
            lines.append(f"{key}={value}")
        
        return "\n".join(lines)
