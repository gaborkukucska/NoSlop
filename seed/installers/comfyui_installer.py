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
    
    def __init__(self, device, ssh_manager, port: int = 8188, gpu_index: int = 0, username: str = "root", password: str = None):
        super().__init__(device, ssh_manager, "comfyui", username=username, password=password)
        self.port = port
        self.gpu_index = gpu_index
        self.install_dir = f"/opt/ComfyUI_{port}" if port != 8188 else "/opt/ComfyUI"
        self.venv_dir = f"{self.install_dir}/venv"
        self.is_secondary = port != 8188

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
        
        # Install system dependencies
        self.install_packages(["git", "python3", "python3-venv", "python3-pip"])
        
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
        
        if self.device.os_type.value == "linux":
            service_name = "comfyui" if not self.is_secondary else f"comfyui-{self.port}"
            
            # Read template
            with open("seed/templates/systemd_template.service", "r") as f:
                template = f.read()
            
            # Command to run ComfyUI
            # We need to pass port and GPU args
            exec_cmd = f"{self.venv_dir}/bin/python main.py --port {self.port} --listen"
            
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
            code, _, err = self.execute_remote(f"sudo systemctl enable {service_name} && sudo systemctl start {service_name}")
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
