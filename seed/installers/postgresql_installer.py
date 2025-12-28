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
    
    def __init__(self, device, ssh_manager, username="root", password=None):
        super().__init__(device, ssh_manager, "postgresql", username=username, password=password)
        self.db_name = "noslop"
        self.db_user = "noslop"
        self.db_password = "noslop" # Matches deployer.py config
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
            self.execute_remote("sudo apt-get update", timeout=300)
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
        # We use sudo -u postgres to run psql.
        # We also need to ensure the user has LOGIN privilege.
        create_user_cmd = f"sudo -u postgres psql -c \"CREATE USER {self.db_user} WITH PASSWORD '{self.db_password}' LOGIN;\""
        code, _, err = self.execute_remote(create_user_cmd)
        if code != 0 and "already exists" not in err:
            self.logger.error(f"Failed to create user: {err}")
            return False
            
        # If user exists, update password to ensure it matches
        if "already exists" in err:
            update_pass_cmd = f"sudo -u postgres psql -c \"ALTER USER {self.db_user} WITH PASSWORD '{self.db_password}';\""
            self.execute_remote(update_pass_cmd)
            
        # Create database
        create_db_cmd = f"sudo -u postgres psql -c \"CREATE DATABASE {self.db_name} OWNER {self.db_user};\""
        code, _, err = self.execute_remote(create_db_cmd)
        if code != 0 and "already exists" not in err:
            self.logger.error(f"Failed to create database: {err}")
            return False
            
        # Allow remote connections
        if not self._allow_remote_connections():
             self.logger.warning("Failed to configure remote connections for PostgreSQL")
        
        return True

    def _allow_remote_connections(self) -> bool:
        """
        Configure PostgreSQL to listen on all interfaces and allow password auth.
        """
        self.logger.info("Configuring PostgreSQL for remote access...")
        
        # 1. Find config directory
        code, out, err = self.execute_remote("sudo -u postgres psql -t -P format=unaligned -c 'SHOW config_file;'")
        if code != 0 or not out.strip():
            self.logger.error(f"Could not find postgresql.conf: {err}")
            return False
            
        conf_path = out.strip()
        hba_path = conf_path.replace("postgresql.conf", "pg_hba.conf")
        
        self.logger.info(f"Found config at: {conf_path}")
        
        # 2. Update postgresql.conf (listen_addresses)
        # Check if already listening on *
        code, out, _ = self.execute_remote(f"sudo grep \"listen_addresses = '*'\" {conf_path}")
        if code != 0:
            # Append to file
            self.logger.info("Setting listen_addresses = '*'...")
            self.execute_remote(f"echo \"listen_addresses = '*'\" | sudo tee -a {conf_path}")
        
        # 3. Update pg_hba.conf
        # Allow md5 auth for the user from any IP (simplified for usability)
        # Ideally we limit to local subnet
        entry = f"host    {self.db_name}    {self.db_user}    0.0.0.0/0    md5"
        
        code, out, _ = self.execute_remote(f"sudo grep \"{entry}\" {hba_path}")
        if code != 0:
            self.logger.info(f"Adding pg_hba.conf entry: {entry}")
            self.execute_remote(f"echo \"{entry}\" | sudo tee -a {hba_path}")
            
        # 4. Restart service to apply changes
        return self.start()

    def start(self) -> bool:
        """Start PostgreSQL service."""
        self.logger.info("Starting PostgreSQL...")
        
        if self.device.os_type.value == "linux":
            code, _, err = self.execute_remote("sudo systemctl enable postgresql && sudo systemctl start postgresql")
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
            self.execute_remote("sudo systemctl stop postgresql")
        elif self.device.os_type.value == "macos":
            self.execute_remote("brew services stop postgresql@14")
            
        # We generally don't uninstall packages automatically to avoid breaking system
        # But we could drop the created user/db if we wanted to be thorough
