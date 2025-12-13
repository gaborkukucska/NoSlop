
import logging
import os
import io
import torch
from faster_whisper import WhisperModel
from transformers import SpeechT5Processor, SpeechT5ForTextToSpeech, SpeechT5HifiGan
from datasets import load_dataset
import soundfile as sf
import numpy as np

logger = logging.getLogger(__name__)

class VoiceService:
    def __init__(self, model_size="tiny", device="cpu", compute_type="int8"):
        """
        Initialize Voice Service with Whisper and SpeechT5.
        
        Args:
            model_size: Whisper model size (tiny, base, small, medium, large-v2)
            device: 'cuda' or 'cpu'
            compute_type: 'float16' or 'int8'
        """
        self.device = device
        self.logger = logging.getLogger("VoiceService")
        
        # Get custom cache paths from env
        self.whisper_cache = os.environ.get("WHISPER_CACHE_DIR")
        # Transformers uses HF_HOME env var automatically, so we don't need to pass it explicitly if set.
        
        if self.whisper_cache:
            self.logger.info(f"Using Whisper cache: {self.whisper_cache}")
            os.makedirs(self.whisper_cache, exist_ok=True)

        # Initialize Whisper (STT)
        self.logger.info(f"Loading Whisper model ({model_size}) on {device}...")
        try:
            # download_root specifies where to store/load the model
            self.stt_model = WhisperModel(model_size, device=device, compute_type=compute_type, download_root=self.whisper_cache)
            self.logger.info("Whisper model loaded successfully")
        except Exception as e:
            self.logger.error(f"Failed to load Whisper model: {e}")
            self.stt_model = None

        # Initialize SpeechT5 (TTS)
        self.logger.info("Loading SpeechT5 model...")
        try:
            self.processor = SpeechT5Processor.from_pretrained("microsoft/speecht5_tts")
            self.tts_model = SpeechT5ForTextToSpeech.from_pretrained("microsoft/speecht5_tts")
            self.vocoder = SpeechT5HifiGan.from_pretrained("microsoft/speecht5_hifigan")
            
            # NOTE: The speaker embeddings dataset has deprecated loading scripts
            # Using default embeddings instead (512-dim for SpeechT5)
            self.logger.info("Using default speaker embeddings")
            self.speaker_embeddings = torch.randn(1, 512)
            
            self.logger.info("SpeechT5 model loaded successfully")
        except Exception as e:
            self.logger.error(f"Failed to load SpeechT5 model: {e}")
            self.tts_model = None

    def transcribe(self, audio_file) -> str:
        """
        Transcribe audio file to text.
        
        Args:
            audio_file: Path to audio file or file-like object
            
        Returns:
            Transcribed text
        """
        if not self.stt_model:
            raise RuntimeError("STT model not initialized")

        segments, info = self.stt_model.transcribe(audio_file, beam_size=5)
        
        text = ""
        for segment in segments:
            text += segment.text + " "
            
        return text.strip()

    def generate_speech(self, text: str) -> io.BytesIO:
        """
        Generate speech from text.
        
        Args:
            text: Text to convert to speech
            
        Returns:
            BytesIO object containing WAV audio
        """
        if not self.tts_model:
            raise RuntimeError("TTS model not initialized")

        inputs = self.processor(text=text, return_tensors="pt")
        
        # Generate speech
        speech = self.tts_model.generate_speech(inputs["input_ids"], self.speaker_embeddings, vocoder=self.vocoder)
        
        # Convert to WAV
        buffer = io.BytesIO()
        sf.write(buffer, speech.numpy(), samplerate=16000, format='WAV')
        buffer.seek(0)
        
        return buffer
