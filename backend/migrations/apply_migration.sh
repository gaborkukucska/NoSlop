#!/bin/bash
# Apply database migration for Phase 0 storage fields
# Run this on BigBOY as: sudo ./apply_migration.sh

echo "Applying database migration: add_storage_fields.sql"
sudo -u postgres psql -d noslop -f /home/tom/NoSlop/backend/migrations/add_storage_fields.sql

if [ $? -eq 0 ]; then
    echo "✅ Migration applied successfully!"
    echo "You can now refresh the UI - the error should be gone."
else
    echo "❌ Migration failed. Check the error above."
    exit 1
fi
