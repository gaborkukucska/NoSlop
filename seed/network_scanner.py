# START OF FILE seed/network_scanner.py
"""
Network Scanner Module for NoSlop Seed.

Discovers SSH-enabled devices on the local network.
"""

import logging
import socket
import subprocess
import ipaddress
from typing import List, Optional, Tuple
from dataclasses import dataclass
import concurrent.futures

logger = logging.getLogger(__name__)


@dataclass
class DiscoveredDevice:
    """Represents a discovered device on the network."""
    ip_address: str
    hostname: Optional[str] = None
    ssh_port: int = 22
    ssh_available: bool = False
    response_time_ms: float = 0.0


class NetworkScanner:
    """
    Scans local network for SSH-enabled devices.
    
    Provides both automatic network discovery and manual IP entry.
    """
    
    def __init__(self, timeout: int = 2):
        """
        Initialize network scanner.
        
        Args:
            timeout: Connection timeout in seconds
        """
        self.timeout = timeout
        logger.info(f"Network scanner initialized with {timeout}s timeout")
    
    def get_local_network(self) -> Optional[ipaddress.IPv4Network]:
        """
        Get the local network range based on current IP.
        
        Returns:
            IPv4Network object or None if detection fails
        """
        try:
            # Get local IP
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.connect(("8.8.8.8", 80))
            local_ip = s.getsockname()[0]
            s.close()
            
            # Assume /24 subnet (common for home networks)
            network = ipaddress.IPv4Network(f"{local_ip}/24", strict=False)
            logger.info(f"Detected local network: {network}")
            return network
            
        except Exception as e:
            logger.error(f"Error detecting local network: {e}")
            return None
    
    def check_ssh_port(self, ip: str, port: int = 22) -> Tuple[bool, float]:
        """
        Check if SSH port is open on a device.
        
        Args:
            ip: IP address to check
            port: SSH port (default 22)
            
        Returns:
            Tuple of (is_open, response_time_ms)
        """
        try:
            import time
            start_time = time.time()
            
            sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            sock.settimeout(self.timeout)
            result = sock.connect_ex((ip, port))
            sock.close()
            
            response_time = (time.time() - start_time) * 1000  # Convert to ms
            
            if result == 0:
                logger.debug(f"SSH port open on {ip}:{port} ({response_time:.1f}ms)")
                return True, response_time
            else:
                return False, 0.0
                
        except Exception as e:
            logger.debug(f"Error checking SSH on {ip}: {e}")
            return False, 0.0
    
    def get_hostname(self, ip: str) -> Optional[str]:
        """
        Resolve hostname for an IP address.
        
        Args:
            ip: IP address
            
        Returns:
            Hostname or None if resolution fails
        """
        try:
            hostname = socket.gethostbyaddr(ip)[0]
            logger.debug(f"Resolved {ip} to {hostname}")
            return hostname
        except Exception as e:
            logger.debug(f"Could not resolve hostname for {ip}: {e}")
            return None
    
    def scan_device(self, ip: str, port: int = 22) -> Optional[DiscoveredDevice]:
        """
        Scan a single device for SSH availability.
        
        Args:
            ip: IP address to scan
            port: SSH port
            
        Returns:
            DiscoveredDevice if SSH is available, None otherwise
        """
        ssh_available, response_time = self.check_ssh_port(ip, port)
        
        if ssh_available:
            hostname = self.get_hostname(ip)
            device = DiscoveredDevice(
                ip_address=ip,
                hostname=hostname,
                ssh_port=port,
                ssh_available=True,
                response_time_ms=response_time
            )
            logger.info(f"Discovered device: {ip} ({hostname}) - {response_time:.1f}ms")
            return device
        
        return None
    
    def scan_network(
        self, 
        network: Optional[ipaddress.IPv4Network] = None,
        max_workers: int = 50
    ) -> List[DiscoveredDevice]:
        """
        Scan network for SSH-enabled devices.
        
        Args:
            network: Network range to scan (auto-detected if None)
            max_workers: Maximum concurrent threads
            
        Returns:
            List of discovered devices
        """
        if network is None:
            network = self.get_local_network()
            if network is None:
                logger.error("Could not detect local network")
                return []
        
        logger.info(f"Scanning network {network} for SSH-enabled devices...")
        logger.info(f"This may take a few minutes depending on network size")
        
        devices = []
        total_hosts = sum(1 for _ in network.hosts())
        scanned_count = 0
        
        # Use ThreadPoolExecutor for concurrent scanning
        with concurrent.futures.ThreadPoolExecutor(max_workers=max_workers) as executor:
            # Submit all scan tasks
            future_to_ip = {
                executor.submit(self.scan_device, str(ip)): str(ip) 
                for ip in network.hosts()
            }
            
            # Collect results as they complete
            for future in concurrent.futures.as_completed(future_to_ip):
                scanned_count += 1
                ip = future_to_ip[future]
                
                try:
                    device = future.result()
                    if device:
                        devices.append(device)
                        logger.info(f"[{scanned_count}/{total_hosts}] Found: {ip}")
                    else:
                        logger.debug(f"[{scanned_count}/{total_hosts}] No SSH: {ip}")
                        
                except Exception as e:
                    logger.warning(f"Error scanning {ip}: {e}")
        
        logger.info(f"Scan complete. Found {len(devices)} SSH-enabled device(s)")
        return devices
    
    def scan_ip_list(self, ip_list: List[str], port: int = 22) -> List[DiscoveredDevice]:
        """
        Scan a specific list of IP addresses.
        
        Args:
            ip_list: List of IP addresses to scan
            port: SSH port
            
        Returns:
            List of discovered devices
        """
        logger.info(f"Scanning {len(ip_list)} specified IP address(es)...")
        
        devices = []
        for ip in ip_list:
            try:
                device = self.scan_device(ip, port)
                if device:
                    devices.append(device)
                else:
                    logger.warning(f"SSH not available on {ip}")
            except Exception as e:
                logger.error(f"Error scanning {ip}: {e}")
        
        logger.info(f"Found {len(devices)} SSH-enabled device(s) from list")
        return devices
    
    def validate_ip(self, ip: str) -> bool:
        """
        Validate IP address format.
        
        Args:
            ip: IP address string
            
        Returns:
            True if valid IPv4 address
        """
        try:
            ipaddress.IPv4Address(ip)
            return True
        except ValueError:
            return False
    
    def scan_range(self, start_ip: str, end_ip: str) -> List[DiscoveredDevice]:
        """
        Scan a range of IP addresses.
        
        Args:
            start_ip: Starting IP address
            end_ip: Ending IP address
            
        Returns:
            List of discovered devices
        """
        try:
            start = ipaddress.IPv4Address(start_ip)
            end = ipaddress.IPv4Address(end_ip)
            
            if start > end:
                logger.error("Start IP must be less than end IP")
                return []
            
            # Generate IP list
            ip_list = []
            current = int(start)
            end_int = int(end)
            
            while current <= end_int:
                ip_list.append(str(ipaddress.IPv4Address(current)))
                current += 1
            
            logger.info(f"Scanning IP range: {start_ip} - {end_ip} ({len(ip_list)} addresses)")
            return self.scan_ip_list(ip_list)
            
        except ValueError as e:
            logger.error(f"Invalid IP range: {e}")
            return []


def main():
    """Test network scanning."""
    logging.basicConfig(
        level=logging.INFO,
        format='%(asctime)s - %(levelname)s - %(message)s'
    )
    
    scanner = NetworkScanner(timeout=1)
    
    print("\n" + "="*60)
    print("NoSlop Network Scanner")
    print("="*60)
    
    # Get local network
    network = scanner.get_local_network()
    if network:
        print(f"\nLocal Network: {network}")
        print(f"Scanning {sum(1 for _ in network.hosts())} potential hosts...")
        
        # Scan network
        devices = scanner.scan_network(network)
        
        if devices:
            print(f"\n✓ Found {len(devices)} SSH-enabled device(s):\n")
            for i, device in enumerate(devices, 1):
                print(f"{i}. {device.ip_address}")
                if device.hostname:
                    print(f"   Hostname: {device.hostname}")
                print(f"   SSH Port: {device.ssh_port}")
                print(f"   Response: {device.response_time_ms:.1f}ms")
                print()
        else:
            print("\n✗ No SSH-enabled devices found on local network")
            print("   - Ensure SSH is enabled on target devices")
            print("   - Check firewall settings")
    else:
        print("\n✗ Could not detect local network")
    
    print("="*60)


if __name__ == "__main__":
    main()
