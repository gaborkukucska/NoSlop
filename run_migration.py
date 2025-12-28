#!/usr/bin/env python3
"""Simple migration runner - apply database schema"""
import sys
sys.path.insert(0, '/home/tom/NoSlop')

from backend.database import Base, engine
from sqlalchemy import inspect

print("="*70)
print("Database Migration: Iterative Workflow Support")
print("="*70)

print("\nApplying schema changes...")
try:
    Base.metadata.create_all(bind=engine)
    print("✓ Schema updated")
    
    # Verify
    inspector = inspect(engine)
    tables = inspector.get_table_names()
    
    print("\nVerifying new tables:")
    for table in ['task_iterations', 'user_feedback', 'project_milestones']:
        if table in tables:
            print(f"  ✓ {table}")
        else:
            print(f"  ✗ {table} - MISSING!")
    
    print("\n" + "="*70)
    print("Migration complete!")
    print("Restart backend: sudo systemctl restart noslop-backend")
    print("="*70)
except Exception as e:
    print(f"\n✗ Migration failed: {e}")
    import traceback
    traceback.print_exc()
    sys.exit(1)
