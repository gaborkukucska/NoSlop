# START OF FILE seed/service_discovery.py
"""
Service Discovery for NoSlop Seed.

Scans local network for existing NoSlop-compatible services (Ollama, ComfyUI, PostgreSQL)
to enable service reuse and parallel processing.
"""

import socket
import requests
import logging
from dataclasses import dataclass, field
from typing import Optional, List, Dict, Any
from enum import Enum
import concurrent.futures
from urllib.parse import urlparse

logger = logging.getLogger(__name__)


class ServiceType(Enum):
    """Types of services that can be discovered."""
    OLLAMA = "ollama"
    COMFYUI = "comfyui"
    POSTGRESQL = "postgresql"
    NOSLOP_BACKEND = "noslop_backend"


@dataclass
class DiscoveredService:
    """
    Represents a discovered service on the network.
    """
    service_type: ServiceType
    host: str
    port: int
    version: Optional[str] = None
    capabilities: Dict[str, Any] = field(default_factory=dict)
    health_status: str = "unknown"  # healthy, degraded, unhealthy, unknown
    response_time_ms: float = 0.0
    
    @property
    def endpoint(self) -> str:
        """Get full endpoint URL."""
        return f"http://{self.host}:{self.port}"
    
    def to_dict(self) -> Dict:
        """Convert to dictionary for serialization."""
        return {
            "service_type": self.service_type.value,
            "host": self.host,
            "port": self.port,
            "endpoint": self.endpoint,
            "version": self.version,
            "capabilities": self.capabilities,
            "health_status": self.health_status,
            "response_time_ms": self.response_time_ms
        }


@dataclass
class OllamaService(DiscoveredService):
    """Ollama-specific service information."""
    available_models: List[str] = field(default_factory=list)
    
    def __post_init__(self):
        self.service_type = ServiceType.OLLAMA
        if self.available_models:
            self.capabilities["models"] = self.available_models
            self.capabilities["model_count"] = len(self.available_models)


@dataclass
class ComfyUIService(DiscoveredService):
    """ComfyUI-specific service information."""
    gpu_available: bool = False
    gpu_name: Optional[str] = None
    vram_total_mb: float = 0.0
    vram_free_mb: float = 0.0
    queue_remaining: int = 0
    
    def __post_init__(self):
        self.service_type = ServiceType.COMFYUI
        self.capabilities.update({
            "gpu_available": self.gpu_available,
            "gpu_name": self.gpu_name,
            "vram_total_mb": self.vram_total_mb,
            "vram_free_mb": self.vram_free_mb,
            "queue_remaining": self.queue_remaining
        })


@dataclass
class PostgreSQLService(DiscoveredService):
    """PostgreSQL-specific service information."""
    database_name: Optional[str] = None
    
    def __post_init__(self):
        self.service_type = ServiceType.POSTGRESQL
        if self.database_name:
            self.capabilities["database"] = self.database_name


