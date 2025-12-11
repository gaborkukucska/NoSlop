# START OF FILE backend/workers/research_agent.py
"""
Research Agent Worker.

Specializes in gathering information and context for media projects.
Provides background research, best practices, and relevant information
to support project planning and execution.
"""

import logging
import json
from typing import Dict, Any
from datetime import datetime

from worker_agent import WorkerAgent, WorkerCapabilities, ResourceRequirements
from database import TaskStatusEnum, ProjectCRUD

logger = logging.getLogger(__name__)


class ResearchAgent(WorkerAgent):
    """
    Worker specialized in research and information gathering.
    
    Capabilities:
    - Analyzes project requirements
    - Provides background research
    - Suggests best practices and techniques
    - Gathers relevant context
    - Identifies potential challenges and solutions
    """
    
    _agent_type = "research_agent"
    
    def __init__(self, db_session):
        super().__init__(db_session)
        self.agent_type = "research_agent"
    
    @staticmethod
    def _get_capabilities_static():
        """Get static capabilities metadata."""
        return {
            "agent_type": "research_agent",
            "supported_task_types": ["research"],
            "description": "Gathers information and context for projects",
            "version": "1.0.0"
        }
    
    def get_capabilities(self) -> WorkerCapabilities:
        """Get worker capabilities."""
        return WorkerCapabilities(
            agent_type=self.agent_type,
            supported_task_types=["research"],
            description="Gathers information and context for projects",
            version="1.0.0"
        )
    
    def get_resource_requirements(self) -> ResourceRequirements:
        """Get resource requirements."""
        return ResourceRequirements(
            gpu=False,
            min_ram_gb=2,
            cpu_cores=1,
            disk_space_gb=1,
            network=True  # May need network for external research
        )
    
    def validate_result(self, result: Dict[str, Any]) -> bool:
        """Validate research result."""
        if not super().validate_result(result):
            return False
        
        # Check for required fields
        required_fields = ["findings", "summary"]
        for field in required_fields:
            if field not in result:
                logger.warning(f"Missing required field in result: {field}")
                return False
        
        # Validate findings structure
        findings = result.get("findings", [])
        if not isinstance(findings, list):
            logger.warning("Findings must be a list")
            return False
        
        logger.debug("ResearchAgent result validation passed")
        return True
    
    async def process_task(self, task) -> Dict[str, Any]:
        """
        Conduct research based on task requirements.
        
        Args:
            task: Task containing research objectives
            
        Returns:
            Dictionary containing:
            - summary: Research summary
            - findings: List of research findings
            - best_practices: Recommended best practices
            - challenges: Potential challenges
            - recommendations: Actionable recommendations
            - references: Reference materials
        """
        logger.info(f"ResearchAgent starting task: {task.id}")
        
        try:
            # Update status to IN_PROGRESS
            self.update_status(task.id, TaskStatusEnum.IN_PROGRESS)
            self.report_progress(task.id, 10, "Analyzing research objectives")
            
            # Get project context
            project = ProjectCRUD.get(self.db, task.project_id)
            if not project:
                raise ValueError(f"Project {task.project_id} not found")
            
            # Prepare research prompt
            self.report_progress(task.id, 30, "Gathering research context")
            
            research_focus = task.meta_data.get("research_focus", "general") if task.meta_data else "general"
            
            prompt = self.prompt_manager.get_worker_prompt(
                "research_agent",
                project_title=project.title,
                project_description=project.description,
                project_type=project.project_type.value,
                project_style=project.style or "professional",
                task_description=task.description,
                research_focus=research_focus
            )
            
            # Call LLM for research
            self.report_progress(task.id, 50, "Conducting research")
            
            logger.debug("Calling LLM for research")
            response_content = await self.call_llm(
                messages=[{"role": "user", "content": prompt}],
                temperature=0.7,
                format="json"
            )
            
            # Parse response
            self.report_progress(task.id, 75, "Processing research results")
            research_data = json.loads(response_content)
            
            # Structure the result
            result = {
                "summary": research_data.get("summary", ""),
                "findings": research_data.get("findings", []),
                "best_practices": research_data.get("best_practices", []),
                "challenges": research_data.get("challenges", []),
                "solutions": research_data.get("solutions", []),
                "recommendations": research_data.get("recommendations", []),
                "references": research_data.get("references", []),
                "keywords": research_data.get("keywords", []),
                "related_topics": research_data.get("related_topics", []),
                "format": "research_v1",
                "generated_at": datetime.utcnow().isoformat()
            }
            
            # Validate result
            if not self.validate_result(result):
                raise ValueError("Generated research failed validation")
            
            # Update status to COMPLETED
            self.report_progress(task.id, 100, "Research complete")
            self.update_status(task.id, TaskStatusEnum.COMPLETED, result)
            
            logger.info(f"ResearchAgent completed task: {task.id}")
            logger.debug(f"Generated {len(result['findings'])} findings")
            
            return result
            
        except Exception as e:
            logger.error(f"ResearchAgent failed task {task.id}: {e}", exc_info=True)
            self.update_status(task.id, TaskStatusEnum.FAILED, {"error": str(e)})
            raise
