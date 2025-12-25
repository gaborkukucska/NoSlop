# START OF FILE backend/data_export.py
"""
Data Export Service for NoSlop.

Provides functionality to export user data in portable formats.
Supports both full database exports and individual user exports.
"""

from sqlalchemy.orm import Session
from typing import Dict, Any, Optional
from io import BytesIO
import json
import logging
from datetime import datetime

from database import UserCRUD, UserModel, ProjectModel, TaskModel, ChatSessionModel, ChatMessageModel

logger = logging.getLogger(__name__)


class UserDataExporter:
    """Service for exporting user data in portable JSON format"""
    
    @staticmethod
    def export_user_complete(db: Session, user_id: str) -> Optional[Dict[str, Any]]:
        """
        Export all data for a single user including:
        - User profile (all fields)
        - All chat sessions
        - All chat messages
        - All projects (created by user)
        - All tasks (for user's projects)
        - Personality settings
        - Preferences and custom data
        
        Args:
            db: Database session
            user_id: User ID to export
            
        Returns:
            Dict with complete user data or None if user not found
        """
        logger.info(f"Exporting complete data for user: {user_id}")
        
        # Use the enhanced CRUD method
        data = UserCRUD.get_user_complete_data(db, user_id)
        
        if not data:
            logger.warning(f"User not found for export: {user_id}")
            return None
        
        # Add export version for future compatibility
        data["export_version"] = "1.0"
        data["export_metadata"]["noslop_version"] = "0.04"
        
        logger.info(f"Export completed for user: {user_id}")
        return data
    
    @staticmethod
    def create_export_file(data: Dict[str, Any], format: str = "json") -> BytesIO:
        """
        Create a downloadable file from export data.
        
        Args:
            data: Export data dictionary
            format: Output format (currently only 'json' supported)
            
        Returns:
            BytesIO buffer containing the file data
        """
        buffer = BytesIO()
        
        if format == "json":
            # Pretty print JSON for readability
            json_str = json.dumps(data, indent=2, ensure_ascii=False)
            buffer.write(json_str.encode('utf-8'))
        else:
            raise ValueError(f"Unsupported format: {format}")
        
        buffer.seek(0)  # Reset to beginning
        return buffer
    
    @staticmethod
    def validate_export_data(data: Dict[str, Any]) -> bool:
        """
        Validate export data structure.
        
        Args:
            data: Export data to validate
            
        Returns:
            True if valid, False otherwise
        """
        required_keys = [
            "user",
            "chat_sessions",
            "chat_messages",
            "projects",
            "tasks",
            "export_metadata"
        ]
        
        for key in required_keys:
            if key not in data:
                logger.error(f"Missing required key in export data: {key}")
                return False
        
        # Check user data has required fields
        if "username" not in data["user"] or "id" not in data["user"]:
            logger.error("User data missing required fields")
            return False
        
        # Check export metadata
        metadata = data.get("export_metadata", {})
        if "exported_at" not in metadata or "user_id" not in metadata:
            logger.error("Export metadata missing required fields")
            return False
        
        return True
    
    @staticmethod
    def get_export_filename(username: str) -> str:
        """
        Generate a filename for the export file.
        
        Args:
            username: Username of the exported user
            
        Returns:
            Filename string
        """
        timestamp = datetime.utcnow().strftime("%Y%m%d_%H%M%S")
        return f"noslop_user_export_{username}_{timestamp}.json"
