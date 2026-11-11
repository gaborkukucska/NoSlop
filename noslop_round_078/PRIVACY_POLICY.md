# Privacy Policy — NoSlop

**Effective date:** 2026-11-11
**App:** NoSlop (Android)
**Developer:** NoSlop (open-source project)
**Source code:** publicly available and auditable
**Hosted at:** https://noslop.me/privacy

---

## The short version

NoSlop has no accounts and no analytics. Your identity, messages, contacts and feed choices stay on your phone, and nobody can recover them for you. Two small servers run by the developer are involved, and this policy says exactly what each one sees: a proxy that carries some search requests, and a relay for bug reports you choose to send. Everything else goes between you and your peers over Tor, or between you and the sites you read.

---

## What data the app uses — and where it stays

### Identity and cryptographic keys

NoSlop generates your key pairs on your device during onboarding. Your private keys stay on your device, except:

- inside a backup file you export, which is encrypted with a key derived from your 12-word Word Cloud;
- inside a creator-identity backup you export, which is protected by a passphrase you choose;
- in builds that include the HAI-Net Home Hub (not the default), when you deploy a Hub to your own server (see below).

Your public keys and onion address go to the peers you connect with, and they are part of every packet you sign. A creator identity is public by design: anyone who follows it sees its public key and onion address.

### Content you create

Posts, direct messages and other content you create are stored on your device. 1:1 direct messages are stored as the encrypted ciphertext they arrived in, and group chat message bodies are encrypted at rest with an AES-256-GCM key held by the Android Keystore. Feed items, history, peers and settings are stored in the app's database, protected by Android's file encryption but not encrypted by NoSlop itself. Photos, videos and audio you send or receive are stored as ordinary files in the app's own storage folder. Content is shared only with the peers you choose, over the encrypted mesh network.

### What the app remembers about your feed

The app keeps a record of what you have viewed and swiped away, so it doesn't show you the same items again. That record stays on your device, and there is no server that holds a copy. Feed items you haven't saved are removed from your device after about 75 days, or sooner once more than 10,000 are unread. The viewed and swiped records are removed after about 90 days. Your saved items stay until you remove them.

### Mesh network and peer connections

Peers reach each other only through Tor onion services. The peers you connect with see your onion address and public keys, not your IP address. NoSlop does not log or keep network addresses.

### Tor

Mesh traffic always goes over Tor. Clearnet requests (feeds, searches, update checks) go over Tor too while **Route Clearnet via Tor** is on, which is the default. If you turn it off, the sites you read and the proxy below see your IP address. Tor is a third-party anonymity network; its privacy properties and limitations are described at [torproject.org](https://www.torproject.org/about/privacy-policy/). NoSlop does not control or monitor Tor.

### Home Hub deployment (only in builds that include the Hub)

Builds include the HAI-Net Home Hub only when made with `NOSLOP_ENABLE_HUB=true`; the default builds don't. In such a build, when you deploy a Home Hub over SSH, your cryptographic identity (including the Ed25519 and X25519 keys) is sent to your own server over SSH so your node can run around the clock. The first SSH connection asks you to check and pin the server's host key fingerprint.

---

## Servers involved

### The proxy Worker (run by the developer)

YouTube, Reddit and Jamendo block many Tor exits, so search requests for those three go through a Cloudflare Worker run by the developer (`yt-proxy.megadreamland.workers.dev`; source in the repository's `proxy/` folder). It sees:

- the search terms, subreddit names and tags in those requests, which come from your interests and searches;
- over Tor, a Tor exit's address, not yours. With Route Clearnet via Tor off, it sees your IP address.

It does not see who you are: the requests carry no account, key or device identifier. Its code forwards only these requests, writes no logs, and caches answers for a few minutes. Cloudflare, which runs the Worker, can see the same requests under its own [privacy policy](https://www.cloudflare.com/privacypolicy/). Video and audio bytes never go through it.

You can point the app at your own Worker in Settings, or switch the aggregator off.

### The bug-report relay (run by the developer)

**Settings › Report an Issue** sends your report to a relay at `noslop.me`, which posts it as a **public** issue on the NoSlop GitHub repository. A report holds what you type, the name you give (if any), and, if you leave those switches on, your phone model, Android version and app version, and the last 25 warnings and errors from the app's log, with onion addresses and keys masked. Nothing is sent unless you press send. If the relay can't be reached, the app opens GitHub in your browser instead. Don't use it for security problems; email [noslop.me@proton.me](mailto:noslop.me@proton.me).

### The Official Creator Node

A fresh install lists the official NoSlop node so there is somewhere to discover creators. It is an ordinary NoSlop node run by the developer, and it is untrusted. Connecting to it is your choice. If you do, it sees what any peer sees: your secondary identity's public key and onion address.

### Content sources and update checks

- **Content sources**: the aggregator reads RSS and Atom feeds and public APIs you choose. Those sites see requests from a Tor exit (or from your IP with Route Clearnet via Tor off).
- **Media streaming**: video and audio are streamed directly from the source's servers (for example `googlevideo.com`, `jamendo.com`).
- **Update checks**: in the GitHub build, update checks go to the GitHub Releases API, and the APK is downloaded from GitHub, over your chosen route. A verified update is saved to your Download folder, and you choose whether to install it. You can turn automatic update checks off in Settings.

---

## Camera and microphone

The camera scans QR codes for contact invites, and takes photos and videos when you choose to send one. The microphone records only while you record a video or audio clip to send. QR scans are not stored or sent.

---

## No analytics, no crash reporting, no advertising

NoSlop includes no analytics SDK, automatic crash-reporting service, advertising framework or tracking library. The app sends no telemetry. The only report that reaches the developer is one you send yourself, as described above.

---

## Data we collect

- **Bug reports you send**, published as public GitHub issues.
- **Search requests passing through the proxy**, which the Worker forwards and does not keep beyond a few minutes of cache.
- **Messages you send to the official node**, if you connect to it, as with any peer.

Nothing else. We have no account system, no user database and no copy of your identity.

---

## Data shared with third parties

We do not sell or rent data. The data that leaves your device is:

- messages and content you send to peers you have connected with;
- requests to the content sources you have chosen, some of them through the proxy above;
- bug reports you choose to send;
- Tor traffic, through the Tor network.

---

## Children

NoSlop isn't built or vetted for children, and we don't knowingly collect data from anyone. If a child uses it, a trusted adult should set it up. See the [FAQ](FAQ.md).

---

## Open source and auditability

NoSlop is fully open source, including the proxy Worker. You don't need to take our word for any of the above: the complete source code is public. If you find anything that contradicts this policy, please open an issue in the repository.

---

## Changes to this policy

If we change this policy, we will update the effective date above and note the change in the repository.

**2026-11-11:** names the proxy Worker and the bug-report relay as servers run by the developer and says what each sees; private keys can leave the device in backups you export and in Hub deployments; peers see your onion address, not your IP address; the camera is also used for photos and videos you send; media files and most of the database are not encrypted by NoSlop itself.

---

## Contact

Questions or concerns about privacy can be raised as an issue in the NoSlop public repository, or by emailing [noslop.me@proton.me](mailto:noslop.me@proton.me).
