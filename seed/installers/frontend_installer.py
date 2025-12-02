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
    
    def __init__(self, device, ssh_manager, env_config: dict = None, username: str = "root"):
        super().__init__(device, ssh_manager, "noslop-frontend", username=username)
        self.install_dir = "/opt/noslop/frontend"
        self.env_config = env_config or {}

    def check_installed(self) -> bool:
        """Check if Frontend is installed."""
        # Check directory
        code, _, _ = self.execute_remote(f"test -d {self.install_dir}")
        if code != 0:
            return False
            
        # Check service
        code, _, _ = self.execute_remote("systemctl is-active noslop-frontend")
        return code == 0

    def install(self) -> bool:
        """Install Frontend."""
        self.logger.info("Installing NoSlop Frontend...")
        
        # Install system dependencies (Node.js)
        # We try to install nodejs and npm
        self.install_packages(["nodejs", "npm", "git"])
        
        # Check node version
        code, out, _ = self.execute_remote("node -v")
        if code == 0:
            self.logger.info(f"Node.js version: {out}")
        else:
            self.logger.error("Node.js installation failed")
            return False
            
        # Create directory
        self.execute_remote(f"mkdir -p {self.install_dir}")
        
        # Transfer frontend files
        local_frontend_dir = Path("frontend").absolute()
        if not local_frontend_dir.exists():
            self.logger.error(f"Local frontend directory not found at {local_frontend_dir}")
            return False
            
        self.logger.info(f"Transferring frontend files from {local_frontend_dir}...")
        # We exclude node_modules and .next to save time/bandwidth
        # Since transfer_directory doesn't support excludes yet, we might transfer everything
        # or better, we should have implemented excludes.
        # For now, let's assume the user hasn't built locally or we transfer everything.
        # A better approach for production would be to build locally and transfer artifacts,
        # but for now we transfer source and build on target.
        if not self.ssh_manager.transfer_directory(self.ssh_client, str(local_frontend_dir), self.install_dir):
            return False
            
        # Install dependencies
        self.logger.info("Installing npm dependencies...")
        code, _, err = self.execute_remote(f"cd {self.install_dir} && npm install", timeout=600)
        if code != 0:
            self.logger.error(f"Failed to install npm dependencies: {err}")
            return False
            
        # Build
        self.logger.info("Building Next.js application...")
        # We need to set env vars for build if needed
        env_vars = " ".join([f"{k}={v}" for k, v in self.env_config.items()])
        code, _, err = self.execute_remote(f"cd {self.install_dir} && {env_vars} npm run build", timeout=900)
        if code != 0:
            self.logger.error(f"Failed to build application: {err}")
            return False
            
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
            env_vars = " ".join([f"{k}={v}" for k, v in self.env_config.items()])
            
            service_content = template.replace("{{SERVICE_NAME}}", "noslop-frontend")
            service_content = service_content.replace("{{USER}}", "root") # Should be specific user
            service_content = service_content.replace("{{WORKING_DIR}}", self.install_dir)
            service_content = service_content.replace("{{EXEC_START}}", exec_cmd)
            service_content = service_content.replace("{{ENVIRONMENT_VARS}}", env_vars)
            
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
                
                self.execute_remote(f"mv /tmp/noslop-frontend.service {remote_path}")
                self.execute_remote("systemctl daemon-reload")
            finally:
                os.unlink(tmp_path)
                
        return True

    def start(self) -> bool:
        """Start Frontend service."""
        self.logger.info("Starting NoSlop Frontend...")
        
        if self.device.os_type.value == "linux":
            code, _, err = self.execute_remote("systemctl enable noslop-frontend && systemctl start noslop-frontend")
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
            self.execute_remote("systemctl stop noslop-frontend")
            self.execute_remote("systemctl disable noslop-frontend")
            self.execute_remote("rm /etc/systemd/system/noslop-frontend.service")
            self.execute_remote("systemctl daemon-reload")
            
        self.execute_remote(f"rm -rf {self.install_dir}")
