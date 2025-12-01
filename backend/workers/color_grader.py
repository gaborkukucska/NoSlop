# START OF FILE backend/workers/color_grader.py
"""
Color Grader Worker Agent.

Specializes in applying professional color grading specifications.
Analyzes project style requirements and generates LUT specifications
and FFmpeg color correction commands.
"""

import logging
import json
from typing import Dict, Any, List
from datetime import datetime

from worker_agent import WorkerAgent, WorkerCapabilities, ResourceRequirements
from database import TaskStatusEnum, ProjectCRUD

logger = logging.getLogger(__name__)


class ColorGrader(WorkerAgent):
    """
    Worker specialized in color grading and color correction.
    
    Capabilities:
    - Analyzes project style requirements
    - Generates LUT (Look-Up Table) specifications
    - Creates FFmpeg color correction commands
    - Supports mood-based grading (cinematic, vibrant, muted, etc.)
    - Applies industry-standard color grading techniques
    """
    
    _agent_type = "color_grader"
    
    def __init__(self, db_session):
        super().__init__(db_session)
        self.agent_type = "color_grader"
    
    @staticmethod
    def _get_capabilities_static():
        """Get static capabilities metadata."""
        return {
            "agent_type": "color_grader",
            "supported_task_types": ["color_grading"],
            "description": "Applies professional color grading specifications",
            "version": "1.0.0"
        }
    
    def get_capabilities(self) -> WorkerCapabilities:
        """Get worker capabilities."""
        return WorkerCapabilities(
            agent_type=self.agent_type,
            supported_task_types=["color_grading"],
            description="Applies professional color grading specifications",
            version="1.0.0"
        )
    
    def get_resource_requirements(self) -> ResourceRequirements:
        """Get resource requirements."""
        return ResourceRequirements(
            gpu=True,  # GPU acceleration for video processing
            min_vram_gb=4,
            min_ram_gb=4,
            cpu_cores=2,
            disk_space_gb=2,
            network=False
        )
    
    def validate_result(self, result: Dict[str, Any]) -> bool:
        """Validate color grading result."""
        if not super().validate_result(result):
            return False
        
        # Check for required fields
        required_fields = ["color_profile", "adjustments"]
        for field in required_fields:
            if field not in result:
                logger.warning(f"Missing required field in result: {field}")
                return False
        
        logger.debug("ColorGrader result validation passed")
        return True
    
    def process_task(self, task) -> Dict[str, Any]:
        """
        Generate color grading specifications.
        
        Args:
            task: Task containing style and mood requirements
            
        Returns:
            Dictionary containing:
            - color_profile: Color grading profile name
            - adjustments: Detailed color adjustments
            - lut_specs: LUT specifications
            - ffmpeg_filters: FFmpeg color filter commands
            - mood: Target mood/atmosphere
        """
        logger.info(f"ColorGrader starting task: {task.id}")
        
        try:
            # Update status to IN_PROGRESS
            self.update_status(task.id, TaskStatusEnum.IN_PROGRESS)
            self.report_progress(task.id, 10, "Analyzing style requirements")
            
            # Get project context
            project = ProjectCRUD.get(self.db, task.project_id)
            if not project:
                raise ValueError(f"Project {task.project_id} not found")
            
            # Prepare prompt for color grading
            self.report_progress(task.id, 30, "Preparing color grading specifications")
            
            prompt = self.prompt_manager.get_worker_prompt(
                "color_grader",
                project_title=project.title,
                project_description=project.description,
                project_style=project.style or "cinematic, balanced",
                task_description=task.description,
                project_type=project.project_type.value,
                target_mood=task.meta_data.get("target_mood", "neutral") if task.meta_data else "neutral"
            )
            
            # Call LLM to generate color grading specs
            self.report_progress(task.id, 50, "Generating color grading plan")
            
            logger.debug("Calling LLM for color grading generation")
            response_content = self.call_llm(
                messages=[{"role": "user", "content": prompt}],
                temperature=0.6,
                format="json"
            )
            
            # Parse response
            self.report_progress(task.id, 70, "Processing color grading output")
            grading_data = json.loads(response_content)
            
            # Generate FFmpeg filters
            self.report_progress(task.id, 85, "Generating FFmpeg filters")
            ffmpeg_filters = self._generate_ffmpeg_filters(grading_data)
            
            # Structure the result
            result = {
                "color_profile": grading_data.get("color_profile", "neutral"),
                "mood": grading_data.get("mood", "balanced"),
                "adjustments": {
                    "brightness": grading_data.get("brightness", 0),
                    "contrast": grading_data.get("contrast", 1.0),
                    "saturation": grading_data.get("saturation", 1.0),
                    "temperature": grading_data.get("temperature", 0),
                    "tint": grading_data.get("tint", 0),
                    "highlights": grading_data.get("highlights", 0),
                    "shadows": grading_data.get("shadows", 0),
                    "whites": grading_data.get("whites", 0),
                    "blacks": grading_data.get("blacks", 0)
                },
                "color_curves": grading_data.get("color_curves", {}),
                "lut_specs": grading_data.get("lut_specs", {}),
                "ffmpeg_filters": ffmpeg_filters,
                "reference_image": grading_data.get("reference_image", None),
                "notes": grading_data.get("notes", []),
                "format": "color_grade_v1",
                "generated_at": datetime.utcnow().isoformat()
            }
            
            # Validate result
            if not self.validate_result(result):
                raise ValueError("Generated color grading failed validation")
            
            # Update status to COMPLETED
            self.report_progress(task.id, 100, "Color grading complete")
            self.update_status(task.id, TaskStatusEnum.COMPLETED, result)
            
            logger.info(f"ColorGrader completed task: {task.id}")
            logger.debug(f"Generated color profile: {result['color_profile']}")
            
            return result
            
        except Exception as e:
            logger.error(f"ColorGrader failed task {task.id}: {e}", exc_info=True)
            self.update_status(task.id, TaskStatusEnum.FAILED, {"error": str(e)})
            raise
    
    def _generate_ffmpeg_filters(self, grading_data: Dict) -> List[str]:
        """
        Generate FFmpeg filter commands from color grading data.
        
        Args:
            grading_data: Color grading specifications
            
        Returns:
            List of FFmpeg filter strings
        """
        filters = []
        
        # Extract adjustment values
        brightness = grading_data.get("brightness", 0)
        contrast = grading_data.get("contrast", 1.0)
        saturation = grading_data.get("saturation", 1.0)
        
        # Build eq filter for basic adjustments
        if brightness != 0 or contrast != 1.0 or saturation != 1.0:
            eq_params = []
            if brightness != 0:
                eq_params.append(f"brightness={brightness}")
            if contrast != 1.0:
                eq_params.append(f"contrast={contrast}")
            if saturation != 1.0:
                eq_params.append(f"saturation={saturation}")
            
            filters.append(f"eq={':'.join(eq_params)}")
        
        # Add color temperature adjustment if specified
        temperature = grading_data.get("temperature", 0)
        if temperature != 0:
            # Simplified temperature adjustment using colortemperature filter
            filters.append(f"colortemperature=temperature={6500 + temperature*100}")
        
        # Add curves adjustment if specified
        color_curves = grading_data.get("color_curves", {})
        if color_curves:
            # Example: curves filter for cinematic look
            filters.append("curves=preset=strong_contrast")
        
        # Add LUT if specified
        lut_file = grading_data.get("lut_specs", {}).get("file")
        if lut_file:
            filters.append(f"lut3d={lut_file}")
        
        logger.debug(f"Generated {len(filters)} FFmpeg filters")
        return filters
