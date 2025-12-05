# START OF FILE backend/database.py
"""
Database layer for NoSlop.

Provides SQLAlchemy models and CRUD operations for projects and tasks.
Uses SQLite by default, but supports PostgreSQL for production.
"""

from sqlalchemy import create_engine, Column, String, Integer, Float, DateTime, Text, JSON, ForeignKey, Enum as SQLEnum
from sqlalchemy.ext.declarative import declarative_base
from sqlalchemy.orm import sessionmaker, relationship, Session
from datetime import datetime
from typing import List, Optional, Dict, Any
import logging
import enum

from config import settings

logger = logging.getLogger(__name__)

# Create database engine
engine = create_engine(
    settings.database_url,
    echo=settings.debug,  # Log SQL queries in debug mode
    connect_args={"check_same_thread": False} if "sqlite" in settings.database_url else {}
)

# Create session factory
SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)

# Base class for models
Base = declarative_base()


# ============================================================================
# Enums
# ============================================================================

class ProjectTypeEnum(str, enum.Enum):
    """Types of media projects"""
    CINEMATIC_FILM = "cinematic_film"
    CORPORATE_VIDEO = "corporate_video"
    ADVERTISEMENT = "advertisement"
    COMEDY_SKIT = "comedy_skit"
    CARTOON = "cartoon"
    VLOG = "vlog"
    PODCAST = "podcast"
    MUSIC_VIDEO = "music_video"
    DOCUMENTARY = "documentary"
    CUSTOM = "custom"


class ProjectStatusEnum(str, enum.Enum):
    """Project lifecycle status"""
    PLANNING = "planning"
    IN_PROGRESS = "in_progress"
    REVIEW = "review"
    COMPLETED = "completed"
    PAUSED = "paused"
    CANCELLED = "cancelled"


class TaskStatusEnum(str, enum.Enum):
    """Task execution status"""
    PENDING = "pending"
    ASSIGNED = "assigned"
    IN_PROGRESS = "in_progress"
    COMPLETED = "completed"
    FAILED = "failed"


class TaskTypeEnum(str, enum.Enum):
    """Types of tasks"""
    SCRIPT_WRITING = "script_writing"
    PROMPT_ENGINEERING = "prompt_engineering"
    IMAGE_GENERATION = "image_generation"
    VIDEO_GENERATION = "video_generation"
    VIDEO_EDITING = "video_editing"
    AUDIO_MIXING = "audio_mixing"
    COLOR_GRADING = "color_grading"
    STORYBOARD = "storyboard"
    RESEARCH = "research"
    CUSTOM = "custom"


# ============================================================================
# Models
# ============================================================================

class ProjectModel(Base):
    """Database model for projects"""
    __tablename__ = "projects"
    
    id = Column(String, primary_key=True)
    title = Column(String, nullable=False)
    project_type = Column(SQLEnum(ProjectTypeEnum), nullable=False)
    description = Column(Text, nullable=False)
    status = Column(SQLEnum(ProjectStatusEnum), default=ProjectStatusEnum.PLANNING)
    
    # Timestamps
    created_at = Column(DateTime, default=datetime.utcnow)
    updated_at = Column(DateTime, default=datetime.utcnow, onupdate=datetime.utcnow)
    
    # Optional fields
    duration = Column(Integer, nullable=True)  # Target duration in seconds
    style = Column(String, nullable=True)
    
    # JSON fields for flexibility
    reference_media = Column(JSON, default=list)  # List of reference media paths
    meta_data = Column(JSON, default=dict)  # Additional metadata
    
    # Relationships
    tasks = relationship("TaskModel", back_populates="project", cascade="all, delete-orphan")
    
    def to_dict(self) -> Dict[str, Any]:
        """Convert model to dictionary"""
        return {
            "id": self.id,
            "title": self.title,
            "project_type": self.project_type.value,
            "description": self.description,
            "status": self.status.value,
            "created_at": self.created_at.isoformat() if self.created_at else None,
            "updated_at": self.updated_at.isoformat() if self.updated_at else None,
            "duration": self.duration,
            "style": self.style,
            "reference_media": self.reference_media or [],
            "metadata": self.meta_data or {}
        }


