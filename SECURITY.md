# Security Policy

NoSlop is a one-person, alpha-stage project. Reports are read and taken seriously, but there is no security team and no bug bounty.

## Reporting a vulnerability

**Please don't open a public issue, and don't paste keys, onion addresses or logs into one.** Report it privately instead:

* Email **[noslop.me@proton.me](mailto:noslop.me@proton.me)**, or
* use the **Report a vulnerability** button on the repository's *Security* tab, if it is shown.

Don't use **Settings › Report an Issue** in the app for this: it creates a public GitHub issue.

Useful things to include: the app version (Settings), the build you used (GitHub APK, Play build, or from source), what you did, what you expected, and what happened. For a protocol or crypto problem, the packet type or code path is more useful than a screenshot. If you are sending a log, read it first: it can hold onion addresses and peer keys.

I'll reply as soon as I can and will tell you when a fix ships. Please give me a reasonable chance to fix a problem before you publish it. If you want credit in the release notes, say so.

## Which versions get fixes

Only the latest release. These are alpha builds and the wire protocol has changed incompatibly before (versions older than v0.7.0-alpha can't connect to current ones), so older builds are not patched.

## What is in scope

* The Android app in `app/`: identity and key handling, DMs and group chats, the gossip and Tor transport, backups and the creator-identity file, media handling, and the update check and download.
* The wire protocol in [docs/WIRE_PROTOCOL_REFERENCE.md](docs/WIRE_PROTOCOL_REFERENCE.md).
* The proxy Worker in [`proxy/`](proxy/), which carries YouTube, Reddit and Jamendo search requests.

## What is out of scope

* The Home Hub. No Hub is deployed, its source is not in this repository, and builds leave it out unless made with `NOSLOP_ENABLE_HUB=true`.
* The iOS and desktop port under `mvp/`, which is still being built.
* Problems in Tor, Android, or the third-party sites NoSlop reads from.
* Findings that need a rooted phone or an attacker who already has your unlocked device, unless NoSlop makes them worse than they have to be.

## Known limitations

Several weaknesses are already known and written down, so you don't have to rediscover them. They are listed under **Mesh and security** in [docs/PROJECT_STATUS.md](docs/PROJECT_STATUS.md). They include:

* DMs use static X25519 keys and have no forward secrecy yet.
* The database is not fully encrypted: DM and group message bodies are, but feed items, history, peers and settings rely on Android's file encryption alone. Photos, videos and audio sent or received are plain files in the app's storage folder.
* If the Keystore-backed preferences can't be opened, secrets fall back to AES-GCM under a separate Keystore key, or, with no working Keystore, under a key derived from the device ID, which only obscures them. The app shows a warning when that happens.
* Updates are checked against a SHA-256 checksum and the signing certificate, but there is no extra signature and no certificate pinning on the update check.
* Packets pulled from a Hub skip the envelope signature check.
* Rate limits are fixed per sender, and the default proxy secret ships inside the APK. The Worker forwards only the app's own requests and rate-limits them (`proxy/`), but anyone with the APK can sign requests.
* **Never reviewed by a human professional.** The primitives are standard ones from Bouncy Castle, but the protocol built from them is NoSlop's own. It has had repeated AI-assisted code reviews (kept in `docs/archived/`) and a lot of device testing, but no human professional has ever reviewed it. Review by someone qualified is very welcome.

A new angle on any of these is welcome. A report that only restates one is still useful as a vote for fixing it sooner.

See also the [privacy policy](docs/PRIVACY_POLICY.md) and the security notes in [docs/TECHNICAL_REFERENCE.md](docs/TECHNICAL_REFERENCE.md) (section 15).
