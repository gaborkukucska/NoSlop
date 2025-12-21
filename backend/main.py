from fastapi import FastAPI, HTTPException, Depends, WebSocket, WebSocketDisconnect, status, BackgroundTasks
from fastapi.middleware.cors import CORSMiddleware
import ollama
import uvicorn
from typing import List, Optional, Dict, Any
from datetime import datetime, timedelta
import time
import logging
from sqlalchemy.orm import Session

from models import (
    ChatRequest, 
    ChatResponse, 
    PersonalityProfile,
    HealthStatus,
    ProjectRequest,
    Project,
    User,
    UserCreate,
    UserUpdate
)
from collections import deque
import asyncio
from admin_ai import AdminAI
from config import settings
from logging_config import setup_logging
from database import init_db, get_db, SessionLocal, ProjectCRUD, TaskCRUD, UserCRUD, SystemSettingsCRUD
from project_manager import ProjectManager
from worker_registry import get_registry, initialize_workers
from task_executor import TaskExecutor
from fastapi.security import OAuth2PasswordRequestForm
from auth import (
    create_access_token, 
    get_current_user, 
    get_password_hash, 
    verify_password,
    ACCESS_TOKEN_EXPIRE_MINUTES
)
from models import Token, UserRole, SystemSettingsData, UserCreate

# Initialize logging with dated files
log_file = setup_logging(
    log_level=settings.log_level,
    log_dir=settings.log_dir,
    enable_console=settings.enable_console_log,
    enable_file=settings.enable_file_log,
    enable_json=settings.enable_json_log,
    use_dated_files=True,  # Use dated files for runtime services
    module_name="backend"
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
    
    # Initialize worker registry with database session
    logger.info("Initializing worker registry...")
    db = SessionLocal()
    try:
        from worker_registry import get_registry
        registry = get_registry(db)
        initialize_workers()
        logger.info("Worker registry initialized")
    finally:
        db.close()

# CORS middleware for frontend communication
# Allow all origins for development (frontend accessible from multiple IPs)
# In production, this should be restricted, but for a self-hosted local tool, functionality is prioritized.
app.add_middleware(
    CORSMiddleware,
    allow_origin_regex=".*",  # Allow all origins with credentials
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)
logger.info("CORS enabled for all origins (development mode)")

# Global Admin AI instance (in production, this would be per-user)
admin_ai_instance: Dict[str, AdminAI] = {}

def get_admin_ai(session_id: str = "default") -> AdminAI:
    """Get or create Admin AI instance for session"""
    if session_id not in admin_ai_instance:
        logger.info(f"Creating new Admin AI instance for session: {session_id}")
        # Inject the global connection manager
        admin_ai_instance[session_id] = AdminAI(connection_manager=manager)
    return admin_ai_instance[session_id]


class ConnectionManager:
    def __init__(self):
        self.active_connections: List[WebSocket] = []
        self.history = deque(maxlen=50)  # Keep last 50 logs

    async def connect(self, websocket: WebSocket):
        await websocket.accept()
        self.active_connections.append(websocket)

    def disconnect(self, websocket: WebSocket):
        if websocket in self.active_connections:
            self.active_connections.remove(websocket)

    async def broadcast(self, message: dict):
        self.history.append(message)
        for connection in self.active_connections:
            try:
                await connection.send_json(message)
            except Exception:
                # Handle potential disconnects gracefully
                pass

manager = ConnectionManager()


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


@app.websocket("/ws/activity")
async def websocket_endpoint(websocket: WebSocket):
    await manager.connect(websocket)
    try:
        while True:
            # Keep the connection open to receive messages if needed in the future
            await websocket.receive_text()
    except WebSocketDisconnect:
        manager.disconnect(websocket)


@app.get("/api/activity/history")
async def get_activity_history(current_user: User = Depends(get_current_user)):
    """Get recent agent activity logs."""
    return list(manager.history)


@app.post("/api/chat", response_model=ChatResponse)
async def chat_with_admin_ai(
    request: ChatRequest, 
    session_id: str = "default", 
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user)
):
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
        
        # Determine personality
        personality = request.personality
        
        # If no personality provided but user_id is present, try to load from user profile
        if not personality and request.user_id:
            user = UserCRUD.get(db, request.user_id)
            if user and user.personality_type:
                # Convert DB personality to PersonalityProfile
                # Note: This is a simplification. In a real app we'd map all fields.
                # For now we just use the type if it matches a preset, or default.
                if user.personality_type in settings.personality_presets:
                    personality = settings.personality_presets[user.personality_type]
                    logger.debug(f"Loaded personality '{user.personality_type}' for user {request.user_id}")
        
        # Update personality if provided or loaded
        if personality:
            admin_ai.personality = personality
            logger.debug(f"Personality updated for session {session_id}")
        
        # Ensure session exists
        from database import ChatSessionModel
        session_record = db.query(ChatSessionModel).filter(ChatSessionModel.id == session_id).first()
        
        if not session_record:
            # Auto-create session if it doesn't exist (e.g. from default)
            session_record = ChatSessionModel(
                id=session_id,
                user_id=current_user.id if current_user else None,
                title="New Chat"
            )
            db.add(session_record)
            db.commit()
            
        # Update session timestamp
        session_record.updated_at = datetime.utcnow()
        
        # Auto-title if it's "New Chat" and we have a message
        if session_record.title == "New Chat" and len(request.message) > 0:
            # Simple title generation: first 30 chars
            new_title = request.message[:30] + "..." if len(request.message) > 30 else request.message
            session_record.title = new_title
            
        db.commit()
        
        # Process chat
        response = await admin_ai.chat(request.message, request.context, db=db, session_id=session_id)
        
        return response
        
    except Exception as e:
        logger.error(f"Chat endpoint error: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=f"Chat error: {str(e)}")


@app.get("/api/chat/history")
async def get_chat_history(
    session_id: str = "default",
    limit: int = 50,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user)
):
    """
    Get chat history for a session.
    
    Args:
        session_id: Session identifier
        limit: Max messages to return
        db: Database session
        
    Returns:
        List of chat messages
    """
    try:
        admin_ai = get_admin_ai(session_id)
        history = admin_ai.get_history(db, session_id, limit)
        return {"history": history}
    except Exception as e:
        logger.error(f"Error getting chat history: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=f"Error getting chat history: {str(e)}")


@app.get("/api/chat/sessions")
async def get_chat_sessions(
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user)
):
    """List all chat sessions for the current user."""
    from database import ChatSessionModel
    sessions = db.query(ChatSessionModel).order_by(ChatSessionModel.updated_at.desc()).all()
    return {"sessions": [s.to_dict() for s in sessions]}


