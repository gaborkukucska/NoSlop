#!/usr/bin/env python3
# START OF FILE seed/setup_ssl.py
"""
NoSlop SSL Setup Script

This script sets up SSL/TLS for an existing NoSlop deployment by:
1. Generating self-signed certificates for the master node
2. Installing and configuring Caddy reverse proxy
3. Updating environment configuration
4. Restarting services with SSL enabled

Usage:
    python3 -m seed.setup_ssl
    python3 -m seed.setup_ssl --ip 192.168.0.22 --domain app.noslop.me
"""

import argparse
import logging
import sys
import json
from pathlib import Path
from typing import List, Optional

# Add parent directory to path
sys.path.insert(0, str(Path(__file__).parent.parent))

from seed.manage_certs import CertificateManager
from seed.installers.caddy_installer import CaddyInstaller
from seed.ssh_manager import SSHManager
from shared.logging_utils import setup_module_logging

logger = logging.getLogger(__name__)


def load_deployment_plan() -> dict:
    """Load existing deployment plan"""
    plan_path = Path.home() / "NoSlop" / "deployment_plan.json"
    
    if not plan_path.exists():
        logger.error(f"Deployment plan not found: {plan_path}")
        logger.error("Please run the NoSlop installer first before setting up SSL")
        return None
    
    with open(plan_path, 'r') as f:
        return json.load(f)


