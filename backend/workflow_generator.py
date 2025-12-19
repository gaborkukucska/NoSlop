# START OF FILE backend/workflow_generator.py
import json
import logging
from typing import Dict, Any, Optional
import ollama
import os
import re
import time
from config import settings
from prompt_manager import get_prompt_manager

logger = logging.getLogger(__name__)

class WorkflowGenerator:
    """
    Generates ComfyUI workflows from natural language descriptions using Ollama.
    """
    
    def __init__(self):
        self.model = settings.model_logic
        self.prompt_manager = get_prompt_manager()
        logger.info("WorkflowGenerator initialized")

    def generate_workflow(self, prompt: str) -> Dict[str, Any]:
        """
        Generate a ComfyUI workflow (JSON) from a user prompt.
        
        Args:
            prompt: User description of what they want to generate (e.g. "A cyberpunk city at night")
            
        Returns:
            Dict containing the workflow JSON structure, or error dict.
        """
        logger.info(f"Generating workflow for prompt: {prompt}")
        
        # Get system prompt for workflow generation
        system_prompt = self.prompt_manager.get_prompt("workflow_generator.system_prompt")
        
        try:
            response = ollama.chat(
                model=self.model,
                messages=[
                    {"role": "system", "content": system_prompt},
                    {"role": "user", "content": prompt}
                ],
                format="json",
                options={
                    "temperature": 0.2, # Low temperature for consistent JSON
                    "num_predict": settings.ollama_max_predict
                }
            )
            
            content = response['message']['content']
            logger.debug(f"Raw generation content: {content[:100]}...")
            
            workflow_data = json.loads(content)
            
            # Basic validation
            if not isinstance(workflow_data, dict):
                 raise ValueError("Generated workflow is not a JSON object")
            
            # Fix if wrapped in a key like "workflow" or "json"
            if "workflow" in workflow_data and isinstance(workflow_data["workflow"], dict):
                workflow_data = workflow_data["workflow"]
            
            # Validate essential keys roughly (ComfyUI workflows usually have numeric keys like "3", "4")
            # But template-based generation might return a named structure. 
            # For now, we assume the LLM follows the system prompt instructions.
            
            return {
                "success": True,
                "workflow": workflow_data,
                "description": prompt
            }
            
        except json.JSONDecodeError as e:
            logger.error(f"Failed to parse generated JSON: {e}")
            return {"success": False, "error": "Failed to generate valid JSON"}
        except Exception as e:
            logger.error(f"Workflow generation error: {e}", exc_info=True)
            return {"success": False, "error": str(e)}

    def save_workflow(self, workflow_data: Dict[str, Any], name: str) -> Dict[str, Any]:
        """
        Save a workflow to the configured workflows directory.
        
        Args:
            workflow_data: The workflow JSON object.
            name: Desired filename (without extension).
            
        Returns:
            Dict with success status and path.
        """
        try:
            # Sanitize filename
            safe_name = re.sub(r'[^\w\-_]', '_', name)
            timestamp = time.strftime("%Y%m%d_%H%M%S")
            filename = f"{safe_name}_{timestamp}.json"
            
            # Ensure directory exists
            workflows_dir = settings.comfyui_workflows_dir
            os.makedirs(workflows_dir, exist_ok=True)
            
            path = os.path.join(workflows_dir, filename)
            
            with open(path, 'w') as f:
                json.dump(workflow_data, f, indent=2)
                
            logger.info(f"Saved workflow to {path}")
            return {"success": True, "path": path, "filename": filename}
            
        except Exception as e:
            logger.error(f"Failed to save workflow: {e}", exc_info=True)
            return {"success": False, "error": str(e)}
