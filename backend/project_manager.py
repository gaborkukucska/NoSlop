# START OF FILE backend/project_manager.py
"""
Project Manager (PM) Agent for NoSlop.

The PM Agent orchestrates media creation projects by:
- Breaking down user requests into structured project plans
- Creating and assigning tasks to worker agents
- Monitoring progress and adapting plans
- Reporting status to the Admin AI
"""

import ollama
import logging
import json
import uuid
from typing import List, Dict, Any, Optional
from datetime import datetime
from sqlalchemy.orm import Session

from models import ProjectRequest, ProjectType, Project, Task, TaskStatus as TaskStatusModel
from database import ProjectModel, TaskModel, ProjectCRUD, TaskCRUD, ProjectStatusEnum, TaskStatusEnum, TaskTypeEnum, ProjectTypeEnum
from config import settings
from prompt_manager import get_prompt_manager
from workers.script_writer import ScriptWriter

logger = logging.getLogger(__name__)


class ProjectManager:
    """
    Project Manager Agent - Orchestrates media creation projects.
    
    Responsibilities:
    - Generate project plans from user requests
    - Break down projects into tasks with dependencies
    - Assign tasks to appropriate worker agents
    - Monitor progress and report to Admin AI
    - Adapt plans based on worker feedback
    """
    
    def __init__(self, db: Session):
        """
        Initialize Project Manager.
        
        Args:
            db: Database session
        """
        self.db = db
        self.model = settings.model_logic  # Use logic model for planning
        self.prompt_manager = get_prompt_manager()
        
        logger.info("Project Manager initialized", extra={"context": {"model": self.model}})
    
    def create_project(self, project_request: ProjectRequest) -> Project:
        """
        Create a new project from user request.
        
        Args:
            project_request: Project creation request
            
        Returns:
            Created project with generated plan
        """
        logger.info(f"Creating project: {project_request.title}", extra={
            "context": {"project_type": project_request.project_type}
        })
        
        # Generate unique project ID
        project_id = f"proj_{uuid.uuid4().hex[:12]}"
        
        # Create project in database
        project_data = {
            "id": project_id,
            "title": project_request.title,
            "project_type": ProjectTypeEnum[project_request.project_type.upper()],
            "description": project_request.description,
            "status": ProjectStatusEnum.PLANNING,
            "duration": project_request.duration,
            "style": project_request.style,
            "reference_media": project_request.reference_media or [],
            "meta_data": {}
        }
        
        project_model = ProjectCRUD.create(self.db, project_data)
        
        # Generate project plan
        logger.info(f"Generating plan for project {project_id}")
        tasks = self.generate_plan(project_model)
        
        # Update project status
        ProjectCRUD.update(self.db, project_id, {"status": ProjectStatusEnum.IN_PROGRESS})
        
        # Convert to Pydantic model
        project = self._model_to_pydantic(project_model)
        
        logger.info(f"Project created successfully: {project_id} with {len(tasks)} tasks")
        
        return project
    
    def generate_plan(self, project: ProjectModel) -> List[TaskModel]:
        """
        Generate a structured project plan with tasks.
        
        Args:
            project: Project model
            
        Returns:
            List of created tasks
        """
        logger.info(f"Generating plan for project: {project.id}")
        
        # Build prompt with project details
        prompt = self.prompt_manager.get_project_manager_prompt(
            "project_planning",
            project_title=project.title,
            project_type=project.project_type.value,
            project_description=project.description,
            duration=project.duration or "not specified",
            style=project.style or "not specified"
        )
        
        try:
            # Call LLM to generate plan
            logger.debug("Calling LLM for project planning")
            response = ollama.chat(
                model=self.model,
                messages=[{"role": "user", "content": prompt}],
                format="json",
                options={"temperature": 0.7}
            )
            
            plan_data = json.loads(response['message']['content'])
            logger.debug(f"Raw LLM response: {response['message']['content']}")
            logger.debug(f"Plan generated with {len(plan_data.get('phases', []))} phases")
            
            # Create tasks from plan
            tasks = []
            task_id_map = {}  # Map temporary IDs to real IDs
            
            for phase_idx, phase in enumerate(plan_data.get("phases", [])):
                phase_name = phase.get("name", f"Phase {phase_idx + 1}")
                
                for task_idx, task_data in enumerate(phase.get("tasks", [])):
                    # Generate unique task ID
                    task_id = f"task_{uuid.uuid4().hex[:12]}"
                    temp_id = f"phase_{phase_idx}_task_{task_idx}"
                    task_id_map[temp_id] = task_id
                    
                    # Map task type string to enum
                    task_type_str = task_data.get("task_type", "custom")
                    try:
                        task_type = TaskTypeEnum[task_type_str.upper()]
                    except KeyError:
                        logger.warning(f"Unknown task type: {task_type_str}, using CUSTOM")
                        task_type = TaskTypeEnum.CUSTOM
                    
                    # Create task
                    task_model_data = {
                        "id": task_id,
                        "project_id": project.id,
                        "title": task_data.get("title", f"Task {task_idx + 1}"),
                        "description": task_data.get("description", ""),
                        "task_type": task_type,
                        "status": TaskStatusEnum.PENDING,
                        "complexity": task_data.get("complexity", 5),
                        "priority": phase_idx + 1,  # Earlier phases have higher priority
                        "dependencies": [],  # Will be updated after all tasks created
                        "meta_data": {
                            "phase": phase_name,
                            "estimated_duration": task_data.get("estimated_duration"),
                            "temp_id": temp_id
                        }
                    }
                    
                    task_model = TaskCRUD.create(self.db, task_model_data)
                    tasks.append(task_model)
                    
                    logger.debug(f"Created task: {task_id} - {task_model_data['title']}")
            
            # Update task dependencies with real IDs
            for task in tasks:
                temp_id = task.meta_data.get("temp_id")
                if temp_id:
                    # Find corresponding task data in plan
                    for phase in plan_data.get("phases", []):
                        for task_data in phase.get("tasks", []):
                            # Map dependency temp IDs to real IDs
                            dep_temp_ids = task_data.get("dependencies", [])
                            real_deps = [task_id_map.get(dep_id) for dep_id in dep_temp_ids if dep_id in task_id_map]
                            
                            if real_deps:
                                TaskCRUD.update(self.db, task.id, {"dependencies": real_deps})
            
            logger.info(f"Plan generated successfully: {len(tasks)} tasks created")
            return tasks
            
        except Exception as e:
            logger.error(f"Error generating plan: {e}", exc_info=True)
            
            # Fallback: Create basic tasks
            logger.warning("Using fallback task generation")
            return self._create_fallback_tasks(project)
    
    def _create_fallback_tasks(self, project: ProjectModel) -> List[TaskModel]:
        """
        Create basic fallback tasks if LLM planning fails.
        
        Args:
            project: Project model
            
        Returns:
            List of basic tasks
        """
        logger.info("Creating fallback tasks")
        
        basic_tasks = [
            {
                "title": "Project Setup",
                "description": "Initial project setup and planning",
                "task_type": TaskTypeEnum.RESEARCH,
                "complexity": 3
            },
            {
                "title": "Script Writing",
                "description": f"Write script for {project.title}",
                "task_type": TaskTypeEnum.SCRIPT_WRITING,
                "complexity": 7
            },
            {
                "title": "Storyboard Creation",
                "description": "Create visual storyboard",
                "task_type": TaskTypeEnum.STORYBOARD,
                "complexity": 6
            },
            {
                "title": "Media Generation",
                "description": "Generate required media assets",
                "task_type": TaskTypeEnum.IMAGE_GENERATION,
                "complexity": 8
            },
            {
                "title": "Final Review",
                "description": "Review and finalize project",
                "task_type": TaskTypeEnum.CUSTOM,
                "complexity": 4
            }
        ]
        
        tasks = []
        prev_task_id = None
        
        for idx, task_data in enumerate(basic_tasks):
            task_id = f"task_{uuid.uuid4().hex[:12]}"
            
            task_model_data = {
                "id": task_id,
                "project_id": project.id,
                "title": task_data["title"],
                "description": task_data["description"],
                "task_type": task_data["task_type"],
                "status": TaskStatusEnum.PENDING,
                "complexity": task_data["complexity"],
                "priority": idx + 1,
                "dependencies": [prev_task_id] if prev_task_id else [],
                "meta_data": {"fallback": True}
            }
            
            task_model = TaskCRUD.create(self.db, task_model_data)
            tasks.append(task_model)
            prev_task_id = task_id
        
        logger.info(f"Created {len(tasks)} fallback tasks")
        return tasks
    
    def get_project_status(self, project_id: str) -> Optional[Dict[str, Any]]:
        """
        Get comprehensive project status.
        
        Args:
            project_id: Project ID
            
        Returns:
            Project status dictionary with tasks and progress
        """
        project = ProjectCRUD.get(self.db, project_id)
        if not project:
            logger.warning(f"Project not found: {project_id}")
            return None
        
        tasks = TaskCRUD.get_by_project(self.db, project_id)
        
        # Calculate progress
        total_tasks = len(tasks)
        completed_tasks = sum(1 for t in tasks if t.status == TaskStatusEnum.COMPLETED)
        in_progress_tasks = sum(1 for t in tasks if t.status == TaskStatusEnum.IN_PROGRESS)
        failed_tasks = sum(1 for t in tasks if t.status == TaskStatusEnum.FAILED)
        
        progress_percentage = (completed_tasks / total_tasks * 100) if total_tasks > 0 else 0
        
        status = {
            "project": project.to_dict(),
            "tasks": [task.to_dict() for task in tasks],
            "progress": {
                "total_tasks": total_tasks,
                "completed": completed_tasks,
                "in_progress": in_progress_tasks,
                "failed": failed_tasks,
                "pending": total_tasks - completed_tasks - in_progress_tasks - failed_tasks,
                "percentage": round(progress_percentage, 2)
            }
        }
        
        logger.debug(f"Project status retrieved: {project_id} - {progress_percentage:.1f}% complete")
        return status
    
    def assign_task(self, task_id: str, worker_id: str) -> Optional[TaskModel]:
        """
        Assign a task to a worker agent.
        
        Args:
            task_id: Task ID
            worker_id: Worker agent ID
            
        Returns:
            Updated task model
        """
        logger.info(f"Assigning task {task_id} to worker {worker_id}")
        
        task = TaskCRUD.update(self.db, task_id, {
            "assigned_to": worker_id,
            "status": TaskStatusEnum.ASSIGNED
        })
        
        return task
    
    def update_task_status(self, task_id: str, status: str, result: Optional[Dict] = None) -> Optional[TaskModel]:
        """
        Update task status and result.
        
        Args:
            task_id: Task ID
            status: New status
            result: Optional task result
            
        Returns:
            Updated task model
        """
        logger.info(f"Updating task {task_id} status to {status}")
        
        updates = {"status": TaskStatusEnum[status.upper()]}
        
        if status.upper() == "IN_PROGRESS":
            updates["started_at"] = datetime.utcnow()
        elif status.upper() == "COMPLETED":
            updates["completed_at"] = datetime.utcnow()
        
        if result:
            updates["result"] = result
        
        task = TaskCRUD.update(self.db, task_id, updates)
        return task
    
    def dispatch_task(self, task_id: str) -> Optional[Dict[str, Any]]:
        """
        Dispatch a task to the appropriate worker agent.
        
        Args:
            task_id: Task ID
            
        Returns:
            Task result
        """
        logger.info(f"Dispatching task {task_id}")
        
        task = TaskCRUD.get(self.db, task_id)
        if not task:
            logger.error(f"Task not found: {task_id}")
            return None
            
        # Determine worker type
        worker = None
        if task.task_type == TaskTypeEnum.SCRIPT_WRITING:
            worker = ScriptWriter(self.db)
        # Add other workers here as they are implemented
        
        if worker:
            logger.info(f"Assigning task {task_id} to {worker.agent_type}")
            self.assign_task(task_id, worker.agent_type)
            return worker.process_task(task)
        else:
            logger.warning(f"No suitable worker found for task type: {task.task_type}")
            return None
    
    def _model_to_pydantic(self, project_model: ProjectModel) -> Project:
        """Convert database model to Pydantic model"""
        return Project(**project_model.to_dict())

    def start_project(self, project_id: str) -> Optional[ProjectModel]:
        """Start a project."""
        logger.info(f"Starting project {project_id}")
        return ProjectCRUD.update(self.db, project_id, {"status": ProjectStatusEnum.IN_PROGRESS})

    def pause_project(self, project_id: str) -> Optional[ProjectModel]:
        """Pause a project."""
        logger.info(f"Pausing project {project_id}")
        return ProjectCRUD.update(self.db, project_id, {"status": ProjectStatusEnum.PAUSED})

    def stop_project(self, project_id: str) -> Optional[ProjectModel]:
        """Stop a project."""
        logger.info(f"Stopping project {project_id}")
        return ProjectCRUD.update(self.db, project_id, {"status": ProjectStatusEnum.STOPPED})

    def update_project(self, project_id: str, updates: Dict[str, Any]) -> Optional[ProjectModel]:
        """Update a project."""
        logger.info(f"Updating project {project_id} with {updates}")
        return ProjectCRUD.update(self.db, project_id, updates)

    def delete_project(self, project_id: str):
        """Delete a project."""
        logger.info(f"Deleting project {project_id}")
        ProjectCRUD.delete(self.db, project_id)
