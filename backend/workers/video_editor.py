import logging
import json
import os
from typing import Dict, Any, List
from datetime import datetime
from pathlib import Path

from worker_agent import WorkerAgent, WorkerCapabilities, ResourceRequirements
from database import TaskStatusEnum, ProjectCRUD, TaskCRUD
from clients.ffmpeg_client import FFmpegClient
from config import settings

logger = logging.getLogger(__name__)


class VideoEditor(WorkerAgent):
    """
    Worker specialized in video editing planning and FFmpeg command generation.
    Now capable of executing basic edits (slideshows, concatenation).
    """
    
    _agent_type = "video_editor"
    
    def __init__(self, db_session):
        super().__init__(db_session)
        self.agent_type = "video_editor"
        self.ffmpeg = FFmpegClient(
            ffmpeg_path=getattr(settings, "FFMPEG_PATH", "ffmpeg"),
            ffprobe_path=getattr(settings, "FFPROBE_PATH", "ffprobe")
        )
        self.output_dir = Path(getattr(settings, "MEDIA_OUTPUT_DIR", "media/output"))
        self.output_dir.mkdir(parents=True, exist_ok=True)
    
    @staticmethod
    def _get_capabilities_static():
        """Get static capabilities metadata."""
        return {
            "agent_type": "video_editor",
            "supported_task_types": ["video_editing"],
            "description": "Plans video editing sequences and executes FFmpeg commands",
            "version": "1.1.0"
        }
    
    def get_capabilities(self) -> WorkerCapabilities:
        """Get worker capabilities."""
        return WorkerCapabilities(
            agent_type=self.agent_type,
            supported_task_types=["video_editing"],
            description="Plans video editing sequences and executes FFmpeg commands",
            version="1.1.0"
        )
    
    def get_resource_requirements(self) -> ResourceRequirements:
        """Get resource requirements."""
        return ResourceRequirements(
            gpu=True,
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
        if "timeline" not in result and "output_file" not in result:
            # Allow "status" only results (e.g. no_inputs_found)
            if "status" in result:
                return True
            logger.warning("Result must have either timeline or output_file")
            return False
            
        return True
    
    async def process_task(self, task) -> Dict[str, Any]:
        """
        Generate video editing plan and optionally execute it.
        """
        logger.info(f"VideoEditor starting task: {task.id}")
        
        try:
            self.update_status(task.id, TaskStatusEnum.IN_PROGRESS)
            self.report_progress(task.id, 10, "Analyzing requirements")
            
            # Check for input files (e.g. from ImageGeneration)
            input_files = self._get_input_files(task)
            
            # If we have input files, we might be doing a simple assembly
            if input_files:
                self.report_progress(task.id, 30, "Assembling video from inputs")
                output_filename = f"video_{task.id}.mp4"
                output_path = self.output_dir / output_filename
                
                # Simple slideshow logic for now
                # In future this would be driven by the LLM plan
                self.ffmpeg.create_slideshow(
                    input_files, 
                    str(output_path.absolute()), 
                    duration_per_image=3.0
                )
                
                result = {
                    "output_file": str(output_path.absolute()),
                    "input_files": input_files,
                    "format": "mp4",
                    "generated_at": datetime.utcnow().isoformat()
                }
                
                self.report_progress(task.id, 100, "Video assembly complete")
                self.update_status(task.id, TaskStatusEnum.COMPLETED, result)
                return result

            # If no inputs, this is an error - tasks need inputs to execute
            logger.error(f"VideoEditor task {task.id} has no input files to process")
            error_msg = "No input files found. Video editing requires input media from dependent tasks."
            result = {"error": error_msg, "status": "no_inputs_found"}
            self.update_status(task.id, TaskStatusEnum.FAILED, result)
            raise ValueError(error_msg)
            
        except Exception as e:
            logger.error(f"VideoEditor failed task {task.id}: {e}", exc_info=True)
            self.update_status(task.id, TaskStatusEnum.FAILED, {"error": str(e)})
            raise
    
    def _get_input_files(self, task) -> List[str]:
        """Retrieve input file paths from dependencies."""
        files = []
        if task.dependencies:
            for dep_id in task.dependencies:
                dep_task = TaskCRUD.get(self.db, dep_id)
                if dep_task and dep_task.result:
                    # Check for generated files from ImageGeneration
                    if "generated_files" in dep_task.result:
                        files.extend(dep_task.result["generated_files"])
        return files
