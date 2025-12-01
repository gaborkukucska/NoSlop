# START OF FILE backend/config.py
from pydantic_settings import BaseSettings
from typing import Optional
import os


class Settings(BaseSettings):
    """Application configuration settings"""
    
    # Application
    app_name: str = "NoSlop"
    app_version: str = "0.02"
    debug: bool = True
    
    # Server
    host: str = "0.0.0.0"
    port: int = 8000
    
    # Ollama
    ollama_host: str = "http://localhost:11434"
    ollama_default_model: str = "llama3.2"
    ollama_timeout: int = 120
    
    # Database
    database_url: str = "sqlite:///./noslop.db"
    
    # Storage
    media_storage_path: str = "./media"
    project_storage_path: str = "./projects"
    
    # ComfyUI
    comfyui_enabled: bool = False
    comfyui_host: str = "http://localhost:8188"
    comfyui_timeout: int = 300
    
    # FFmpeg
    ffmpeg_path: str = "ffmpeg"
    
    # Admin AI Personality Presets
    personality_presets: dict = {
        "creative": {
            "type": "creative",
            "creativity": 0.9,
            "formality": 0.3,
            "verbosity": 0.7,
            "enthusiasm": 0.9,
            "technical_depth": 0.4
        },
        "technical": {
            "type": "technical",
            "creativity": 0.4,
            "formality": 0.7,
            "verbosity": 0.6,
            "enthusiasm": 0.5,
            "technical_depth": 0.9
        },
        "balanced": {
            "type": "balanced",
            "creativity": 0.7,
            "formality": 0.5,
            "verbosity": 0.6,
            "enthusiasm": 0.7,
            "technical_depth": 0.6
        }
    }
    
    # Resource Limits
    max_concurrent_tasks: int = 5
    max_project_size_mb: int = 10240  # 10GB
    
    class Config:
        env_file = ".env"
        env_file_encoding = "utf-8"


# Global settings instance
settings = Settings()

# Ensure storage directories exist
os.makedirs(settings.media_storage_path, exist_ok=True)
os.makedirs(settings.project_storage_path, exist_ok=True)