@app.post("/api/chat/sessions")
async def create_chat_session(
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user)
):
    """Create a new chat session."""
    from database import ChatSessionModel
    import uuid
    
    session_id = str(uuid.uuid4())
    new_session = ChatSessionModel(
        id=session_id,
        user_id=current_user.id if current_user else None,
        title="New Chat"
    )
    db.add(new_session)
    db.commit()
    
    return new_session.to_dict()


@app.put("/api/chat/sessions/{session_id}")
async def update_chat_session(
    session_id: str,
    data: Dict[str, Any],
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user)
):
    """Update a chat session (e.g. title)."""
    from database import ChatSessionModel
    
    session = db.query(ChatSessionModel).filter(ChatSessionModel.id == session_id).first()
    if not session:
        raise HTTPException(status_code=404, detail="Session not found")
        
    if "title" in data:
        session.title = data["title"]
        
    session.updated_at = datetime.utcnow()
    db.commit()
    
    return session.to_dict()


@app.delete("/api/chat/sessions/{session_id}")
async def delete_chat_session(
    session_id: str,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user)
):
    """Delete a chat session."""
    from database import ChatSessionModel, ChatMessageModel
    
    session = db.query(ChatSessionModel).filter(ChatSessionModel.id == session_id).first()
    if not session:
        raise HTTPException(status_code=404, detail="Session not found")
        
    # Delete messages first
    db.query(ChatMessageModel).filter(ChatMessageModel.session_id == session_id).delete()
    db.delete(session)
    db.commit()
    
    return {"status": "success", "message": "Session deleted"}


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
async def set_personality(
    personality: PersonalityProfile, 
    session_id: str = "default",
    current_user: User = Depends(get_current_user)
):
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
async def clear_chat_history(
    session_id: str = "default",
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user)
):
    """
    Clear conversation history for a session.
    
    Args:
        session_id: Session identifier
        
    Returns:
        Success message
    """
    admin_ai = get_admin_ai(session_id)
    admin_ai.clear_history(db, session_id)
    
    return {
        "status": "success",
        "message": "Conversation history cleared"
    }


