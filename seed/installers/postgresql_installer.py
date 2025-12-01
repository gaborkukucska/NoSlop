# START OF FILE seed/installers/postgresql_installer.py
"""
PostgreSQL Installer for NoSlop Seed.

Installs and configures PostgreSQL database on MASTER nodes.
"""

import time
from typing import Optional

from seed.installers.base_installer import BaseInstaller

class PostgreSQLInstaller(BaseInstaller):
    """
    Installs and configures PostgreSQL.
    """
    
    def __init__(self, device, ssh_manager):
        super().__init__(device, ssh_manager, "postgresql")
        self.db_name = "noslop"
        self.db_user = "noslop"
        self.db_password = "noslop_password" # In production this should come from config/env
        self.port = 5432

    def check_installed(self) -> bool:
        """Check if PostgreSQL is installed."""
        # Check for psql command
        code, _, _ = self.execute_remote("which psql")
        if code != 0:
            return False
            
        # Check service status
        if self.device.os_type.value == "linux":
            code, _, _ = self.execute_remote("systemctl is-active postgresql")
            return code == 0
        elif self.device.os_type.value == "macos":
            code, _, _ = self.execute_remote("brew services list | grep postgresql | grep started")
            return code == 0
            
        return False

    def install(self) -> bool:
        """Install PostgreSQL."""
        self.logger.info("Installing PostgreSQL...")
        
        pm = self.get_package_manager()
        if pm == "apt":
            # Update apt first
            self.execute_remote("apt-get update", timeout=300)
            return self.install_packages(["postgresql", "postgresql-contrib"])
        elif pm == "brew":
            return self.install_packages(["postgresql@14"]) # Install specific version
        elif pm == "yum":
            return self.install_packages(["postgresql-server", "postgresql-contrib"])
        else:
            self.logger.error(f"Unsupported package manager for PostgreSQL: {pm}")
            return False

    def configure(self) -> bool:
        """Configure PostgreSQL user and database."""
        self.logger.info("Configuring PostgreSQL...")
        
        # Initialize DB if needed (mostly for yum/linux)
        if self.get_package_manager() == "yum":
            self.execute_remote("postgresql-setup initdb")
            
        # Start service temporarily to configure
        self.start()
        time.sleep(5) # Wait for startup
        
        # Create user
        create_user_cmd = f"sudo -u postgres psql -c \"CREATE USER {self.db_user} WITH PASSWORD '{self.db_password}';\""
        code, _, err = self.execute_remote(create_user_cmd)
        if code != 0 and "already exists" not in err:
            self.logger.error(f"Failed to create user: {err}")
            return False
            
        # Create database
        create_db_cmd = f"sudo -u postgres psql -c \"CREATE DATABASE {self.db_name} OWNER {self.db_user};\""
        code, _, err = self.execute_remote(create_db_cmd)
        if code != 0 and "already exists" not in err:
            self.logger.error(f"Failed to create database: {err}")
            return False
            
        # Allow password auth (pg_hba.conf) - simplified for now
        # In a real scenario, we'd need to locate pg_hba.conf and edit it carefully
        # For now, we assume local connections are trusted or md5 is enabled
        
        return True

    def start(self) -> bool:
        """Start PostgreSQL service."""
        self.logger.info("Starting PostgreSQL...")
        
        if self.device.os_type.value == "linux":
            code, _, err = self.execute_remote("systemctl enable postgresql && systemctl start postgresql")
            return code == 0
        elif self.device.os_type.value == "macos":
            code, _, err = self.execute_remote("brew services start postgresql@14")
            return code == 0
            
        return False

    def verify(self) -> bool:
        """Verify PostgreSQL is running and accessible."""
        self.logger.info("Verifying PostgreSQL...")
        
        # Check connection using psql
        # We try to connect as the noslop user
        cmd = f"PGPASSWORD='{self.db_password}' psql -h localhost -U {self.db_user} -d {self.db_name} -c 'SELECT 1'"
        code, out, err = self.execute_remote(cmd)
        
        if code == 0 and "1" in out:
            self.logger.info("✓ PostgreSQL verification successful")
            return True
        else:
            self.logger.error(f"PostgreSQL verification failed: {err}")
            return False

    def rollback(self):
        """Rollback installation."""
        self.logger.info("Rolling back PostgreSQL installation...")
        # Stop service
        if self.device.os_type.value == "linux":
            self.execute_remote("systemctl stop postgresql")
        elif self.device.os_type.value == "macos":
            self.execute_remote("brew services stop postgresql@14")
            
        # We generally don't uninstall packages automatically to avoid breaking system
        # But we could drop the created user/db if we wanted to be thorough
