# START OF FILE seed/deployer.py
"""
Deployment Orchestrator for NoSlop Seed.

Main orchestration logic for deploying NoSlop across multiple devices.
"""

import logging
import json
from pathlib import Path
from typing import Dict, List, Optional
from datetime import datetime
# import paramiko # TODO

from models import DeploymentPlan, NodeAssignment, NodeRole
from ssh_manager import SSHManager

logger = logging.getLogger(__name__)


class Deployer:
    """
    Orchestrates the deployment of NoSlop across multiple devices.
    
    Handles:
    - Deployment plan validation
    - Configuration generation (.env files)
    - Service installation coordination
    - Progress tracking
    - Rollback on failure
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
        
        logger.info(f"Deployer initialized. Deployment ID: {self.deployment_id}")
        logger.info(f"Deployment directory: {self.deployment_dir}")
    
    def validate_plan(self, plan: DeploymentPlan) -> tuple[bool, str]:
        """
        Validate deployment plan before execution.
        
        Args:
            plan: Deployment plan to validate
            
        Returns:
            Tuple of (is_valid, error_message)
        """
        logger.info("Validating deployment plan...")
        
        # Use built-in validation
        is_valid, error = plan.validate()
        
        if not is_valid:
            logger.error(f"Deployment plan validation failed: {error}")
            return False, error
        
        # Additional checks
        for node in plan.nodes:
            # Check minimum requirements
            if not node.device.meets_minimum_requirements():
                logger.warning(
                    f"Device {node.device.hostname} does not meet minimum requirements. "
                    f"Deployment may fail or perform poorly."
                )
        
        logger.info("✓ Deployment plan is valid")
        return True, ""
    
    def save_deployment_plan(self, plan: DeploymentPlan):
        """
        Save deployment plan to JSON file.
        
        Args:
            plan: Deployment plan to save
        """
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
        """
        Generate environment configuration for a node.
        
        Args:
            node: Node assignment
            plan: Complete deployment plan
            
        Returns:
            Dictionary of environment variables
        """
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
        if NodeRole.MASTER in node.roles or NodeRole.ALL in node.roles:
            # This node runs the backend
            config["NOSLOP_BACKEND_URL"] = f"http://{node.device.ip_address}:8000"
            config["NOSLOP_DATABASE_URL"] = f"postgresql://noslop:noslop@localhost:5432/noslop"
            config["OLLAMA_URL"] = f"http://{node.device.ip_address}:11434"
        else:
            # Point to master node
            if plan.master_node:
                config["NOSLOP_BACKEND_URL"] = f"http://{plan.master_node.device.ip_address}:8000"
                config["OLLAMA_URL"] = f"http://{plan.master_node.device.ip_address}:11434"
        
        # ComfyUI URLs (find compute nodes)
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
        
        # Model preferences (from environment or defaults)
        config["NOSLOP_LOGIC_MODEL"] = "llama3.2:latest"
        config["NOSLOP_IMAGE_MODEL"] = "stable-diffusion"
        config["NOSLOP_VIDEO_MODEL"] = "animatediff"
        
        return config
    
    def write_env_file(
        self, 
        node: NodeAssignment, 
        config: Dict[str, str]
    ) -> Path:
        """
        Write .env file for a node.
        
        Args:
            node: Node assignment
            config: Environment configuration
            
        Returns:
            Path to the .env file
        """
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
    
    def deploy(self, plan: DeploymentPlan) -> bool:
        """
        Execute deployment plan.
        
        Args:
            plan: Validated deployment plan
            
        Returns:
            True if deployment succeeded
        """
        logger.info("="*70)
        logger.info(f"Starting NoSlop Deployment (ID: {self.deployment_id})")
        logger.info("="*70)
        
        # Validate plan
        is_valid, error = self.validate_plan(plan)
        if not is_valid:
            logger.error(f"Cannot deploy: {error}")
            return False
        
        # Save deployment plan
        self.save_deployment_plan(plan)
        
        # Generate configurations for all nodes
        logger.info("\n📝 Generating node configurations...")
        for node in plan.nodes:
            config = self.generate_node_config(node, plan)
            env_file = self.write_env_file(node, config)
            logger.info(f"  ✓ {node.device.hostname}: {env_file}")
        
        logger.info("\n🚀 Deployment plan ready!")
        logger.info(f"Deployment artifacts saved to: {self.deployment_dir}")
        
        # TODO: Implement actual service installation
        # This will be done in Phase 2 with service installers
        logger.warning("\n⚠️  Service installation not yet implemented")
        logger.warning("    This will be added in Phase 2 (Service Installers)")
        
        return True
    
    def get_deployment_summary(self, plan: DeploymentPlan) -> str:
        """
        Generate a human-readable deployment summary.
        
        Args:
            plan: Deployment plan
            
        Returns:
            Formatted summary string
        """
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
    
    from hardware_detector import HardwareDetector
    from role_assigner import RoleAssigner
    
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
    print("\n🚀 Executing deployment...\n")
    success = deployer.deploy(plan)
    
    if success:
        print("\n✅ Deployment preparation complete!")
        print(f"📁 Deployment artifacts: {deployer.deployment_dir}")
    else:
        print("\n❌ Deployment failed!")


if __name__ == "__main__":
    main()
