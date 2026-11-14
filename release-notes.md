Database & media encryption:
- Your database (`mesh.db`) is now encrypted on your phone with SQLCipher using a random key kept by the Android Keystore
- Your photos, videos and audio stored by NoSlop are now encrypted per file (AES-256-CTR) under a Keystore-wrapped key, and existing files are converted automatically in the background
- If your database cannot be opened on launch, NoSlop keeps the file untouched and shows a recovery screen (Close, Restore from Backup, or Start Over) instead of crashing or deleting anything
- Settings > General now shows whether database encryption and media encryption are active on your device

Backups & onboarding:
- Backups (NSG2 v2) carry your encrypted database and its key inside the Word Cloud encryption, and re-key it to the restoring device
- The "Protect Your Sovereign Identity" backup reminder now appears on any tab after onboarding and once after each app update (after the feed tutorial finishes)
- Redesigned Welcome cards on the first onboarding screen

Once downloaded, open the file from your Files app to install the update.
