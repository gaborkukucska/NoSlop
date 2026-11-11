Feed fixes:
- Clearing a filter or a search puts the Live Feed back where you were
- Opening a filter no longer marks its first slides as seen, so Audio and Articles keep loading more
- Videos, Audio, Images and Articles skip what you've seen, and fetch more of their type from your interests when they run low
- Flickr photos show as images, Reddit posts come once, and an article with no text shows its page's description
- Liked shows what you liked, not everything you saved

Notifications:
- New Phone notifications switch (on by default) in Settings > Notifications. On Android 13 and newer NoSlop now asks for permission to show notifications

HUB:
- Builds include the HAI-Net HUB only when made with NOSLOP_ENABLE_HUB=true. Without it, the notifications icon is in the bottom menu

Updater fixes:
- On Android 10 and newer, Start Update opens a "Save as" prompt so the saved APK opens from your Files app (before, tapping it did nothing)
- Every download re-reads the latest release and its checksum first. If it can't, the update is cancelled
- The "update available" banner and notification clear once you are on the new version

Sources and keys:
- Dead sources removed (TikTok, Instagram, YouTube Trending, NewsAPI, Invidious and Piped search, and several feeds). WHO News, NPR Health, Jalopnik, Autocar and Guardian feeds added
- Source switches now work for sources that need no key
- Guided API key setup with a Test key button. Pexels, Vimeo, Podcast Index, Jamendo and NASA fixed
- Internet Archive and channel dates fixed

Privacy and mesh:
- Group invites, updates and catch-up answers now go only to group members
- The official node has a new identity. If you followed the old one, connect to the new one
- Backups now include the Podcast Index secret, and a backup with nothing in it is refused

Once downloaded, open the file from your Files app to install the update.
