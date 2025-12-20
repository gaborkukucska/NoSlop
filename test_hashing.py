import sys
import os
sys.path.append(os.path.dirname(os.path.abspath(__file__)))

from passlib.context import CryptContext

# Replicate auth.py configuration
pwd_context = CryptContext(schemes=["argon2"], deprecated="auto")

def test_hashing():
    print("Testing Argon2 hashing...")
    password = "correct_horse_battery_staple"
    try:
        hashed = pwd_context.hash(password)
        print(f"Hash generated successfully: {hashed[:20]}...")
        
        verify = pwd_context.verify(password, hashed)
        print(f"Verification result: {verify}")
        
        if verify:
            print("SUCCESS: Hashing and verification working.")
        else:
            print("FAILURE: Verification failed.")
            sys.exit(1)
            
    except Exception as e:
        print(f"ERROR: {e}")
        import traceback
        traceback.print_exc()
        sys.exit(1)

if __name__ == "__main__":
    test_hashing()
