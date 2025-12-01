from fastapi import FastAPI, HTTPException, Depends
from fastapi.middleware.cors import CORSMiddleware
import ollama
import uvicorn
from typing import Dict, List
import logging
from sqlalchemy.orm import Session

from models import (
    ChatRequest, 
    ChatResponse, 
    PersonalityProfile,
    HealthStatus,
    ProjectRequest,
    Project
)
from admin_ai import AdminAI
from config import settings
from logging_config import setup_logging
from database import init_db, get_db, ProjectCRUD, TaskCRUD
from project_manager import ProjectManager
from worker_registry import get_registry, initialize_workers
from task_executor import TaskExecutor

# Initialize logging with dated files
log_file = setup_logging(
    log_level=settings.log_level,
    log_dir=settings.log_dir,
    enable_console=settings.enable_console_log,
    enable_file=settings.enable_file_log,
    enable_json=settings.enable_json_log,
    use_dated_files=True  # Always use dated log files
)

logger = logging.getLogger(__name__)

if log_file:
    logger.info(f"Log file: {log_file}")

app = FastAPI(
    title=settings.app_name,
    version=settings.app_version,
    description="NoSlop Backend - Self-hosted AI-driven media creation platform"
)

logger.info(f"Initializing {settings.app_name} v{settings.app_version}")

# Initialize database
if settings.enable_project_manager:
    logger.info("Initializing database...")
    init_db()
    logger.info("Database initialized")
    
    # Initialize worker registry
    logger.info("Initializing worker registry...")
    initialize_workers()
    logger.info("Worker registry initialized")

# CORS middleware for frontend communication
app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.cors_origins_list,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

logger.info(f"CORS enabled for origins: {settings.cors_origins_list}")

# Global Admin AI instance (in production, this would be per-user)
admin_ai_instance: Dict[str, AdminAI] = {}

def get_admin_ai(session_id: str = "default") -> AdminAI:
    """Get or create Admin AI instance for session"""
    if session_id not in admin_ai_instance:
        logger.info(f"Creating new Admin AI instance for session: {session_id}")
        admin_ai_instance[session_id] = AdminAI()
    return admin_ai_instance[session_id]


@app.get("/")
def read_root():
    return {
        "message": "NoSlop Backend API",
        "version": settings.app_version,
        "docs": "/docs"
    }


@app.get("/health", response_model=HealthStatus)
def health_check():
    """
    Checks the health of the backend and the connection to Ollama.
    """
    logger.debug("Health check requested")
    try:
        # Attempt to list models to verify connection
        models = ollama.list()
        
        # Check optional services
        services = {
            "ollama": True,
            "comfyui": False,  # TODO: Implement ComfyUI health check
            "database": True   # TODO: Implement database health check
        }
        
        logger.debug(f"Health check passed: {len(models.get('models', []))} models available")
        
        return HealthStatus(
            status="ok", 
            ollama="connected", 
            model_count=len(models.get('models', [])),
            services=services
        )
    except Exception as e:
        logger.error(f"Health check failed: {e}", exc_info=True)
        return HealthStatus(
            status="degraded", 
            ollama="disconnected", 
            model_count=0,
            services={"ollama": False}
        )


