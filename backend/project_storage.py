# START OF FILE backend/project_storage.py
"""
Project Storage Manager for NoSlop.

Manages project folder structure and file organization.
All project outputs are organized in a consistent folder structure.
"""

import os
import json
import shutil
from pathlib import Path
from typing import Dict, Any, Optional, List
from datetime import datetime
import logging

logger = logging.getLogger(__name__)


class ProjectStorage:
    """
    Manages project folder structure and file organization.
    
    Project Folder Structure:
    projects/{project_id}/
        ├── metadata.json
        ├── workflows/
        ├── prompts/
        ├── generated/
        │   ├── images/
        │   ├── videos/
        │   └── audio/
        ├── intermediate/
        └── final/
    """
    
    def __init__(self, base_path: str = None):
        """
        Initialize project storage manager.
        
        Args:
            base_path: Base path for project storage (from config)
        """
        from config import settings
        self.base_path = Path(base_path or settings.project_storage_path)
        self.base_path.mkdir(parents=True, exist_ok=True)
        
        self.folder_structure = {
            "workflows": "workflows",
            "prompts": "prompts",
            "generated": "generated",
            "generated/images": "generated/images",
            "generated/videos": "generated/videos",
            "generated/audio": "generated/audio",
            "intermediate": "intermediate",
            "intermediate/latents": "intermediate/latents",
            "intermediate/masks": "intermediate/masks",
            "intermediate/frames": "intermediate/frames",
            "final": "final"
        }
    
    def create_project_structure(self, project_id: str) -> Path:
        """
        Create folder structure for a new project.
        
        Args:
            project_id: Unique project identifier
            
        Returns:
            Path to project folder
        """
        project_path = self.base_path / project_id
        
        if project_path.exists():
            logger.warning(f"Project folder already exists: {project_path}")
            return project_path
        
        logger.info(f"Creating project structure for {project_id}")
        
        # Create all folders
        for folder in self.folder_structure.values():
            folder_path = project_path / folder
            folder_path.mkdir(parents=True, exist_ok=True)
            logger.debug(f"Created folder: {folder_path}")
        
        # Create metadata file
        metadata = {
            "project_id": project_id,
            "created_at": datetime.utcnow().isoformat(),
            "folder_structure_version": "1.0",
            "storage_stats": {
                "workflows_count": 0,
                "media_count": 0,
                "storage_size_mb": 0.0
            }
        }
        
        metadata_path = project_path / "metadata.json"
        with open(metadata_path, 'w') as f:
            json.dump(metadata, f, indent=2)
        
        logger.info(f"✓ Project structure created: {project_path}")
        return project_path
    
    def get_project_path(self, project_id: str) -> Path:
        """Get path to project folder."""
        return self.base_path / project_id
    
    def save_workflow(self, project_id: str, workflow: Dict[str, Any], 
                     task_id: str, version: int = 1) -> str:
        """
        Save ComfyUI workflow to project folder.
        
        Args:
            project_id: Project identifier
            workflow: Workflow JSON data
            task_id: Task identifier
            version: Workflow version number
            
        Returns:
            Relative path to saved workflow file
        """
        project_path = self.get_project_path(project_id)
        workflows_dir = project_path / "workflows"
        workflows_dir.mkdir(parents=True, exist_ok=True)
        
        filename = f"{task_id}_v{version}.json"
        workflow_path = workflows_dir / filename
        
        # Add metadata to workflow
        workflow_with_meta = {
            "workflow": workflow,
            "metadata": {
                "task_id": task_id,
                "version": version,
                "saved_at": datetime.utcnow().isoformat()
            }
        }
        
        with open(workflow_path, 'w') as f:
            json.dump(workflow_with_meta, f, indent=2)
        
        logger.info(f"Saved workflow: {workflow_path}")
        return f"workflows/{filename}"
    
    def load_workflow(self, project_id: str, workflow_path: str) -> Dict[str, Any]:
        """
        Load workflow from project folder.
        
        Args:
            project_id: Project identifier
            workflow_path: Relative path to workflow file
            
        Returns:
            Workflow JSON data
        """
        project_path = self.get_project_path(project_id)
        full_path = project_path / workflow_path
        
        if not full_path.exists():
            raise FileNotFoundError(f"Workflow not found: {full_path}")
        
        with open(full_path, 'r') as f:
            data = json.load(f)
        
        # Return just the workflow, not the metadata wrapper
        return data.get("workflow", data)
    
    def save_prompt(self, project_id: str, prompt_data: Dict[str, Any], 
                   task_id: str) -> str:
        """
        Save generated prompt to project folder.
        
        Args:
            project_id: Project identifier
            prompt_data: Prompt data (positive, negative, parameters)
            task_id: Task identifier
            
        Returns:
            Relative path to saved prompt file
        """
        project_path = self.get_project_path(project_id)
        prompts_dir = project_path / "prompts"
        prompts_dir.mkdir(parents=True, exist_ok=True)
        
        filename = f"{task_id}_prompt.json"
        prompt_path = prompts_dir / filename
        
        # Add metadata
        prompt_with_meta = {
            **prompt_data,
            "metadata": {
                "task_id": task_id,
                "saved_at": datetime.utcnow().isoformat()
            }
        }
        
        with open(prompt_path, 'w') as f:
            json.dump(prompt_with_meta, f, indent=2)
        
        logger.info(f"Saved prompt: {prompt_path}")
        return f"prompts/{filename}"
    
    def save_generated_media(self, project_id: str, file_data: bytes, 
                            task_id: str, media_type: str, 
                            extension: str = "png") -> str:
        """
        Save generated media file to project folder.
        
        Args:
            project_id: Project identifier
            file_data: Binary file data
            task_id: Task identifier
            media_type: Type of media (image, video, audio)
            extension: File extension
            
        Returns:
            Relative path to saved media file
        """
        project_path = self.get_project_path(project_id)
        media_dir = project_path / "generated" / f"{media_type}s"
        media_dir.mkdir(parents=True, exist_ok=True)
        
        # Generate unique filename with timestamp
        timestamp = datetime.utcnow().strftime("%Y%m%d_%H%M%S")
        filename = f"{task_id}_{timestamp}.{extension}"
        media_path = media_dir / filename
        
        # Save file
        with open(media_path, 'wb') as f:
            f.write(file_data)
        
        logger.info(f"Saved {media_type}: {media_path}")
        return f"generated/{media_type}s/{filename}"
    
    def save_metadata(self, project_id: str, task_id: str, 
                     metadata: Dict[str, Any]) -> str:
        """
        Save generation metadata alongside media.
        
        Args:
            project_id: Project identifier
            task_id: Task identifier
            metadata: Metadata to save (workflow, prompt, seed, parameters)
            
        Returns:
            Relative path to metadata file
        """
        project_path = self.get_project_path(project_id)
        generated_dir = project_path / "generated" / "images"
        generated_dir.mkdir(parents=True, exist_ok=True)
        
        filename = f"{task_id}_metadata.json"
        metadata_path = generated_dir / filename
        
        with open(metadata_path, 'w') as f:
            json.dump(metadata, f, indent=2)
        
        logger.debug(f"Saved metadata: {metadata_path}")
        return f"generated/images/{filename}"
    
    def save_intermediate(self, project_id: str, file_data: bytes, 
                         category: str, filename: str) -> str:
        """
        Save intermediate file (latents, masks, frames).
        
        Args:
            project_id: Project identifier
            file_data: Binary file data
            category: Category (latents, masks, frames)
            filename: Filename
            
        Returns:
            Relative path to saved file
        """
        project_path = self.get_project_path(project_id)
        intermediate_dir = project_path / "intermediate" / category
        intermediate_dir.mkdir(parents=True, exist_ok=True)
        
        file_path = intermediate_dir / filename
        
        with open(file_path, 'wb') as f:
            f.write(file_data)
        
        logger.debug(f"Saved intermediate file: {file_path}")
        return f"intermediate/{category}/{filename}"
    
    def promote_to_final(self, project_id: str, source_path: str, 
                        new_name: Optional[str] = None) -> str:
        """
        Move/copy file to final folder (user-approved outputs).
        
        Args:
            project_id: Project identifier
            source_path: Relative path to source file
            new_name: Optional new filename
            
        Returns:
            Relative path to final file
        """
        project_path = self.get_project_path(project_id)
        source_full = project_path / source_path
        
        if not source_full.exists():
            raise FileNotFoundError(f"Source file not found: {source_full}")
        
        final_dir = project_path / "final"
        final_dir.mkdir(parents=True, exist_ok=True)
        
        filename = new_name or source_full.name
        final_path = final_dir / filename
        
        # Copy file (keep original)
        shutil.copy2(source_full, final_path)
        
        logger.info(f"Promoted to final: {final_path}")
        return f"final/{filename}"
    
    def cleanup_intermediate(self, project_id: str) -> int:
        """
        Remove intermediate files to free up space.
        
        Args:
            project_id: Project identifier
            
        Returns:
            Number of files removed
        """
        project_path = self.get_project_path(project_id)
        intermediate_dir = project_path / "intermediate"
        
        if not intermediate_dir.exists():
            return 0
        
        files_removed = 0
        for item in intermediate_dir.rglob('*'):
            if item.is_file():
                item.unlink()
                files_removed += 1
        
        logger.info(f"Cleaned up {files_removed} intermediate files")
        return files_removed
    
    def get_project_files(self, project_id: str, category: str) -> List[Path]:
        """
        List files in a project category.
        
        Args:
            project_id: Project identifier
            category: Category (workflows, prompts, generated, intermediate, final)
            
        Returns:
            List of file paths
        """
        project_path = self.get_project_path(project_id)
        category_dir = project_path / category
        
        if not category_dir.exists():
            return []
        
        files = [f for f in category_dir.rglob('*') if f.is_file()]
        return files
    
    def get_storage_stats(self, project_id: str) -> Dict[str, Any]:
        """
        Calculate storage statistics for a project.
        
        Args:
            project_id: Project identifier
            
        Returns:
            Dictionary with storage stats
        """
        project_path = self.get_project_path(project_id)
        
        if not project_path.exists():
            return {"error": "Project not found"}
        
        stats = {
            "workflows_count": len(list((project_path / "workflows").glob('*.json'))),
            "prompts_count": len(list((project_path / "prompts").glob('*.json'))),
            "images_count": len(list((project_path / "generated" / "images").glob('*.*'))),
            "videos_count": len(list((project_path / "generated" / "videos").glob('*.*'))),
            "audio_count": len(list((project_path / "generated" / "audio").glob('*.*'))),
            "final_count": len(list((project_path / "final").glob('*.*'))),
            "storage_size_mb": 0.0
        }
        
        # Calculate total size
        total_size = sum(f.stat().st_size for f in project_path.rglob('*') if f.is_file())
        stats["storage_size_mb"] = round(total_size / (1024 * 1024), 2)
        
        return stats
    
    def update_project_metadata(self, project_id: str, updates: Dict[str, Any]) -> bool:
        """
        Update project metadata file.
        
        Args:
            project_id: Project identifier
            updates: Dictionary of updates to apply
            
        Returns:
            True if successful
        """
        project_path = self.get_project_path(project_id)
        metadata_path = project_path / "metadata.json"
        
        if not metadata_path.exists():
            logger.error(f"Metadata file not found: {metadata_path}")
            return False
        
        # Load existing metadata
        with open(metadata_path, 'r') as f:
            metadata = json.load(f)
        
        # Apply updates
        metadata.update(updates)
        metadata["updated_at"] = datetime.utcnow().isoformat()
        
        # Save updated metadata
        with open(metadata_path, 'w') as f:
            json.dump(metadata, f, indent=2)
        
        logger.debug(f"Updated project metadata: {project_id}")
        return True


# Global instance
_project_storage = None

def get_project_storage() -> ProjectStorage:
    """Get global ProjectStorage instance."""
    global _project_storage
    if _project_storage is None:
        _project_storage = ProjectStorage()
    return _project_storage
