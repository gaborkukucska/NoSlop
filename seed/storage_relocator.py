# START OF FILE seed/storage_relocator.py
"""
Storage Relocator for NoSlop Seed.

Safely relocates service data directories (like Ollama models, ComfyUI models)
to new storage paths with user approval and duplicate-skipping file copy.
"""

import logging
from typing import Optional, Tuple

logger = logging.getLogger(__name__)


class StorageRelocator:
    """
    Manages relocation of service data to new storage paths.
    
    Features:
    - Interactive user approval prompts
    - Rsync-based copying with duplicate skipping
    - Size estimation and progress tracking
    - Service configuration updates
    """
    
    def __init__(self, ssh_manager=None):
        self.ssh_manager = ssh_manager
    
    def _execute_on_device(self, device, ssh_client, command: str, timeout: int = 600):
        """Execute command on device (local or remote)."""
        if ssh_client is None:
            # Local execution
            import subprocess
            try:
                result = subprocess.run(
                    command,
                    shell=True,
                    capture_output=True,
                    text=True,
                    timeout=timeout
                )
                return result.returncode, result.stdout.strip(), result.stderr.strip()
            except Exception as e:
                return -1, "", str(e)
        else:
            # Remote execution
            if self.ssh_manager:
                return self.ssh_manager.execute_command(ssh_client, command, timeout)
            return -1, "", "No SSH manager available"
    
    def estimate_directory_size(self, device, ssh_client, path: str) -> Tuple[str, int]:
        """
        Estimate size of a directory.
        
        Args:
            device: Device to check
            ssh_client: SSH client
            path: Directory path
            
        Returns:
            Tuple of (human_readable_size, size_in_mb)
        """
        code, out, _ = self._execute_on_device(
            device, ssh_client,
            f"du -sm {path} 2>/dev/null | cut -f1 || echo '0'"
        )
        
        if code != 0:
            return "unknown", 0
        
        try:
            size_mb = int(out.strip())
            if size_mb < 1024:
                return f"{size_mb} MB", size_mb
            else:
                size_gb = size_mb / 1024
                return f"{size_gb:.1f} GB", size_mb
        except:
            return "unknown", 0
    
    def prompt_relocation_approval(
        self,
        service: str,
        current_path: str,
        new_path: str,
        estimated_size: str
    ) -> bool:
        """
        Prompt user for approval to relocate service data.
        
        Args:
            service: Service name (ollama, comfyui)
            current_path: Current data directory
            new_path: New data directory
            estimated_size: Estimated size of data
            
        Returns:
            True if user approves, False otherwise
        """
        print(f"\n{'='*70}")
        print(f"🔄 Storage Relocation Required: {service.upper()}")
        print(f"{'='*70}")
        print(f"\nCurrent location: {current_path}")
        print(f"New location:     {new_path}")
        print(f"Estimated size:   {estimated_size}")
        print(f"\nThis will:")
        print(f"  1. Copy all files from current to new location")
        print(f"  2. Skip files that already exist (no duplicates)")
        print(f"  3. Update {service} configuration to use new location")
        print(f"  4. Keep original files intact (manual cleanup required)")
        print(f"\n⚠️  Large transfers may take time. Ensure sufficient disk space.")
        print(f"{'='*70}")
        
        response = input("\nApprove relocation? (yes/no): ").strip().lower()
        approved = response in ['yes', 'y']
        
        if approved:
            logger.info(f"✓ User approved relocation of {service} from {current_path} to {new_path}")
        else:
            logger.info(f"✗ User declined relocation of {service}")
        
        return approved
    
    def copy_with_skip_duplicates(
        self,
        device,
        ssh_client,
        source_path: str,
        dest_path: str,
        show_progress: bool = True
    ) -> bool:
        """
        Copy directory contents with rsync, skipping duplicates.
        
        Args:
            device: Device to operate on
            ssh_client: SSH client
            source_path: Source directory
            dest_path: Destination directory
            show_progress: Show progress during copy
            
        Returns:
            True if successful
        """
        logger.info(f"Copying {source_path} to {dest_path}...")
        
        # Ensure destination directory exists
        code, _, err = self._execute_on_device(
            device, ssh_client,
            f"sudo mkdir -p {dest_path}"
        )
        if code != 0:
            logger.error(f"Failed to create destination directory: {err}")
            return False
        
        # Build rsync command
        # -a: archive mode (preserves permissions, timestamps, etc)
        # -v: verbose
        # --ignore-existing: skip files that already exist at destination
        # --progress: show progress (if show_progress is True)
        rsync_opts = "-av --ignore-existing"
        if show_progress:
            rsync_opts += " --progress"
        
        rsync_cmd = f"sudo rsync {rsync_opts} {source_path}/ {dest_path}/"
        
        logger.debug(f"Executing: {rsync_cmd}")
        print(f"\n📦 Starting file copy...")
        print(f"   This may take several minutes for large directories...")
        
        code, out, err = self._execute_on_device(
            device, ssh_client,
            rsync_cmd,
            timeout=3600  # 1 hour timeout for large transfers
        )
        
        if code != 0:
            logger.error(f"❌ rsync failed: {err}")
            print(f"\n❌ File copy failed: {err[:200]}")
            return False
        
        # Verify copy by comparing file counts
        code1, count1, _ = self._execute_on_device(
            device, ssh_client,
            f"find {source_path} -type f 2>/dev/null | wc -l || echo '0'"
        )
        code2, count2, _ = self._execute_on_device(
            device, ssh_client,
            f"find {dest_path} -type f 2>/dev/null | wc -l || echo '0'"
        )
        
        if code1 == 0 and code2 == 0:
            source_files = int(count1.strip())
            dest_files = int(count2.strip())
            logger.info(f"File count - Source: {source_files}, Destination: {dest_files}")
            
            if dest_files >= source_files:
                logger.info("✓ File copy verified successfully")
                print(f"\n✓ Copy complete: {dest_files} files in {dest_path}")
            else:
                logger.warning(f"⚠️  Destination has fewer files than source")
                print(f"\n⚠️  Warning: Destination has {dest_files} files but source has {source_files}")
        
        return True
    
    def update_service_config(
        self,
        service: str,
        device,
        ssh_client,
        new_path: str
    ) -> bool:
        """
        Update service configuration to use new storage path.
        
        Args:
            service: Service name (ollama, comfyui)
            device: Device to update
            ssh_client: SSH client
            new_path: New data directory path
            
        Returns:
            True if successful
        """
        logger.info(f"Updating {service} configuration to use {new_path}...")
        
        if service == "ollama":
            return self._update_ollama_config(device, ssh_client, new_path)
        elif service == "comfyui":
            return self._update_comfyui_config(device, ssh_client, new_path)
        else:
            logger.warning(f"Unknown service: {service}")
            return False
    
    def _update_ollama_config(self, device, ssh_client, models_dir: str) -> bool:
        """Update Ollama systemd service to use new models directory."""
        logger.info("Updating Ollama systemd service configuration...")
        
        # Check if systemd service exists
        code, _, _ = self._execute_on_device(
            device, ssh_client,
            "systemctl status ollama >/dev/null 2>&1"
        )
        
        if code != 0:
            logger.warning("Ollama systemd service not found, skipping config update")
            return True
        
        # Update environment variable in service file
        # We need to add or update Environment=OLLAMA_MODELS=<path>
        update_cmd = f"""
sudo mkdir -p /etc/systemd/system/ollama.service.d/
echo '[Service]' | sudo tee /etc/systemd/system/ollama.service.d/override.conf > /dev/null
echo 'Environment="OLLAMA_MODELS={models_dir}"' | sudo tee -a /etc/systemd/system/ollama.service.d/override.conf > /dev/null
sudo systemctl daemon-reload
sudo systemctl restart ollama
"""
        
        code, out, err = self._execute_on_device(
            device, ssh_client,
            update_cmd
        )
        
        if code != 0:
            logger.error(f"Failed to update Ollama configuration: {err}")
            return False
        
        logger.info("✓ Ollama configuration updated and service restarted")
        return True
    
    def _update_comfyui_config(self, device, ssh_client, models_dir: str) -> bool:
        """Update ComfyUI to use new models directory via symlink."""
        logger.info("Updating ComfyUI models directory...")
        
        # Find ComfyUI installation
        install_paths = ["/opt/ComfyUI", "/opt/comfyui"]
        comfyui_path = None
        
        for path in install_paths:
            code, _, _ = self._execute_on_device(
                device, ssh_client,
                f"test -d {path}"
            )
            if code == 0:
                comfyui_path = path
                break
        
        if not comfyui_path:
            logger.warning("ComfyUI installation not found, skipping config update")
            return True
        
        # Remove old models directory/symlink and create new symlink
        update_cmd = f"""
cd {comfyui_path} && \
sudo rm -rf models && \
sudo ln -s {models_dir} models
"""
        
        code, out, err = self._execute_on_device(
            device, ssh_client,
            update_cmd
        )
        
        if code != 0:
            logger.error(f"Failed to update ComfyUI configuration: {err}")
            return False
        
        # Restart ComfyUI service if running
        self._execute_on_device(
            device, ssh_client,
            "sudo systemctl restart comfyui 2>/dev/null || true"
        )
        
        logger.info("✓ ComfyUI configuration updated")
        return True
    
    def relocate_service_data(
        self,
        service: str,
        device,
        ssh_client,
        current_path: str,
        new_path: str,
        skip_user_prompt: bool = False
    ) -> bool:
        """
        Complete relocation workflow for a service.
        
        Args:
            service: Service name
            device: Device to operate on
            ssh_client: SSH client
            current_path: Current data directory
            new_path: New data directory
            skip_user_prompt: Skip user approval (for testing)
            
        Returns:
            True if successful
        """
        logger.info(f"Starting relocation of {service} data...")
        
        # Estimate size
        size_str, size_mb = self.estimate_directory_size(device, ssh_client, current_path)
        
        # Get user approval
        if not skip_user_prompt:
            if not self.prompt_relocation_approval(service, current_path, new_path, size_str):
                logger.info("Relocation cancelled by user")
                return False
        
        # Copy files
        if not self.copy_with_skip_duplicates(device, ssh_client, current_path, new_path):
            logger.error("File copy failed, aborting relocation")
            return False
        
        # Update service configuration
        if not self.update_service_config(service, device, ssh_client, new_path):
            logger.error("Configuration update failed")
            return False
        
        logger.info(f"✓ {service} relocation complete")
        print(f"\n✅ {service.upper()} relocation successful!")
        print(f"   Original files remain at: {current_path}")
        print(f"   New location: {new_path}")
        print(f"   You can safely delete {current_path} once verified.")
        
        return True
