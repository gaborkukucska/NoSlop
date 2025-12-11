
import logging
import json
import os
import random
from typing import Dict, Any, List
from datetime import datetime
from pathlib import Path

from worker_agent import WorkerAgent, WorkerCapabilities, ResourceRequirements
from database import TaskStatusEnum, ProjectCRUD, TaskCRUD
from clients.comfyui_client import ComfyUIClient
from config import settings

logger = logging.getLogger(__name__)

class ImageGenerationWorker(WorkerAgent):
    """
    Worker specialized in generating images using ComfyUI.
    Orchestrates the generation process:
    1. Receives prompt data
    2. Constructs workflow
    3. Queues job in ComfyUI
    4. Retrieves and saves result
    """
    
    _agent_type = "image_generation"
    
    def __init__(self, db_session):
        super().__init__(db_session)
        self.agent_type = "image_generation"
        # Initialize client with config settings (defaulting if not set)
        host = getattr(settings, "COMFYUI_HOST", "127.0.0.1")
        port = getattr(settings, "COMFYUI_PORT", 8188)
        self.client = ComfyUIClient(host=host, port=port)
        
        # Ensure media directory exists
        self.media_dir = Path("media/generated")
        self.media_dir.mkdir(parents=True, exist_ok=True)
    
    @staticmethod
    def _get_capabilities_static():
        return {
            "agent_type": "image_generation",
            "supported_task_types": ["image_generation"],
            "description": "Generates images using local ComfyUI instance",
            "version": "1.0.0"
        }
    
    def get_capabilities(self) -> WorkerCapabilities:
        return WorkerCapabilities(
            agent_type=self.agent_type,
            supported_task_types=["image_generation"],
            description="Generates images using local ComfyUI instance",
            version="1.0.0"
        )
    
    def get_resource_requirements(self) -> ResourceRequirements:
        return ResourceRequirements(
            gpu=True,
            min_ram_gb=8,
            cpu_cores=2,
            disk_space_gb=4,
            network=True
        )
        
    def _get_default_workflow(self) -> Dict[str, Any]:
        """
        Returns a default SDXL text-to-image workflow.
        In a real system, this would load from a file or template.
        """
        # Simplified workflow structure for demonstration
        # This matches standard ComfyUI API format (node graph)
        return {
            "3": {
                "inputs": {
                    "seed": 0,
                    "steps": 20,
                    "cfg": 8.0,
                    "sampler_name": "euler",
                    "scheduler": "normal",
                    "denoise": 1.0,
                    "model": ["4", 0],
                    "positive": ["6", 0],
                    "negative": ["7", 0],
                    "latent_image": ["5", 0]
                },
                "class_type": "KSampler"
            },
            "4": {
                "inputs": {
                    "ckpt_name": "sd_xl_base_1.0.safetensors"
                },
                "class_type": "CheckpointLoaderSimple"
            },
            "5": {
                "inputs": {
                    "width": 1024,
                    "height": 1024,
                    "batch_size": 1
                },
                "class_type": "EmptyLatentImage"
            },
            "6": {
                "inputs": {
                    "text": "",
                    "clip": ["4", 1]
                },
                "class_type": "CLIPTextEncode"
            },
            "7": {
                "inputs": {
                    "text": "",
                    "clip": ["4", 1]
                },
                "class_type": "CLIPTextEncode"
            },
            "8": {
                "inputs": {
                    "samples": ["3", 0],
                    "vae": ["4", 2]
                },
                "class_type": "VAEDecode"
            },
            "9": {
                "inputs": {
                    "filename_prefix": "NoSlop",
                    "images": ["8", 0]
                },
                "class_type": "SaveImage"
            }
        }

    async def process_task(self, task) -> Dict[str, Any]:
        logger.info(f"ImageGenerationWorker starting task: {task.id}")
        
        try:
            self.update_status(task.id, TaskStatusEnum.IN_PROGRESS)
            self.report_progress(task.id, 10, "Checking ComfyUI connection")
            
            if not self.client.is_connected():
                raise ConnectionError("ComfyUI is not reachable")
                
            # Get prompt data from dependencies
            prompt_data = self._get_prompt_data(task)
            
            # Prepare workflow
            self.report_progress(task.id, 30, "Preparing workflow")
            workflow = self._get_default_workflow()
            
            # Inject parameters
            seed = random.randint(1, 1000000000)
            workflow["3"]["inputs"]["seed"] = seed
            workflow["6"]["inputs"]["text"] = prompt_data.get("positive_prompt", "")
            workflow["7"]["inputs"]["text"] = prompt_data.get("negative_prompt", "")
            
            # Queue prompt
            self.report_progress(task.id, 50, "Queuing generation job")
            prompt_id = self.client.queue_prompt(workflow)
            logger.info(f"Queued prompt {prompt_id}")
            
            # Wait for completion
            self.report_progress(task.id, 60, "Waiting for generation")
            result_data = self.client.wait_for_completion(prompt_id)
            
            # Process outputs
            self.report_progress(task.id, 90, "Processing results")
            outputs = result_data.get("outputs", {})
            generated_files = []
            
            for node_id, node_output in outputs.items():
                if "images" in node_output:
                    for image in node_output["images"]:
                        filename = image["filename"]
                        subfolder = image["subfolder"]
                        folder_type = image["type"]
                        
                        # Download image
                        image_data = self.client.get_image(filename, subfolder, folder_type)
                        
                        # Save locally
                        local_filename = f"{task.id}_{filename}"
                        local_path = self.media_dir / local_filename
                        with open(local_path, "wb") as f:
                            f.write(image_data)
                            
                        generated_files.append(str(local_path.absolute()))
            
            result = {
                "generated_files": generated_files,
                "prompt_id": prompt_id,
                "seed": seed,
                "generated_at": datetime.utcnow().isoformat()
            }
            
            self.report_progress(task.id, 100, "Generation complete")
            self.update_status(task.id, TaskStatusEnum.COMPLETED, result)
            
            return result
            
        except Exception as e:
            logger.error(f"ImageGenerationWorker failed task {task.id}: {e}", exc_info=True)
            self.update_status(task.id, TaskStatusEnum.FAILED, {"error": str(e)})
            raise

    def _get_prompt_data(self, task) -> Dict[str, Any]:
        """Retrieve prompt data from dependencies or task description."""
        # Check dependencies first
        if task.dependencies:
            for dep_id in task.dependencies:
                dep_task = TaskCRUD.get(self.db, dep_id)
                if dep_task and dep_task.task_type.value == "prompt_engineering":
                    if dep_task.result:
                        return dep_task.result
        
        # Fallback to task description/metadata
        return {
            "positive_prompt": task.description,
            "negative_prompt": "text, watermark, blurry"
        }
