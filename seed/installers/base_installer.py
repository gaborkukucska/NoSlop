# START OF FILE seed/installers/base_installer.py
"""
Base Installer Module for NoSlop Seed.

Defines the abstract base class for all service installers, providing
common functionality for OS detection, package management, and remote execution.
"""

import logging
import time
import sys
from abc import ABC, abstractmethod
from typing import Optional, Tuple, List, Dict
from pathlib import Path

from seed.models import DeviceCapabilities, OSType
from seed.ssh_manager import SSHManager

logger = logging.getLogger(__name__)



class BaseInstaller(ABC):
    """
    Abstract base class for service installers.
    """
    
    def __init__(
        self,
        device: DeviceCapabilities,
        ssh_manager: SSHManager,
        service_name: str,
        username: Optional[str] = "root",
        password: Optional[str] = None
    ):
        """
        Initialize the installer.

        Args:
            device: Target device capabilities
            ssh_manager: SSH manager instance
            service_name: Name of the service being installed
            username: SSH username for remote connections
            password: Password for sudo/SSH (optional)
        """
        self.device = device
        self.ssh_manager = ssh_manager
        self.service_name = service_name
        self.username = username
        self.password = password
        self.ssh_client = None
        self.logger = logging.getLogger(f"{__name__}.{service_name}")

        # Connect to device
        self._connect()


    def _connect(self):
        """Establish SSH connection to the device."""
        # Check if this device is localhost
        import socket
        try:
            # Get local IP address
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.connect(("8.8.8.8", 80))
            local_ip = s.getsockname()[0]
            s.close()
        except Exception:
            local_ip = "127.0.0.1"
        
        # Check if device is local
        if self.device.ip_address in ["localhost", "127.0.0.1", local_ip]:
            self.is_local = True
            self.ssh_client = None
            self.logger.debug(f"Device {self.device.ip_address} is local")
        else:
            self.is_local = False
            # We assume credentials have been collected and key distributed
            # Use the username from previous steps or default to root/user
            # For now, we'll try to create a client assuming keys are set up
            # In a real flow, we might need to pass specific credentials
            self.ssh_client = self.ssh_manager.create_ssh_client(
                self.device.ip_address,
                username=self.username
            )
            if not self.ssh_client:
                raise ConnectionError(f"Failed to connect to {self.device.ip_address}")

    def execute_remote(self, command: str, timeout: int = 300) -> Tuple[int, str, str]:
        """
        Execute command on the target device (local or remote).
        
        Args:
            command: Command to execute
            timeout: Timeout in seconds
            
        Returns:
            (exit_code, stdout, stderr)
        """
        if self.is_local:
            import subprocess
            try:
                self.logger.debug(f"Executing local: {command}")
                
                # Handle sudo with password if needed
                if command.strip().startswith("sudo") and self.password:
                    # Use sudo -S to read password from stdin
                    cmd_parts = command.split(" ", 1)
                    if len(cmd_parts) > 1:
                        # Replace 'sudo' with 'sudo -S'
                        # We need to be careful not to replace inner sudos if any, but usually it's the start
                        final_command = f"sudo -S {cmd_parts[1]}"
                    else:
                        final_command = "sudo -S" # Just sudo?
                        
                    self.logger.debug("Using sudo -S with provided password")
                    result = subprocess.run(
                        final_command, 
                        shell=True, 
                        input=f"{self.password}\n",
                        capture_output=True, 
                        text=True, 
                        timeout=timeout
                    )
                else:
                    result = subprocess.run(
                        command, 
                        shell=True, 
                        capture_output=True, 
                        text=True, 
                        timeout=timeout
                    )
                return result.returncode, result.stdout.strip(), result.stderr.strip()
            except Exception as e:
                self.logger.error(f"Local execution failed: {e}")
                return -1, "", str(e)
        else:
            if not self.ssh_client:
                return -1, "", "SSH client not connected"
            return self.ssh_manager.execute_command(
                self.ssh_client, 
                command, 
                timeout, 
                sudo_password=self.password
            )

    def transfer_file(self, local_path: str, remote_path: str) -> bool:
        """
        Transfer file to target device.
        
        Args:
            local_path: Path to local file
            remote_path: Destination path on target
            
        Returns:
            True if successful
        """
        if self.is_local:
            try:
                # Use sudo cp via execute_remote to handle permissions
                # First ensure directory exists
                parent_dir = str(Path(remote_path).parent)
                self.create_directory(parent_dir)
                
                cmd = f"sudo cp {local_path} {remote_path}"
                code, _, err = self.execute_remote(cmd)
                if code != 0:
                    self.logger.error(f"Local file copy failed: {err}")
                    return False
                return True
            except Exception as e:
                self.logger.error(f"Local file copy failed: {e}")
                return False
        else:
            if not self.ssh_client:
                return False
            return self.ssh_manager.transfer_file(self.ssh_client, local_path, remote_path)

    def transfer_directory(self, local_dir: str, remote_dir: str, excludes: List[str] = None) -> bool:
        """
        Transfer directory to target device.
        
        Args:
            local_dir: Path to local directory
            remote_dir: Destination path on target
            excludes: List of glob patterns to exclude
            
        Returns:
            True if successful
        """
        if self.is_local:
            try:
                # Use sudo cp -r via execute_remote to handle permissions
                # Ensure parent of destination exists
                parent_dir = str(Path(remote_dir).parent)
                self.create_directory(parent_dir)
                
                # If remote_dir exists, cp -r source remote_dir will put source INSIDE remote_dir
                # We want the contents of source to be IN remote_dir
                # So we use cp -r source/. remote_dir/
                
                # First ensure remote_dir exists
                self.create_directory(remote_dir)
                
                # For local copy, we need to handle excludes manually or use rsync
                # Since we don't want to depend on rsync, we'll use a simpler approach for now:
                # Copy everything then delete excluded items. This is not ideal but works for local.
                # OR better: use tar with exclude patterns if available.
                
                # Let's try rsync if available, fallback to cp
                code, _, _ = self.execute_remote("which rsync")
                if code == 0:
                    exclude_args = ""
                    if excludes:
                        for pattern in excludes:
                            exclude_args += f" --exclude='{pattern}'"
                    
                    cmd = f"sudo rsync -av {exclude_args} {local_dir}/ {remote_dir}/"
                    code, _, err = self.execute_remote(cmd)
                    if code != 0:
                        self.logger.error(f"Local directory sync failed: {err}")
                        return False
                    return True
                else:
                    # Fallback to cp (no excludes support easily)
                    self.logger.warning("rsync not found, falling back to cp (excludes ignored)")
                    cmd = f"sudo cp -r {local_dir}/. {remote_dir}/"
                    code, _, err = self.execute_remote(cmd)
                    if code != 0:
                        self.logger.error(f"Local directory copy failed: {err}")
                        return False
                    return True
            except Exception as e:
                self.logger.error(f"Local directory copy failed: {e}")
                return False
        else:
            if not self.ssh_client:
                return False
            
            # For remote transfers, create directory with sudo first and set ownership
            # This allows SFTP to write files without permission issues
            parent_dir = str(Path(remote_dir).parent)
            self.create_directory(parent_dir)
            self.create_directory(remote_dir)
            
            # Set ownership to the user so SFTP can write
            code, _, err = self.execute_remote(f"sudo chown -R {self.username}:{self.username} {remote_dir}")
            if code != 0:
                self.logger.warning(f"Failed to set ownership on {remote_dir}: {err}")
            
            return self.ssh_manager.transfer_directory(self.ssh_client, local_dir, remote_dir, excludes=excludes, sudo_password=self.password)

    def create_directory(self, path: str) -> bool:
        """Create directory on target device."""
        if self.is_local:
            try:
                cmd = f"sudo mkdir -p {path}"
                code, _, err = self.execute_remote(cmd)
                if code != 0:
                    self.logger.error(f"Failed to create local directory {path}: {err}")
                    return False
                return True
            except Exception as e:
                self.logger.error(f"Failed to create local directory {path}: {e}")
                return False
        else:
            if not self.ssh_client:
                return False
            return self.ssh_manager.create_remote_directory(self.ssh_client, path, sudo_password=self.password)

    def get_package_manager(self) -> str:
        """
        Detect package manager on target device.
        
        Returns:
            'apt', 'yum', 'brew', 'choco', or 'unknown'
        """
        # Check for apt
        code, _, _ = self.execute_remote("which apt-get")
        if code == 0: return "apt"
        
        # Check for yum
        code, _, _ = self.execute_remote("which yum")
        if code == 0: return "yum"
        
        # Check for brew
        code, _, _ = self.execute_remote("which brew")
        if code == 0: return "brew"
        
        # Check for choco (Windows)
        code, _, _ = self.execute_remote("choco --version")
        if code == 0: return "choco"
        
        return "unknown"

    def install_packages(self, packages: List[str]) -> bool:
        """
        Install system packages using detected package manager.
        
        Args:
            packages: List of package names
            
        Returns:
            True if successful
        """
        pm = self.get_package_manager()
        pkg_list = " ".join(packages)
        
        if pm == "apt":
            cmd = f"DEBIAN_FRONTEND=noninteractive sudo apt-get install -y {pkg_list}"
        elif pm == "yum":
            cmd = f"sudo yum install -y {pkg_list}"
        elif pm == "brew":
            cmd = f"brew install {pkg_list}"
        elif pm == "choco":
            cmd = f"choco install -y {pkg_list}"
        else:
            self.logger.error("Unknown package manager")
            return False
            
        self.logger.info(f"Installing packages: {pkg_list} via {pm}")
        code, out, err = self.execute_remote(cmd, timeout=600)
        
        if code != 0:
            self.logger.error(f"Package installation failed: {err}")
            return False
        return True
    
    def wait_for_apt_lock(self, max_wait_seconds: int = 300) -> bool:
        """
        Wait for apt/dpkg lock to be released.
        
        Checks for lock files and waits with exponential backoff if they exist.
        
        Args:
            max_wait_seconds: Maximum time to wait (default 5 minutes)
            
        Returns:
            True if lock is free, False if timeout
        """
        lock_files = [
            "/var/lib/dpkg/lock",
            "/var/lib/dpkg/lock-frontend",
            "/var/cache/apt/archives/lock"
        ]
        
        wait_intervals = [5, 10, 20, 40, 60]  # Exponential backoff in seconds
        total_waited = 0
        attempt = 0
        
        while total_waited < max_wait_seconds:
            # Check if any lock files exist and are held by a process
            locks_held = []
            for lock_file in lock_files:
                code, out, _ = self.execute_remote(
                    f"sudo lsof {lock_file} 2\u003e/dev/null | grep -v COMMAND"
                )
                if code == 0 and out.strip():
                    # Lock is held
                    locks_held.append(lock_file)
            
            if not locks_held:
                # No locks held, we're good
                self.logger.debug("✓ No apt locks detected")
                return True
            
            # Locks are held, wait
            wait_time = wait_intervals[min(attempt, len(wait_intervals) - 1)]
            self.logger.info(
                f"⏳ apt/dpkg locks detected: {', '.join(locks_held)}"
            )
            self.logger.info(
                f"   Waiting {wait_time}s for locks to release... "
                f"({total_waited}/{max_wait_seconds}s elapsed)"
            )
            
            time.sleep(wait_time)
            total_waited += wait_time
            attempt += 1
        
        # Timeout reached
        self.logger.error(f"❌ Timeout waiting for apt locks after {max_wait_seconds}s")
        return False
    
    def install_packages_with_retry(self, packages: List[str], max_retries: int = 3) -> bool:
        """
        Install system packages with retry logic for lock conflicts.
        
        Args:
            packages: List of package names
            max_retries: Maximum number of retries
            
        Returns:
            True if successful
        """
        pm = self.get_package_manager()
        
        # Only use lock detection for apt-based systems
        if pm != "apt":
            return self.install_packages(packages)
        
        for attempt in range(max_retries):
            # Wait for any existing locks to clear
            if not self.wait_for_apt_lock():
                if attempt < max_retries - 1:
                    self.logger.warning(f"Lock timeout, retrying... (attempt {attempt + 1}/{max_retries})")
                    continue
                else:
                    self.logger.error("Failed to acquire apt lock after all retries")
                    return False
            
            # Try installation
            success = self.install_packages(packages)
            
            if success:
                return True
            
            # Check if failure was due to lock
            # If we reach here, install_packages failed - check if it's a lock error
            if attempt < max_retries - 1:
                self.logger.warning(f"Package installation failed, retrying... (attempt {attempt + 1}/{max_retries})")
                time.sleep(5)  # Brief pause before retry
            else:
                self.logger.error("Package installation failed after all retries")
                return False
        
        return False

    @abstractmethod
    def check_installed(self) -> bool:
        """Check if service is already installed."""
        pass

    @abstractmethod
    def install(self) -> bool:
        """Perform installation steps."""
        pass

    @abstractmethod
    def configure(self) -> bool:
        """Configure the service."""
        pass

    @abstractmethod
    def start(self) -> bool:
        """Start the service."""
        pass

    @abstractmethod
    def verify(self) -> bool:
        """Verify service health."""
        pass

    def run(self) -> bool:
        """
        Run the full installation process.
        
        Returns:
            True if successful
        """
        self.logger.info(f"Starting installation of {self.service_name} on {self.device.hostname}")
        
        if self.check_installed():
            self.logger.info(f"{self.service_name} is already installed.")
            # Even if installed, we might want to ensure it's configured and running
            if not self.configure(): return False
            if not self.start(): return False
            return self.verify()
        
        if not self.install():
            self.logger.error(f"Installation of {self.service_name} failed.")
            self.rollback()
            return False
            
        if not self.configure():
            self.logger.error(f"Configuration of {self.service_name} failed.")
            self.rollback()
            return False
            
        if not self.start():
            self.logger.error(f"Starting {self.service_name} failed.")
            return False
            
        if not self.verify():
            self.logger.error(f"Verification of {self.service_name} failed.")
            return False
            
        self.logger.info(f"Successfully installed and started {self.service_name}")
        return True

    def rollback(self):
        """Rollback installation on failure."""
        self.logger.warning(f"Rolling back {self.service_name} installation...")
        # Default implementation does nothing, override in subclasses
        pass
