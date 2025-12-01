# START OF FILE backend/workers/video_editor.py
"""
Video Editor Worker Agent.

Specializes in planning video editing sequences and generating FFmpeg commands.
Creates edit decision lists (EDL), plans transitions, effects, and pacing.
"""

import logging
import json
from typing import Dict, Any, List
from datetime import datetime

from worker_agent import WorkerAgent, WorkerCapabilities, ResourceRequirements
from database import TaskStatusEnum, ProjectCRUD, TaskCRUD

logger = logging.getLogger(__name__)


class VideoEditor(WorkerAgent):
    """
    Worker specialized in video editing planning and FFmpeg command generation.
    
    Capabilities:
    - Creates edit decision lists (EDL)
    - Plans transitions, effects, and pacing
    - Generates FFmpeg command sequences
    - Outputs editing timeline specifications
    - Handles multi-track audio/video editing
    """
    
    _agent_type = "video_editor"
    
    def __init__(self, db_session):
        super().__init__(db_session)
        self.agent_type = "video_editor"
    
    @staticmethod
    def _get_capabilities_static():
        """Get static capabilities metadata."""
        return {
            "agent_type": "video_editor",
            "supported_task_types": ["video_editing"],
            "description": "Plans video editing sequences and generates FFmpeg commands",
            "version": "1.0.0"
        }
    
    def get_capabilities(self) -> WorkerCapabilities:
        """Get worker capabilities."""
        return WorkerCapabilities(
            agent_type=self.agent_type,
            supported_task_types=["video_editing"],
            description="Plans video editing sequences and generates FFmpeg commands",
            version="1.0.0"
        )
    
    def get_resource_requirements(self) -> ResourceRequirements:
        """Get resource requirements."""
        return ResourceRequirements(
            gpu=True,  # GPU acceleration helpful for video processing
            min_vram_gb=4,
            min_ram_gb=8,
            cpu_cores=4,
            disk_space_gb=10,
            network=False
        )
    
    def validate_result(self, result: Dict[str, Any]) -> bool:
        """Validate video editing result."""
        if not super().validate_result(result):
            return False
        
        # Check for required fields
        required_fields = ["timeline", "total_duration"]
        for field in required_fields:
            if field not in result:
                logger.warning(f"Missing required field in result: {field}")
                return False
        
        # Validate timeline structure
        timeline = result.get("timeline", [])
        if not isinstance(timeline, list):
            logger.warning("Timeline must be a list")
            return False
        
        logger.debug("VideoEditor result validation passed")
        return True
    
    def process_task(self, task) -> Dict[str, Any]:
        """
        Generate video editing plan and FFmpeg commands.
        
        Args:
            task: Task containing storyboard or editing requirements
            
        Returns:
            Dictionary containing:
            - timeline: Editing timeline with clips and transitions
            - ffmpeg_commands: List of FFmpeg commands to execute
            - total_duration: Total video duration
            - audio_tracks: Audio track specifications
            - effects: Visual effects to apply
        """
        logger.info(f"VideoEditor starting task: {task.id}")
        
        try:
            # Update status to IN_PROGRESS
            self.update_status(task.id, TaskStatusEnum.IN_PROGRESS)
            self.report_progress(task.id, 10, "Analyzing storyboard")
            
            # Get project context
            project = ProjectCRUD.get(self.db, task.project_id)
            if not project:
                raise ValueError(f"Project {task.project_id} not found")
            
            # Get storyboard from previous tasks
            self.report_progress(task.id, 20, "Gathering storyboard data")
            storyboard_data = self._get_storyboard_data(task)
            
            # Prepare prompt for editing plan
            self.report_progress(task.id, 35, "Planning edit sequence")
            
            prompt = self.prompt_manager.get_worker_prompt(
                "video_editor",
                project_title=project.title,
                project_description=project.description,
                project_style=project.style or "cinematic",
                task_description=task.description,
                storyboard_summary=storyboard_data,
                target_duration=project.duration or 60
            )
            
            # Call LLM to generate editing plan
            self.report_progress(task.id, 50, "Generating editing plan")
            
            logger.debug("Calling LLM for editing plan generation")
            response_content = self.call_llm(
                messages=[{"role": "user", "content": prompt}],
                temperature=0.6,
                format="json"
            )
            
            # Parse response
            self.report_progress(task.id, 70, "Processing editing plan")
            editing_data = json.loads(response_content)
            
            # Generate FFmpeg commands
            self.report_progress(task.id, 85, "Generating FFmpeg commands")
            ffmpeg_commands = self._generate_ffmpeg_commands(editing_data)
            
            # Structure the result
            result = {
                "timeline": editing_data.get("timeline", []),
                "transitions": editing_data.get("transitions", []),
                "effects": editing_data.get("effects", []),
                "audio_tracks": editing_data.get("audio_tracks", []),
                "ffmpeg_commands": ffmpeg_commands,
                "total_duration": editing_data.get("total_duration", 60),
                "output_format": editing_data.get("output_format", "mp4"),
                "resolution": editing_data.get("resolution", "1920x1080"),
                "framerate": editing_data.get("framerate", 30),
                "format": "edl_v1",
                "generated_at": datetime.utcnow().isoformat()
            }
            
            # Validate result
            if not self.validate_result(result):
                raise ValueError("Generated editing plan failed validation")
            
            # Update status to COMPLETED
            self.report_progress(task.id, 100, "Editing plan complete")
            self.update_status(task.id, TaskStatusEnum.COMPLETED, result)
            
            logger.info(f"VideoEditor completed task: {task.id}")
            logger.debug(f"Generated {len(ffmpeg_commands)} FFmpeg commands")
            
            return result
            
        except Exception as e:
            logger.error(f"VideoEditor failed task {task.id}: {e}", exc_info=True)
            self.update_status(task.id, TaskStatusEnum.FAILED, {"error": str(e)})
            raise
    
    def _get_storyboard_data(self, task) -> str:
        """
        Get storyboard data from previous tasks.
        
        Args:
            task: Current task
            
        Returns:
            Storyboard summary string
        """
        # Check if storyboard is in task metadata
        if task.meta_data and "storyboard_data" in task.meta_data:
            return json.dumps(task.meta_data["storyboard_data"])
        
        # Try to find storyboard from dependencies
        if task.dependencies:
            for dep_id in task.dependencies:
                dep_task = TaskCRUD.get(self.db, dep_id)
                if dep_task and dep_task.task_type.value == "storyboard":
                    if dep_task.result:
                        logger.debug(f"Found storyboard in dependent task {dep_id}")
                        return json.dumps(dep_task.result)
        
        # Fallback to task description
        logger.warning("No storyboard found in dependencies, using task description")
        return task.description
    
    def _generate_ffmpeg_commands(self, editing_data: Dict) -> List[str]:
        """
        Generate FFmpeg commands from editing plan.
        
        Args:
            editing_data: Editing plan data
            
        Returns:
            List of FFmpeg command strings
        """
        commands = []
        
        # Base command template
        resolution = editing_data.get("resolution", "1920x1080")
        framerate = editing_data.get("framerate", 30)
        output_format = editing_data.get("output_format", "mp4")
        
        # This is a simplified example - real implementation would be more complex
        timeline = editing_data.get("timeline", [])
        
        if timeline:
            # Example: Concatenate clips
            input_files = " ".join([f"-i {clip.get('source', 'clip.mp4')}" for clip in timeline])
            concat_filter = f"concat=n={len(timeline)}:v=1:a=1"
            
            command = (
                f"ffmpeg {input_files} "
                f"-filter_complex \"{concat_filter}\" "
                f"-s {resolution} -r {framerate} "
                f"-c:v libx264 -preset medium -crf 23 "
                f"-c:a aac -b:a 192k "
                f"output.{output_format}"
            )
            commands.append(command)
        
        logger.debug(f"Generated {len(commands)} FFmpeg commands")
        return commands
