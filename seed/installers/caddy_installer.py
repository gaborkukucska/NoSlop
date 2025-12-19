# START OF FILE seed/installers/caddy_installer.py
"""
Caddy Reverse Proxy Installer for NoSlop

Installs and configures Caddy as a reverse proxy for HTTPS support.
Handles backend API, frontend, and WebSocket proxying with automatic SSL/TLS.
"""

import logging
from pathlib import Path
from typing import Optional, Dict, List
from seed.installers.base_installer import BaseInstaller

logger = logging.getLogger(__name__)


class CaddyInstaller(BaseInstaller):
    """Installer for Caddy reverse proxy"""
    
    SERVICE_NAME = "caddy"
    CADDY_CONFIG_DIR = "/etc/caddy"
    CADDY_LOG_DIR = "/var/log/caddy"
    
    def __init__(self, ssh_manager, node_hostname: str, node_ip: str):
        """
        Initialize Caddy installer
        
        Args:
            ssh_manager: SSH manager instance
            node_hostname: Hostname of the node
            node_ip: IP address of the node
        """
        # Create a DeviceCapabilities object for BaseInstaller
        from seed.models import DeviceCapabilities, Architecture, GPUVendor, OSType
        device = DeviceCapabilities(
            hostname=node_hostname,
            ip_address=node_ip,
            os_type=OSType.LINUX,
            cpu_cores=1,  # Dummy values
            cpu_speed_ghz=2.0,
            cpu_architecture=Architecture.X86_64,
            ram_total_gb=1,
            ram_available_gb=1,
            gpu_vendor=GPUVendor.NONE,
            disk_total_gb=1
        )
        
        super().__init__(
            device=device,
            ssh_manager=ssh_manager,
            service_name=self.SERVICE_NAME
        )
        
        self.node_ip = node_ip
        self.node_hostname = node_hostname
        self.backend_port = 8000
        self.frontend_port = 3000
        self.comfyui_port = 8188
        self.https_backend_port = 8443
        self.https_frontend_port = 3443
        
        # Configuration to be set by install method
        self.cert_path = None
        self.key_path = None
        self.enable_frontend_proxy = True
    
    def check_installed(self) -> bool:
        """Check if Caddy is already installed"""
        try:
            exit_code, stdout, stderr = self.execute_remote("which caddy", timeout=10)
            if exit_code == 0 and stdout.strip():
                logger.info(f"✅ Caddy is already installed on {self.node_hostname}")
                return True
            return False
        except Exception:
            return False
    
    def install(
        self,
        cert_path: str = None,
        key_path: str = None,
        backend_port: int = 8000,
        frontend_port: int = 3000,
        comfyui_port: int = 8488,
        comfyui_internal_port: int = 8188,
        enable_frontend_proxy: bool = True,
        frontend_node_ip: str = "127.0.0.1",
        external_hostname: str = None
    ) -> bool:
        """
        Install and configure Caddy reverse proxy
        
        Args:
            cert_path: Path to SSL certificate file
            key_path: Path to SSL private key file
            backend_port: Backend API port (default: 8000)
            frontend_port: Frontend port (default: 3000)
            comfyui_port: ComfyUI external port (default: 8488)
            comfyui_internal_port: ComfyUI internal port (default: 8188)
            enable_frontend_proxy: Whether to proxy frontend (default: True)
            frontend_node_ip: IP address of the frontend node (default: 127.0.0.1)
            external_hostname: External domain name (e.g. app.noslop.me) to accept requests for
            
        Returns:
            True if installation successful
        """
        logger.info(f"Installing Caddy on {self.node_hostname}...")
        
        # Store configuration for later use
        if cert_path:
            self.cert_path = cert_path
        if key_path:
            self.key_path = key_path
        self.backend_port = backend_port
        self.frontend_port = frontend_port
        self.comfyui_port = comfyui_port
        self.comfyui_internal_port = comfyui_internal_port
        self.enable_frontend_proxy = enable_frontend_proxy
        self.frontend_node_ip = frontend_node_ip
        self.external_hostname = external_hostname
        
        try:
            # Check if already installed
            if self.check_installed():
                logger.info("Caddy is already installed, proceeding with configuration...")
                self._create_directories()
                self._fix_cert_permissions()
                return self.configure() and self.start() and self.verify()
           
           # Install Caddy package
            if not self._install_caddy():
                return False
           
            # Create directories
            self._create_directories()
            self._fix_cert_permissions()
           
            # Full run through: configure, start, verify
            return self.configure() and self.start() and self.verify()
            
        except Exception as e:
            logger.error(f"Failed to install Caddy: {e}")
            self.rollback()
            return False
    
    def configure(self) -> bool:
        """Configure Caddy with Caddyfile"""
        if not self.cert_path or not self.key_path:
            logger.error("Certificate paths not set - call install() with cert paths first")
            return False
        
        try:
            # Generate Caddyfile configuration
            caddyfile_content = self._generate_caddyfile(
                self.cert_path, self.key_path, self.enable_frontend_proxy
            )
            
            # Write Caddyfile
            if not self._write_caddyfile(caddyfile_content):
                return False
            
            logger.info("✅ Caddy configured successfully")
            return True
            
        except Exception as e:
            logger.error(f"Failed to configure Caddy: {e}")
            return False
    
    def start(self) -> bool:
        """Start Caddy service"""
        try:
            # Setup systemd service (if not exists)
            self._setup_systemd_service()
            
            # Reload Caddy configuration
            if not self._reload_caddy_config():
                return False
            
            logger.info("✅ Caddy started successfully")
            return True
            
        except Exception as e:
            logger.error(f"Failed to start Caddy: {e}")
            return False
    
    def verify(self) -> bool:
        """Verify Caddy installation"""
        if not self._verify_installation():
            return False
        
        logger.info(f"✅ Caddy installed and configured successfully on {self.node_hostname}")
        if self.cert_path and self.key_path:
            logger.info(f"   Backend HTTPS: https://{self.node_ip}:{self.https_backend_port}")
            if self.enable_frontend_proxy:
                logger.info(f"   Frontend HTTPS: https://{self.node_ip}:{self.https_frontend_port}")
            logger.info(f"   ComfyUI HTTPS: https://{self.node_ip}:{self.comfyui_port}")
        
        return True
    
    def _install_caddy(self) -> bool:
        """Install Caddy package"""
        logger.info("Installing Caddy package...")
        
        try:
            # Detect OS
            os_info = self._detect_os()
            
            if os_info["os_type"] in ["debian", "ubuntu"]:
                # Debian/Ubuntu installation via official repository
                commands = [
                    # Install dependencies
                    "sudo apt-get update",
                    "sudo apt-get install -y debian-keyring debian-archive-keyring apt-transport-https curl",
                    
                    # Add Caddy repository
                    "curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | sudo gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg",
                    "curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' | sudo tee /etc/apt/sources.list.d/caddy-stable.list",
                    
                    # Install Caddy
                    "sudo apt-get update",
                    "sudo apt-get install -y caddy"
                ]
                
                for cmd in commands:
                    logger.debug(f"Running: {cmd}")
                    exit_code, stdout, stderr = self.execute_remote(cmd, timeout=120)
                    if exit_code != 0:
                        logger.error(f"Command failed: {cmd}")
                        logger.error(f"Error: {stderr}")
                        return False
                
                logger.info("✅ Caddy installed successfully")
                return True
                
            else:
                logger.error(f"Unsupported OS: {os_info['os_type']}")
                return False
                
        except Exception as e:
            logger.error(f"Failed to install Caddy package: {e}")
            return False
    
    def _detect_os(self) -> Dict[str, str]:
        """Detect operating system"""
        exit_code, os_release, stderr = self.execute_remote("cat /etc/os-release", timeout=10)
        
        os_type = "unknown"
        
        if "Debian" in os_release or "debian" in os_release:
            os_type = "debian"
        elif "Ubuntu" in os_release or "ubuntu" in os_release:
            os_type = "ubuntu"
        
        return {"os_type": os_type, "os_release": os_release}
    
    def _create_directories(self):
        """Create required directories"""
        logger.debug("Creating Caddy directories...")
        
        dirs = [
            self.CADDY_CONFIG_DIR,
            self.CADDY_LOG_DIR,
        ]
        
        for dir_path in dirs:
            self.execute_remote(f"sudo mkdir -p {dir_path}", timeout=30)
            
        # Set ownership to caddy user
        # Note: We assume 'caddy' user exists (created by package install)
        # We do this AFTER installation to ensure user exists
        self.execute_remote(f"sudo chown -R caddy:caddy {self.CADDY_LOG_DIR}", timeout=30)
        self.execute_remote(f"sudo chown -R caddy:caddy {self.CADDY_CONFIG_DIR}", timeout=30)
        
        # Pre-create log files to avoid permission issues
        log_files = ["backend.log", "frontend.log", "comfyui.log", "access.log"]
        for log_file in log_files:
            cmd = f"sudo touch {self.CADDY_LOG_DIR}/{log_file} && sudo chown caddy:caddy {self.CADDY_LOG_DIR}/{log_file} && sudo chmod 644 {self.CADDY_LOG_DIR}/{log_file}"
            self.execute_remote(cmd, timeout=30)
    
    def _generate_caddyfile(
        self,
        cert_path: str,
        key_path: str,
        enable_frontend_proxy: bool
    ) -> str:
        """Generate Caddyfile configuration"""
        logger.debug("Generating Caddyfile configuration...")
        
        # Construct site addresses
        # We want to accept requests for both the IP and the external hostname (if set)
        
        # Backend (8443)
        backend_hosts = [f"{self.node_ip}:{self.https_backend_port}"]
        if self.external_hostname and self.external_hostname != self.node_ip:
             backend_hosts.append(f"{self.external_hostname}:{self.https_backend_port}")
        backend_site_block = ", ".join([f"https://{h}" for h in backend_hosts])
        
        # Frontend (HTTPS 3443 & HTTP 8080)
        # We listen on both to allow Cloudflare Tunnel to connect via HTTP (avoiding SSL errors)
        # AND allow local users to connect via HTTPS.
        
        # HTTPS Hosts (3443)
        https_frontend_hosts = [f"{self.node_ip}:{self.https_frontend_port}"]
        if self.external_hostname and self.external_hostname != self.node_ip:
             https_frontend_hosts.append(f"{self.external_hostname}:{self.https_frontend_port}")
        https_frontend_block_hosts = ", ".join([f"https://{h}" for h in https_frontend_hosts])
        
        # HTTP Hosts (8080)
        http_frontend_hosts = [f"{self.node_ip}:8080"]
        if self.external_hostname and self.external_hostname != self.node_ip:
             http_frontend_hosts.append(f"{self.external_hostname}:8080")
        # Note: http:// prefix is implicit for port 80, but since we use 8080, Caddy treats it as HTTP unless tls is specified.
        # But to be safe and explicit in site block matching:
        http_frontend_block_hosts = ", ".join([f"http://{h}" for h in http_frontend_hosts])
        
        # ComfyUI (8488)
        comfyui_hosts = [f"{self.node_ip}:{self.comfyui_port}"]
        if self.external_hostname and self.external_hostname != self.node_ip:
             comfyui_hosts.append(f"{self.external_hostname}:{self.comfyui_port}")
        comfyui_site_block = ", ".join([f"https://{h}" for h in comfyui_hosts])

        
        caddyfile = f"""# NoSlop Caddy Configuration
# Auto-generated by NoSlop Seed Installer

{{
    # Disable automatic HTTPS (we're using our own certs)
    auto_https off
    # Disable admin API (not needed for our use case)
    admin off
}}

# Backend API (HTTPS)
{backend_site_block} {{
    tls {cert_path} {key_path}
    
    reverse_proxy localhost:{self.backend_port}
    
    log {{
        output file {self.CADDY_LOG_DIR}/backend.log
        format console
    }}
}}
"""
        
        # Unified Frontend Proxy (HTTP & HTTPS)
        if enable_frontend_proxy:
            # Shared routing logic for both HTTP and HTTPS blocks
            routing_logic = f"""
    # Route /api/* requests to Backend API (preserve prefix)
    handle /api/* {{
        reverse_proxy localhost:{self.backend_port}
    }}
    
    # Route /ws/* requests to Backend WebSocket (preserve prefix)
    handle /ws/* {{
        reverse_proxy localhost:{self.backend_port}
    }}
    
    # Route /auth/* requests to Backend Auth endpoints (preserve prefix)
    handle /auth/* {{
        reverse_proxy localhost:{self.backend_port}
    }}

    # Route all other requests to Frontend
    handle /* {{
        reverse_proxy {self.frontend_node_ip}:{self.frontend_port}
    }}
    
    log {{
        output file {self.CADDY_LOG_DIR}/access.log
        format console
    }}
"""

            caddyfile += f"""
# Frontend (HTTPS - Secure Local Access)
{https_frontend_block_hosts} {{
    tls {cert_path} {key_path}
    {routing_logic}
}}

# Frontend (HTTP - Cloudflare Tunnel Access)
{http_frontend_block_hosts} {{
    {routing_logic}
}}
"""
        
        # ComfyUI proxy
        caddyfile += f"""
# ComfyUI (HTTPS)
{comfyui_site_block} {{
    tls {cert_path} {key_path}
    
    reverse_proxy localhost:{self.comfyui_internal_port}
    
    log {{
        output file {self.CADDY_LOG_DIR}/comfyui.log
        format console
    }}
}}
"""
        
        return caddyfile
    
    def _write_caddyfile(self, content: str) -> bool:
        """Write Caddyfile to remote node"""
        logger.debug("Writing Caddyfile...")
        
        try:
            # Write content to temporary file locally
            import tempfile
            with tempfile.NamedTemporaryFile(mode='w', delete=False, suffix='.Caddyfile') as f:
                f.write(content)
                temp_path = f.name
            
            # Transfer to remote
            remote_path = f"{self.CADDY_CONFIG_DIR}/Caddyfile"
            if not self.transfer_file(temp_path, remote_path):
                raise Exception("Failed to transfer Caddyfile")
            
            # Set permissions
            self.execute_remote(f"sudo chmod 644 {remote_path}", timeout=30)
            
            # Cleanup local temp file
            Path(temp_path).unlink()
            
            logger.info(f"✅ Caddyfile written to {remote_path}")
            return True
            
        except Exception as e:
            logger.error(f"Failed to write Caddyfile: {e}")
            return False
    
    def _setup_systemd_service(self):
        """Setup systemd service for Caddy (usually installed by package)"""
        # Caddy package usually installs its own systemd service
        # We just need to enable and start it
        logger.debug("Enabling Caddy systemd service...")
        
        self.execute_remote("sudo systemctl daemon-reload", timeout=30)
        self.execute_remote("sudo systemctl enable caddy", timeout=30)
    
    def _reload_caddy_config(self) -> bool:
        """Reload Caddy configuration"""
        logger.info("Reloading Caddy configuration...")
        
        try:
            # First, validate the Caddyfile
            exit_code, stdout, stderr = self.execute_remote(
                f"sudo caddy validate --config {self.CADDY_CONFIG_DIR}/Caddyfile",
                timeout=30
            )
            
            if exit_code != 0:
                logger.error(f"Caddyfile validation failed: {stderr}")
                return False
            
            # Reload Caddy
            exit_code, stdout, stderr = self.execute_remote(
                "sudo systemctl restart caddy",
                timeout=30
            )
            
            if exit_code != 0:
                logger.error(f"Failed to restart Caddy: {stderr}")
                return False
            
            logger.info("✅ Caddy configuration reloaded")
            return True
            
        except Exception as e:
            logger.error(f"Failed to reload Caddy configuration: {e}")
            return False
    
    def _verify_installation(self) -> bool:
        """Verify Caddy installation"""
        logger.info("Verifying Caddy installation...")
        
        try:
            # Check if Caddy is running
            exit_code, stdout, stderr = self.execute_remote(
                "sudo systemctl is-active caddy",
                timeout=10
            )
            
            if "active" not in stdout.lower():
                logger.error("Caddy service is not active")
                return False
            
            logger.info("✅ Caddy is running")
            return True
            
        except Exception as e:
            logger.error(f"Verification failed: {e}")
            return False
    
    def rollback(self):
        """Rollback Caddy installation"""
        logger.warning(f"Rolling back Caddy installation on {self.node_hostname}...")
        
        try:
            # Stop Caddy
            self.execute_remote("sudo systemctl stop caddy", timeout=30)
            
            # Optionally uninstall (commented out by default - user may want to keep it)
            # self.ssh_manager.run_command(
            #     self.node_hostname,
            #     "sudo apt-get remove -y caddy",
            #     timeout=60
            # )
            
            logger.info("✅ Rollback completed")
            
        except Exception as e:
            logger.error(f"Rollback failed: {e}")
    
    def _fix_cert_permissions(self):
        """Fix permissions for certificate files"""
        if self.cert_path and self.key_path:
            logger.debug("Fixing certificate permissions...")
            # Set ownership to caddy
            self.execute_remote(f"sudo chown caddy:caddy {self.cert_path}", timeout=30)
            self.execute_remote(f"sudo chown caddy:caddy {self.key_path}", timeout=30)
            # Ensure permissions are restrictive
            self.execute_remote(f"sudo chmod 644 {self.cert_path}", timeout=30)
            self.execute_remote(f"sudo chmod 600 {self.key_path}", timeout=30)

    def uninstall(self) -> bool:
        """Uninstall Caddy"""
        logger.info(f"Uninstalling Caddy from {self.node_hostname}...")
        
        try:
            # Stop service
            self.execute_remote("sudo systemctl stop caddy", timeout=30)
            
            # Disable service
            self.execute_remote("sudo systemctl disable caddy", timeout=30)
            
            # Remove package
            self.execute_remote("sudo apt-get remove -y caddy", timeout=60)
            
            # Remove config files (optional)
            self.execute_remote(f"sudo rm -rf {self.CADDY_CONFIG_DIR}", timeout=30)
            
            logger.info("✅ Caddy uninstalled successfully")
            return True
            
        except Exception as e:
            logger.error(f"Uninstall failed: {e}")
            return False
