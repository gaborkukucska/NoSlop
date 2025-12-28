import sys
import os
import logging

# Add project root and backend to path
sys.path.append(os.path.abspath(os.path.dirname(__file__))) # for imports within backend

from database import init_db, get_db, AgentMessageCRUD
from models import AgentMessageType

# Configure logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

def verify_messaging():
    logger.info("Initializing DB...")
    init_db()  # This should create the new table
    
    db = next(get_db())
    
    logger.info("Creating test message...")
    msg_data = {
        "id": "test-msg-1",
        "sender_id": "test_agent",
        "recipient_id": "project_manager",
        "message_type": AgentMessageType.INFO.value,
        "content": "This is a test message",
        "context": {"foo": "bar"},
        "status": "pending"
    }
    
    try:
        msg = AgentMessageCRUD.create(db, msg_data)
        logger.info(f"Message created: {msg.id} (Status: {msg.status})")
        
        # Verify retrieval
        pending = AgentMessageCRUD.get_pending(db, "project_manager")
        logger.info(f"Pending messages for PM: {len(pending)}")
        assert len(pending) >= 1
        assert pending[0].content == "This is a test message"
        
        # Verify update
        AgentMessageCRUD.mark_processed(db, "test-msg-1")
        pending_after = AgentMessageCRUD.get_pending(db, "project_manager")
        logger.info(f"Pending messages after processing: {len(pending_after)}")
        
        # Cleanup
        db.delete(msg)
        db.commit()
        logger.info("Test passed!")
        
    except Exception as e:
        logger.error(f"Test failed: {e}")
        raise

if __name__ == "__main__":
    verify_messaging()
