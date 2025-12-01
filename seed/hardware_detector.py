# START OF FILE seed/hardware_detector.py
"""
Hardware Detection Module for NoSlop Seed.

Detects system capabilities including CPU, RAM, GPU, disk, and OS information.
"""

import platform
import subprocess
import logging
import shutil
import psutil
from typing import Optional
import socket

from seed.models import (
    DeviceCapabilities,
    OSType,
    Architecture,
    GPUVendor
)
from seed.ssh_manager import SSHManager, SSHCredentials

logger = logging.getLogger(__name__)


class HardwareDetector:
    """
    Detects hardware capabilities of the current system.
    
    Uses psutil for cross-platform detection and specialized tools
    for GPU detection (nvidia-smi, rocm-smi, etc.).
    """
    
    def __init__(self):
        """Initialize hardware detector."""
        self.hostname = socket.gethostname()
        self.ip_address = self._get_ip_address()
        logger.info(f"Hardware detector initialized for {self.hostname}")
    
    def detect(self) -> DeviceCapabilities:
        """
        Detect all hardware capabilities of the current system.
        
        Returns:
            DeviceCapabilities object with complete system information
        """
        logger.info("Starting hardware detection...")
        
        capabilities = DeviceCapabilities(
            hostname=self.hostname,
            ip_address=self.ip_address,
            cpu_cores=self._detect_cpu_cores(),
            cpu_speed_ghz=self._detect_cpu_speed(),
            cpu_architecture=self._detect_architecture(),
            ram_total_gb=self._detect_ram_total(),
            ram_available_gb=self._detect_ram_available(),
            gpu_vendor=self._detect_gpu_vendor(),
            gpu_name=self._detect_gpu_name(),
            vram_total_gb=self._detect_vram_total(),
            vram_available_gb=self._detect_vram_available(),
            gpu_count=self._detect_gpu_count(),
            disk_total_gb=self._detect_disk_total(),
            disk_available_gb=self._detect_disk_available(),
            os_type=self._detect_os_type(),
            os_version=self._detect_os_version(),
            ssh_available=self._check_ssh_available(),
            ssh_port=22
        )
        
        logger.info(f"Hardware detection complete. Score: {capabilities.capability_score}")
        return capabilities
    
    def _get_ip_address(self) -> str:
        """Get the primary IP address of this machine."""
        try:
            # Create a socket to get the IP used for outbound connections
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.connect(("8.8.8.8", 80))
            ip = s.getsockname()[0]
            s.close()
            return ip
        except Exception as e:
            logger.warning(f"Could not detect IP address: {e}")
            return "127.0.0.1"
    
    def _detect_cpu_cores(self) -> int:
        """Detect number of CPU cores."""
        try:
            cores = psutil.cpu_count(logical=False) or psutil.cpu_count()
            logger.debug(f"Detected {cores} CPU cores")
            return cores
        except Exception as e:
            logger.error(f"Error detecting CPU cores: {e}")
            return 1
    
    def _detect_cpu_speed(self) -> float:
        """Detect CPU speed in GHz."""
        try:
            # Get CPU frequency
            freq = psutil.cpu_freq()
            if freq:
                speed_ghz = round(freq.max / 1000, 2) if freq.max else round(freq.current / 1000, 2)
                logger.debug(f"Detected CPU speed: {speed_ghz} GHz")
                return speed_ghz
            return 2.0  # Default assumption
        except Exception as e:
            logger.error(f"Error detecting CPU speed: {e}")
            return 2.0
    
    def _detect_architecture(self) -> Architecture:
        """Detect CPU architecture."""
        try:
            machine = platform.machine().lower()
            logger.debug(f"Platform machine: {machine}")
            
            if "x86_64" in machine or "amd64" in machine:
                return Architecture.X86_64
            elif "arm64" in machine:
                return Architecture.ARM64
            elif "aarch64" in machine:
                return Architecture.AARCH64
            else:
                return Architecture.UNKNOWN
        except Exception as e:
            logger.error(f"Error detecting architecture: {e}")
            return Architecture.UNKNOWN
    
    def _detect_ram_total(self) -> float:
        """Detect total RAM in GB."""
        try:
            ram_bytes = psutil.virtual_memory().total
            ram_gb = round(ram_bytes / (1024**3), 2)
            logger.debug(f"Detected total RAM: {ram_gb} GB")
            return ram_gb
        except Exception as e:
            logger.error(f"Error detecting total RAM: {e}")
            return 0.0
    
    def _detect_ram_available(self) -> float:
        """Detect available RAM in GB."""
        try:
            ram_bytes = psutil.virtual_memory().available
            ram_gb = round(ram_bytes / (1024**3), 2)
            logger.debug(f"Detected available RAM: {ram_gb} GB")
            return ram_gb
        except Exception as e:
            logger.error(f"Error detecting available RAM: {e}")
            return 0.0
    
    def _detect_gpu_vendor(self) -> GPUVendor:
        """Detect GPU vendor."""
        # Check for NVIDIA
        if shutil.which("nvidia-smi"):
            logger.debug("Detected NVIDIA GPU")
            return GPUVendor.NVIDIA
        
        # Check for AMD
        if shutil.which("rocm-smi") or shutil.which("rocminfo"):
            logger.debug("Detected AMD GPU")
            return GPUVendor.AMD
        
        # Check for Apple Silicon (M1/M2/M3)
        if platform.system() == "Darwin" and platform.machine() == "arm64":
            logger.debug("Detected Apple Silicon GPU")
            return GPUVendor.APPLE
        
        # Check for Intel GPU
        try:
            if platform.system() == "Linux":
                result = subprocess.run(
                    ["lspci"], 
                    capture_output=True, 
                    text=True, 
                    timeout=5
                )
                if "Intel" in result.stdout and ("VGA" in result.stdout or "3D" in result.stdout):
                    logger.debug("Detected Intel GPU")
                    return GPUVendor.INTEL
        except Exception as e:
            logger.debug(f"Error checking for Intel GPU: {e}")
        
        logger.debug("No dedicated GPU detected")
        return GPUVendor.NONE
    
    def _detect_gpu_name(self) -> Optional[str]:
        """Detect GPU name/model."""
        vendor = self._detect_gpu_vendor()
        
        try:
            if vendor == GPUVendor.NVIDIA:
                result = subprocess.run(
                    ["nvidia-smi", "--query-gpu=name", "--format=csv,noheader"],
                    capture_output=True,
                    text=True,
                    timeout=5
                )
                if result.returncode == 0:
                    name = result.stdout.strip().split('\n')[0]
                    logger.debug(f"Detected GPU name: {name}")
                    return name
            
            elif vendor == GPUVendor.AMD:
                result = subprocess.run(
                    ["rocm-smi", "--showproductname"],
                    capture_output=True,
                    text=True,
                    timeout=5
                )
                if result.returncode == 0:
                    lines = result.stdout.strip().split('\n')
                    for line in lines:
                        if "Card series" in line or "Card model" in line:
                            name = line.split(':')[-1].strip()
                            logger.debug(f"Detected GPU name: {name}")
                            return name
            
            elif vendor == GPUVendor.APPLE:
                # Get chip name from system_profiler
                result = subprocess.run(
                    ["system_profiler", "SPHardwareDataType"],
                    capture_output=True,
                    text=True,
                    timeout=5
                )
                if result.returncode == 0:
                    for line in result.stdout.split('\n'):
                        if "Chip:" in line:
                            name = line.split(':')[-1].strip()
                            logger.debug(f"Detected Apple chip: {name}")
                            return name
        
        except Exception as e:
            logger.debug(f"Error detecting GPU name: {e}")
        
        return None
    
    def _detect_vram_total(self) -> float:
        """Detect total VRAM in GB."""
        vendor = self._detect_gpu_vendor()
        
        try:
            if vendor == GPUVendor.NVIDIA:
                result = subprocess.run(
                    ["nvidia-smi", "--query-gpu=memory.total", "--format=csv,noheader,nounits"],
                    capture_output=True,
                    text=True,
                    timeout=5
                )
                if result.returncode == 0:
                    vram_mb = float(result.stdout.strip().split('\n')[0])
                    vram_gb = round(vram_mb / 1024, 2)
                    logger.debug(f"Detected VRAM: {vram_gb} GB")
                    return vram_gb
            
            elif vendor == GPUVendor.AMD:
                result = subprocess.run(
                    ["rocm-smi", "--showmeminfo", "vram"],
                    capture_output=True,
                    text=True,
                    timeout=5
                )
                if result.returncode == 0:
                    # Parse output for VRAM size
                    for line in result.stdout.split('\n'):
                        if "Total Memory" in line:
                            # Extract number
                            parts = line.split()
                            for i, part in enumerate(parts):
                                if part.replace('.', '').isdigit():
                                    vram_gb = float(part) / 1024  # Assuming MB
                                    logger.debug(f"Detected VRAM: {vram_gb} GB")
                                    return round(vram_gb, 2)
            
            elif vendor == GPUVendor.APPLE:
                # Apple Silicon uses unified memory
                # Return portion of RAM (approximate)
                ram_total = self._detect_ram_total()
                vram_estimate = ram_total * 0.5  # Rough estimate
                logger.debug(f"Estimated VRAM for Apple Silicon: {vram_estimate} GB")
                return vram_estimate
        
        except Exception as e:
            logger.debug(f"Error detecting VRAM: {e}")
        
        return 0.0
    
    def _detect_vram_available(self) -> float:
        """Detect available VRAM in GB."""
        vendor = self._detect_gpu_vendor()
        
        try:
            if vendor == GPUVendor.NVIDIA:
                result = subprocess.run(
                    ["nvidia-smi", "--query-gpu=memory.free", "--format=csv,noheader,nounits"],
                    capture_output=True,
                    text=True,
                    timeout=5
                )
                if result.returncode == 0:
                    vram_mb = float(result.stdout.strip().split('\n')[0])
                    vram_gb = round(vram_mb / 1024, 2)
                    return vram_gb
            
            # For other vendors, assume all VRAM is available initially
            return self._detect_vram_total()
        
        except Exception as e:
            logger.debug(f"Error detecting available VRAM: {e}")
        
        return 0.0
    
    def _detect_gpu_count(self) -> int:
        """Detect number of GPUs."""
        vendor = self._detect_gpu_vendor()
        
        if vendor == GPUVendor.NONE:
            return 0
        
        try:
            if vendor == GPUVendor.NVIDIA:
                result = subprocess.run(
                    ["nvidia-smi", "--list-gpus"],
                    capture_output=True,
                    text=True,
                    timeout=5
                )
                if result.returncode == 0:
                    count = len(result.stdout.strip().split('\n'))
                    logger.debug(f"Detected {count} NVIDIA GPU(s)")
                    return count
            
            # For other vendors, assume 1 GPU if detected
            return 1
        
        except Exception as e:
            logger.debug(f"Error detecting GPU count: {e}")
            return 1 if vendor != GPUVendor.NONE else 0
    
    def _detect_disk_total(self) -> float:
        """Detect total disk space in GB."""
        try:
            disk = psutil.disk_usage('/')
            disk_gb = round(disk.total / (1024**3), 2)
            logger.debug(f"Detected total disk: {disk_gb} GB")
            return disk_gb
        except Exception as e:
            logger.error(f"Error detecting total disk: {e}")
            return 0.0
    
    def _detect_disk_available(self) -> float:
        """Detect available disk space in GB."""
        try:
            disk = psutil.disk_usage('/')
            disk_gb = round(disk.free / (1024**3), 2)
            logger.debug(f"Detected available disk: {disk_gb} GB")
            return disk_gb
        except Exception as e:
            logger.error(f"Error detecting available disk: {e}")
            return 0.0
    
    def _detect_os_type(self) -> OSType:
        """Detect operating system type."""
        try:
            system = platform.system()
            logger.debug(f"Platform system: {system}")
            
            if system == "Linux":
                return OSType.LINUX
            elif system == "Darwin":
                return OSType.MACOS
            elif system == "Windows":
                return OSType.WINDOWS
            else:
                return OSType.UNKNOWN
        except Exception as e:
            logger.error(f"Error detecting OS type: {e}")
            return OSType.UNKNOWN
    
    def _detect_os_version(self) -> Optional[str]:
        """Detect operating system version."""
        try:
            version = platform.platform()
            logger.debug(f"OS version: {version}")
            return version
        except Exception as e:
            logger.error(f"Error detecting OS version: {e}")
            return None
    
    def _check_ssh_available(self) -> bool:
        """Check if SSH server is available on this machine."""
        try:
            # Check if SSH daemon is running
            if platform.system() == "Linux":
                result = subprocess.run(
                    ["systemctl", "is-active", "sshd"],
                    capture_output=True,
                    text=True,
                    timeout=5
                )
                return result.returncode == 0
            
            elif platform.system() == "Darwin":
                result = subprocess.run(
                    ["launchctl", "list"],
                    capture_output=True,
                    text=True,
                    timeout=5
                )
                return "com.openssh.sshd" in result.stdout
            
            elif platform.system() == "Windows":
                result = subprocess.run(
                    ["sc", "query", "sshd"],
                    capture_output=True,
                    text=True,
                    timeout=5
                )
                return "RUNNING" in result.stdout
            
            return False
        
        except Exception as e:
            logger.debug(f"Error checking SSH availability: {e}")
            return False
    
    def detect_remote(
        self,
        credentials: SSHCredentials,
        ssh_manager: SSHManager
    ) -> Optional[DeviceCapabilities]:
        """
        Detect hardware capabilities of a remote device via SSH.
        
        Args:
            credentials: SSH credentials for the remote device
            ssh_manager: SSHManager instance for remote command execution
            
        Returns:
            DeviceCapabilities object or None if detection fails
        """
        logger.info(f"Starting remote hardware detection for {credentials.ip_address}...")
        
        try:
            # First, detect the OS type
            os_type = self._detect_remote_os_type(credentials, ssh_manager)
            
            if os_type == OSType.UNKNOWN:
                logger.error(f"Could not detect OS type for {credentials.ip_address}")
                return None
            
            logger.info(f"Detected OS: {os_type.value}")
            
            # Route to OS-specific detection
            if os_type == OSType.LINUX:
                return self._detect_remote_linux(credentials, ssh_manager)
            elif os_type == OSType.MACOS:
                return self._detect_remote_macos(credentials, ssh_manager)
            elif os_type == OSType.WINDOWS:
                return self._detect_remote_windows(credentials, ssh_manager)
            else:
                logger.error(f"Unsupported OS type: {os_type.value}")
                return None
                
        except Exception as e:
            logger.error(f"Remote hardware detection failed for {credentials.ip_address}: {e}")
            return None
    
    def _detect_remote_os_type(
        self,
        credentials: SSHCredentials,
        ssh_manager: SSHManager
    ) -> OSType:
        """Detect OS type of remote device."""
        try:
            # Try uname first (works on Linux/macOS/Android)
            success, exit_code, stdout, stderr = ssh_manager.execute_remote_command(
                credentials, "uname -s", timeout=10
            )
            
            if success and exit_code == 0:
                os_name = stdout.strip().lower()
                logger.debug(f"Remote uname output: {os_name}")
                
                if "linux" in os_name:
                    return OSType.LINUX
                elif "darwin" in os_name:
                    return OSType.MACOS
            
            # Try Windows detection
            success, exit_code, stdout, stderr = ssh_manager.execute_remote_command(
                credentials, "ver", timeout=10
            )
            
            if success and exit_code == 0 and "Windows" in stdout:
                return OSType.WINDOWS
            
            # Try PowerShell method for Windows
            success, exit_code, stdout, stderr = ssh_manager.execute_remote_command(
                credentials, "echo %OS%", timeout=10
            )
            
            if success and exit_code == 0 and "Windows" in stdout:
                return OSType.WINDOWS
            
            logger.warning("Could not determine remote OS type")
            return OSType.UNKNOWN
            
        except Exception as e:
            logger.error(f"Error detecting remote OS type: {e}")
            return OSType.UNKNOWN
    
    def _detect_remote_linux(
        self,
        credentials: SSHCredentials,
        ssh_manager: SSHManager
    ) -> Optional[DeviceCapabilities]:
        """Detect hardware capabilities of a remote Linux device."""
        logger.info("Detecting Linux hardware...")
        
        try:
            # Get hostname
            _, _, hostname, _ = ssh_manager.execute_remote_command(
                credentials, "hostname", timeout=10
            )
            hostname = hostname.strip() or credentials.ip_address
            
            # CPU cores
            _, _, cpu_cores_str, _ = ssh_manager.execute_remote_command(
                credentials, "nproc", timeout=10
            )
            cpu_cores = int(cpu_cores_str.strip()) if cpu_cores_str.strip() else 1
            
            # CPU speed (from /proc/cpuinfo)
            _, _, cpuinfo, _ = ssh_manager.execute_remote_command(
                credentials, "grep 'cpu MHz' /proc/cpuinfo | head -1 | awk '{print $4}'", timeout=10
            )
            cpu_speed = round(float(cpuinfo.strip()) / 1000, 2) if cpuinfo.strip() else 2.0
            
            # Architecture
            _, _, arch_str, _ = ssh_manager.execute_remote_command(
                credentials, "uname -m", timeout=10
            )
            arch_str = arch_str.strip().lower()
            if "x86_64" in arch_str or "amd64" in arch_str:
                architecture = Architecture.X86_64
            elif "arm64" in arch_str:
                architecture = Architecture.ARM64
            elif "aarch64" in arch_str:
                architecture = Architecture.AARCH64
            else:
                architecture = Architecture.UNKNOWN
            
            # RAM (in KB from /proc/meminfo)
            _, _, meminfo, _ = ssh_manager.execute_remote_command(
                credentials, "grep MemTotal /proc/meminfo | awk '{print $2}'", timeout=10
            )
            ram_total_gb = round(int(meminfo.strip()) / (1024**2), 2) if meminfo.strip() else 0.0
            
            _, _, memavail, _ = ssh_manager.execute_remote_command(
                credentials, "grep MemAvailable /proc/meminfo | awk '{print $2}'", timeout=10
            )
            ram_available_gb = round(int(memavail.strip()) / (1024**2), 2) if memavail.strip() else 0.0
            
            # GPU detection - NVIDIA
            gpu_vendor = GPUVendor.NONE
            gpu_name = None
            vram_total_gb = 0.0
            vram_available_gb = 0.0
            gpu_count = 0
            
            success, exit_code, nvidia_check, _ = ssh_manager.execute_remote_command(
                credentials, "which nvidia-smi", timeout=10
            )
            if success and exit_code == 0 and nvidia_check.strip():
                gpu_vendor = GPUVendor.NVIDIA
                _, _, gpu_name_out, _ = ssh_manager.execute_remote_command(
                    credentials, "nvidia-smi --query-gpu=name --format=csv,noheader | head -1", timeout=10
                )
                gpu_name = gpu_name_out.strip() if gpu_name_out.strip() else None
                
                _, _, vram_out, _ = ssh_manager.execute_remote_command(
                    credentials, "nvidia-smi --query-gpu=memory.total --format=csv,noheader,nounits | head -1", timeout=10
                )
                if vram_out.strip():
                    vram_total_gb = round(float(vram_out.strip()) / 1024, 2)
                    vram_available_gb = vram_total_gb
                
                _, _, gpu_count_out, _ = ssh_manager.execute_remote_command(
                    credentials, "nvidia-smi --list-gpus | wc -l", timeout=10
                )
                gpu_count = int(gpu_count_out.strip()) if gpu_count_out.strip() else 1
            
            # Check for AMD GPU
            if gpu_vendor == GPUVendor.NONE:
                success, exit_code, amd_check, _ = ssh_manager.execute_remote_command(
                    credentials, "which rocm-smi", timeout=10
                )
                if success and exit_code == 0 and amd_check.strip():
                    gpu_vendor = GPUVendor.AMD
                    gpu_count = 1
            
            # Disk space
            _, _, disk_out, _ = ssh_manager.execute_remote_command(
                credentials, "df -BG / | tail -1 | awk '{print $2}' | sed 's/G//'", timeout=10
            )
            disk_total_gb = float(disk_out.strip()) if disk_out.strip() else 0.0
            
            _, _, disk_avail_out, _ = ssh_manager.execute_remote_command(
                credentials, "df -BG / | tail -1 | awk '{print $4}' | sed 's/G//'", timeout=10
            )
            disk_available_gb = float(disk_avail_out.strip()) if disk_avail_out.strip() else 0.0
            
            # OS version
            _, _, os_version, _ = ssh_manager.execute_remote_command(
                credentials, "cat /etc/os-release | grep PRETTY_NAME | cut -d '=' -f2 | tr -d '\"'", timeout=10
            )
            os_version = os_version.strip() if os_version.strip() else "Linux"
            
            capabilities = DeviceCapabilities(
                hostname=hostname,
                ip_address=credentials.ip_address,
                cpu_cores=cpu_cores,
                cpu_speed_ghz=cpu_speed,
                cpu_architecture=architecture,
                ram_total_gb=ram_total_gb,
                ram_available_gb=ram_available_gb,
                gpu_vendor=gpu_vendor,
                gpu_name=gpu_name,
                vram_total_gb=vram_total_gb,
                vram_available_gb=vram_available_gb,
                gpu_count=gpu_count,
                disk_total_gb=disk_total_gb,
                disk_available_gb=disk_available_gb,
                os_type=OSType.LINUX,
                os_version=os_version,
                ssh_available=True,
                ssh_port=credentials.port
            )
            
            logger.info(f"Remote Linux detection complete. Score: {capabilities.capability_score}")
            return capabilities
            
        except Exception as e:
            logger.error(f"Error detecting remote Linux hardware: {e}")
            return None
    
    def _detect_remote_macos(
        self,
        credentials: SSHCredentials,
        ssh_manager: SSHManager
    ) -> Optional[DeviceCapabilities]:
        """Detect hardware capabilities of a remote macOS device."""
        logger.info("Detecting macOS hardware...")
        
        try:
            # Get hostname
            _, _, hostname, _ = ssh_manager.execute_remote_command(
                credentials, "hostname", timeout=10
            )
            hostname = hostname.strip() or credentials.ip_address
            
            # CPU cores
            _, _, cpu_cores_str, _ = ssh_manager.execute_remote_command(
                credentials, "sysctl -n hw.physicalcpu", timeout=10
            )
            cpu_cores = int(cpu_cores_str.strip()) if cpu_cores_str.strip() else 1
            
            # CPU speed
            _, _, cpu_speed_str, _ = ssh_manager.execute_remote_command(
                credentials, "sysctl -n hw.cpufrequency", timeout=10
            )
            cpu_speed = round(int(cpu_speed_str.strip()) / 1e9, 2) if cpu_speed_str.strip() else 2.0
            
            # Architecture
            _, _, arch_str, _ = ssh_manager.execute_remote_command(
                credentials, "uname -m", timeout=10
            )
            arch_str = arch_str.strip().lower()
            if "arm64" in arch_str:
                architecture = Architecture.ARM64
            elif "x86_64" in arch_str:
                architecture = Architecture.X86_64
            else:
                architecture = Architecture.UNKNOWN
            
            # RAM
            _, _, ram_str, _ = ssh_manager.execute_remote_command(
                credentials, "sysctl -n hw.memsize", timeout=10
            )
            ram_total_gb = round(int(ram_str.strip()) / (1024**3), 2) if ram_str.strip() else 0.0
            ram_available_gb = ram_total_gb * 0.5  # Estimate
            
            # GPU detection (Apple Silicon or discrete)
            gpu_vendor = GPUVendor.NONE
            gpu_name = None
            vram_total_gb = 0.0
            gpu_count = 0
            
            if architecture == Architecture.ARM64:
                # Apple Silicon
                gpu_vendor = GPUVendor.APPLE
                _, _, chip_name, _ = ssh_manager.execute_remote_command(
                    credentials, "sysctl -n machdep.cpu.brand_string", timeout=10
                )
                gpu_name = chip_name.strip() if chip_name.strip() else "Apple Silicon"
                vram_total_gb = ram_total_gb * 0.5  # Unified memory estimate
                vram_available_gb = vram_total_gb
                gpu_count = 1
            
            # Disk space
            _, _, disk_out, _ = ssh_manager.execute_remote_command(
                credentials, "df -g / | tail -1 | awk '{print $2}'", timeout=10
            )
            disk_total_gb = float(disk_out.strip()) if disk_out.strip() else 0.0
            
            _, _, disk_avail_out, _ = ssh_manager.execute_remote_command(
                credentials, "df -g / | tail -1 | awk '{print $4}'", timeout=10
            )
            disk_available_gb = float(disk_avail_out.strip()) if disk_avail_out.strip() else 0.0
            
            # OS version
            _, _, os_version, _ = ssh_manager.execute_remote_command(
                credentials, "sw_vers -productVersion", timeout=10
            )
            os_version = f"macOS {os_version.strip()}" if os_version.strip() else "macOS"
            
            capabilities = DeviceCapabilities(
                hostname=hostname,
                ip_address=credentials.ip_address,
                cpu_cores=cpu_cores,
                cpu_speed_ghz=cpu_speed,
                cpu_architecture=architecture,
                ram_total_gb=ram_total_gb,
                ram_available_gb=ram_available_gb,
                gpu_vendor=gpu_vendor,
                gpu_name=gpu_name,
                vram_total_gb=vram_total_gb,
                vram_available_gb=vram_available_gb,
                gpu_count=gpu_count,
                disk_total_gb=disk_total_gb,
                disk_available_gb=disk_available_gb,
                os_type=OSType.MACOS,
                os_version=os_version,
                ssh_available=True,
                ssh_port=credentials.port
            )
            
            logger.info(f"Remote macOS detection complete. Score: {capabilities.capability_score}")
            return capabilities
            
        except Exception as e:
            logger.error(f"Error detecting remote macOS hardware: {e}")
            return None
    
    def _detect_remote_windows(
        self,
        credentials: SSHCredentials,
        ssh_manager: SSHManager
    ) -> Optional[DeviceCapabilities]:
        """Detect hardware capabilities of a remote Windows device."""
        logger.info("Detecting Windows hardware...")
        
        try:
            # Get hostname
            _, _, hostname, _ = ssh_manager.execute_remote_command(
                credentials, "hostname", timeout=10
            )
            hostname = hostname.strip() or credentials.ip_address
            
            # Use PowerShell for Windows detection
            # CPU cores
            _, _, cpu_cores_str, _ = ssh_manager.execute_remote_command(
                credentials, 
                "powershell -Command \"(Get-WmiObject -Class Win32_Processor).NumberOfCores\"",
                timeout=10
            )
            cpu_cores = int(cpu_cores_str.strip()) if cpu_cores_str.strip() else 1
            
            # RAM (in bytes)
            _, _, ram_str, _ = ssh_manager.execute_remote_command(
                credentials,
                "powershell -Command \"(Get-WmiObject -Class Win32_ComputerSystem).TotalPhysicalMemory\"",
                timeout=10
            )
            ram_total_gb = round(int(ram_str.strip()) / (1024**3), 2) if ram_str.strip() else 0.0
            ram_available_gb = ram_total_gb * 0.5  # Estimate
            
            # Simple GPU check for NVIDIA
            gpu_vendor = GPUVendor.NONE
            gpu_name = None
            vram_total_gb = 0.0
            gpu_count = 0
            
            success, exit_code, nvidia_check, _ = ssh_manager.execute_remote_command(
                credentials, "where nvidia-smi", timeout=10
            )
            if success and exit_code == 0:
                gpu_vendor = GPUVendor.NVIDIA
                gpu_count = 1
                # Try to get GPU name
                _, _, gpu_name_out, _ = ssh_manager.execute_remote_command(
                    credentials, "nvidia-smi --query-gpu=name --format=csv,noheader", timeout=10
                )
                gpu_name = gpu_name_out.strip() if gpu_name_out.strip() else "NVIDIA GPU"
            
            # Disk space
            _, _, disk_out, _ = ssh_manager.execute_remote_command(
                credentials,
                "powershell -Command \"[math]::Round((Get-PSDrive C).Free / 1GB + (Get-PSDrive C).Used / 1GB)\"",
                timeout=10
            )
            disk_total_gb = float(disk_out.strip()) if disk_out.strip() else 0.0
            
            _, _, disk_avail_out, _ = ssh_manager.execute_remote_command(
                credentials,
                "powershell -Command \"[math]::Round((Get-PSDrive C).Free / 1GB)\"",
                timeout=10
            )
            disk_available_gb = float(disk_avail_out.strip()) if disk_avail_out.strip() else 0.0
            
            # OS version
            _, _, os_version, _ = ssh_manager.execute_remote_command(
                credentials,
                "powershell -Command \"(Get-WmiObject -Class Win32_OperatingSystem).Caption\"",
                timeout=10
            )
            os_version = os_version.strip() if os_version.strip() else "Windows"
            
            capabilities = DeviceCapabilities(
                hostname=hostname,
                ip_address=credentials.ip_address,
                cpu_cores=cpu_cores,
                cpu_speed_ghz=2.0,  # Default for Windows
                cpu_architecture=Architecture.X86_64,
                ram_total_gb=ram_total_gb,
                ram_available_gb=ram_available_gb,
                gpu_vendor=gpu_vendor,
                gpu_name=gpu_name,
                vram_total_gb=vram_total_gb,
                vram_available_gb=vram_total_gb,
                gpu_count=gpu_count,
                disk_total_gb=disk_total_gb,
                disk_available_gb=disk_available_gb,
                os_type=OSType.WINDOWS,
                os_version=os_version,
                ssh_available=True,
                ssh_port=credentials.port
            )
            
            logger.info(f"Remote Windows detection complete. Score: {capabilities.capability_score}")
            return capabilities
            
        except Exception as e:
            logger.error(f"Error detecting remote Windows hardware: {e}")
            return None


