# START OF FILE seed/seed_cli.py
#!/usr/bin/env python3
"""
NoSlop Seed CLI - Smart Installer for NoSlop Framework.

Interactive deployment wizard for NoSlop across local network devices.
"""

import argparse
import logging
import sys
from pathlib import Path
from typing import List, Optional

from seed.hardware_detector import HardwareDetector
from seed.network_scanner import NetworkScanner
from seed.role_assigner import RoleAssigner
from seed.ssh_manager import SSHManager
from seed.deployer import Deployer
from seed.models import DeviceCapabilities

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)


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
        
        # Deploy
        return self.execute_deployment(plan)
    
    def run_interactive_mode(self):
        """Interactive deployment wizard."""
        print("🧙 Interactive Deployment Wizard\n")
        
        # Step 1: Discover devices
        devices = self.discover_devices()
        
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
        
        # Step 5: Setup SSH (if multi-device)
        if len(selected_devices) > 1:
            if not self.setup_ssh(selected_devices):
                print("\n❌ SSH setup failed. Deployment cancelled.")
                return False
        
        # Step 6: Execute deployment
        return self.execute_deployment(plan)
    
    def discover_devices(self) -> List[DeviceCapabilities]:
        """Discover devices on the network."""
        devices = []
        
        # Option 1: Scan network
        if not self.args.skip_scan:
            print("🔍 Scanning local network for SSH-enabled devices...")
            print("   (This may take a few minutes)\n")
            
            discovered = self.network_scanner.scan_network()
            
            if discovered:
                print(f"\n✓ Found {len(discovered)} SSH-enabled device(s)")
                
                # Detect hardware for each discovered device
                # For now, just add current device
                # TODO: SSH into each device and detect hardware
                print("\n⚠️  Remote hardware detection not yet implemented.")
                print("   Adding current device only.\n")
        
        # Always include current device
        print("🔍 Detecting local hardware...")
        current_device = self.hardware_detector.detect()
        devices.append(current_device)
        print(f"✓ Added: {current_device.hostname} (score: {current_device.capability_score}/100)")
        
        # Option 2: Manual IP entry
        if self.args.ips:
            print(f"\n📝 Manual IPs provided: {self.args.ips}")
            print("⚠️  Remote hardware detection not yet implemented.")
        
        return devices
    
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
    
    def setup_ssh(self, devices: List[DeviceCapabilities]) -> bool:
        """Setup SSH keys for remote devices."""
        print("\n" + "="*70)
        print("SSH Setup")
        print("="*70)
        
        # Generate keys
        self.ssh_manager.generate_key_pair()
        
        # Collect credentials
        ip_addresses = [d.ip_address for d in devices if d.ip_address not in ["localhost", "127.0.0.1"]]
        if not ip_addresses:
            return True
            
        credentials_map = self.ssh_manager.collect_credentials(ip_addresses)
        
        # Distribute keys
        print("\nDistributing SSH keys...")
        success_count = 0
        for ip, creds in credentials_map.items():
            if self.ssh_manager.distribute_key(creds, interactive=False):
                success_count += 1
            else:
                print(f"❌ Failed to distribute key to {ip}")
        
        if success_count == len(ip_addresses):
            print("\n✓ SSH setup complete for all devices.")
            return True
        else:
            print(f"\n⚠️  SSH setup failed for {len(ip_addresses) - success_count} devices.")
            return self.confirm("Continue with partial deployment?")
    
    def execute_deployment(self, plan) -> bool:
        """Execute the deployment plan."""
        print("\n" + "="*70)
        print("🚀 Executing Deployment")
        print("="*70)
        
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
            
        success = self.deployer.deploy(plan)
        
        if success:
            print("\n" + "="*70)
            print("✅ Deployment Complete!")
            print("="*70)
            print(f"\n📁 Deployment artifacts: {self.deployer.deployment_dir}")
            print("\nNext steps:")
            print("1. Access the dashboard at http://localhost:3000")
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
    
    args = parser.parse_args()
    
    # Create and run CLI
    cli = NoSlopSeedCLI(args)
    success = cli.run()
    
    sys.exit(0 if success else 1)


if __name__ == "__main__":
    main()
