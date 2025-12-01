# START OF FILE backend/worker_registry.py
"""
Worker Registry for NoSlop.

Central registry for all available worker agents. Provides:
- Worker registration and discovery
- Capability matching (task type -> worker mapping)
- Worker instantiation factory
- Worker metadata management
"""

import logging
from typing import Dict, List, Optional, Type
from sqlalchemy.orm import Session

from worker_agent import WorkerAgent, WorkerCapabilities, ResourceRequirements
from database import TaskTypeEnum

logger = logging.getLogger(__name__)


class WorkerRegistry:
    """
    Central registry for worker agents.
    
    Manages worker discovery, registration, and instantiation.
    """
    
    def __init__(self):
        """Initialize worker registry."""
        self._workers: Dict[str, Type[WorkerAgent]] = {}
        self._task_type_mapping: Dict[TaskTypeEnum, str] = {}
        logger.info("Worker Registry initialized")
    
    def register_worker(self, worker_class: Type[WorkerAgent], task_types: List[TaskTypeEnum]):
        """
        Register a worker class with the registry.
        
        Args:
            worker_class: Worker agent class to register
            task_types: List of task types this worker can handle
        """
        # Get worker type from a temporary instance
        temp_instance = worker_class.__new__(worker_class)
        temp_instance.agent_type = getattr(worker_class, '_agent_type', 'unknown')
        
        agent_type = temp_instance.agent_type
        
        # Register worker
        self._workers[agent_type] = worker_class
        
        # Map task types to this worker
        for task_type in task_types:
            self._task_type_mapping[task_type] = agent_type
        
        logger.info(f"Registered worker: {agent_type} for task types: {[t.value for t in task_types]}")
    
    def get_worker_for_task_type(self, task_type: TaskTypeEnum, db: Session) -> Optional[WorkerAgent]:
        """
        Get appropriate worker instance for a task type.
        
        Args:
            task_type: Type of task
            db: Database session
            
        Returns:
            Worker agent instance or None if no suitable worker found
        """
        agent_type = self._task_type_mapping.get(task_type)
        
        if not agent_type:
            logger.warning(f"No worker found for task type: {task_type.value}")
            return None
        
        worker_class = self._workers.get(agent_type)
        if not worker_class:
            logger.error(f"Worker class not found: {agent_type}")
            return None
        
        # Instantiate worker
        try:
            worker = worker_class(db)
            logger.debug(f"Instantiated worker: {agent_type} for task type: {task_type.value}")
            return worker
        except Exception as e:
            logger.error(f"Error instantiating worker {agent_type}: {e}", exc_info=True)
            return None
    
    def get_worker_by_type(self, agent_type: str, db: Session) -> Optional[WorkerAgent]:
        """
        Get worker instance by agent type.
        
        Args:
            agent_type: Worker agent type
            db: Database session
            
        Returns:
            Worker agent instance or None if not found
        """
        worker_class = self._workers.get(agent_type)
        
        if not worker_class:
            logger.warning(f"Worker not found: {agent_type}")
            return None
        
        try:
            worker = worker_class(db)
            logger.debug(f"Instantiated worker: {agent_type}")
            return worker
        except Exception as e:
            logger.error(f"Error instantiating worker {agent_type}: {e}", exc_info=True)
            return None
    
    def list_available_workers(self) -> List[Dict]:
        """
        List all registered workers with their capabilities.
        
        Returns:
            List of worker metadata dictionaries
        """
        workers = []
        
        for agent_type, worker_class in self._workers.items():
            try:
                # Create temporary instance to get capabilities
                temp_worker = worker_class.__new__(worker_class)
                temp_worker.agent_type = agent_type
                temp_worker.prompt_manager = None
                
                # Get capabilities if method exists
                if hasattr(worker_class, '_get_capabilities_static'):
                    capabilities = worker_class._get_capabilities_static()
                else:
                    # Fallback: basic info
                    capabilities = {
                        "agent_type": agent_type,
                        "supported_task_types": [
                            task_type.value 
                            for task_type, mapped_agent in self._task_type_mapping.items() 
                            if mapped_agent == agent_type
                        ],
                        "description": f"{agent_type} worker agent",
                        "version": "1.0.0"
                    }
                
                workers.append(capabilities)
                
            except Exception as e:
                logger.warning(f"Error getting capabilities for {agent_type}: {e}")
                continue
        
        logger.debug(f"Listed {len(workers)} available workers")
        return workers
    
    def get_worker_capabilities(self, agent_type: str) -> Optional[Dict]:
        """
        Get capabilities for a specific worker.
        
        Args:
            agent_type: Worker agent type
            
        Returns:
            Capabilities dictionary or None if worker not found
        """
        worker_class = self._workers.get(agent_type)
        
        if not worker_class:
            logger.warning(f"Worker not found: {agent_type}")
            return None
        
        try:
            # Get static capabilities if available
            if hasattr(worker_class, '_get_capabilities_static'):
                return worker_class._get_capabilities_static()
            
            # Fallback: get from task type mapping
            supported_types = [
                task_type.value 
                for task_type, mapped_agent in self._task_type_mapping.items() 
                if mapped_agent == agent_type
            ]
            
            return {
                "agent_type": agent_type,
                "supported_task_types": supported_types,
                "description": f"{agent_type} worker agent",
                "version": "1.0.0"
            }
            
        except Exception as e:
            logger.error(f"Error getting capabilities for {agent_type}: {e}", exc_info=True)
            return None
    
    def get_task_type_mapping(self) -> Dict[str, str]:
        """
        Get mapping of task types to worker types.
        
        Returns:
            Dictionary mapping task type names to agent types
        """
        return {
            task_type.value: agent_type 
            for task_type, agent_type in self._task_type_mapping.items()
        }


