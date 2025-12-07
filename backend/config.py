# START OF FILE backend/config.py
"""
Centralized configuration management for NoSlop.
All settings are loaded from environment variables with sensible defaults.
"""

from pydantic_settings import BaseSettings
from typing import Optional, Dict
import os


class Settings(BaseSettings):
    """Application configuration settings loaded from environment variables."""
    
    # ========================================================================
    # Application Settings
    # ========================================================================
    app_name: str = "NoSlop"
    app_version: str = "0.02"
    debug: bool = True
    
    # ========================================================================
    # Server Configuration
    # ========================================================================
    host: str = "0.0.0.0"
    port: int = 8000
    
    # ========================================================================
    # Logging Configuration
    # ========================================================================
    log_level: str = "INFO"
    log_dir: str = "logs"
    enable_console_log: bool = True
    enable_file_log: bool = True
    enable_json_log: bool = False
    
    # ========================================================================
    # Ollama Configuration
    # ========================================================================
    ollama_host: str = "http://localhost:11434"
    ollama_timeout: int = 120
    
    # Model preferences by task type
    model_logic: str = "llama3.2"
    model_video: str = "llama3.2"
    model_image: str = "llama3.2"    # Ollama Settings
    OLLAMA_HOST: str = "127.0.0.1"
    OLLAMA_PORT: int = 11434
    
    # ComfyUI Settings
    COMFYUI_HOST: str = "127.0.0.1"
    COMFYUI_PORT: int = 8188
    COMFYUI_OUTPUT_DIR: str = "media/generated"
    
    # FFmpeg Settings
    FFMPEG_PATH: str = "ffmpeg"
    FFPROBE_PATH: str = "ffprobe"
    MEDIA_OUTPUT_DIR: str = "media/output"
    
    # Logging
    LOG_LEVEL: str = "INFO"
    model_math: str = "llama3.2"
    model_tts: str = "llama3.2"
    model_ttv: str = "llama3.2"
    ollama_default_model: str = "llama3.2"
    
    # ========================================================================
    # ComfyUI Configuration
    # ========================================================================
    comfyui_enabled: bool = False
    comfyui_host: str = "http://localhost:8188"
    comfyui_timeout: int = 300
    
    # ========================================================================
    # Database Configuration
    # ========================================================================
    database_url: str = "sqlite:///./noslop.db"
    
    # ========================================================================
    # Storage Configuration
    # ========================================================================
    media_storage_path: str = "./media"
    project_storage_path: str = "./projects"
    log_storage_path: str = "./logs"
    
    # ========================================================================
    # Shared Storage Configuration
    # ========================================================================
    shared_storage_enabled: bool = False
    ollama_models_dir: str = "/mnt/noslop/ollama/models"
    comfyui_models_dir: str = "/mnt/noslop/comfyui/models"
    comfyui_custom_nodes_dir: str = "/mnt/noslop/comfyui/custom_nodes"
    project_storage_dir: str = "/mnt/noslop/projects"
    media_cache_dir: str = "/mnt/noslop/media_cache"
    
    # ========================================================================
    # Resource Limits
    # ========================================================================
    max_concurrent_tasks: int = 5
    max_project_size_mb: int = 10240  # 10GB
    max_workers_per_project: int = 10
    
    # ========================================================================
    # FFmpeg Configuration
    # ========================================================================
    ffmpeg_path: str = "ffmpeg"
    ffprobe_path: str = "ffprobe"
    
    # ========================================================================
    # Admin AI Personality Defaults
    # ========================================================================
    default_personality: str = "balanced"
    default_creativity: float = 0.7
    default_formality: float = 0.5
    default_verbosity: float = 0.6
    default_enthusiasm: float = 0.7
    default_technical_depth: float = 0.6
    
    # ========================================================================
    # Security Settings
    # ========================================================================
    secret_key: Optional[str] = None
    # Allow all origins for development (frontend can be accessed from multiple IPs)
    # In production, this should be restricted to specific domains
    cors_origins: str = "*"
    
    # ========================================================================
    # Feature Flags
    # ========================================================================
    enable_project_manager: bool = True
    enable_worker_agents: bool = False
    enable_blockchain: bool = False
    enable_mesh_network: bool = False
    
    # ========================================================================
    # Admin AI Personality Presets
    # ========================================================================
    @property
    def personality_presets(self) -> Dict:
        """Get personality presets based on configuration."""
        return {
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
                "creativity": self.default_creativity,
                "formality": self.default_formality,
                "verbosity": self.default_verbosity,
                "enthusiasm": self.default_enthusiasm,
                "technical_depth": self.default_technical_depth
            }
        }
    
    @property
    def cors_origins_list(self) -> list:
        """Get CORS origins as a list."""
        return [origin.strip() for origin in self.cors_origins.split(",")]
    
    class Config:
        env_file = ".env"
        env_file_encoding = "utf-8"
        case_sensitive = False
        extra = "ignore"


# Global settings instance
settings = Settings()

# Ensure storage directories exist
os.makedirs(settings.media_storage_path, exist_ok=True)
os.makedirs(settings.project_storage_path, exist_ok=True)
os.makedirs(settings.log_storage_path, exist_ok=True)
