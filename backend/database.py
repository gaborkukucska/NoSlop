# START OF FILE backend/database.py
"""
Database layer for NoSlop.

Provides SQLAlchemy models and CRUD operations for projects and tasks.
Uses SQLite by default, but supports PostgreSQL for production.
"""

from sqlalchemy import create_engine, Column, String, Integer, Float, DateTime, Text, JSON, ForeignKey, Boolean, Enum as SQLEnum
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
    STOPPED = "stopped"
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


class UserRoleEnum(str, enum.Enum):
    """User roles"""
    ADMIN = "admin"
    BASIC = "basic"


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
    
    # Storage tracking
    folder_path = Column(String, nullable=True)  # Path to project folder
    workflows_count = Column(Integer, default=0)  # Number of workflows
    media_count = Column(Integer, default=0)  # Number of generated media files
    storage_size_mb = Column(Float, default=0.0)  # Total storage used
    
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
            "folder_path": self.folder_path,
            "workflows_count": self.workflows_count,
            "media_count": self.media_count,
            "storage_size_mb": self.storage_size_mb,
            "reference_media": self.reference_media or [],
            "metadata": self.meta_data or {}
        }


class ChatSessionModel(Base):
    """Database model for chat sessions"""
    __tablename__ = "chat_sessions"
    
    id = Column(String, primary_key=True)  # UUID
    user_id = Column(String, ForeignKey("users.id"), nullable=True)
    title = Column(String, nullable=False, default="New Chat")
    created_at = Column(DateTime, default=datetime.utcnow)
    updated_at = Column(DateTime, default=datetime.utcnow, onupdate=datetime.utcnow)
    
    # Optional metadata
    meta_data = Column(JSON, default=dict)
    
    def to_dict(self) -> Dict[str, Any]:
        return {
            "id": self.id,
            "user_id": self.user_id,
            "title": self.title,
            "created_at": self.created_at.isoformat() if self.created_at else None,
            "updated_at": self.updated_at.isoformat() if self.updated_at else None,
            "metadata": self.meta_data or {}
        }