@app.get("/api/suggestions/{project_type}")
async def get_project_suggestions(
    project_type: str, 
    session_id: str = "default",
    current_user: User = Depends(get_current_user)
):
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
# Authentication Endpoints
# ============================================================================

def get_current_admin(current_user: User = Depends(get_current_user)) -> User:
    """Dependency to check for admin privileges"""
    if current_user.role != UserRole.ADMIN:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Admin privileges required"
        )
    return current_user


@app.post("/auth/register", response_model=User)
async def register(user_create: UserCreate, db: Session = Depends(get_db)):
    """Register a new user"""
    # Check if registration is enabled
    settings = SystemSettingsCRUD.get(db)
    if not settings.registration_enabled:
        # Check if any users exist. If 0, allow registration (first user setup)
        user_count = UserCRUD.count(db)
        if user_count > 0:
            raise HTTPException(status_code=403, detail="Registration is disabled")

    if UserCRUD.get_by_username(db, user_create.username):
        raise HTTPException(status_code=400, detail="Username already registered")
    
    try:
        # Determine role: First user is ADMIN
        user_count = UserCRUD.count(db)
        role = UserRole.ADMIN if user_count == 0 else UserRole.BASIC

        # Prepare user data
        user_data = {
            "id": f"user_{int(time.time())}_{user_create.username}", 
            "username": user_create.username,
            "email": user_create.email,
            "hashed_password": get_password_hash(user_create.password),
            "role": role.value,
            "bio": user_create.bio,
            "custom_data": user_create.custom_data or {},
            "preferences": user_create.preferences or {}
        }
        
        if user_create.personality:
            user_data["personality_type"] = user_create.personality.type.value
            user_data["personality_formality"] = user_create.personality.formality
            user_data["personality_enthusiasm"] = user_create.personality.enthusiasm
            user_data["personality_verbosity"] = user_create.personality.verbosity
            
        user = UserCRUD.create(db, user_data)
        return user.to_dict()
        
    except Exception as e:
        logger.error(f"Error creating user: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=f"Error creating user: {str(e)}")

@app.post("/auth/token", response_model=Token)
async def login_for_access_token(form_data: OAuth2PasswordRequestForm = Depends(), db: Session = Depends(get_db)):
    """Login to get access token"""
    user = UserCRUD.get_by_username(db, form_data.username)
    if not user or not verify_password(form_data.password, user.hashed_password):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Incorrect username or password",
            headers={"WWW-Authenticate": "Bearer"},
        )
    
    if not user.is_active:
        raise HTTPException(status_code=400, detail="User account is inactive")

    access_token_expires = timedelta(minutes=ACCESS_TOKEN_EXPIRE_MINUTES)
    access_token = create_access_token(
        data={"sub": user.username, "role": user.role.value if user.role else "basic"}, 
        expires_delta=access_token_expires
    )
    
    return {
        "access_token": access_token, 
        "token_type": "bearer",
        "user": user.to_dict()
    }


# ============================================================================
# User Management Endpoints
# ============================================================================

@app.post("/api/users", response_model=User)
async def create_user(user_create: UserCreate, db: Session = Depends(get_db)):
    """Create a new user"""
    try:
        # Check if username exists
        if UserCRUD.get_by_username(db, user_create.username):
            raise HTTPException(status_code=400, detail="Username already exists")
        
        # Prepare user data
        user_data = {
            "id": f"user_{int(time.time())}_{user_create.username}",  # Simple ID generation
            "username": user_create.username,
            "email": user_create.email,
            "preferences": user_create.preferences or {}
        }
        
        if user_create.personality:
            user_data["personality_type"] = user_create.personality.type.value
            user_data["personality_formality"] = user_create.personality.formality
            user_data["personality_enthusiasm"] = user_create.personality.enthusiasm
            user_data["personality_verbosity"] = user_create.personality.verbosity
            
        user = UserCRUD.create(db, user_data)
        return user.to_dict()
        
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Error creating user: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=f"Error creating user: {str(e)}")


