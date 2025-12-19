# START OF FILE seed/seed_cli.py
#!/usr/bin/env python3
"""
NoSlop Seed CLI - Smart Installer for NoSlop Framework.

Interactive deployment wizard for NoSlop across local network devices.
"""

import argparse
import logging
import sys
import os
import subprocess
from pathlib import Path
from typing import List, Optional
from datetime import datetime
import logging.handlers


def ensure_dependencies():
    """
    Ensure all seed dependencies are installed.
    Auto-installs missing dependencies if needed.
    """
    try:
        import paramiko
        import psutil
        import requests
        return True
    except ImportError:
        print("📦 Installing NoSlop Seed dependencies...")
        print("   This only needs to happen once.\n")
        
        # Get the seed requirements file
        seed_dir = Path(__file__).parent
        requirements_file = seed_dir / "requirements.txt"
        
        if not requirements_file.exists():
            print("❌ Error: requirements.txt not found")
            return False
        
        try:
            # Install dependencies with --break-system-packages for PEP 668 systems
            # This is safe for deployment tools that manage their own dependencies
            subprocess.check_call([
                sys.executable, "-m", "pip", "install", "-q", 
                "--break-system-packages",
                "-r", str(requirements_file)
            ])
            print("✅ Dependencies installed successfully!\n")
            return True
        except subprocess.CalledProcessError as e:
            print(f"❌ Failed to install dependencies: {e}")
            print(f"\nPlease install manually with:")
            print(f"   pip install --break-system-packages -r {requirements_file}")
            return False


# Ensure dependencies before importing seed modules
if not ensure_dependencies():
    sys.exit(1)

from seed.hardware_detector import HardwareDetector
from seed.network_scanner import NetworkScanner
from seed.role_assigner import RoleAssigner
from seed.ssh_manager import SSHManager, PARAMIKO_AVAILABLE, SSHCredentials
from seed.deployer import Deployer
from seed.models import DeviceCapabilities
from seed.credential_store import CredentialStore

# Import shared logging utilities
try:
    from shared.logging_utils import setup_module_logging
    SHARED_LOGGING_AVAILABLE = True
except ImportError:
    SHARED_LOGGING_AVAILABLE = False
    # Fallback to custom logging setup


def setup_logging(log_level: str = "INFO", module_name: str = "seed_installer"):
    """
    Configure comprehensive logging for all modules.
    
    Logs to both console and dated files in logs/ folder.
    Uses shared logging utilities if available, otherwise falls back to custom setup.
    """
    if SHARED_LOGGING_AVAILABLE:
        # Use shared logging utilities
        log_file = setup_module_logging(
            module_name=module_name,
            log_level=log_level,
            log_dir="logs",
            enable_console=True,
            enable_file=True
        )
        logger = logging.getLogger(__name__)
        logger.info(f"Using shared logging utilities")
        return log_file
    else:
        # Fallback to custom logging setup
        # Create logs directory
        logs_dir = Path("logs")
        logs_dir.mkdir(exist_ok=True)
        
        # Generate dated log filename
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        log_file = logs_dir / f"{module_name}_{timestamp}.log"
        
        # Convert log level string to logging constant
        level = getattr(logging, log_level.upper(), logging.INFO)
        
        # Create formatters
        detailed_formatter = logging.Formatter(
            '%(asctime)s - %(name)s - %(levelname)s - [%(filename)s:%(lineno)d] - %(message)s',
            datefmt='%Y-%m-%d %H:%M:%S'
        )
        
        console_formatter = logging.Formatter(
            '%(asctime)s - %(levelname)s - %(message)s',
            datefmt='%H:%M:%S'
        )
        
        # Configure root logger
        root_logger = logging.getLogger()
        root_logger.setLevel(level)
        
        # Remove existing handlers
        root_logger.handlers.clear()
        
        # File handler (always detailed)
        file_handler = logging.FileHandler(log_file, encoding='utf-8')
        file_handler.setLevel(logging.DEBUG)  # Always capture DEBUG to file
        file_handler.setFormatter(detailed_formatter)
        root_logger.addHandler(file_handler)
        
        # Console handler (respects user's log level)
        console_handler = logging.StreamHandler(sys.stdout)
        console_handler.setLevel(level)
        console_handler.setFormatter(console_formatter)
        root_logger.addHandler(console_handler)
        
        # Log the logging configuration
        logger = logging.getLogger(__name__)
        logger.info(f"Logging initialized: Level={log_level}, File={log_file}")
        
        return log_file