class ChatMessageModel(Base):
    """Database model for chat messages"""
    __tablename__ = "chat_messages"
    
    id = Column(Integer, primary_key=True, autoincrement=True)
    session_id = Column(String, index=True, default="default")  # Links to ChatSessionModel.id
    user_id = Column(String, ForeignKey("users.id"), nullable=True)
    role = Column(String, nullable=False)  # 'user' or 'assistant'
    content = Column(Text, nullable=False)
    timestamp = Column(DateTime, default=datetime.utcnow)
    
    # Optional metadata
    meta_data = Column(JSON, default=dict)
    
    def to_dict(self) -> Dict[str, Any]:
        return {
            "id": self.id,
            "session_id": self.session_id,
            "role": self.role,
            "content": self.content,
            "timestamp": self.timestamp.isoformat() if self.timestamp else None,
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
    
    @staticmethod
    def get_folder_path(db: Session, project_id: str) -> Optional[str]:
        """Get project folder path"""
        project = ProjectCRUD.get(db, project_id)
        return project.folder_path if project else None
    
    @staticmethod
    def update_storage_stats(db: Session, project_id: str, stats: Dict[str, Any]) -> Optional[ProjectModel]:
        """Update project storage statistics"""
        project = ProjectCRUD.get(db, project_id)
        if not project:
            return None
        
        if "workflows_count" in stats:
            project.workflows_count = stats["workflows_count"]
        if "media_count" in stats:
            project.media_count = stats["media_count"]
        if "storage_size_mb" in stats:
            project.storage_size_mb = stats["storage_size_mb"]
        
        project.updated_at = datetime.utcnow()
        db.commit()
        db.refresh(project)
        
        logger.debug(f"Storage stats updated for project: {project_id}")
        return project


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
    
    # Role and Status
    role = Column(SQLEnum(UserRoleEnum), default=UserRoleEnum.BASIC)
    is_active = Column(Boolean, default=True)
    
    # Profile
    bio = Column(Text, nullable=True)
    custom_data = Column(JSON, default=dict)
    
    # Extended Profile Fields (for Admin AI Personalization)
    display_name = Column(String, nullable=True)  # Public display name
    first_name = Column(String, nullable=True)
    last_name = Column(String, nullable=True)
    date_of_birth = Column(DateTime, nullable=True)
    location = Column(String, nullable=True)  # City, Country
    timezone = Column(String, default="UTC")  # User's timezone
    avatar_url = Column(String, nullable=True)  # Path to avatar image
    address = Column(Text, nullable=True)  # Full physical address (safe primarily because local-first)
    
    # Personalization Fields (for Admin AI to understand user better)
    interests = Column(JSON, default=list)  # ["photography", "filmmaking", "music", ...]
    occupation = Column(String, nullable=True)  # User's profession/job/role
    experience_level = Column(String, default="beginner")  # beginner/intermediate/advanced/expert
    preferred_media_types = Column(JSON, default=list)  # ["video", "podcast", "blog", ...]
    content_goals = Column(Text, nullable=True)  # What user wants to create/achieve
    
    # Social & Privacy
    social_links = Column(JSON, default=dict)  # {"twitter": "...", "youtube": "...", etc}
    profile_visibility = Column(String, default="private")  # private/friends/public

    # Admin AI Personality Settings
    personality_type = Column(String, default="balanced")  # creative, technical, balanced
    personality_formality = Column(Float, default=0.5)
    personality_enthusiasm = Column(Float, default=0.5)
    personality_verbosity = Column(Float, default=0.5)
    
    # Timestamps
    created_at = Column(DateTime, default=datetime.utcnow)
    updated_at = Column(DateTime, default=datetime.utcnow, onupdate=datetime.utcnow)
    last_login = Column(DateTime, nullable=True)
    
    # Security
    email_verified = Column(Boolean, default=False)
    password_changed_at = Column(DateTime, nullable=True)
    
    # JSON fields
    preferences = Column(JSON, default=dict)  # Additional user preferences
    
    def to_dict(self) -> Dict[str, Any]:
        return {
            "id": self.id,
            "username": self.username,
            "email": self.email,
            "role": self.role.value if self.role else "basic",
            "is_active": self.is_active,
            
            # Profile
            "bio": self.bio,
            "display_name": self.display_name,
            "first_name": self.first_name,
            "last_name": self.last_name,
            "date_of_birth": self.date_of_birth.isoformat() if self.date_of_birth else None,
            "location": self.location,
            "address": self.address,
            "timezone": self.timezone,
            "avatar_url": self.avatar_url,
            
            # Personalization
            "interests": self.interests or [],
            "occupation": self.occupation,
            "experience_level": self.experience_level,
            "preferred_media_types": self.preferred_media_types or [],
            "content_goals": self.content_goals,
            
            # Social
            "social_links": self.social_links or {},
            "profile_visibility": self.profile_visibility,
            
            # Custom data
            "custom_data": self.custom_data or {},
            
            # Personality
            "personality": {
                "type": self.personality_type,
                "formality": self.personality_formality,
                "enthusiasm": self.personality_enthusiasm,
                "verbosity": self.personality_verbosity
            },
            
            # Security
            "email_verified": self.email_verified,
            
            # Preferences
            "preferences": self.preferences or {},
            
            # Timestamps
            "created_at": self.created_at.isoformat() if self.created_at else None,
            "updated_at": self.updated_at.isoformat() if self.updated_at else None,
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


    @staticmethod
    def get_all(db: Session, skip: int = 0, limit: int = 100) -> List[UserModel]:
        """Get all users"""
        return db.query(UserModel).offset(skip).limit(limit).all()

    @staticmethod
    def delete(db: Session, user_id: str) -> bool:
        """Delete user (basic - does not cascade, use delete_cascade for complete removal)"""
        user = UserCRUD.get(db, user_id)
        if not user:
            return False
        
        db.delete(user)
        db.commit()
        logger.info(f"User deleted: {user_id}")
        return True
    
    @staticmethod
    def delete_cascade(db: Session, user_id: str) -> bool:
        """
        Delete user and ALL related data (cascade delete).
        This removes:
        - User record
        - All chat sessions
        - All chat messages
        - All projects created by user
        - All tasks for those projects
        """
        user = UserCRUD.get(db, user_id)
        if not user:
            return False
        
        logger.info(f"Starting cascade delete for user: {user_id}")
        
        try:
            # Delete chat messages
            message_count = db.query(ChatMessageModel).filter(
                ChatMessageModel.user_id == user_id
            ).delete(synchronize_session=False)
            logger.debug(f"Deleted {message_count} chat messages")
            
            # Delete chat sessions
            session_count = db.query(ChatSessionModel).filter(
                ChatSessionModel.user_id == user_id
            ).delete(synchronize_session=False)
            logger.debug(f"Deleted {session_count} chat sessions")
            
            # Get user's projects to delete their tasks
            projects = db.query(ProjectModel).filter(
                ProjectModel.id.in_(
                    db.query(ProjectModel.id).filter(
                        ProjectModel.meta_data['created_by'].astext == user_id
                    )
                )
            ).all()
            
            project_ids = [p.id for p in projects]
            
            # Delete tasks for user's projects
            if project_ids:
                task_count = db.query(TaskModel).filter(
                    TaskModel.project_id.in_(project_ids)
                ).delete(synchronize_session=False)
                logger.debug(f"Deleted {task_count} tasks")
                
                # Delete projects
                project_count = db.query(ProjectModel).filter(
                    ProjectModel.id.in_(project_ids)
                ).delete(synchronize_session=False)
                logger.debug(f"Deleted {project_count} projects")
            
            # Finally delete the user
            db.delete(user)
            db.commit()
            
            logger.info(f"User cascade delete completed: {user_id}")
            return True
            
        except Exception as e:
            db.rollback()
            logger.error(f"Error during cascade delete for user {user_id}: {e}", exc_info=True)
            raise

    @staticmethod
    def count(db: Session) -> int:
        """Count users"""
        return db.query(UserModel).count()
    
    @staticmethod
    def get_user_complete_data(db: Session, user_id: str) -> Optional[Dict[str, Any]]:
        """
        Get complete user data including all related data for export.
        Returns dict with user profile and all related data.
        """
        user = UserCRUD.get(db, user_id)
        if not user:
            return None
        
        # Get all chat sessions
        sessions = db.query(ChatSessionModel).filter(
            ChatSessionModel.user_id == user_id
        ).all()
        
        # Get all chat messages
        messages = db.query(ChatMessageModel).filter(
            ChatMessageModel.user_id == user_id
        ).all()
        
        # Get user's projects (stored in meta_data)
        projects = db.query(ProjectModel).filter(
            ProjectModel.meta_data['created_by'].astext == user_id
        ).all()
        
        # Get tasks for those projects
        project_ids = [p.id for p in projects]
        tasks = []
        if project_ids:
            tasks = db.query(TaskModel).filter(
                TaskModel.project_id.in_(project_ids)
            ).all()
        
        return {
            "user": user.to_dict(),
            "chat_sessions": [s.to_dict() for s in sessions],
            "chat_messages": [m.to_dict() for m in messages],
            "projects": [p.to_dict() for p in projects],
            "tasks": [t.to_dict() for t in tasks],
            "export_metadata": {
                "exported_at": datetime.utcnow().isoformat(),
                "user_id": user_id,
                "username": user.username
            }
        }


class SystemSettingsModel(Base):
    """Database model for system settings"""
    __tablename__ = "system_settings"
    
    id = Column(Integer, primary_key=True)  # Singleton, always ID=1
    registration_enabled = Column(Boolean, default=True)
    updated_at = Column(DateTime, default=datetime.utcnow, onupdate=datetime.utcnow)
    
    def to_dict(self) -> Dict[str, Any]:
        return {
            "registration_enabled": self.registration_enabled,
            "updated_at": self.updated_at.isoformat() if self.updated_at else None
        }


class SystemSettingsCRUD:
    """CRUD for system settings"""
    
    @staticmethod
    def get(db: Session) -> SystemSettingsModel:
        """Get settings (create defaults if missing)"""
        settings = db.query(SystemSettingsModel).first()
        if not settings:
            settings = SystemSettingsModel(id=1, registration_enabled=True)
            db.add(settings)
            db.commit()
            db.refresh(settings)
        return settings
    
    @staticmethod
    def update(db: Session, updates: Dict[str, Any]) -> SystemSettingsModel:
        """Update settings"""
        settings = SystemSettingsCRUD.get(db)
        
        for key, value in updates.items():
            if hasattr(settings, key):
                setattr(settings, key, value)
                
        settings.updated_at = datetime.utcnow()
        db.commit()
        db.refresh(settings)
        return settings