@app.put("/api/users/me", response_model=User)
async def update_user_me(
    user_update: UserUpdate,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user)
):
    """Update current user profile"""
    try:
        user = UserCRUD.get(db, current_user.id)
        if not user:
            raise HTTPException(status_code=404, detail="User not found")
            
        update_data = user_update.dict(exclude_unset=True)
        
        # Handle nested personality update
        if "personality" in update_data and update_data["personality"]:
            p_data = update_data.pop("personality")
            update_data["personality_type"] = p_data["type"].value
            update_data["personality_formality"] = p_data["formality"]
            update_data["personality_enthusiasm"] = p_data["enthusiasm"]
            update_data["personality_verbosity"] = p_data["verbosity"]
            
        updated_user = UserCRUD.update(db, user.id, update_data)
        return updated_user.to_dict()
        
    except Exception as e:
        logger.error(f"Error updating user: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=f"Error updating user: {str(e)}")


@app.get("/api/users/{user_id}", response_model=User)
async def get_user(user_id: str, db: Session = Depends(get_db)):
    """Get user profile"""
    try:
        user = UserCRUD.get(db, user_id)
        if not user:
            raise HTTPException(status_code=404, detail="User not found")
        return user.to_dict()
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Error getting user: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=f"Error getting user: {str(e)}")


@app.put("/api/users/{user_id}/personality")
async def update_user_personality(
    user_id: str, 
    personality: PersonalityProfile, 
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user)
):
    """Update user's Admin AI personality settings"""
    try:
        updates = {
            "personality_type": personality.type.value,
            "personality_formality": personality.formality,
            "personality_enthusiasm": personality.enthusiasm,
            "personality_verbosity": personality.verbosity
        }
        
        user = UserCRUD.update(db, user_id, updates)
        if not user:
            raise HTTPException(status_code=404, detail="User not found")
            
        return {"status": "success", "message": "Personality updated", "user": user.to_dict()}
        
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Error updating personality: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=f"Error updating personality: {str(e)}")


# ============================================================================
# Project Manager Endpoints
# ============================================================================

@app.post("/api/projects", response_model=Project)
async def create_project(
    project_request: ProjectRequest, 
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user)
):
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
        pm = ProjectManager(db, manager)
        project = await pm.create_project(project_request)
        
        return project
        
    except Exception as e:
        logger.error(f"Error creating project: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=f"Error creating project: {str(e)}")


@app.get("/api/projects")
async def list_projects(
    skip: int = 0, 
    limit: int = 100, 
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user)
):
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
async def get_project(
    project_id: str, 
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user)
):
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
        pm = ProjectManager(db, manager)
        status = pm.get_project_status(project_id)
        
        if not status:
            raise HTTPException(status_code=404, detail=f"Project not found: {project_id}")
        
        return status
        
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Error getting project: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=f"Error getting project: {str(e)}")


# ============================================================================
# Setup & Admin Endpoints
# ============================================================================

@app.get("/api/setup/status")
async def get_setup_status(
    current_user: User = Depends(get_current_user),
    db: Session = Depends(get_db)
):
    """
    Check if user needs to run setup wizard.
    """
    # Check if user has specific setup flag in preferences
    is_setup = current_user.preferences.get("is_setup_complete", False)
    
    # Also check if they have a non-default personality set (as a heuristic if flag missing)
    # Default is typically "balanced" or None depending on registration
    # But explicit flag is better.
    
    return {
        "setup_required": not is_setup,
        "username": current_user.username
    }

