#!/usr/bin/env python3
"""
Database Migration: Add Iterative Workflow Support

This script adds new tables and columns to support the iterative workflow system:
- New tables: task_iterations, user_feedback, project_milestones
- New columns in projects: review_required, current_milestone, user_feedback_summary, iteration_count
- New columns in tasks: iteration_count, current_iteration_status, user_approved, feedback_requested

Run this script after updating database.py to apply the schema changes.
"""

import sys
import os

# Add backend directory to path
backend_dir = os.path.dirname(os.path.abspath(__file__))
parent_dir = os.path.dirname(backend_dir)
sys.path.insert(0, parent_dir)

from backend.database import Base, engine, SessionLocal
from sqlalchemy import inspect, text
import logging

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)


def check_column_exists(table_name: str, column_name: str) -> bool:
    """Check if a column exists in a table"""
    inspector = inspect(engine)
    columns = [col['name'] for col in inspector.get_columns(table_name)]
    return column_name in columns


def check_table_exists(table_name: str) -> bool:
    """Check if a table exists"""
    inspector = inspect(engine)
    return table_name in inspector.get_table_names()


def migrate():
    """Run database migration"""
    logger.info("Starting database migration for iterative workflow...")
    
    db = SessionLocal()
    
    try:
        # Check if new tables already exist
        new_tables = ['task_iterations', 'user_feedback', 'project_milestones']
        existing_new_tables = [t for t in new_tables if check_table_exists(t)]
        
        if existing_new_tables:
            logger.info(f"New tables already exist: {existing_new_tables}")
            logger.info("Skipping new table creation for existing tables...")
        
        # Create all tables (will skip existing ones)
        logger.info("Creating new tables if they don't exist...")
        Base.metadata.create_all(bind=engine)
        logger.info("✓ New tables created successfully")
        
        # Add new columns to existing tables if they don't exist
        project_columns = [
            ("review_required", "BOOLEAN DEFAULT 0"),
            ("current_milestone", "VARCHAR"),
            ("user_feedback_summary", "TEXT"),
            ("iteration_count", "INTEGER DEFAULT 0")
        ]
        
        task_columns = [
            ("iteration_count", "INTEGER DEFAULT 0"),
            ("current_iteration_status", "VARCHAR DEFAULT 'draft'"),
            ("user_approved", "BOOLEAN DEFAULT 0"),
            ("feedback_requested", "BOOLEAN DEFAULT 0")
        ]
        
        # Add columns to projects table
        logger.info("Checking/adding new columns to projects table...")
        for col_name, col_def in project_columns:
            if not check_column_exists("projects", col_name):
                logger.info(f"  Adding column: {col_name}")
                db.execute(text(f"ALTER TABLE projects ADD COLUMN {col_name} {col_def}"))
                db.commit()
            else:
                logger.info(f"  Column already exists: {col_name}")
        
        # Add columns to tasks table
        logger.info("Checking/adding new columns to tasks table...")
        for col_name, col_def in task_columns:
            if not check_column_exists("tasks", col_name):
                logger.info(f"  Adding column: {col_name}")
                db.execute(text(f"ALTER TABLE tasks ADD COLUMN {col_name} {col_def}"))
                db.commit()
            else:
                logger.info(f"  Column already exists: {col_name}")
        
        logger.info("✓ Migration completed successfully!")
        logger.info("\nNew database features:")
        logger.info("  • Task iterations - Multi-draft support")
        logger.info("  • User feedback - Capture user input at milestones")
        logger.info("  • Project milestones - Track checkpoint reviews")
        logger.info("  • Enhanced project/task models - Iteration tracking")
        
    except Exception as e:
        logger.error(f"Migration failed: {e}", exc_info=True)
        db.rollback()
        raise
    finally:
        db.close()


if __name__ == "__main__":
    logger.info("="*70)
    logger.info("Database Migration: Iterative Workflow Support")
    logger.info("="*70)
    
    migrate()
    
    logger.info("\n" + "="*70)
    logger.info("Migration complete! Backend restart required.")
    logger.info("Run: sudo systemctl restart noslop-backend")
    logger.info("="*70)
