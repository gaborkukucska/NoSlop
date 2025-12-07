# START OF FILE seed/installers/backend_installer.py
"""
Backend Installer for NoSlop Seed.

Deploys NoSlop FastAPI backend on MASTER nodes.
"""

import time
import os
from pathlib import Path

from seed.installers.base_installer import BaseInstaller

class BackendInstaller(BaseInstaller):
    """
    Installs and configures NoSlop Backend.
    """
    
    def __init__(self, device, ssh_manager, env_config: dict = None, username: str = "root", password: str = None):
        super().__init__(device, ssh_manager, "noslop-backend", username=username, password=password)
        self.install_dir = "/opt/noslop/backend"
        self.venv_dir = f"{self.install_dir}/venv"
        self.env_config = env_config or {}

    def check_installed(self) -> bool:
        """Check if Backend is installed."""
        # Always return False to force update of files
        return False

    def install(self) -> bool:
        """Install Backend."""
        self.logger.info("Installing NoSlop Backend...")
        
        # Install system dependencies
        self.install_packages(["python3", "python3-venv", "python3-pip", "git"])
        
        # Create directory
        self.execute_remote(f"sudo mkdir -p {self.install_dir}")
        
        # Change ownership to the user
        self.logger.info(f"Changing ownership of {self.install_dir} to {self.username}...")
        self.execute_remote(f"sudo chown -R {self.username}:{self.username} {self.install_dir}")
        
        # Transfer backend files
        # We assume we are running from repo root
        local_backend_dir = Path("backend").absolute()
        if not local_backend_dir.exists():
            self.logger.error(f"Local backend directory not found at {local_backend_dir}")
            return False
            
        self.logger.info(f"Transferring backend files from {local_backend_dir}...")
        excludes = ["venv", "__pycache__", ".git", ".pytest_cache", "*.pyc", ".idea", ".vscode"]
        if not self.transfer_directory(str(local_backend_dir), self.install_dir, excludes=excludes):
            return False
            
        # Change ownership to the user AGAIN because sudo cp (used in transfer_directory for local)
        # makes files owned by root.
        self.logger.info(f"Fixing ownership of {self.install_dir} to {self.username}...")
        self.execute_remote(f"sudo chown -R {self.username}:{self.username} {self.install_dir}")
            
        # Check if venv exists
        code, _, _ = self.execute_remote(f"test -d {self.venv_dir}")
        venv_exists = (code == 0)
        
        if not venv_exists:
            # Create venv
            self.logger.info("Creating virtual environment...")
            code, _, err = self.execute_remote(f"python3 -m venv {self.venv_dir}")
            if code != 0:
                self.logger.error(f"Failed to create venv: {err}")
                return False
        else:
            self.logger.info("Virtual environment already exists, skipping creation.")
            
        # Installing requirements
        self.logger.info("Installing requirements...")
        # We use --force-reinstall to ensure integrity, as we've seen corrupted installs
        code, _, err = self.execute_remote(f"{self.venv_dir}/bin/pip install --force-reinstall -r {self.install_dir}/requirements.txt", timeout=600)
        if code != 0:
            self.logger.error(f"Failed to install requirements: {err}")
            return False
            
        return True

    def configure(self) -> bool:
        """Configure Backend service."""
        self.logger.info("Configuring NoSlop Backend...")
        
        # Create .env file
        env_content = ""
        for key, value in self.env_config.items():
            env_content += f"{key}={value}\n"
            
        # Write .env to temp file and transfer
        import tempfile
        with tempfile.NamedTemporaryFile(mode='w', delete=False) as tmp:
            tmp.write(env_content)
            tmp_path = tmp.name
            
        try:
            if not self.transfer_file(tmp_path, f"{self.install_dir}/.env"):
                return False
        finally:
            os.unlink(tmp_path)
            
        # Run database migrations (if any)
        # For now we just ensure DB is accessible
        
        if self.device.os_type.value == "linux":
            # Create systemd service
            with open("seed/templates/systemd_template.service", "r") as f:
                template = f.read()
            
            exec_cmd = f"{self.venv_dir}/bin/python main.py"
            
            # Determine logs directory in user's home
            # We want logs to go to /home/<user>/NoSlop/logs
            # Since we are installing on a remote device, we construct the path
            # assuming standard home directory structure
            if self.username == "root":
                home_dir = "/root"
            else:
                home_dir = f"/home/{self.username}"
                
            logs_dir = f"{home_dir}/NoSlop/logs"
            
            # Ensure logs directory exists with correct permissions
            self.execute_remote(f"mkdir -p {logs_dir}")
            self.execute_remote(f"chown {self.username}:{self.username} {logs_dir}")
            
            service_content = template.replace("{{SERVICE_NAME}}", "noslop-backend")
            service_content = service_content.replace("{{USER}}", self.username) # Use the actual user
            service_content = service_content.replace("{{WORKING_DIR}}", self.install_dir)
            service_content = service_content.replace("{{EXEC_START}}", exec_cmd)
            
            # Add LOG_DIR to environment variables
            # Systemd Environment directive takes space-separated assignments
            # We also include any other env vars from config
            env_vars = f"LOG_DIR={logs_dir}"
            service_content = service_content.replace("{{ENVIRONMENT_VARS}}", env_vars)
            
            # Write service file
            with tempfile.NamedTemporaryFile(mode='w', delete=False) as tmp:
                tmp.write(service_content)
                tmp_path = tmp.name
                
            try:
                remote_path = "/etc/systemd/system/noslop-backend.service"
                if not self.transfer_file(tmp_path, f"/tmp/noslop-backend.service"):
                    return False
                
                self.execute_remote(f"sudo mv /tmp/noslop-backend.service {remote_path}")
                self.execute_remote("sudo systemctl daemon-reload")
            finally:
                os.unlink(tmp_path)
                
        return True

    def start(self) -> bool:
        """Start Backend service."""
        self.logger.info("Starting NoSlop Backend...")
        
        if self.device.os_type.value == "linux":
            code, _, err = self.execute_remote("sudo systemctl enable noslop-backend && sudo systemctl restart noslop-backend")
            if code != 0:
                self.logger.error(f"Failed to start backend: {err}")
                return False
                
        time.sleep(5)
        return True

    def verify(self) -> bool:
        """Verify Backend is running."""
        self.logger.info("Verifying NoSlop Backend...")
        
        cmd = "curl -s http://localhost:8000/health"
        code, out, err = self.execute_remote(cmd)
        
        if code != 0:
            self.logger.error(f"Backend health check failed: {err}")
            return False
            
        self.logger.info("✓ Backend API is accessible")
        return True

    def rollback(self):
        """Rollback installation."""
        if self.device.os_type.value == "linux":
            self.execute_remote("sudo systemctl stop noslop-backend")
            self.execute_remote("sudo systemctl disable noslop-backend")
            self.execute_remote("sudo rm /etc/systemd/system/noslop-backend.service")
            self.execute_remote("sudo systemctl daemon-reload")
            
        self.execute_remote(f"sudo rm -rf {self.install_dir}")
