# START OF FILE backend/worker_agent.py
"""
Base Worker Agent class for NoSlop.

All specialized worker agents (ScriptWriter, ImageGenerator, etc.) should inherit from this class.
"""

import logging
import abc
import time
from typing import Dict, Any, Optional, Callable, List
from datetime import datetime
import ollama

from models import Task, TaskStatus
from database import TaskCRUD, TaskStatusEnum
from config import settings
from prompt_manager import get_prompt_manager

logger = logging.getLogger(__name__)


class ResourceRequirements:
    """Resource requirements specification for worker agents."""
    
    def __init__(
        self,
        gpu: bool = False,
        min_vram_gb: float = 0,
        min_ram_gb: float = 1,
        cpu_cores: int = 1,
        disk_space_gb: float = 1,
        network: bool = False
    ):
        self.gpu = gpu
        self.min_vram_gb = min_vram_gb
        self.min_ram_gb = min_ram_gb
        self.cpu_cores = cpu_cores
        self.disk_space_gb = disk_space_gb
        self.network = network
    
    def to_dict(self) -> Dict[str, Any]:
        """Convert to dictionary."""
        return {
            "gpu": self.gpu,
            "min_vram_gb": self.min_vram_gb,
            "min_ram_gb": self.min_ram_gb,
            "cpu_cores": self.cpu_cores,
            "disk_space_gb": self.disk_space_gb,
            "network": self.network
        }


class WorkerCapabilities:
    """Worker agent capabilities metadata."""
    
    def __init__(
        self,
        agent_type: str,
        supported_task_types: List[str],
        description: str,
        version: str = "1.0.0"
    ):
        self.agent_type = agent_type
        self.supported_task_types = supported_task_types
        self.description = description
        self.version = version
    
    def to_dict(self) -> Dict[str, Any]:
        """Convert to dictionary."""
        return {
            "agent_type": self.agent_type,
            "supported_task_types": self.supported_task_types,
            "description": self.description,
            "version": self.version
        }

class WorkerAgent(abc.ABC):
    """
    Abstract base class for Worker Agents.
    
    Provides common functionality for all worker agents including:
    - Task processing with retry logic
    - Progress reporting
    - Result validation
    - Resource management
    - Error handling
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
        self.max_retries = 3
        self.retry_delay = 2  # seconds
        self.progress_callback: Optional[Callable] = None
        
    @abc.abstractmethod
    def process_task(self, task: Task) -> Dict[str, Any]:
        """
        Process an assigned task.
        
        Subclasses must implement this method to define specific task processing logic.
        
        Args:
            task: Task to process
            
        Returns:
            Result dictionary containing task output
            
        Raises:
            Exception: If task processing fails
        """
        pass
    
    def get_capabilities(self) -> WorkerCapabilities:
        """
        Get worker capabilities metadata.
        
        Subclasses should override to provide specific capabilities.
        
        Returns:
            WorkerCapabilities object
        """
        return WorkerCapabilities(
            agent_type=self.agent_type,
            supported_task_types=["custom"],
            description="Generic worker agent"
        )
    
    def get_resource_requirements(self) -> ResourceRequirements:
        """
        Get resource requirements for this worker.
        
        Subclasses should override to specify specific requirements.
        
        Returns:
            ResourceRequirements object
        """
        return ResourceRequirements(
            gpu=False,
            min_ram_gb=1,
            cpu_cores=1,
            disk_space_gb=1
        )
    
    def validate_result(self, result: Dict[str, Any]) -> bool:
        """
        Validate task result.
        
        Subclasses can override to implement specific validation logic.
        
        Args:
            result: Task result to validate
            
        Returns:
            True if result is valid, False otherwise
        """
        # Basic validation: result should be a dict with content
        if not isinstance(result, dict):
            logger.warning(f"Result validation failed: not a dictionary")
            return False
        
        if not result:
            logger.warning(f"Result validation failed: empty result")
            return False
        
        logger.debug(f"Result validation passed for {self.agent_type}")
        return True
    
    def report_progress(self, task_id: str, percentage: float, message: str):
        """
        Report task progress.
        
        Args:
            task_id: Task ID
            percentage: Progress percentage (0-100)
            message: Progress message
        """
        logger.info(f"Task {task_id} progress: {percentage:.1f}% - {message}")
        
        # Update task metadata with progress
        task = TaskCRUD.get(self.db, task_id)
        if task:
            meta_data = task.meta_data or {}
            meta_data["progress"] = {
                "percentage": percentage,
                "message": message,
                "updated_at": datetime.utcnow().isoformat()
            }
            TaskCRUD.update(self.db, task_id, {"meta_data": meta_data})
        
        # Call progress callback if set
        if self.progress_callback:
            self.progress_callback(task_id, percentage, message)
    
    def request_assistance(self, message: str, context: Optional[Dict] = None) -> str:
        """
        Request assistance from PM or other agents.
        
        Args:
            message: Assistance request message
            context: Optional context information
            
        Returns:
            Response message
        """
        logger.info(f"{self.agent_type} requesting assistance: {message}")
        
        # TODO: Implement inter-agent communication
        # For now, just log the request
        
        return "Assistance request logged. PM will be notified."
    
    def execute_with_retry(self, task: Task) -> Dict[str, Any]:
        """
        Execute task with automatic retry on failure.
        
        Args:
            task: Task to execute
            
        Returns:
            Task result
            
        Raises:
            Exception: If task fails after all retries
        """
        last_error = None
        
        for attempt in range(self.max_retries):
            try:
                logger.info(f"Executing task {task.id}, attempt {attempt + 1}/{self.max_retries}")
                
                # Process the task
                result = self.process_task(task)
                
                # Validate result
                if not self.validate_result(result):
                    raise ValueError("Task result validation failed")
                
                logger.info(f"Task {task.id} completed successfully on attempt {attempt + 1}")
                return result
                
            except Exception as e:
                last_error = e
                logger.warning(
                    f"Task {task.id} failed on attempt {attempt + 1}/{self.max_retries}: {e}",
                    exc_info=True
                )
                
                # Don't retry on last attempt
                if attempt < self.max_retries - 1:
                    # Exponential backoff
                    delay = self.retry_delay * (2 ** attempt)
                    logger.info(f"Retrying in {delay} seconds...")
                    time.sleep(delay)
                else:
                    logger.error(f"Task {task.id} failed after {self.max_retries} attempts")
        
        # All retries exhausted
        raise Exception(f"Task failed after {self.max_retries} attempts: {last_error}")
    
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

    def call_llm(
        self,
        messages: list,
        temperature: float = 0.7,
        format: str = None,
        max_tokens: Optional[int] = None
    ) -> str:
        """
        Helper method to call Ollama with error handling.
        
        Args:
            messages: List of message dicts
            temperature: Creativity control (0.0-1.0)
            format: Response format (e.g., "json")
            max_tokens: Maximum tokens in response
            
        Returns:
            Content of the response
            
        Raises:
            Exception: If LLM call fails
        """
        try:
            options = {"temperature": temperature}
            
            if max_tokens:
                options["num_predict"] = max_tokens
            
            logger.debug(f"Calling LLM {self.model} with temperature {temperature}")
            
            response = ollama.chat(
                model=self.model,
                messages=messages,
                format=format,
                options=options
            )
            
            content = response['message']['content']
            logger.debug(f"LLM response received: {len(content)} characters")
            
            return content
            
        except Exception as e:
            logger.error(f"LLM call failed: {e}", exc_info=True)
            raise
