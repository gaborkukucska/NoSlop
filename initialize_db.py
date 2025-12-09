
import os
import sys
# Add the backend directory to the Python path
sys.path.append(os.path.abspath('backend'))

from backend.database import init_db, get_db
from backend.auth import get_password_hash
from backend.models import UserCreate
from backend.database import UserCRUD

# Initialize the database
init_db()

# Create a user
db = next(get_db())
user = UserCRUD.get_by_username(db, 'godmin')
if not user:
    user_in = UserCreate(
        username='godmin',
        email='godmin@example.com',
        password='password'
    )
    hashed_password = get_password_hash(user_in.password)
    user_data = {
        'id': 'user_godmin',
        'username': user_in.username,
        'email': user_in.email,
        'hashed_password': hashed_password,
    }
    UserCRUD.create(db, user_data)
    print("User 'godmin' created.")
else:
    print("User 'godmin' already exists.")
