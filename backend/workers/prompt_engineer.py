# START OF FILE backend/workers/prompt_engineer.py
"""
Prompt Engineer Worker Agent.

Specializes in generating optimized prompts for ComfyUI image/video generation.
Creates detailed, structured prompts with style modifiers, negative prompts, 
and quality parameters.
"""

import logging
import json
from typing import Dict, Any
from datetime import datetime

from worker_agent import WorkerAgent, WorkerCapabilities, ResourceRequirements
from database import TaskStatusEnum, ProjectCRUD, TaskTypeEnum

logger = logging.getLogger(__name__)


class PromptEngineer(WorkerAgent):
    """
    Worker specialized in creating optimized prompts for AI image/video generation.
    
    Capabilities:
    - Analyzes project requirements and generates detailed prompts
    - Creates style-specific prompt modifiers
    - Generates negative prompts for quality control
    - Outputs structured prompt specifications for ComfyUI
    """
    
    _agent_type = "prompt_engineer"
    
    def __init__(self, db_session):
        super().__init__(db_session)
        self.agent_type = "prompt_engineer"
    
    @staticmethod
    def _get_capabilities_static():
        """Get static capabilities metadata."""
        return {
            "agent_type": "prompt_engineer",
            "supported_task_types": ["prompt_engineering"],
            "description": "Generates optimized prompts for ComfyUI image/video generation",
            "version": "1.0.0"
        }
    
    def get_capabilities(self) -> WorkerCapabilities:
        """Get worker capabilities."""
        return WorkerCapabilities(
            agent_type=self.agent_type,
            supported_task_types=["prompt_engineering"],
            description="Generates optimized prompts for AI image/video generation",
            version="1.0.0"
        )
    
    def get_resource_requirements(self) -> ResourceRequirements:
        """Get resource requirements."""
        return ResourceRequirements(
            gpu=False,
            min_ram_gb=2,
            cpu_cores=1,
            disk_space_gb=0.5,
            network=False
        )
    
    def validate_result(self, result: Dict[str, Any]) -> bool:
        """Validate prompt generation result."""
        if not super().validate_result(result):
            return False
        
        # Check for required fields
        required_fields = ["positive_prompt", "negative_prompt"]
        for field in required_fields:
            if field not in result:
                logger.warning(f"Missing required field in result: {field}")
                return False
        
        # Validate positive prompt is not empty
        if not result["positive_prompt"] or not result["positive_prompt"].strip():
            logger.warning("Positive prompt is empty")
            return False
        
        logger.debug("PromptEngineer result validation passed")
        return True
    
    async def process_task(self, task) -> Dict[str, Any]:
        """
        Generate optimized prompts based on task requirements.
        
        Args:
            task: Task containing scene/shot description
            
        Returns:
            Dictionary containing:
            - positive_prompt: Main prompt for generation
            - negative_prompt: Things to avoid
            - style_modifiers: Style-specific additions
            - quality_tags: Quality enhancement tags
            - parameters: Suggested generation parameters
        """
        logger.info(f"PromptEngineer starting task: {task.id}")
        
        try:
            # Update status to IN_PROGRESS
            self.update_status(task.id, TaskStatusEnum.IN_PROGRESS)
            self.report_progress(task.id, 10, "Analyzing project context")
            
            # Get project context
            project = ProjectCRUD.get(self.db, task.project_id)
            if not project:
                raise ValueError(f"Project {task.project_id} not found")
            
            # Prepare context for prompt generation
            self.report_progress(task.id, 30, "Preparing prompt generation")
            
            prompt = self.prompt_manager.get_worker_prompt(
                "prompt_engineer",
                project_title=project.title,
                project_description=project.description,
                project_style=project.style or "cinematic, professional",
                task_description=task.description,
                scene_context=task.meta_data.get("scene_context", "") if task.meta_data else ""
            )
            
            # Call LLM to generate structured prompt
            self.report_progress(task.id, 50, "Generating optimized prompts")
            
            logger.debug("Calling LLM for prompt generation")
            response_content = await self.call_llm(
                messages=[{"role": "user", "content": prompt}],
                temperature=0.8,
                format="json"
            )
            
            # Parse response
            self.report_progress(task.id, 70, "Processing prompt output")
            prompt_data = json.loads(response_content)
            
            # Structure the result
            result = {
                "positive_prompt": prompt_data.get("positive_prompt", ""),
                "negative_prompt": prompt_data.get("negative_prompt", ""),
                "style_modifiers": prompt_data.get("style_modifiers", []),
                "quality_tags": prompt_data.get("quality_tags", []),
                "parameters": {
                    "steps": prompt_data.get("steps", 30),
                    "cfg_scale": prompt_data.get("cfg_scale", 7.5),
                    "sampler": prompt_data.get("sampler", "DPM++ 2M Karras"),
                    "resolution": prompt_data.get("resolution", "1024x1024")
                },
                "variations": prompt_data.get("variations", []),
                "format": "comfyui_prompt",
                "generated_at": datetime.utcnow().isoformat()
            }
            
            # Validate result
            if not self.validate_result(result):
                raise ValueError("Generated prompt failed validation")
            
            # Update status to COMPLETED
            self.report_progress(task.id, 100, "Prompt generation complete")
            self.update_status(task.id, TaskStatusEnum.COMPLETED, result)
            
            logger.info(f"PromptEngineer completed task: {task.id}")
            logger.debug(f"Generated prompt length: {len(result['positive_prompt'])} chars")
            
            return result
            
        except Exception as e:
            logger.error(f"PromptEngineer failed task {task.id}: {e}", exc_info=True)
            self.update_status(task.id, TaskStatusEnum.FAILED, {"error": str(e)})
            raise
