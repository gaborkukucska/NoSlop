
import asyncio
import logging
import sys
import os

# Add backend to path
sys.path.append(os.path.dirname(os.path.abspath(__file__)))

from workflow_generator import WorkflowGenerator
from config import settings
from admin_ai import AdminAI

# Setup logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

async def test_workflow_generation():
    logger.info("Testing WorkflowGenerator...")
    generator = WorkflowGenerator()
    
    prompt = "Create a workflow for a cyberpunk city scene at night with neon lights."
    result = generator.generate_workflow(prompt)
    
    if result.get("success"):
        logger.info("✅ Workflow generated successfully")
        logger.info(f"Keys in workflow: {list(result['workflow'].keys())}")
    else:
        logger.error(f"❌ Workflow generation failed: {result.get('error')}")

async def test_admin_ai_config():
    logger.info("Testing AdminAI configuration...")
    admin = AdminAI()
    
    # Check if num_predict is correctly calculated in the chat method 
    # (We can't easily inspect the partial inside chat without running it, 
    # but we can check if the code runs without syntax errors)
    
    logger.info(f"Ollama Max Predict setting: {settings.ollama_max_predict}")
    logger.info("✅ AdminAI initialized successfully")

if __name__ == "__main__":
    loop = asyncio.get_event_loop()
    loop.run_until_complete(test_workflow_generation())
    loop.run_until_complete(test_admin_ai_config())
