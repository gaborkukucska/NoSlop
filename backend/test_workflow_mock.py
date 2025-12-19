
import unittest
from unittest.mock import MagicMock, patch
import os
import shutil
import json
import sys

# Add backend to path
sys.path.append(os.path.dirname(os.path.abspath(__file__)))

from workflow_generator import WorkflowGenerator
from config import settings

class TestWorkflowGenerator(unittest.TestCase):
    def setUp(self):
        # Use a temp directory for tests
        self.test_dir = "/tmp/noslop_test_workflows"
        if os.path.exists(self.test_dir):
            shutil.rmtree(self.test_dir)
        os.makedirs(self.test_dir)
        
        # Override settings
        settings.comfyui_workflows_dir = self.test_dir
        
        self.generator = WorkflowGenerator()

    @patch('ollama.chat')
    def test_generate_and_save(self, mock_chat):
        # Mock LLM response
        mock_workflow = {
            "3": {"class_type": "KSampler", "inputs": {}}
        }
        mock_chat.return_value = {
            'message': {
                'content': json.dumps(mock_workflow)
            }
        }
        
        # Test generation
        prompt = "Test workflow"
        result = self.generator.generate_workflow(prompt)
        
        self.assertTrue(result['success'])
        self.assertEqual(result['workflow'], mock_workflow)
        
        # Test saving
        save_result = self.generator.save_workflow(result['workflow'], "test_workflow")
        
        self.assertTrue(save_result['success'])
        self.assertTrue(os.path.exists(save_result['path']))
        self.assertTrue(save_result['filename'].startswith("test_workflow"))
        self.assertTrue(save_result['filename'].endswith(".json"))
        
        # Verify content
        with open(save_result['path'], 'r') as f:
            saved_data = json.load(f)
        self.assertEqual(saved_data, mock_workflow)
        
        print(f"✅ Workflow saved to {save_result['path']}")

    def tearDown(self):
        if os.path.exists(self.test_dir):
            shutil.rmtree(self.test_dir)

if __name__ == '__main__':
    unittest.main()