class TaskModel(Base):
    """Database model for tasks"""
    __tablename__ = "tasks"
    
    id = Column(String, primary_key=True)
    project_id = Column(String, ForeignKey("projects.id"), nullable=False)
    
    title = Column(String, nullable=False)
    description = Column(Text, nullable=False)
    task_type = Column(SQLEnum(TaskTypeEnum), nullable=False)
    status = Column(SQLEnum(TaskStatusEnum), default=TaskStatusEnum.PENDING)
    
    # Assignment
    assigned_to = Column(String, nullable=True)  # Worker agent ID
    
    # Complexity and priority
    complexity = Column(Integer, default=5)  # 1-10 scale
    priority = Column(Integer, default=5)  # 1-10 scale
    
    # Timestamps
    created_at = Column(DateTime, default=datetime.utcnow)
    updated_at = Column(DateTime, default=datetime.utcnow, onupdate=datetime.utcnow)
    started_at = Column(DateTime, nullable=True)
    completed_at = Column(DateTime, nullable=True)
    
    # JSON fields
    dependencies = Column(JSON, default=list)  # List of task IDs this depends on
    result = Column(JSON, nullable=True)  # Task execution result
    meta_data = Column(JSON, default=dict)  # Additional metadata
    
    # Relationships
    project = relationship("ProjectModel", back_populates="tasks")
    
    def to_dict(self) -> Dict[str, Any]:
        """Convert model to dictionary"""
        return {
            "id": self.id,
            "project_id": self.project_id,
            "title": self.title,
            "description": self.description,
            "task_type": self.task_type.value,
            "status": self.status.value,
            "assigned_to": self.assigned_to,
            "complexity": self.complexity,
            "priority": self.priority,
            "created_at": self.created_at.isoformat() if self.created_at else None,
            "updated_at": self.updated_at.isoformat() if self.updated_at else None,
            "started_at": self.started_at.isoformat() if self.started_at else None,
            "completed_at": self.completed_at.isoformat() if self.completed_at else None,
            "dependencies": self.dependencies or [],
            "result": self.result,
            "metadata": self.meta_data or {}
        }


# ============================================================================
# Database Initialization
# ============================================================================

def init_db():
    """Initialize database tables"""
    logger.info("Initializing database...")
    Base.metadata.create_all(bind=engine)
    logger.info("Database initialized successfully")


def get_db() -> Session:
    """
    Get database session.
    Use as dependency injection in FastAPI endpoints.
    
    Example:
        @app.get("/projects")
        def get_projects(db: Session = Depends(get_db)):
            return db.query(ProjectModel).all()
    """
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()


# ============================================================================
# CRUD Operations
# ============================================================================

class ProjectCRUD:
    """CRUD operations for projects"""
    
    @staticmethod
    def create(db: Session, project_data: Dict[str, Any]) -> ProjectModel:
        """Create a new project"""
        logger.info(f"Creating project: {project_data.get('title')}")
        
        project = ProjectModel(**project_data)
        db.add(project)
        db.commit()
        db.refresh(project)
        
        logger.info(f"Project created: {project.id}")
        return project
    
    @staticmethod
    def get(db: Session, project_id: str) -> Optional[ProjectModel]:
        """Get project by ID"""
        return db.query(ProjectModel).filter(ProjectModel.id == project_id).first()
    
    @staticmethod
    def get_all(db: Session, skip: int = 0, limit: int = 100) -> List[ProjectModel]:
        """Get all projects with pagination"""
        return db.query(ProjectModel).offset(skip).limit(limit).all()
    
    @staticmethod
    def update(db: Session, project_id: str, updates: Dict[str, Any]) -> Optional[ProjectModel]:
        """Update project"""
        project = ProjectCRUD.get(db, project_id)
        if not project:
            return None
        
        for key, value in updates.items():
            if hasattr(project, key):
                setattr(project, key, value)
        
        project.updated_at = datetime.utcnow()
        db.commit()
        db.refresh(project)
        
        logger.info(f"Project updated: {project_id}")
        return project
    
    @staticmethod
    def delete(db: Session, project_id: str) -> bool:
        """Delete project"""
        project = ProjectCRUD.get(db, project_id)
        if not project:
            return False
        
        db.delete(project)
        db.commit()
        
        logger.info(f"Project deleted: {project_id}")
        return True


