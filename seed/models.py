# START OF FILE seed/models.py
"""
Data models for NoSlop Seed.

Represents device capabilities, roles, and deployment configurations.
"""

from dataclasses import dataclass, field
from typing import Optional, List, Dict
from enum import Enum


class OSType(Enum):
    """Supported operating system types."""
    LINUX = "linux"
    MACOS = "macos"
    WINDOWS = "windows"
    UNKNOWN = "unknown"


class Architecture(Enum):
    """CPU architectures."""
    X86_64 = "x86_64"
    ARM64 = "arm64"
    AARCH64 = "aarch64"
    UNKNOWN = "unknown"


class GPUVendor(Enum):
    """GPU vendors."""
    NVIDIA = "nvidia"
    AMD = "amd"
    INTEL = "intel"
    APPLE = "apple"
    NONE = "none"


class NodeRole(Enum):
    """Node roles in NoSlop cluster."""
    MASTER = "master"      # Runs backend, database, Admin AI
    COMPUTE = "compute"    # Runs ComfyUI, heavy AI tasks
    STORAGE = "storage"    # Stores media and blockchain
    CLIENT = "client"      # Frontend access point
    ALL = "all"           # Single device with all roles


@dataclass
class DeviceCapabilities:
    """
    Hardware capabilities of a device.
    
    Used for intelligent role assignment and service deployment.
    """
    # Identification
    hostname: str
    ip_address: str
    
    # CPU Information
    cpu_cores: int
    cpu_speed_ghz: float
    cpu_architecture: Architecture
    
    # Memory
    ram_total_gb: float
    ram_available_gb: float
    
    # GPU Information
    gpu_vendor: GPUVendor
    gpu_name: Optional[str] = None
    vram_total_gb: float = 0.0
    vram_available_gb: float = 0.0
    gpu_count: int = 0
    
    # Storage
    disk_total_gb: float = 0.0
    disk_available_gb: float = 0.0
    
    # Operating System
    os_type: OSType = OSType.UNKNOWN
    os_version: Optional[str] = None
    
    # Network
    ssh_available: bool = False
    ssh_port: int = 22
    
    # Computed score (for role assignment)
    capability_score: float = 0.0
    
    def __post_init__(self):
        """Calculate capability score after initialization."""
        self.capability_score = self.calculate_score()
    
    def calculate_score(self) -> float:
        """
        Calculate weighted capability score.
        
        Weights:
        - RAM: 40%
        - GPU/VRAM: 30%
        - CPU: 20%
        - Disk: 10%
        
        Returns:
            Capability score (0-100)
        """
        # Normalize values to 0-100 scale
        ram_score = min((self.ram_total_gb / 64) * 100, 100)  # 64GB = 100
        
        gpu_score = 0
        if self.gpu_vendor != GPUVendor.NONE:
            gpu_score = min((self.vram_total_gb / 24) * 100, 100)  # 24GB VRAM = 100
        
        cpu_score = min((self.cpu_cores / 16) * 100, 100)  # 16 cores = 100
        
        disk_score = min((self.disk_total_gb / 1024) * 100, 100)  # 1TB = 100
        
        # Weighted average
        total_score = (
            ram_score * 0.40 +
            gpu_score * 0.30 +
            cpu_score * 0.20 +
            disk_score * 0.10
        )
        
        return round(total_score, 2)
    
    def meets_minimum_requirements(self) -> bool:
        """
        Check if device meets minimum NoSlop requirements.
        
        Minimum (basic functionality):
        - Multi-core CPU (2+ cores)
        - 4GB RAM (can run smaller models)
        - 4GB VRAM (optional, but recommended for AI workloads)
        - 100GB disk space (for OS, services, and some media)
        
        Note: These are bare minimum specs. For better performance:
        - Recommended: 16GB RAM, 8GB+ VRAM, 500GB+ disk
        
        Returns:
            True if minimum requirements are met
        """
        return (
            self.cpu_cores >= 2 and
            self.ram_total_gb >= 4 and
            self.disk_total_gb >= 100
        )
    
    def to_dict(self) -> Dict:
        """Convert to dictionary for serialization."""
        return {
            "hostname": self.hostname,
            "ip_address": self.ip_address,
            "cpu": {
                "cores": self.cpu_cores,
                "speed_ghz": self.cpu_speed_ghz,
                "architecture": self.cpu_architecture.value
            },
            "ram": {
                "total_gb": self.ram_total_gb,
                "available_gb": self.ram_available_gb
            },
            "gpu": {
                "vendor": self.gpu_vendor.value,
                "name": self.gpu_name,
                "vram_total_gb": self.vram_total_gb,
                "vram_available_gb": self.vram_available_gb,
                "count": self.gpu_count
            },
            "disk": {
                "total_gb": self.disk_total_gb,
                "available_gb": self.disk_available_gb
            },
            "os": {
                "type": self.os_type.value,
                "version": self.os_version
            },
            "ssh": {
                "available": self.ssh_available,
                "port": self.ssh_port
            },
            "capability_score": self.capability_score,
            "meets_requirements": self.meets_minimum_requirements()
        }


@dataclass
class NodeAssignment:
    """
    Role assignment for a device in the NoSlop cluster.
    """
    device: DeviceCapabilities
    roles: List[NodeRole] = field(default_factory=list)
    services: List[str] = field(default_factory=list)  # Services to install
    
    def add_role(self, role: NodeRole):
        """Add a role to this node."""
        if role not in self.roles:
            self.roles.append(role)
    
    def add_service(self, service: str):
        """Add a service to install on this node."""
        if service not in self.services:
            self.services.append(service)
    
    def to_dict(self) -> Dict:
        """Convert to dictionary for serialization."""
        return {
            "device": self.device.to_dict(),
            "roles": [role.value for role in self.roles],
            "services": self.services
        }


@dataclass
class DeploymentPlan:
    """
    Complete deployment plan for NoSlop cluster.
    """
    nodes: List[NodeAssignment] = field(default_factory=list)
    is_single_device: bool = False
    master_node: Optional[NodeAssignment] = None
    
    def add_node(self, assignment: NodeAssignment):
        """Add a node to the deployment plan."""
        self.nodes.append(assignment)
        
        # Track master node
        if NodeRole.MASTER in assignment.roles or NodeRole.ALL in assignment.roles:
            self.master_node = assignment
    
    def get_node_by_role(self, role: NodeRole) -> List[NodeAssignment]:
        """Get all nodes with a specific role."""
        return [node for node in self.nodes if role in node.roles]
    
    def validate(self) -> tuple[bool, str]:
        """
        Validate deployment plan.
        
        Returns:
            (is_valid, error_message)
        """
        if not self.nodes:
            return False, "No nodes in deployment plan"
        
        if not self.master_node:
            return False, "No master node assigned"
        
        # Check for at least one compute node (or all-in-one)
        compute_nodes = self.get_node_by_role(NodeRole.COMPUTE)
        all_in_one = self.get_node_by_role(NodeRole.ALL)
        
        if not compute_nodes and not all_in_one:
            return False, "No compute node assigned"
        
        return True, ""
    
    def to_dict(self) -> Dict:
        """Convert to dictionary for serialization."""
        return {
            "is_single_device": self.is_single_device,
            "node_count": len(self.nodes),
            "master_node": self.master_node.to_dict() if self.master_node else None,
            "nodes": [node.to_dict() for node in self.nodes]
        }
