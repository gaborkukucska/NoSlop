-- Migration: Add storage tracking fields to projects table
-- Date: 2025-12-05
-- Description: Adds folder_path, workflows_count, media_count, and storage_size_mb columns for Phase 0 project organization

-- Add folder_path column
ALTER TABLE projects 
ADD COLUMN IF NOT EXISTS folder_path VARCHAR(512);

-- Add workflows_count column
ALTER TABLE projects 
ADD COLUMN IF NOT EXISTS workflows_count INTEGER DEFAULT 0;

-- Add media_count column
ALTER TABLE projects 
ADD COLUMN IF NOT EXISTS media_count INTEGER DEFAULT 0;

-- Add storage_size_mb column
ALTER TABLE projects 
ADD COLUMN IF NOT EXISTS storage_size_mb FLOAT DEFAULT 0.0;

-- Create index on folder_path for faster lookups
CREATE INDEX IF NOT EXISTS idx_projects_folder_path ON projects(folder_path);

-- Update existing projects to have default values
UPDATE projects 
SET 
    workflows_count = 0,
    media_count = 0,
    storage_size_mb = 0.0
WHERE workflows_count IS NULL 
   OR media_count IS NULL 
   OR storage_size_mb IS NULL;