def setup_ssl(
    master_ip: str,
    master_hostname: str,
    additional_ips: List[str] = None,
    domain: Optional[str] = None,
    backend_port: int = 8000,
    frontend_port: int = 3000
):
    """
    Setup SSL for NoSlop
    
    Args:
        master_ip: IP address of master node
        master_hostname: Hostname of master node
        additional_ips: Additional IP addresses to include in certificate
        domain: Optional domain name (e.g., app.noslop.me)
        backend_port: Backend API port (default: 8000)
        frontend_port: Frontend port (default: 3000)
    """
    logger.info("=" * 60)
    logger.info("NoSlop SSL/TLS Setup")
    logger.info("=" * 60)
    
    # Prepare certificate information
    cert_ips = [master_ip]
    if additional_ips:
        cert_ips.extend(additional_ips)
    
    cert_hostname = domain if domain else master_ip
    
    logger.info(f"\n📋 Configuration:")
    logger.info(f"   Master Node: {master_hostname} ({master_ip})")
    logger.info(f"   Certificate Hostname: {cert_hostname}")
    logger.info(f"   Certificate IPs: {', '.join(cert_ips)}")
    if domain:
        logger.info(f"   External Domain: {domain}")
    
    # Step 1: Generate certificates
    logger.info(f"\n🔐 Step 1: Generating SSL certificates...")
    cert_manager = CertificateManager()
    
    try:
        cert_path, key_path = cert_manager.generate_self_signed_cert(
            hostname=cert_hostname,
            ip_addresses=cert_ips,
            output_name="server",
            validity_days=365
        )
        logger.info(f"✅ Certificates generated successfully")
    except Exception as e:
        logger.error(f"❌ Failed to generate certificates: {e}")
        return False
    
    # Step 2: Install Caddy (if local) or via SSH (if remote)
    logger.info(f"\n🔧 Step 2: Installing Caddy reverse proxy...")
    
    # For local installation (master node is local machine)
    is_local = (master_ip == "127.0.0.1" or master_hostname == "localhost")
    
    if is_local:
        logger.info("Installing Caddy on local machine...")
        # For local, we need to use subprocess
        import subprocess
        
        try:
            # Install Caddy
            logger.info("Adding Caddy repository...")
            subprocess.run([
                "bash", "-c",
                "curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | sudo gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg"
            ], check=True)
            
            subprocess.run([
                "bash", "-c",
                "curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' | sudo tee /etc/apt/sources.list.d/caddy-stable.list"
            ], check=True)
            
            logger.info("Installing Caddy package...")
            subprocess.run(["sudo", "apt-get", "update"], check=True)
            subprocess.run(["sudo", "apt-get", "install", "-y", "caddy"], check=True)
            
            logger.info("✅ Caddy installed successfully (local)")
            
            # Generate Caddyfile
            logger.info("Generating Caddyfile configuration...")
            caddyfile_content = f"""# NoSlop Caddy Configuration
{{
    auto_https off
    admin off
}}

# Backend API (HTTPS)
https://{master_ip}:8443 {{
    tls {cert_path} {key_path}
    
    reverse_proxy localhost:{backend_port} {{
        header_up Upgrade {{{{http.request.header.Upgrade}}}}
        header_up Connection "upgrade"
    }}
    
    log {{
        output file /var/log/caddy/backend.log
        format console
    }}
}}

# Frontend (HTTPS)
https://{master_ip}:3443 {{
    tls {cert_path} {key_path}
    
    reverse_proxy localhost:{frontend_port}
    
    log {{
        output file /var/log/caddy/frontend.log
        format console
    }}
}}
"""
            
            # Write Caddyfile
            subprocess.run(["sudo", "mkdir", "-p", "/etc/caddy"], check=True)
            subprocess.run(["sudo", "mkdir", "-p", "/var/log/caddy"], check=True)
            
            with open("/tmp/Caddyfile", "w") as f:
                f.write(caddyfile_content)
            
            subprocess.run(["sudo", "mv", "/tmp/Caddyfile", "/etc/caddy/Caddyfile"], check=True)
            subprocess.run(["sudo", "chmod", "644", "/etc/caddy/Caddyfile"], check=True)
            
            # Restart Caddy
            logger.info("Restarting Caddy service...")
            subprocess.run(["sudo", "systemctl", "enable", "caddy"], check=True)
            subprocess.run(["sudo", "systemctl", "restart", "caddy"], check=True)
            
            logger.info("✅ Caddy configured and started")
            
        except subprocess.CalledProcessError as e:
            logger.error(f"❌ Failed to install/configure Caddy: {e}")
            return False
    
    else:
        # Remote installation via SSH
        logger.info("Connecting to remote master node...")
        ssh_manager = SSHManager()
        
        # Load SSH credentials from deployment plan
        plan = load_deployment_plan()
        if not plan:
            return False
        
        ssh_username = plan.get("ssh_username", "noslop")
        
        # Initialize connection
        if not ssh_manager.connect(master_hostname, username=ssh_username):
            logger.error(f"Failed to connect to {master_hostname}")
            return False
        
        # Install certificate to remote node
        if not cert_manager.install_certificate(
            cert_path, key_path, ssh_manager, master_hostname
        ):
            return False
        
        # Install Caddy
        caddy_installer = CaddyInstaller(
            ssh_manager=ssh_manager,
            node_hostname=master_hostname,
            node_ip=master_ip
        )
        
        if not caddy_installer.install(
            cert_path=cert_path,
            key_path=key_path,
            backend_port=backend_port,
            frontend_port=frontend_port
        ):
            return False
    
    # Step 3: Update .env configuration
    logger.info(f"\n⚙️  Step 3: Updating NoSlop configuration...")
    
    env_path = Path.home() / "NoSlop" / ".env"
    backend_url = f"https://{domain if domain else master_ip}:8443"
    frontend_url = f"https://{domain if domain else master_ip}:3443"
    
    # Read existing .env
    env_content = []
    if env_path.exists():
        with open(env_path, 'r') as f:
            env_content = f.readlines()
    
    # Update or add SSL settings
    ssl_settings = {
        'NOSLOP_SSL_ENABLED': 'true',
        'NOSLOP_SSL_CERT': cert_path,
        'NOSLOP_SSL_KEY': key_path,
        'NOSLOP_BACKEND_EXTERNAL_URL': backend_url,
        'NOSLOP_FRONTEND_EXTERNAL_URL': frontend_url,
        'NEXT_PUBLIC_BACKEND_EXTERNAL_URL': backend_url,
    }
    
    # Update existing keys or append new ones
    updated_keys = set()
    for i, line in enumerate(env_content):
        for key, value in ssl_settings.items():
            if line.startswith(f"{key}="):
                env_content[i] = f"{key}={value}\n"
                updated_keys.add(key)
                break
    
    # Append keys that weren't found
    for key, value in ssl_settings.items():
        if key not in updated_keys:
            env_content.append(f"{key}={value}\n")
    
    # Write updated .env
    with open(env_path, 'w') as f:
        f.writelines(env_content)
    
    logger.info(f"✅ Configuration updated: {env_path}")
    logger.info(f"   NOSLOP_BACKEND_EXTERNAL_URL={backend_url}")
    logger.info(f"   NOSLOP_FRONTEND_EXTERNAL_URL={frontend_url}")
    
    # Step 4: Rebuild frontend with new environment variables
    logger.info(f"\n🔨 Step 4: Rebuilding frontend with SSL configuration...")
    
    try:
        import subprocess
        frontend_dir = Path.home() / "NoSlop" / "frontend"
        
        # Create .env.local for Next.js
        env_local_content = f"""NEXT_PUBLIC_BACKEND_EXTERNAL_URL={backend_url}
NEXT_PUBLIC_API_URL={backend_url}
"""
        with open(frontend_dir / ".env.local", "w") as f:
            f.write(env_local_content)
        
        logger.info("Frontend .env.local updated")
        logger.info("\n⚠️  NOTE: You need to rebuild and restart the frontend manually:")
        logger.info(f"   cd {frontend_dir}")
        logger.info("   npm run build")
        logger.info("   sudo systemctl restart noslop-frontend")
        
    except Exception as e:
        logger.warning(f"Could not update frontend .env.local: {e}")
    
    # Summary
    logger.info("\n" + "=" * 60)
    logger.info("✅ SSL/TLS Setup Complete!")
    logger.info("=" * 60)
    logger.info(f"\n🔒 Your NoSlop instance is now configured for HTTPS:")
    logger.info(f"   Backend API:  {backend_url}")
    logger.info(f"   Frontend:     {frontend_url}")
    
    if domain:
        logger.info(f"\n🌐 Cloudflare Tunnel Configuration:")
        logger.info(f"   Point your tunnel to: https://{master_ip}:3443")
        logger.info(f"   Or use separate domains:")
        logger.info(f"     - app.{domain.split('.')[-2]}.{domain.split('.')[-1]} → https://{master_ip}:3443 (Frontend)")
        logger.info(f"     - api.{domain.split('.')[-2]}.{domain.split('.')[-1]} → https://{master_ip}:8443 (Backend)")
    
    logger.info(f"\n📝 Next Steps:")
    logger.info("   1. Rebuild frontend: cd ~/NoSlop/frontend && npm run build")
    logger.info("   2. Restart frontend: sudo systemctl restart noslop-frontend")
    logger.info("   3. Restart backend: sudo systemctl restart noslop-backend")
    logger.info("   4. Check Caddy: sudo systemctl status caddy")
    logger.info(f"   5. Test HTTPS: curl -k {backend_url}/api/health")
    
    logger.info(f"\n⚠️  Certificate Warning:")
    logger.info("   Your browser will show a security warning for self-signed certificates.")
    logger.info("   You can:")
    logger.info("   - Accept the risk and continue (for home/dev use)")
    logger.info("   - Import the certificate to your device's trust store")
    logger.info(f"   - Certificate location: {cert_path}")
    
    return True


