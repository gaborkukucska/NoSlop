"""
Secure Credential Store for NoSlop.

Persists device credentials to ~/.noslop/credentials.json with restricted permissions.
Allows management commands to function without repeated prompts or global passwords.
"""

import json
import logging
import os
import stat
from pathlib import Path
from typing import Dict, Optional, Tuple

logger = logging.getLogger(__name__)

class CredentialStore:
    """
    Manages secure persistent storage of device credentials.
    """
    
    def __init__(self):
        self.store_dir = Path.home() / ".noslop"
        self.store_file = self.store_dir / "credentials.json"
        
        # Ensure directory exists
        if not self.store_dir.exists():
            self.store_dir.mkdir(parents=True, exist_ok=True)
            
        self.credentials: Dict[str, Dict[str, str]] = {}
        self._load()
    
    def _load(self):
        """Load credentials from disk."""
        if not self.store_file.exists():
            return
            
        try:
            # Check permissions
            mode = self.store_file.stat().st_mode
            if mode & stat.S_IRWXG or mode & stat.S_IRWXO:
                logger.warning(f"Insecure permissions on {self.store_file}. Fixing...")
                self.store_file.chmod(0o600)
                
            with open(self.store_file, 'r') as f:
                self.credentials = json.load(f)
                
        except Exception as e:
            logger.error(f"Failed to load credentials: {e}")
            
    def _save(self):
        """Save credentials to disk with restricted permissions."""
        try:
            # Atomic write pattern not strictly necessary for this scale, 
            # but direct write is fine if we set perms first.
            
            # Ensure file exists and has correct permissions BEFORE writing sensitive data
            if not self.store_file.exists():
                self.store_file.touch(mode=0o600)
            else:
                self.store_file.chmod(0o600)
                
            with open(self.store_file, 'w') as f:
                json.dump(self.credentials, f, indent=2)
                
        except Exception as e:
            logger.error(f"Failed to save credentials: {e}")
    
    def save_credential(self, ip_address: str, username: str, password: str, port: int = 22):
        """
        Save or update a credential.
        
        Args:
            ip_address: Target IP
            username: SSH username
            password: Sudo/SSH password
            port: SSH port
        """
        self.credentials[ip_address] = {
            "username": username,
            "password": password,
            "port": port
        }
        self._save()
        logger.debug(f"Saved credentials for {ip_address}")
        
    def get_credential(self, ip_address: str) -> Optional[Dict[str, str]]:
        """
        Retrieve credential for an IP.
        
        Returns:
            Dict with username, password, port, or None.
        """
        return self.credentials.get(ip_address)
        
    def get_password(self, ip_address: str) -> Optional[str]:
        """Helper to get just the password."""
        cred = self.credentials.get(ip_address)
        return cred.get("password") if cred else None