# Global registry instance
_registry = WorkerRegistry()


def get_registry() -> WorkerRegistry:
    """
    Get the global worker registry instance.
    
    Returns:
        WorkerRegistry instance
    """
    return _registry


def register_worker(worker_class: Type[WorkerAgent], task_types: List[TaskTypeEnum]):
    """
    Register a worker with the global registry.
    
    Args:
        worker_class: Worker class to register
        task_types: Task types this worker handles
    """
    _registry.register_worker(worker_class, task_types)


def initialize_workers():
    """
    Initialize and register all available workers.
    
    This function should be called at application startup to register all worker types.
    """
    logger.info("Initializing worker registry...")
    
    # Import and register workers
    try:
        from workers.script_writer import ScriptWriter
        register_worker(ScriptWriter, [TaskTypeEnum.SCRIPT_WRITING])
        logger.info("Registered ScriptWriter")
    except ImportError as e:
        logger.warning(f"Could not import ScriptWriter: {e}")
    
    try:
        from workers.prompt_engineer import PromptEngineer
        register_worker(PromptEngineer, [TaskTypeEnum.PROMPT_ENGINEERING])
        logger.info("Registered PromptEngineer")
    except ImportError as e:
        logger.warning(f"Could not import PromptEngineer: {e}")
    
    try:
        from workers.storyboard_artist import StoryboardArtist
        register_worker(StoryboardArtist, [TaskTypeEnum.STORYBOARD])
        logger.info("Registered StoryboardArtist")
    except ImportError as e:
        logger.warning(f"Could not import StoryboardArtist: {e}")
    
    try:
        from workers.video_editor import VideoEditor
        register_worker(VideoEditor, [TaskTypeEnum.VIDEO_EDITING])
        logger.info("Registered VideoEditor")
    except ImportError as e:
        logger.warning(f"Could not import VideoEditor: {e}")
    
    try:
        from workers.color_grader import ColorGrader
        register_worker(ColorGrader, [TaskTypeEnum.COLOR_GRADING])
        logger.info("Registered ColorGrader")
    except ImportError as e:
        logger.warning(f"Could not import ColorGrader: {e}")
    
    try:
        from workers.research_agent import ResearchAgent
        register_worker(ResearchAgent, [TaskTypeEnum.RESEARCH])
        logger.info("Registered ResearchAgent")
    except ImportError as e:
        logger.warning(f"Could not import ResearchAgent: {e}")
    
    logger.info(f"Worker registry initialized with {len(_registry._workers)} workers")
