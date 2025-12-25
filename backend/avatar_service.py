# START OF FILE backend/avatar_service.py
"""
Avatar Upload & Management Service for NoSlop.

Handles user avatar image uploads, processing, and storage.
"""

from fastapi import UploadFile, HTTPException
from typing import Optional
import logging
import os
from pathlib import Path
from PIL import Image
import io
import uuid

from config import settings

logger = logging.getLogger(__name__)


class AvatarService:
    """Handle user avatar uploads and management"""
    
    # Supported image formats
    SUPPORTED_FORMATS = {"image/jpeg", "image/jpg", "image/png", "image/webp"}
    
    # Max file size (5MB)
    MAX_FILE_SIZE = 5 * 1024 * 1024
    
    # Avatar dimensions
    AVATAR_SIZE = (256, 256)
    
    def __init__(self, storage_path: Optional[str] = None):
        """
        Initialize avatar service.
        
        Args:
            storage_path: Path to store avatar images (defaults to settings.media_storage_path)
        """
        self.storage_path = Path(storage_path or settings.media_storage_path) / "avatars"
        # Don't create directory here - defer to first use to avoid permission errors during module import
        logger.info(f"Avatar service initialized with storage path: {self.storage_path}")
    
    def _ensure_storage_exists(self):
        """Ensure storage directory exists. Creates it if needed."""
        try:
            self.storage_path.mkdir(parents=True, exist_ok=True)
        except PermissionError as e:
            logger.error(f"Permission denied creating avatar storage directory: {self.storage_path}")
            raise HTTPException(
                status_code=500,
                detail="Avatar storage not accessible. Please contact administrator."
            )
        except Exception as e:
            logger.error(f"Error creating avatar storage directory: {e}")
            raise HTTPException(
                status_code=500,
                detail=f"Error initializing avatar storage: {str(e)}"
            )
    
    async def upload_avatar(
        self,
        user_id: str,
        file: UploadFile
    ) -> str:
        """
        Upload and process avatar image.
        
        Process:
        1. Validate file type and size
        2. Resize to standard dimensions
        3. Save to storage
        4. Return URL/path
        
        Args:
            user_id: User ID who owns the avatar
            file: Uploaded file
            
        Returns:
            Path/URL to the saved avatar
            
        Raises:
            HTTPException: If validation fails
        """
        logger.info(f"Processing avatar upload for user: {user_id}")
        
        # Ensure storage directory exists
        self._ensure_storage_exists()
        
        # Validate content type
        if file.content_type not in self.SUPPORTED_FORMATS:
            raise HTTPException(
                status_code=400,
                detail=f"Unsupported file format. Supported formats: {', '.join(self.SUPPORTED_FORMATS)}"
            )
        
        # Read file content
        content = await file.read()
        
        # Validate file size
        if len(content) > self.MAX_FILE_SIZE:
            raise HTTPException(
                status_code=400,
                detail=f"File too large. Maximum size: {self.MAX_FILE_SIZE / 1024 / 1024}MB"
            )
        
        try:
            # Open image with PIL
            image = Image.open(io.BytesIO(content))
            
            # Convert RGBA to RGB if necessary
            if image.mode == 'RGBA':
                # Create white background
                background = Image.new('RGB', image.size, (255, 255, 255))
                background.paste(image, mask=image.split()[3])  # Use alpha channel as mask
                image = background
            elif image.mode != 'RGB':
                image = image.convert('RGB')
            
            # Resize to standard size (maintaining aspect ratio, then crop)
            image.thumbnail(self.AVATAR_SIZE, Image.Resampling.LANCZOS)
            
            # Create square image (center crop if needed)
            if image.size[0] != image.size[1]:
                # Calculate crop box for center
                width, height = image.size
                new_size = min(width, height)
                left = (width - new_size) / 2
                top = (height - new_size) / 2
                right = (width + new_size) / 2
                bottom = (height + new_size) / 2
                image = image.crop((left, top, right, bottom))
                image = image.resize(self.AVATAR_SIZE, Image.Resampling.LANCZOS)
            
            # Generate filename
            filename = f"{user_id}_{uuid.uuid4().hex[:8]}.jpg"
            file_path = self.storage_path / filename
            
            # Delete old avatar if exists
            self.delete_old_avatars(user_id)
            
            # Save optimized image
            image.save(file_path, "JPEG", quality=85, optimize=True)
            
            # Return relative path
            avatar_url = f"/media/avatars/{filename}"
            logger.info(f"Avatar uploaded successfully: {avatar_url}")
            
            return avatar_url
            
        except Exception as e:
            logger.error(f"Error processing avatar image: {e}", exc_info=True)
            raise HTTPException(
                status_code=500,
                detail=f"Error processing image: {str(e)}"
            )
    
    def delete_avatar(self, user_id: str, avatar_url: Optional[str] = None) -> bool:
        """
        Delete user's avatar file.
        
        Args:
            user_id: User ID
            avatar_url: Optional specific avatar URL to delete
            
        Returns:
            True if deleted, False if not found
        """
        try:
            if avatar_url:
                # Delete specific avatar
                # Extract filename from URL (format: /media/avatars/filename.jpg)
                filename = Path(avatar_url).name
                file_path = self.storage_path / filename
                
                if file_path.exists():
                    file_path.unlink()
                    logger.info(f"Deleted avatar: {avatar_url}")
                    return True
                else:
                    logger.warning(f"Avatar file not found: {file_path}")
                    return False
            else:
                # Delete all avatars for user
                deleted_count = self.delete_old_avatars(user_id)
                return deleted_count > 0
                
        except Exception as e:
            logger.error(f"Error deleting avatar: {e}", exc_info=True)
            return False
    
    def delete_old_avatars(self, user_id: str) -> int:
        """
        Delete old avatar files for a user.
        
        Args:
            user_id: User ID
            
        Returns:
            Number of files deleted
        """
        deleted_count = 0
        
        try:
            # Find all avatar files for this user
            pattern = f"{user_id}_*.jpg"
            for file_path in self.storage_path.glob(pattern):
                file_path.unlink()
                deleted_count += 1
                logger.debug(f"Deleted old avatar: {file_path.name}")
            
            if deleted_count > 0:
                logger.info(f"Deleted {deleted_count} old avatar(s) for user: {user_id}")
                
        except Exception as e:
            logger.error(f"Error deleting old avatars: {e}", exc_info=True)
        
        return deleted_count
    
    def get_avatar_path(self, avatar_url: str) -> Optional[Path]:
        """
        Get absolute file path from avatar URL.
        
        Args:
            avatar_url: Avatar URL (e.g., /media/avatars/user_id_hash.jpg)
            
        Returns:
            Absolute path to avatar file or None if not found
        """
        if not avatar_url:
            return None
        
        filename = Path(avatar_url).name
        file_path = self.storage_path / filename
        
        return file_path if file_path.exists() else None
