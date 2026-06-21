import os
import sys

def apply_patch(filepath, old_content, new_content, name):
    if not os.path.exists(filepath):
        print(f"❌ File not found: {filepath}")
        sys.exit(1)
        
    with open(filepath, 'r', encoding='utf-8') as f:
        content = f.read()
        
    if old_content in content:
        content = content.replace(old_content, new_content)
        with open(filepath, 'w', encoding='utf-8') as f:
            f.write(content)
        print(f"✅ Successfully patched {name}")
    elif new_content in content:
        print(f"⚠️ {name} is already patched.")
    else:
        print(f"❌ Failed to patch {name}: Could not find the exact code block to replace.")
        sys.exit(1)

tab_path = "app/src/main/java/com/noslop/app/ui/UnifiedFeedTab.kt"

tab_old = """                            IconButton(onClick = { 
                                if (ContextCompat.checkSelfPermission(contextWrapper, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                                    showCamera = true
                                } else {
                                    permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
                                }
                            }) {"""

tab_new = """                            IconButton(onClick = { 
                                val hasCamera = ContextCompat.checkSelfPermission(contextWrapper, Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                val hasAudio = ContextCompat.checkSelfPermission(contextWrapper, Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                if (hasCamera && hasAudio) {
                                    showCamera = true
                                } else {
                                    permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
                                }
                            }) {"""

apply_patch(tab_path, tab_old, tab_new, "UnifiedFeedTab.kt")