logger = logging.getLogger(__name__)


from seed.storage_manager import StorageManager

class NoSlopSeedCLI:
    """
    Command-line interface for NoSlop Seed installer.
    
    Provides interactive and automated deployment modes.
    """
    
    def __init__(self, args):
        """Initialize CLI with parsed arguments."""
        self.args = args
        
        # Set log level
        if args.log_level:
            logging.getLogger().setLevel(args.log_level.upper())
        
        # Initialize components
        self.hardware_detector = HardwareDetector()
        self.network_scanner = NetworkScanner(timeout=2)
        self.role_assigner = RoleAssigner()
        self.ssh_manager = SSHManager()
        self.credential_store = CredentialStore()
        self.storage_manager = StorageManager(self.ssh_manager)
        self.deployer = None  # Created later with SSH manager
    
    def print_banner(self):
        """Print welcome banner."""
        print("\n" + "="*70)
        print("🌱 NoSlop Seed - Smart Installer")
        print("="*70)
        print("\nDeploy your decentralized media creation network")
        print("across local devices with intelligent role assignment.\n")
    
    def run(self):
        """Main entry point for CLI."""
        self.print_banner()
        
        if self.args.single_device:
            return self.run_single_device_mode()
        else:
            return self.run_interactive_mode()
    
    def run_single_device_mode(self):
        """Deploy on current device only (all-in-one mode)."""
        print("📍 Single Device Mode\n")
        
        # Detect current device
        print("🔍 Detecting hardware capabilities...")
        device = self.hardware_detector.detect()
        
        print(f"\n✓ Detected: {device.hostname}")
        print(f"  Hardware Score: {device.capability_score}/100")
        print(f"  CPU: {device.cpu_cores} cores @ {device.cpu_speed_ghz} GHz")
        print(f"  RAM: {device.ram_total_gb} GB")
        print(f"  GPU: {device.gpu_vendor.value} ({device.vram_total_gb} GB VRAM)")
        print(f"  Disk: {device.disk_total_gb} GB")
        
        if not device.meets_minimum_requirements():
            print("\n⚠️  WARNING: This device does not meet minimum requirements:")
            print("   - 2+ CPU cores")
            print("   - 4GB RAM")
            print("   - 100GB disk space")
            print("\n   The framework will work but may be slow.")
            print("   Recommended: 16GB RAM, 8GB VRAM, 500GB disk\n")
            
            if not self.confirm("Continue anyway?"):
                print("\n❌ Deployment cancelled.")
                return False
        
        # Create deployment plan
        print("\n📋 Creating deployment plan...")
        plan = self.role_assigner.create_deployment_plan([device])
        
        # Collect local credentials to ensure correct user ownership
        import os
        import getpass
        from seed.ssh_manager import SSHCredentials
        
        print("\n🔐 Local Sudo Access")
        local_user = os.getenv("USER", "root")
        print(f"   Installing services as user: {local_user}")
        local_pass = getpass.getpass(f"   Enter sudo password for {local_user}: ")
        
        credentials_map = {}
        if local_pass:
            creds = SSHCredentials(
                ip_address=device.ip_address,
                username=local_user,
                password=local_pass,
                port=22
            )
            credentials_map[device.ip_address] = creds
            print("   ✓ Credentials stored\n")
        
        # Deploy
        return self.execute_deployment(plan, credentials_map)
    
    def configure_storage(self):
        """Configure shared storage upfront."""
        print("\n" + "="*70)
        print("🗄️  Storage Configuration")
        print("="*70)
        print("\nCentralized logging and model storage relies on a shared folder.")
        print("We configure this upfront so logs are captured correctly from the start.\n")
        
        return self.storage_manager.prompt_storage_config()

    def load_env_file(self):
        """Load environment variables from .env file."""
        env_path = Path(".env")
        if not env_path.exists():
            return
            
        print("📄 Loading configuration from .env...")
        try:
            with open(env_path, 'r') as f:
                for line in f:
                    line = line.strip()
                    if not line or line.startswith('#'):
                        continue
                    
                    if '=' in line:
                        key, value = line.split('=', 1)
                        key = key.strip()
                        value = value.strip().strip("'").strip('"')
                        
                        # Only set if not already set (respect existing env vars)
                        if key not in os.environ:
                            os.environ[key] = value
                            # logging.debug(f"Loaded {key} from .env")
        except Exception as e:
            logger.warning(f"Failed to parse .env file: {e}")

    def configure_external_access(self):
        """Configure external access URL."""
        # Check if already set in environment (e.g. from .env)
        external_url = os.environ.get("NOSLOP_FRONTEND_EXTERNAL_URL")
        
        if external_url:
            print(f"✓ Using configured external URL: {external_url}")
            return
            
        # Not set - ask user
        print("\n" + "="*70)
        print("🌐 External Access Configuration")
        print("="*70)
        print("\nDo you have an existing external URL (e.g., Cloudflare Tunnel)?")
        print("This enables features like Voice input/output over HTTPS.")
        print("Example: https://noslop.example.com\n")
        
        response = input("External URL [Leave empty or 's' to skip]: ").strip()
        
        if response and response.lower() != 's':
            # Basic validation
            if not response.startswith("http"):
                response = f"https://{response}"
                print(f"   corrected to: {response}")
                
            os.environ["NOSLOP_FRONTEND_EXTERNAL_URL"] = response
            print(f"   ✓ Configured: {response}")
        else:
            print("   Skipped external access configuration.")

    def run_interactive_mode(self):
        """Interactive deployment wizard."""
        print("🧙 Interactive Deployment Wizard\n")

        # Load environment variables
        self.load_env_file()
        
        # Configure external access early
        self.configure_external_access()

        if not PARAMIKO_AVAILABLE:
            logger.error("SSH library (paramiko) is not installed.")
            print("\n❌ Critical dependency missing!")
            print("   Multi-device deployment requires SSH support.")
            print("\n   Please install all dependencies with:")
            print("   pip install -r seed/requirements.txt\n")
            return False
        
        # Step 0: Configure Storage (Upfront)
        storage_config = self.configure_storage()
        
        # Step 1: Discover devices (stores credentials internally)
        devices, credentials_map = self.discover_devices()
        
        if not devices:
            print("\n❌ No devices discovered. Exiting.")
            return False
        
        # Step 2: Select devices
        selected_devices = self.select_devices(devices)
        
        if not selected_devices:
            print("\n❌ No devices selected. Exiting.")
            return False
        
        # Step 3: Create deployment plan
        print("\n📋 Creating deployment plan...")
        plan = self.role_assigner.create_deployment_plan(selected_devices)
        
        # Step 4: Show plan and confirm
        self.role_assigner.print_deployment_plan(plan)
        
        if not self.confirm("\nProceed with deployment?"):
            print("\n❌ Deployment cancelled.")
            return False
        
        # Step 5: Setup SSH keys (if multi-device)
        if len(selected_devices) > 1:
            if not self.setup_ssh_keys(credentials_map):
                print("\n❌ SSH key setup failed. Deployment cancelled.")
                return False
        
        # Step 6: Execute deployment
        return self.execute_deployment(plan, credentials_map, storage_config)
    
    def discover_devices(self):
        """Discover devices on the network. Returns (devices, credentials_map)."""
        devices = []
        credentials_map = {}  # Store credentials for SSH key distribution
        
        # Always include current device first
        print("🔍 Detecting local hardware...")
        current_device = self.hardware_detector.detect()
        devices.append(current_device)
        print(f"✓ Added: {current_device.hostname} ({current_device.ip_address}) - score: {current_device.capability_score}/100\n")
        
        # Collect local credentials for sudo operations
        print("🔐 Local Sudo Access")
        print("   To install services locally, we need sudo access.")
        import getpass
        import os
        local_user = os.getenv("USER", "root")
        local_pass = getpass.getpass(f"   Enter sudo password for {local_user}: ")
        
        if local_pass:
            from seed.ssh_manager import SSHCredentials
            # We store credentials for the local IP so deployer can find them
            creds = SSHCredentials(
                ip_address=current_device.ip_address,
                username=local_user,
                password=local_pass,
                port=22
            )
            credentials_map[current_device.ip_address] = creds
            self.credential_store.save_credential(
                current_device.ip_address, local_user, local_pass, 22
            )
            print("   ✓ Local credentials stored\n")
        else:
            print("   ⚠️  No password provided. Local installation might fail if sudo requires password.\n")
        
        # Option 1: Scan network for remote devices
        if not self.args.skip_scan:
            print("🔍 Scanning local network for SSH-enabled devices...")
            print("   (This may take a few minutes)\n")
            
            discovered = self.network_scanner.scan_network()
            
            if discovered:
                # Filter out localhost/current device
                remote_devices = [
                    d for d in discovered 
                    if d.ip_address not in [current_device.ip_address, "127.0.0.1", "localhost"]
                ]
                
                if remote_devices:
                    print(f"\n✓ Found {len(remote_devices)} remote SSH-enabled device(s)\n")
                    
                    # Collect credentials and detect hardware for each
                    for discovered_device in remote_devices:
                        print(f"📡 Device: {discovered_device.ip_address}")
                        if discovered_device.hostname:
                            print(f"   Hostname: {discovered_device.hostname}")
                        
                        # Prompt for credentials
                        # Prompt for credentials with retry
                        current_user = os.getenv("USER", "root")
                        username = current_user
                        password = None
                        
                        while True:
                            user_input = input(f"   Username [{current_user}] (or 's' to skip): ").strip()
                            
                            if user_input.lower() == 's':
                                print(f"   ⚠️  Skipping {discovered_device.ip_address}\n")
                                password = None # Ensure we break out later
                                break
                                
                            username = user_input or current_user
                            
                            import getpass
                            password = getpass.getpass(f"   Password: ")
                            
                            if password:
                                break
                                
                            print(f"   ⚠️  Password is required.")
                            retry = input("   Retry? [Y/n]: ").strip().lower()
                            if retry == 'n':
                                print(f"   ⚠️  Skipping {discovered_device.ip_address}\n")
                                password = None
                                break
                        
                        if not password:
                            continue
                        
                        # Create credentials
                        from seed.ssh_manager import SSHCredentials
                        credentials = SSHCredentials(
                            ip_address=discovered_device.ip_address,
                            username=username,
                            password=password,
                            port=discovered_device.ssh_port
                        )
                        
                        # Detect remote hardware
                        print(f"   🔍 Detecting hardware...")
                        remote_capabilities = self.hardware_detector.detect_remote(
                            credentials, self.ssh_manager
                        )
                        
                        if remote_capabilities:
                            devices.append(remote_capabilities)
                            credentials_map[remote_capabilities.ip_address] = credentials
                            self.credential_store.save_credential(
                                remote_capabilities.ip_address, username, password, discovered_device.ssh_port
                            )
                            print(f"   ✓ Added: {remote_capabilities.hostname} - {remote_capabilities.os_type.value} - score: {remote_capabilities.capability_score}/100\n")
                        else:
                            print(f"   ✗ Failed to detect hardware for {discovered_device.ip_address}\n")
                else:
                    print("\n✓ Network scan complete. No additional SSH-enabled devices found.")
                    print("   (Current device is already included)\n")
        
        # Option 2: Manual IP entry
        if self.args.ips:
            ip_list = [ip.strip() for ip in self.args.ips.split(',')]
            print(f"\n📝 Processing {len(ip_list)} manually specified IP(s)...\n")
            
            for ip in ip_list:
                # Skip if already discovered
                if any(d.ip_address == ip for d in devices):
                    print(f"⚠️  {ip} already discovered, skipping.\n")
                    continue
                
                print(f"📡 Device: {ip}")
                
                # Prompt for credentials
                username = input(f"   Username [root]: ").strip() or "root"
                
                import getpass
                password = getpass.getpass(f"   Password: ")
                
                if not password:
                    print(f"   ⚠️  Skipping {ip} (no password provided)\n")
                    continue
                
                # Create credentials
                from seed.ssh_manager import SSHCredentials
                credentials = SSHCredentials(
                    ip_address=ip,
                    username=username,
                    password=password,
                    port=22
                )
                
                # Detect remote hardware
                print(f"   🔍 Detecting hardware...")
                remote_capabilities = self.hardware_detector.detect_remote(
                    credentials, self.ssh_manager
                )
                
                if remote_capabilities:
                    devices.append(remote_capabilities)
                    credentials_map[remote_capabilities.ip_address] = credentials
                    self.credential_store.save_credential(
                        remote_capabilities.ip_address, username, password, 22
                    )
                    print(f"   ✓ Added: {remote_capabilities.hostname} - {remote_capabilities.os_type.value} - score: {remote_capabilities.capability_score}/100\n")
                else:
                    print(f"   ✗ Failed to detect hardware for {ip}\n")
        
        return devices, credentials_map
    
    def select_devices(self, devices: List[DeviceCapabilities]) -> List[DeviceCapabilities]:
        """Allow user to select which devices to use."""
        print("\n" + "="*70)
        print("Device Selection")
        print("="*70)
        
        print("\nAvailable devices:\n")
        for i, device in enumerate(devices, 1):
            print(f"{i}. {device.hostname} ({device.ip_address})")
            print(f"   Score: {device.capability_score}/100")
            print(f"   Hardware: {device.cpu_cores} cores, "
                  f"{device.ram_total_gb}GB RAM, "
                  f"{device.vram_total_gb}GB VRAM")
            print()
        
        # For now, just use all devices
        print("Using all available devices.")
        return devices
    
    def setup_ssh_keys(self, credentials_map: dict) -> bool:
        """Setup SSH keys for remote devices using stored credentials."""
        if not credentials_map:
            return True  # No remote devices
        
        print("\n" + "="*70)
        print("🔑 SSH Key Distribution")
        print("="*70)
        print(f"\nSetting up passwordless SSH for {len(credentials_map)} remote device(s)...")
        
        # Generate keys if not exists
        self.ssh_manager.generate_key_pair()
        
        # Distribute keys
        print("\nDistributing SSH keys...")
        success_count = 0
        for ip, credentials in credentials_map.items():
            print(f"  📤 {ip}...", end=" ")
            if self.ssh_manager.distribute_key(credentials, interactive=False):
                success_count += 1
                print("✓")
            else:
                print("✗")
        
        if success_count == len(credentials_map):
            print(f"\n✓ SSH keys distributed to all {success_count} device(s).")
            print("   Master node can now manage remote nodes without passwords.\n")
            return True
        else:
            failed_count = len(credentials_map) - success_count
            print(f"\n⚠️  SSH key distribution failed for {failed_count} device(s).")
            print("   You may need to enter passwords during deployment.\n")
            return self.confirm("Continue anyway?")
    
    def execute_deployment(self, plan, credentials_map=None, storage_config=None) -> bool:
        """Execute the deployment plan."""
        if credentials_map is None:
            credentials_map = {}

        print("\n" + "=" * 70)
        print("🚀 Executing Deployment")
        print("=" * 70)

        # Create deployer
        self.deployer = Deployer(self.ssh_manager, output_dir=self.args.output_dir)

        # Show summary
        print(self.deployer.get_deployment_summary(plan))

        # Deploy
        print("\n📦 Starting deployment process...")
        print("   This involves:")
        print("   1. Network discovery for existing services")
        print("   2. Configuration generation")
        print("   3. Service installation (PostgreSQL, Ollama, ComfyUI, etc.)")
        print("   4. Verification")
        print("\n   This may take 10-20 minutes depending on internet speed.\n")

        if not self.confirm("Start installation?"):
            print("\n❌ Installation cancelled.")
            return False

        success = self.deployer.deploy(plan, credentials_map, storage_config)

        if success:
            print("\n" + "=" * 70)
            print("✅ Deployment Complete!")
            print("=" * 70)
            print(f"\n📁 Deployment artifacts: {self.deployer.deployment_dir}")
            
            print("\nNext steps:")
            print("1. Access the dashboard at the URL(s) listed above")
            print("2. Check service status with: python seed_cli.py --status")
            print("\n")
            return True
        else:
            print("\n❌ Deployment failed!")
            print("Check logs for details.")
            return False
    
    def confirm(self, message: str) -> bool:
        """Ask user for confirmation."""
        while True:
            response = input(f"{message} [y/N]: ").strip().lower()
            if response in ['y', 'yes']:
                return True
            elif response in ['n', 'no', '']:
                return False
            else:
                print("Please enter 'y' or 'n'")


