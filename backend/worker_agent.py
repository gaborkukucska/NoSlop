# START OF FILE backend/worker_agent.py
"""
Base Worker Agent class for NoSlop.

All specialized worker agents (ScriptWriter, ImageGenerator, etc.) should inherit from this class.
"""

import logging
import abc
from typing import Dict, Any, Optional
from datetime import datetime
import ollama

from models import Task, TaskStatus
from database import TaskCRUD, TaskStatusEnum
from config import settings
from prompt_manager import get_prompt_manager

logger = logging.getLogger(__name__)

class WorkerAgent(abc.ABC):
    """
    Abstract base class for Worker Agents.
    """
    
    def __init__(self, db_session):
        """
        Initialize Worker Agent.
        
        Args:
            db_session: Database session for updating task status
        """
        self.db = db_session
        self.model = settings.model_logic  # Default to logic model, can be overridden
        self.prompt_manager = get_prompt_manager()
        self.agent_type = "generic_worker"
        
    @abc.abstractmethod
    def process_task(self, task: Task) -> Dict[str, Any]:
        """
        Process a assigned task.
        
        Args:
            task: Task to process
            
        Returns:
            Result dictionary
        """
        pass
    
    def update_status(self, task_id: str, status: TaskStatusEnum, result: Optional[Dict] = None):
        """
        Update task status in database.
        
        Args:
            task_id: Task ID
            status: New status
            result: Optional result data
        """
        updates = {"status": status}
        
        if status == TaskStatusEnum.IN_PROGRESS:
            updates["started_at"] = datetime.utcnow()
        elif status == TaskStatusEnum.COMPLETED:
            updates["completed_at"] = datetime.utcnow()
            
        if result:
            updates["result"] = result
            
        TaskCRUD.update(self.db, task_id, updates)
        logger.info(f"Task {task_id} updated to {status.value}")

    def call_llm(self, messages: list, temperature: float = 0.7, format: str = None) -> str:
        """
        Helper method to call Ollama.
        
        Args:
            messages: List of message dicts
            temperature: Creativity control
            format: Response format (e.g., "json")
            
        Returns:
            Content of the response
        """
        try:
            options = {"temperature": temperature}
            
            response = ollama.chat(
                model=self.model,
                messages=messages,
                format=format,
                options=options
            )
            
            return response['message']['content']
            
        except Exception as e:
            logger.error(f"LLM call failed: {e}", exc_info=True)
            raise
