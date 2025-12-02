# START OF FILE backend/models.py
from pydantic import BaseModel, Field
from typing import Optional, List, Dict, Any
from datetime import datetime
from enum import Enum


class PersonalityType(str, Enum):
    """Available Admin AI personality types"""
    CREATIVE = "creative"
    TECHNICAL = "technical"
    BALANCED = "balanced"
    CUSTOM = "custom"


class PersonalityProfile(BaseModel):
    """Admin AI personality configuration"""
    type: PersonalityType = PersonalityType.BALANCED
    creativity: float = Field(default=0.7, ge=0.0, le=1.0, description="Creativity level (0-1)")
    formality: float = Field(default=0.5, ge=0.0, le=1.0, description="Formality level (0-1)")
    verbosity: float = Field(default=0.6, ge=0.0, le=1.0, description="Response length preference (0-1)")
    enthusiasm: float = Field(default=0.7, ge=0.0, le=1.0, description="Enthusiasm level (0-1)")
    technical_depth: float = Field(default=0.5, ge=0.0, le=1.0, description="Technical detail level (0-1)")
    
    class Config:
        json_schema_extra = {
            "example": {
                "type": "creative",
                "creativity": 0.9,
                "formality": 0.3,
                "verbosity": 0.7,
                "enthusiasm": 0.8,
                "technical_depth": 0.4
            }
        }


class ChatMessage(BaseModel):
    """Chat message structure"""
    role: str = Field(..., description="Message role: 'user' or 'assistant'")
    content: str = Field(..., description="Message content")
    timestamp: datetime = Field(default_factory=datetime.utcnow)
    
    class Config:
        json_schema_extra = {
            "example": {
                "role": "user",
                "content": "I want to create a cinematic short film",
                "timestamp": "2025-12-01T12:00:00Z"
            }
        }


class ChatRequest(BaseModel):
    """Request to chat with Admin AI"""
    message: str = Field(..., description="User message")
    context: Optional[Dict[str, Any]] = Field(default=None, description="Additional context")
    personality: Optional[PersonalityProfile] = Field(default=None, description="Personality override")
    user_id: Optional[str] = Field(default=None, description="User ID for personalization")


class ChatResponse(BaseModel):
    """Admin AI response"""
    message: str = Field(..., description="AI response message")
    suggestions: Optional[List[str]] = Field(default=None, description="Follow-up suggestions")
    action: Optional[str] = Field(default=None, description="Suggested action (e.g., 'create_project')")
    metadata: Optional[Dict[str, Any]] = Field(default=None, description="Additional metadata")


class ProjectType(str, Enum):
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


class ProjectStatus(str, Enum):
    """Project lifecycle status"""
    PLANNING = "planning"
    IN_PROGRESS = "in_progress"
    REVIEW = "review"
    COMPLETED = "completed"
    PAUSED = "paused"
    CANCELLED = "cancelled"


class ProjectRequest(BaseModel):
    """Request to create a new project"""
    title: str = Field(..., description="Project title")
    project_type: ProjectType = Field(..., description="Type of media project")
    description: str = Field(..., description="Project description")
    duration: Optional[int] = Field(default=None, description="Target duration in seconds")
    style: Optional[str] = Field(default=None, description="Visual/audio style preferences")
    reference_media: Optional[List[str]] = Field(default=None, description="Paths to reference media")
    
    class Config:
        json_schema_extra = {
            "example": {
                "title": "Product Launch Video",
                "project_type": "advertisement",
                "description": "30-second product showcase with cinematic feel",
                "duration": 30,
                "style": "modern, sleek, professional",
                "reference_media": []
            }
        }


class Project(BaseModel):
    """Project data model"""
    id: str = Field(..., description="Unique project ID")
    title: str
    project_type: ProjectType
    description: str
    status: ProjectStatus = ProjectStatus.PLANNING
    created_at: datetime = Field(default_factory=datetime.utcnow)
    updated_at: datetime = Field(default_factory=datetime.utcnow)
    duration: Optional[int] = None
    style: Optional[str] = None
    reference_media: List[str] = Field(default_factory=list)
    metadata: Dict[str, Any] = Field(default_factory=dict)


class TaskStatus(str, Enum):
    """Task execution status"""
    PENDING = "pending"
    ASSIGNED = "assigned"
    IN_PROGRESS = "in_progress"
    COMPLETED = "completed"
    FAILED = "failed"


class Task(BaseModel):
    """Task data model"""
    id: str = Field(..., description="Unique task ID")
    project_id: str = Field(..., description="Parent project ID")
    title: str
    description: str
    task_type: str = Field(..., description="Type of task (e.g., 'script_writing', 'image_generation')")
    status: TaskStatus = TaskStatus.PENDING
    assigned_to: Optional[str] = Field(default=None, description="Worker agent ID")
    dependencies: List[str] = Field(default_factory=list, description="Task IDs this task depends on")
    created_at: datetime = Field(default_factory=datetime.utcnow)
    updated_at: datetime = Field(default_factory=datetime.utcnow)
    result: Optional[Dict[str, Any]] = Field(default=None, description="Task execution result")


class HealthStatus(BaseModel):
    """System health status"""
    status: str = Field(..., description="Overall status: 'ok', 'degraded', 'error'")
    ollama: str = Field(..., description="Ollama connection status")
    model_count: int = Field(default=0, description="Number of available models")
    services: Dict[str, bool] = Field(default_factory=dict, description="Service availability")


class UserCreate(BaseModel):
    """Request to create a new user"""
    username: str = Field(..., description="Unique username")
    email: Optional[str] = Field(default=None, description="User email")
    personality: Optional[PersonalityProfile] = Field(default=None, description="Initial personality settings")
    preferences: Optional[Dict[str, Any]] = Field(default=None, description="User preferences")


class User(BaseModel):
    """User data model"""
    id: str = Field(..., description="Unique user ID")
    username: str
    email: Optional[str] = None
    personality: PersonalityProfile
    preferences: Dict[str, Any] = Field(default_factory=dict)
    created_at: datetime
    last_login: Optional[datetime] = None
