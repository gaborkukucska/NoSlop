# START OF FILE backend/admin_ai.py
import ollama
from typing import List, Dict, Any, Optional
from datetime import datetime
import json
import logging
import asyncio
from functools import partial

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
from workflow_generator import WorkflowGenerator

logger = logging.getLogger(__name__)


from database import ChatMessageModel

class AdminAI:
    """
    Admin AI - The primary interface for user interaction.
    Guides users through media creation workflows with a customizable personality.
    """
    
    def __init__(self, personality: Optional[PersonalityProfile] = None, connection_manager: Any = None):
        """
        Initialize Admin AI with a personality profile.
        
        Args:
            personality: Personality configuration. Defaults to balanced.
            connection_manager: WebSocket manager for broadcasting logs.
        """
        self.personality = personality or PersonalityProfile(type=PersonalityType.BALANCED)
        # self.conversation_history is now managed via database
        self.model = settings.ollama_default_model
        self.context: Dict[str, Any] = {}
        self.prompt_manager = get_prompt_manager()
        self.connection_manager = connection_manager
        self.workflow_generator = WorkflowGenerator()
        
        logger.info("Admin AI initialized", extra={"context": {"personality": self.personality.type, "model": self.model}})

    async def broadcast_activity(self, activity_type: str, message: str, data: Optional[Dict] = None):
        """
        Broadcast admin activity to frontend.
        """
        if self.connection_manager:
            payload = {
                "type": "agent_activity",
                "data": {
                    "project_id": "admin_chat",  # logical ID for grouping in terminal
                    "agent_type": "Admin AI",
                    "activity_type": activity_type,
                    "message": message,
                    "timestamp": datetime.utcnow().isoformat(),
                    "details": data or {}
                }
            }
            try:
                await self.connection_manager.broadcast(payload)
            except Exception as e:
                logger.warning(f"Failed to broadcast activity: {e}")
        
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
        # Pass .value to ensure we get the string "balanced" not "PersonalityType.BALANCED"
        # even if str(Enum) behavior varies.
        p_type = self.personality.type
        if hasattr(p_type, "value"):
            p_type = p_type.value
            
        system_prompt = self.prompt_manager.get_admin_ai_system_prompt(
            personality_type=p_type,
            formality=self.personality.formality,
            enthusiasm=self.personality.enthusiasm
        )
        
        logger.debug("Built system prompt", extra={"context": {"personality": self.personality.type}})
        return system_prompt
    
    def _format_conversation_history(self, db: Session, session_id: str = "default", max_messages: int = 10) -> List[Dict[str, str]]:
        """
        Format conversation history for Ollama API.
        
        Args:
            db: Database session
            session_id: Session ID
            max_messages: Maximum number of recent messages to include
            
        Returns:
            List of message dictionaries
        """
        if not db:
            return []
            
        # Fetch recent messages from DB
        recent_messages = db.query(ChatMessageModel).filter(
            ChatMessageModel.session_id == session_id
        ).order_by(ChatMessageModel.timestamp.desc()).limit(max_messages).all()
        
        # Reverse to chronological order
        recent_messages.reverse()
        
        return [
            {"role": msg.role, "content": msg.content}
            for msg in recent_messages
        ]
    
    def get_history(self, db: Session, session_id: str = "default", limit: int = 50) -> List[Dict[str, Any]]:
        """
        Get conversation history for frontend.
        
        Args:
            db: Database session
            session_id: Session ID
            limit: Max messages
            
        Returns:
            List of message dictionaries
        """
        if not db:
            return []
            
        messages = db.query(ChatMessageModel).filter(
            ChatMessageModel.session_id == session_id
        ).order_by(ChatMessageModel.timestamp.asc()).limit(limit).all()
        
        return [msg.to_dict() for msg in messages]

    async def chat(self, message: str, context: Optional[Dict[str, Any]] = None, db: Optional[Session] = None, session_id: str = "default") -> ChatResponse:
        """
        Main conversation interface with the Admin AI.
        
        Args:
            message: User message
            context: Additional context (current project, user preferences, etc.)
            db: Database session for performing actions
            session_id: Session identifier
            
        Returns:
            ChatResponse with AI message and optional suggestions
        """
        logger.info("Chat request received", extra={"context": {"message_length": len(message)}})
        
        if not db:
            raise ValueError("Database session is required for chat")

        # Update context
        if context:
            self.context.update(context)
            logger.debug("Context updated", extra={"context": {"keys": list(context.keys())}})
        
        # Save user message to DB
        user_msg_db = ChatMessageModel(
            session_id=session_id,
            role="user",
            content=message,
            timestamp=datetime.utcnow()
        )
        db.add(user_msg_db)
        db.commit()
        
        # Build messages for Ollama
        # Context Optimization: Limit history size
        messages = [
            {"role": "system", "content": self._build_system_prompt()}
        ]
        # Only fetch last 5 messages for context
        messages.extend(self._format_conversation_history(db, session_id, max_messages=5)) 

        # Detect action PRE-chat for status injection
        # Some actions like "status" need data injected BEFORE the LLM generates a response
        pre_chat_action = self._detect_action(message, "")
        
        if pre_chat_action == "system_status":
             status_report = self.get_concise_status_report(db)
             # Inject status into system prompt or latest user message
             messages[-1]["content"] += f"\n\n[SYSTEM DATA]\n{status_report}\nUser is asking for status. Summarize this data briefly."
             
        if pre_chat_action == "clear_context":
             self.clear_history(db, session_id)
             return ChatResponse(
                 message="I've cleared our conversation context. What would you like to discuss next?",
                 suggestions=["New Project", "System Status"],
                 metadata={"cleared": True}
             )

        # Add generic context if available
        if self.context:
            context_str = f"\n\nCurrent context: {json.dumps(self.context, indent=2)}"
            messages[-1]["content"] += context_str
        
        try:
            # Call Ollama
            logger.debug("Calling Ollama", extra={"context": {"model": self.model, "message_count": len(messages)}})
            
            await self.broadcast_activity(
                "thinking", 
                "Admin AI is thinking...", 
                {"context_length": len(messages)}
            )

            loop = asyncio.get_event_loop()
            func = partial(
                ollama.chat,
                model=self.model,
                messages=messages,
                options={
                    "temperature": self.personality.creativity,
                    "num_predict": int(settings.ollama_max_predict * self.personality.verbosity + 500)
                }
            )
            response = await loop.run_in_executor(None, func)
            
            ai_message = response['message']['content']
            logger.info("Ollama response received", extra={"context": {"response_length": len(ai_message)}})
            
            await self.broadcast_activity(
                "response", 
                "Response received from Admin AI", 
                {"response_length": len(ai_message)}
            )
            
            # Save AI response to DB
            ai_msg_db = ChatMessageModel(
                session_id=session_id,
                role="assistant",
                content=ai_message,
                timestamp=datetime.utcnow()
            )
            db.add(ai_msg_db)
            db.commit()
            
            # Detect post-chat actions (like creation)
            action = self._detect_action(message, ai_message)
            suggestions = self._generate_suggestions(message, ai_message, db, session_id)
            
            metadata = {"model": self.model, "personality": self.personality.type}
            
            # Handle project creation if action detected and DB is available
            if action == "create_project" and db and settings.enable_project_manager:
                logger.info("Attempting to create project from chat")
                
                await self.broadcast_activity("action", "Initiating project creation workflow...")
                
                # Convert guide_project_creation to async execution wrapper
                project_result = await loop.run_in_executor(
                    None, 
                    self.guide_project_creation, 
                    message + "\n" + ai_message
                )
                
                if project_result.get("status") == "ready_for_creation":
                    try:
                        details = project_result["project_details"]
                        project_req = ProjectRequest(
                            title=details.get("title", "New Project"),
                            description=details.get("description", ""),
                            project_type=details.get("project_type", "custom"),
                            duration=details.get("duration"),
                            style=details.get("style"),
                            workflow_path=self.context.get("last_workflow_path")
                        )
                        
                        pm = ProjectManager(db, self.connection_manager)
                        project = await pm.create_project(project_req) # Now async!
                        
                        metadata["project_created"] = True
                        metadata["project_id"] = project.id
                        metadata["project"] = project.dict()
                        
                        # Append confirmation to AI message
                        ai_message += f"\n\nI've created a new project for you: **{project.title}**. I've also generated a preliminary plan with {len(project.tasks)} tasks."
                        
                        # Update AI message in DB with confirmation
                        ai_msg_db.content = ai_message
                        db.commit()
                        
                    except Exception as e:
                        logger.error(f"Failed to create project from chat: {e}", exc_info=True)
                        metadata["project_creation_error"] = str(e)
            
            # Handle workflow generation
            if action == "generate_workflow":
                logger.info("Attempting to generate workflow from chat")
                await self.broadcast_activity("action", "Generating ComfyUI workflow...")
                
                try:
                    # Run generation in executor
                    workflow_result = await loop.run_in_executor(
                        None,
                        self.workflow_generator.generate_workflow,
                        message + "\n" + ai_message
                    )
                    
                    if workflow_result.get("success"):
                        # Extract a name from the request or use default
                        # Simple heuristic: use first few words of message or "generated_workflow"
                        # For now, let's use a generic name plus timestamp handled by save_workflow
                        name_hint = "generated_workflow"
                        if len(message) < 50:
                             name_hint = message.strip()
                        
                        save_result = self.workflow_generator.save_workflow(
                            workflow_result["workflow"], 
                            name_hint
                        )
                        
                        metadata["workflow_generated"] = True
                        metadata["workflow_data"] = workflow_result["workflow"]
                        
                        ai_message += f"\n\nI've generated a new ComfyUI workflow for you based on your request."
                        
                        if save_result.get("success"):
                            metadata["workflow_path"] = save_result["path"]
                            self.context["last_workflow_path"] = save_result["path"]
                            ai_message += f"\n\n It has been saved to `{save_result['filename']}` and is ready for use by the workers."
                        else:
                            ai_message += f"\n\n However, I couldn't save it to disk: {save_result.get('error')}"
                        
                        # Update AI message
                        ai_msg_db.content = ai_message
                        db.commit()
                    else:
                        ai_message += f"\n\nI attempted to generate a workflow but ran into an issue: {workflow_result.get('error')}"
                        ai_msg_db.content = ai_message
                        db.commit()
                        
                except Exception as e:
                    logger.error(f"Failed to generate workflow: {e}", exc_info=True)
                    metadata["workflow_error"] = str(e)
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
        """
        # Simple keyword detection
        user_lower = user_message.lower()
        
        # System status
        if any(w in user_lower for w in ["status", "report", "how are things", "system check"]):
            return "system_status"

        # Worker status
        if any(w in user_lower for w in ["workers", "agents", "capabilities", "online"]):
            return "worker_status"

        # Context clearing
        if any(w in user_lower for w in ["forget", "reset", "clear history", "new topic"]):
            return "clear_context"

        create_keywords = ["create", "make", "start", "new project", "begin"]
        
        if any(keyword in user_lower for keyword in create_keywords):
            if any(pt.value in user_lower for pt in ProjectType):
                return "create_project"
            if "workflow" in user_lower:
                return "generate_workflow"
        
        return None
    
    def _generate_suggestions(self, user_message: str, ai_response: str, db: Session, session_id: str) -> List[str]:
        """
        Generate follow-up suggestions based on conversation.
        """
        suggestions = []
        user_lower = user_message.lower()
        
        # Context-aware suggestions
        if "project" in user_lower or "create" in user_lower:
            suggestions.extend([
                "Tell me more about the style",
                "Do you have reference media?",
                "What's the target duration?"
            ])
        elif "status" in user_lower:
            suggestions.extend([
                "List available workers",
                "Create a new project",
                "Show detailed project view"
            ])
        else:
            # Check history length
            history_count = db.query(ChatMessageModel).filter(ChatMessageModel.session_id == session_id).count()
            if history_count < 3:
                suggestions.extend([
                    "System Status Report",
                    "I want to create a video",
                    "Help me brainstorm ideas"
                ])
        
        return suggestions[:3]  # Limit to 3 suggestions

    def get_concise_status_report(self, db: Session) -> str:
        """
        Get a concise status report for context injection.
        """
        try:
            if settings.enable_project_manager:
                pm = ProjectManager(db, self.connection_manager)
                summary = pm.get_projects_summary()
                
                return (
                    f"System Status: "
                    f"{summary['total_count']} Total Projects "
                    f"({summary['status_counts']['active']} Active, {summary['status_counts']['completed']} Completed). "
                    f"Active: {summary['active_projects_summary']}"
                )
            return "Project Manager disabled."
        except Exception as e:
            logger.error(f"Error generating status report: {e}")
            return "Status unavailable."
    
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
    
    def clear_history(self, db: Session, session_id: str = "default") -> None:
        """Clear conversation history."""
        logger.info(f"Clearing conversation history for session {session_id}")
        if db:
            db.query(ChatMessageModel).filter(ChatMessageModel.session_id == session_id).delete()
            db.commit()
        self.context = {}

    async def prime_session(self, user: Any, active_projects: List[Dict[str, Any]], db: Session, session_id: str = "default") -> ChatResponse:
        """
        Proactively prime the session with a greeting/status report.
        
        Args:
            user: User object
            active_projects: List of active projects
            db: Database session
            session_id: Session ID
            
        Returns:
            ChatResponse with AI message
        """
        logger.info(f"Priming session {session_id} for user {user.username}")
        
        # Build priming prompt
        prompt = self.prompt_manager.get_prompt(
            "admin_ai.prime_session",
            {
                "username": user.username,
                "time_of_day": datetime.now().strftime("%I:%M %p"),
                "active_projects": json.dumps([p.get("title") for p in active_projects]),
                "personality": self.personality.type
            }
        )
        
        # If prompt template doesn't exist (fallback), build it manually
        if "{" in prompt: # Indicates template keys weren't replaced or prompt missing
             prompt = (
                f"You are the Admin AI, a {self.personality.type} assistant. "
                "The user has just started a new session. "
                "Give a very short, crisp welcome. State that you are online and ready to create media. "
                "Do not apologize for anything. Do not mention hiccups. Just be helpful and ready."
             )

        try:
            await self.broadcast_activity(
                "thinking", 
                "Priming neural link...", 
                {"context": "startup"}
            )
            
            loop = asyncio.get_event_loop()
            func = partial(
                ollama.chat,
                model=self.model,
                messages=[{"role": "system", "content": self._build_system_prompt()}, {"role": "user", "content": prompt}],
                options={
                    "temperature": self.personality.creativity,
                    "num_predict": 500 # Ensure greeting isn't truncated
                }
            )
            response = await loop.run_in_executor(None, func)
            ai_message = response['message']['content']
            
            await self.broadcast_activity(
                "response", 
                "Connection established", 
                {"response_length": len(ai_message)}
            )
            
            # Save prime message to DB
            ai_msg_db = ChatMessageModel(
                session_id=session_id,
                role="assistant",
                content=ai_message,
                timestamp=datetime.utcnow()
            )
            db.add(ai_msg_db)
            db.commit()
            
            return ChatResponse(
                message=ai_message,
                suggestions=["Let's work on my project", "Create a new project", "What's the system status?"],
                metadata={"primed": True}
            )
            
        except Exception as e:
            logger.error(f"Priming error: {e}", exc_info=True)
            return ChatResponse(
                message=f"Welcome back, {user.username}. I'm ready to help.",
                suggestions=[],
                metadata={"error": str(e)}
            )
