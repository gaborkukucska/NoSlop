
import logging
import json
import subprocess
import os
from typing import Dict, Any, List, Optional
from pathlib import Path

logger = logging.getLogger(__name__)

class FFmpegClient:
    """
    Wrapper for executing FFmpeg and FFprobe commands.
    """
    
    def __init__(self, ffmpeg_path: str = "ffmpeg", ffprobe_path: str = "ffprobe"):
        self.ffmpeg_path = ffmpeg_path
        self.ffprobe_path = ffprobe_path
        
    def _run_command(self, command: List[str]) -> str:
        """Run a command and return stdout."""
        try:
            logger.debug(f"Executing: {' '.join(command)}")
            result = subprocess.run(
                command,
                check=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True
            )
            return result.stdout
        except subprocess.CalledProcessError as e:
            logger.error(f"Command failed: {e.stderr}")
            raise RuntimeError(f"FFmpeg command failed: {e.stderr}")

    def get_video_info(self, path: str) -> Dict[str, Any]:
        """Get video metadata using ffprobe."""
        command = [
            self.ffprobe_path,
            "-v", "quiet",
            "-print_format", "json",
            "-show_format",
            "-show_streams",
            path
        ]
        
        output = self._run_command(command)
        data = json.loads(output)
        
        info = {
            "duration": 0.0,
            "width": 0,
            "height": 0,
            "codec": "unknown"
        }
        
        if "format" in data:
            info["duration"] = float(data["format"].get("duration", 0))
            
        for stream in data.get("streams", []):
            if stream["codec_type"] == "video":
                info["width"] = int(stream.get("width", 0))
                info["height"] = int(stream.get("height", 0))
                info["codec"] = stream.get("codec_name", "unknown")
                break
                
        return info

    def create_slideshow(self, image_paths: List[str], output_path: str, duration_per_image: float = 3.0):
        """Create a slideshow video from a list of images."""
        # Create a temporary input file
        list_file = f"{output_path}.txt"
        with open(list_file, "w") as f:
            for img in image_paths:
                f.write(f"file '{img}'\n")
                f.write(f"duration {duration_per_image}\n")
            # Repeat last image to handle duration correctly in some ffmpeg versions
            if image_paths:
                f.write(f"file '{image_paths[-1]}'\n")
        
        command = [
            self.ffmpeg_path,
            "-y", # Overwrite
            "-f", "concat",
            "-safe", "0",
            "-i", list_file,
            "-vsync", "vfr",
            "-pix_fmt", "yuv420p",
            output_path
        ]
        
        try:
            self._run_command(command)
        finally:
            if os.path.exists(list_file):
                os.remove(list_file)

    def concat_videos(self, video_paths: List[str], output_path: str):
        """Concatenate multiple video files."""
        list_file = f"{output_path}.txt"
        with open(list_file, "w") as f:
            for vid in video_paths:
                f.write(f"file '{vid}'\n")
        
        command = [
            self.ffmpeg_path,
            "-y",
            "-f", "concat",
            "-safe", "0",
            "-i", list_file,
            "-c", "copy",
            output_path
        ]
        
        try:
            self._run_command(command)
        finally:
            if os.path.exists(list_file):
                os.remove(list_file)

    def trim_video(self, input_path: str, output_path: str, start_time: float, duration: float):
        """Trim a video file."""
        command = [
            self.ffmpeg_path,
            "-y",
            "-i", input_path,
            "-ss", str(start_time),
            "-t", str(duration),
            "-c", "copy", # Fast copy if possible, otherwise re-encode might be needed for frame accuracy
            output_path
        ]
        self._run_command(command)