class TaskCRUD:
    """CRUD operations for tasks"""
    
    @staticmethod
    def create(db: Session, task_data: Dict[str, Any]) -> TaskModel:
        """Create a new task"""
        logger.info(f"Creating task: {task_data.get('title')}")
        
        task = TaskModel(**task_data)
        db.add(task)
        db.commit()
        db.refresh(task)
        
        logger.info(f"Task created: {task.id}")
        return task
    
    @staticmethod
    def get(db: Session, task_id: str) -> Optional[TaskModel]:
        """Get task by ID"""
        return db.query(TaskModel).filter(TaskModel.id == task_id).first()
    
    @staticmethod
    def get_by_project(db: Session, project_id: str) -> List[TaskModel]:
        """Get all tasks for a project"""
        return db.query(TaskModel).filter(TaskModel.project_id == project_id).all()
    
    @staticmethod
    def update(db: Session, task_id: str, updates: Dict[str, Any]) -> Optional[TaskModel]:
        """Update task"""
        task = TaskCRUD.get(db, task_id)
        if not task:
            return None
        
        for key, value in updates.items():
            if hasattr(task, key):
                setattr(task, key, value)
        
        task.updated_at = datetime.utcnow()
        db.commit()
        db.refresh(task)
        
        logger.info(f"Task updated: {task_id}")
        return task
    
    @staticmethod
    def delete(db: Session, task_id: str) -> bool:
        """Delete task"""
        task = TaskCRUD.get(db, task_id)
        if not task:
            return False
        
        db.delete(task)
        db.commit()
        
        logger.info(f"Task deleted: {task_id}")
        return True


class UserModel(Base):
    """Database model for users"""
    __tablename__ = "users"
    
    id = Column(String, primary_key=True)
    username = Column(String, unique=True, nullable=False)
    email = Column(String, unique=True, nullable=True)
    hashed_password = Column(String, nullable=False)
    
    # Admin AI Personality Settings
    personality_type = Column(String, default="balanced")  # creative, technical, balanced
    personality_formality = Column(Float, default=0.5)
    personality_enthusiasm = Column(Float, default=0.5)
    personality_verbosity = Column(Float, default=0.5)
    
    # Timestamps
    created_at = Column(DateTime, default=datetime.utcnow)
    updated_at = Column(DateTime, default=datetime.utcnow, onupdate=datetime.utcnow)
    last_login = Column(DateTime, nullable=True)
    
    # JSON fields
    preferences = Column(JSON, default=dict)  # Additional user preferences
    
    def to_dict(self) -> Dict[str, Any]:
        return {
            "id": self.id,
            "username": self.username,
            "email": self.email,
            "personality": {
                "type": self.personality_type,
                "formality": self.personality_formality,
                "enthusiasm": self.personality_enthusiasm,
                "verbosity": self.personality_verbosity
            },
            "preferences": self.preferences or {},
            "created_at": self.created_at.isoformat() if self.created_at else None,
            "last_login": self.last_login.isoformat() if self.last_login else None
        }


class UserCRUD:
    """CRUD operations for users"""
    
    @staticmethod
    def create(db: Session, user_data: Dict[str, Any]) -> UserModel:
        """Create a new user"""
        logger.info(f"Creating user: {user_data.get('username')}")
        
        user = UserModel(**user_data)
        db.add(user)
        db.commit()
        db.refresh(user)
        
        logger.info(f"User created: {user.id}")
        return user
    
    @staticmethod
    def get(db: Session, user_id: str) -> Optional[UserModel]:
        """Get user by ID"""
        return db.query(UserModel).filter(UserModel.id == user_id).first()
    
    @staticmethod
    def get_by_username(db: Session, username: str) -> Optional[UserModel]:
        """Get user by username"""
        return db.query(UserModel).filter(UserModel.username == username).first()
    
    @staticmethod
    def update(db: Session, user_id: str, updates: Dict[str, Any]) -> Optional[UserModel]:
        """Update user"""
        user = UserCRUD.get(db, user_id)
        if not user:
            return None
        
        for key, value in updates.items():
            if hasattr(user, key):
                setattr(user, key, value)
        
        user.updated_at = datetime.utcnow()
        db.commit()
        db.refresh(user)
        
        logger.info(f"User updated: {user_id}")
        return user

