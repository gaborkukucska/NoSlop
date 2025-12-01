# START OF FILE backend/workers/storyboard_artist.py
"""
Storyboard Artist Worker Agent.

Specializes in creating visual storyboard descriptions from scripts.
Breaks down scripts into scenes and generates shot-by-shot descriptions
with camera angles, lighting, composition notes, and timing.
"""

import logging
import json
from typing import Dict, Any, List
from datetime import datetime

from worker_agent import WorkerAgent, WorkerCapabilities, ResourceRequirements
from database import TaskStatusEnum, ProjectCRUD, TaskCRUD

logger = logging.getLogger(__name__)


class StoryboardArtist(WorkerAgent):
    """
    Worker specialized in creating visual storyboards from scripts.
    
    Capabilities:
    - Breaks scripts into scenes and shots
    - Generates detailed shot descriptions
    - Includes camera angles, movements, and framing
    - Specifies lighting, composition, and mood
    - Creates timing and duration estimates
    """
    
    _agent_type = "storyboard_artist"
    
    def __init__(self, db_session):
        super().__init__(db_session)
        self.agent_type = "storyboard_artist"
    
    @staticmethod
    def _get_capabilities_static():
        """Get static capabilities metadata."""
        return {
            "agent_type": "storyboard_artist",
            "supported_task_types": ["storyboard"],
            "description": "Creates visual storyboard descriptions from scripts",
            "version": "1.0.0"
        }
    
    def get_capabilities(self) -> WorkerCapabilities:
        """Get worker capabilities."""
        return WorkerCapabilities(
            agent_type=self.agent_type,
            supported_task_types=["storyboard"],
            description="Creates visual storyboard descriptions from scripts",
            version="1.0.0"
        )
    
    def get_resource_requirements(self) -> ResourceRequirements:
        """Get resource requirements."""
        return ResourceRequirements(
            gpu=False,
            min_ram_gb=2,
            cpu_cores=1,
            disk_space_gb=1,
            network=False
        )
    
    def validate_result(self, result: Dict[str, Any]) -> bool:
        """Validate storyboard result."""
        if not super().validate_result(result):
            return False
        
        # Check for required fields
        if "scenes" not in result:
            logger.warning("Missing 'scenes' in result")
            return False
        
        # Validate scenes structure
        scenes = result.get("scenes", [])
        if not isinstance(scenes, list) or len(scenes) == 0:
            logger.warning("Scenes must be a non-empty list")
            return False
        
        # Validate each scene has required fields
        for idx, scene in enumerate(scenes):
            if not isinstance(scene, dict):
                logger.warning(f"Scene {idx} is not a dictionary")
                return False
            
            required_fields = ["scene_number", "shots"]
            for field in required_fields:
                if field not in scene:
                    logger.warning(f"Scene {idx} missing required field: {field}")
                    return False
        
        logger.debug("StoryboardArtist result validation passed")
        return True
    
    def process_task(self, task) -> Dict[str, Any]:
        """
        Generate storyboard from script.
        
        Args:
            task: Task containing script or scene description
            
        Returns:
            Dictionary containing:
            - scenes: List of scene objects with shots
            - total_shots: Total number of shots
            - estimated_duration: Estimated total duration
            - format: Output format identifier
        """
        logger.info(f"StoryboardArtist starting task: {task.id}")
        
        try:
            # Update status to IN_PROGRESS
            self.update_status(task.id, TaskStatusEnum.IN_PROGRESS)
            self.report_progress(task.id, 10, "Analyzing script")
            
            # Get project context
            project = ProjectCRUD.get(self.db, task.project_id)
            if not project:
                raise ValueError(f"Project {task.project_id} not found")
            
            # Try to find script from previous tasks
            self.report_progress(task.id, 20, "Gathering script content")
            script_content = self._get_script_content(task)
            
            # Prepare prompt for storyboard generation
            self.report_progress(task.id, 35, "Preparing storyboard generation")
            
            prompt = self.prompt_manager.get_worker_prompt(
                "storyboard_artist",
                project_title=project.title,
                project_description=project.description,
                project_style=project.style or "cinematic",
                task_description=task.description,
                script_content=script_content,
                target_duration=project.duration or 60
            )
            
            # Call LLM to generate storyboard
            self.report_progress(task.id, 50, "Generating storyboard")
            
            logger.debug("Calling LLM for storyboard generation")
            response_content = self.call_llm(
                messages=[{"role": "user", "content": prompt}],
                temperature=0.7,
                format="json"
            )
            
            # Parse response
            self.report_progress(task.id, 75, "Processing storyboard output")
            storyboard_data = json.loads(response_content)
            
            # Structure the result
            scenes = storyboard_data.get("scenes", [])
            total_shots = sum(len(scene.get("shots", [])) for scene in scenes)
            
            result = {
                "scenes": scenes,
                "total_shots": total_shots,
                "total_scenes": len(scenes),
                "estimated_duration": storyboard_data.get("estimated_duration", 60),
                "notes": storyboard_data.get("notes", []),
                "format": "storyboard_v1",
                "generated_at": datetime.utcnow().isoformat()
            }
            
            # Validate result
            if not self.validate_result(result):
                raise ValueError("Generated storyboard failed validation")
            
            # Update status to COMPLETED
            self.report_progress(task.id, 100, "Storyboard generation complete")
            self.update_status(task.id, TaskStatusEnum.COMPLETED, result)
            
            logger.info(f"StoryboardArtist completed task: {task.id}")
            logger.debug(f"Generated {total_shots} shots across {len(scenes)} scenes")
            
            return result
            
        except Exception as e:
            logger.error(f"StoryboardArtist failed task {task.id}: {e}", exc_info=True)
            self.update_status(task.id, TaskStatusEnum.FAILED, {"error": str(e)})
            raise
    
    def _get_script_content(self, task) -> str:
        """
        Get script content from previous tasks or task description.
        
        Args:
            task: Current task
            
        Returns:
            Script content string
        """
        # Check if script is in task metadata
        if task.meta_data and "script_content" in task.meta_data:
            return task.meta_data["script_content"]
        
        # Try to find script from dependencies
        if task.dependencies:
            for dep_id in task.dependencies:
                dep_task = TaskCRUD.get(self.db, dep_id)
                if dep_task and dep_task.task_type.value == "script_writing":
                    if dep_task.result and "content" in dep_task.result:
                        logger.debug(f"Found script in dependent task {dep_id}")
                        return dep_task.result["content"]
        
        # Fallback to task description
        logger.warning("No script found in dependencies, using task description")
        return task.description
