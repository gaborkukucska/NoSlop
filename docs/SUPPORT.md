# NoSlop Support Documentation

## Troubleshooting

### Feed is Empty
*   **Tor Connection**: Ensure Tor is connected. Check the status indicator at the top of the feed.
*   **Refresh**: Pull down to refresh or use the "Refresh Feed" button in the search modal.
*   **API Keys**: Some sources like Pexels or NewsAPI require an API key in Settings. Wikimedia and NASA do not.

### Media Playback Issues
*   **Videos not playing**: Some video sources (especially YouTube via Invidious) can be unreliable. Try swiping to the next item or refreshing.
*   **Audio buffering**: Clearnet audio (your aggregated feed) is fetched directly and is usually fast; audio or media attached to **mesh posts/DMs** is fetched chunk-by-chunk from a peer over Tor and may take longer to buffer, especially on a slow or freshly-built circuit.

### Mesh Connectivity
*   **Adding Peers**: Use the QR scan feature to exchange identity details with friends.
*   **Tor v3 Addresses**: Mesh communication depends on Tor hidden services. If Tor is not ready, you won't be able to send or receive mesh posts.

## Backup and Restore
*   **Word Cloud**: Your 12-word mnemonic is the ONLY way to recover your identity if you lose your device. Write it down and keep it safe.
*   **Export Backup**: Go to Settings -> Backup to export an AES-256-CBC encrypted archive containing your identity, the full local database (peers, mesh posts, comments, DMs, votes/reactions), and any downloaded media files. The encryption key is derived from your Word Cloud mnemonic — see [TECHNICAL_REFERENCE.md §3.8](TECHNICAL_REFERENCE.md#38-bip39-mnemonic).

## Known Issues
*   WebView for "Read Full Article" might not block all trackers on the source website.
*   Large mesh attachments (>5MB) may propagate slowly over Tor circuits.

---

**Related docs**: [DEBUG.md](DEBUG.md) for pulling logs when troubleshooting · [BUILD.md](BUILD.md) for build/install issues · [PROJECT_STATUS.md](PROJECT_STATUS.md) for currently-known bugs and in-progress fixes.
