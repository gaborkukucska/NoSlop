# START OF FILE backend/workers/script_writer.py
"""
Script Writer Worker Agent.
"""

import logging
import json
from typing import Dict, Any

from worker_agent import WorkerAgent
from models import Task
from database import TaskStatusEnum, ProjectCRUD

logger = logging.getLogger(__name__)

class ScriptWriter(WorkerAgent):
    """
    Worker specialized in writing scripts for media projects.
    """
    
    def __init__(self, db_session):
        super().__init__(db_session)
        self.agent_type = "script_writer"
        
    async def process_task(self, task: Task) -> Dict[str, Any]:
        """
        Generate a script based on the task description and project context.
        """
        logger.info(f"ScriptWriter starting task: {task.id}")
        
        try:
            # Update status to IN_PROGRESS
            self.update_status(task.id, TaskStatusEnum.IN_PROGRESS)
            
            # Get project context
            project = ProjectCRUD.get(self.db, task.project_id)
            if not project:
                raise ValueError(f"Project {task.project_id} not found")
            
            # Prepare prompt
            prompt = self.prompt_manager.get_worker_prompt(
                "script_writer",
                project_title=project.title,
                project_description=project.description,
                task_description=task.description,
                style=project.style or "standard"
            )
            
            # Call LLM
            logger.debug("Generating script content...")
            script_content = await self.call_llm(
                messages=[{"role": "user", "content": prompt}],
                temperature=0.8
            )
            
            # Structure the result
            result = {
                "content": script_content,
                "format": "markdown",
                "generated_at": str(task.created_at) # Placeholder, should be now
            }
            
            # Update status to COMPLETED
            self.update_status(task.id, TaskStatusEnum.COMPLETED, result)
            
            logger.info(f"ScriptWriter completed task: {task.id}")
            return result
            
        except Exception as e:
            logger.error(f"ScriptWriter failed task {task.id}: {e}", exc_info=True)
            self.update_status(task.id, TaskStatusEnum.FAILED, {"error": str(e)})
            raise