@app.post("/api/setup/complete")
async def complete_setup(
    data: Dict[str, Any],
    current_user: User = Depends(get_current_user),
    db: Session = Depends(get_db)
):
    """
    Mark setup as complete and update profile.
    """
    try:
        # Update personality if provided
        if "personality" in data:
            personality_data = data["personality"] # Expects dict
            # We need to explicitly update columns or the JSON field depending on User model
            # UserCRUD.update handles dictionary mapping to columns
            
            updates = {
                "personality_type": personality_data.get("type", "balanced"),
                "personality_formality": personality_data.get("formality", 0.5),
                "personality_enthusiasm": personality_data.get("enthusiasm", 0.5),
                "personality_verbosity": personality_data.get("verbosity", 0.6)
            }
            UserCRUD.update(db, current_user.id, updates)
            
        # Update extra profile fields if provided
        profile_updates = {}
        if "bio" in data:
            profile_updates["bio"] = data["bio"]
        
        if "custom_data" in data:
            # Merge with existing custom_data if present
            current_custom = current_user.custom_data or {}
            merged_custom = {**current_custom, **data["custom_data"]}
            profile_updates["custom_data"] = merged_custom
            
        if profile_updates:
            UserCRUD.update(db, current_user.id, profile_updates)
            
        # Update preferences to mark setup complete
        new_preferences = dict(current_user.preferences) if current_user.preferences else {}
        new_preferences["is_setup_complete"] = True
        UserCRUD.update(db, current_user.id, {"preferences": new_preferences})
        
        return {"status": "success", "message": "Setup completed"}
        
    except Exception as e:
        logger.error(f"Setup completion failed: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))

@app.post("/api/admin/prime")
async def prime_admin_ai(
    session_id: str = "default",
    current_user: User = Depends(get_current_user),
    db: Session = Depends(get_db)
):
    """
    Trigger proactive priming of Admin AI.
    """
    try:
        admin_ai = get_admin_ai(session_id)
        
        # Get active projects for context
        projects = []
        if settings.enable_project_manager:
            all_projects = ProjectCRUD.get_all(db, limit=5)
            # Filter for active ones (simplified)
            projects = [p.to_dict() for p in all_projects if p.status != "completed"]
            
        response = await admin_ai.prime_session(
            user=current_user,
            active_projects=projects,
            db=db,
            session_id=session_id
        )
        
        return response
        
    except Exception as e:
        logger.error(f"Priming failed: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/api/projects/{project_id}/tasks")
async def get_project_tasks(
    project_id: str, 
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user)
):
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
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user)
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
        pm = ProjectManager(db, manager)
        task = await pm.update_task_status(task_id, status, result)
        
        if not task:
            raise HTTPException(status_code=404, detail=f"Task not found: {task_id}")
        
        return task.to_dict()
        
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Error updating task status: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=f"Error updating task status: {str(e)}")


@app.post("/api/tasks/{task_id}/execute")
async def execute_task(
    task_id: str, 
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user)
):
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
        pm = ProjectManager(db, manager)
        result = await pm.dispatch_task(task_id)
        
        if not result:
            # Check if task exists but no worker found or other issue
            task = TaskCRUD.get(db, task_id)
            if not task:
                raise HTTPException(status_code=404, detail=f"Task not found: {task_id}")
            
            return {"status": "no_action_taken", "message": "No suitable worker found or task execution failed"}
            
        return result
        
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Error executing task: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=f"Error executing task: {str(e)}")


# ============================================================================
# Voice Services (STT & TTS)
# ============================================================================

from fastapi import UploadFile, File
from fastapi.responses import StreamingResponse
from voice_service import VoiceService
# Global variable for Voice Service
voice_service = None

def initialize_voice_service_background():
    """Helper function to load models in background thread"""
    global voice_service
    try:
        logger.info("Starting background initialization of Voice Service (downloading models)...")
        # Initialize Voice Service
        # We use strict=False or similar if needed, but here we just catch exceptions
        service = VoiceService(model_size="tiny", device="cpu")
        voice_service = service
        logger.info("Voice Service initialized successfully and ready to use.")
    except Exception as e:
        logger.error(f"Failed to initialize Voice Service in background: {e}")

@app.on_event("startup")
async def startup_event():
    """Event handler for application startup."""
    import threading
    
    # Start Voice Service initialization in a separate thread to not block API startup
    # This prevents systemd from killing the service due to timeout during model downloads
    init_thread = threading.Thread(target=initialize_voice_service_background, daemon=True)
    init_thread.start()
    logger.info("Scheduled Voice Service initialization in background")


@app.post("/api/audio/transcribe")
async def transcribe_audio(file: UploadFile = File(...), current_user: User = Depends(get_current_user)):
    """
    Transcribe uploaded audio file to text.
    """
    if not voice_service:
        raise HTTPException(status_code=503, detail="Voice service not available")
    
    try:
        # Save temp file
        import tempfile
        import os
        import shutil
        
        with tempfile.NamedTemporaryFile(delete=False, suffix=".wav") as tmp:
            shutil.copyfileobj(file.file, tmp)
            tmp_path = tmp.name
            
        try:
            text = voice_service.transcribe(tmp_path)
            return {"text": text}
        finally:
            os.unlink(tmp_path)
            
    except Exception as e:
        logger.error(f"Transcription error: {e}")
        raise HTTPException(status_code=500, detail=f"Transcription failed: {str(e)}")

