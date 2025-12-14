# START OF FILE seed/installers/comfyui_installer.py
"""
ComfyUI Installer for NoSlop Seed.

Installs and configures ComfyUI on COMPUTE nodes.
Supports GPU detection (CUDA/ROCm/Metal) and multi-instance deployment.
"""

import time
import os
from typing import List, Optional

from seed.installers.base_installer import BaseInstaller
from seed.models import GPUVendor

class ComfyUIInstaller(BaseInstaller):
    """
    Installs and configures ComfyUI with GPU support.
    """
    
    def __init__(self, device, ssh_manager, port: int = 8188, gpu_index: int = 0, 
                 username: str = "root", password: str = None, 
                 models_dir: Optional[str] = None, custom_nodes_dir: Optional[str] = None,
                 workflows_dir: Optional[str] = None, logs_dir: Optional[str] = None):
        super().__init__(device, ssh_manager, "comfyui", username=username, password=password)
        self.port = port
        self.gpu_index = gpu_index
        self.install_dir = f"/opt/ComfyUI_{port}" if port != 8188 else "/opt/ComfyUI"
        self.venv_dir = f"{self.install_dir}/venv"
        self.is_secondary = port != 8188
        self.models_dir = models_dir  # Shared models directory (optional)
        self.custom_nodes_dir = custom_nodes_dir  # Shared custom nodes directory (optional)
        self.workflows_dir = workflows_dir # Shared workflows directory (optional)
        self.logs_dir = logs_dir # Shared logs directory (optional)

    def check_installed(self) -> bool:
        """Check if ComfyUI is installed and running on the specific port."""
        # Check directory
        code, _, _ = self.execute_remote(f"test -d {self.install_dir}")
        if code != 0:
            return False
            
        # Check if service is running
        code, _, _ = self.execute_remote(f"netstat -tuln | grep :{self.port}")
        return code == 0

    def install(self) -> bool:
        """Install ComfyUI and dependencies."""
        self.logger.info(f"Installing ComfyUI to {self.install_dir}...")
        
        # Check if already installed (files exist even if service is stopped)
        # We check for main.py and venv
        code, _, _ = self.execute_remote(f"test -f {self.install_dir}/main.py && test -d {self.venv_dir}")
        if code == 0:
            self.logger.info(f"✓ Found existing ComfyUI installation at {self.install_dir}, skipping install.")
            return True
        
        # Install system dependencies
        self.install_packages_with_retry(["git", "python3", "python3-venv", "python3-pip"])
        
        # Create directory with sudo
        self.execute_remote(f"sudo mkdir -p {self.install_dir}")
        
        # Change ownership to the user so we can clone without sudo
        # We use the username provided in __init__
        self.logger.info(f"Changing ownership of {self.install_dir} to {self.username}...")
        self.execute_remote(f"sudo chown -R {self.username}:{self.username} {self.install_dir}")
        
        # Clone repository (as user)
        self.logger.info("Cloning ComfyUI repository...")
        # Explicitly run as user to ensure ownership
        code, _, err = self.execute_remote(f"sudo -u {self.username} git clone https://github.com/comfyanonymous/ComfyUI.git {self.install_dir}")
        if code != 0 and "already exists" not in err:
            self.logger.error(f"Failed to clone ComfyUI: {err}")
            return False
            
        # Create venv (as user)
        self.logger.info("Creating virtual environment...")
        # Explicitly run as user to ensure ownership
        code, _, err = self.execute_remote(f"sudo -u {self.username} python3 -m venv {self.venv_dir}")
        if code != 0:
            self.logger.error(f"Failed to create venv: {err}")
            return False
            
        # Install dependencies
        return self._install_python_deps()

    def _install_python_deps(self) -> bool:
        """Install Python dependencies with correct PyTorch version."""
        self.logger.info("Installing Python dependencies...")
        
        # We don't use sudo for pip inside venv if we own the venv
        # But to be consistent and ensure correct permissions/environment, we use sudo -u
        pip_cmd = f"sudo -u {self.username} {self.venv_dir}/bin/pip"
        
        # Upgrade pip
        self.execute_remote(f"{pip_cmd} install --upgrade pip")
        
        # Determine PyTorch command based on GPU
        torch_cmd = ""
        if self.device.gpu_vendor == GPUVendor.NVIDIA:
            self.logger.info("Detected NVIDIA GPU. Installing PyTorch with CUDA...")
            torch_cmd = f"{pip_cmd} install torch torchvision torchaudio --extra-index-url https://download.pytorch.org/whl/cu121"
        elif self.device.gpu_vendor == GPUVendor.AMD:
            self.logger.info("Detected AMD GPU. Installing PyTorch with ROCm...")
            torch_cmd = f"{pip_cmd} install torch torchvision torchaudio --index-url https://download.pytorch.org/whl/rocm6.0"
        elif self.device.gpu_vendor == GPUVendor.APPLE:
            self.logger.info("Detected Apple Silicon. Installing PyTorch with MPS support...")
            torch_cmd = f"{pip_cmd} install --pre torch torchvision torchaudio --extra-index-url https://download.pytorch.org/whl/nightly/cpu"
        else:
            self.logger.info("No dedicated GPU detected. Installing PyTorch for CPU...")
            torch_cmd = f"{pip_cmd} install torch torchvision torchaudio --extra-index-url https://download.pytorch.org/whl/cpu"
            
        # Install PyTorch
        code, _, err = self.execute_remote(torch_cmd, timeout=900)
        if code != 0:
            self.logger.error(f"Failed to install PyTorch: {err}")
            return False
            
        # Install ComfyUI requirements
        self.logger.info("Installing ComfyUI requirements...")
        code, _, err = self.execute_remote(f"{pip_cmd} install -r {self.install_dir}/requirements.txt", timeout=600)
        if code != 0:
            self.logger.error(f"Failed to install requirements: {err}")
            return False
            
        return True

    def configure(self) -> bool:
        """Configure ComfyUI service."""
        self.logger.info(f"Configuring ComfyUI on port {self.port}...")
        
        # Setup shared storage if specified
        if self.models_dir or self.custom_nodes_dir:
            self.logger.info("Setting up shared storage...")
            
            if self.models_dir:
                self.logger.info(f"Linking models directory to {self.models_dir}")
                # Ensure shared directory exists
                self.execute_remote(f"sudo mkdir -p {self.models_dir}")
                self.execute_remote(f"sudo chmod 755 {self.models_dir}")
                
                # Create subdirectories for different model types
                for subdir in ["checkpoints", "vae", "loras", "embeddings", "controlnet", "upscale_models"]:
                    self.execute_remote(f"sudo mkdir -p {self.models_dir}/{subdir}")
                
                # Check if local models directory exists and has content
                local_models = f"{self.install_dir}/models"
                code, local_files, _ = self.execute_remote(f"[ -d {local_models} ] && find {local_models} -type f | head -1")
                
                if code == 0 and local_files.strip():
                    # Local models exist - check if shared directory has content
                    code_shared, shared_files, _ = self.execute_remote(f"find {self.models_dir} -type f | head -1")
                    
                    if code_shared != 0 or not shared_files.strip():
                        # Shared directory is empty - migrate local models first
                        
                        # Check size threshold (ignore if < 100MB)
                        code_size, size_mb_out, _ = self.execute_remote(f"du -sm {local_models} | awk '{{print $1}}'")
                        size_mb = int(size_mb_out.strip()) if code_size == 0 and size_mb_out.strip().isdigit() else 0
                        
                        if size_mb < 100:
                             self.logger.info(f"Local models directory is too small ({size_mb} MB), skipping migration.")
                             # Safe to remove local and link
                        else:
                            self.logger.info(f"Migrating local models to shared storage...")
                            print(f"\n📦 Migrating ComfyUI models to shared storage...")
                            print(f"   From: {local_models}")
                        print(f"   To: {self.models_dir}")
                        
                        # Use rsync with --ignore-existing to preserve any existing files
                        code_rsync, _, _ = self.execute_remote("which rsync")
                        if code_rsync == 0:
                            migrate_cmd = f"sudo rsync -av --ignore-existing {local_models}/ {self.models_dir}/"
                            print(f"   Using rsync (won't overwrite existing files)...")
                        else:
                            migrate_cmd = f"sudo cp -rn {local_models}/* {self.models_dir}/"
                            print(f"   Using cp (won't overwrite existing files)...")
                        
                        code_migrate, _, err = self.execute_remote(migrate_cmd, timeout=3600)
                        if code_migrate != 0:
                            self.logger.warning(f"Migration had issues: {err}")
                            print(f"\n⚠️  Migration completed with warnings")
                        else:
                            self.logger.info("✓ Models migrated successfully")
                            print(f"✓ Models migrated successfully")
                
                # NOW safe to remove local directory and create symlink
                # (Either it was empty, or we've migrated its contents)
                self.execute_remote(f"rm -rf {local_models}")
                
                # Create symlink to shared models
                code, _, err = self.execute_remote(f"ln -s {self.models_dir} {local_models}")
                if code != 0:
                    self.logger.error(f"Failed to create models symlink: {err}")
                    return False
                
                self.logger.info("✓ Models directory linked to shared storage")
            
            if self.custom_nodes_dir:
                self.logger.info(f"Linking custom_nodes directory to {self.custom_nodes_dir}")
                # Ensure shared directory exists
                self.execute_remote(f"sudo mkdir -p {self.custom_nodes_dir}")
                self.execute_remote(f"sudo chmod 755 {self.custom_nodes_dir}")
                
                # Check if local custom_nodes directory exists and has content
                local_custom_nodes = f"{self.install_dir}/custom_nodes"
                code, local_files, _ = self.execute_remote(f"[ -d {local_custom_nodes} ] && find {local_custom_nodes} -type f | head -1")
                
                if code == 0 and local_files.strip():
                    # Local custom_nodes exist - check if shared directory has content
                    code_shared, shared_files, _ = self.execute_remote(f"find {self.custom_nodes_dir} -type f | head -1")
                    
                    if code_shared != 0 or not shared_files.strip():
                        # Shared directory is empty - migrate local custom_nodes first
                        
                        # Check size threshold for custom nodes (ignore if < 10MB as they are code)
                        code_size, size_mb_out, _ = self.execute_remote(f"du -sm {local_custom_nodes} | awk '{{print $1}}'")
                        size_mb = int(size_mb_out.strip()) if code_size == 0 and size_mb_out.strip().isdigit() else 0
                        
                        if size_mb < 10:
                             self.logger.info(f"Local custom_nodes directory is small ({size_mb} MB), skipping migration.")
                        else:
                            self.logger.info(f"Migrating local custom_nodes to shared storage...")
                            print(f"\n📦 Migrating ComfyUI custom nodes to shared storage...")
                        
                        # Use rsync with --ignore-existing
                        code_rsync, _, _ = self.execute_remote("which rsync")
                        if code_rsync == 0:
                            migrate_cmd = f"sudo rsync -av --ignore-existing {local_custom_nodes}/ {self.custom_nodes_dir}/"
                        else:
                            migrate_cmd = f"sudo cp -rn {local_custom_nodes}/* {self.custom_nodes_dir}/"
                        
                        code_migrate, _, err = self.execute_remote(migrate_cmd, timeout=1800)
                        if code_migrate != 0:
                            self.logger.warning(f"Migration had issues: {err}")
                        else:
                            self.logger.info("✓ Custom nodes migrated successfully")
                            print(f"✓ Custom nodes migrated successfully")
                
                # NOW safe to remove local directory and create symlink
                self.execute_remote(f"rm -rf {local_custom_nodes}")
                
                # Create symlink to shared custom_nodes
                code, _, err = self.execute_remote(f"ln -s {self.custom_nodes_dir} {local_custom_nodes}")
                if code != 0:
                    self.logger.error(f"Failed to create custom_nodes symlink: {err}")
                    return False
                
                self.logger.info("✓ Custom nodes directory linked to shared storage")
            
            if self.workflows_dir:
                self.logger.info(f"Setting up shared workflows directory: {self.workflows_dir}")
                self.execute_remote(f"sudo mkdir -p {self.workflows_dir}")
                self.execute_remote(f"sudo chmod 777 {self.workflows_dir}") # Allow users to write
                
                
                # Check if workflows already exist
                code, out, _ = self.execute_remote(f"ls -1 {self.workflows_dir}/*.json 2>/dev/null | head -1")
                has_workflows = code == 0 and out.strip()
                
                if has_workflows:
                     self.logger.info(f"✓ Workflows directory already populated at {self.workflows_dir}")
                else:
                    # Prompt user for local workflows to import
                    print(f"\n" + "="*70)
                    print(f"📄 ComfyUI Workflows")
                    print(f"="*70)
                    print(f"Do you have a folder of existing ComfyUI workflows (.json)?")
                    print(f"The installer can import them to shared storage for the Agent to use.")
                    
                    workflows_src = input("Path to workflows folder [Leave empty to skip]: ").strip()
                    
                    if workflows_src:
                        # Validate local path
                        import os
                        if os.path.isdir(workflows_src):
                            print(f"   ✓ Found directory: {workflows_src}")
                            print(f"   Importing workflows...")
                            
                            if self.transfer_directory(workflows_src, self.workflows_dir):
                                 self.logger.info(f"Workflows transferred to {self.workflows_dir}")
                                 print(f"   ✓ Workflows imported successfully")
                                 
                                 # Fix permissions
                                 self.execute_remote(f"sudo chown -R {self.username}:{self.username} {self.workflows_dir}")
                            else:
                                 self.logger.error("Failed to transfer workflows")
                                 print(f"   ❌ Failed to import workflows")
                        else:
                            print(f"   ⚠️  Directory not found: {workflows_src}")
                            self.logger.warning(f"User provided invalid workflows path: {workflows_src}")
                    else:
                        self.logger.info("No workflows imported")
        
        if self.device.os_type.value == "linux":
            service_name = "comfyui" if not self.is_secondary else f"comfyui-{self.port}"
            
            # Read template
            with open("seed/templates/systemd_template.service", "r") as f:
                template = f.read()
            
            # Command to run ComfyUI
            # We need to pass port and GPU args
            # Determine logs directory
            # Use shared logs dir if configured, otherwise use home
            if self.logs_dir:
                logs_dir = self.logs_dir
            elif self.username == "root":
                logs_dir = "/root/NoSlop/logs"
            else:
                logs_dir = f"/home/{self.username}/NoSlop/logs"
            
            # Command to run ComfyUI
            exec_cmd = f"{self.venv_dir}/bin/python main.py --port {self.port} --listen"
            
            # Ensure logs directory exists
            self.execute_remote(f"sudo mkdir -p {logs_dir}")
            self.execute_remote(f"sudo chown {self.username}:{self.username} {logs_dir}")
            
            # Create timestamped log file path
            from datetime import datetime
            timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
            service_name_log = "comfyui" if not self.is_secondary else f"comfyui-{self.port}"
            log_file = f"{logs_dir}/{service_name_log}_{timestamp}.log"
            
            # Environment variables
            env_vars = ""
            if self.device.gpu_vendor == GPUVendor.NVIDIA:
                env_vars = f"CUDA_VISIBLE_DEVICES={self.gpu_index}"
            
            # Fill template
            service_content = template.replace("{{SERVICE_NAME}}", service_name)
            service_content = service_content.replace("{{USER}}", self.username) # Run as the user
            service_content = service_content.replace("{{WORKING_DIR}}", self.install_dir)
            service_content = service_content.replace("{{EXEC_START}}", exec_cmd)
            service_content = service_content.replace("{{ENVIRONMENT_VARS}}", env_vars)
            
            # Add StandardOutput and StandardError directives for logging
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
                remote_path = f"/etc/systemd/system/{service_name}.service"
                if not self.transfer_file(tmp_path, f"/tmp/{service_name}.service"):
                    return False
                
                self.execute_remote(f"sudo mv /tmp/{service_name}.service {remote_path}")
                self.execute_remote("sudo systemctl daemon-reload")
                
            finally:
                os.unlink(tmp_path)
                
        return True

    def start(self) -> bool:
        """Start ComfyUI service."""
        self.logger.info(f"Starting ComfyUI on port {self.port}...")
        
        service_name = "comfyui" if not self.is_secondary else f"comfyui-{self.port}"
        
        if self.device.os_type.value == "linux":
            code, _, err = self.execute_remote(f"sudo systemctl enable {service_name} && sudo systemctl restart {service_name}")
            if code != 0:
                self.logger.error(f"Failed to start {service_name}: {err}")
                return False
                
        # Wait for service to be ready
        time.sleep(10)
        return True

    def verify(self) -> bool:
        """Verify ComfyUI is running."""
        self.logger.info(f"Verifying ComfyUI on port {self.port}...")
        
        # Check API
        cmd = f"curl -s http://localhost:{self.port}/system_stats"
        code, out, err = self.execute_remote(cmd)
        
        if code != 0:
            self.logger.error(f"ComfyUI API check failed: {err}")
            return False
            
        self.logger.info("✓ ComfyUI API is accessible")
        return True

    def rollback(self):
        """Rollback installation."""
        service_name = "comfyui" if not self.is_secondary else f"comfyui-{self.port}"
        if self.device.os_type.value == "linux":
            self.execute_remote(f"sudo systemctl stop {service_name}")
            self.execute_remote(f"sudo systemctl disable {service_name}")
            self.execute_remote(f"sudo rm /etc/systemd/system/{service_name}.service")
            self.execute_remote("sudo systemctl daemon-reload")
        
        # Remove directory
        self.execute_remote(f"sudo rm -rf {self.install_dir}")
