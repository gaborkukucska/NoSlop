# START OF FILE seed/deployer.py
"""
Deployment Orchestrator for NoSlop Seed.

Main orchestration logic for deploying NoSlop across multiple devices.
"""

import logging
import json
import time
from pathlib import Path
from typing import Dict, List, Optional
from datetime import datetime

from seed.models import DeploymentPlan, NodeAssignment, NodeRole
from seed.ssh_manager import SSHManager
from seed.service_discovery import ServiceDiscovery, ServiceType
from seed.service_registry import ServiceRegistry, ServiceInstance

# Installers
from seed.installers.postgresql_installer import PostgreSQLInstaller
from seed.installers.ollama_installer import OllamaInstaller
from seed.installers.comfyui_installer import ComfyUIInstaller
from seed.installers.ffmpeg_installer import FFmpegInstaller
from seed.installers.backend_installer import BackendInstaller
from seed.installers.frontend_installer import FrontendInstaller

logger = logging.getLogger(__name__)


class Deployer:
    """
    Orchestrates the deployment of NoSlop across multiple devices.
    """
    
    def __init__(
        self, 
        ssh_manager: SSHManager,
        output_dir: Optional[str] = None
    ):
        """
        Initialize deployer.
        
        Args:
            ssh_manager: SSH manager for remote connections
            output_dir: Directory for deployment artifacts (default: ~/.noslop/deployments)
        """
        self.ssh_manager = ssh_manager
        
        if output_dir is None:
            output_dir = Path.home() / ".noslop" / "deployments"
        
        self.output_dir = Path(output_dir)
        self.output_dir.mkdir(parents=True, exist_ok=True)
        
        # Create deployment timestamp
        self.deployment_id = datetime.now().strftime("%Y%m%d_%H%M%S")
        self.deployment_dir = self.output_dir / self.deployment_id
        self.deployment_dir.mkdir(parents=True, exist_ok=True)
        
        # Initialize registry
        self.registry = ServiceRegistry(self.deployment_dir / "service_registry.json")
        self.discovery = ServiceDiscovery()
        
        logger.info(f"Deployer initialized. Deployment ID: {self.deployment_id}")
        logger.info(f"Deployment directory: {self.deployment_dir}")
    
    def validate_plan(self, plan: DeploymentPlan) -> tuple[bool, str]:
        """Validate deployment plan before execution."""
        logger.info("Validating deployment plan...")
        
        is_valid, error = plan.validate()
        
        if not is_valid:
            logger.error(f"Deployment plan validation failed: {error}")
            return False, error
        
        # Additional checks
        for node in plan.nodes:
            if not node.device.meets_minimum_requirements():
                logger.warning(
                    f"Device {node.device.hostname} does not meet minimum requirements. "
                    f"Deployment may fail or perform poorly."
                )
        
        logger.info("✓ Deployment plan is valid")
        return True, ""
    
    def save_deployment_plan(self, plan: DeploymentPlan):
        """Save deployment plan to JSON file."""
        plan_file = self.deployment_dir / "deployment_plan.json"
        try:
            with open(plan_file, 'w') as f:
                json.dump(plan.to_dict(), f, indent=2)
            logger.info(f"Deployment plan saved to {plan_file}")
        except Exception as e:
            logger.error(f"Failed to save deployment plan: {e}")
    
    def generate_node_config(
        self, 
        node: NodeAssignment, 
        plan: DeploymentPlan
    ) -> Dict[str, str]:
        """Generate environment configuration for a node."""
        logger.debug(f"Generating config for {node.device.hostname}")
        
        config = {}
        
        # Basic identification
        config["NOSLOP_NODE_HOSTNAME"] = node.device.hostname
        config["NOSLOP_NODE_IP"] = node.device.ip_address
        config["NOSLOP_NODE_ROLES"] = ",".join([role.value for role in node.roles])
        
        # Master node configuration
        if plan.master_node:
            config["NOSLOP_MASTER_IP"] = plan.master_node.device.ip_address
            config["NOSLOP_MASTER_HOSTNAME"] = plan.master_node.device.hostname
        
        # Service URLs
        # We prefer using the registry if populated, otherwise fallback to plan
        # Since we generate config BEFORE installation, we use plan
        
        if NodeRole.MASTER in node.roles or NodeRole.ALL in node.roles:
            config["NOSLOP_BACKEND_URL"] = f"http://{node.device.ip_address}:8000"
            config["NOSLOP_DATABASE_URL"] = f"postgresql://noslop:noslop@localhost:5432/noslop"
            config["OLLAMA_URL"] = f"http://{node.device.ip_address}:11434"
        else:
            if plan.master_node:
                config["NOSLOP_BACKEND_URL"] = f"http://{plan.master_node.device.ip_address}:8000"
                config["OLLAMA_URL"] = f"http://{plan.master_node.device.ip_address}:11434"
        
        # ComfyUI URLs
        compute_nodes = plan.get_node_by_role(NodeRole.COMPUTE)
        if not compute_nodes:
            compute_nodes = plan.get_node_by_role(NodeRole.ALL)
        
        if compute_nodes:
            comfyui_urls = [
                f"http://{n.device.ip_address}:8188" 
                for n in compute_nodes
            ]
            config["COMFYUI_URLS"] = ",".join(comfyui_urls)
        
        # Storage paths
        if NodeRole.STORAGE in node.roles or NodeRole.ALL in node.roles:
            config["NOSLOP_MEDIA_PATH"] = "/var/noslop/media"
            config["NOSLOP_BLOCKCHAIN_PATH"] = "/var/noslop/blockchain"
        
        # Model preferences
        config["NOSLOP_LOGIC_MODEL"] = "llama3.2:latest"
        config["NOSLOP_IMAGE_MODEL"] = "stable-diffusion"
        config["NOSLOP_VIDEO_MODEL"] = "animatediff"
        
        return config
    
    def write_env_file(
        self, 
        node: NodeAssignment, 
        config: Dict[str, str]
    ) -> Path:
        """Write .env file for a node."""
        env_file = self.deployment_dir / f"{node.device.hostname}.env"
        try:
            with open(env_file, 'w') as f:
                f.write(f"# NoSlop Environment Configuration\n")
                f.write(f"# Node: {node.device.hostname}\n")
                f.write(f"# Generated: {datetime.now().isoformat()}\n\n")
                
                for key, value in sorted(config.items()):
                    f.write(f"{key}={value}\n")
            
            logger.info(f"Environment file created: {env_file}")
            return env_file
        except Exception as e:
            logger.error(f"Failed to write env file for {node.device.hostname}: {e}")
            raise
    
    def install_services(self, plan: DeploymentPlan, credentials_map: Dict) -> bool:
        """
        Install services on all nodes according to plan.

        Phases:
        1. Base Dependencies (FFmpeg, etc.)
        2. Core Infrastructure (PostgreSQL)
        3. AI Services (Ollama, ComfyUI)
        4. NoSlop Services (Backend, Frontend)
        """
        logger.info("\n🛠️  Starting Service Installation...")

        def get_username(device):
            return credentials_map.get(device.ip_address, {}).get("username", "root")

        # Phase 1: Base Dependencies
        logger.info("\n[Phase 1] Installing Base Dependencies...")
        for node in plan.nodes:
            if NodeRole.COMPUTE in node.roles or NodeRole.ALL in node.roles:
                installer = FFmpegInstaller(node.device, self.ssh_manager, username=get_username(node.device))
                if not installer.run():
                    logger.error(f"Failed to install FFmpeg on {node.device.hostname}")
                    return False

        # Phase 2: Core Infrastructure (PostgreSQL)
        logger.info("\n[Phase 2] Installing Core Infrastructure...")
        if plan.master_node:
            installer = PostgreSQLInstaller(plan.master_node.device, self.ssh_manager, username=get_username(plan.master_node.device))
            if not installer.run():
                logger.error("Failed to install PostgreSQL on master node")
                return False

            # Register PostgreSQL
            self.registry.register_service(ServiceInstance(
                instance_id=f"postgresql_{plan.master_node.device.ip_address}",
                service_type=ServiceType.POSTGRESQL,
                host=plan.master_node.device.ip_address,
                port=5432,
                is_newly_deployed=True,
                health_status="healthy"
            ))

        # Phase 3: AI Services
        logger.info("\n[Phase 3] Installing AI Services...")

        # Ollama (Master + Compute)
        for node in plan.nodes:
            if "ollama" in node.services:
                # Determine port (default 11434, but could be others if multi-instance)
                # For now we stick to default port for primary instance
                installer = OllamaInstaller(node.device, self.ssh_manager, username=get_username(node.device))
                if not installer.run():
                    logger.error(f"Failed to install Ollama on {node.device.hostname}")
                    return False

                # Register Ollama
                self.registry.register_service(ServiceInstance(
                    instance_id=f"ollama_{node.device.ip_address}_11434",
                    service_type=ServiceType.OLLAMA,
                    host=node.device.ip_address,
                    port=11434,
                    is_newly_deployed=True,
                    health_status="healthy"
                ))

        # ComfyUI (Compute)
        for node in plan.nodes:
            if "comfyui" in node.services:
                # Determine GPU index (default 0)
                installer = ComfyUIInstaller(node.device, self.ssh_manager, username=get_username(node.device))
                if not installer.run():
                    logger.error(f"Failed to install ComfyUI on {node.device.hostname}")
                    return False

                # Register ComfyUI
                self.registry.register_service(ServiceInstance(
                    instance_id=f"comfyui_{node.device.ip_address}_8188",
                    service_type=ServiceType.COMFYUI,
                    host=node.device.ip_address,
                    port=8188,
                    is_newly_deployed=True,
                    health_status="healthy"
                ))

        # Phase 4: NoSlop Services
        logger.info("\n[Phase 4] Installing NoSlop Services...")

        # Backend (Master)
        if plan.master_node:
            # Generate config for backend
            config = self.generate_node_config(plan.master_node, plan)
            installer = BackendInstaller(plan.master_node.device, self.ssh_manager, config, username=get_username(plan.master_node.device))
            if not installer.run():
                logger.error("Failed to install Backend on master node")
                return False

            # Register Backend
            self.registry.register_service(ServiceInstance(
                instance_id=f"backend_{plan.master_node.device.ip_address}",
                service_type=ServiceType.NOSLOP_BACKEND,
                host=plan.master_node.device.ip_address,
                port=8000,
                is_newly_deployed=True,
                health_status="healthy"
            ))

        # Frontend (Client/All)
        for node in plan.nodes:
            if "noslop-frontend" in node.services:
                config = self.generate_node_config(node, plan)
                installer = FrontendInstaller(node.device, self.ssh_manager, config, username=get_username(node.device))
                if not installer.run():
                    logger.error(f"Failed to install Frontend on {node.device.hostname}")
                    return False

        return True

    def deploy(self, plan: DeploymentPlan, credentials_map: Dict = None) -> bool:
        """Execute deployment plan."""
        logger.info("="*70)
        logger.info(f"Starting NoSlop Deployment (ID: {self.deployment_id})")
        logger.info("="*70)
        
        # Phase 0: Discovery
        logger.info("\n🔍 [Phase 0] Network Discovery...")
        discovered_services = self.discovery.scan_network()
        for service in discovered_services:
            self.registry.register_discovered_service(service)
        
        # Validate plan
        is_valid, error = self.validate_plan(plan)
        if not is_valid:
            logger.error(f"Cannot deploy: {error}")
            return False
        
        # Save deployment plan
        self.save_deployment_plan(plan)
        
        # Generate configurations
        logger.info("\n📝 Generating node configurations...")
        for node in plan.nodes:
            config = self.generate_node_config(node, plan)
            self.write_env_file(node, config)
        
        # Install Services
        if not self.install_services(plan):
            logger.error("\n❌ Service installation failed!")
            return False
        
        logger.info("\n✅ Deployment Complete!")
        logger.info(f"Deployment artifacts: {self.deployment_dir}")
        
        # Print Registry Summary
        stats = self.registry.get_service_stats()
        logger.info("\nService Registry Summary:")
        logger.info(json.dumps(stats, indent=2))
        
        return True
    
    def get_deployment_summary(self, plan: DeploymentPlan) -> str:
        """Generate a human-readable deployment summary."""
        lines = []
        lines.append("="*70)
        lines.append("NoSlop Deployment Summary")
        lines.append("="*70)
        lines.append(f"Deployment ID: {self.deployment_id}")
        lines.append(f"Deployment Type: {'Single Device' if plan.is_single_device else 'Multi-Device'}")
        lines.append(f"Total Nodes: {len(plan.nodes)}")
        lines.append("")
        
        # Count services
        all_services = set()
        for node in plan.nodes:
            all_services.update(node.services)
        
        lines.append(f"Services to Deploy: {', '.join(sorted(all_services))}")
        lines.append("")
        lines.append("Node Details:")
        lines.append("-"*70)
        
        for i, node in enumerate(plan.nodes, 1):
            lines.append(f"\n{i}. {node.device.hostname} ({node.device.ip_address})")
            lines.append(f"   Roles: {', '.join([r.value for r in node.roles])}")
            lines.append(f"   Services: {', '.join(node.services)}")
            lines.append(f"   Hardware: {node.device.cpu_cores} cores, "
                        f"{node.device.ram_total_gb}GB RAM, "
                        f"{node.device.vram_total_gb}GB VRAM")
        
        lines.append("\n" + "="*70)
        
        return "\n".join(lines)


def main():
    """Test deployer."""
    logging.basicConfig(
        level=logging.INFO,
        format='%(asctime)s - %(levelname)s - %(message)s'
    )
    
    from seed.hardware_detector import HardwareDetector
    from seed.role_assigner import RoleAssigner
    
    print("\n🧪 Testing NoSlop Deployer\n")
    
    # Detect current device
    detector = HardwareDetector()
    current_device = detector.detect()
    
    # Create deployment plan
    assigner = RoleAssigner()
    plan = assigner.create_deployment_plan([current_device])
    
    # Create deployer
    ssh_manager = SSHManager()
    deployer = Deployer(ssh_manager)
    
    # Print summary
    print(deployer.get_deployment_summary(plan))
    
    # Execute deployment
    # Note: We don't auto-execute in test main to avoid accidental installation
    print("\n⚠️  To execute deployment, run with --deploy flag")
    # if "--deploy" in sys.argv:
    #    deployer.deploy(plan)


if __name__ == "__main__":
    main()