@app.post("/api/audio/speak")
async def generate_speech(request: dict, current_user: User = Depends(get_current_user)):
    """
    Generate speech from text.
    """
    if not voice_service:
        raise HTTPException(status_code=503, detail="Voice service not available")
    
    text = request.get("text")
    if not text:
        raise HTTPException(status_code=400, detail="Text required")
        
    try:
        audio_buffer = voice_service.generate_speech(text)
        return StreamingResponse(audio_buffer, media_type="audio/wav")
    except Exception as e:
        logger.error(f"TTS error: {e}")
        raise HTTPException(status_code=500, detail=f"TTS failed: {str(e)}")
            



# ============================================================================
# Worker Registry Endpoints
# ============================================================================

@app.get("/api/workers")
async def list_workers(current_user: User = Depends(get_current_user)):
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
async def get_worker_capabilities(
    worker_type: str,
    current_user: User = Depends(get_current_user)
):
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
async def get_task_type_mapping(current_user: User = Depends(get_current_user)):
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
async def execute_project(
    project_id: str, 
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user)
):
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
        executor = TaskExecutor(db, manager)
        result = await executor.execute_project(project_id)

        return result

    except Exception as e:
        logger.error(f"Error executing project: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=f"Error executing project: {str(e)}")




async def run_background_project_execution(project_id: str):
    """
    Run project execution in background with its own database session.
    Using a dedicated session prevents 'PendingRollbackError' when the main request session
    is closed or reused while the background task is still running.
    """
    logger.info(f"Starting background execution for project {project_id}")
    db = SessionLocal()
    try:
        executor = TaskExecutor(db, manager)
        result = await executor.execute_project(project_id)
        logger.info(f"Background execution finished for project {project_id}: {result}")
    except Exception as e:
        logger.error(f"Background project execution failed: {e}", exc_info=True)
    finally:
        db.close()


@app.post("/api/projects/{project_id}/start", response_model=Project)
async def start_project(
    project_id: str,
    background_tasks: BackgroundTasks,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user)
):
    if not settings.enable_project_manager:
        raise HTTPException(status_code=503, detail="Project Manager is not enabled")
    logger.info(f"Project start requested: {project_id}")
    pm = ProjectManager(db, manager)
    project = await pm.start_project(project_id)
    if not project:
        raise HTTPException(status_code=404, detail="Project not found")
    
    # Trigger execution in background on the main loop with isolated session
    asyncio.create_task(run_background_project_execution(project_id))
    logger.info(f"Project execution scheduled in background: {project_id}")
    
    return project


@app.post("/api/projects/{project_id}/pause", response_model=Project)
async def pause_project(
    project_id: str,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user)
):
    if not settings.enable_project_manager:
        raise HTTPException(status_code=503, detail="Project Manager is not enabled")
    logger.info(f"Project pause requested: {project_id}")
    pm = ProjectManager(db, manager)
    project = await pm.pause_project(project_id)
    if not project:
        raise HTTPException(status_code=404, detail="Project not found")
    return project


@app.post("/api/projects/{project_id}/stop", response_model=Project)
async def stop_project(
    project_id: str,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user)
):
    if not settings.enable_project_manager:
        raise HTTPException(status_code=503, detail="Project Manager is not enabled")
    logger.info(f"Project stop requested: {project_id}")
    pm = ProjectManager(db, manager)
    project = await pm.stop_project(project_id)
    if not project:
        raise HTTPException(status_code=404, detail="Project not found")
    return project


@app.put("/api/projects/{project_id}", response_model=Project)
async def update_project(
    project_id: str,
    project_update: ProjectRequest,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user)
):
    if not settings.enable_project_manager:
        raise HTTPException(status_code=503, detail="Project Manager is not enabled")
    logger.info(f"Project update requested: {project_id}")
    pm = ProjectManager(db, manager)
    project = await pm.update_project(project_id, project_update.dict())
    if not project:
        raise HTTPException(status_code=404, detail="Project not found")
    return project


