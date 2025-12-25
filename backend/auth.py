# START OF FILE backend/auth.py
from datetime import datetime, timedelta
from typing import Optional
from jose import JWTError, jwt
from passlib.context import CryptContext
from fastapi import Depends, HTTPException, status
from fastapi.security import OAuth2PasswordBearer
from sqlalchemy.orm import Session

from config import settings
from database import get_db, UserCRUD
from models import TokenData, User

# Password hashing context
pwd_context = CryptContext(schemes=["argon2"], deprecated="auto")

# OAuth2 scheme
oauth2_scheme = OAuth2PasswordBearer(tokenUrl="auth/token")

# Secret key and algorithm for JWT
SECRET_KEY = settings.secret_key or "noslop_secret_key_change_me_in_production"
ALGORITHM = "HS256"
ACCESS_TOKEN_EXPIRE_MINUTES = 30


def verify_password(plain_password: str, hashed_password: str) -> bool:
    """Verify a password against its hash"""
    return pwd_context.verify(plain_password, hashed_password)


def get_password_hash(password: str) -> str:
    """Generate password hash"""
    return pwd_context.hash(password)


def create_access_token(data: dict, expires_delta: Optional[timedelta] = None) -> str:
    """Create a JWT access token"""
    to_encode = data.copy()
    if expires_delta:
        expire = datetime.utcnow() + expires_delta
    else:
        expire = datetime.utcnow() + timedelta(minutes=15)
    
    to_encode.update({"exp": expire})
    encoded_jwt = jwt.encode(to_encode, SECRET_KEY, algorithm=ALGORITHM)
    return encoded_jwt


def verify_token(token: str) -> dict:
    """
    Verify JWT token and return payload.
    Raises HTTPException (401) if invalid.
    """
    credentials_exception = HTTPException(
        status_code=status.HTTP_401_UNAUTHORIZED,
        detail="Could not validate credentials",
        headers={"WWW-Authenticate": "Bearer"},
    )
    try:
        payload = jwt.decode(token, SECRET_KEY, algorithms=[ALGORITHM])
        if payload.get("sub") is None:
            raise credentials_exception
        return payload
    except JWTError:
        raise credentials_exception


async def get_current_user(token: str = Depends(oauth2_scheme), db: Session = Depends(get_db)) -> User:
    """
    Get the current authenticated user from the token.
    Use as a dependency in protected routes.
    """
    credentials_exception = HTTPException(
        status_code=status.HTTP_401_UNAUTHORIZED,
        detail="Could not validate credentials",
        headers={"WWW-Authenticate": "Bearer"},
    )
    
    # Use the shared verification logic
    # Note: verify_token raises the exception if it fails
    payload = verify_token(token)
    username: str = payload.get("sub")
    token_data = TokenData(username=username)
        
    user = UserCRUD.get_by_username(db, username=token_data.username)
    if user is None:
        raise credentials_exception
    
    if not user.is_active:
        raise HTTPException(status_code=400, detail="Inactive user")
        
    # Convert SQLAlchemy model to Pydantic model
    user_dict = user.to_dict()
    return User(**user_dict)


def change_password(
    db: Session,
    user_id: str,
    current_password: str,
    new_password: str
) -> bool:
    """
    Change user password with validation.
    
    Args:
        db: Database session
        user_id: User ID
        current_password: Current password for verification
        new_password: New password to set
        
    Returns:
        True if password changed successfully
        
    Raises:
        HTTPException: If current password is incorrect or validation fails
    """
    import logging
    logger = logging.getLogger(__name__)
    
    # Get user
    user = UserCRUD.get(db, user_id)
    if not user:
        raise HTTPException(status_code=404, detail="User not found")
    
    # Verify current password
    if not verify_password(current_password, user.hashed_password):
        logger.warning(f"Password change failed: incorrect current password for user {user_id}")
        raise HTTPException(
            status_code=400,
            detail="Current password is incorrect"
        )
    
    # Validate new password strength
    if len(new_password) < 8:
        raise HTTPException(
            status_code=400,
            detail="New password must be at least 8 characters long"
        )
    
    # Don't allow same password
    if verify_password(new_password, user.hashed_password):
        raise HTTPException(
            status_code=400,
            detail="New password must be different from current password"
        )
    
    # Hash and update password
    hashed_password = get_password_hash(new_password)
    UserCRUD.update(db, user_id, {
        "hashed_password": hashed_password,
        "password_changed_at": datetime.utcnow()
    })
    
    logger.info(f"Password changed successfully for user: {user_id}")
    return True
