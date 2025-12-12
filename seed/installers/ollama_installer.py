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
    
    def __init__(self, device, ssh_manager, port: int = 11434, models: List[str] = None, 
                 username: str = "root", password: str = None, models_dir: Optional[str] = None):
        super().__init__(device, ssh_manager, "ollama", username=username, password=password)
        self.port = port
        self.models = models or ["gemma3:4b-it-q4_K_M", "qwen3-vl:4b-instruct-q8_0", "llava:latest"]
        self.is_secondary = port != 11434
        self.models_dir = models_dir  # Shared models directory (optional)

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
    
    def _check_port_conflict(self) -> bool:
        """
        Check if port is already in use and handle conflicts.
        
        Returns:
            True if port is free or conflict resolved, False otherwise
        """
        # Check if port is in use
        code, _, _ = self.execute_remote(f"netstat -tuln | grep :{self.port}")
        if code != 0:
            # Port is free
            return True
        
        self.logger.warning(f"Port {self.port} is already in use")
        
        # Check for non-systemd ollama processes
        code, out, _ = self.execute_remote("ps aux | grep 'ollama serve' | grep -v grep")
        if code == 0 and out.strip():
            self.logger.warning(f"Found running Ollama process(es):")
            for line in out.strip().split('\n')[:3]:  # Show first 3 processes
                self.logger.warning(f"  {line}")
            
            # Prompt user for action
            print(f"\n⚠️  Port {self.port} is already in use by existing Ollama process(es).")
            print("   This may be a manual Ollama instance or orphaned process.")
            response = input("   Kill existing process(es) and continue? (Y/n): ").strip().lower()
            
            if response == '' or response == 'y' or response == 'yes':
                self.logger.info("Killing existing Ollama processes...")
                
                # Try systemctl stop first (cleanest way)
                stop_code, _, _ = self.execute_remote("sudo systemctl stop ollama 2>/dev/null || true", timeout=10)
                time.sleep(2)
                
                # Check if processes are gone
                check_code, _, _ = self.execute_remote("ps aux | grep 'ollama serve' | grep -v grep")
                if check_code != 0:
                    # No processes found - success!
                    self.logger.info("✓ Processes stopped via systemctl")
                    return True
                
                # Still running - use pkill -9 (force kill)
                self.logger.info("Processes still running, using force kill...")
                kill_code, _, err = self.execute_remote("sudo pkill -9 -f 'ollama serve'", timeout=10)
                time.sleep(2)
               
                # Final check
                final_code, _, _ = self.execute_remote("ps aux | grep 'ollama serve' | grep -v grep")
                if final_code != 0:
                    self.logger.info("✓ Processes killed")
                    return True
                else:
                    self.logger.error(f"Failed to kill processes after multiple attempts")
                    return False
            else:
                self.logger.warning("User chose not to kill existing processes")
                return False
        else:
            # Port in use but no ollama processes found (might be systemd or other service)
            self.logger.error(f"Port {self.port} is in use but cause not identified")
            return False

    def install(self) -> bool:
        """Install Ollama."""
        self.logger.info(f"Installing Ollama (Port {self.port})...")
        
        # ALWAYS check for port conflicts, even if binary exists
        if not self._check_port_conflict():
            self.logger.error(f"Cannot proceed: Port {self.port} conflict unresolved")
            return False
        
        # Check if binary exists and its version
        code, version_out, _ = self.execute_remote("ollama --version 2>&1")
        
        should_install = False
        if code != 0:
            # Binary not found, need to install
            self.logger.info("Ollama not found, installing...")
            should_install = True
        else:
            # Check version
            version_str = version_out.strip()
            self.logger.info(f"Found existing Ollama: {version_str}")
            
            # Extract version number (e.g., "ollama version is 0.13.0" -> "0.13.0")
            try:
                import re
                match = re.search(r'(\d+\.\d+\.\d+)', version_str)
                if match:
                    current_version = match.group(1)
                    # Very basic version check - if major version is 0 and minor < 5, upgrade
                    major, minor, _ = map(int, current_version.split('.'))
                    if major == 0 and minor < 5:
                        self.logger.warning(f"Ollama {current_version} is outdated (< 0.5.0)")
                        print(f"\n⚠️  Ollama {current_version} detected - newer models may not work")
                        print(f"   Upgrade to latest version?")
                        response = input("   Upgrade Ollama? (Y/n): ").strip().lower()
                        if response == '' or response == 'y' or response == 'yes':
                            self.logger.info("User approved upgrade")
                            should_install = True
                        else:
                            self.logger.warning("User declined upgrade, continuing with old version")
            except Exception as e:
                self.logger.warning(f"Could not parse version: {e}, assuming need to install")
                should_install = True
        
        # Install or upgrade Ollama
        if should_install:
            if self.device.os_type.value == "linux":
                install_cmd = "curl -fsSL https://ollama.com/install.sh | sh"
                code, out, err = self.execute_remote(install_cmd, timeout=600)
                if code != 0:
                    self.logger.error(f"Ollama installation failed: {err}")
                    return False
                self.logger.info("✓ Ollama installed/upgraded successfully")
            elif self.device.os_type.value == "macos":
                # On macOS we usually download the app, but for CLI usage we can use brew or manual
                # For server usage, manual binary might be better or brew
                if not self.install_packages(["ollama"]):
                    return False
        
        return True
    
    def _detect_and_migrate_models(self) -> bool:
        """
        Detect existing Ollama model directories and migrate to shared storage.
        
        Returns:
            True if successful or no migration needed, False on error
        """
        if not self.models_dir:
            # No shared storage configured, skip migration
            return True
        
        # Common Ollama model locations
        possible_locations = [
            "~/.ollama/models",
            "/usr/share/ollama/.ollama/models",
            "/root/.ollama/models",
        ]
        
        # FIRST: Check if Ollama is currently running and get its OLLAMA_MODELS path
        code, ps_out, _ = self.execute_remote("ps aux | grep 'ollama serve' | grep -v grep")
        if code == 0 and ps_out.strip():
            self.logger.info("Found running Ollama process, checking its environment...")
            # Get the PID of the running Ollama
            try:
                pid_line = ps_out.strip().split('\n')[0]
                pid = pid_line.split()[1]
                # Try to read the process environment
                code, env_out, _ = self.execute_remote(f"sudo cat /proc/{pid}/environ 2>/dev/null | tr '\\0' '\\n' | grep OLLAMA_MODELS")
                if code == 0 and env_out.strip():
                    running_models_path = env_out.strip().split('=', 1)[1]
                    self.logger.info(f"Running Ollama is using: {running_models_path}")
                    # Add this as the FIRST location to check
                    possible_locations.insert(0, running_models_path)
            except Exception as e:
                self.logger.debug(f"Could not parse running Ollama environment: {e}")
        
        # SECOND: Check for custom OLLAMA_MODELS env var in user's environment
        code, out, _ = self.execute_remote("printenv OLLAMA_MODELS")
        if code == 0 and out.strip():
            custom_location = out.strip()
            if custom_location not in possible_locations:
                possible_locations.insert(0, custom_location)
                self.logger.info(f"Detected custom OLLAMA_MODELS in environment: {custom_location}")
        
        # Find existing models
        existing_models_dir = None
        for location in possible_locations:
            # Expand ~ if present
            expanded = location.replace("~", f"/home/{self.username}" if self.username != "root" else "/root")
            code, out, _ = self.execute_remote(f"test -d {expanded} && echo 'exists'")
            if code == 0 and "exists" in out:
                # Check if directory has models
                code, out, _ = self.execute_remote(f"ls -A {expanded} 2>/dev/null | head -1")
                if code == 0 and out.strip():
                    existing_models_dir = expanded
                    self.logger.info(f"Found existing models in: {existing_models_dir}")
                    break
        
        if not existing_models_dir:
            self.logger.info("No existing models found, will download to shared storage")
            return True
        
        # Check if models are already in the destination
        if existing_models_dir == self.models_dir:
            self.logger.info(f"✓ Models already in shared storage: {self.models_dir}")
            return True
        
        # Check model count and size
        code, count_out, _ = self.execute_remote(f"find {existing_models_dir} -type f | wc -l")
        code2, size_out, _ = self.execute_remote(f"du -sh {existing_models_dir} 2>/dev/null")
        
        model_count = count_out.strip() if code == 0 else "unknown"
        model_size = size_out.split()[0] if code2 == 0 else "unknown"
        
        # Prompt user for migration
        print(f"\n📦 Existing Ollama Models Detected")
        print(f"   Location: {existing_models_dir}")
        print(f"   Files: {model_count}")
        print(f"   Size: {model_size}")
        print(f"\n   Migrate to shared storage ({self.models_dir})?")
        print(f"   This will avoid re-downloading models.")
        response = input("   Migrate models? (Y/n): ").strip().lower()
        
        if response == '' or response == 'y' or response == 'yes':
            self.logger.info(f"Migrating models from {existing_models_dir} to {self.models_dir}...")
            
            # Ensure shared directory exists
            self.execute_remote(f"sudo mkdir -p {self.models_dir}", timeout=30)
            
            # Check if source and destination are on the same filesystem
            code_src, df_src, _ = self.execute_remote(f"df {existing_models_dir} | tail -1 | awk '{{print $1}}'")
            code_dst, df_dst, _ = self.execute_remote(f"df {self.models_dir} | tail -1 | awk '{{print $1}}'")
            
            same_filesystem = (code_src == 0 and code_dst == 0 and df_src.strip() == df_dst.strip())
            
            if same_filesystem:
                print(f"\n💡 Source and destination are on the same filesystem.")
                print(f"   Move instead of copying? (Much faster!)")
                move_response = input("   Move models? (Y/n): ").strip().lower()
                
                if move_response == '' or move_response == 'y' or move_response == 'yes':
                    # Remove empty dest directory and move
                    self.execute_remote(f"sudo rmdir {self.models_dir} 2>/dev/null || true", timeout=30)
                    code, out, err = self.execute_remote(f"sudo mv {existing_models_dir} {self.models_dir}", timeout=300)
                    if code != 0:
                        self.logger.error(f"Failed to move models: {err}")
                        return False
                    else:
                        self.logger.info("✓ Models moved successfully")
                        return True
            
            # Copy models (use rsync if available, otherwise cp)
            # For 191GB, this could take hours - set timeout to 6 hours
            copy_timeout = 21600  # 6 hours
            
            print(f"\n⏳ Copying {model_size} of models (this may take a while)...")
            self.logger.info(f"Starting copy with {copy_timeout}s timeout...")
            
            code, _, _ = self.execute_remote("which rsync")
            if code == 0:
                migrate_cmd = f"sudo rsync -av {existing_models_dir}/ {self.models_dir}/"
            else:
                migrate_cmd = f"sudo cp -r {existing_models_dir}/* {self.models_dir}/"
            
            code, out, err = self.execute_remote(migrate_cmd, timeout=copy_timeout)
            if code != 0:
                self.logger.error(f"Failed to migrate models: {err}")
                print(f"\n⚠️  Migration failed. Continue anyway? (y/N): ", end='')
                response2 = input().strip().lower()
                if response2 != 'y' and response2 != 'yes':
                    return False
            else:
                self.logger.info("✓ Models migrated successfully")
                # Set proper permissions
                self.execute_remote(f"sudo chmod -R 755 {self.models_dir}", timeout=60)
        else:
            self.logger.info("User chose not to migrate models")
        
        return True

    def configure(self) -> bool:
        """Configure Ollama service."""
        self.logger.info(f"Configuring Ollama on port {self.port}...")
        
        # Detect and migrate existing models before configuration
        if not self._detect_and_migrate_models():
            self.logger.error("Model migration failed or was cancelled")
            return False
        
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
            # Use the user's home directory for logs, not /root
            if self.username == "root":
                logs_dir = "/root/NoSlop/logs"
            else:
                logs_dir = f"/home/{self.username}/NoSlop/logs"
            
            # Ensure logs directory exists with proper permissions
            # Use sudo to create directory and then set ownership
            self.execute_remote(f"sudo mkdir -p {logs_dir}", timeout=30)
            if self.username != "root":
                self.execute_remote(f"sudo chown -R {self.username}:{self.username} /home/{self.username}/NoSlop", timeout=30)
            
            # Create timestamped log file path
            from datetime import datetime
            timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
            service_name_log = "ollama" if not self.is_secondary else f"ollama-{self.port}"
            log_file = f"{logs_dir}/{service_name_log}_{timestamp}.log"
            
            
            # Create the log file with proper permissions before systemd tries to use it
            self.execute_remote(f"sudo touch {log_file}", timeout=30)
            if self.username != "root":
                self.execute_remote(f"sudo chown {self.username}:{self.username} {log_file}", timeout=30)
            
            
            # Set exec command
            exec_cmd = "/usr/local/bin/ollama serve"
            service_content = service_content.replace("{{EXEC_START}}", exec_cmd)
            
            # Environment variables - systemd requires separate Environment= lines
            env_lines = [f"Environment=OLLAMA_HOST=0.0.0.0:{self.port}"]
            
            # Add shared models directory if specified
            if self.models_dir:
                self.logger.info(f"Using shared models directory: {self.models_dir}")
                env_lines.append(f"Environment=OLLAMA_MODELS={self.models_dir}")
                # Ensure directory exists
                self.execute_remote(f"sudo mkdir -p {self.models_dir}", timeout=30)
                self.execute_remote(f"sudo chmod 777 {self.models_dir}", timeout=30)
            
            # Join with newlines for proper systemd format
            env_vars = "\n".join(env_lines)
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
        
        # Final port conflict check right before starting
        # (manual process might have started after initial check)
        if not self._check_port_conflict():
            self.logger.error(f"Port {self.port} conflict detected before service start")
            return False
        
        if self.device.os_type.value == "linux":
            code, _, err = self.execute_remote(f"sudo systemctl daemon-reload && sudo systemctl enable {service_name} && sudo systemctl start {service_name}")
            if code != 0:
                self.logger.error(f"Failed to start {service_name}: {err}")
                return False
                
        # Give it a moment to start
        time.sleep(3)
        return True

    def verify(self) -> bool:
        """Verify Ollama is running and pull models."""
        self.logger.info(f"Verifying Ollama on port {self.port}...")
        
        service_name = "ollama" if not self.is_secondary else f"ollama-{self.port}"
        
        # First, check if systemd service is running
        if self.device.os_type.value == "linux":
            code, out, err = self.execute_remote(f"systemctl is-active {service_name}")
            if code != 0:
                self.logger.error(f"Service {service_name} is not running: {err}")
                # Try to check status for more details
                code2, status_out, _ = self.execute_remote(f"systemctl status {service_name}")
                if code2 == 0:
                    self.logger.info(f"Service status: {status_out[:500]}")
                return False
        
        # Check API with retries
        max_retries = 3
        for attempt in range(max_retries):
            cmd = f"curl -s http://localhost:{self.port}/api/tags"
            code, out, err = self.execute_remote(cmd, timeout=10)
            
            if code == 0:
                self.logger.info("✓ Ollama API is accessible")
                break
            else:
                if attempt < max_retries - 1:
                    self.logger.warning(f"Ollama API check attempt {attempt + 1}/{max_retries} failed, retrying in 3s...")
                    time.sleep(3)
                else:
                    # Last attempt failed
                    self.logger.warning(f"Ollama API check failed after {max_retries} attempts: {err}")
                    # Check if curl is installed
                    curl_code, _, _ = self.execute_remote("which curl")
                    if curl_code != 0:
                        self.logger.warning("curl not found - skipping API verification (service may still be running)")
                        # If service is active via systemctl, we can proceed
                        if self.device.os_type.value == "linux":
                            code, _, _ = self.execute_remote(f"systemctl is-active {service_name}")
                            if code == 0:
                                self.logger.info("✓ Service is active (verified via systemctl)")
                                # Skip model pulling if we can't verify API
                                return True
                    return False
        
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
