# START OF FILE backend/task_executor.py
"""
Task Executor for NoSlop.

Handles automatic task dispatching with dependency resolution.
Features:
- Dependency graph building
- Topological sort for execution order
- Parallel execution of independent tasks
- Progress monitoring and status updates
- Error recovery and retry logic
"""

import logging
from typing import Dict, List, Optional, Set
from datetime import datetime
from sqlalchemy.orm import Session
import threading
from queue import Queue, Empty

from database import TaskModel, TaskCRUD, TaskStatusEnum, ProjectCRUD
from worker_registry import get_registry

logger = logging.getLogger(__name__)


class TaskExecutor:
    """
    Task executor with dependency resolution.
    
    Executes tasks in correct order based on dependencies,
    supports parallel execution of independent tasks.
    """
    
    def __init__(self, db: Session, manager):
        """
        Initialize task executor.
        
        Args:
            db: Database session
            manager: WebSocket connection manager
        """
        self.db = db
        self.registry = get_registry()
        self.manager = manager
        self.running = False
        self.task_queue = Queue()
        logger.info("Task Executor initialized")
    
    def build_dependency_graph(self, tasks: List[TaskModel]) -> Dict[str, Set[str]]:
        """
        Build dependency graph from task list.
        
        Args:
            tasks: List of tasks
            
        Returns:
            Dictionary mapping task IDs to their dependencies
        """
        graph = {}
        
        for task in tasks:
            task_id = task.id
            dependencies = set(task.dependencies) if task.dependencies else set()
            graph[task_id] = dependencies
        
        logger.debug(f"Built dependency graph with {len(graph)} tasks")
        return graph
    
    def topological_sort(self, graph: Dict[str, Set[str]]) -> List[List[str]]:
        """
        Perform topological sort to determine execution order.
        
        Groups tasks by execution level - tasks in the same level
        can be executed in parallel.
        
        Args:
            graph: Dependency graph
            
        Returns:
            List of task ID lists, where each inner list represents a level
            
        Raises:
            ValueError: If circular dependency detected
        """
        # Copy graph for modification
        remaining = {k: v.copy() for k, v in graph.items()}
        levels = []
        
        while remaining:
            # Find tasks with no dependencies
            ready = [task_id for task_id, deps in remaining.items() if not deps]
            
            if not ready:
                # Check for circular dependency
                raise ValueError(f"Circular dependency detected in tasks: {list(remaining.keys())}")
            
            levels.append(ready)
            
            # Remove completed tasks from remaining
            for task_id in ready:
                del remaining[task_id]
            
            # Remove completed tasks from other tasks' dependencies
            for deps in remaining.values():
                deps.difference_update(ready)
        
        logger.info(f"Topological sort complete: {len(levels)} execution levels")
        return levels
    
    def execute_project(self, project_id: str) -> Dict:
        """
        Execute all tasks for a project in correct order.
        
        Args:
            project_id: Project ID
            
        Returns:
            Execution summary
        """
        logger.info(f"Starting project execution: {project_id}")
        
        # Get all tasks for project
        tasks = TaskCRUD.get_by_project(self.db, project_id)
        
        if not tasks:
            logger.warning(f"No tasks found for project {project_id}")
            return {"status": "no_tasks", "message": "No tasks to execute"}
        
        # Filter only pending/assigned tasks
        pending_tasks = [
            t for t in tasks 
            if t.status in [TaskStatusEnum.PENDING, TaskStatusEnum.ASSIGNED]
        ]
        
        if not pending_tasks:
            logger.info(f"No pending tasks for project {project_id}")
            return {"status": "complete", "message": "All tasks already completed"}
        
        # Build dependency graph
        try:
            graph = self.build_dependency_graph(pending_tasks)
            execution_levels = self.topological_sort(graph)
        except ValueError as e:
            logger.error(f"Dependency resolution failed: {e}")
            return {"status": "error", "message": str(e)}
        
        # Execute tasks level by level
        results = {
            "project_id": project_id,
            "total_tasks": len(pending_tasks),
            "completed": 0,
            "failed": 0,
            "execution_levels": len(execution_levels)
        }
        
        for level_idx, level_tasks in enumerate(execution_levels):
            logger.info(f"Executing level {level_idx + 1}/{len(execution_levels)}: {len(level_tasks)} tasks")
            
            # Execute tasks in this level (could be parallel in future)
            for task_id in level_tasks:
                try:
                    task = TaskCRUD.get(self.db, task_id)
                    if not task:
                        logger.error(f"Task not found: {task_id}")
                        results["failed"] += 1
                        continue
                    
                    # Dispatch task to appropriate worker
                    result = self.dispatch_task(task)
                    
                    if result:
                        results["completed"] += 1
                        logger.info(f"Task {task_id} completed successfully")
                    else:
                        results["failed"] += 1
                        logger.warning(f"Task {task_id} failed")
                        
                except Exception as e:
                    logger.error(f"Error executing task {task_id}: {e}", exc_info=True)
                    results["failed"] += 1
        
        # Update project status
        if results["failed"] == 0:
            ProjectCRUD.update(self.db, project_id, {"status": "completed"})
            results["status"] = "success"
        else:
            results["status"] = "partial"
        
        logger.info(f"Project execution complete: {results}")
        return results
    
    def dispatch_task(self, task: TaskModel) -> Optional[Dict]:
        """
        Dispatch task to appropriate worker.
        
        Args:
            task: Task to dispatch
            
        Returns:
            Task result or None if failed
        """
        logger.info(f"Dispatching task: {task.id} (type: {task.task_type.value})")
        
        # Get appropriate worker
        worker = self.registry.get_worker_for_task_type(task.task_type, self.db)
        
        if not worker:
            logger.error(f"No worker found for task type: {task.task_type.value}")
            TaskCRUD.update(
                self.db,
                task.id,
                {
                    "status": TaskStatusEnum.FAILED,
                    "result": {"error": f"No worker available for {task.task_type.value}"}
                }
            )
            return None
        
        try:
            # Execute with retry logic
            result = worker.execute_with_retry(task)
            logger.info(f"Task {task.id} completed by {worker.agent_type}")
            return result
            
        except Exception as e:
            logger.error(f"Task {task.id} execution failed: {e}", exc_info=True)
            # Error already logged by worker
            return None
    
    def execute_task_with_dependencies(self, task_id: str) -> Dict:
        """
        Execute a single task and its dependencies.
        
        Args:
            task_id: Task ID
            
        Returns:
            Execution result
        """
        logger.info(f"Executing task with dependencies: {task_id}")
        
        task = TaskCRUD.get(self.db, task_id)
        if not task:
            return {"status": "error", "message": f"Task not found: {task_id}"}
        
        # Get all tasks for this project
        all_tasks = TaskCRUD.get_by_project(self.db, task.project_id)
        
        # Build dependency graph
        graph = self.build_dependency_graph(all_tasks)
        
        # Find all dependencies (recursive)
        dependencies = self._find_all_dependencies(task_id, graph)
        dependencies.add(task_id)  # Include the task itself
        
        # Create subgraph with only needed tasks
        subgraph = {tid: graph[tid] for tid in dependencies if tid in graph}
        
        try:
            execution_levels = self.topological_sort(subgraph)
        except ValueError as e:
            return {"status": "error", "message": str(e)}
        
        # Execute
        completed = 0
        failed = 0
        
        for level_tasks in execution_levels:
            for tid in level_tasks:
                t = TaskCRUD.get(self.db, tid)
                if t and t.status in [TaskStatusEnum.PENDING, TaskStatusEnum.ASSIGNED]:
                    result = self.dispatch_task(t)
                    if result:
                        completed += 1
                    else:
                        failed += 1
        
        return {
            "status": "success" if failed == 0 else "partial",
            "task_id": task_id,
            "completed": completed,
            "failed": failed
        }
    
    def _find_all_dependencies(self, task_id: str, graph: Dict[str, Set[str]]) -> Set[str]:
        """
        Recursively find all dependencies for a task.
        
        Args:
            task_id: Task ID
            graph: Dependency graph
            
        Returns:
            Set of all dependency task IDs
        """
        all_deps = set()
        
        if task_id not in graph:
            return all_deps
        
        direct_deps = graph[task_id]
        all_deps.update(direct_deps)
        
        # Recursively get dependencies of dependencies
        for dep_id in direct_deps:
            all_deps.update(self._find_all_dependencies(dep_id, graph))
        
        return all_deps
    
    def monitor_task_progress(self, task_id: str) -> Optional[Dict]:
        """
        Get current progress of a task.
        
        Args:
            task_id: Task ID
            
        Returns:
            Progress information
        """
        task = TaskCRUD.get(self.db, task_id)
        
        if not task:
            return None
        
        progress_info = {
            "task_id": task_id,
            "status": task.status.value,
            "progress": None
        }
        
        # Get progress from metadata if available
        if task.meta_data and "progress" in task.meta_data:
            progress_info["progress"] = task.meta_data["progress"]
        
        return progress_info
