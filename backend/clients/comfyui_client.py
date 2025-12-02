
import logging
import json
import time
import uuid
import websocket
import urllib.request
import urllib.parse
from typing import Dict, Any, Optional, List
from dataclasses import dataclass

logger = logging.getLogger(__name__)

@dataclass
class ComfyUIConfig:
    host: str
    port: int
    output_dir: str

class ComfyUIClient:
    """
    Client for interacting with ComfyUI API.
    Handles workflow queuing, websocket connection for status updates,
    and history retrieval.
    """
    
    def __init__(self, host: str = "127.0.0.1", port: int = 8188):
        self.host = host
        self.port = port
        self.base_url = f"http://{host}:{port}"
        self.ws_url = f"ws://{host}:{port}/ws"
        self.client_id = str(uuid.uuid4())
        self.ws = None
        
    def is_connected(self) -> bool:
        """Check if ComfyUI is reachable."""
        try:
            with urllib.request.urlopen(f"{self.base_url}/system_stats", timeout=2) as response:
                return response.status == 200
        except Exception:
            return False

    def queue_prompt(self, prompt: Dict[str, Any]) -> str:
        """
        Queue a workflow for execution.
        
        Args:
            prompt: The full workflow JSON structure
            
        Returns:
            prompt_id: The ID of the queued execution
        """
        p = {"prompt": prompt, "client_id": self.client_id}
        data = json.dumps(p).encode('utf-8')
        
        req = urllib.request.Request(f"{self.base_url}/prompt", data=data)
        
        try:
            with urllib.request.urlopen(req) as response:
                response_data = json.loads(response.read())
                return response_data['prompt_id']
        except Exception as e:
            logger.error(f"Failed to queue prompt: {e}")
            raise

    def get_history(self, prompt_id: str) -> Dict[str, Any]:
        """Get execution history for a prompt ID."""
        try:
            with urllib.request.urlopen(f"{self.base_url}/history/{prompt_id}") as response:
                return json.loads(response.read())
        except Exception as e:
            logger.error(f"Failed to get history: {e}")
            raise

    def get_image(self, filename: str, subfolder: str, folder_type: str) -> bytes:
        """Download generated image."""
        data = {"filename": filename, "subfolder": subfolder, "type": folder_type}
        url_values = urllib.parse.urlencode(data)
        
        try:
            with urllib.request.urlopen(f"{self.base_url}/view?{url_values}") as response:
                return response.read()
        except Exception as e:
            logger.error(f"Failed to get image: {e}")
            raise

    def connect_websocket(self):
        """Connect to ComfyUI websocket."""
        try:
            self.ws = websocket.WebSocket()
            self.ws.connect(f"{self.ws_url}?clientId={self.client_id}")
            logger.info(f"Connected to ComfyUI websocket: {self.ws_url}")
        except Exception as e:
            logger.error(f"Failed to connect to websocket: {e}")
            raise

    def close_websocket(self):
        """Close websocket connection."""
        if self.ws:
            self.ws.close()
            self.ws = None

    def wait_for_completion(self, prompt_id: str, timeout: int = 300) -> Dict[str, Any]:
        """
        Wait for workflow completion via websocket.
        
        Args:
            prompt_id: ID of the prompt to wait for
            timeout: Max wait time in seconds
            
        Returns:
            Dictionary containing output data (filenames, etc.)
        """
        if not self.ws:
            self.connect_websocket()
            
        start_time = time.time()
        
        try:
            while True:
                if time.time() - start_time > timeout:
                    raise TimeoutError(f"Timed out waiting for prompt {prompt_id}")
                
                out = self.ws.recv()
                if isinstance(out, str):
                    message = json.loads(out)
                    
                    if message['type'] == 'executing':
                        data = message['data']
                        if data['node'] is None and data['prompt_id'] == prompt_id:
                            # Execution finished
                            logger.info(f"Prompt {prompt_id} execution finished")
                            break
                            
                    elif message['type'] == 'execution_start':
                        data = message['data']
                        if data['prompt_id'] == prompt_id:
                            logger.info(f"Prompt {prompt_id} started execution")
                            
        except Exception as e:
            logger.error(f"Error waiting for completion: {e}")
            raise
        finally:
            # We don't close the WS here to allow reuse, 
            # but in a robust system we might manage connection lifecycle better
            pass
            
        # Get final history to return outputs
        history = self.get_history(prompt_id)
        return history[prompt_id]
