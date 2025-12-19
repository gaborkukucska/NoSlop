# START OF FILE seed/manage_certs.py
"""
Certificate Management for NoSlop

Handles SSL/TLS certificate generation, validation, and deployment for NoSlop installations.
Supports self-signed certificates for local development and Let's Encrypt for production.
"""

import os
import subprocess
import logging
from datetime import datetime, timedelta
from pathlib import Path
from typing import List, Optional, Tuple
from dataclasses import dataclass

logger = logging.getLogger(__name__)


@dataclass
class CertificateInfo:
    """Information about a certificate"""
    cert_path: str
    key_path: str
    hostname: str
    ip_addresses: List[str]
    valid_from: datetime
    valid_until: datetime
    is_valid: bool


class CertificateManager:
    """Manages SSL/TLS certificates for NoSlop"""
    
    DEFAULT_CERT_DIR = "/etc/noslop/certs"
    DEFAULT_VALIDITY_DAYS = 365
    
    def __init__(self, cert_dir: str = DEFAULT_CERT_DIR):
        self.cert_dir = Path(cert_dir)
        self.cert_dir.mkdir(parents=True, exist_ok=True)
    
    def generate_self_signed_cert(
        self,
        hostname: str,
        ip_addresses: List[str],
        output_name: str = "server",
        validity_days: int = DEFAULT_VALIDITY_DAYS
    ) -> Tuple[str, str]:
        """
        Generate a self-signed certificate with Subject Alternative Names (SANs)
        
        Args:
            hostname: Primary hostname for the certificate
            ip_addresses: List of IP addresses to include in SANs
            output_name: Base name for cert/key files (default: "server")
            validity_days: Certificate validity period in days
            
        Returns:
            Tuple of (cert_path, key_path)
        """
        logger.info(f"Generating self-signed certificate for {hostname}")
        
        cert_path = self.cert_dir / f"{output_name}.crt"
        key_path = self.cert_dir / f"{output_name}.key"
        
        # Build Subject Alternative Names (SANs)
        san_entries = [f"DNS:{hostname}"]
        for ip in ip_addresses:
            san_entries.append(f"IP:{ip}")
        san_string = ",".join(san_entries)
        
        # Generate private key
        logger.debug("Generating RSA private key (2048 bits)")
        subprocess.run([
            "openssl", "genrsa",
            "-out", str(key_path),
            "2048"
        ], check=True, capture_output=True)
        
        # Set proper permissions on private key
        os.chmod(key_path, 0o600)
        
        # Create OpenSSL config for SANs
        config_content = f"""
[req]
default_bits = 2048
prompt = no
default_md = sha256
distinguished_name = dn
req_extensions = v3_req

[dn]
C = US
ST = State
L = City
O = NoSlop
OU = NoSlop Self-Signed
CN = {hostname}

[v3_req]
subjectAltName = {san_string}
keyUsage = digitalSignature, keyEncipherment
extendedKeyUsage = serverAuth
"""
        
        config_path = self.cert_dir / f"{output_name}_openssl.cnf"
        config_path.write_text(config_content)
        
        # Generate certificate signing request (CSR)
        csr_path = self.cert_dir / f"{output_name}.csr"
        logger.debug("Generating certificate signing request")
        subprocess.run([
            "openssl", "req",
            "-new",
            "-key", str(key_path),
            "-out", str(csr_path),
            "-config", str(config_path)
        ], check=True, capture_output=True)
        
        # Self-sign the certificate
        logger.debug(f"Self-signing certificate (valid for {validity_days} days)")
        subprocess.run([
            "openssl", "x509",
            "-req",
            "-in", str(csr_path),
            "-signkey", str(key_path),
            "-out", str(cert_path),
            "-days", str(validity_days),
            "-extensions", "v3_req",
            "-extfile", str(config_path)
        ], check=True, capture_output=True)
        
        # Set proper permissions
        os.chmod(cert_path, 0o644)
        
        # Cleanup temporary files
        csr_path.unlink()
        config_path.unlink()
        
        logger.info(f"✅ Certificate generated successfully")
        logger.info(f"   Certificate: {cert_path}")
        logger.info(f"   Private Key: {key_path}")
        logger.info(f"   Hostname: {hostname}")
        logger.info(f"   SANs: {san_string}")
        
        return str(cert_path), str(key_path)
    
    def validate_certificate(self, cert_path: str) -> CertificateInfo:
        """
        Validate a certificate and extract information
        
        Args:
            cert_path: Path to certificate file
            
        Returns:
            CertificateInfo object with certificate details
        """
        logger.debug(f"Validating certificate: {cert_path}")
        
        if not Path(cert_path).exists():
            raise FileNotFoundError(f"Certificate not found: {cert_path}")
        
        # Get certificate details
        result = subprocess.run([
            "openssl", "x509",
            "-in", cert_path,
            "-noout",
            "-subject",
            "-dates",
            "-ext", "subjectAltName"
        ], capture_output=True, text=True, check=True)
        
        output = result.stdout
        
        # Parse certificate information
        hostname = ""
        ip_addresses = []
        valid_from = None
        valid_until = None
        
        for line in output.split('\n'):
            line = line.strip()
            
            if line.startswith("subject="):
                # Extract CN (Common Name)
                cn_part = [p for p in line.split(',') if 'CN' in p]
                if cn_part:
                    hostname = cn_part[0].split('=')[-1].strip()
            
            elif line.startswith("notBefore="):
                date_str = line.split('=', 1)[1]
                valid_from = datetime.strptime(date_str, "%b %d %H:%M:%S %Y %Z")
            
            elif line.startswith("notAfter="):
                date_str = line.split('=', 1)[1]
                valid_until = datetime.strptime(date_str, "%b %d %H:%M:%S %Y %Z")
            
            elif "IP Address:" in line:
                # Extract IP addresses from SANs
                parts = line.split(',')
                for part in parts:
                    if 'IP Address:' in part:
                        ip = part.split(':', 1)[1].strip()
                        ip_addresses.append(ip)
        
        # Check if certificate is currently valid
        now = datetime.now()
        is_valid = (valid_from and valid_until and 
                   valid_from <= now <= valid_until)
        
        # Determine key path (assume same directory, same name)
        cert_file = Path(cert_path)
        key_path = str(cert_file.parent / f"{cert_file.stem}.key")
        
        cert_info = CertificateInfo(
            cert_path=cert_path,
            key_path=key_path,
            hostname=hostname,
            ip_addresses=ip_addresses,
            valid_from=valid_from,
            valid_until=valid_until,
            is_valid=is_valid
        )
        
        logger.info(f"Certificate validation complete:")
        logger.info(f"   Valid: {is_valid}")
        logger.info(f"   Hostname: {hostname}")
        logger.info(f"   Valid from: {valid_from}")
        logger.info(f"   Valid until: {valid_until}")
        logger.info(f"   IP Addresses: {', '.join(ip_addresses)}")
        
        return cert_info
    
    def install_certificate(
        self,
        cert_path: str,
        key_path: str,
        ssh_manager,
        node_hostname: str,
        remote_cert_dir: str = DEFAULT_CERT_DIR
    ) -> bool:
        """
        Install certificate on a remote node via SSH
        
        Args:
            cert_path: Local path to certificate file
            key_path: Local path to private key file
            ssh_manager: SSHManager instance for remote operations
            node_hostname: Target node hostname
            remote_cert_dir: Remote directory for certificates
            
        Returns:
            True if installation successful
        """
        logger.info(f"Installing certificate on {node_hostname}")
        
        try:
            # Create remote certificate directory
            ssh_manager.run_command(
                node_hostname,
                f"sudo mkdir -p {remote_cert_dir}",
                timeout=30
            )
            
            # Copy certificate file
            ssh_manager.transfer_file(
                cert_path,
                node_hostname,
                f"{remote_cert_dir}/server.crt"
            )
            
            # Copy private key file
            ssh_manager.transfer_file(
                key_path,
                node_hostname,
                f"{remote_cert_dir}/server.key"
            )
            
            # Set proper permissions
            ssh_manager.run_command(
                node_hostname,
                f"sudo chmod 644 {remote_cert_dir}/server.crt",
                timeout=30
            )
            ssh_manager.run_command(
                node_hostname,
                f"sudo chmod 600 {remote_cert_dir}/server.key",
                timeout=30
            )
            
            logger.info(f"✅ Certificate installed successfully on {node_hostname}")
            return True
            
        except Exception as e:
            logger.error(f"Failed to install certificate on {node_hostname}: {e}")
            return False
    
    def needs_renewal(self, cert_path: str, days_before_expiry: int = 30) -> bool:
        """
        Check if a certificate needs renewal
        
        Args:
            cert_path: Path to certificate file
            days_before_expiry: Renew if expiring within this many days
            
        Returns:
            True if certificate needs renewal
        """
        try:
            cert_info = self.validate_certificate(cert_path)
            
            if not cert_info.is_valid:
                logger.warning(f"Certificate is expired or not yet valid")
                return True
            
            days_remaining = (cert_info.valid_until - datetime.now()).days
            
            if days_remaining <= days_before_expiry:
                logger.warning(f"Certificate expires in {days_remaining} days (threshold: {days_before_expiry})")
                return True
            
            logger.info(f"Certificate is valid for {days_remaining} more days")
            return False
            
        except Exception as e:
            logger.error(f"Error checking certificate renewal status: {e}")
            return True  # Err on the side of caution
    
    def get_certificate_fingerprint(self, cert_path: str) -> str:
        """
        Get SHA256 fingerprint of a certificate
        
        Args:
            cert_path: Path to certificate file
            
        Returns:
            SHA256 fingerprint as hex string
        """
        result = subprocess.run([
            "openssl", "x509",
            "-in", cert_path,
            "-noout",
            "-fingerprint",
            "-sha256"
        ], capture_output=True, text=True, check=True)
        
        # Extract fingerprint from output (format: "SHA256 Fingerprint=XX:XX:...")
        fingerprint = result.stdout.split('=', 1)[1].strip()
        return fingerprint


