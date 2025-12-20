import sys
import os
from sqlalchemy import create_engine, inspect

# Add backend to path to import models if needed, though we just inspect
sys.path.append("/opt/noslop/backend")

# Database URL - checking standard location
DB_URL = "sqlite:////opt/noslop/backend/noslop.db"

def inspect_db():
    print(f"Inspecting database at: {DB_URL}")
    if not os.path.exists("/opt/noslop/backend/noslop.db"):
        print("Database file NOT FOUND at /opt/noslop/backend/noslop.db")
        # Try local path relative to where script runs? 
        # But installation puts it in /opt/noslop/backend
        return

    engine = create_engine(DB_URL)
    inspector = inspect(engine)
    
    if not inspector.has_table("users"):
        print("Table 'users' DOES NOT EXIST.")
        return

    columns = inspector.get_columns("users")
    print("Columns in 'users' table:")
    for c in columns:
        print(f" - {c['name']} ({c['type']})")

if __name__ == "__main__":
    inspect_db()