def main():
    """Test hardware detection."""
    logging.basicConfig(level=logging.DEBUG)
    
    detector = HardwareDetector()
    capabilities = detector.detect()
    
    print("\n" + "="*60)
    print("Hardware Detection Results")
    print("="*60)
    print(f"Hostname: {capabilities.hostname}")
    print(f"IP Address: {capabilities.ip_address}")
    print(f"\nCPU: {capabilities.cpu_cores} cores @ {capabilities.cpu_speed_ghz} GHz")
    print(f"Architecture: {capabilities.cpu_architecture.value}")
    print(f"\nRAM: {capabilities.ram_total_gb} GB (Available: {capabilities.ram_available_gb} GB)")
    print(f"\nGPU: {capabilities.gpu_vendor.value}")
    if capabilities.gpu_name:
        print(f"  Model: {capabilities.gpu_name}")
    print(f"  VRAM: {capabilities.vram_total_gb} GB (Available: {capabilities.vram_available_gb} GB)")
    print(f"  Count: {capabilities.gpu_count}")
    print(f"\nDisk: {capabilities.disk_total_gb} GB (Available: {capabilities.disk_available_gb} GB)")
    print(f"\nOS: {capabilities.os_type.value}")
    print(f"Version: {capabilities.os_version}")
    print(f"\nSSH Available: {capabilities.ssh_available}")
    print(f"\nCapability Score: {capabilities.capability_score}/100")
    print(f"Meets Requirements: {capabilities.meets_minimum_requirements()}")
    print("="*60)


if __name__ == "__main__":
    main()