@app.delete("/api/projects/{project_id}")
async def delete_project(
    project_id: str,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user)
):
    if not settings.enable_project_manager:
        raise HTTPException(status_code=503, detail="Project Manager is not enabled")
    logger.info(f"Project delete requested: {project_id}")
    pm = ProjectManager(db, manager)
    await pm.delete_project(project_id)
    return {"status": "deleted", "project_id": project_id}


@app.post("/api/tasks/{task_id}/execute-with-dependencies")
async def execute_task_with_dependencies(
    task_id: str, 
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user)
):
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
        executor = TaskExecutor(db, manager)
        result = await executor.execute_task_with_dependencies(task_id)

        return result

    except Exception as e:
        logger.error(f"Error executing task with dependencies: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=f"Error executing task with dependencies: {str(e)}")


@app.get("/api/tasks/{task_id}/progress")
async def get_task_progress(
    task_id: str, 
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user)
):
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
        executor = TaskExecutor(db, manager)
        progress = executor.monitor_task_progress(task_id)

        if not progress:
            raise HTTPException(status_code=404, detail=f"Task not found: {task_id}")
        
        return progress
        
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Error getting task progress: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=f"Error getting task progress: {str(e)}")


# ============================================================================
# Admin API Endpoints
# ============================================================================

@app.get("/api/admin/users", response_model=Dict[str, Any])
async def list_users(
    skip: int = 0,
    limit: int = 100,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_admin)
):
    """List all users (Admin only)"""
    users = UserCRUD.get_all(db, skip=skip, limit=limit)
    return {"users": [u.to_dict() for u in users]}


@app.put("/api/admin/users/{user_id}")
async def update_user_admin(
    user_id: str,
    updates: Dict[str, Any],
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_admin)
):
    """Update user details including role/status (Admin only)"""
    # Prevent admin from deactivating themselves? Maybe.
    if user_id == current_user.id:
        if "is_active" in updates and not updates["is_active"]:
            raise HTTPException(status_code=400, detail="Cannot deactivate your own account")
        if "role" in updates and updates["role"] != UserRole.ADMIN:
            raise HTTPException(status_code=400, detail="Cannot demote your own account")

    user = UserCRUD.update(db, user_id, updates)
    if not user:
        raise HTTPException(status_code=404, detail="User not found")
    return user.to_dict()


@app.delete("/api/admin/users/{user_id}")
async def delete_user_admin(
    user_id: str,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_admin)
):
    """Delete a user (Admin only)"""
    if user_id == current_user.id:
         raise HTTPException(status_code=400, detail="Cannot delete your own account")
         
    success = UserCRUD.delete(db, user_id)
    if not success:
        raise HTTPException(status_code=404, detail="User not found")
    return {"status": "success", "message": "User deleted"}


@app.get("/api/admin/settings")
async def get_system_settings(
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_admin)
):
    """Get system settings"""
    settings = SystemSettingsCRUD.get(db)
    return settings.to_dict()


@app.put("/api/admin/settings")
async def update_system_settings(
    settings_data: SystemSettingsData,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_admin)
):
    """Update system settings"""
    updates = settings_data.dict()
    settings = SystemSettingsCRUD.update(db, updates)
    return settings.to_dict()


@app.get("/api/admin/export")
async def export_data(
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_admin)
):
    """Export all system data (Admin only)"""
    from database import ProjectModel, TaskModel, ChatSessionModel, ChatMessageModel, UserModel, SystemSettingsModel
    
    export = {
        "timestamp": datetime.utcnow().isoformat(),
        "exported_by": current_user.username,
        "users": [],
        "projects": [],
        "tasks": [],
        "chat_sessions": [],
        "chat_messages": [],
        "system_settings": SystemSettingsCRUD.get(db).to_dict()
    }

    # Helper to serialize model
    def model_to_dict(obj):
        d = {}
        for column in obj.__table__.columns:
            val = getattr(obj, column.name)
            if isinstance(val, datetime):
                val = val.isoformat()
            if isinstance(val, UserRole):
                val = val.value
            if hasattr(val, "value"): # Handle other enums
                val = val.value
            d[column.name] = val
        return d

    # Users
    users = db.query(UserModel).all()
    export["users"] = [model_to_dict(u) for u in users]
    
    # Projects
    projects = db.query(ProjectModel).all()
    export["projects"] = [model_to_dict(p) for p in projects]
    
    # Tasks
    tasks = db.query(TaskModel).all()
    export["tasks"] = [model_to_dict(t) for t in tasks]
    
    # Sessions
    sessions = db.query(ChatSessionModel).all()
    export["chat_sessions"] = [model_to_dict(s) for s in sessions]
    
    # Messages
    messages = db.query(ChatMessageModel).all()
    export["chat_messages"] = [model_to_dict(m) for m in messages]

    return export


