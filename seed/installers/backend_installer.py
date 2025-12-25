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
        self.install_packages(["python3", "python3-venv", "python3-pip", "git", "curl", "postgresql-client", "rsync"])
        
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
        
        # Ensure model directories exist (for local storage fallback)
        # If shared storage is mounted at /mnt/noslop, this might already be handled by StorageManager,
        # but we ensure local fallback /var/noslop/models exists too.
        models_dir = "/var/noslop/models"
        self.execute_remote(f"sudo mkdir -p {models_dir}/transformers {models_dir}/whisper")
        self.execute_remote(f"sudo chown -R {self.username}:{self.username} {models_dir}")
        
        # Also check if we are using custom paths from env code (e.g. shared storage)
        # and ensure they exist and are writable
        hf_home = self.env_config.get("HF_HOME")
        whisper_cache = self.env_config.get("WHISPER_CACHE_DIR")
        
        if hf_home:
             self.execute_remote(f"sudo mkdir -p {hf_home}")
             self.execute_remote(f"sudo chown -R {self.username}:{self.username} {hf_home}")
             
        if whisper_cache:
             self.execute_remote(f"sudo mkdir -p {whisper_cache}")
             self.execute_remote(f"sudo chown -R {self.username}:{self.username} {whisper_cache}")
        
        # Ensure media storage directory exists (for user avatars, generated content, etc.)
        media_dir = self.env_config.get("MEDIA_STORAGE_PATH", "/var/noslop/media")
        self.execute_remote(f"sudo mkdir -p {media_dir}")
        self.execute_remote(f"sudo chown -R {self.username}:{self.username} {media_dir}")
        
        # Ensure project storage directory exists
        project_dir = self.env_config.get("PROJECT_STORAGE_PATH", "/var/noslop/projects")
        self.execute_remote(f"sudo mkdir -p {project_dir}")
        self.execute_remote(f"sudo chown -R {self.username}:{self.username} {project_dir}")


            
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
            
        # Run database migrations (if any) or schema check
        self.logger.info("Checking database schema and performing necessary upgrades...")
        
        # Explicitly pass DATABASE_URL to ensure manage_db.py connects to the correct DB (Postgres)
        # and not the default SQLite if .env loading fails or is ambiguous.
        db_url = self.env_config.get("DATABASE_URL", "")
        cmd = f"cd {self.install_dir} && DATABASE_URL='{db_url}' {self.venv_dir}/bin/python manage_db.py --check-and-upgrade"
        
        code, out, err = self.execute_remote(cmd)
        if code != 0:
            self.logger.warning(f"Database check failed: {err}")
            # We don't fail the install, but warn users
        else:
            self.logger.info("Database check completed.")
            if "SAFEGUARD" in out: # Log the backup message if present
                 self.logger.info(f"Database update info:\n{out}")
        
        if self.device.os_type.value == "linux":
            # Create systemd service
            with open("seed/templates/systemd_template.service", "r") as f:
                template = f.read()
            
            exec_cmd = f"{self.venv_dir}/bin/python main.py"
            
            # Determine logs directory in user's home
            # We want logs to go to /home/<user>/NoSlop/logs
            # Since we are installing on a remote device, we construct the path
            # assuming standard home directory structure
            # Determine logs directory
            # If LOG_DIR is passed in config (e.g. from shared storage), use it
            if self.env_config.get("LOG_DIR"):
                logs_dir = self.env_config["LOG_DIR"]
            elif self.username == "root":
                logs_dir = "/root/NoSlop/logs"
            else:
                logs_dir = f"/home/{self.username}/NoSlop/logs"
            
            # Ensure logs directory exists with correct permissions
            self.execute_remote(f"sudo mkdir -p {logs_dir}")
            self.execute_remote(f"sudo chown {self.username}:{self.username} {logs_dir}")
            
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
        
        # Retry loop for health check
        # Increased timeout to handle large model downloads (Whisper/SpeechT5) on first run
        max_retries = 60
        retry_interval = 10
        
        for i in range(max_retries):
            cmd = "curl -s http://localhost:8000/health"
            code, out, err = self.execute_remote(cmd)
            
            if code == 0:
                self.logger.info("✓ Backend API is accessible")
                return True
                
            if (i + 1) % 5 == 0:
                 self.logger.info(f"Health check attempt {i+1}/{max_retries} failed. Backend might be downloading models...")
            
            time.sleep(retry_interval)
            
        self.logger.error(f"Backend health check failed after {max_retries} attempts.")
        # Try to get logs to help debugging
        self.logger.info("Fetching recent logs...")
        self.execute_remote("sudo journalctl -u noslop-backend -n 50 --no-pager")
        
        # Actually fetch the logs (previous command was just running it in void context if result ignored?)
        # Wait, execute_remote executes and returns code, out, err.
        # We need to capture and log 'out'.
        code, logs, err = self.execute_remote("sudo journalctl -u noslop-backend -n 50 --no-pager")
        if code == 0:
            self.logger.error(f"Backend Logs:\n{logs}")
        else:
            self.logger.error(f"Failed to fetch logs: {err}")
        return False

    def rollback(self):
        """Rollback installation."""
        if self.device.os_type.value == "linux":
            self.execute_remote("sudo systemctl stop noslop-backend")
            self.execute_remote("sudo systemctl disable noslop-backend")
            self.execute_remote("sudo rm /etc/systemd/system/noslop-backend.service")
            self.execute_remote("sudo systemctl daemon-reload")
            
        self.execute_remote(f"sudo rm -rf {self.install_dir}")
