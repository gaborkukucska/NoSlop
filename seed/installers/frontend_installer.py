# START OF FILE seed/installers/frontend_installer.py
"""
Frontend Installer for NoSlop Seed.

Deploys NoSlop Next.js frontend on CLIENT nodes.
"""

import time
import os
from pathlib import Path

from seed.installers.base_installer import BaseInstaller

class FrontendInstaller(BaseInstaller):
    """
    Installs and configures NoSlop Frontend.
    """
    
    def __init__(self, device, ssh_manager, env_config: dict = None, username: str = "root", password: str = None):
        super().__init__(device, ssh_manager, "noslop-frontend", username=username, password=password)
        self.install_dir = "/opt/noslop/frontend"
        self.env_config = env_config or {}

    def check_installed(self) -> bool:
        """Check if Frontend is installed."""
        # Always return False to force update of files
        return False

    def install(self) -> bool:
        """Install Frontend."""
        self.logger.info("Installing NoSlop Frontend...")
        
        # Install Node.js 20.x from NodeSource repository (Next.js requires >=20.9.0)
        self.logger.info("Installing Node.js 20.x from NodeSource...")
        
        # Install prerequisites
        self.install_packages(["curl", "ca-certificates", "gnupg"])
        
        # Add NodeSource repository for Node.js 20.x
        code, _, err = self.execute_remote(
            "curl -fsSL https://deb.nodesource.com/setup_20.x | sudo -E bash -",
            timeout=120
        )
        if code != 0:
            self.logger.error(f"Failed to add NodeSource repository: {err}")
            return False
        
        # Install Node.js and npm
        self.install_packages(["nodejs", "git"])
        
        # Check node version
        code, out, _ = self.execute_remote("node -v")
        if code == 0:
            self.logger.info(f"Node.js version: {out}")
            # Verify version is >= 20
            version = out.strip().lstrip('v')
            try:
                major_version = int(version.split('.')[0])
                if major_version < 20:
                    self.logger.warning(f"Node.js version {out} is too old. Attempting to upgrade to 20.x...")
                    # Remove old version
                    self.execute_remote("sudo apt-get remove -y nodejs npm libnode*")
                    self.execute_remote("sudo apt-get autoremove -y")
                    
                    # Re-add repo and install
                    self.execute_remote("curl -fsSL https://deb.nodesource.com/setup_20.x | sudo -E bash -")
                    self.install_packages(["nodejs", "build-essential"])
                    
                    # Check again
                    code, out, _ = self.execute_remote("node -v")
                    if code == 0:
                        new_version = out.strip().lstrip('v')
                        if int(new_version.split('.')[0]) < 20:
                            self.logger.error(f"Failed to upgrade Node.js. Still version {out}")
                            return False
                        self.logger.info(f"Upgraded to Node.js {out}")
                    else:
                        self.logger.error("Failed to verify Node.js after upgrade")
                        return False
            except ValueError:
                self.logger.warning(f"Could not parse Node.js version: {out}")
        else:
            self.logger.warning("Node.js not found or error checking version. Proceeding with installation...")
            # Re-add repo and install
            self.execute_remote("curl -fsSL https://deb.nodesource.com/setup_20.x | sudo -E bash -")
            self.install_packages(["nodejs", "build-essential"])
        
        # Create the install directory with proper ownership BEFORE transferring files
        self.logger.info(f"Creating install directory {self.install_dir} with ownership {self.username}...")
        code, _, err = self.execute_remote(f"sudo mkdir -p {self.install_dir}")
        if code != 0:
            self.logger.error(f"Failed to create directory: {err}")
            return False
        
        code, _, err = self.execute_remote(f"sudo chown -R {self.username}:{self.username} {self.install_dir}")
        if code != 0:
            self.logger.error(f"Failed to set ownership: {err}")
            return False
        
        # Transfer frontend files
        local_frontend_dir = Path("frontend").absolute()
        if not local_frontend_dir.exists():
            self.logger.error(f"Local frontend directory not found at {local_frontend_dir}")
            return False
            
        self.logger.info(f"Transferring frontend files from {local_frontend_dir}...")
        excludes = ["node_modules", ".next", ".git", ".idea", ".vscode"]
        if not self.transfer_directory(str(local_frontend_dir), self.install_dir, excludes=excludes):
            return False
        
        # Ensure ownership is correct after transfer
        self.logger.info(f"Ensuring ownership of {self.install_dir} is set to {self.username}...")
        code, _, err = self.execute_remote(f"sudo chown -R {self.username}:{self.username} {self.install_dir}")
        if code != 0:
            self.logger.error(f"Failed to change ownership: {err}")
            return False
        
        # Verify ownership was set correctly
        code, out, _ = self.execute_remote(f"stat -c '%U' {self.install_dir}")
        if code == 0:
            self.logger.debug(f"Directory owner: {out.strip()}")
        
        # Clean up existing node_modules ONLY if we suspect corruption, otherwise keep for speed
        # self.logger.info("Cleaning up existing node_modules if present...")
        # self.execute_remote(f"sudo rm -rf {self.install_dir}/node_modules {self.install_dir}/.next")
            
        # Install dependencies as the user (not root)
        self.logger.info(f"Installing npm dependencies as user {self.username}...")
        code, out, err = self.execute_remote(
            f"cd {self.install_dir} && sudo -u {self.username} npm install", 
            timeout=600
        )
        if code != 0:
            self.logger.error(f"Failed to install npm dependencies: {err}")
            self.logger.error(f"npm install stdout: {out}")
            return False
            
        # Build as the user (not root)
        self.logger.info(f"Building Next.js application as user {self.username}...")
        # We need to set env vars for build if needed
        env_vars = " ".join([f"{k}={v}" for k, v in self.env_config.items()])
        build_cmd = f"{env_vars} npm run build" if env_vars else "npm run build"
        code, out, err = self.execute_remote(
            f"cd {self.install_dir} && sudo -u {self.username} {build_cmd}", 
            timeout=900
        )
        if code != 0:
            self.logger.error(f"Failed to build application: {err}")
            self.logger.error(f"npm build stdout: {out}")
            return False
        
        # Create .env.local file with frontend-specific environment variables
        self.logger.info("Creating .env.local file for frontend...")
        env_local_content = ""
        
        # Add NEXT_PUBLIC_API_URL from NOSLOP_BACKEND_URL
        if "NOSLOP_BACKEND_URL" in self.env_config:
            env_local_content += f"NEXT_PUBLIC_API_URL={self.env_config['NOSLOP_BACKEND_URL']}\n"
        else:
            # Fallback to localhost
            env_local_content += "NEXT_PUBLIC_API_URL=http://localhost:8000\n"
        
        # Write .env.local file
        import tempfile
        with tempfile.NamedTemporaryFile(mode='w', delete=False, suffix='.env') as tmp:
            tmp.write(env_local_content)
            tmp_env_path = tmp.name
        
        try:
            if not self.transfer_file(tmp_env_path, f"{self.install_dir}/.env.local"):
                self.logger.warning("Failed to transfer .env.local file")
            else:
                # Set ownership
                self.execute_remote(f"sudo chown {self.username}:{self.username} {self.install_dir}/.env.local")
        finally:
            import os
            os.unlink(tmp_env_path)
            
        return True

    def configure(self) -> bool:
        """Configure Frontend service."""
        self.logger.info("Configuring NoSlop Frontend...")
        
        if self.device.os_type.value == "linux":
            # Create systemd service
            with open("seed/templates/systemd_template.service", "r") as f:
                template = f.read()
            
            exec_cmd = "/usr/bin/npm start"
            
            # Environment variables
            env_vars_list = []
            if self.env_config:
                env_vars_list.extend([f"{k}={v}" for k, v in self.env_config.items()])
            env_vars_list.append("HOSTNAME=0.0.0.0")  # Bind to all interfaces
            env_vars = " ".join(env_vars_list)
            
            # Use system npm directly
            exec_start = "/usr/bin/npm start"
            
            # Determine logs directory
            if self.username == "root":
                home_dir = "/root"
            else:
                home_dir = f"/home/{self.username}"
            logs_dir = f"{home_dir}/NoSlop/logs"
            
            # Ensure logs directory exists
            self.execute_remote(f"mkdir -p {logs_dir}")
            self.execute_remote(f"chown {self.username}:{self.username} {logs_dir}")

            # Create timestamped log file path
            from datetime import datetime
            timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
            log_file = f"{logs_dir}/frontend_{timestamp}.log"
            
            service_content = template.replace("{{SERVICE_NAME}}", "noslop-frontend")
            service_content = service_content.replace("{{USER}}", self.username)  # Use the actual user, not root
            service_content = service_content.replace("{{WORKING_DIR}}", self.install_dir)
            service_content = service_content.replace("{{EXEC_START}}", exec_start)
            service_content = service_content.replace("{{ENVIRONMENT_VARS}}", env_vars)
            
            # Add StandardOutput and StandardError directives for logging
            # Insert after [Service] section
            service_content = service_content.replace(
                "[Service]",
                f"[Service]\nStandardOutput=append:{log_file}\nStandardError=append:{log_file}"
            )
            
            # Write service file
            import tempfile
            import os
            
            with tempfile.NamedTemporaryFile(mode='w', delete=False) as tmp:
                tmp.write(service_content)
                tmp_path = tmp.name
                
            try:
                remote_path = "/etc/systemd/system/noslop-frontend.service"
                if not self.transfer_file(tmp_path, f"/tmp/noslop-frontend.service"):
                    return False
                
                self.execute_remote(f"sudo mv /tmp/noslop-frontend.service {remote_path}")
                self.execute_remote("sudo systemctl daemon-reload")
            finally:
                os.unlink(tmp_path)
                
        return True

    def start(self) -> bool:
        """Start Frontend service."""
        self.logger.info("Starting NoSlop Frontend...")
        
        if self.device.os_type.value == "linux":
            code, _, err = self.execute_remote("sudo systemctl enable noslop-frontend && sudo systemctl restart noslop-frontend")
            if code != 0:
                self.logger.error(f"Failed to start frontend: {err}")
                return False
                
        time.sleep(5)
        return True

    def verify(self) -> bool:
        """Verify Frontend is running."""
        self.logger.info("Verifying NoSlop Frontend...")
        
        cmd = "curl -s http://localhost:3000"
        code, out, err = self.execute_remote(cmd)
        
        if code != 0:
            self.logger.error(f"Frontend health check failed: {err}")
            return False
            
        self.logger.info("✓ Frontend is accessible")
        return True

    def rollback(self):
        """Rollback installation."""
        if self.device.os_type.value == "linux":
            self.execute_remote("sudo systemctl stop noslop-frontend")
            self.execute_remote("sudo systemctl disable noslop-frontend")
            self.execute_remote("sudo rm /etc/systemd/system/noslop-frontend.service")
            self.execute_remote("sudo systemctl daemon-reload")
            
        self.execute_remote(f"sudo rm -rf {self.install_dir}")
