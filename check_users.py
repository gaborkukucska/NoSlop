import sys
import os
sys.path.append("/opt/noslop/backend")

from sqlalchemy import create_engine, text
from dotenv import load_dotenv

# Load env vars before importing config to ensure settings are correct
load_dotenv("/opt/noslop/backend/.env")

try:
    from config import settings
except ImportError:
    # Fallback if config import fails (e.g. pydantic missing in this context?)
    # But we run with venv python, so it should be fine.
    print("WARNING: Could not import config. Using manual DB URL.")
    settings = None

def check_users():
    db_url = os.getenv("DATABASE_URL")
    if not db_url:
        print("DATABASE_URL not found in env.")
        if settings:
             db_url = settings.database_url
        else:
             db_url = "postgresql://noslop:noslop@localhost:5432/noslop"

    print(f"Checking database at: {db_url}")
    try:
        engine = create_engine(db_url)
        with engine.connect() as conn:
            result = conn.execute(text("SELECT id, username, hashed_password, role, is_active FROM users"))
            users = result.fetchall()
            print(f"User count: {len(users)}")
            for user in users:
                print(f"User: {user.username} | Role: {user.role} | Active: {user.is_active}")
                print(f"Hash prefix: {user.hashed_password[:20]}")
                
                # Try to verify 'password' (common dev password)
                try:
                    from auth import verify_password
                    is_valid = verify_password("password", user.hashed_password)
                    print(f"--> Verify 'password': {is_valid}")
                except Exception as e:
                    print(f"--> Verify ERROR: {e}")
                
                if user.hashed_password.startswith("$2b$"):
                    print("--> TYPE: BCRYPT")
                elif user.hashed_password.startswith("$argon2"):
                    print("--> TYPE: ARGON2")
                else:
                    print("--> TYPE: UNKNOWN")
    except Exception as e:
        print(f"Error checking users: {e}")

if __name__ == "__main__":
    check_users()
