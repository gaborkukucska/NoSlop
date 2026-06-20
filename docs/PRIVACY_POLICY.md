# Privacy Policy — NoSlop

**Effective date:** 2026-06-20
**App:** NoSlop (iOS)
**Developer:** NoSlop (open-source project)
**Source code:** publicly available and auditable
**Hosted at:** https://noslop.me/privacy

---

## The short version

NoSlop is designed so that you are in control of everything. The app does not collect, store, or transmit any personal data to us or any third party. There is no server we run, no account to create, and nothing we can see.

---

## What data the app uses — and where it stays

### Identity and cryptographic keys

NoSlop generates a public/private key pair on your device during onboarding. Your private key never leaves your device. Your public key is shared only with peers you explicitly invite, via a QR code you show them directly.

### Content you create

Posts, direct messages, and any other content you create are stored locally in an encrypted SQLite database on your device. They are shared only with the peers you choose to connect with, over the encrypted mesh network. Nothing is sent to any server operated by NoSlop.

### Mesh network and peer connections

When you connect to peers, your device exchanges messages directly with theirs (peer-to-peer). The IP address your device uses on the local network or over Tor is visible to the peers you connect to, just as in any network communication. NoSlop does not log or retain this data.

### Tor

If you use the Tor routing option, your traffic is routed through the Tor network. Tor is a third-party anonymity network; its privacy properties and limitations are described at [torproject.org](https://www.torproject.org/about/privacy-policy/). NoSlop does not control or monitor Tor.

### Content feeds

NoSlop can display content from third-party sources you choose (RSS/Atom feeds, public APIs). When your device fetches content from those sources, the operators of those sources may see your IP address, as is normal for any web request. NoSlop does not intermediary, log, or see those requests.

### Camera (QR scanning)

The camera is used only to scan QR codes for peer invites. No images or video are stored or transmitted.

### No analytics, no crash reporting, no advertising

NoSlop does not include any analytics SDK, crash-reporting service, advertising framework, or tracking library. We receive no telemetry from your device.

---

## Data we collect

**None.** We operate no servers and receive no data from users.

---

## Data shared with third parties

We do not sell, rent, or share your data. The only data that leaves your device is:

- Messages and content you intentionally send to peers you have connected with.
- Network requests your device makes to content sources you have chosen.
- Tor traffic, routed through the Tor network if you enable it.

---

## Children

NoSlop does not knowingly collect data from anyone, including children. Because we collect no data at all, no special provisions for children are required beyond this statement.

---

## Open source and auditability

NoSlop is fully open-source. You do not need to take our word for any of the above — the complete source code is publicly available for inspection. If you find anything that contradicts this policy, please open an issue in the repository.

---

## Changes to this policy

If we change this policy, we will update the effective date above and note the change in the repository. Because we collect no data, changes are unlikely to affect your privacy in practice.

---

## Contact

Questions or concerns about privacy can be raised as an issue in the NoSlop public repository, or by emailing [noslop.me@proton.me](mailto:noslop.me@proton.me).
