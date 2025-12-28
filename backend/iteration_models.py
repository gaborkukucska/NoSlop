# NEW MODELS FOR ITERATIVE WORKFLOW (To be inserted after AgentMessageModel)

class TaskIterationModel(Base):
    """Database model for task iterations - supports multiple drafts/revisions"""
    __tablename__ = "task_iterations"
    
    id = Column(String, primary_key=True)
    task_id = Column(String, ForeignKey("tasks.id"), nullable=False, index=True)
    iteration_number = Column(Integer, nullable=False)  # 1, 2, 3, etc.
    status = Column(SQLEnum(IterationStatusEnum), default=IterationStatusEnum.DRAFT)
    
    # Result data for this iteration
    result = Column(JSON, nullable=True)
    
    # User feedback for this iteration (if rejected)
    user_feedback = Column(Text, nullable=True)
    
    # Timestamps
    created_at = Column(DateTime, default=datetime.utcnow)
    submitted_at = Column(DateTime, nullable=True)  # When submitted for review
    reviewed_at = Column(DateTime, nullable=True)  # When user reviewed
    
    # Metadata
    meta_data = Column(JSON, default=dict)
    
    # Relationships
    task = relationship("TaskModel", back_populates="iterations")
    
    def to_dict(self) -> Dict[str, Any]:
        return {
            "id": self.id,
            "task_id": self.task_id,
            "iteration_number": self.iteration_number,
            "status": self.status.value,
            "result": self.result,
            "user_feedback": self.user_feedback,
            "created_at": self.created_at.isoformat() if self.created_at else None,
            "submitted_at": self.submitted_at.isoformat() if self.submitted_at else None,
            "reviewed_at": self.reviewed_at.isoformat() if self.reviewed_at else None,
            "metadata": self.meta_data or {}
        }


class UserFeedbackModel(Base):
    """Database model for user feedback on projects/tasks"""
    __tablename__ = "user_feedback"
    
    id = Column(String, primary_key=True)
    user_id = Column(String, ForeignKey("users.id"), nullable=False, index=True)
    project_id = Column(String, ForeignKey("projects.id"), nullable=False, index=True)
    task_id = Column(String, ForeignKey("tasks.id"), nullable=True, index=True)  # Optional - can be project-level
    milestone = Column(String, nullable=True)  # Which milestone this feedbackis for
    
    feedback_text = Column(Text, nullable=False)
    
    # Classification
    feedback_type = Column(String, default="general")  # general, approval, rejection, clarification
    sentiment = Column(String, nullable=True)  # positive, negative, neutral  
    
    # Timestamps
    created_at = Column(DateTime, default=datetime.utcnow)
    
    # Metadata
    meta_data = Column(JSON, default=dict)
    
    # Relationships
    user = relationship("UserModel")
    project = relationship("ProjectModel", back_populates="feedback")
    task = relationship("TaskModel", back_populates="feedback")
    
    def to_dict(self) -> Dict[str, Any]:
        return {
            "id": self.id,
            "user_id": self.user_id,
            "project_id": self.project_id,
            "task_id": self.task_id,
            "milestone": self.milestone,
            "feedback_text": self.feedback_text,
            "feedback_type": self.feedback_type,
            "sentiment": self.sentiment,
            "created_at": self.created_at.isoformat() if self.created_at else None,
            "metadata": self.meta_data or {}
        }


class ProjectMilestoneModel(Base):
    """Database model for project milestones/checkpoints"""
    __tablename__ = "project_milestones"
    
    id = Column(String, primary_key=True)
    project_id = Column(String, ForeignKey("projects.id"), nullable=False, index=True)
    milestone_type = Column(SQLEnum(MilestoneTypeEnum), nullable=False)
    name = Column(String, nullable=False)
    description = Column(Text, nullable=True)
    
    # Status
    status = Column(String, default="pending")  # pending, awaiting_review, approved, rejected
    
    # Associated tasks
    task_ids = Column(JSON, default=list)  # Tasks that contribute to this milestone
    
    # Timestamps
    created_at = Column(DateTime, default=datetime.utcnow)
    completed_at = Column(DateTime, nullable=True)
    reviewed_at = Column(DateTime, nullable=True)
    
    # User response
    user_approved = Column(Boolean, default=False)
    user_feedback = Column(Text, nullable=True)
    
    # Metadata
    meta_data = Column(JSON, default=dict)
    
    # Relationships
    project = relationship("ProjectModel", back_populates="milestones")
    
    def to_dict(self) -> Dict[str, Any]:
        return {
            "id": self.id,
            "project_id": self.project_id,
            "milestone_type": self.milestone_type.value,
            "name": self.name,
            "description": self.description,
            "status": self.status,
            "task_ids": self.task_ids or [],
            "created_at": self.created_at.isoformat() if self.created_at else None,
            "completed_at": self.completed_at.isoformat() if self.completed_at else None,
            "reviewed_at": self.reviewed_at.isoformat() if self.reviewed_at else None,
            "user_approved": self.user_approved,
            "user_feedback": self.user_feedback,
            "metadata": self.meta_data or {}
        }
