#!/usr/bin/env python3
"""
Migration script to add user_id column to projects table.
Run this on the database server (master node).
"""

import psycopg2
import sys

# Database connection parameters
DB_NAME = "noslop"
DB_USER = "noslop"
DB_PASSWORD = "noslop"
DB_HOST = "localhost"
DB_PORT = 5432

def run_migration():
    """Add user_id column to projects table"""
    print("Connecting to database...")
    
    try:
        conn = psycopg2.connect(
            dbname=DB_NAME,
            user=DB_USER,
            password=DB_PASSWORD,
            host=DB_HOST,
            port=DB_PORT
        )
        conn.autocommit = False
        cursor = conn.cursor()
        
        print("Adding user_id column to projects table...")
        
        # Check if column already exists
        cursor.execute("""
            SELECT column_name 
            FROM information_schema.columns 
            WHERE table_name='projects' AND column_name='user_id';
        """)
        
        if cursor.fetchone():
            print("✓ Column user_id already exists, skipping migration")
            cursor.close()
            conn.close()
            return
        
        # Add the column (nullable first to allow for potential existing data)
        cursor.execute("""
            ALTER TABLE projects 
            ADD COLUMN user_id VARCHAR;
        """)
        print("✓ Added user_id column")
        
        # Add foreign key constraint
        cursor.execute("""
            ALTER TABLE projects 
            ADD CONSTRAINT fk_projects_user 
            FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;
        """)
        print("✓ Added foreign key constraint")
        
        # Add index for performance
        cursor.execute("""
            CREATE INDEX idx_projects_user_id ON projects(user_id);
        """)
        print("✓ Created index on user_id")
        
        # Since you deleted all projects, we can now make it NOT NULL
        # If there were projects, we'd need to set a default user_id first
        cursor.execute("""
            ALTER TABLE projects 
            ALTER COLUMN user_id SET NOT NULL;
        """)
        print("✓ Set user_id as NOT NULL")
        
        # Commit the transaction
        conn.commit()
        print("\n✅ Migration completed successfully!")
        
        cursor.close()
        conn.close()
        
    except psycopg2.Error as e:
        print(f"\n❌ Migration failed: {e}")
        if 'conn' in locals():
            conn.rollback()
        sys.exit(1)
    except Exception as e:
        print(f"\n❌ Unexpected error: {e}")
        sys.exit(1)

if __name__ == "__main__":
    print("=" * 60)
    print("NoSlop Database Migration: Add user_id to projects")
    print("=" * 60)
    print()
    
    run_migration()
