# START OF FILE seed/service_registry.py
"""
Service Registry for NoSlop Seed.

Maintains registry of all service instances (existing + newly deployed)
for load balancing and failover support.
"""

import logging
import time
from dataclasses import dataclass, field
from typing import List, Dict, Optional, Any
from enum import Enum
import threading
import json
from pathlib import Path

from seed.service_discovery import ServiceType, DiscoveredService, OllamaService, ComfyUIService


logger = logging.getLogger(__name__)


class LoadBalancingStrategy(Enum):
    """Load balancing strategies."""
    ROUND_ROBIN = "round_robin"
    LEAST_LOADED = "least_loaded"
    CAPABILITY_BASED = "capability_based"


@dataclass
class ServiceInstance:
    """
    Represents a registered service instance.
    """
    instance_id: str
    service_type: ServiceType
    host: str
    port: int
    is_local: bool = False
    is_newly_deployed: bool = False
    current_load: float = 0.0  # 0.0 to 1.0
    last_health_check: float = 0.0
    health_status: str = "unknown"
    capabilities: Dict[str, Any] = field(default_factory=dict)
    
    @property
    def endpoint(self) -> str:
        """Get full endpoint URL."""
        return f"http://{self.host}:{self.port}"
    
    def to_dict(self) -> Dict:
        """Convert to dictionary for serialization."""
        return {
            "instance_id": self.instance_id,
            "service_type": self.service_type.value,
            "host": self.host,
            "port": self.port,
            "endpoint": self.endpoint,
            "is_local": self.is_local,
            "is_newly_deployed": self.is_newly_deployed,
            "current_load": self.current_load,
            "health_status": self.health_status,
            "capabilities": self.capabilities
        }


