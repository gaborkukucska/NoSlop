import os
import json
import logging
from typing import Dict, Any, List, Optional
from pathlib import Path

logger = logging.getLogger(__name__)

class WorkflowManager:
    """
    Manages ComfyUI workflows, including loading, parsing, and variable injection.
    """
    
    def __init__(self, workflows_dir: str = None):
        # Default to configured shared storage path if not provided
        self.workflows_dir = workflows_dir or os.environ.get("COMFYUI_WORKFLOWS_DIR", "/mnt/noslop/comfyui/workflows")
        self._workflows_cache = {}
        
    def list_workflows(self) -> List[str]:
        """List available workflow names."""
        if not os.path.exists(self.workflows_dir):
            return []
            
        workflows = []
        for f in os.listdir(self.workflows_dir):
            if f.endswith(".json"):
                workflows.append(f[:-5]) # Remove .json extension
        return sorted(workflows)
    
    def load_workflow(self, name: str) -> Optional[Dict[str, Any]]:
        """
        Load a workflow by name or absolute path.
        
        Args:
            name: Workflow name (in workflows dir) or absolute file path.
        """
        # Check if name is an absolute path to an existing file
        if os.path.isabs(name) and os.path.exists(name):
            path = name
        # Check if name is a relative path to an existing file
        elif os.path.exists(name):
            path = name
        else:
            # Fallback to default workflows directory
            # Strip extension if provided for backward compatibility
            clean_name = name[:-5] if name.endswith(".json") else name
            path = os.path.join(self.workflows_dir, f"{clean_name}.json")
            
        if not os.path.exists(path):
            logger.error(f"Workflow not found: {path}")
            return None
            
        try:
            with open(path, 'r') as f:
                return json.load(f)
        except Exception as e:
            logger.error(f"Failed to load workflow {name}: {e}")
            return None

    def get_workflow_inputs(self, workflow: Dict[str, Any]) -> Dict[str, Any]:
        """
        Identify input parameters in a workflow.
        Returns a dictionary of found inputs and their default values.
        
        Logic: Looks for specific node types or titles acting as inputs.
        - PrimitiveNodes (often used for specific inputs)
        - KSampler (seed, steps, cfg)
        - LoadImage (image path)
        - CLIPTextEncode (text prompts) - hard to distinguish pos/neg without custom titles
        """
        inputs = {}
        
        # ComfyUI workflow format is usually { "node_id": { "inputs": {...}, "class_type": "..." } }
        for node_id, node_data in workflow.items():
            class_type = node_data.get("class_type", "")
            node_inputs = node_data.get("inputs", {})
            title = node_data.get("_meta", {}).get("title", "")
            
            # 1. KSampler Inputs
            if class_type == "KSampler":
                inputs[f"seed"] = node_inputs.get("seed", 0)
                inputs[f"steps"] = node_inputs.get("steps", 20)
                inputs[f"cfg"] = node_inputs.get("cfg", 8.0)
                
            # 2. Text Prompts (CLIPTextEncode)
            # We rely on the user titling the node "Positive Prompt" or "Negative Prompt" for robust mapping
            elif class_type == "CLIPTextEncode":
                if "Positive" in title or "positive" in title:
                    inputs["positive_prompt"] = node_inputs.get("text", "")
                elif "Negative" in title or "negative" in title:
                    inputs["negative_prompt"] = node_inputs.get("text", "")
                else:
                    # Fallback: Generic prompt input
                    inputs[f"prompt_{node_id}"] = node_inputs.get("text", "")

            # 3. Image Inputs
            elif class_type == "LoadImage":
                inputs["image"] = node_inputs.get("image", "")

            # 4. Checkpoint Loader (Model)
            elif class_type == "CheckpointLoaderSimple":
                inputs["model"] = node_inputs.get("ckpt_name", "")

        return inputs

    def inject_inputs(self, workflow: Dict[str, Any], inputs: Dict[str, Any]) -> Dict[str, Any]:
        """
        Inject runtime values into the workflow variables.
        Returns a new workflow dictionary with updated values.
        """
        # Deep copy to avoid modifying cache
        import copy
        new_workflow = copy.deepcopy(workflow)
        
        for node_id, node_data in new_workflow.items():
            class_type = node_data.get("class_type", "")
            title = node_data.get("_meta", {}).get("title", "")
            
            # 1. KSampler
            if class_type == "KSampler":
                if "seed" in inputs:
                    node_data["inputs"]["seed"] = inputs["seed"]
                if "steps" in inputs:
                    node_data["inputs"]["steps"] = inputs["steps"]
                if "cfg" in inputs:
                    node_data["inputs"]["cfg"] = inputs["cfg"]
            
            # 2. Text Prompts
            elif class_type == "CLIPTextEncode":
                if ("Positive" in title or "positive" in title) and "positive_prompt" in inputs:
                    node_data["inputs"]["text"] = inputs["positive_prompt"]
                elif ("Negative" in title or "negative" in title) and "negative_prompt" in inputs:
                    node_data["inputs"]["text"] = inputs["negative_prompt"]
                elif f"prompt_{node_id}" in inputs:
                    node_data["inputs"]["text"] = inputs[f"prompt_{node_id}"]
            
            # 3. Image Load
            elif class_type == "LoadImage" and "image" in inputs:
                node_data["inputs"]["image"] = inputs["image"]
                
             # 4. Checkpoint
            elif class_type == "CheckpointLoaderSimple" and "model" in inputs:
                 node_data["inputs"]["ckpt_name"] = inputs["model"]
                 
        return new_workflow

    def check_missing_models(self, workflow: Dict[str, Any]) -> List[str]:
        """
        Scan workflow for required models and check if they exist in shared storage.
        Returns a list of missing model filenames.
        """
        required_models = set()
        
        for node_id, node_data in workflow.items():
            node_inputs = node_data.get("inputs", {})
            
            # CheckpointLoaderSimple -> ckpt_name
            if "ckpt_name" in node_inputs:
                required_models.add(node_inputs["ckpt_name"])
            
            # LoraLoader -> lora_name
            if "lora_name" in node_inputs:
                required_models.add(node_inputs["lora_name"])
                
            # VAELoader -> vae_name
            if "vae_name" in node_inputs:
                required_models.add(node_inputs["vae_name"])
        
        # Check against shared storage
        models_dir = os.environ.get("COMFYUI_MODELS_DIR", "/mnt/noslop/comfyui/models")
        if not os.path.exists(models_dir):
            logger.warning(f"Models directory not found: {models_dir}")
            return list(required_models) # All missing if dir not found

        # Build index of available models (filename -> path)
        # We walk the directory once to cache available models
        # Optimization: In a real app, this should be cached or done via DB
        available_models = set()
        for root, dirs, files in os.walk(models_dir):
            for file in files:
                available_models.add(file)
        
        missing = []
        for model in required_models:
            if model not in available_models:
                missing.append(model)
                
        return missing

    def validate_workflow_readiness(self, workflow: Dict[str, Any]) -> Dict[str, Any]:
        """
        Check if workflow is ready to run (models exist, etc.)
        Returns dict with 'ready': bool, 'missing_models': list, 'inputs': dict
        """
        inputs = self.get_workflow_inputs(workflow)
        missing = self.check_missing_models(workflow)
        
        return {
            "ready": len(missing) == 0,
            "missing_models": missing,
            "inputs": inputs
        }