def main():
    """Main entry point"""
    parser = argparse.ArgumentParser(
        description="NoSlop SSL/TLS Setup",
        formatter_class=argparse.RawDescriptionHelpFormatter
    )
    
    parser.add_argument(
        "--ip",
        help="Master node IP address (auto-detected from deployment plan if not provided)"
    )
    parser.add_argument(
        "--hostname",
        help="Master node hostname (auto-detected from deployment plan if not provided)"
    )
    parser.add_argument(
        "--domain",
        help="External domain name (e.g., app.noslop.me)"
    )
    parser.add_argument(
        "--additional-ip",
        action="append",
        dest="additional_ips",
        help="Additional IP addresses to include in certificate (can be used multiple times)"
    )
    parser.add_argument(
        "--backend-port",
        type=int,
        default=8000,
        help="Backend API port (default: 8000)"
    )
    parser.add_argument(
        "--frontend-port",
        type=int,
        default=3000,
        help="Frontend port (default: 3000)"
    )
    
    args = parser.parse_args()
    
    # Setup logging
    setup_module_logging("ssl_setup")
    
    # Auto-detect master node from deployment plan
    if not args.ip or not args.hostname:
        logger.info("Loading deployment plan...")
        plan = load_deployment_plan()
        
        if not plan:
            logger.error("Could not load deployment plan. Please specify --ip and --hostname manually.")
            return 1
        
        # Find master node
        for node_hostname, node_config in plan.get("nodes", {}).items():
            if "master" in node_config.get("roles", []):
                if not args.ip:
                    args.ip = node_config.get("ip")
                if not args.hostname:
                    args.hostname = node_hostname
                break
        
        if not args.ip or not args.hostname:
            logger.error("Could not determine master node from deployment plan.")
            logger.error("Please specify --ip and --hostname manually.")
            return 1
        
        logger.info(f"Detected master node: {args.hostname} ({args.ip})")
    
    # Run SSL setup
    success = setup_ssl(
        master_ip=args.ip,
        master_hostname=args.hostname,
        additional_ips=args.additional_ips,
        domain=args.domain,
        backend_port=args.backend_port,
        frontend_port=args.frontend_port
    )
    
    return 0 if success else 1


if __name__ == "__main__":
    sys.exit(main())
