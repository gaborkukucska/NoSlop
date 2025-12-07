
import sys
import os
import logging

# Set up logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

# Add current directory to path
sys.path.append(os.getcwd())

logger.info("Attempting to import main...")

try:
    from main import app
    logger.info("Successfully imported main app")
except Exception as e:
    logger.error(f"Failed to import main: {e}", exc_info=True)
    sys.exit(1)

logger.info("Attempting to initialize database...")
try:
    from database import init_db
    init_db()
    logger.info("Database initialized")
except Exception as e:
    logger.error(f"Failed to initialize database: {e}", exc_info=True)
    sys.exit(1)

logger.info("Startup check passed")
