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
from seed.installers.caddy_installer import CaddyInstaller
from seed.storage_manager import StorageManager, StorageConfig
from seed.manage_certs import CertificateManager

logger = logging.getLogger(__name__)

# Default Database Configuration
DEFAULT_DB_NAME = "noslop"
DEFAULT_DB_USER = "noslop"
DEFAULT_DB_PASS = "noslop"
DEFAULT_DB_PORT = 5432



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
        
        # Initialize storage manager
        self.storage_manager = StorageManager(ssh_manager)
        self.storage_config = None  # Will be set during deployment
        
        logger.info(f"Deployer initialized. Deployment ID: {self.deployment_id}")
        logger.info(f"Deployment directory: {self.deployment_dir}")
    
    
    def _load_env_file(self) -> Dict[str, str]:
        """Load .env file and return dict of key-value pairs"""
        env_vars = {}
        env_path = Path.home() / "NoSlop" / ".env"
        
        if not env_path.exists():
            return env_vars
        
        try:
            with open(env_path, 'r') as f:
                for line in f:
                    line = line.strip()
                    if not line or line.startswith('#'):
                        continue
                    if '=' in line:
                        key, value = line.split('=', 1)
                        env_vars[key.strip()] = value.strip()
        except Exception as e:
            logger.warning(f"Failed to read .env file: {e}")
        
        return env_vars

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
    
    def stop_all_services(self, deployment_plan: Dict, credentials_map: Dict = None):
        """Stop all NoSlop services on all nodes to ensure clean installation."""
        logger.info("Stopping existing NoSlop services...")
        
        if credentials_map is None:
            credentials_map = {}
        
        # Collect all unique nodes
        nodes = deployment_plan.nodes
            
        # Services to stop
        services = ["noslop-backend", "noslop-frontend", "ollama", "comfyui"]
        
        for node in nodes:
            logger.info(f"Stopping services on {node.device.hostname}...")
            # We use semicolon to run all commands even if some fail (e.g. service not found)
            # using '|| true' to suppress errors if service doesn't exist
            cmd = "sudo systemctl stop " + " ".join(services) + " || true"
            
            # Execute directly via SSH manager
            username = node.device.ssh_username or "root"
            
            # Get password from credentials_map
            creds = credentials_map.get(node.device.ip_address)
            password = creds.password if creds else None
            
            client = self.ssh_manager.create_ssh_client(node.device.ip_address, username=username)
            if client:
                 self.ssh_manager.execute_command(client, cmd, sudo_password=password)
            
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
        
        # Default Models Configuration (aligned with what we install)
        # This ensures the .env matches the installed models
        # defaults: "gemma3:4b-it-q4_K_M", "qwen3-vl:4b-instruct-q8_0", "llava:latest"
        default_logic_model = "gemma3:4b-it-q4_K_M"
        
        config["OLLAMA_DEFAULT_MODEL"] = default_logic_model
        config["MODEL_LOGIC"] = default_logic_model
        config["MODEL_VIDEO"] = "qwen3-vl:4b-instruct-q8_0" # Video/Multimodal analysis
        config["MODEL_IMAGE"] = "llava:latest" # Image analysis
        config["MODEL_MATH"] = default_logic_model
        config["MODEL_CODE"] = "qwen2.5-coder:7b"
        config["MODEL_EMBEDDING"] = "nomic-embed-text"
        
        # NOTE: MODEL_TTS and MODEL_TTV are not set here as they do not use Ollama models.
        # The backend uses faster-whisper and SpeechT5 for voice services.
        
        # Service URLs
        # We prefer using the registry if populated, otherwise fallback to plan
        # Since we generate config BEFORE installation, we use plan
        
        if NodeRole.MASTER in node.roles or NodeRole.ALL in node.roles:
            config["NOSLOP_BACKEND_URL"] = f"http://{node.device.ip_address}:8000"
            # Default to local postgresql connection for master
            config["DATABASE_URL"] = f"postgresql://{DEFAULT_DB_USER}:{DEFAULT_DB_PASS}@localhost:{DEFAULT_DB_PORT}/{DEFAULT_DB_NAME}"
            config["OLLAMA_HOST"] = f"http://{node.device.ip_address}:11434"
            # Setting permissive CORS for development; user should restrict this in production
            config["CORS_ORIGINS"] = "*"
        else:
            if plan.master_node:
                config["NOSLOP_BACKEND_URL"] = f"http://{plan.master_node.device.ip_address}:8000"
                # Remote workers connect to master's postgresql (future TODO: configure properly)
                # Currently workers don't need direct DB access, only backend does.
                config["OLLAMA_HOST"] = f"http://{plan.master_node.device.ip_address}:11434"
        
        # NOTE: NEXT_PUBLIC_NOSLOP_BACKEND_URL will be set later based on external URL configuration
        # to avoid mixed content errors when using HTTPS (Cloudflare Tunnel, etc.)
        
        # ComfyUI URLs
        compute_nodes = plan.get_node_by_role(NodeRole.COMPUTE)
        if not compute_nodes:
            compute_nodes = plan.get_node_by_role(NodeRole.ALL)
        
        if compute_nodes:
            # Backend expects a single string for now, or we need to handle list.
            # Config says comfyui_host: str. So we pick the first one.
            # If we want multiple, we need to update backend config.
            # For now, let's just pick the first one.
            comfyui_url = f"http://{compute_nodes[0].device.ip_address}:8188"
            config["COMFYUI_HOST"] = comfyui_url
            config["COMFYUI_ENABLED"] = "true"
        
        # Storage paths
        if NodeRole.STORAGE in node.roles or NodeRole.ALL in node.roles:
            config["MEDIA_STORAGE_PATH"] = "/var/noslop/media"
            # config["NOSLOP_BLOCKCHAIN_PATH"] = "/var/noslop/blockchain" # Not used in backend config yet

        # Voice Model Storage (Shared or Local)
        # Using shared storage for heavy models is preferred in multi-node setups
        models_base = None
        
        if self.storage_config:
            # We have shared storage configured
            if NodeRole.MASTER in node.roles:
                # Master node: Use the actual physical path (base_path)
                # Ensure we append /models to the base path
                base_path = self.storage_config.base_path
                models_base = f"{base_path}/models"
            else:
                # Worker node: Use the configured NFS mount point (same path structure)
                models_base = f"{self.storage_config.base_path}/models"
        else:
            # Single device or no shared storage - use local /var path
            models_base = "/var/noslop/models"
            
        config["HF_HOME"] = f"{models_base}/transformers"
        config["WHISPER_CACHE_DIR"] = f"{models_base}/whisper"
        
        env_config = self._load_env_file()
        
        # Check for external URL override from .env file
        import os
        frontend_external_url = env_config.get("NOSLOP_FRONTEND_EXTERNAL_URL",
                                               os.environ.get("NOSLOP_FRONTEND_EXTERNAL_URL", ""))
        backend_external_url = env_config.get("NOSLOP_BACKEND_EXTERNAL_URL",
                                              os.environ.get("NOSLOP_BACKEND_EXTERNAL_URL", ""))

        if frontend_external_url:
             config["NOSLOP_FRONTEND_EXTERNAL_URL"] = frontend_external_url
        
        # Determine if we're in Cloudflare Tunnel / HTTPS mode
        is_https_mode = (
            frontend_external_url.startswith("https://") or 
            backend_external_url.startswith("https://")
        )
        
        # Determine the backend URL for client-side access
        # For HTTPS mode: use relative paths (Caddy routing)
        # For HTTP mode: use explicit backend URL (important for multi-node deployments)
        if backend_external_url:
            config["NOSLOP_BACKEND_EXTERNAL_URL"] = backend_external_url
            
            # HTTPS Clients (External): Use relative paths (handled by frontend logic)
            # HTTP Clients (Internal): Use internal backend URL (NOSLOP_BACKEND_URL)
            # We set NEXT_PUBLIC_API_URL to the internal URL so local worker nodes can connect.
            if "NOSLOP_BACKEND_URL" in config:
                config["NEXT_PUBLIC_API_URL"] = config["NOSLOP_BACKEND_URL"]
            else:
                config["NEXT_PUBLIC_API_URL"] = backend_external_url

        elif frontend_external_url and frontend_external_url.startswith("https://"):
            # Frontend is HTTPS but backend URL not explicitly set
            # Assume Caddy is handling routing
            
            # Identify the backend IP (Master Node IP)
            backend_ip = plan.master_node.device.ip_address if plan.master_node else node.device.ip_address
            # SSR URL: Use internal HTTP for server-side calls
            internal_backend_url = f"http://{backend_ip}:8000"
            # SSR URL: Use internal HTTP for server-side calls
            internal_backend_url = f"http://{backend_ip}:8000"
            
            config["NOSLOP_BACKEND_EXTERNAL_URL"] = internal_backend_url
            
            # CORS Configuration: Allow localhost, 127.0.0.1, and the node's own IP/Hostname
            # If we are master, we also allow all known node IPs (implied, but for now we trust network)
            # We construct a list of trusted origins
            trusted_origins = [
                "http://localhost:3000", 
                "http://127.0.0.1:3000",
                f"http://{node.device.ip_address}:3000",
                f"http://{node.device.hostname}:3000"
            ]
            
            # If we have a master node, add its frontend too
            if plan.master_node:
                trusted_origins.append(f"http://{plan.master_node.device.ip_address}:3000")
                trusted_origins.append(f"http://{plan.master_node.device.hostname}:3000")

            # Add external URL if present
            if frontend_external_url:
                 trusted_origins.append(frontend_external_url)

            config["CORS_ORIGINS"] = ",".join(list(set(trusted_origins)))
            # HTTP Clients (Internal): Use internal backend URL
            if "NOSLOP_BACKEND_URL" in config:
                config["NEXT_PUBLIC_API_URL"] = config["NOSLOP_BACKEND_URL"]
            else:
                 config["NEXT_PUBLIC_API_URL"] = internal_backend_url
        else:
            # No explicit external URLs set - standard HTTP deployment
            # For multi-node: frontend needs to know where backend is
            # For single-node: frontend and backend are on same machine
            
            if "NOSLOP_BACKEND_URL" in config:
                # Use the backend URL we set earlier (lines 207 or 215)
                # This handles both single-node and multi-node cases
                config["NEXT_PUBLIC_API_URL"] = config["NOSLOP_BACKEND_URL"]

        # Centralized Logging
        # If shared storage is available, we write logs to it so they are aggregated on Master
        if self.storage_config:
            if NodeRole.MASTER in node.roles:
                 # Master: Write to physical storage path
                 config["LOG_DIR"] = f"{self.storage_config.base_path}/logs/{node.device.hostname}"
            else:
                 # Worker: Write to mounted NFS path (same base_path as master)
                 config["LOG_DIR"] = f"{self.storage_config.base_path}/logs/{node.device.hostname}"
        else:
             # Local logging fallback (installers usually default to this, but explicit is better)
             # However, we can't easily expand ~ for remote user here, so we let installer handle default
             # or we construct it if we know username. Installer knows username.
             # We'll just skip setting LOG_DIR here if not shared, and update installers to use config["LOG_DIR"] if present.
             pass
        
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
        
        # Define the models we want to install
        # This MUST align with what we put in generate_node_config
        target_models = [
            "gemma3:4b-it-q4_K_M", 
            "qwen3-vl:4b-instruct-q8_0", 
            "llava:latest",
            "qwen2.5-coder:7b",
            "nomic-embed-text",
            "llama3.2" # Safe fallback/standard model
        ]

        if credentials_map:
             logger.info(f"DEBUG: install_services called with credentials for IPs: {list(credentials_map.keys())}")
        else:
             logger.info("DEBUG: install_services called with EMPTY credentials_map")

        def get_credentials(device):
            """Helper to get username and password for a device."""
            # DEBUG LOGGING
            logger.info(f"DEBUG: Looking up credentials for {device.ip_address}")
            creds = credentials_map.get(device.ip_address)
            
            # Check for localhost/127.0.0.1 mismatch if map has local IP
            if not creds and device.ip_address in ["127.0.0.1", "localhost"]:
                 for ip, c in credentials_map.items():
                     if ip.startswith("192.168.") or ip.startswith("10."):
                         if len(credentials_map) == 1:
                             creds = c
                             break
            
            if creds:
                logger.info(f"DEBUG: Found credentials for {device.ip_address}: User={creds.username}, Password={'****' if creds.password else 'NONE'}")
                if not creds.password and device.os_type.value != "windows":
                     logger.warning(f"⚠️  No password found for {device.ip_address} (User: {creds.username}) in install_services.")
                return creds.username, creds.password
            
            # Fallback for localhost if not found in map at all (maybe running as user)
            if device.ip_address in ["127.0.0.1", "localhost"]:
                 import os
                 return os.environ.get("USER", "root"), None
                 
            logger.warning(f"DEBUG: No credentials found for {device.ip_address}. Map keys: {list(credentials_map.keys())}. Using root/None.")
            return "root", None

        # Phase 1: Base Dependencies
        logger.info("\n[Phase 1] Installing Base Dependencies...")
        for node in plan.nodes:
            if NodeRole.COMPUTE in node.roles or NodeRole.ALL in node.roles:
                username, password = get_credentials(node.device)
                installer = FFmpegInstaller(node.device, self.ssh_manager, username=username, password=password)
                if not installer.run():
                    logger.error(f"Failed to install FFmpeg on {node.device.hostname}")
                    return False

        # Phase 2: Core Infrastructure (PostgreSQL)
        logger.info("\n[Phase 2] Installing Core Infrastructure...")
        if plan.master_node:
            username, password = get_credentials(plan.master_node.device)
            installer = PostgreSQLInstaller(plan.master_node.device, self.ssh_manager, username=username, password=password)
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
                logger.info(f"Starting installation of ollama on {node.device.hostname}")
                
                # Determine correct models directory path
                # Master uses its actual storage path, workers use NFS mount point
                logs_dir = None
                if node == plan.master_node:
                    # Master node - use actual storage path
                    models_dir = self.storage_config.ollama_models_dir if self.storage_config else None
                    if self.storage_config:
                         logs_dir = f"{self.storage_config.base_path}/logs/{node.device.hostname}"
                else:
                    # Worker nodes - use NFS mount points (same base path structure)
                    if self.storage_config:
                        # Use configured ollama path
                        models_dir = self.storage_config.ollama_models_dir
                        logs_dir = f"{self.storage_config.base_path}/logs/{node.device.hostname}"
                    else:
                        models_dir = None
                
                username, password = get_credentials(node.device)
                installer = OllamaInstaller(
                    node.device,
                    self.ssh_manager,
                    username=username,
                    password=password,
                    models_dir=models_dir,
                    logs_dir=logs_dir,
                    models=target_models
                )
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
                logger.info(f"Starting installation of comfyui on {node.device.hostname}")
                
                # Determine correct storage paths
                # Master uses its actual storage path, workers use NFS mount point
                logs_dir = None
                if node == plan.master_node:
                    # Master node - use actual storage paths
                    models_dir = self.storage_config.comfyui_models_dir if self.storage_config else None
                    custom_nodes_dir = self.storage_config.comfyui_custom_nodes_dir if self.storage_config else None
                    workflows_dir = self.storage_config.workflows_dir if hasattr(self.storage_config, 'workflows_dir') and self.storage_config else None
                    if self.storage_config:
                         logs_dir = f"{self.storage_config.base_path}/logs/{node.device.hostname}"
                else:
                    # Worker nodes - use NFS mount points (same base path structure)
                    if self.storage_config:
                        models_dir = self.storage_config.comfyui_models_dir
                        custom_nodes_dir = self.storage_config.comfyui_custom_nodes_dir
                        workflows_dir = self.storage_config.workflows_dir if hasattr(self.storage_config, 'workflows_dir') else None
                        logs_dir = f"{self.storage_config.base_path}/logs/{node.device.hostname}"
                    else:
                        models_dir = None
                        custom_nodes_dir = None
                        workflows_dir = None
                
                username, password = get_credentials(node.device)
                installer = ComfyUIInstaller(
                    node.device,
                    self.ssh_manager,
                    username=username,
                    password=password,
                    models_dir=models_dir,
                    custom_nodes_dir=custom_nodes_dir,
                    workflows_dir=workflows_dir,
                    logs_dir=logs_dir
                )
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

        # Phase 4.5: SSL/TLS Setup (if HTTPS is required)
        logger.info("\n[Phase 4.5] Checking SSL/TLS Requirements...")
        
        # Load configuration from .env file
        env_config = self._load_env_file()
        
        # Check for HTTPS URLs (prioritize .env file, fallback to environment)
        import os
        frontend_external_url = env_config.get("NOSLOP_FRONTEND_EXTERNAL_URL", 
                                               os.environ.get("NOSLOP_FRONTEND_EXTERNAL_URL", ""))
        backend_external_url = env_config.get("NOSLOP_BACKEND_EXTERNAL_URL",
                                              os.environ.get("NOSLOP_BACKEND_EXTERNAL_URL", ""))
        
        needs_https = (
            frontend_external_url.startswith("https://") or 
            backend_external_url.startswith("https://")
        )
        
        if needs_https and plan.master_node:
            logger.info("🔐 HTTPS detected in configuration - Setting up SSL/TLS...")
            logger.info(f"   Frontend URL: {frontend_external_url}")
            logger.info(f"   Backend URL: {backend_external_url}")
            
            master_node = plan.master_node
            master_ip = master_node.device.ip_address
            master_hostname = master_node.device.hostname
            username, password = get_credentials(master_node.device)
            
            # Determine certificate hostname and IPs
            # Extract domain from URLs if present
            cert_hostname = master_ip  # Default to IP
            cert_ips = [master_ip]
            
            if frontend_external_url and "://" in frontend_external_url:
                # Extract hostname from URL
                from urllib.parse import urlparse
                parsed = urlparse(frontend_external_url)
                if parsed.hostname and not parsed.hostname.startswith("192.168") and not parsed.hostname.startswith("10."):
                    # It's a domain name, use it as cert hostname
                    cert_hostname = parsed.hostname
            
            logger.info(f"   Certificate hostname: {cert_hostname}")
            logger.info(f"   Certificate IPs: {', '.join(cert_ips)}")
            
            # Step 1: Generate SSL certificates
            logger.info("   📜 Generating SSL certificates...")
            cert_manager = CertificateManager(cert_dir="/home/tom/NoSlop/certs")
            
            try:
                cert_path, key_path = cert_manager.generate_self_signed_cert(
                    hostname=cert_hostname,
                    ip_addresses=cert_ips,
                    output_name="server",
                    validity_days=365
                )
                logger.info(f"   ✓ Certificates generated: {cert_path}")
            except Exception as e:
                logger.error(f"   ✗ Failed to generate certificates: {e}")
                # Continue without SSL - services will still work on HTTP
                needs_https = False
            
            if needs_https:
                # Step 2: Copy certificates to master node
                logger.info("   📤 Copying certificates to master node...")
                try:
                    # Check if master node is local (same machine)
                    import socket
                    local_hostname = socket.gethostname()
                    local_ips = []
                    try:
                        local_ips.append(socket.gethostbyname(local_hostname))
                        # Also check all local IPs
                        import netifaces
                        for interface in netifaces.interfaces():
                            addrs = netifaces.ifaddresses(interface)
                            if netifaces.AF_INET in addrs:
                                for addr in addrs[netifaces.AF_INET]:
                                    local_ips.append(addr['addr'])
                    except:
                        pass
                    
                    is_local = (
                        master_hostname == local_hostname or
                        master_hostname == "localhost" or
                        master_ip == "127.0.0.1" or
                        master_ip in local_ips
                    )
                    
                    remote_cert_path = "/etc/noslop/certs/server.crt"
                    remote_key_path = "/etc/noslop/certs/server.key"
                    
                    if is_local:
                        # Local deployment - use local file operations
                        logger.info("   📋 Local deployment detected - copying certificates locally...")
                        import subprocess
                        import shutil
                        
                        # Create directory with sudo
                        subprocess.run(
                            ["sudo", "mkdir", "-p", "/etc/noslop/certs"],
                            check=True,
                            timeout=30
                        )
                        
                        # Copy files with sudo
                        subprocess.run(
                            ["sudo", "cp", cert_path, remote_cert_path],
                            check=True,
                            timeout=30
                        )
                        subprocess.run(
                            ["sudo", "cp", key_path, remote_key_path],
                            check=True,
                            timeout=30
                        )
                        
                        # Set permissions
                        subprocess.run(
                            ["sudo", "chmod", "644", remote_cert_path],
                            check=True,
                            timeout=30
                        )
                        subprocess.run(
                            ["sudo", "chmod", "600", remote_key_path],
                            check=True,
                            timeout=30
                        )
                        
                        logger.info("   ✓ Certificates copied locally")
                    else:
                        # Remote deployment - use SSH
                        logger.info("   📤 Remote deployment detected - copying via SSH...")
                        
                        # Create SSH client
                        client = self.ssh_manager.create_ssh_client(
                            master_ip,
                            username=username,
                            port=22
                        )
                        
                        if not client:
                            raise Exception(f"Failed to create SSH connection to {master_ip}")
                        
                        # Create remote directory
                        exit_code, stdout, stderr = self.ssh_manager.execute_command(
                            client,
                            "sudo mkdir -p /etc/noslop/certs && sudo chown -R $USER:$USER /etc/noslop",
                            timeout=30,
                            sudo_password=password
                        )
                        
                        if exit_code != 0:
                            raise Exception(f"Failed to create remote directory: {stderr}")
                        
                        # Transfer files
                        if not self.ssh_manager.transfer_file(client, cert_path, remote_cert_path):
                            raise Exception("Failed to transfer certificate file")
                        
                        if not self.ssh_manager.transfer_file(client, key_path, remote_key_path):
                            raise Exception("Failed to transfer key file")
                        
                        # Set permissions
                        exit_code, stdout, stderr = self.ssh_manager.execute_command(
                            client,
                            f"chmod 644 {remote_cert_path} && chmod 600 {remote_key_path}",
                            timeout=30
                        )
                        
                        if exit_code != 0:
                            raise Exception(f"Failed to set permissions: {stderr}")
                        
                        client.close()
                        logger.info("   ✓ Certificates copied to remote node")
                    
                    # Use remote paths for Caddy
                    cert_path = remote_cert_path
                    key_path = remote_key_path
                    
                except Exception as e:
                    logger.error(f"   ✗ Failed to copy certificates: {e}")
                    import traceback
                    logger.debug(traceback.format_exc())
                    needs_https = False
            
            if needs_https:
                # Step 3: Install and configure Caddy
                logger.info("   🔧 Installing Caddy reverse proxy...")
                try:
                    caddy_installer = CaddyInstaller(
                        ssh_manager=self.ssh_manager,
                        node_hostname=master_hostname,
                        node_ip=master_ip
                    )
                    
                    # Find frontend node IP
                    frontend_node_ip = "127.0.0.1"
                    for node in plan.nodes:
                        if "noslop-frontend" in node.services:
                            if node.device.hostname == master_hostname:
                                frontend_node_ip = "127.0.0.1"
                            else:
                                frontend_node_ip = node.device.ip_address
                            break

                    if caddy_installer.install(
                        cert_path=cert_path,
                        key_path=key_path,
                        backend_port=8000,
                        frontend_port=3000,
                        enable_frontend_proxy=True,
                        frontend_node_ip=frontend_node_ip,
                        external_hostname=cert_hostname
                    ):
                        logger.info("   ✓ Caddy installed and configured")
                        logger.info(f"   🔒 HTTPS Backend: https://{master_ip}:8443")
                        logger.info(f"   🔒 HTTPS Frontend: https://{master_ip}:3443")
                    else:
                        logger.warning("   ⚠ Caddy installation failed - continuing without SSL")
                        needs_https = False
                        
                except Exception as e:
                    logger.error(f"   ✗ Caddy installation error: {e}")
                    import traceback
                    logger.debug(traceback.format_exc())
                    needs_https = False
        else:
            logger.info("   ℹ HTTP configuration detected - Skipping SSL setup")

        # Phase 4: NoSlop Services
        logger.info("\n[Phase 4] Installing NoSlop Services...")

        # Backend (Master)
        for node in plan.nodes:
            if "noslop-backend" in node.services:
                # Generate config for backend
                config = self.generate_node_config(node, plan)
                username, password = get_credentials(node.device)
                try:
                    installer = BackendInstaller(node.device, self.ssh_manager, config, username=username, password=password)
                    if not installer.run():
                        logger.error(f"Failed to install Backend on {node.device.hostname}")
                        return False

                    # Register Backend
                    self.registry.register_service(ServiceInstance(
                        instance_id=f"backend_{node.device.ip_address}",
                        service_type=ServiceType.NOSLOP_BACKEND,
                        host=node.device.ip_address,
                        port=8000,
                        is_newly_deployed=True,
                        health_status="healthy"
                    ))
                except ConnectionError as e:
                    logger.error(f"⚠️  Skipping Backend on {node.device.hostname}: {e}")
                except Exception as e:
                    logger.error(f"❌ Unexpected error installing Backend on {node.device.hostname}: {e}")
                    return False

        # Frontend (Client/All)
        for node in plan.nodes:
            if "noslop-frontend" in node.services:
                try:
                    config = self.generate_node_config(node, plan)
                    username, password = get_credentials(node.device)
                    installer = FrontendInstaller(node.device, self.ssh_manager, config, username=username, password=password)
                    if not installer.run():
                        logger.error(f"Failed to install Frontend on {node.device.hostname}")
                        return False

                    # Register Frontend
                    self.registry.register_service(ServiceInstance(
                        instance_id=f"frontend_{node.device.ip_address}",
                        service_type=ServiceType.NOSLOP_FRONTEND,
                        host=node.device.ip_address,
                        port=3000,
                        is_newly_deployed=True,
                        health_status="healthy"
                    ))
                except ConnectionError as e:
                    logger.error(f"⚠️  Skipping Frontend on {node.device.hostname}: {e}")
                except Exception as e:
                    logger.error(f"❌ Unexpected error installing Frontend on {node.device.hostname}: {e}")
                    return False

        return True

    def deploy(self, plan: DeploymentPlan, credentials_map: Dict = None, storage_config: Optional[StorageConfig] = None) -> bool:
        """Execute deployment plan."""
        logger.info("="*70)
        logger.info(f"Starting NoSlop Deployment (ID: {self.deployment_id})")
        logger.info("="*70)
        
        # Phase 0: Discovery - Skipped as it's redundant with seed_cli.py discovery
        # logger.info("\n🔍 [Phase 0] Network Discovery...")
        # discovered_services = self.discovery.scan_network()
        # for service in discovered_services:
        #     self.registry.register_discovered_service(service)
        
        # Validate plan
        is_valid, error = self.validate_plan(plan)
        if not is_valid:
            logger.error(f"Cannot deploy: {error}")
            return False
        
        # Save deployment plan
        # First, update nodes with credentials used
        if credentials_map:
            for node in plan.nodes:
                creds = credentials_map.get(node.device.ip_address)
                if creds:
                    node.device.ssh_username = creds.username
                    
        self.save_deployment_plan(plan)
        
        # Configure Shared Storage (if multi-device)
        if len(plan.nodes) > 1:
            logger.info("\n🗄️  Configuring Shared Storage...")
            
            if storage_config:
                 self.storage_config = storage_config
                 # CRITICAL: Update storage_manager's config to match
                 self.storage_manager.config = storage_config
                 logger.info(f"Using upfront storage configuration: {storage_config.base_path}")
            else:
                # Fallback to prompting if not provided (legacy behavior)
                print("\n" + "="*70)
                print("Shared Storage Configuration")
                print("="*70)
                # Prompt for storage configuration
                self.storage_config = self.storage_manager.prompt_storage_config()
            
            # Helper to get credentials
            def get_credentials(device):
                """Helper to get username and password for a device."""
                creds = credentials_map.get(device.ip_address)
                
                # Check for localhost/127.0.0.1 mismatch if map has local IP
                if not creds and device.ip_address in ["127.0.0.1", "localhost"]:
                     # Try finding credentials for any local IP
                     # This is a bit hacky but helps single-device mode consistency
                     for ip, c in credentials_map.items():
                         if ip.startswith("192.168.") or ip.startswith("10."):
                             # Assume this might be the local credential we want 
                             # (if we only have one set of credentials and we are deploying locally)
                             if len(credentials_map) == 1:
                                 creds = c
                                 break

                if creds:
                    if not creds.password and device.os_type.value != "windows": # Windows might use keys only?
                        logger.warning(f"⚠️  No password found for {device.ip_address} (User: {creds.username}). Sudo commands may fail.")
                    return creds.username, creds.password
                
                logger.warning(f"⚠️  No credentials found for {device.ip_address}. using root/None.")
                return "root", None
                
            # Stop existing services before messing with storage or installing
            self.stop_all_services(plan, credentials_map)
            
            # Setup NFS server on master node
            if plan.master_node:
                print(f"\n📡 Setting up NFS server on {plan.master_node.device.hostname}...")
                username, password = get_credentials(plan.master_node.device)
                if not self.storage_manager.setup_nfs_server(plan.master_node.device, username, password):
                    logger.error("Failed to setup NFS server")
                    print("\n⚠️  Warning: Shared storage setup failed.")
                    print("   Services will use local storage instead.")
                    self.storage_config = None  # Disable shared storage
                else:
                    # Mount NFS shares on worker nodes
                    master_ip = plan.master_node.device.ip_address
                    for node in plan.nodes:
                        if node != plan.master_node:
                            print(f"📥 Mounting shared storage on {node.device.hostname}...")
                            username, password = get_credentials(node.device)
                            if not self.storage_manager.mount_nfs_shares(node.device, master_ip, username, password):
                                logger.warning(f"Failed to mount NFS on {node.device.hostname}")
                            else:
                                # Verify shared storage
                                if self.storage_manager.verify_shared_storage(node.device, username, password):
                                    print(f"   ✓ Shared storage verified on {node.device.hostname}")
                    
                    # Save storage configuration to deployment
                    storage_env = self.storage_manager.generate_storage_env()
                    storage_file = self.deployment_dir / "shared_storage.env"
                    with open(storage_file, 'w') as f:
                        f.write(storage_env)
                    logger.info(f"Storage configuration saved to {storage_file}")
        else:
            logger.info("\n🗄️  Single device deployment - using local storage")
        
        # Generate configurations
        logger.info("\n📝 Generating node configurations...")
        for node in plan.nodes:
            config = self.generate_node_config(node, plan)
            self.write_env_file(node, config)
        
        # Install Services
        if credentials_map is None:
            credentials_map = {}
        if not self.install_services(plan, credentials_map):
            logger.error("\n❌ Service installation failed!")
            return False
        
        logger.info("\n✅ Deployment Complete!")
        logger.info(f"Deployment artifacts: {self.deployment_dir}")
        
        # Print Registry Summary
        stats = self.registry.get_service_stats()
        logger.info("\nService Registry Summary:")
        logger.info(json.dumps(stats, indent=2))
        
        # Display access points
        logger.info("\n" + "="*70)
        logger.info("🌐 NoSlop Access Points")
        logger.info("="*70)
        
        # Backend URL
        backend_instances = self.registry.get_instances_by_type(ServiceType.NOSLOP_BACKEND)
        if backend_instances:
            backend = backend_instances[0]
            # Check if SSL was enabled (quick check via env config or default port assumptions)
            # Better to use the registry or just check if 8443 port is active?
            # Ideally registry would track https scheme.
            # For now, we reuse the logic: if we deployed caddy, we have https.
            # But simpler: check if we set up SSL logic.
            # We can check if `needs_https` was true, but that variable is local to deploy().
            # Let's check environment variable in config or registry.
            # Or just check if we have https urls in registry? Registry currently stores http scheme mostly.
            
            # Simple heuristic: Check if env config has external URL with https
            env_config = self._load_env_file()
            backend_url = f"http://{backend.host}:{backend.port}"
            
            if env_config.get("NOSLOP_BACKEND_EXTERNAL_URL", "").startswith("https://"):
                 backend_url = env_config.get("NOSLOP_BACKEND_EXTERNAL_URL")
            elif env_config.get("NOSLOP_FRONTEND_EXTERNAL_URL", "").startswith("https://"):
                 # Assume backend also https
                 backend_url = f"https://{backend.host}:8443"

            logger.info(f"\n📡 Backend API: {backend_url}")
        
        # Frontend URLs
        frontend_instances = self.registry.get_instances_by_type(ServiceType.NOSLOP_FRONTEND)
        if frontend_instances:
            logger.info(f"\n🖥️  Frontend (Web UI):")
            for i, frontend in enumerate(frontend_instances, 1):
                frontend_url = f"http://{frontend.host}:{frontend.port}"
                
                if env_config.get("NOSLOP_FRONTEND_EXTERNAL_URL", "").startswith("https://"):
                     # If override matches IP, use it. If it's a domain, use it.
                     frontend_url = env_config.get("NOSLOP_FRONTEND_EXTERNAL_URL")
                     
                     logger.info(f"   {i}. {frontend_url}")
                     
                     # Check if it is a domain name. If so, also show the IP based access.
                     if "://" in frontend_url:
                        try:
                           from urllib.parse import urlparse
                           u = urlparse(frontend_url)
                           # Check if hostname is an IP
                           import ipaddress
                           try:
                               ipaddress.ip_address(u.hostname)
                           except ValueError:
                               # It's a domain name. Show IP fallback.
                               # Assuming Caddy is on Master Node / Local Node.
                               # We need Master IP. 
                               # Since we are inside deploy(), we might not have master_ip variable easily unless we get it from plan.
                               if plan.master_node:
                                    master_ip = plan.master_node.device.ip_address
                                    logger.info(f"      (IP Access: https://{master_ip}:3443)")
                        except:
                            pass
                else:
                    logger.info(f"   {i}. {frontend_url}")
        
        logger.info("\n" + "="*70)
        
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
