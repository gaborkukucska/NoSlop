import os
import sqlite3
import subprocess

db_name = "mesh.db"
remote_path = f"/data/data/com.noslop.app.debug/databases/{db_name}"
local_path = f"./{db_name}"

# Pull the DB from the device
print(f"--- Pulling {db_name} from device ---")
subprocess.run(["adb", "pull", remote_path, local_path])

if not os.path.exists(local_path):
    print("❌ Failed to pull database. Is the app installed and the package name correct?")
    exit(1)

conn = sqlite3.connect(local_path)
cursor = conn.cursor()

print("\n--- LAST 3 MESH POSTS ---")
cursor.execute("SELECT id, mediaType, clearnetMediaType, clearnetUrl, clearnetTitle FROM mesh_posts ORDER BY timestamp DESC LIMIT 3")
for row in cursor.fetchall():
    print(f"ID: {row[0]}\n  mediaType: {row[1]}\n  clearnetType: {row[2]}\n  URL: {row[3]}\n  Title: {row[4]}\n")

print("\n--- LAST 3 MESH COMMENTS ---")
cursor.execute("SELECT id, content, mediaId, mediaType FROM mesh_comments ORDER BY timestamp DESC LIMIT 3")
for row in cursor.fetchall():
    content_snippet = row[1][:50] + "..." if row[1] else "None"
    print(f"ID: {row[0]}\n  Content: {content_snippet}\n  mediaId: {row[2]}\n  mediaType: {row[3]}\n")

conn.close()
os.remove(local_path)
