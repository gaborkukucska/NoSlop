# START OF FILE backend/admin_ai.py
import ollama
from typing import List, Dict, Any, Optional
from datetime import datetime
import json

from models import (
    PersonalityProfile, 
    PersonalityType, 
    ChatMessage, 
    ChatResponse,
    ProjectRequest,
    ProjectType
)
from config import settings


class AdminAI:
    """
    Admin AI - The primary interface for user interaction.
    Guides users through media creation workflows with a customizable personality.
    """
    
    def __init__(self, personality: Optional[PersonalityProfile] = None):
        """
        Initialize Admin AI with a personality profile.
        
        Args:
            personality: Personality configuration. Defaults to balanced.
        """
        self.personality = personality or PersonalityProfile(type=PersonalityType.BALANCED)
        self.conversation_history: List[ChatMessage] = []
        self.model = settings.ollama_default_model
        self.context: Dict[str, Any] = {}
        
    def load_personality(self, personality_type: str) -> None:
        """
        Load a preset personality profile.
        
        Args:
            personality_type: One of 'creative', 'technical', 'balanced'
        """
        if personality_type in settings.personality_presets:
            preset = settings.personality_presets[personality_type]
            self.personality = PersonalityProfile(**preset)
        else:
            raise ValueError(f"Unknown personality type: {personality_type}")
    
    def _build_system_prompt(self) -> str:
        """
        Build the system prompt based on current personality.
        
        Returns:
            System prompt string
        """
        base_prompt = """You are the Admin AI for NoSlop, a self-hosted AI-driven media creation platform.
Your role is to guide users through creating professional-grade media content using local AI tools.

You help users:
- Brainstorm creative ideas for videos, images, and audio
- Plan and structure media projects
- Navigate the media creation workflow
- Make decisions about style, tone, and technical aspects

You have access to powerful local tools:
- Ollama (LLMs for creative writing and assistance)
- ComfyUI (image and video generation)
- FFmpeg (video editing and processing)
- OpenCV (computer vision and analysis)

You work with a Project Manager agent who handles task orchestration and specialized Worker agents who execute specific tasks.
"""
        
        # Adjust tone based on personality
        if self.personality.creativity > 0.7:
            base_prompt += "\nYou are highly creative and enthusiastic, encouraging bold artistic choices and innovative ideas."
        elif self.personality.technical_depth > 0.7:
            base_prompt += "\nYou are technically precise and detail-oriented, focusing on specifications and best practices."
        else:
            base_prompt += "\nYou balance creativity with practicality, offering both artistic and technical guidance."
        
        if self.personality.formality < 0.4:
            base_prompt += "\nYou communicate in a friendly, casual manner."
        elif self.personality.formality > 0.7:
            base_prompt += "\nYou communicate in a professional, formal manner."
        
        if self.personality.enthusiasm > 0.7:
            base_prompt += "\nYou are energetic and encouraging, celebrating user ideas and progress."
        
        return base_prompt
    
    def _format_conversation_history(self, max_messages: int = 10) -> List[Dict[str, str]]:
        """
        Format conversation history for Ollama API.
        
        Args:
            max_messages: Maximum number of recent messages to include
            
        Returns:
            List of message dictionaries
        """
        recent_messages = self.conversation_history[-max_messages:]
        return [
            {"role": msg.role, "content": msg.content}
            for msg in recent_messages
        ]
    
    def chat(self, message: str, context: Optional[Dict[str, Any]] = None) -> ChatResponse:
        """
        Main conversation interface with the Admin AI.
        
        Args:
            message: User message
            context: Additional context (current project, user preferences, etc.)
            
        Returns:
            ChatResponse with AI message and optional suggestions
        """
        # Update context
        if context:
            self.context.update(context)
        
        # Add user message to history
        user_msg = ChatMessage(role="user", content=message)
        self.conversation_history.append(user_msg)
        
        # Build messages for Ollama
        messages = [
            {"role": "system", "content": self._build_system_prompt()}
        ]
        messages.extend(self._format_conversation_history())
        
        # Add context if available
        if self.context:
            context_str = f"\n\nCurrent context: {json.dumps(self.context, indent=2)}"
            messages[-1]["content"] += context_str
        
        try:
            # Call Ollama
            response = ollama.chat(
                model=self.model,
                messages=messages,
                options={
                    "temperature": self.personality.creativity,
                    "num_predict": int(200 * self.personality.verbosity + 100)
                }
            )
            
            ai_message = response['message']['content']
            
            # Add AI response to history
            ai_msg = ChatMessage(role="assistant", content=ai_message)
            self.conversation_history.append(ai_msg)
            
            # Detect if user wants to create a project
            action = self._detect_action(message, ai_message)
            suggestions = self._generate_suggestions(message, ai_message)
            
            return ChatResponse(
                message=ai_message,
                suggestions=suggestions,
                action=action,
                metadata={"model": self.model, "personality": self.personality.type}
            )
            
        except Exception as e:
            error_msg = f"I apologize, but I encountered an error: {str(e)}. Please try again."
            return ChatResponse(
                message=error_msg,
                suggestions=["Try rephrasing your message", "Check if Ollama is running"],
                metadata={"error": str(e)}
            )
    
    def _detect_action(self, user_message: str, ai_response: str) -> Optional[str]:
        """
        Detect if an action should be triggered based on conversation.
        
        Args:
            user_message: User's message
            ai_response: AI's response
            
        Returns:
            Action name or None
        """
        # Simple keyword detection (can be enhanced with LLM-based classification)
        create_keywords = ["create", "make", "start", "new project", "begin"]
        user_lower = user_message.lower()
        
        if any(keyword in user_lower for keyword in create_keywords):
            if any(pt.value in user_lower for pt in ProjectType):
                return "create_project"
        
        return None
    
    def _generate_suggestions(self, user_message: str, ai_response: str) -> List[str]:
        """
        Generate follow-up suggestions based on conversation.
        
        Args:
            user_message: User's message
            ai_response: AI's response
            
        Returns:
            List of suggestion strings
        """
        suggestions = []
        
        # Context-aware suggestions
        if "project" in user_message.lower() or "create" in user_message.lower():
            suggestions.extend([
                "Tell me more about the style you're envisioning",
                "Do you have any reference media?",
                "What's the target duration?"
            ])
        elif not self.conversation_history or len(self.conversation_history) < 3:
            suggestions.extend([
                "I want to create a video",
                "Help me brainstorm ideas",
                "What can you help me with?"
            ])
        
        return suggestions[:3]  # Limit to 3 suggestions
    
    def guide_project_creation(self, user_input: str) -> Dict[str, Any]:
        """
        Interactive project creation workflow.
        
        Args:
            user_input: User's project description
            
        Returns:
            Dictionary with project details and next steps
        """
        # Use LLM to extract project details
        extraction_prompt = f"""Based on this user input, extract project details:
"{user_input}"

Identify:
1. Project type (cinematic_film, corporate_video, advertisement, etc.)
2. Title (if mentioned, otherwise suggest one)
3. Description
4. Duration (if mentioned)
5. Style preferences

Respond in JSON format."""

        try:
            response = ollama.chat(
                model=self.model,
                messages=[{"role": "user", "content": extraction_prompt}],
                format="json"
            )
            
            project_details = json.loads(response['message']['content'])
            
            return {
                "project_details": project_details,
                "next_steps": [
                    "Review and confirm project details",
                    "Add reference media (optional)",
                    "Start project creation"
                ],
                "status": "ready_for_creation"
            }
            
        except Exception as e:
            return {
                "error": str(e),
                "status": "needs_clarification",
                "next_steps": ["Please provide more details about your project"]
            }
    
    def get_suggestions(self, project_type: str) -> List[str]:
        """
        Get creative suggestions for a project type.
        
        Args:
            project_type: Type of project
            
        Returns:
            List of creative suggestions
        """
        prompt = f"""Suggest 5 creative ideas for a {project_type} project.
Be specific and inspiring. Focus on unique angles and interesting concepts.
Keep each suggestion to 1-2 sentences."""

        try:
            response = ollama.chat(
                model=self.model,
                messages=[{"role": "user", "content": prompt}],
                options={"temperature": 0.9}
            )
            
            # Parse suggestions from response
            suggestions_text = response['message']['content']
            suggestions = [s.strip() for s in suggestions_text.split('\n') if s.strip() and not s.strip().startswith('#')]
            
            return suggestions[:5]
            
        except Exception as e:
            return [f"Error generating suggestions: {str(e)}"]
    
    def clear_history(self) -> None:
        """Clear conversation history."""
        self.conversation_history = []
        self.context = {}
