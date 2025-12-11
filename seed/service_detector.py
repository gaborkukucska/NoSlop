# START OF FILE seed/service_detector.py
"""
Service Detector for NoSlop Seed.

Detects pre-installed services on devices to avoid unnecessary reinstallation.
Checks for Ollama, ComfyUI, PostgreSQL and determines version compatibility.
"""

import logging
from dataclasses import dataclass
from typing import Optional

logger = logging.getLogger(__name__)


@dataclass
class DetectedService:
    """Represents a detected service on a device."""
    service_type: str  # ollama, comfyui, postgresql
    version: str
    install_path: str
    config_path: Optional[str] = None
    data_path: Optional[str] = None
    is_running: bool = False
    meets_requirements: bool = False


class ServiceDetector:
    """
    Detects existing service installations on devices.
    
    Helps avoid reinstalling services that are already present
    and meet minimum version requirements.
    """
    
    # Minimum required versions (not enforcing packaging import for now)
    MIN_VERSIONS = {
        "ollama": "0.1.0",
        "postgresql": "12.0",
    }
    
    def __init__(self, ssh_manager=None):
        self.ssh_manager = ssh_manager
    
    def _execute_on_device(self, device, ssh_client, command: str, timeout: int = 30):
        """Execute command on device (local or remote)."""
        if ssh_client is None:
            # Local execution
            import subprocess
            try:
                result = subprocess.run(
                    command,
                    shell=True,
                    capture_output=True,
                    text=True,
                    timeout=timeout
                )
                return result.returncode, result.stdout.strip(), result.stderr.strip()
            except Exception as e:
                return -1, "", str(e)
        else:
            # Remote execution
            if self.ssh_manager:
                return self.ssh_manager.execute_command(ssh_client, command, timeout)
            return -1, "", "No SSH manager available"
    
    def detect_ollama(self, device, ssh_client=None) -> Optional[DetectedService]:
        """
        Detect Ollama installation on device.
        
        Args:
            device: Device to check
            ssh_client: SSH client for remote access (None for local)
            
        Returns:
            DetectedService if Ollama is installed, None otherwise
        """
        logger.info(f"Checking for Ollama on {device.ip_address}...")
        
        # Check if ollama command exists
        code, out, _ = self._execute_on_device(device, ssh_client, "which ollama")
        if code != 0:
            logger.debug(f"Ollama not found on {device.ip_address}")
            return None
        
        install_path = out.strip()
        logger.debug(f"Found Ollama at {install_path}")
        
        # Get version
        code, out, err = self._execute_on_device(device, ssh_client, "ollama --version")
        if code != 0:
            logger.warning(f"Could not get Ollama version: {err}")
            version_str = "unknown"
        else:
            # Parse version from output like "ollama version 0.1.17"
            import re
            match = re.search(r'(\d+\.\d+\.\d+)', out)
            if match:
                version_str = match.group(1)
            else:
                version_str = "unknown"
        
        # Check if running
        code, out, _ = self._execute_on_device(
            device, ssh_client, 
            "systemctl is-active ollama 2>/dev/null || pgrep -f 'ollama serve' >/dev/null"
        )
        is_running = (code == 0)
        
        # Get data path (models directory)
        data_path = None
        code, out, _ = self._execute_on_device(
            device, ssh_client,
            "systemctl show ollama 2>/dev/null | grep 'Environment.*OLLAMA_MODELS' | cut -d= -f3 || echo '/usr/share/ollama/.ollama/models'"
        )
        if code == 0 and out:
            data_path = out.strip()
        
        # Check version compatibility (basic string comparison for now)
        meets_requirements = version_str != "unknown"
        
        detected = DetectedService(
            service_type="ollama",
            version=version_str,
            install_path=install_path,
            data_path=data_path,
            is_running=is_running,
            meets_requirements=meets_requirements
        )
        
        logger.info(
            f"✓ Ollama detected: version={version_str}, running={is_running}, "
            f"compatible={meets_requirements}, models={data_path}"
        )
        return detected
    
    def detect_comfyui(self, device, ssh_client=None) -> Optional[DetectedService]:
        """
        Detect ComfyUI installation on device.
        
        Args:
            device: Device to check
            ssh_client: SSH client for remote access (None for local)
            
        Returns:
            DetectedService if ComfyUI is installed, None otherwise
        """
        logger.info(f"Checking for ComfyUI on {device.ip_address}...")
        
        # Check common installation paths
        install_paths = ["/opt/ComfyUI", "/opt/comfyui", "~/ComfyUI", "/usr/local/ComfyUI"]
        found_path = None
        
        for path in install_paths:
            code, _, _ = self._execute_on_device(
                device, ssh_client,
                f"test -f {path}/main.py && test -f {path}/comfy/__init__.py"
            )
            if code == 0:
                found_path = path
                break
        
        if not found_path:
            logger.debug(f"ComfyUI not found on {device.ip_address}")
            return None
        
        logger.debug(f"Found ComfyUI at {found_path}")
        
        # ComfyUI doesn't have traditional versioning, check git commit
        version_str = "git"
        code, out, _ = self._execute_on_device(
            device, ssh_client,
            f"cd {found_path} && git log -1 --format='%h %ci' 2>/dev/null || echo 'unknown'"
        )
        if code == 0 and out != "unknown":
            version_str = out.strip()
        
        # Check if running (check port 8188)
        code, _, _ = self._execute_on_device(
            device, ssh_client,
            "netstat -tuln 2>/dev/null | grep ':8188 ' >/dev/null || ss -tuln 2>/dev/null | grep ':8188 ' >/dev/null"
        )
        is_running = (code == 0)
        
        # Get models path
        models_path = None
        code, out, _ = self._execute_on_device(
            device, ssh_client,
            f"readlink -f {found_path}/models 2>/dev/null || echo '{found_path}/models'"
        )
        if code == 0:
            models_path = out.strip()
        
        detected = DetectedService(
            service_type="comfyui",
            version=version_str,
            install_path=found_path,
            data_path=models_path,
            is_running=is_running,
            meets_requirements=True  # Since we just check existence
        )
        
        logger.info(
            f"✓ ComfyUI detected: path={found_path}, running={is_running}, models={models_path}"
        )
        return detected
    
    def detect_postgresql(self, device, ssh_client=None) -> Optional[DetectedService]:
        """
        Detect PostgreSQL installation on device.
        
        Args:
            device: Device to check
            ssh_client: SSH client for remote access (None for local)
            
        Returns:
            DetectedService if PostgreSQL is installed, None otherwise
        """
        logger.info(f"Checking for PostgreSQL on {device.ip_address}...")
        
        # Check if psql command exists
        code, out, _ = self._execute_on_device(device, ssh_client, "which psql")
        if code != 0:
            logger.debug(f"PostgreSQL not found on {device.ip_address}")
            return None
        
        install_path = out.strip()
        logger.debug(f"Found PostgreSQL at {install_path}")
        
        # Get version
        code, out, err = self._execute_on_device(device, ssh_client, "psql --version")
        if code != 0:
            logger.warning(f"Could not get PostgreSQL version: {err}")
            version_str = "unknown"
        else:
            # Parse version from output like "psql (PostgreSQL) 14.5"
            import re
            match = re.search(r'(\d+\.\d+)', out)
            if match:
                version_str = match.group(1)
            else:
                version_str = "unknown"
        
        # Check if running
        code, _, _ = self._execute_on_device(
            device, ssh_client,
            "systemctl is-active postgresql 2>/dev/null || pg_isready >/dev/null 2>&1"
        )
        is_running = (code == 0)
        
        # Get data directory
        data_path = None
        if is_running:
            code, out, _ = self._execute_on_device(
                device, ssh_client,
                "sudo -u postgres psql -t -P format=unaligned -c 'show data_directory;' 2>/dev/null"
            )
            if code == 0 and out:
                data_path = out.strip()
        
        # Check version compatibility (PostgreSQL 12.0+)
        meets_requirements = False
        if version_str != "unknown":
            try:
                major_version = int(version_str.split('.')[0])
                meets_requirements = major_version >= 12
            except Exception as e:
                logger.warning(f"Could not parse PostgreSQL version: {e}")
                meets_requirements = False
        
        detected = DetectedService(
            service_type="postgresql",
            version=version_str,
            install_path=install_path,
            data_path=data_path,
            is_running=is_running,
            meets_requirements=meets_requirements
        )
        
        logger.info(
            f"✓ PostgreSQL detected: version={version_str}, running={is_running}, "
            f"compatible={meets_requirements}"
        )
        return detected
    
    def detect_all_services(self, device, ssh_client=None) -> dict:
        """
        Detect all supported services on a device.
        
        Args:
            device: Device to check
            ssh_client: SSH client for remote access (None for local)
            
        Returns:
            Dictionary mapping service type to DetectedService (or None if not found)
        """
        logger.info(f"Detecting services on {device.ip_address}...")
        
        services = {
            "ollama": self.detect_ollama(device, ssh_client),
            "comfyui": self.detect_comfyui(device, ssh_client),
            "postgresql": self.detect_postgresql(device, ssh_client)
        }
        
        found_count = sum(1 for s in services.values() if s is not None)
        logger.info(f"Detection complete: found {found_count}/3 services on {device.ip_address}")
        
        return services
