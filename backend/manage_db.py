import sys
import os
import shutil
import logging
from datetime import datetime
from sqlalchemy import inspect, text

# Add current directory to path
sys.path.append(os.path.dirname(os.path.abspath(__file__)))

from dotenv import load_dotenv
import os

# Explicitly load .env from current directory - MUST BE BEFORE importing config
load_dotenv(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".env"))

from database import engine, Base, init_db
from config import settings

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("db_manager")

logger.info(f"Using database URL: {settings.database_url}")


def check_schema():
    """
    Check if the current database schema matches the expected models.
    Returns:
        bool: True if schema is compatible, False otherwise.
    """
    try:
        inspector = inspect(engine)
        
        # Check for 'users' table
        if not inspector.has_table("users"):
            logger.info("Table 'users' not found. New initialization needed.")
            return False 
            
        columns = [c["name"] for c in inspector.get_columns("users")]
        required_columns = [
            "role", "is_active", "bio", "custom_data",
            "display_name", "avatar_url", "interests", "address",
            "content_goals", "timezone", "experience_level"
        ]
        
        missing = [c for c in required_columns if c not in columns]
        if missing:
            logger.warning(f"Schema mismatch! Missing columns in 'users': {missing}")
            return False
            
        return True
    except Exception as e:
        logger.error(f"Error checking schema: {e}")
        # If we can't check, assume it might be broken or empty
        return False

def backup_db():
    """
    Backup the SQLite database file.
    """
    import subprocess

    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    
    if "sqlite" in settings.database_url:
        # Extract path from sqlite:///./noslop.db -> ./noslop.db
        db_path = settings.database_url.replace("sqlite:///", "")
        
        # Resolve absolute path relative to current dir
        if db_path.startswith("./"):
            db_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), db_path[2:])
            
        if os.path.exists(db_path):
            backup_path = f"{db_path}.bak_{timestamp}"
            try:
                shutil.copy2(db_path, backup_path)
                logger.info("="*50)
                logger.info(f"SAFEGUARD: Database backed up to: {backup_path}")
                logger.info("="*50)
                return True
            except Exception as e:
                logger.error(f"Failed to backup database: {e}")
                return False
    elif "postgresql" in settings.database_url:
        # PostgreSQL Backup
        try:
            # Parse DB URL roughly to mask password in logs, but strictly pg_dump handles connection via env or params
            # We can use the URL directly with pg_dump if we formats it correctly or use params.
            # Easiest is to pass the full URL string to pg_dump via -d
            
            backup_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "backups")
            os.makedirs(backup_dir, exist_ok=True)
            backup_file = os.path.join(backup_dir, f"noslop_backup_{timestamp}.sql")
            
            logger.info("performing pg_dump...")
            
            # Using Popen to mask potential password leaks in process lists if possible, 
            # but providing URL is standard.
            # Construct command: pg_dump --dbname=postgresql://... -f backup_file
            
            # NOTE: subprocess environment might need PGPASSWORD if not in URL.
            # SQLAlchemy URL usually has it.
            
            cmd = ["pg_dump", "--dbname", settings.database_url, "-f", backup_file]
            
            result = subprocess.run(cmd, check=True, capture_output=True, text=True)
            
            logger.info("="*50)
            logger.info(f"SAFEGUARD: Postgres Database backed up to: {backup_file}")
            logger.info("="*50)
            return True
            
        except subprocess.CalledProcessError as e:
            logger.error(f"Failed to backup PostgreSQL database: {e.stderr}")
            return False
        except Exception as e:
            logger.error(f"Failed to backup PostgreSQL database: {e}")
            return False
    else:
        logger.info("Skipping backup (unknown database type)")
        return False

def reset_db():
    """
    Drop and recreate all tables.
    """
    logger.info("Resetting database schema...")
    try:
        Base.metadata.drop_all(bind=engine)
        init_db()
        logger.info("Database reset complete.")
    except Exception as e:
        logger.error(f"Failed to reset database: {e}")
        sys.exit(1)