class ServiceRegistry:
    """
    Maintains registry of all service instances for load balancing.
    
    Features:
    - Track all Ollama instances (existing + newly deployed)
    - Track all ComfyUI instances (existing + newly deployed)
    - Monitor service health and availability
    - Provide load balancing recommendations
    - Support service failover
    """
    
    def __init__(self, registry_file: Optional[Path] = None):
        """
        Initialize service registry.
        
        Args:
            registry_file: Path to persist registry data (optional)
        """
        self.registry_file = registry_file or Path("deployment/service_registry.json")
        self.services: Dict[str, ServiceInstance] = {}
        self.round_robin_counters: Dict[ServiceType, int] = {}
        self.lock = threading.Lock()
        self.logger = logging.getLogger(__name__)
        
        # Load existing registry if available
        self._load_registry()
    
    def register_service(self, service: ServiceInstance) -> None:
        """
        Register a service instance.
        
        Args:
            service: Service instance to register
        """
        with self.lock:
            self.services[service.instance_id] = service
            self.logger.info(
                f"Registered {service.service_type.value} instance: "
                f"{service.endpoint} (ID: {service.instance_id})"
            )
            self._save_registry()
    
    def register_discovered_service(self, discovered: DiscoveredService) -> ServiceInstance:
        """
        Register a discovered service.
        
        Args:
            discovered: Discovered service
        
        Returns:
            Registered service instance
        """
        instance_id = f"{discovered.service_type.value}_{discovered.host}_{discovered.port}"
        
        instance = ServiceInstance(
            instance_id=instance_id,
            service_type=discovered.service_type,
            host=discovered.host,
            port=discovered.port,
            is_local=(discovered.host in ["localhost", "127.0.0.1"]),
            is_newly_deployed=False,
            health_status=discovered.health_status,
            capabilities=discovered.capabilities,
            last_health_check=time.time()
        )
        
        self.register_service(instance)
        return instance
    
    def unregister_service(self, instance_id: str) -> bool:
        """
        Unregister a service instance.
        
        Args:
            instance_id: Instance ID to unregister
        
        Returns:
            True if unregistered, False if not found
        """
        with self.lock:
            if instance_id in self.services:
                service = self.services.pop(instance_id)
                self.logger.info(f"Unregistered service: {instance_id}")
                self._save_registry()
                return True
            return False
    
    def get_available_ollama_instances(self) -> List[ServiceInstance]:
        """
        Get all available Ollama instances.
        
        Returns:
            List of healthy Ollama instances
        """
        with self.lock:
            return [
                service for service in self.services.values()
                if service.service_type == ServiceType.OLLAMA
                and service.health_status == "healthy"
            ]
    
    def get_available_comfyui_instances(self) -> List[ServiceInstance]:
        """
        Get all available ComfyUI instances.
        
        Returns:
            List of healthy ComfyUI instances
        """
        with self.lock:
            return [
                service for service in self.services.values()
                if service.service_type == ServiceType.COMFYUI
                and service.health_status == "healthy"
            ]
    
    def select_instance_for_task(
        self,
        service_type: ServiceType,
        requirements: Optional[Dict] = None,
        strategy: LoadBalancingStrategy = LoadBalancingStrategy.LEAST_LOADED
    ) -> Optional[ServiceInstance]:
        """
        Select best service instance for a task based on strategy.
        
        Args:
            service_type: Type of service needed
            requirements: Task requirements (e.g., required models, GPU)
            strategy: Load balancing strategy
        
        Returns:
            Selected service instance or None if none available
        """
        requirements = requirements or {}
        
        # Get available instances of the requested type
        with self.lock:
            candidates = [
                service for service in self.services.values()
                if service.service_type == service_type
                and service.health_status == "healthy"
            ]
        
        if not candidates:
            self.logger.warning(f"No healthy {service_type.value} instances available")
            return None
        
        # Filter by requirements
        if requirements:
            candidates = self._filter_by_requirements(candidates, requirements)
        
        if not candidates:
            self.logger.warning(
                f"No {service_type.value} instances match requirements: {requirements}"
            )
            return None
        
        # Select based on strategy
        if strategy == LoadBalancingStrategy.ROUND_ROBIN:
            return self._select_round_robin(service_type, candidates)
        elif strategy == LoadBalancingStrategy.LEAST_LOADED:
            return self._select_least_loaded(candidates)
        elif strategy == LoadBalancingStrategy.CAPABILITY_BASED:
            return self._select_capability_based(candidates, requirements)
        
        return candidates[0]  # Fallback
    
    def update_instance_load(self, instance_id: str, load: float) -> None:
        """
        Update current load of a service instance.
        
        Args:
            instance_id: Instance ID
            load: Current load (0.0 to 1.0)
        """
        with self.lock:
            if instance_id in self.services:
                self.services[instance_id].current_load = max(0.0, min(1.0, load))
                self.logger.debug(f"Updated load for {instance_id}: {load:.2f}")
    
    def update_health_status(self, instance_id: str, status: str) -> None:
        """
        Update health status of a service instance.
        
        Args:
            instance_id: Instance ID
            status: Health status (healthy, degraded, unhealthy)
        """
        with self.lock:
            if instance_id in self.services:
                self.services[instance_id].health_status = status
                self.services[instance_id].last_health_check = time.time()
                self.logger.info(f"Updated health for {instance_id}: {status}")
    
    def health_check_all(self) -> Dict[str, bool]:
        """
        Perform health check on all registered services.
        
        Returns:
            Dictionary mapping instance_id to health status (True=healthy)
        """
        from seed.service_discovery import ServiceDiscovery
        
        discovery = ServiceDiscovery(timeout=2.0)
        results = {}
        
        with self.lock:
            instances = list(self.services.values())
        
        for instance in instances:
            try:
                # Perform health check based on service type
                is_healthy = False
                
                if instance.service_type == ServiceType.OLLAMA:
                    service = discovery.detect_ollama(instance.host, instance.port)
                    is_healthy = service is not None
                elif instance.service_type == ServiceType.COMFYUI:
                    service = discovery.detect_comfyui(instance.host, instance.port)
                    is_healthy = service is not None
                elif instance.service_type == ServiceType.POSTGRESQL:
                    service = discovery.detect_postgresql(instance.host, instance.port)
                    is_healthy = service is not None
                
                results[instance.instance_id] = is_healthy
                
                # Update health status
                new_status = "healthy" if is_healthy else "unhealthy"
                self.update_health_status(instance.instance_id, new_status)
                
            except Exception as e:
                self.logger.error(f"Health check failed for {instance.instance_id}: {e}")
                results[instance.instance_id] = False
                self.update_health_status(instance.instance_id, "unhealthy")
        
        return results
    
    def get_service_stats(self) -> Dict[str, Any]:
        """
        Get statistics about registered services.
        
        Returns:
            Dictionary with service statistics
        """
        with self.lock:
            stats = {
                "total_services": len(self.services),
                "by_type": {},
                "by_status": {
                    "healthy": 0,
                    "degraded": 0,
                    "unhealthy": 0,
                    "unknown": 0
                },
                "newly_deployed": 0,
                "existing": 0
            }
            
            for service in self.services.values():
                # Count by type
                type_name = service.service_type.value
                stats["by_type"][type_name] = stats["by_type"].get(type_name, 0) + 1
                
                # Count by status
                stats["by_status"][service.health_status] += 1
                
                # Count deployment source
                if service.is_newly_deployed:
                    stats["newly_deployed"] += 1
                else:
                    stats["existing"] += 1
            
            return stats
    
    def _filter_by_requirements(
        self,
        candidates: List[ServiceInstance],
        requirements: Dict
    ) -> List[ServiceInstance]:
        """
        Filter service instances by requirements.
        
        Args:
            candidates: List of candidate instances
            requirements: Requirements dictionary
        
        Returns:
            Filtered list of instances
        """
        filtered = []
        
        for instance in candidates:
            meets_requirements = True
            
            # Check for required models (Ollama)
            if "required_model" in requirements:
                models = instance.capabilities.get("models", [])
                if requirements["required_model"] not in models:
                    meets_requirements = False
            
            # Check for GPU requirement (ComfyUI)
            if "requires_gpu" in requirements and requirements["requires_gpu"]:
                if not instance.capabilities.get("gpu_available", False):
                    meets_requirements = False
            
            # Check for minimum VRAM (ComfyUI)
            if "min_vram_mb" in requirements:
                vram = instance.capabilities.get("vram_free_mb", 0)
                if vram < requirements["min_vram_mb"]:
                    meets_requirements = False
            
            if meets_requirements:
                filtered.append(instance)
        
        return filtered
    
    def _select_round_robin(
        self,
        service_type: ServiceType,
        candidates: List[ServiceInstance]
    ) -> ServiceInstance:
        """Round-robin selection."""
        if service_type not in self.round_robin_counters:
            self.round_robin_counters[service_type] = 0
        
        index = self.round_robin_counters[service_type] % len(candidates)
        self.round_robin_counters[service_type] += 1
        
        return candidates[index]
    
    def _select_least_loaded(self, candidates: List[ServiceInstance]) -> ServiceInstance:
        """Select instance with lowest current load."""
        return min(candidates, key=lambda x: x.current_load)
    
    def _select_capability_based(
        self,
        candidates: List[ServiceInstance],
        requirements: Dict
    ) -> ServiceInstance:
        """
        Select instance based on capabilities.
        
        Prioritizes:
        1. Most available VRAM (for ComfyUI)
        2. Most models (for Ollama)
        3. Lowest load
        """
        def score_instance(instance: ServiceInstance) -> float:
            score = 0.0
            
            # VRAM availability (higher is better)
            vram_free = instance.capabilities.get("vram_free_mb", 0)
            score += vram_free / 1000.0  # Normalize
            
            # Model count (higher is better)
            model_count = instance.capabilities.get("model_count", 0)
            score += model_count * 10
            
            # Lower load is better
            score += (1.0 - instance.current_load) * 50
            
            return score
        
        return max(candidates, key=score_instance)
    
    def _save_registry(self) -> None:
        """Save registry to file."""
        try:
            self.registry_file.parent.mkdir(parents=True, exist_ok=True)
            
            data = {
                "services": {
                    instance_id: service.to_dict()
                    for instance_id, service in self.services.items()
                }
            }
            
            with open(self.registry_file, 'w') as f:
                json.dump(data, f, indent=2)
            
            self.logger.debug(f"Saved registry to {self.registry_file}")
        except Exception as e:
            self.logger.error(f"Failed to save registry: {e}")
    
    def _load_registry(self) -> None:
        """Load registry from file."""
        try:
            if self.registry_file.exists():
                with open(self.registry_file, 'r') as f:
                    data = json.load(f)
                
                for instance_id, service_data in data.get("services", {}).items():
                    # Reconstruct ServiceInstance
                    service = ServiceInstance(
                        instance_id=service_data["instance_id"],
                        service_type=ServiceType(service_data["service_type"]),
                        host=service_data["host"],
                        port=service_data["port"],
                        is_local=service_data.get("is_local", False),
                        is_newly_deployed=service_data.get("is_newly_deployed", False),
                        current_load=service_data.get("current_load", 0.0),
                        health_status=service_data.get("health_status", "unknown"),
                        capabilities=service_data.get("capabilities", {})
                    )
                    self.services[instance_id] = service
                
                self.logger.info(f"Loaded {len(self.services)} services from registry")
        except Exception as e:
            self.logger.warning(f"Could not load registry: {e}")