@app.post("/api/admin/import")
async def import_data(
    data: Dict[str, Any],
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_admin)
):
    """Import system data (Wipes existing data) (Admin only)"""
    from database import ProjectModel, TaskModel, ChatSessionModel, ChatMessageModel, UserModel, SystemSettingsModel
    
    try:
        # Clear existing data - Order matters for Foreign Keys
        db.query(ChatMessageModel).delete()
        db.query(ChatSessionModel).delete()
        db.query(TaskModel).delete()
        db.query(ProjectModel).delete()
        db.query(UserModel).delete()
        
        # Import Users
        for u_data in data.get("users", []):
            # Parse datetimes
            if u_data.get("created_at"): u_data["created_at"] = datetime.fromisoformat(u_data["created_at"])
            if u_data.get("updated_at"): u_data["updated_at"] = datetime.fromisoformat(u_data["updated_at"])
            if u_data.get("last_login"): u_data["last_login"] = datetime.fromisoformat(u_data["last_login"])
            
            user = UserModel(**u_data)
            db.add(user)
            
        # Import Projects
        for p_data in data.get("projects", []):
            if p_data.get("created_at"): p_data["created_at"] = datetime.fromisoformat(p_data["created_at"])
            if p_data.get("updated_at"): p_data["updated_at"] = datetime.fromisoformat(p_data["updated_at"])
            
            project = ProjectModel(**p_data)
            db.add(project)
            
        # Import Tasks
        for t_data in data.get("tasks", []):
            if t_data.get("created_at"): t_data["created_at"] = datetime.fromisoformat(t_data["created_at"])
            if t_data.get("updated_at"): t_data["updated_at"] = datetime.fromisoformat(t_data["updated_at"])
            if t_data.get("started_at"): t_data["started_at"] = datetime.fromisoformat(t_data["started_at"])
            if t_data.get("completed_at"): t_data["completed_at"] = datetime.fromisoformat(t_data["completed_at"])
            
            task = TaskModel(**t_data)
            db.add(task)
            
        # Import Chat Sessions
        for s_data in data.get("chat_sessions", []):
            if s_data.get("created_at"): s_data["created_at"] = datetime.fromisoformat(s_data["created_at"])
            if s_data.get("updated_at"): s_data["updated_at"] = datetime.fromisoformat(s_data["updated_at"])
            
            session = ChatSessionModel(**s_data)
            db.add(session)
            
        # Import Chat Messages
        for m_data in data.get("chat_messages", []):
            if m_data.get("timestamp"): m_data["timestamp"] = datetime.fromisoformat(m_data["timestamp"])
            
            message = ChatMessageModel(**m_data)
            db.add(message)
            
        # Settings
        if "system_settings" in data:
            settings_data = data["system_settings"]
            settings = SystemSettingsCRUD.update(db, settings_data)
            
        db.commit()
        return {"status": "success", "message": "Data imported successfully"}
        
    except Exception as e:
        db.rollback()
        logger.error(f"Import failed: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=f"Import failed: {str(e)}")

@app.put("/api/users/{user_id}")
async def update_user_profile(
    user_id: str,
    updates: Dict[str, Any],
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user)
):
    """Update user profile (Self or Admin)"""
    if user_id != current_user.id and current_user.role != UserRole.ADMIN:
        raise HTTPException(status_code=403, detail="Not authorized to update this profile")
        
    # Whitelist allowed fields for non-admins
    allowed_fields = ["bio", "custom_data", "preferences", "email"]
    if current_user.role == UserRole.ADMIN:
        allowed_fields.extend(["role", "is_active"])
        
    filtered_updates = {k: v for k, v in updates.items() if k in allowed_fields}
    
    if not filtered_updates and not current_user.role == UserRole.ADMIN: # Admins might update other things
         return current_user # No op
         
    user = UserCRUD.update(db, user_id, filtered_updates)
    if not user:
        raise HTTPException(status_code=404, detail="User not found")
        
    return user.to_dict()


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
