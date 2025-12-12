# START OF FILE seed/role_assigner.py
"""
Role Assignment Module for NoSlop Seed.

Intelligently assigns roles to devices based on hardware capabilities.
"""

import logging
from typing import List, Dict
from seed.models import (
    DeviceCapabilities,
    NodeRole,
    NodeAssignment,
    DeploymentPlan,
    GPUVendor
)

logger = logging.getLogger(__name__)


class RoleAssigner:
    """
    Assigns roles to devices based on hardware capabilities.
    
    Uses weighted scoring to determine optimal role assignments:
    - RAM: 40%
    - GPU/VRAM: 30%
    - CPU: 20%
    - Disk: 10%
    """
    
    def __init__(self):
        """Initialize role assigner."""
        logger.info("Role assigner initialized")
    
    def create_deployment_plan(
        self, 
        devices: List[DeviceCapabilities]
    ) -> DeploymentPlan:
        """
        Create a complete deployment plan from discovered devices.
        
        Args:
            devices: List of discovered devices with capabilities
            
        Returns:
            DeploymentPlan with role assignments
        """
        if not devices:
            logger.error("No devices provided for deployment plan")
            raise ValueError("At least one device is required")
        
        logger.info(f"Creating deployment plan for {len(devices)} device(s)")
        
        # Check if single device deployment
        if len(devices) == 1:
            return self._create_single_device_plan(devices[0])
        
        # Multi-device deployment
        return self._create_multi_device_plan(devices)
    
    def _create_single_device_plan(
        self, 
        device: DeviceCapabilities
    ) -> DeploymentPlan:
        """
        Create deployment plan for single device (all-in-one mode).
        
        Args:
            device: The single device
            
        Returns:
            DeploymentPlan with ALL role assigned
        """
        logger.info(f"Creating single-device plan for {device.hostname}")
        
        # Check minimum requirements
        if not device.meets_minimum_requirements():
            logger.warning(
                f"Device {device.hostname} does not meet minimum requirements. "
                f"Score: {device.capability_score}/100"
            )
        
        # Create node assignment with ALL role
        assignment = NodeAssignment(device=device)
        assignment.add_role(NodeRole.ALL)
        
        # Add all services
        services = [
            "ollama",
            "comfyui",
            "ffmpeg",
            "opencv",
            "postgresql",
            "noslop-backend",
            "noslop-frontend"
        ]
        for service in services:
            assignment.add_service(service)
        
        # Create deployment plan
        plan = DeploymentPlan(is_single_device=True)
        plan.add_node(assignment)
        
        logger.info(f"Single-device plan created. Services: {services}")
        return plan
    
    def _create_multi_device_plan(
        self, 
        devices: List[DeviceCapabilities]
    ) -> DeploymentPlan:
        """
        Create deployment plan for multiple devices.
        
        Args:
            devices: List of devices
            
        Returns:
            DeploymentPlan with optimized role assignments
        """
        logger.info(f"Creating multi-device plan for {len(devices)} devices")
        
        # Sort devices by capability score (highest first)
        sorted_devices = sorted(
            devices, 
            key=lambda d: d.capability_score, 
            reverse=True
        )
        
        logger.debug("Device scores:")
        for device in sorted_devices:
            logger.debug(
                f"  {device.hostname}: {device.capability_score}/100 "
                f"(RAM: {device.ram_total_gb}GB, VRAM: {device.vram_total_gb}GB)"
            )
        
        # Create deployment plan
        plan = DeploymentPlan(is_single_device=False)
        
        # Assign roles
        self._assign_master_role(sorted_devices, plan)
        self._assign_compute_roles(sorted_devices, plan)
        self._assign_storage_roles(sorted_devices, plan)
        self._assign_client_roles(sorted_devices, plan)
        
        # Map services to roles
        self._map_services_to_nodes(plan)
        
        logger.info(f"Multi-device plan created with {len(plan.nodes)} nodes")
        return plan
    
    def _assign_master_role(
        self, 
        devices: List[DeviceCapabilities], 
        plan: DeploymentPlan
    ):
        """
        Assign MASTER role to the most capable device.
        
        Master node runs:
        - Backend (FastAPI)
        - Database (PostgreSQL/SQLite)
        - Admin AI (Ollama)
        
        Selection criteria:
        - Highest RAM (most important for backend + database)
        - Good CPU (for API handling)
        - Stable (not a mobile device)
        """
        master_device = devices[0]  # Already sorted by capability score
        
        logger.info(
            f"Assigning MASTER role to {master_device.hostname} "
            f"(score: {master_device.capability_score}/100)"
        )
        
        assignment = NodeAssignment(device=master_device)
        assignment.add_role(NodeRole.MASTER)
        plan.add_node(assignment)
    
    def _assign_compute_roles(
        self, 
        devices: List[DeviceCapabilities], 
        plan: DeploymentPlan
    ):
        """
        Assign COMPUTE role to devices with dedicated GPUs.
        
        Compute nodes run:
        - ComfyUI (image/video generation)
        - FFmpeg (video processing)
        - OpenCV (computer vision)
        
        Selection criteria:
        - Has dedicated GPU (NVIDIA/AMD preferred)
        - Sufficient VRAM (6GB+)
        """
        for device in devices:
            # Check if device has a dedicated GPU
            if device.gpu_vendor in [GPUVendor.NVIDIA, GPUVendor.AMD, GPUVendor.APPLE]:
                if device.vram_total_gb >= 6.0:
                    logger.info(
                        f"Assigning COMPUTE role to {device.hostname} "
                        f"({device.gpu_vendor.value}, {device.vram_total_gb}GB VRAM)"
                    )
                    
                    # Check if this device already has an assignment
                    existing = self._find_node_assignment(plan, device)
                    if existing:
                        existing.add_role(NodeRole.COMPUTE)
                    else:
                        assignment = NodeAssignment(device=device)
                        assignment.add_role(NodeRole.COMPUTE)
                        plan.add_node(assignment)
        
        # If no dedicated GPU devices found, assign compute to master
        compute_nodes = plan.get_node_by_role(NodeRole.COMPUTE)
        if not compute_nodes:
            logger.warning(
                "No dedicated GPU devices found. "
                "Assigning COMPUTE role to MASTER node."
            )
            master_node = plan.master_node
            if master_node:
                master_node.add_role(NodeRole.COMPUTE)
    
    def _assign_storage_roles(
        self, 
        devices: List[DeviceCapabilities], 
        plan: DeploymentPlan
    ):
        """
        Assign STORAGE role to devices with large disk space.
        
        Storage nodes store:
        - Generated media
        - Blockchain ledger
        - User uploads
        
        Selection criteria:
        - Large disk space (500GB+)
        """
        for device in devices:
            if device.disk_total_gb >= 500:
                logger.info(
                    f"Assigning STORAGE role to {device.hostname} "
                    f"({device.disk_total_gb}GB disk)"
                )
                
                existing = self._find_node_assignment(plan, device)
                if existing:
                    existing.add_role(NodeRole.STORAGE)
                else:
                    assignment = NodeAssignment(device=device)
                    assignment.add_role(NodeRole.STORAGE)
                    plan.add_node(assignment)
        
        # Ensure at least one storage node (use master if needed)
        storage_nodes = plan.get_node_by_role(NodeRole.STORAGE)
        if not storage_nodes:
            logger.warning(
                "No devices with 500GB+ disk. "
                "Assigning STORAGE role to MASTER node."
            )
            master_node = plan.master_node
            if master_node:
                master_node.add_role(NodeRole.STORAGE)
    
    def _assign_client_roles(
        self, 
        devices: List[DeviceCapabilities], 
        plan: DeploymentPlan
    ):
        """
        Assign CLIENT role to all devices.
        
        Client nodes run:
        - Frontend (Next.js)
        
        All devices can serve as access points to the NoSlop network.
        """
        for device in devices:
            logger.debug(f"Assigning CLIENT role to {device.hostname}")
            
            existing = self._find_node_assignment(plan, device)
            if existing:
                existing.add_role(NodeRole.CLIENT)
            else:
                assignment = NodeAssignment(device=device)
                assignment.add_role(NodeRole.CLIENT)
                plan.add_node(assignment)
    
    def _map_services_to_nodes(self, plan: DeploymentPlan):
        """
        Intelligently map services to nodes based on hardware capabilities.
        
        Strategy:
        - Core services (backend, database): Master node only
        - Frontend: Master node + low-spec devices (for access points)
        - AI services (ollama, comfyui): Distributed across capable devices
        - Media processing (ffmpeg, opencv): Distributed to devices with sufficient resources
        
        This approach maximizes hardware utilization and enables load balancing.
        """
        logger.info("Mapping services to nodes based on hardware capabilities...")
        
        # 1. Core Services - Deploy to Master Node Only
        master_node = plan.master_node
        if master_node:
            master_node.add_service("noslop-backend")
            master_node.add_service("postgresql")
            # Frontend will be assigned later if needed (to avoid duplicate with client nodes)
            logger.info(
                f"Core services assigned to {master_node.device.hostname}: "
                f"backend, postgresql"
            )
        
        # 2. Ollama Distribution - Deploy to All Devices with Sufficient RAM
        # Threshold: 8GB RAM (can run small to medium models)
        ollama_count = 0
        for node in plan.nodes:
            if node.device.ram_total_gb >= 8.0:
                node.add_service("ollama")
                ollama_count += 1
                logger.info(
                    f"Ollama assigned to {node.device.hostname} "
                    f"({node.device.ram_total_gb:.1f}GB RAM available)"
                )
        
        if ollama_count == 0:
            logger.warning("No devices meet Ollama RAM requirements (8GB+)")
        else:
            logger.info(f"Ollama will run on {ollama_count} device(s) for load balancing")
        
        # 3. ComfyUI Distribution - Deploy to All Devices with Capable GPUs
        # Threshold: 4GB+ VRAM (can run SDXL with optimizations)
        comfyui_count = 0
        for node in plan.nodes:
            if node.device.gpu_vendor in [GPUVendor.NVIDIA, GPUVendor.AMD, GPUVendor.APPLE]:
                if node.device.vram_total_gb >= 4.0:
                    node.add_service("comfyui")
                    comfyui_count += 1
                    logger.info(
                        f"ComfyUI assigned to {node.device.hostname} "
                        f"({node.device.gpu_vendor.value}, {node.device.vram_total_gb:.1f}GB VRAM)"
                    )
        
        if comfyui_count == 0:
            logger.warning("No devices meet ComfyUI GPU requirements (4GB+ VRAM)")
        else:
            logger.info(f"ComfyUI will run on {comfyui_count} device(s) for parallel generation")
        
        # 4. FFmpeg/OpenCV Distribution - Deploy to Devices with Good CPU or GPU
        # Criteria: 4+ cores OR has GPU (for hardware acceleration)
        media_processing_count = 0
        for node in plan.nodes:
            has_good_cpu = node.device.cpu_cores >= 4
            has_gpu = node.device.gpu_vendor != GPUVendor.NONE
            
            if has_good_cpu or has_gpu:
                node.add_service("ffmpeg")
                node.add_service("opencv")
                media_processing_count += 1
                reason = []
                if has_good_cpu:
                    reason.append(f"{node.device.cpu_cores} CPU cores")
                if has_gpu:
                    reason.append(f"{node.device.gpu_vendor.value} GPU")
                logger.info(
                    f"Media processing assigned to {node.device.hostname} "
                    f"({', '.join(reason)})"
                )
        
        if media_processing_count == 0:
            logger.warning("No devices meet media processing requirements")
        else:
            logger.info(
                f"Media processing will run on {media_processing_count} device(s) "
                f"for distributed encoding"
            )
        
        # 5. Frontend for Low-Spec Devices - Utilize devices that don't meet other thresholds
        # Deploy frontend to devices with no other services (access points)
        for node in plan.nodes:
            if len(node.services) == 0:
                node.add_service("noslop-frontend")
                logger.info(
                    f"Frontend assigned to {node.device.hostname} "
                    f"(provides network access point)"
                )
        
        # Log summary
        logger.info("Service distribution summary:")
        for node in plan.nodes:
            if len(node.services) > 0:
                logger.info(
                    f"  {node.device.hostname}: {len(node.services)} service(s) - "
                    f"{', '.join(node.services)}"
                )
            else:
                logger.info(f"  {node.device.hostname}: 0 services (below all thresholds)")
        
        # Final Check: Ensure Frontend is assigned somewhere
        frontend_assigned = False
        for node in plan.nodes:
            if "noslop-frontend" in node.services:
                frontend_assigned = True
                break
        
        if not frontend_assigned and master_node:
            master_node.add_service("noslop-frontend")
            logger.info(f"Frontend assigned to Master {master_node.device.hostname} (fallback)")
    
    def _find_node_assignment(
        self, 
        plan: DeploymentPlan, 
        device: DeviceCapabilities
    ) -> NodeAssignment:
        """
        Find existing node assignment for a device.
        
        Args:
            plan: Deployment plan
            device: Device to find
            
        Returns:
            NodeAssignment if found, None otherwise
        """
        for node in plan.nodes:
            if node.device.hostname == device.hostname:
                return node
        return None
    
    def print_deployment_plan(self, plan: DeploymentPlan):
        """
        Print a human-readable deployment plan summary.
        
        Args:
            plan: Deployment plan to print
        """
        print("\n" + "="*70)
        print("NoSlop Deployment Plan")
        print("="*70)
        
        if plan.is_single_device:
            print("\n🖥️  Single Device Deployment (All-in-One Mode)")
        else:
            print(f"\n🌐 Multi-Device Deployment ({len(plan.nodes)} nodes)")
        
        print("\nNode Assignments:")
        print("-"*70)
        
        for i, node in enumerate(plan.nodes, 1):
            device = node.device
            print(f"\n{i}. {device.hostname} ({device.ip_address})")
            print(f"   Capability Score: {device.capability_score}/100")
            print(f"   Hardware: {device.cpu_cores} cores, "
                  f"{device.ram_total_gb}GB RAM, "
                  f"{device.vram_total_gb}GB VRAM")
            print(f"   Roles: {', '.join([role.value for role in node.roles])}")
            print(f"   Services: {', '.join(node.services)}")
        
        # Validation
        is_valid, error = plan.validate()
        print("\n" + "-"*70)
        if is_valid:
            print("✅ Deployment plan is valid")
        else:
            print(f"❌ Deployment plan is invalid: {error}")
        
        print("="*70 + "\n")