class ServiceDiscovery:
    """
    Discovers existing NoSlop-compatible services on the local network.
    
    Supports detection of:
    - Ollama (LLM inference)
    - ComfyUI (generative AI)
    - PostgreSQL (database)
    - NoSlop Backend (existing installations)
    """
    
    def __init__(self, timeout: float = 2.0):
        """
        Initialize service discovery.
        
        Args:
            timeout: Connection timeout in seconds
        """
        self.timeout = timeout
        self.logger = logging.getLogger(__name__)
    
    def scan_network(self, ip_range: Optional[str] = None) -> List[DiscoveredService]:
        """
        Scan network for all NoSlop-compatible services.
        
        Args:
            ip_range: CIDR notation IP range to scan (e.g., "192.168.1.0/24"). 
                     If None, auto-detects local network.
        
        Returns:
            List of discovered services
        """
        if ip_range is None:
            from seed.network_scanner import NetworkScanner
            scanner = NetworkScanner()
            network = scanner.get_local_network()
            if network:
                ip_range = str(network)
            else:
                ip_range = "127.0.0.1/32" # Fallback to localhost
                
        self.logger.info(f"Scanning network {ip_range} for services...")
        discovered = []
        
        # Generate IP list from CIDR
        ips = self._generate_ip_list(ip_range)
        
        # Scan for each service type in parallel
        with concurrent.futures.ThreadPoolExecutor(max_workers=20) as executor:
            futures = []
            
            for ip in ips:
                # Ollama (default port 11434)
                futures.append(executor.submit(self.detect_ollama, ip, 11434))
                # Also check alternative ports for multi-instance
                futures.append(executor.submit(self.detect_ollama, ip, 11435))
                futures.append(executor.submit(self.detect_ollama, ip, 11436))
                
                # ComfyUI (default port 8188)
                futures.append(executor.submit(self.detect_comfyui, ip, 8188))
                # Alternative ports
                futures.append(executor.submit(self.detect_comfyui, ip, 8189))
                futures.append(executor.submit(self.detect_comfyui, ip, 8190))
                
                # PostgreSQL (default port 5432)
                futures.append(executor.submit(self.detect_postgresql, ip, 5432))
                
                # NoSlop Backend (default port 8000)
                futures.append(executor.submit(self.detect_noslop_backend, ip, 8000))
            
            # Collect results
            for future in concurrent.futures.as_completed(futures):
                result = future.result()
                if result:
                    discovered.append(result)
        
        self.logger.info(f"Discovery complete. Found {len(discovered)} services.")
        return discovered
    
    def detect_ollama(self, host: str, port: int = 11434) -> Optional[OllamaService]:
        """
        Detect Ollama instance at specified host and port.
        
        Args:
            host: Host IP or hostname
            port: Port number (default 11434)
        
        Returns:
            OllamaService if found, None otherwise
        """
        try:
            import time
            start_time = time.time()
            
            # Check if port is open
            if not self._is_port_open(host, port):
                return None
            
            # Try to get tags (list of models)
            url = f"http://{host}:{port}/api/tags"
            response = requests.get(url, timeout=self.timeout)
            
            response_time = (time.time() - start_time) * 1000  # ms
            
            if response.status_code == 200:
                data = response.json()
                models = [model.get("name", "") for model in data.get("models", [])]
                
                self.logger.info(f"Found Ollama at {host}:{port} with {len(models)} models")
                
                return OllamaService(
                    service_type=ServiceType.OLLAMA,
                    host=host,
                    port=port,
                    available_models=models,
                    health_status="healthy",
                    response_time_ms=response_time
                )
        except Exception as e:
            self.logger.debug(f"No Ollama at {host}:{port}: {e}")
        
        return None
    
    def detect_comfyui(self, host: str, port: int = 8188) -> Optional[ComfyUIService]:
        """
        Detect ComfyUI instance at specified host and port.
        
        Args:
            host: Host IP or hostname
            port: Port number (default 8188)
        
        Returns:
            ComfyUIService if found, None otherwise
        """
        try:
            import time
            start_time = time.time()
            
            # Check if port is open
            if not self._is_port_open(host, port):
                return None
            
            # Try to get system stats
            url = f"http://{host}:{port}/system_stats"
            response = requests.get(url, timeout=self.timeout)
            
            response_time = (time.time() - start_time) * 1000  # ms
            
            if response.status_code == 200:
                data = response.json()
                system = data.get("system", {})
                devices = system.get("devices", [])
                
                gpu_available = len(devices) > 0
                gpu_name = devices[0].get("name") if devices else None
                vram_total = devices[0].get("vram_total", 0) / (1024 * 1024) if devices else 0  # Convert to MB
                vram_free = devices[0].get("vram_free", 0) / (1024 * 1024) if devices else 0
                
                # Get queue info
                queue_url = f"http://{host}:{port}/queue"
                queue_response = requests.get(queue_url, timeout=self.timeout)
                queue_remaining = 0
                if queue_response.status_code == 200:
                    queue_data = queue_response.json()
                    queue_remaining = len(queue_data.get("queue_running", [])) + len(queue_data.get("queue_pending", []))
                
                self.logger.info(f"Found ComfyUI at {host}:{port} (GPU: {gpu_name or 'None'})")
                
                return ComfyUIService(
                    service_type=ServiceType.COMFYUI,
                    host=host,
                    port=port,
                    gpu_available=gpu_available,
                    gpu_name=gpu_name,
                    vram_total_mb=vram_total,
                    vram_free_mb=vram_free,
                    queue_remaining=queue_remaining,
                    health_status="healthy",
                    response_time_ms=response_time
                )
        except Exception as e:
            self.logger.debug(f"No ComfyUI at {host}:{port}: {e}")
        
        return None
    
    def detect_postgresql(self, host: str, port: int = 5432) -> Optional[PostgreSQLService]:
        """
        Detect PostgreSQL instance at specified host and port.
        
        Args:
            host: Host IP or hostname
            port: Port number (default 5432)
        
        Returns:
            PostgreSQLService if found, None otherwise
        """
        try:
            import time
            start_time = time.time()
            
            # Check if port is open (PostgreSQL specific check)
            if self._is_port_open(host, port):
                response_time = (time.time() - start_time) * 1000  # ms
                
                self.logger.info(f"Found PostgreSQL at {host}:{port}")
                
                return PostgreSQLService(
                    service_type=ServiceType.POSTGRESQL,
                    host=host,
                    port=port,
                    health_status="healthy",
                    response_time_ms=response_time
                )
        except Exception as e:
            self.logger.debug(f"No PostgreSQL at {host}:{port}: {e}")
        
        return None
    
    def detect_noslop_backend(self, host: str, port: int = 8000) -> Optional[DiscoveredService]:
        """
        Detect NoSlop Backend instance at specified host and port.
        
        Args:
            host: Host IP or hostname
            port: Port number (default 8000)
        
        Returns:
            DiscoveredService if found, None otherwise
        """
        try:
            import time
            start_time = time.time()
            
            # Check if port is open
            if not self._is_port_open(host, port):
                return None
            
            # Try to get health endpoint
            url = f"http://{host}:{port}/health"
            response = requests.get(url, timeout=self.timeout)
            
            response_time = (time.time() - start_time) * 1000  # ms
            
            if response.status_code == 200:
                data = response.json()
                
                self.logger.info(f"Found NoSlop Backend at {host}:{port}")
                
                return DiscoveredService(
                    service_type=ServiceType.NOSLOP_BACKEND,
                    host=host,
                    port=port,
                    health_status="healthy",
                    response_time_ms=response_time,
                    capabilities=data
                )
        except Exception as e:
            self.logger.debug(f"No NoSlop Backend at {host}:{port}: {e}")
        
        return None
    
    def get_service_capabilities(self, service: DiscoveredService) -> Dict[str, Any]:
        """
        Get detailed capabilities of a discovered service.
        
        Args:
            service: Discovered service
        
        Returns:
            Dictionary of capabilities
        """
        return service.capabilities
    
    def _is_port_open(self, host: str, port: int) -> bool:
        """
        Check if a port is open on a host.
        
        Args:
            host: Host IP or hostname
            port: Port number
        
        Returns:
            True if port is open, False otherwise
        """
        try:
            sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            sock.settimeout(self.timeout)
            result = sock.connect_ex((host, port))
            sock.close()
            return result == 0
        except Exception:
            return False
    
    def _generate_ip_list(self, cidr: str) -> List[str]:
        """
        Generate list of IPs from CIDR notation.
        
        Args:
            cidr: CIDR notation (e.g., "192.168.1.0/24")
        
        Returns:
            List of IP addresses
        """
        import ipaddress
        try:
            network = ipaddress.ip_network(cidr, strict=False)
            return [str(ip) for ip in network.hosts()]
        except Exception as e:
            self.logger.error(f"Invalid CIDR notation {cidr}: {e}")
            return []
