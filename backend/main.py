from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
import ollama
import uvicorn
from typing import Dict

from models import (
    ChatRequest, 
    ChatResponse, 
    PersonalityProfile,
    HealthStatus
)
from admin_ai import AdminAI
from config import settings

app = FastAPI(
    title=settings.app_name,
    version=settings.app_version,
    description="NoSlop Backend - Self-hosted AI-driven media creation platform"
)

# CORS middleware for frontend communication
app.add_middleware(
    CORSMiddleware,
    allow_origins=["http://localhost:3000", "http://127.0.0.1:3000"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Global Admin AI instance (in production, this would be per-user)
admin_ai_instance: Dict[str, AdminAI] = {}

def get_admin_ai(session_id: str = "default") -> AdminAI:
    """Get or create Admin AI instance for session"""
    if session_id not in admin_ai_instance:
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
    try:
        # Attempt to list models to verify connection
        models = ollama.list()
        
        # Check optional services
        services = {
            "ollama": True,
            "comfyui": False,  # TODO: Implement ComfyUI health check
            "database": True   # TODO: Implement database health check
        }
        
        return HealthStatus(
            status="ok", 
            ollama="connected", 
            model_count=len(models.get('models', [])),
            services=services
        )
    except Exception as e:
        return HealthStatus(
            status="degraded", 
            ollama="disconnected", 
            model_count=0,
            services={"ollama": False}
        )


@app.post("/api/chat", response_model=ChatResponse)
async def chat_with_admin_ai(request: ChatRequest, session_id: str = "default"):
    """
    Chat with the Admin AI.
    
    Args:
        request: Chat request with message and optional context
        session_id: Session identifier for conversation continuity
        
    Returns:
        ChatResponse with AI message and suggestions
    """
    try:
        admin_ai = get_admin_ai(session_id)
        
        # Update personality if provided
        if request.personality:
            admin_ai.personality = request.personality
        
        # Process chat
        response = admin_ai.chat(request.message, request.context)
        
        return response
        
    except Exception as e:
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


if __name__ == "__main__":
    print(f"🚀 Starting {settings.app_name} v{settings.app_version}")
    print(f"📡 Ollama endpoint: {settings.ollama_host}")
    print(f"💾 Database: {settings.database_url}")
    print(f"📁 Media storage: {settings.media_storage_path}")
    
    uvicorn.run(app, host=settings.host, port=settings.port)

