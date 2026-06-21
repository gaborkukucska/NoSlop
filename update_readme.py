import os
import sys

readme_path = "README.md"

if not os.path.exists(readme_path):
    print(f"❌ Could not find {readme_path}")
    sys.exit(1)

with open(readme_path, "r", encoding="utf-8") as f:
    content = f.read()

# 1. Add "Reasons to stop the slop" before the Features section
target_features = "---\n\n## Features"
reasons_content = """---

## Reasons to stop the slop with NoSlop

- **Background Playback** — Keep listening to content seamlessly without keeping the app open. NoSlop fully supports background playback out of the box.
- **No Advertisements** — Say goodbye to annoying banners and video interruptions. Your messaging and content experience on NoSlop is 100% ad-free, always.
- **Complete Feed Control** — No obscure algorithms. You curate your content, you organize your sources, you decide what you see. Total chronological freedom.
- **P2P Mesh Engagement** — Connect locally and globally with peers. Bypass central platforms and establish direct, censorship-resistant connections.
- **Secure Direct Messaging** — End-to-end encrypted communications over our peer-to-peer mesh. Your conversations are mathematically secure and truly private.

---

## Features"""

if target_features in content:
    content = content.replace(target_features, reasons_content)
    print("✅ Injected 'Reasons to stop the slop' section.")
elif "## Reasons to stop the slop" in content:
    print("⚠️ 'Reasons to stop the slop' is already in the README.")
else:
    print("❌ Could not find the target '## Features' section to replace.")

# 2. Add "Your Responsibilities" before the Documentation section
target_docs = "---\n\n## Documentation"
responsibilities_content = """---

## Your Responsibilities

- **Open Source & Your Responsibility** — NoSlop is well-built open-source software with all functionalities in open code. Therefore, all responsibilities for its use fall entirely on you, the user.
- **No Server & No Automatic Backups** — Because there is no central server, there is NO cloud data backup. You must back up your identity and data yourself using the built-in export function.
- **Content Filtering** — While we do compile with some negative keywords to avoid certain content (see the repo), you should also set up your own negative keywords to avoid unwanted content in your feed.
- **Bring Your Own Network** — NoSlop is much better with friends, HOWEVER, it holds no user directory whatsoever. You must manually add peers to build your mesh. It is entirely up to you.
- **Installing the APK** — Android will likely show security warnings about installing apps from unknown sources since this is downloaded directly and not from the Play Store. You will likely need to search your phone's settings for `unknown` to find the 'Install unknown apps' section and allow installing from unknown sources to be able to install this app.

---

## Documentation"""

if target_docs in content:
    content = content.replace(target_docs, responsibilities_content)
    print("✅ Injected 'Your Responsibilities' section.")
elif "## Your Responsibilities" in content:
    print("⚠️ 'Your Responsibilities' is already in the README.")
else:
    print("❌ Could not find the target '## Documentation' section to replace.")

# Write back to README.md
with open(readme_path, "w", encoding="utf-8") as f:
    f.write(content)

print("--- README.md updated successfully! ---")