def main():
    """CLI interface for certificate management"""
    import argparse
    
    parser = argparse.ArgumentParser(description="NoSlop Certificate Management")
    parser.add_argument("--generate", action="store_true", help="Generate new certificate")
    parser.add_argument("--validate", metavar="CERT_PATH", help="Validate existing certificate")
    parser.add_argument("--hostname", default="noslop.local", help="Hostname for certificate")
    parser.add_argument("--ip", action="append", help="IP address to include in SANs (can be used multiple times)")
    parser.add_argument("--cert-dir", default=CertificateManager.DEFAULT_CERT_DIR, help="Certificate directory")
    parser.add_argument("--days", type=int, default=365, help="Certificate validity in days")
    parser.add_argument("--output-name", default="server", help="Base name for certificate files")
    
    args = parser.parse_args()
    
    # Setup logging
    logging.basicConfig(
        level=logging.INFO,
        format='%(asctime)s - %(levelname)s - %(message)s'
    )
    
    manager = CertificateManager(cert_dir=args.cert_dir)
    
    if args.generate:
        ip_addresses = args.ip or ["127.0.0.1"]
        cert_path, key_path = manager.generate_self_signed_cert(
            hostname=args.hostname,
            ip_addresses=ip_addresses,
            output_name=args.output_name,
            validity_days=args.days
        )
        print(f"\n✅ Certificate generated successfully!")
        print(f"Certificate: {cert_path}")
        print(f"Private Key: {key_path}")
        
    elif args.validate:
        cert_info = manager.validate_certificate(args.validate)
        print(f"\nCertificate Information:")
        print(f"  Valid: {cert_info.is_valid}")
        print(f"  Hostname: {cert_info.hostname}")
        print(f"  IP Addresses: {', '.join(cert_info.ip_addresses)}")
        print(f"  Valid From: {cert_info.valid_from}")
        print(f"  Valid Until: {cert_info.valid_until}")
        
        if manager.needs_renewal(args.validate):
            print(f"\n⚠️  Certificate needs renewal!")
        
        fingerprint = manager.get_certificate_fingerprint(args.validate)
        print(f"  Fingerprint: {fingerprint}")
    
    else:
        parser.print_help()


if __name__ == "__main__":
    main()
