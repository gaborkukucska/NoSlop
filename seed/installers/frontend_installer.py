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
        
        # Transfer frontend files
        # Note: The base class transfer_directory method will handle directory creation
        # and permission setup for remote transfers
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
        excludes = ["node_modules", ".next", ".git", ".idea", ".vscode"]
        if not self.transfer_directory(str(local_frontend_dir), self.install_dir, excludes=excludes):
            return False
        
        # Change ownership to the user AFTER file transfer
        self.logger.info(f"Changing ownership of {self.install_dir} to {self.username}...")
        code, _, err = self.execute_remote(f"sudo chown -R {self.username}:{self.username} {self.install_dir}")
        if code != 0:
            self.logger.error(f"Failed to change ownership: {err}")
            return False
        
        # Verify ownership was set correctly
        code, out, _ = self.execute_remote(f"stat -c '%U' {self.install_dir}")
        if code == 0:
            self.logger.debug(f"Directory owner: {out.strip()}")
        
        # Clean up any existing node_modules to avoid permission issues
        self.logger.info("Cleaning up existing node_modules if present...")
        self.execute_remote(f"sudo rm -rf {self.install_dir}/node_modules {self.install_dir}/.next")
            
        # Install dependencies as the user (not root)
        # Source nvm if available to use the correct Node.js version
        self.logger.info(f"Installing npm dependencies as user {self.username}...")
        nvm_setup = "export NVM_DIR=\"$HOME/.nvm\" && [ -s \"$NVM_DIR/nvm.sh\" ] && . \"$NVM_DIR/nvm.sh\" && "
        code, _, err = self.execute_remote(
            f"sudo -u {self.username} bash -c '{nvm_setup}cd {self.install_dir} && npm install'", 
            timeout=600
        )
        if code != 0:
            self.logger.error(f"Failed to install npm dependencies: {err}")
            return False
            
        # Build as the user (not root)
        # Source nvm if available to use the correct Node.js version
        self.logger.info(f"Building Next.js application as user {self.username}...")
        # We need to set env vars for build if needed
        env_vars = " ".join([f"{k}={v}" for k, v in self.env_config.items()])
        build_cmd = f"cd {self.install_dir} && {env_vars} npm run build" if env_vars else f"cd {self.install_dir} && npm run build"
        code, _, err = self.execute_remote(
            f"sudo -u {self.username} bash -c '{nvm_setup}{build_cmd}'", 
            timeout=900
        )
        if code != 0:
            self.logger.error(f"Failed to build application: {err}")
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
            
            # Environment variables - add nvm setup
            nvm_env = f"NVM_DIR={self.install_dir.replace('/opt/noslop/frontend', '/home/' + self.username + '/.nvm')}"
            env_vars_list = [nvm_env]
            if self.env_config:
                env_vars_list.extend([f"{k}={v}" for k, v in self.env_config.items()])
            env_vars = " ".join(env_vars_list)
            
            # Create ExecStart command that sources nvm
            exec_start = f"/bin/bash -c 'export NVM_DIR=\"$HOME/.nvm\" && [ -s \"$NVM_DIR/nvm.sh\" ] && . \"$NVM_DIR/nvm.sh\" && npm start'"
            
            service_content = template.replace("{{SERVICE_NAME}}", "noslop-frontend")
            service_content = service_content.replace("{{USER}}", self.username)  # Use the actual user, not root
            service_content = service_content.replace("{{WORKING_DIR}}", self.install_dir)
            service_content = service_content.replace("{{EXEC_START}}", exec_start)
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
                
                self.execute_remote(f"sudo mv /tmp/noslop-frontend.service {remote_path}")
                self.execute_remote("sudo systemctl daemon-reload")
            finally:
                os.unlink(tmp_path)
                
        return True

    def start(self) -> bool:
        """Start Frontend service."""
        self.logger.info("Starting NoSlop Frontend...")
        
        if self.device.os_type.value == "linux":
            code, _, err = self.execute_remote("sudo systemctl enable noslop-frontend && sudo systemctl start noslop-frontend")
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
