# START OF FILE backend/prompt_manager.py
"""
Centralized prompt management system for NoSlop.

Loads prompts from YAML, supports template variables, and provides
caching and hot-reload functionality.
"""

import yaml
import logging
from pathlib import Path
from typing import Dict, Any, Optional
from string import Template
import os
import time

logger = logging.getLogger(__name__)


class PromptManager:
    """
    Manages prompts loaded from YAML configuration.
    Supports template variables and hot-reload in development mode.
    """
    
    def __init__(self, prompts_file: str = "backend/prompts.yaml", enable_hot_reload: bool = True):
        """
        Initialize the prompt manager.
        
        Args:
            prompts_file: Path to prompts YAML file
            enable_hot_reload: Enable hot-reload in development mode
        """
        self.prompts_file = Path(prompts_file)
        self.enable_hot_reload = enable_hot_reload
        self._prompts: Dict[str, Any] = {}
        self._last_modified: float = 0
        
        # Load prompts on initialization
        self.load_prompts()
        
        logger.info(f"PromptManager initialized with {len(self._prompts)} prompt categories")
    
    def load_prompts(self) -> None:
        """Load prompts from YAML file."""
        try:
            if not self.prompts_file.exists():
                logger.error(f"Prompts file not found: {self.prompts_file}")
                self._prompts = {}
                return
            
            with open(self.prompts_file, 'r', encoding='utf-8') as f:
                self._prompts = yaml.safe_load(f) or {}
            
            self._last_modified = os.path.getmtime(self.prompts_file)
            logger.debug(f"Loaded prompts from {self.prompts_file}")
            
        except Exception as e:
            logger.error(f"Error loading prompts: {e}", exc_info=True)
            self._prompts = {}
    
    def _check_reload(self) -> None:
        """Check if prompts file has been modified and reload if necessary."""
        if not self.enable_hot_reload:
            return
        
        try:
            current_mtime = os.path.getmtime(self.prompts_file)
            if current_mtime > self._last_modified:
                logger.info("Prompts file modified, reloading...")
                self.load_prompts()
        except Exception as e:
            logger.warning(f"Error checking for prompt file changes: {e}")
    
    def get_prompt(self, path: str, variables: Optional[Dict[str, Any]] = None) -> str:
        """
        Get a prompt by path and optionally fill in template variables.
        
        Args:
            path: Dot-separated path to prompt (e.g., "admin_ai.base_system_prompt")
            variables: Dictionary of variables to substitute in template
            
        Returns:
            Formatted prompt string
            
        Example:
            >>> pm = PromptManager()
            >>> pm.get_prompt("admin_ai.base_system_prompt")
            >>> pm.get_prompt("project_manager.task_breakdown", {"task_title": "Create intro"})
        """
        self._check_reload()
        
        # Navigate through nested dictionary
        parts = path.split('.')
        current = self._prompts
        
        try:
            for part in parts:
                current = current[part]
            
            prompt = str(current)
            
            # Apply template variables if provided
            if variables:
                try:
                    # Use safe_substitute to avoid KeyError for missing variables
                    template = Template(prompt)
                    prompt = template.safe_substitute(variables)
                except Exception as e:
                    logger.warning(f"Error substituting variables in prompt: {e}")
            
            return prompt.strip()
            
        except KeyError:
            logger.error(f"Prompt not found: {path}")
            return f"[Prompt not found: {path}]"
        except Exception as e:
            logger.error(f"Error getting prompt {path}: {e}", exc_info=True)
            return f"[Error loading prompt: {path}]"
    
    def get_admin_ai_system_prompt(
        self,
        personality_type: str = "balanced",
        formality: float = 0.5,
        enthusiasm: float = 0.7
    ) -> str:
        """
        Build complete Admin AI system prompt with personality modifiers.
        
        Args:
            personality_type: Personality type (creative, technical, balanced)
            formality: Formality level (0-1)
            enthusiasm: Enthusiasm level (0-1)
            
        Returns:
            Complete system prompt
        """
        base = self.get_prompt("admin_ai.base_system_prompt")
        
        # Add personality modifier
        personality_modifier = self.get_prompt(f"admin_ai.personality_modifiers.{personality_type}")
        
        # Add formality modifier
        formality_type = "formal" if formality > 0.7 else "casual" if formality < 0.4 else ""
        formality_modifier = ""
        if formality_type:
            formality_modifier = self.get_prompt(f"admin_ai.formality_modifiers.{formality_type}")
        
        # Add enthusiasm modifier
        enthusiasm_type = "high" if enthusiasm > 0.7 else "low" if enthusiasm < 0.4 else ""
        enthusiasm_modifier = ""
        if enthusiasm_type:
            enthusiasm_modifier = self.get_prompt(f"admin_ai.enthusiasm_modifiers.{enthusiasm_type}")
        
        # Combine all parts
        parts = [base]
        if personality_modifier:
            parts.append(personality_modifier)
        if formality_modifier:
            parts.append(formality_modifier)
        if enthusiasm_modifier:
            parts.append(enthusiasm_modifier)
        
        return "\n\n".join(parts)
    
    def get_project_manager_prompt(
        self,
        prompt_type: str,
        **variables
    ) -> str:
        """
        Get a Project Manager prompt with variables.
        
        Args:
            prompt_type: Type of PM prompt (project_planning, task_breakdown, etc.)
            **variables: Template variables
            
        Returns:
            Formatted prompt
        """
        return self.get_prompt(f"project_manager.{prompt_type}", variables)
    
    def get_worker_prompt(
        self,
        worker_type: str,
        prompt_type: str = "task_prompt",
        **variables
    ) -> str:
        """
        Get a Worker agent prompt with variables.
        
        Args:
            worker_type: Type of worker (script_writer, prompt_engineer, etc.)
            prompt_type: Type of prompt (system_prompt, task_prompt)
            **variables: Template variables
            
        Returns:
            Formatted prompt
        """
        return self.get_prompt(f"worker_agents.{worker_type}.{prompt_type}", variables)
    
    def list_prompts(self, category: Optional[str] = None) -> Dict[str, Any]:
        """
        List all available prompts or prompts in a category.
        
        Args:
            category: Optional category to filter by
            
        Returns:
            Dictionary of prompts
        """
        self._check_reload()
        
        if category:
            return self._prompts.get(category, {})
        
        return self._prompts
    
    def reload(self) -> None:
        """Force reload prompts from file."""
        logger.info("Force reloading prompts...")
        self.load_prompts()


# Global prompt manager instance
_prompt_manager: Optional[PromptManager] = None


def get_prompt_manager(
    prompts_file: str = "backend/prompts.yaml",
    enable_hot_reload: bool = True
) -> PromptManager:
    """
    Get the global prompt manager instance (singleton pattern).
    
    Args:
        prompts_file: Path to prompts YAML file
        enable_hot_reload: Enable hot-reload in development mode
        
    Returns:
        PromptManager instance
    """
    global _prompt_manager
    
    if _prompt_manager is None:
        _prompt_manager = PromptManager(prompts_file, enable_hot_reload)
    
    return _prompt_manager
