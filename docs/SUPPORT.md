# NoSlop Support Documentation

## Troubleshooting

### Feed is Empty
*   **Tor Connection**: Ensure Tor is connected. Check the status indicator at the top of the feed.
*   **Refresh**: Pull down to refresh or use the "Refresh Feed" button in the search modal.
*   **API Keys**: Some sources like Pexels or NewsAPI require an API key in Settings. Wikimedia and NASA do not.

### Media Playback Issues
*   **Videos not playing**: Some video sources (especially YouTube via Invidious) can be unreliable. Try swiping to the next item or refreshing.
*   **Audio buffering**: High-quality audio may take a moment to buffer over Tor.

### Mesh Connectivity
*   **Adding Peers**: Use the QR scan feature to exchange identity details with friends.
*   **Tor v3 Addresses**: Mesh communication depends on Tor hidden services. If Tor is not ready, you won't be able to send or receive mesh posts.

## Backup and Restore
*   **Word Cloud**: Your 12-word mnemonic is the ONLY way to recover your identity if you lose your device. Write it down and keep it safe.
*   **Export Backup**: Go to Settings -> Backup to export an encrypted archive of your identity and viewed history.

## Known Issues
*   WebView for "Read Full Article" might not block all trackers on the source website.
*   Large mesh attachments (>5MB) may propagate slowly over Tor circuits.
