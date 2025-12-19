
import unittest
from unittest.mock import MagicMock, patch
import os
import sys

# Add backend to path
sys.path.append(os.path.dirname(os.path.abspath(__file__)))

from workers.image_generation_worker import ImageGenerationWorker
from models import Task
from database import TaskStatusEnum

class TestWorkerIntegration(unittest.TestCase):
    def setUp(self):
        self.mock_db = MagicMock()
        self.worker = ImageGenerationWorker(self.mock_db)
        self.worker.client = MagicMock()
        
    def test_worker_uses_custom_workflow(self):
        # Create a mock task with custom workflow metadata
        mock_task = MagicMock()
        mock_task.id = "test_task"
        mock_task.meta_data = {"custom_workflow": "/abs/path/to/custom_workflow.json"}
        mock_task.dependencies = []
        mock_task.description = "A cat"
        
        # Mock client behavior
        self.worker.client.is_connected.return_value = True
        self.worker.client.execute_workflow.return_value = "prompt_123"
        self.worker.client.wait_for_completion.return_value = {"outputs": {}}
        
        # Execute processing logic (we need to mock async loop or run sync parts, 
        # but process_task is async. Let's mock the internal methods called if possible, 
        # or just run it with asyncio.run)
        
        import asyncio
        asyncio.run(self.worker.process_task(mock_task))
        
        # Verify client.execute_workflow was called with the correct path
        self.worker.client.execute_workflow.assert_called_with(
            "/abs/path/to/custom_workflow.json", 
            unittest.mock.ANY
        )
        print("✅ Verified: ImageGenerationWorker used custom workflow path correctly")

if __name__ == '__main__':
    unittest.main()
