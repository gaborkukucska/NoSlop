# START OF FILE seed/installers/ollama_installer.py
"""
Ollama Installer for NoSlop Seed.

Installs and configures Ollama on MASTER nodes.
Supports multi-instance deployment for parallel processing.
"""

import time
import json
from typing import List, Optional

from seed.installers.base_installer import BaseInstaller

class OllamaInstaller(BaseInstaller):
    """
    Installs and configures Ollama with multi-instance support.
    """
    
    def __init__(self, device, ssh_manager, port: int = 11434, models: List[str] = None, username: str = "root", password: str = None):
        super().__init__(device, ssh_manager, "ollama", username=username, password=password)
        self.port = port
        self.models = models or ["gemma3:4b-it-q4_K_M", "qwen3-vl:4b-instruct-q8_0", "llava:latest"]
        self.is_secondary = port != 11434

    def check_installed(self) -> bool:
        """Check if Ollama is installed and running on the specific port."""
        # Check if binary exists
        code, _, _ = self.execute_remote("which ollama")
        if code != 0:
            return False
            
        # Check if service is running on the specific port
        # We check if the port is listening
        code, _, _ = self.execute_remote(f"netstat -tuln | grep :{self.port}")
        return code == 0

    def install(self) -> bool:
        """Install Ollama."""
        self.logger.info(f"Installing Ollama (Port {self.port})...")
        
        # Check if binary is already there (might be a secondary instance)
        code, _, _ = self.execute_remote("which ollama")
        if code != 0:
            # Install binary
            if self.device.os_type.value == "linux":
                install_cmd = "curl -fsSL https://ollama.com/install.sh | sh"
                code, out, err = self.execute_remote(install_cmd, timeout=600)
                if code != 0:
                    self.logger.error(f"Ollama installation failed: {err}")
                    return False
            elif self.device.os_type.value == "macos":
                # On macOS we usually download the app, but for CLI usage we can use brew or manual
                # For server usage, manual binary might be better or brew
                if not self.install_packages(["ollama"]):
                    return False
        
        return True

    def configure(self) -> bool:
        """Configure Ollama service."""
        self.logger.info(f"Configuring Ollama on port {self.port}...")
        
        if self.device.os_type.value == "linux":
            # Increase system-wide limits first
            self.logger.info("Increasing system-wide file limits...")
            self.execute_remote("echo 'fs.inotify.max_user_watches=524288' | sudo tee -a /etc/sysctl.conf && sudo sysctl -p")
            
            # Create systemd service
            service_name = "ollama" if not self.is_secondary else f"ollama-{self.port}"
            
            # Read template
            with open("seed/templates/systemd_template.service", "r") as f:
                template = f.read()
            
            # Fill template
            service_content = template.replace("{{SERVICE_NAME}}", service_name)
            service_content = service_content.replace("{{USER}}", "root") # Or specific user
            service_content = service_content.replace("{{WORKING_DIR}}", "/root")
            
            # Determine logs directory
            logs_dir = "/root/NoSlop/logs"
            
            # Ensure logs directory exists
            self.execute_remote(f"mkdir -p {logs_dir}")
            
            # Create timestamped log file path
            from datetime import datetime
            timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
            service_name_log = "ollama" if not self.is_secondary else f"ollama-{self.port}"
            log_file = f"{logs_dir}/{service_name_log}_{timestamp}.log"
            
            # Set exec command
            exec_cmd = "/usr/local/bin/ollama serve"
            service_content = service_content.replace("{{EXEC_START}}", exec_cmd)
            
            # Environment variables
            env_vars = f"OLLAMA_HOST=0.0.0.0:{self.port}"
            service_content = service_content.replace("{{ENVIRONMENT_VARS}}", env_vars)
            
            # Add StandardOutput and StandardError directives for logging
            service_content = service_content.replace(
                "[Service]",
                f"[Service]\nStandardOutput=append:{log_file}\nStandardError=append:{log_file}"
            )
            
            # Add LimitNOFILE to Service section
            # We inject it before Environment
            service_content = service_content.replace("Environment=", "LimitNOFILE=65536\nEnvironment=")
            
            # Write service file
            remote_path = f"/etc/systemd/system/{service_name}.service"
            
            # We need to write this content to a temp file and transfer it
            import tempfile
            import os
            
            with tempfile.NamedTemporaryFile(mode='w', delete=False) as tmp:
                tmp.write(service_content)
                tmp_path = tmp.name
            
            try:
                if not self.transfer_file(tmp_path, f"/tmp/{service_name}.service"):
                    return False
                
                # Move to correct location (requires sudo)
                # Increased timeout for mv command as it was timing out
                self.execute_remote(f"sudo mv /tmp/{service_name}.service {remote_path}", timeout=60)
                self.execute_remote("sudo systemctl daemon-reload")
                
            finally:
                os.unlink(tmp_path)
                
        return True

    def start(self) -> bool:
        """Start Ollama service."""
        self.logger.info(f"Starting Ollama on port {self.port}...")
        
        service_name = "ollama" if not self.is_secondary else f"ollama-{self.port}"
        
        if self.device.os_type.value == "linux":
            code, _, err = self.execute_remote(f"sudo systemctl enable {service_name} && sudo systemctl start {service_name}")
            if code != 0:
                self.logger.error(f"Failed to start {service_name}: {err}")
                return False
                
        # Wait for service to be ready
        time.sleep(5)
        return True

    def verify(self) -> bool:
        """Verify Ollama is running and pull models."""
        self.logger.info(f"Verifying Ollama on port {self.port}...")
        
        # Check API
        cmd = f"curl -s http://localhost:{self.port}/api/tags"
        code, out, err = self.execute_remote(cmd)
        
        if code != 0:
            self.logger.error(f"Ollama API check failed: {err}")
            return False
            
        self.logger.info("✓ Ollama API is accessible")
        
        # Pull models
        for model in self.models:
            self.logger.info(f"Pulling model {model}...")
            # We use the CLI to pull, setting OLLAMA_HOST
            pull_cmd = f"OLLAMA_HOST=localhost:{self.port} ollama pull {model}"
            # This can take a while
            code, out, err = self.execute_remote(pull_cmd, timeout=1200) # 20 mins timeout
            if code != 0:
                self.logger.error(f"Failed to pull model {model}: {err}")
                # We don't fail the whole installation if one model fails, but we log it
            else:
                self.logger.info(f"✓ Model {model} pulled successfully")
                
        return True

    def rollback(self):
        """Rollback installation."""
        service_name = "ollama" if not self.is_secondary else f"ollama-{self.port}"
        if self.device.os_type.value == "linux":
            self.execute_remote(f"sudo systemctl stop {service_name}")
            self.execute_remote(f"sudo systemctl disable {service_name}")
            self.execute_remote(f"sudo rm /etc/systemd/system/{service_name}.service")
            self.execute_remote("sudo systemctl daemon-reload")
