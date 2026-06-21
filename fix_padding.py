import os

filepath = "app/src/main/java/com/noslop/app/ui/QRScanScreen.kt"
old_code = ".padding(horizontal = 16.dp, bottom = 16.dp)"
new_code = ".padding(start = 16.dp, end = 16.dp, bottom = 16.dp)"

if os.path.exists(filepath):
    with open(filepath, 'r', encoding='utf-8') as f:
        content = f.read()
    
    if old_code in content:
        content = content.replace(old_code, new_code)
        with open(filepath, 'w', encoding='utf-8') as f:
            f.write(content)
        print("✅ Fixed the padding typo in QRScanScreen.kt!")
    elif new_code in content:
        print("⚠️ The padding is already fixed.")
    else:
        print("❌ Could not find the typo to replace. Please check the file manually.")
else:
    print(f"❌ File not found: {filepath}")
