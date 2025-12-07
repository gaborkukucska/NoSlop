# START OF FILE seed/device_rediscovery.py
"""
Device Re-Discovery Module for NoSlop Seed.

Handles finding devices on the network when their IP addresses change.
Uses MAC addresses, hostnames, and network scanning to locate devices.
"""

import logging
import subprocess
import socket
import json
from pathlib import Path
from typing import Optional, List, Dict, Tuple
from datetime import datetime

from seed.models import DeviceCapabilities, NodeAssignment, DeploymentPlan

logger = logging.getLogger(__name__)


class DeviceRediscovery:
    """
    Handles device re-discovery when IP addresses change.
    
    Uses multiple strategies to find devices:
    1. ARP table scanning (fast, local cache)
    2. Hostname resolution (DNS/mDNS)
    3. Network scanning (ping sweep + ARP)
    4. nmap scanning (if available)
    """
    
    def __init__(self):
        """Initialize device re-discovery system."""
        logger.info("Device re-discovery system initialized")
    
    def find_device_by_mac(
        self,
        mac_address: str,
        subnet: Optional[str] = None,
        timeout: int = 30
    ) -> Optional[str]:
        """
        Find device IP address by MAC address.
        
        Strategy:
        1. Check ARP table (fast, local cache)
        2. Ping sweep + ARP check (medium speed)
        3. nmap scan if available (thorough)
        
        Args:
            mac_address: MAC address to search for
            subnet: Subnet to scan (e.g., "192.168.1.0/24"), auto-detected if None
            timeout: Maximum time to spend searching (seconds)
            
        Returns:
            New IP address or None if not found
        """
        logger.info(f"Searching for device with MAC: {mac_address}")
        
        # Normalize MAC address format
        mac_address = mac_address.lower().replace('-', ':')
        
        # Strategy 1: Check ARP table
        ip = self._check_arp_table(mac_address)
        if ip:
            logger.info(f"Found device in ARP table: {ip}")
            return ip
        
        # Strategy 2: Ping sweep + ARP
        if not subnet:
            subnet = self._detect_local_subnet()
        
        if subnet:
            logger.info(f"Performing ping sweep on subnet: {subnet}")
            ip = self._ping_sweep_and_check_arp(mac_address, subnet, timeout=timeout)
            if ip:
                logger.info(f"Found device via ping sweep: {ip}")
                return ip
        
        # Strategy 3: nmap scan (if available)
        if self._is_nmap_available():
            logger.info("Attempting nmap scan...")
            ip = self._nmap_scan(mac_address, subnet, timeout=timeout)
            if ip:
                logger.info(f"Found device via nmap: {ip}")
                return ip
        
        logger.warning(f"Could not find device with MAC: {mac_address}")
        return None
    
    def find_device_by_hostname(
        self,
        hostname: str
    ) -> Optional[str]:
        """
        Find device IP by hostname resolution.
        
        Methods:
        1. DNS lookup
        2. mDNS/Bonjour (for .local domains)
        3. /etc/hosts file
        
        Args:
            hostname: Hostname to resolve
            
        Returns:
            IP address or None if not found
        """
        logger.info(f"Resolving hostname: {hostname}")
        
        try:
            # Try standard DNS resolution
            ip = socket.gethostbyname(hostname)
            logger.info(f"Resolved {hostname} to {ip} via DNS")
            return ip
        except socket.gaierror:
            logger.debug(f"DNS resolution failed for {hostname}")
        
        # Try with .local suffix for mDNS
        if not hostname.endswith('.local'):
            try:
                ip = socket.gethostbyname(f"{hostname}.local")
                logger.info(f"Resolved {hostname}.local to {ip} via mDNS")
                return ip
            except socket.gaierror:
                logger.debug(f"mDNS resolution failed for {hostname}.local")
        
        logger.warning(f"Could not resolve hostname: {hostname}")
        return None
    
    def verify_device_identity(
        self,
        ip_address: str,
        expected_hostname: Optional[str] = None,
        expected_mac: Optional[str] = None
    ) -> bool:
        """
        Verify device identity by checking hostname and MAC.
        
        Args:
            ip_address: IP address to verify
            expected_hostname: Expected hostname (optional)
            expected_mac: Expected MAC address (optional)
            
        Returns:
            True if device matches expected identity
        """
        logger.debug(f"Verifying device identity at {ip_address}")
        
        # Check hostname if provided
        if expected_hostname:
            try:
                actual_hostname = socket.gethostbyaddr(ip_address)[0]
                if actual_hostname.lower() != expected_hostname.lower():
                    logger.warning(f"Hostname mismatch: expected {expected_hostname}, got {actual_hostname}")
                    return False
                logger.debug(f"Hostname verified: {actual_hostname}")
            except socket.herror:
                logger.debug(f"Could not resolve hostname for {ip_address}")
        
        # Check MAC if provided
        if expected_mac:
            actual_mac = self._get_mac_for_ip(ip_address)
            if actual_mac:
                expected_mac = expected_mac.lower().replace('-', ':')
                actual_mac = actual_mac.lower().replace('-', ':')
                if actual_mac != expected_mac:
                    logger.warning(f"MAC mismatch: expected {expected_mac}, got {actual_mac}")
                    return False
                logger.debug(f"MAC verified: {actual_mac}")
            else:
                logger.debug(f"Could not get MAC for {ip_address}")
        
        return True
    
    def update_deployment_plan(
        self,
        deployment_dir: Path,
        old_ip: str,
        new_ip: str,
        backup: bool = True
    ) -> bool:
        """
        Update deployment plan with new IP address.
        
        Creates backup before modification.
        
        Args:
            deployment_dir: Path to deployment artifacts directory
            old_ip: Old IP address to replace
            new_ip: New IP address
            backup: Whether to create backup before updating
            
        Returns:
            True if successful
        """
        plan_file = deployment_dir / "deployment_plan.json"
        
        if not plan_file.exists():
            logger.error(f"Deployment plan not found: {plan_file}")
            return False
        
        try:
            # Create backup if requested
            if backup:
                timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
                backup_file = deployment_dir / f"deployment_plan.json.backup.{timestamp}"
                with open(plan_file, 'r') as f:
                    backup_content = f.read()
                with open(backup_file, 'w') as f:
                    f.write(backup_content)
                logger.info(f"Created backup: {backup_file}")
            
            # Read deployment plan
            with open(plan_file, 'r') as f:
                plan_data = json.load(f)
            
            # Update IP addresses
            updated = False
            for node in plan_data.get('nodes', []):
                device = node.get('device', {})
                if device.get('ip_address') == old_ip:
                    device['ip_address'] = new_ip
                    updated = True
                    logger.info(f"Updated node {device.get('hostname', 'unknown')}: {old_ip} → {new_ip}")
            
            # Update master node if present
            if plan_data.get('master_node'):
                master_device = plan_data['master_node'].get('device', {})
                if master_device.get('ip_address') == old_ip:
                    master_device['ip_address'] = new_ip
                    updated = True
                    logger.info(f"Updated master node: {old_ip} → {new_ip}")
            
            if not updated:
                logger.warning(f"No nodes found with IP {old_ip}")
                return False
            
            # Write updated plan
            with open(plan_file, 'w') as f:
                json.dump(plan_data, f, indent=2)
            
            logger.info(f"Deployment plan updated successfully")
            return True
            
        except Exception as e:
            logger.error(f"Error updating deployment plan: {e}")
            return False
    
    # Private helper methods
    
    def _check_arp_table(self, mac_address: str) -> Optional[str]:
        """Check ARP table for MAC address."""
        try:
            # Try Linux/macOS arp command
            result = subprocess.run(
                ["arp", "-an"],
                capture_output=True,
                text=True,
                timeout=5
            )
            
            if result.returncode == 0:
                for line in result.stdout.split('\n'):
                    if mac_address in line.lower():
                        # Extract IP address
                        parts = line.split()
                        for part in parts:
                            if part.startswith('(') and part.endswith(')'):
                                ip = part.strip('()')
                                return ip
                            elif '.' in part and part.replace('.', '').isdigit():
                                return part
        except Exception as e:
            logger.debug(f"Error checking ARP table: {e}")
        
        return None
    
    def _get_mac_for_ip(self, ip_address: str) -> Optional[str]:
        """Get MAC address for an IP from ARP table."""
        try:
            # Ping first to populate ARP table
            subprocess.run(
                ["ping", "-c", "1", "-W", "1", ip_address],
                capture_output=True,
                timeout=2
            )
            
            # Check ARP table
            result = subprocess.run(
                ["arp", "-an", ip_address],
                capture_output=True,
                text=True,
                timeout=5
            )
            
            if result.returncode == 0:
                for line in result.stdout.split('\n'):
                    # Look for MAC address pattern
                    parts = line.split()
                    for part in parts:
                        if ':' in part and len(part.replace(':', '')) == 12:
                            return part
        except Exception as e:
            logger.debug(f"Error getting MAC for IP: {e}")
        
        return None
    
    def _detect_local_subnet(self) -> Optional[str]:
        """Detect local subnet for scanning."""
        try:
            # Get default route
            result = subprocess.run(
                ["ip", "route", "show", "default"],
                capture_output=True,
                text=True,
                timeout=5
            )
            
            if result.returncode == 0:
                # Extract subnet from output
                for line in result.stdout.split('\n'):
                    if 'src' in line:
                        parts = line.split()
                        src_idx = parts.index('src') + 1
                        if src_idx < len(parts):
                            local_ip = parts[src_idx]
                            # Convert to /24 subnet
                            subnet_parts = local_ip.split('.')
                            subnet = f"{'.'.join(subnet_parts[:3])}.0/24"
                            logger.debug(f"Detected subnet: {subnet}")
                            return subnet
        except Exception as e:
            logger.debug(f"Error detecting subnet: {e}")
        
        return None
    
    def _ping_sweep_and_check_arp(
        self,
        mac_address: str,
        subnet: str,
        timeout: int = 30
    ) -> Optional[str]:
        """Perform ping sweep and check ARP table."""
        try:
            # Extract subnet base
            subnet_base = subnet.split('/')[0].rsplit('.', 1)[0]
            
            logger.info(f"Ping sweeping {subnet}...")
            
            # Ping all IPs in subnet (1-254)
            processes = []
            for i in range(1, 255):
                ip = f"{subnet_base}.{i}"
                proc = subprocess.Popen(
                    ["ping", "-c", "1", "-W", "1", ip],
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL
                )
                processes.append(proc)
                
                # Limit concurrent pings
                if len(processes) >= 50:
                    for p in processes:
                        p.wait()
                    processes = []
                    
                    # Check ARP table periodically
                    ip_found = self._check_arp_table(mac_address)
                    if ip_found:
                        return ip_found
            
            # Wait for remaining pings
            for p in processes:
                p.wait()
            
            # Final ARP check
            return self._check_arp_table(mac_address)
            
        except Exception as e:
            logger.error(f"Error during ping sweep: {e}")
            return None
    
    def _is_nmap_available(self) -> bool:
        """Check if nmap is installed."""
        try:
            result = subprocess.run(
                ["which", "nmap"],
                capture_output=True,
                timeout=5
            )
            return result.returncode == 0
        except Exception:
            return False
    
    def _nmap_scan(
        self,
        mac_address: str,
        subnet: Optional[str],
        timeout: int = 30
    ) -> Optional[str]:
        """Scan network using nmap."""
        if not subnet:
            return None
        
        try:
            logger.info(f"Running nmap scan on {subnet}...")
            result = subprocess.run(
                ["nmap", "-sn", "-PR", subnet],
                capture_output=True,
                text=True,
                timeout=timeout
            )
            
            if result.returncode == 0:
                # Parse nmap output for MAC address
                lines = result.stdout.split('\n')
                current_ip = None
                for line in lines:
                    if 'Nmap scan report for' in line:
                        parts = line.split()
                        current_ip = parts[-1].strip('()')
                    elif 'MAC Address:' in line and mac_address.lower() in line.lower():
                        return current_ip
        except Exception as e:
            logger.error(f"Error during nmap scan: {e}")
        
        return None


def main():
    """Test device re-discovery."""
    logging.basicConfig(
        level=logging.DEBUG,
        format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
    )
    
    rediscovery = DeviceRediscovery()
    
    # Test MAC lookup
    test_mac = "aa:bb:cc:dd:ee:ff"
    ip = rediscovery.find_device_by_mac(test_mac)
    print(f"Found IP for {test_mac}: {ip}")
    
    # Test hostname lookup
    test_hostname = "localhost"
    ip = rediscovery.find_device_by_hostname(test_hostname)
    print(f"Found IP for {test_hostname}: {ip}")


if __name__ == "__main__":
    main()