@app.post("/api/chat", response_model=ChatResponse)
async def chat_with_admin_ai(request: ChatRequest, session_id: str = "default", db: Session = Depends(get_db)):
    """
    Chat with the Admin AI.
    
    Args:
        request: Chat request with message and optional context
        session_id: Session identifier for conversation continuity
        db: Database session
        
    Returns:
        ChatResponse with AI message and suggestions
    """
    logger.info(f"Chat request from session {session_id}")
    try:
        admin_ai = get_admin_ai(session_id)
        
        # Update personality if provided
        if request.personality:
            admin_ai.personality = request.personality
            logger.debug(f"Personality updated for session {session_id}")
        
        # Process chat
        response = admin_ai.chat(request.message, request.context, db=db)
        
        return response
        
    except Exception as e:
        logger.error(f"Chat endpoint error: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=f"Chat error: {str(e)}")


@app.get("/api/personality/{personality_type}")
async def get_personality(personality_type: str):
    """
    Get a preset personality profile.
    
    Args:
        personality_type: One of 'creative', 'technical', 'balanced'
        
    Returns:
        PersonalityProfile configuration
    """
    if personality_type not in settings.personality_presets:
        raise HTTPException(status_code=404, detail=f"Personality type '{personality_type}' not found")
    
    return settings.personality_presets[personality_type]


@app.post("/api/personality")
async def set_personality(personality: PersonalityProfile, session_id: str = "default"):
    """
    Set custom personality for Admin AI.
    
    Args:
        personality: Custom personality configuration
        session_id: Session identifier
        
    Returns:
        Success message
    """
    try:
        admin_ai = get_admin_ai(session_id)
        admin_ai.personality = personality
        
        return {
            "status": "success",
            "message": "Personality updated",
            "personality": personality.dict()
        }
        
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Error setting personality: {str(e)}")


@app.post("/api/chat/clear")
async def clear_chat_history(session_id: str = "default"):
    """
    Clear conversation history for a session.
    
    Args:
        session_id: Session identifier
        
    Returns:
        Success message
    """
    admin_ai = get_admin_ai(session_id)
    admin_ai.clear_history()
    
    return {
        "status": "success",
        "message": "Conversation history cleared"
    }


@app.get("/api/suggestions/{project_type}")
async def get_project_suggestions(project_type: str, session_id: str = "default"):
    """
    Get creative suggestions for a project type.
    
    Args:
        project_type: Type of project (e.g., 'cinematic_film', 'vlog')
        session_id: Session identifier
        
    Returns:
        List of creative suggestions
    """
    try:
        admin_ai = get_admin_ai(session_id)
        suggestions = admin_ai.get_suggestions(project_type)
        
        return {
            "project_type": project_type,
            "suggestions": suggestions
        }
        
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Error generating suggestions: {str(e)}")


# ============================================================================
# Project Manager Endpoints
# ============================================================================

@app.post("/api/projects", response_model=Project)
async def create_project(project_request: ProjectRequest, db: Session = Depends(get_db)):
    """
    Create a new media project.
    
    Args:
        project_request: Project creation request
        db: Database session
        
    Returns:
        Created project with generated tasks
    """
    if not settings.enable_project_manager:
        raise HTTPException(status_code=503, detail="Project Manager is not enabled")
    
    logger.info(f"Creating project: {project_request.title}")
    
    try:
        pm = ProjectManager(db)
        project = pm.create_project(project_request)
        
        return project
        
    except Exception as e:
        logger.error(f"Error creating project: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=f"Error creating project: {str(e)}")


@app.get("/api/projects")
async def list_projects(skip: int = 0, limit: int = 100, db: Session = Depends(get_db)):
    """
    List all projects.
    
    Args:
        skip: Number of projects to skip (pagination)
        limit: Maximum number of projects to return
        db: Database session
        
    Returns:
        List of projects
    """
    if not settings.enable_project_manager:
        raise HTTPException(status_code=503, detail="Project Manager is not enabled")
    
    try:
        projects = ProjectCRUD.get_all(db, skip=skip, limit=limit)
        return {"projects": [p.to_dict() for p in projects]}
        
    except Exception as e:
        logger.error(f"Error listing projects: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=f"Error listing projects: {str(e)}")


@app.get("/api/projects/{project_id}")
async def get_project(project_id: str, db: Session = Depends(get_db)):
    """
    Get project details and status.
    
    Args:
        project_id: Project ID
        db: Database session
        
    Returns:
        Project details with tasks and progress
    """
    if not settings.enable_project_manager:
        raise HTTPException(status_code=503, detail="Project Manager is not enabled")
    
    try:
        pm = ProjectManager(db)
        status = pm.get_project_status(project_id)
        
        if not status:
            raise HTTPException(status_code=404, detail=f"Project not found: {project_id}")
        
        return status
        
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Error getting project: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=f"Error getting project: {str(e)}")


@app.get("/api/projects/{project_id}/tasks")
async def get_project_tasks(project_id: str, db: Session = Depends(get_db)):
    """
    Get all tasks for a project.
    
    Args:
        project_id: Project ID
        db: Database session
        
    Returns:
        List of tasks
    """
    if not settings.enable_project_manager:
        raise HTTPException(status_code=503, detail="Project Manager is not enabled")
    
    try:
        tasks = TaskCRUD.get_by_project(db, project_id)
        return {"tasks": [t.to_dict() for t in tasks]}
        
    except Exception as e:
        logger.error(f"Error getting tasks: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=f"Error getting tasks: {str(e)}")


@app.put("/api/tasks/{task_id}/status")
async def update_task_status(
    task_id: str,
    status: str,
    result: Dict = None,
    db: Session = Depends(get_db)
):
    """
    Update task status.
    
    Args:
        task_id: Task ID
        status: New status (pending, assigned, in_progress, completed, failed)
        result: Optional task result
        db: Database session
        
    Returns:
        Updated task
    """
    if not settings.enable_project_manager:
        raise HTTPException(status_code=503, detail="Project Manager is not enabled")
    
    try:
        pm = ProjectManager(db)
        task = pm.update_task_status(task_id, status, result)
        
        if not task:
            raise HTTPException(status_code=404, detail=f"Task not found: {task_id}")
        
        return task.to_dict()
        
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Error updating task status: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=f"Error updating task status: {str(e)}")


@app.post("/api/tasks/{task_id}/execute")
async def execute_task(task_id: str, db: Session = Depends(get_db)):
    """
    Manually trigger execution of a task.
    
    Args:
        task_id: Task ID
        db: Database session
        
    Returns:
        Task result
    """
    if not settings.enable_project_manager:
        raise HTTPException(status_code=503, detail="Project Manager is not enabled")
    
    logger.info(f"Manual execution requested for task {task_id}")
    
    try:
        pm = ProjectManager(db)
        result = pm.dispatch_task(task_id)
        
        if not result:
            # Check if task exists but no worker found or other issue
            task = TaskCRUD.get(db, task_id)
            if not task:
                raise HTTPException(status_code=404, detail=f"Task not found: {task_id}")
            
            return {"status": "no_action_taken", "message": "No suitable worker found or task execution failed"}
            
        return result
        
    except Exception as e:
        logger.error(f"Error executing task: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=f"Error executing task: {str(e)}")


# ============================================================================
# Worker Registry Endpoints
# ============================================================================

@app.get("/api/workers")
async def list_workers():
    """
    List all available worker agents with their capabilities.
    
    Returns:
        List of worker metadata
    """
    if not settings.enable_project_manager:
        raise HTTPException(status_code=503, detail="Project Manager is not enabled")
    
    try:
        registry = get_registry()
        workers = registry.list_available_workers()
        
        return {
            "workers": workers,
            "total": len(workers)
        }
        
    except Exception as e:
        logger.error(f"Error listing workers: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=f"Error listing workers: {str(e)}")


@app.get("/api/workers/{worker_type}/capabilities")
async def get_worker_capabilities(worker_type: str):
    """
    Get capabilities for a specific worker type.
    
    Args:
        worker_type: Worker agent type
        
    Returns:
        Worker capabilities
    """
    if not settings.enable_project_manager:
        raise HTTPException(status_code=503, detail="Project Manager is not enabled")
    
    try:
        registry = get_registry()
        capabilities = registry.get_worker_capabilities(worker_type)
        
        if not capabilities:
            raise HTTPException(status_code=404, detail=f"Worker type not found: {worker_type}")
        
        return capabilities
        
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Error getting worker capabilities: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=f"Error getting worker capabilities: {str(e)}")


@app.get("/api/workers/task-mapping")
async def get_task_type_mapping():
    """
    Get mapping of task types to worker types.
    
    Returns:
        Dictionary mapping task types to worker types
    """
    if not settings.enable_project_manager:
        raise HTTPException(status_code=503, detail="Project Manager is not enabled")
    
    try:
        registry = get_registry()
        mapping = registry.get_task_type_mapping()
        
        return {
            "mapping": mapping
        }
        
    except Exception as e:
        logger.error(f"Error getting task mapping: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=f"Error getting task mapping: {str(e)}")


# ============================================================================
# Task Executor Endpoints
# ============================================================================

@app.post("/api/projects/{project_id}/execute")
async def execute_project(project_id: str, db: Session = Depends(get_db)):
    """
    Execute all tasks for a project with dependency resolution.
    
    Args:
        project_id: Project ID
        db: Database session
        
    Returns:
        Execution summary
    """
    if not settings.enable_project_manager:
        raise HTTPException(status_code=503, detail="Project Manager is not enabled")
    
    logger.info(f"Project execution requested: {project_id}")
    
    try:
        executor = TaskExecutor(db)
        result = executor.execute_project(project_id)
        
        return result
        
    except Exception as e:
        logger.error(f"Error executing project: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=f"Error executing project: {str(e)}")


@app.post("/api/tasks/{task_id}/execute-with-dependencies")
async def execute_task_with_dependencies(task_id: str, db: Session = Depends(get_db)):
    """
    Execute a task and all its dependencies.
    
    Args:
        task_id: Task ID
        db: Database session
        
    Returns:
        Execution result
    """
    if not settings.enable_project_manager:
        raise HTTPException(status_code=503, detail="Project Manager is not enabled")
    
    logger.info(f"Task execution with dependencies requested: {task_id}")
    
    try:
        executor = TaskExecutor(db)
        result = executor.execute_task_with_dependencies(task_id)
        
        return result
        
    except Exception as e:
        logger.error(f"Error executing task with dependencies: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=f"Error executing task with dependencies: {str(e)}")


@app.get("/api/tasks/{task_id}/progress")
async def get_task_progress(task_id: str, db: Session = Depends(get_db)):
    """
    Get current progress of a task.
    
    Args:
        task_id: Task ID
        db: Database session
        
    Returns:
        Task progress information
    """
    if not settings.enable_project_manager:
        raise HTTPException(status_code=503, detail="Project Manager is not enabled")
    
    try:
        executor = TaskExecutor(db)
        progress = executor.monitor_task_progress(task_id)
        
        if not progress:
            raise HTTPException(status_code=404, detail=f"Task not found: {task_id}")
        
        return progress
        
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Error getting task progress: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=f"Error getting task progress: {str(e)}")


if __name__ == "__main__":
    logger.info("="*60)
    logger.info(f"🚀 Starting {settings.app_name} v{settings.app_version}")
    logger.info(f"📡 Ollama endpoint: {settings.ollama_host}")
    logger.info(f"💾 Database: {settings.database_url}")
    logger.info(f"📁 Media storage: {settings.media_storage_path}")
    logger.info(f"📁 Project storage: {settings.project_storage_path}")
    logger.info(f"📝 Log level: {settings.log_level}")
    logger.info(f"🔧 Debug mode: {settings.debug}")
    logger.info("="*60)
    
    uvicorn.run(app, host=settings.host, port=settings.port)