def main():
    """Main entry point."""
    parser = argparse.ArgumentParser(
        description="NoSlop Seed - Smart Installer for NoSlop Framework",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  # Interactive mode (default)
  python seed_cli.py
  
  # Single device deployment
  python seed_cli.py --single-device
  
  # Scan specific network
  python seed_cli.py --network 192.168.1.0/24
  
  # Use specific IPs
  python seed_cli.py --ips 192.168.1.10,192.168.1.11
  
  # Debug mode
  python seed_cli.py --log-level DEBUG
        """
    )
    
    parser.add_argument(
        '--single-device',
        action='store_true',
        help='Deploy on current device only (all-in-one mode)'
    )
    
    parser.add_argument(
        '--network',
        type=str,
        help='Network range to scan (e.g., 192.168.1.0/24)'
    )
    
    parser.add_argument(
        '--ips',
        type=str,
        help='Comma-separated list of IP addresses to use'
    )
    
    parser.add_argument(
        '--skip-scan',
        action='store_true',
        help='Skip network scan (use manual IPs only)'
    )
    
    parser.add_argument(
        '--log-level',
        type=str,
        choices=['DEBUG', 'INFO', 'WARN', 'ERROR'],
        default='INFO',
        help='Logging level'
    )
    
    parser.add_argument(
        '--output-dir',
        type=str,
        help='Directory for deployment artifacts'
    )
    
    # Management commands
    parser.add_argument(
        '--start',
        action='store_true',
        help='Start all services on all nodes'
    )
    
    parser.add_argument(
        '--stop',
        action='store_true',
        help='Stop all services on all nodes'
    )
    
    parser.add_argument(
        '--restart',
        action='store_true',
        help='Restart all services on all nodes'
    )
    
    parser.add_argument(
        '--status',
        action='store_true',
        help='Show status of all services on all nodes'
    )
    
    parser.add_argument(
        '--uninstall',
        action='store_true',
        help='Uninstall NoSlop from all nodes (requires confirmation)'
    )
    
    parser.add_argument(
        '--deployment-id',
        type=str,
        help='Deployment ID to manage (defaults to latest)'
    )
    
    args = parser.parse_args()
    
    # Determine module name based on command
    is_management = args.start or args.stop or args.restart or args.status or args.uninstall
    module_name = "service_manager" if is_management else "seed_installer"
    
    # Setup comprehensive logging (always captures DEBUG to file)
    log_file = setup_logging(args.log_level, module_name)
    
    logger.info(f"NoSlop Seed Installer started")
    logger.info(f"Log file: {log_file}")
    logger.debug(f"Command line arguments: {args}")
    
    # Check if this is a management command
    if args.start or args.stop or args.restart or args.status or args.uninstall:
        from seed.manager import ServiceManager
        
        # Find deployment directory
        deployments_dir = Path.home() / ".noslop" / "deployments"
        if not deployments_dir.exists():
            logger.error("No deployments found. Please deploy NoSlop first.")
            sys.exit(1)
        
        # Get deployment ID
        if args.deployment_id:
            deployment_dir = deployments_dir / args.deployment_id
            if not deployment_dir.exists():
                logger.error(f"Deployment not found: {args.deployment_id}")
                sys.exit(1)
        else:
            # Use latest deployment
            deployments = sorted(deployments_dir.iterdir(), key=lambda x: x.name, reverse=True)
            if not deployments:
                logger.error("No deployments found. Please deploy NoSlop first.")
                sys.exit(1)
            deployment_dir = deployments[0]
            logger.info(f"Using latest deployment: {deployment_dir.name}")
        
        # Create service manager
        ssh_manager = SSHManager()
        
        # Prompt for sudo password for remote management
        import getpass
        print(f"\n🔐 Sudo Access Required")
        print("   To manage services (start/stop/etc), sudo privileges are required.")
        sudo_password = getpass.getpass("   Enter sudo password (leave empty if passwordless): ")
        if not sudo_password:
             sudo_password = None
             print("   ⚠️  No signature provided. Operations may fail if password is required.")
        
        manager = ServiceManager(deployment_dir, ssh_manager, sudo_password=sudo_password)
        
        # Execute management command
        success = True
        if args.status:
            status = manager.status_all()
            print("\n" + "="*70)
            print("NoSlop Service Status")
            print("="*70)
            print(f"\nDeployment ID: {status['deployment_id']}\n")
            
            for node in status['nodes']:
                print(f"📡 {node['hostname']} ({node['ip_address']})")
                print(f"   Roles: {', '.join(node['roles'])}")
                print(f"   Services:")
                for svc in node['services']:
                    status_icon = "✓" if svc['active'] else "✗"
                    status_text = svc['status']
                    print(f"     {status_icon} {svc['name']}: {status_text}")
                print()
        elif args.start:
            success = manager.start_all()
        elif args.stop:
            success = manager.stop_all()
        elif args.restart:
            success = manager.restart_all()
        elif args.uninstall:
            success = manager.uninstall_all()
        
        sys.exit(0 if success else 1)
    
    # Create and run CLI
    cli = NoSlopSeedCLI(args)
    success = cli.run()
    
    logger.info(f"Installer {'completed successfully' if success else 'failed'}")
    
    sys.exit(0 if success else 1)


if __name__ == "__main__":
    main()
