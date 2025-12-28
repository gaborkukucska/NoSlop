# Append to end of database.py after SystemSettingsCRUD

# ============================================================================
# CRUD Operations for Iterative Workflow Models
# ============================================================================

class TaskIterationCRUD:
    """CRUD operations for task iterations"""
    
    @staticmethod
    def create(db: Session, iteration_data: Dict[str, Any]) -> TaskIterationModel:
        """Create a new task iteration"""
        logger.info(f"Creating iteration {iteration_data.get('iteration_number')} for task {iteration_data.get('task_id')}")
        
        iteration = TaskIterationModel(**iteration_data)
        db.add(iteration)
        db.commit()
        db.refresh(iteration)
        
        return iteration
    
    @staticmethod
    def get(db: Session, iteration_id: str) -> Optional[TaskIterationModel]:
        """Get iteration by ID"""
        return db.query(TaskIterationModel).filter(TaskIterationModel.id == iteration_id).first()
    
    @staticmethod
    def get_by_task(db: Session, task_id: str) -> List[TaskIterationModel]:
        """Get all iterations for a task"""
        return db.query(TaskIterationModel).filter(
            TaskIterationModel.task_id == task_id
        ).order_by(TaskIterationModel.iteration_number.asc()).all()
    
    @staticmethod
    def get_latest(db: Session, task_id: str) -> Optional[TaskIterationModel]:
        """Get latest iteration for a task"""
        return db.query(TaskIterationModel).filter(
            TaskIterationModel.task_id == task_id
        ).order_by(TaskIterationModel.iteration_number.desc()).first()
    
    @staticmethod
    def update(db: Session, iteration_id: str, updates: Dict[str, Any]) -> Optional[TaskIterationModel]:
        """Update iteration"""
        iteration = TaskIterationCRUD.get(db, iteration_id)
        if not iteration:
            return None
        
        for key, value in updates.items():
            if hasattr(iteration, key):
                setattr(iteration, key, value)
        
        db.commit()
        db.refresh(iteration)
        
        return iteration


class UserFeedbackCRUD:
    """CRUD operations for user feedback"""
    
    @staticmethod
    def create(db: Session, feedback_data: Dict[str, Any]) -> UserFeedbackModel:
        """Create user feedback"""
        logger.info(f"Creating feedback for project {feedback_data.get('project_id')}")
        
        feedback = UserFeedbackModel(**feedback_data)
        db.add(feedback)
        db.commit()
        db.refresh(feedback)
        
        return feedback
    
    @staticmethod
    def get(db: Session, feedback_id: str) -> Optional[UserFeedbackModel]:
        """Get feedback by ID"""
        return db.query(UserFeedbackModel).filter(UserFeedbackModel.id == feedback_id).first()
    
    @staticmethod
    def get_by_project(db: Session, project_id: str) -> List[UserFeedbackModel]:
        """Get all feedback for a project"""
        return db.query(UserFeedbackModel).filter(
            UserFeedbackModel.project_id == project_id
        ).order_by(UserFeedbackModel.created_at.desc()).all()
    
    @staticmethod
    def get_by_task(db: Session, task_id: str) -> List[UserFeedbackModel]:
        """Get all feedback for a task"""
        return db.query(UserFeedbackModel).filter(
            UserFeedbackModel.task_id == task_id
        ).order_by(UserFeedbackModel.created_at.desc()).all()
    
    @staticmethod  
    def get_by_milestone(db: Session, project_id: str, milestone: str) -> List[UserFeedbackModel]:
        """Get feedback for a specific milestone"""
        return db.query(UserFeedbackModel).filter(
            UserFeedbackModel.project_id == project_id,
            UserFeedbackModel.milestone == milestone
        ).order_by(UserFeedbackModel.created_at.desc()).all()


class ProjectMilestoneCRUD:
    """CRUD operations for project milestones"""
    
    @staticmethod
    def create(db: Session, milestone_data: Dict[str, Any]) -> ProjectMilestoneModel:
        """Create a project milestone"""
        logger.info(f"Creating milestone '{milestone_data.get('name')}' for project {milestone_data.get('project_id')}")
        
        milestone = ProjectMilestoneModel(**milestone_data)
        db.add(milestone)
        db.commit()
        db.refresh(milestone)
        
        return milestone
    
    @staticmethod
    def get(db: Session, milestone_id: str) -> Optional[ProjectMilestoneModel]:
        """Get milestone by ID"""
        return db.query(ProjectMilestoneModel).filter(ProjectMilestoneModel.id == milestone_id).first()
    
    @staticmethod
    def get_by_project(db: Session, project_id: str) -> List[ProjectMilestoneModel]:
        """Get all milestones for a project"""
        return db.query(ProjectMilestoneModel).filter(
            ProjectMilestoneModel.project_id == project_id
        ).order_by(ProjectMilestoneModel.created_at.asc()).all()
    
    @staticmethod
    def get_pending(db: Session, project_id: str) -> List[ProjectMilestoneModel]:
        """Get pending milestones for a project"""
        return db.query(ProjectMilestoneModel).filter(
            ProjectMilestoneModel.project_id == project_id,
            ProjectMilestoneModel.status.in_(["pending", "awaiting_review"])
        ).all()
    
    @staticmethod
    def update(db: Session, milestone_id: str, updates: Dict[str, Any]) -> Optional[ProjectMilestoneModel]:
        """Update milestone"""
        milestone = ProjectMilestoneCRUD.get(db, milestone_id)
        if not milestone:
            return None
        
        for key, value in updates.items():
            if hasattr(milestone, key):
                setattr(milestone, key, value)
        
        db.commit()
        db.refresh(milestone)
        
        logger.info(f"Milestone updated: {milestone_id}")
        return milestone
