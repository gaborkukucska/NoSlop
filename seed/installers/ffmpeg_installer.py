# START OF FILE seed/installers/ffmpeg_installer.py
"""
FFmpeg Installer for NoSlop Seed.

Installs FFmpeg and OpenCV dependencies on COMPUTE nodes.
"""

from seed.installers.base_installer import BaseInstaller

class FFmpegInstaller(BaseInstaller):
    """
    Installs FFmpeg and OpenCV dependencies.
    """
    
    def __init__(self, device, ssh_manager, username="root", password=None):
        super().__init__(device, ssh_manager, "ffmpeg", username=username, password=password)

    def check_installed(self) -> bool:
        """Check if FFmpeg is installed."""
        code, _, _ = self.execute_remote("which ffmpeg")
        return code == 0

    def install(self) -> bool:
        """Install FFmpeg and OpenCV dependencies."""
        self.logger.info("Installing FFmpeg and OpenCV dependencies...")
        
        pm = self.get_package_manager()
        
        packages = []
        if pm == "apt":
            packages = ["ffmpeg", "libsm6", "libxext6"] # libsm6/libxext6 for opencv
        elif pm == "brew":
            packages = ["ffmpeg", "opencv"]
        elif pm == "yum":
            packages = ["ffmpeg", "opencv"] # Might need rpmfusion repo
        else:
            self.logger.error(f"Unsupported package manager: {pm}")
            return False
            
        return self.install_packages(packages)

    def configure(self) -> bool:
        """Configure FFmpeg (nothing to do usually)."""
        return True

    def start(self) -> bool:
        """Start FFmpeg (it's a CLI tool, nothing to start)."""
        return True

    def verify(self) -> bool:
        """Verify FFmpeg installation."""
        self.logger.info("Verifying FFmpeg...")
        
        code, out, _ = self.execute_remote("ffmpeg -version")
        if code != 0:
            self.logger.error("FFmpeg verification failed")
            return False
            
        self.logger.info(f"✓ FFmpeg installed: {out.splitlines()[0]}")
        return True

    def rollback(self):
        """Rollback installation (not implemented for system packages)."""
        pass
