# START OF FILE backend/verify_worker.py
import logging
import sys
import os

# Add backend directory to path
sys.path.append(os.path.dirname(os.path.abspath(__file__)))

from database import init_db, SessionLocal, ProjectCRUD, TaskCRUD, TaskTypeEnum, TaskStatusEnum
from project_manager import ProjectManager
from models import ProjectRequest

# Configure logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

def verify_worker():
    logger.info("Starting Worker Verification")
    
    # Initialize DB
    init_db()
    db = SessionLocal()
    
    try:
        pm = ProjectManager(db)
        
        # 1. Create Project
        logger.info("Creating test project...")
        project_req = ProjectRequest(
            title="Verification Project",
            description="A test project for worker verification.",
            project_type="custom",
            style="documentary"
        )
        project = pm.create_project(project_req)
        logger.info(f"Project created: {project.id}")
        
        # 2. Create a specific Script Writing Task
        logger.info("Creating script writing task...")
        task_data = {
            "id": f"task_verify_{project.id[-6:]}",
            "project_id": project.id,
            "title": "Write Intro Script",
            "description": "Write a 30-second intro script for the documentary.",
            "task_type": TaskTypeEnum.SCRIPT_WRITING,
            "status": TaskStatusEnum.PENDING,
            "complexity": 3,
            "priority": 1,
            "dependencies": [],
            "meta_data": {}
        }
        task = TaskCRUD.create(db, task_data)
        logger.info(f"Task created: {task.id}")
        
        # 3. Dispatch Task
        logger.info("Dispatching task...")
        result = pm.dispatch_task(task.id)
        
        if result:
            logger.info("Task execution successful!")
            logger.info(f"Result: {result}")
        else:
            logger.error("Task execution failed or returned None")
            
    except Exception as e:
        logger.error(f"Verification failed: {e}", exc_info=True)
    finally:
        db.close()

if __name__ == "__main__":
    verify_worker()
