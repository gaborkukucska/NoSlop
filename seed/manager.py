# START OF FILE seed/manager.py
"""
Service Manager for NoSlop Seed.

Manages deployed services across single or multi-device installations.
Provides start, stop, restart, status, and uninstall functionality.
"""

import logging
import json
from pathlib import Path
from typing import List, Dict, Optional
from datetime import datetime

from seed.models import DeploymentPlan, NodeAssignment
from seed.ssh_manager import SSHManager
from seed.service_registry import ServiceRegistry, ServiceType

logger = logging.getLogger(__name__)



class ServiceManager:
    """
    Manages NoSlop services across deployed nodes.
    
    Handles start, stop, restart, status, and uninstall operations.
    """
    
    def __init__(self, deployment_dir: Path, ssh_manager: SSHManager):
        """
        Initialize service manager.
        
        Args:
            deployment_dir: Path to deployment artifacts directory
            ssh_manager: SSH manager for remote operations
        """
        self.deployment_dir = deployment_dir
        self.ssh_manager = ssh_manager
        
        # Load deployment plan
        plan_file = deployment_dir / "deployment_plan.json"
        if not plan_file.exists():
            raise FileNotFoundError(f"Deployment plan not found: {plan_file}")
        
        with open(plan_file, 'r') as f:
            plan_data = json.load(f)
        
        # Reconstruct deployment plan from JSON
        from seed.models import DeviceCapabilities, GPUVendor, OSType, Architecture, NodeRole
        
        self.plan = DeploymentPlan()
        self.plan.is_single_device = plan_data.get('is_single_device', False)
        
        for node_data in plan_data.get('nodes', []):
            # Reconstruct device from nested JSON structure
            device_data = node_data['device']
            
            # Extract nested data
            cpu_data = device_data.get('cpu', {})
            ram_data = device_data.get('ram', {})
            gpu_data = device_data.get('gpu', {})
            disk_data = device_data.get('disk', {})
            os_data = device_data.get('os', {})
            
            # Determine GPU vendor
            gpu_vendor_str = gpu_data.get('vendor', 'none')
            if gpu_vendor_str == 'nvidia':
                gpu_vendor = GPUVendor.NVIDIA
            elif gpu_vendor_str == 'amd':
                gpu_vendor = GPUVendor.AMD
            elif gpu_vendor_str == 'apple':
                gpu_vendor = GPUVendor.APPLE
            else:
                gpu_vendor = GPUVendor.NONE
            
            # Determine OS type
            os_type_str = os_data.get('type', 'linux')
            if os_type_str == 'linux':
                os_type = OSType.LINUX
            elif os_type_str == 'macos':
                os_type = OSType.MACOS
            elif os_type_str == 'windows':
                os_type = OSType.WINDOWS
            else:
                os_type = OSType.LINUX
            
            # Determine architecture
            arch_str = cpu_data.get('architecture', 'x86_64')
            if arch_str == 'x86_64':
                arch = Architecture.X86_64
            elif arch_str == 'arm64':
                arch = Architecture.ARM64
            else:
                arch = Architecture.X86_64
            
            device = DeviceCapabilities(
                hostname=device_data['hostname'],
                ip_address=device_data['ip_address'],
                cpu_cores=cpu_data.get('cores', 1),
                cpu_speed_ghz=cpu_data.get('speed_ghz', 1.0),
                cpu_architecture=arch,
                ram_total_gb=ram_data.get('total_gb', 1.0),
                ram_available_gb=ram_data.get('available_gb', 1.0),
                disk_total_gb=disk_data.get('total_gb', 1.0),
                disk_available_gb=disk_data.get('available_gb', 1.0),
                gpu_vendor=gpu_vendor,
                gpu_name=gpu_data.get('name'),
                vram_total_gb=gpu_data.get('vram_total_gb', 0.0),
                vram_available_gb=gpu_data.get('vram_available_gb', 0.0),
                gpu_count=gpu_data.get('count', 0),
                os_type=os_type,
                os_version=os_data.get('version'),
                capability_score=device_data.get('capability_score', 0.0)
            )
            
            # Reconstruct node assignment
            node = NodeAssignment(
                device=device,
                roles=[NodeRole(r) for r in node_data['roles']],
                services=node_data.get('services', [])
            )
            
            self.plan.add_node(node)
        
        # Load service registry
        registry_file = deployment_dir / "service_registry.json"
        self.registry = ServiceRegistry(registry_file)
        
        logger.info(f"Service manager initialized for deployment: {deployment_dir.name}")

    
    def _execute_on_node(self, node: NodeAssignment, command: str, timeout: int = 60) -> tuple[int, str, str]:
        """
        Execute command on a node (local or remote).
        
        Args:
            node: Node to execute on
            command: Command to execute
            timeout: Command timeout in seconds
            
        Returns:
            Tuple of (exit_code, stdout, stderr)
        """
        import socket
        
        # Check if node is local
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.connect(("8.8.8.8", 80))
            local_ip = s.getsockname()[0]
            s.close()
        except Exception:
            local_ip = "127.0.0.1"
        
        if node.device.ip_address in ["localhost", "127.0.0.1", local_ip]:
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
                logger.error(f"Local execution failed: {e}")
                return -1, "", str(e)
        else:
            # Remote execution
            client = self.ssh_manager.create_ssh_client(node.device.ip_address)
            if not client:
                return -1, "", "Failed to connect to remote node"
            
            exit_code, stdout, stderr = self.ssh_manager.execute_command(client, command, timeout)
            client.close()
            return exit_code, stdout, stderr
    
    def _get_service_names(self, node: NodeAssignment) -> List[str]:
        """
        Get list of systemd service names for a node.
        
        Args:
            node: Node to get services for
            
        Returns:
            List of service names
        """
        services = []
        for service in node.services:
            if service == "noslop-backend":
                services.append("noslop-backend")
            elif service == "noslop-frontend":
                services.append("noslop-frontend")
            elif service == "ollama":
                services.append("ollama")
            elif service == "comfyui":
                services.append("comfyui")
            elif service == "postgresql":
                services.append("postgresql")
        
        return services
    
    def start_all(self) -> bool:
        """
        Start all services on all nodes.
        
        Returns:
            True if all services started successfully
        """
        logger.info("Starting all services...")
        success = True
        
        for node in self.plan.nodes:
            logger.info(f"\nStarting services on {node.device.hostname} ({node.device.ip_address})...")
            
            services = self._get_service_names(node)
            for service in services:
                logger.info(f"  Starting {service}...")
                code, _, err = self._execute_on_node(
                    node,
                    f"sudo systemctl start {service}"
                )
                
                if code == 0:
                    logger.info(f"  ✓ {service} started")
                else:
                    logger.error(f"  ✗ Failed to start {service}: {err}")
                    success = False
        
        return success
    
    def stop_all(self) -> bool:
        """
        Stop all services on all nodes.
        
        Returns:
            True if all services stopped successfully
        """
        logger.info("Stopping all services...")
        success = True
        
        for node in self.plan.nodes:
            logger.info(f"\nStopping services on {node.device.hostname} ({node.device.ip_address})...")
            
            services = self._get_service_names(node)
            for service in services:
                logger.info(f"  Stopping {service}...")
                code, _, err = self._execute_on_node(
                    node,
                    f"sudo systemctl stop {service}"
                )
                
                if code == 0:
                    logger.info(f"  ✓ {service} stopped")
                else:
                    logger.error(f"  ✗ Failed to stop {service}: {err}")
                    success = False
        
        return success
    
    def restart_all(self) -> bool:
        """
        Restart all services on all nodes.
        
        Returns:
            True if all services restarted successfully
        """
        logger.info("Restarting all services...")
        success = True
        
        for node in self.plan.nodes:
            logger.info(f"\nRestarting services on {node.device.hostname} ({node.device.ip_address})...")
            
            services = self._get_service_names(node)
            for service in services:
                logger.info(f"  Restarting {service}...")
                code, _, err = self._execute_on_node(
                    node,
                    f"sudo systemctl restart {service}"
                )
                
                if code == 0:
                    logger.info(f"  ✓ {service} restarted")
                else:
                    logger.error(f"  ✗ Failed to restart {service}: {err}")
                    success = False
        
        return success
    
    def status_all(self) -> Dict:
        """
        Get status of all services on all nodes.
        
        Returns:
            Dictionary with status information
        """
        logger.info("Checking status of all services...")
        status = {
            "deployment_id": self.deployment_dir.name,
            "nodes": []
        }
        
        for node in self.plan.nodes:
            node_status = {
                "hostname": node.device.hostname,
                "ip_address": node.device.ip_address,
                "roles": [role.value for role in node.roles],
                "services": []
            }
            
            services = self._get_service_names(node)
            for service in services:
                code, stdout, _ = self._execute_on_node(
                    node,
                    f"sudo systemctl is-active {service}"
                )
                
                is_active = stdout.strip() == "active"
                
                # Get more detailed status
                code2, stdout2, _ = self._execute_on_node(
                    node,
                    f"sudo systemctl status {service} --no-pager -l"
                )
                
                service_status = {
                    "name": service,
                    "active": is_active,
                    "status": stdout.strip(),
                    "details": stdout2 if code2 == 0 else None
                }
                
                node_status["services"].append(service_status)
            
            status["nodes"].append(node_status)
        
        return status
    
    def uninstall_all(self, confirm: bool = False) -> bool:
        """
        Uninstall NoSlop from all nodes.
        
        Args:
            confirm: If True, skip confirmation prompt
            
        Returns:
            True if uninstall successful
        """
        if not confirm:
            logger.warning("This will completely remove NoSlop from all nodes!")
            response = input("Are you sure you want to continue? [y/N]: ").strip().lower()
            if response not in ['y', 'yes']:
                logger.info("Uninstall cancelled.")
                return False
        
        logger.info("Uninstalling NoSlop from all nodes...")
        success = True
        
        for node in self.plan.nodes:
            logger.info(f"\nUninstalling from {node.device.hostname} ({node.device.ip_address})...")
            
            # Stop and disable services
            services = self._get_service_names(node)
            for service in services:
                logger.info(f"  Stopping and disabling {service}...")
                self._execute_on_node(node, f"sudo systemctl stop {service}")
                self._execute_on_node(node, f"sudo systemctl disable {service}")
                
                # Remove service file
                if service in ["noslop-backend", "noslop-frontend", "comfyui"]:
                    self._execute_on_node(node, f"sudo rm -f /etc/systemd/system/{service}.service")
            
            # Reload systemd
            self._execute_on_node(node, "sudo systemctl daemon-reload")
            
            # Remove installation directories
            logger.info(f"  Removing installation directories...")
            dirs_to_remove = [
                "/opt/noslop/backend",
                "/opt/noslop/frontend",
                "/opt/ComfyUI"
            ]
            
            for dir_path in dirs_to_remove:
                code, _, err = self._execute_on_node(node, f"sudo rm -rf {dir_path}")
                if code != 0:
                    logger.warning(f"  Failed to remove {dir_path}: {err}")
            
            logger.info(f"  ✓ Uninstall complete for {node.device.hostname}")
        
        logger.info("\n✅ NoSlop uninstalled from all nodes")
        logger.info("Note: PostgreSQL and Ollama were not removed as they may be used by other applications.")
        
        return success


def main():
    """Test service manager."""
    logging.basicConfig(
        level=logging.INFO,
        format='%(asctime)s - %(levelname)s - %(message)s'
    )
    
    # Find latest deployment
    deployments_dir = Path.home() / ".noslop" / "deployments"
    if not deployments_dir.exists():
        print("No deployments found.")
        return
    
    deployments = sorted(deployments_dir.iterdir(), key=lambda x: x.name, reverse=True)
    if not deployments:
        print("No deployments found.")
        return
    
    latest = deployments[0]
    print(f"\nUsing deployment: {latest.name}\n")
    
    ssh_manager = SSHManager()
    manager = ServiceManager(latest, ssh_manager)
    
    # Show status
    status = manager.status_all()
    print(json.dumps(status, indent=2))


if __name__ == "__main__":
    main()