def check_and_apply_migrations():
    """
    Check schema and apply necessary migrations automatically.
    """
    try:
        inspector = inspect(engine)
        
        # Check if projects table exists
        if not inspector.has_table("projects"):
            logger.info("Projects table not found. Creating schema...")
            init_db()
            logger.info("✅ Database schema created successfully")
            return True
        
        # Check for missing columns in projects table
        existing_columns = {col['name'] for col in inspector.get_columns('projects')}
        required_columns = {'id', 'user_id', 'title', 'project_type', 'description', 'status', 
                           'created_at', 'updated_at', 'duration', 'style', 'folder_path',
                           'workflows_count', 'media_count', 'storage_size_mb', 
                           'reference_media', 'meta_data'}
        
        missing_columns = required_columns - existing_columns
        
        if missing_columns:
            logger.info("="*70)
            logger.info("SCHEMA MIGRATION REQUIRED")
            logger.info("="*70)
            logger.info(f"Missing columns in 'projects' table: {', '.join(missing_columns)}")
            logger.info("Applying automatic migration...")
            
            with engine.connect() as conn:
                # Apply migrations for missing columns
                if 'user_id' in missing_columns:
                    logger.info("  → Adding 'user_id' column...")
                    conn.execute(text("ALTER TABLE projects ADD COLUMN user_id VARCHAR"))
                    conn.execute(text("""
                        ALTER TABLE projects 
                        ADD CONSTRAINT fk_projects_user 
                        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
                    """))
                    conn.execute(text("CREATE INDEX idx_projects_user_id ON projects(user_id)"))
                    
                    # Check if there are existing projects
                    result = conn.execute(text("SELECT COUNT(*) FROM projects"))
                    count = result.scalar()
                    
                    if count > 0:
                        logger.warning(f"  ⚠️  WARNING: {count} existing projects found without user_id")
                        logger.warning("  → Manual intervention may be required to assign ownership")
                    else:
                        # Safe to make NOT NULL since no existing data
                        conn.execute(text("ALTER TABLE projects ALTER COLUMN user_id SET NOT NULL"))
                        logger.info("  → Set user_id as NOT NULL")
                    
                    conn.commit()
                    logger.info("  ✅ user_id column added successfully")
                
            logger.info("="*70)
            logger.info("✅ MIGRATION COMPLETED")
            logger.info("="*70)
            return True
        
        # Check for agent_messages table
        if not inspector.has_table("agent_messages"):
            logger.info("agent_messages table missing. Creating...")
            init_db()
            logger.info("✅ agent_messages table created")
        
        logger.info("✅ Database schema is up to date")
        return True
        
    except Exception as e:
        logger.error(f"❌ Migration failed: {e}")
        logger.error("Manual intervention required. Check database state.")
        return False

def main():
    if len(sys.argv) > 1 and sys.argv[1] == "--check-and-upgrade":
        # Check if DB file exists (only for SQLite)
        db_exists = True # Default to True (assume DB server is running for Postgres)
        if "sqlite" in settings.database_url:
            db_path = settings.database_url.replace("sqlite:///", "")
            if db_path.startswith("./"):
                db_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), db_path[2:])
            db_exists = os.path.exists(db_path)
        
        if db_exists:
            logger.info("Checking database schema...")
            
            # Try automatic migration first
            if check_and_apply_migrations():
                logger.info("Database is ready")
            else:
                # Fall back to old behavior if migration fails
                logger.info("Attempting schema compatibility check...")
                if not check_schema():
                    logger.warning("Database schema is incompatible with this version.")
                    logger.info("Performing safety backup before reset...")
                    backup_db()
                    reset_db()
                else:
                    logger.info("Database schema is compatible.")
        else:
            logger.info("No existing database found. Initializing new database...")
            init_db()
    else:
        print("Usage: python manage_db.py --check-and-upgrade")

if __name__ == "__main__":
    main()