def main():
    """Test role assignment."""
    logging.basicConfig(level=logging.DEBUG)
    
    from hardware_detector import HardwareDetector
    
    # Detect current device
    detector = HardwareDetector()
    current_device = detector.detect()
    
    # Create role assigner
    assigner = RoleAssigner()
    
    # Test single device plan
    print("\n🧪 Testing Single Device Deployment:")
    plan = assigner.create_deployment_plan([current_device])
    assigner.print_deployment_plan(plan)
    
    # Test multi-device plan (simulate multiple devices)
    print("\n🧪 Testing Multi-Device Deployment (simulated):")
    
    # Create simulated devices with different capabilities
    from copy import deepcopy
    
    device1 = deepcopy(current_device)
    device1.hostname = "master-node"
    device1.ip_address = "192.168.1.10"
    device1.ram_total_gb = 64.0
    device1.vram_total_gb = 24.0
    device1.gpu_vendor = GPUVendor.NVIDIA
    
    device2 = deepcopy(current_device)
    device2.hostname = "compute-node-1"
    device2.ip_address = "192.168.1.11"
    device2.ram_total_gb = 32.0
    device2.vram_total_gb = 12.0
    device2.gpu_vendor = GPUVendor.NVIDIA
    
    device3 = deepcopy(current_device)
    device3.hostname = "storage-node"
    device3.ip_address = "192.168.1.12"
    device3.ram_total_gb = 16.0
    device3.vram_total_gb = 0.0
    device3.gpu_vendor = GPUVendor.NONE
    device3.disk_total_gb = 2000.0
    
    # Recalculate scores
    device1.capability_score = device1.calculate_score()
    device2.capability_score = device2.calculate_score()
    device3.capability_score = device3.calculate_score()
    
    multi_plan = assigner.create_deployment_plan([device1, device2, device3])
    assigner.print_deployment_plan(multi_plan)


if __name__ == "__main__":
    main()
