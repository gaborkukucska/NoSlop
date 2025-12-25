# START OF FILE backend/data_import.py
"""
Data Import Service for NoSlop.

Provides functionality to import user data with conflict resolution.
Supports merge and replace modes for flexible data restoration.
"""

from sqlalchemy.orm import Session
from typing import Dict, Any, List, Tuple, Optional, Set
import logging
from datetime import datetime
import uuid

from database import (
    UserModel, UserCRUD, ProjectModel, ProjectCRUD, TaskModel, TaskCRUD,
    ChatSessionModel, ChatMessageModel
)

logger = logging.getLogger(__name__)


class UserDataImporter:
    """Service for importing user data with conflict resolution"""
    
    @staticmethod
    def import_user_data(
        db: Session,
        data: Dict[str, Any],
        target_user_id: Optional[str] = None,
        mode: str = "merge"
    ) -> Dict[str, Any]:
        """
        Import user data with options for handling conflicts.
        
        Args:
            db: Database session
            data: Import data dictionary (from export)
            target_user_id: Optional user ID to import into (None creates new user)
            mode: "merge" (add new data, keep existing) or "replace" (overwrite existing)
            
        Returns:
            Dict with import summary and results
        """
        logger.info(f"Starting user data import, mode={mode}, target_user={target_user_id}")
        
        # Validate import data
        is_valid, errors = UserDataImporter.validate_import_data(data)
        if not is_valid:
            logger.error(f"Invalid import data: {errors}")
            return {
                "status": "error",
                "message": "Invalid import data",
                "errors": errors
            }
        
        try:
            results = {
                "status": "success",
                "user_id": None,
                "imported": {
                    "user": False,
                    "sessions": 0,
                    "messages": 0,
                    "projects": 0,
                    "tasks": 0
                },
                "skipped": {
                    "sessions": 0,
                    "messages": 0,
                    "projects": 0,
                    "tasks": 0
                }
            }
            
            user_data = data["user"]
            
            # Handle user import
            if target_user_id:
                # Import into existing user
                user = UserCRUD.get(db, target_user_id)
                if not user:
                    raise ValueError(f"Target user not found: {target_user_id}")
                
                # Update user profile based on mode
                if mode == "replace":
                    # Replace all fields
                    updates = {k: v for k, v in user_data.items() 
                              if k not in ["id", "hashed_password", "created_at"]}
                    UserCRUD.update(db, target_user_id, updates)
                    logger.info(f"Replaced user profile for: {target_user_id}")
                else:
                    # Merge: only update fields that are None or empty
                    updates = {}
                    for key, value in user_data.items():
                        if key not in ["id", "hashed_password", "created_at"]:
                            current_value = getattr(user, key, None)
                            if current_value is None or current_value == "" or current_value == []:
                                updates[key] = value
                    if updates:
                        UserCRUD.update(db, target_user_id, updates)
                        logger.info(f"Merged user profile for: {target_user_id}")
                
                results["user_id"] = target_user_id
                results["imported"]["user"] = True
            else:
                # Create new user (not allowed for security reasons)
                # User creation should only happen through registration
                raise ValueError("Cannot create new user via import. Must import into existing user.")
            
            # Import chat sessions
            existing_session_ids = {s.id for s in db.query(ChatSessionModel.id).filter(
                ChatSessionModel.user_id == target_user_id
            ).all()}
            
            for session_data in data.get("chat_sessions", []):
                session_id = session_data.get("id")
                
                if session_id in existing_session_ids:
                    if mode == "replace":
                        # Delete and recreate
                        db.query(ChatSessionModel).filter(ChatSessionModel.id == session_id).delete()
                        session = ChatSessionModel(**{**session_data, "user_id": target_user_id})
                        db.add(session)
                        results["imported"]["sessions"] += 1
                    else:
                        # Skip existing
                        results["skipped"]["sessions"] += 1
                else:
                    # Add new session
                    session = ChatSessionModel(**{**session_data, "user_id": target_user_id})
                    db.add(session)
                    results["imported"]["sessions"] += 1
            
            # Import chat messages
            existing_message_ids = {m.id for m in db.query(ChatMessageModel.id).filter(
                ChatMessageModel.user_id == target_user_id
            ).all()}
            
            for message_data in data.get("chat_messages", []):
                message_id = message_data.get("id")
                
                if message_id in existing_message_ids:
                    if mode == "replace":
                        db.query(ChatMessageModel).filter(ChatMessageModel.id == message_id).delete()
                        message = ChatMessageModel(**{**message_data, "user_id": target_user_id})
                        db.add(message)
                        results["imported"]["messages"] += 1
                    else:
                        results["skipped"]["messages"] += 1
                else:
                    message = ChatMessageModel(**{**message_data, "user_id": target_user_id})
                    db.add(message)
                    results["imported"]["messages"] += 1
            
            # Import projects
            existing_project_ids = {p.id for p in db.query(ProjectModel.id).all()}
            
            for project_data in data.get("projects", []):
                project_id = project_data.get("id")
                
                # Update created_by in metadata
                if "meta_data" not in project_data:
                    project_data["meta_data"] = {}
                project_data["meta_data"]["created_by"] = target_user_id
                
                if project_id in existing_project_ids:
                    if mode == "replace":
                        db.query(ProjectModel).filter(ProjectModel.id == project_id).delete()
                        project = ProjectModel(**project_data)
                        db.add(project)
                        results["imported"]["projects"] += 1
                    else:
                        results["skipped"]["projects"] += 1
                else:
                    project = ProjectModel(**project_data)
                    db.add(project)
                    results["imported"]["projects"] += 1
            
            # Import tasks
            existing_task_ids = {t.id for t in db.query(TaskModel.id).all()}
            
            for task_data in data.get("tasks", []):
                task_id = task_data.get("id")
                
                if task_id in existing_task_ids:
                    if mode == "replace":
                        db.query(TaskModel).filter(TaskModel.id == task_id).delete()
                        task = TaskModel(**task_data)
                        db.add(task)
                        results["imported"]["tasks"] += 1
                    else:
                        results["skipped"]["tasks"] += 1
                else:
                    task = TaskModel(**task_data)
                    db.add(task)
                    results["imported"]["tasks"] += 1
            
            # Commit all changes
            db.commit()
            
            logger.info(f"Import completed successfully: {results}")
            return results
            
        except Exception as e:
            db.rollback()
            logger.error(f"Import failed: {e}", exc_info=True)
            return {
                "status": "error",
                "message": str(e),
                "errors": [str(e)]
            }
    
    @staticmethod
    def validate_import_data(data: Dict[str, Any]) -> Tuple[bool, List[str]]:
        """
        Validate import data structure and content.
        
        Args:
            data: Import data dictionary
            
        Returns:
            Tuple of (is_valid, list_of_errors)
        """
        errors = []
        
        # Check required top-level keys
        required_keys = ["user", "export_metadata"]
        for key in required_keys:
            if key not in data:
                errors.append(f"Missing required key: {key}")
        
        if errors:
            return False, errors
        
        # Validate user data
        user_data = data.get("user", {})
        required_user_fields = ["id", "username"]
        for field in required_user_fields:
            if field not in user_data:
                errors.append(f"Missing required user field: {field}")
        
        # Validate export metadata
        metadata = data.get("export_metadata", {})
        required_metadata_fields = ["exported_at", "user_id"]
        for field in required_metadata_fields:
            if field not in metadata:
                errors.append(f"Missing required metadata field: {field}")
        
        # Check data types
        if "chat_sessions" in data and not isinstance(data["chat_sessions"], list):
            errors.append("chat_sessions must be a list")
        
        if "chat_messages" in data and not isinstance(data["chat_messages"], list):
            errors.append("chat_messages must be a list")
        
        if "projects" in data and not isinstance(data["projects"], list):
            errors.append("projects must be a list")
        
        if "tasks" in data and not isinstance(data["tasks"], list):
            errors.append("tasks must be a list")
        
        return len(errors) == 0, errors
    
    @staticmethod
    def resolve_id_conflicts(data: Dict[str, Any], existing_ids: Set[str]) -> Dict[str, Any]:
        """
        Resolve ID conflicts by generating new UUIDs.
        
        Args:
            data: Import data with potential ID conflicts
            existing_ids: Set of existing IDs in the database
            
        Returns:
            Updated data with new IDs where conflicts existed
        """
        id_mapping = {}
        
        # Process each data type
        for data_type in ["chat_sessions", "chat_messages", "projects", "tasks"]:
            if data_type not in data:
                continue
            
            for item in data[data_type]:
                old_id = item.get("id")
                if old_id in existing_ids:
                    new_id = str(uuid.uuid4())
                    id_mapping[old_id] = new_id
                    item["id"] = new_id
                    logger.debug(f"Resolved ID conflict: {old_id} -> {new_id}")
        
        # Update foreign key references
        if "chat_messages" in data:
            for message in data["chat_messages"]:
                session_id = message.get("session_id")
                if session_id in id_mapping:
                    message["session_id"] = id_mapping[session_id]
        
        if "tasks" in data:
            for task in data["tasks"]:
                project_id = task.get("project_id")
                if project_id in id_mapping:
                    task["project_id"] = id_mapping[project_id]
                
                # Update dependencies
                dependencies = task.get("dependencies", [])
                task["dependencies"] = [
                    id_mapping.get(dep_id, dep_id) for dep_id in dependencies
                ]
        
        return data
