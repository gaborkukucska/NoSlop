import logging
import sys
import os

# Add current directory to path
sys.path.append(os.path.dirname(os.path.abspath(__file__)))

from database import engine, Base, init_db

# Configure logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

def reset_database():
    """
    Reset the database by dropping and recreating all tables.
    WARNING: This will delete all data!
    """
    logger.info("Starting database reset...")
    
    try:
        # Drop all tables
        logger.info("Dropping all existing tables...")
        Base.metadata.drop_all(bind=engine)
        logger.info("Tables dropped successfully.")
        
        # Re-create all tables
        logger.info("Re-creating tables from schema...")
        init_db()
        logger.info("Database reset complete. You can now restart the backend service.")
        
    except Exception as e:
        logger.error(f"Error resetting database: {e}")
        sys.exit(1)

if __name__ == "__main__":
    confirm = input("This will DELETE ALL DATA in the database. Are you sure? (y/N): ")
    if confirm.lower() == 'y':
        reset_database()
    else:
        logger.info("Operation cancelled.")
