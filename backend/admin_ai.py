# START OF FILE backend/admin_ai.py
import ollama
from typing import List, Dict, Any, Optional
from datetime import datetime
import json
import logging

from models import (
    PersonalityProfile, 
    PersonalityType, 
    ChatMessage, 
    ChatResponse,
    ProjectRequest,
    ProjectType
)
from config import settings
from prompt_manager import get_prompt_manager
from sqlalchemy.orm import Session
from project_manager import ProjectManager

logger = logging.getLogger(__name__)


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
        self.prompt_manager = get_prompt_manager()
        
        logger.info("Admin AI initialized", extra={"context": {"personality": self.personality.type, "model": self.model}})
        
    def load_personality(self, personality_type: str) -> None:
        """
        Load a preset personality profile.
        
        Args:
            personality_type: One of 'creative', 'technical', 'balanced'
        """
        if personality_type in settings.personality_presets:
            preset = settings.personality_presets[personality_type]
            self.personality = PersonalityProfile(**preset)
            logger.info(f"Personality changed to {personality_type}")
        else:
            logger.error(f"Unknown personality type: {personality_type}")
            raise ValueError(f"Unknown personality type: {personality_type}")
    
    def _build_system_prompt(self) -> str:
        """
        Build the system prompt based on current personality using prompt manager.
        
        Returns:
            System prompt string
        """
        # Use centralized prompt management
        system_prompt = self.prompt_manager.get_admin_ai_system_prompt(
            personality_type=self.personality.type,
            formality=self.personality.formality,
            enthusiasm=self.personality.enthusiasm
        )
        
        logger.debug("Built system prompt", extra={"context": {"personality": self.personality.type}})
        return system_prompt
    
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
    
    def chat(self, message: str, context: Optional[Dict[str, Any]] = None, db: Optional[Session] = None) -> ChatResponse:
        """
        Main conversation interface with the Admin AI.
        
        Args:
            message: User message
            context: Additional context (current project, user preferences, etc.)
            db: Database session for performing actions
            
        Returns:
            ChatResponse with AI message and optional suggestions
        """
        logger.info("Chat request received", extra={"context": {"message_length": len(message)}})
        
        # Update context
        if context:
            self.context.update(context)
            logger.debug("Context updated", extra={"context": {"keys": list(context.keys())}})
        
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
            logger.debug("Calling Ollama", extra={"context": {"model": self.model, "message_count": len(messages)}})
            
            response = ollama.chat(
                model=self.model,
                messages=messages,
                options={
                    "temperature": self.personality.creativity,
                    "num_predict": int(200 * self.personality.verbosity + 100)
                }
            )
            
            ai_message = response['message']['content']
            logger.info("Ollama response received", extra={"context": {"response_length": len(ai_message)}})
            
            # Add AI response to history
            ai_msg = ChatMessage(role="assistant", content=ai_message)
            self.conversation_history.append(ai_msg)
            
            # Detect if user wants to create a project
            action = self._detect_action(message, ai_message)
            suggestions = self._generate_suggestions(message, ai_message)
            
            metadata = {"model": self.model, "personality": self.personality.type}
            
            # Handle project creation if action detected and DB is available
            if action == "create_project" and db and settings.enable_project_manager:
                logger.info("Attempting to create project from chat")
                project_result = self.guide_project_creation(message + "\n" + ai_message)
                
                if project_result.get("status") == "ready_for_creation":
                    try:
                        details = project_result["project_details"]
                        project_req = ProjectRequest(
                            title=details.get("title", "New Project"),
                            description=details.get("description", ""),
                            project_type=details.get("project_type", "custom"),
                            duration=details.get("duration"),
                            style=details.get("style")
                        )
                        
                        pm = ProjectManager(db)
                        project = pm.create_project(project_req)
                        
                        metadata["project_created"] = True
                        metadata["project_id"] = project.id
                        metadata["project"] = project.dict()
                        
                        # Append confirmation to AI message
                        ai_message += f"\n\nI've created a new project for you: **{project.title}**. I've also generated a preliminary plan with {len(project.tasks)} tasks."
                        
                    except Exception as e:
                        logger.error(f"Failed to create project from chat: {e}", exc_info=True)
                        metadata["project_creation_error"] = str(e)
            
            if action:
                logger.info(f"Action detected: {action}")
            
            return ChatResponse(
                message=ai_message,
                suggestions=suggestions,
                action=action,
                metadata=metadata
            )
            
        except Exception as e:
            logger.error(f"Chat error: {e}", exc_info=True)
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
        logger.info("Guiding project creation", extra={"context": {"input_length": len(user_input)}})
        
        # Use centralized prompt
        extraction_prompt = self.prompt_manager.get_prompt(
            "admin_ai.project_extraction",
            {"user_input": user_input}
        )

        try:
            logger.debug("Extracting project details from user input")
            response = ollama.chat(
                model=self.model,
                messages=[{"role": "user", "content": extraction_prompt}],
                format="json"
            )
            
            project_details = json.loads(response['message']['content'])
            logger.info("Project details extracted", extra={"context": {"project_type": project_details.get("project_type")}})
            
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
            logger.error(f"Error extracting project details: {e}", exc_info=True)
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
        logger.info(f"Generating suggestions for {project_type}")
        
        # Use centralized prompt
        prompt = self.prompt_manager.get_prompt(
            "admin_ai.suggestion_generation",
            {"project_type": project_type}
        )

        try:
            response = ollama.chat(
                model=self.model,
                messages=[{"role": "user", "content": prompt}],
                options={"temperature": 0.9}
            )
            
            # Parse suggestions from response
            suggestions_text = response['message']['content']
            suggestions = [s.strip() for s in suggestions_text.split('\n') if s.strip() and not s.strip().startswith('#')]
            
            logger.debug(f"Generated {len(suggestions)} suggestions")
            return suggestions[:5]
            
        except Exception as e:
            logger.error(f"Error generating suggestions: {e}", exc_info=True)
            return [f"Error generating suggestions: {str(e)}"]
    
    def clear_history(self) -> None:
        """Clear conversation history."""
        logger.info("Clearing conversation history")
        self.conversation_history = []
        self.context = {}
