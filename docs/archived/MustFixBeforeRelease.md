# [ARCHIVED - RESOLVED] NoSlop (Legacy Android) — Must-Fix-Before-Release Report

> [!NOTE]
> **Status: RESOLVED**. All critical issues (Tor-by-default routing, cleartext network security scoping, StrictHostKeyChecking TOFU handling, non-exported broadcast receivers, and DAO unit test fixtures) have been implemented and verified in `app/`.

Scope: `NoSlop-Legacy-Android/app/` only, as requested (the `mvp/` KMP rewrite was ignored). Cross-referenced against `hai-master` for context on the HAI-Net vision it plugs into.

---

## The Honest Take First

Yes — this is a genuinely unique package. There is very little else on Android that combines a tracker-free RSS/API content aggregator, a TikTok-style snapping feed, an Ed25519/X25519 sovereign identity, a Tor-routed gossip mesh with signed posts and DMs, *and* a one-tap SSH deployer for a self-hosted "Hub" — all AGPL, no account, no server. The onboarding, feed prioritization, mesh filters, and OTA update system are unusually polished for a solo/AI-assisted project. That's a real story.

But there are a handful of issues that would blow up publicly the moment someone with a decompiler or a packet sniffer looks at it — which, if this goes on TikTok, *will* happen fast. None of these are "rewrite the app" problems; they're all fixable in hours to a couple of days. Fix these first, then post.

---

## 🔴 Critical (fix before any public release / video)

All three items below now have an agreed fix approach (details in each section) — flagging them as "planned" rather than open problems.

### 1. The privacy headline claim is false as shipped — ✅ Planned fix: Tor-by-default toggle
The README states: *"All outbound traffic — feed fetches, mesh messages, media requests — is routed through an embedded Tor SOCKS5 proxy... Your real IP address is never exposed to feed servers, peers, or anyone on the network."*

The code does not do this. `HttpClientProvider.kt` deliberately runs two separate clients:
- `clearnetClient` — **no proxy**, used for all RSS/Atom/API feed fetches, video/audio streaming, and image loading (used in `FeedParser`, every `feeds/api/*Client`, `VideoPlayer`, `AudioPlayer`, `PreloadManager`, `UnifiedFeedTab`, etc.)
- `torClient` — SOCKS5-proxied, used only by `MeshTransport` and the mesh media-fetch path

This is a deliberate, documented architectural choice (`docs/TECHNICAL_REFERENCE.md §7.1`), and your own `docs/PRIVACY_POLICY.md` actually describes it *honestly*: "the operators of those sources may see your IP address, as is normal for any web request." **The README and Privacy Policy directly contradict each other.** The mesh/social layer genuinely is Tor-routed — that part is true and good. But "your real IP is never exposed" is a marketing claim that's trivially falsified by anyone running Wireshark for ten seconds while the feed loads.

**Agreed direction**: ship with clearnet content routed through Tor **by default**, with a Settings toggle to opt out for speed (mesh/DM traffic stays Tor-only always, no toggle). This makes the README claim true out of the box rather than aspirational. Implementation notes:
- Replace the ~15 direct `HttpClientProvider.clearnetClient` call sites with a single settings-aware accessor (e.g. `activeClearnetClient`, backed by a `StateFlow<Boolean>` from `SettingsRepository`) so the toggle actually governs every call site — a partial swap would just recreate the same claims-vs-code gap.
- When Tor mode is on, hostname resolution should happen at the Tor exit node (like `torClient` already does), not via the Cloudflare/Google DoH cascade — otherwise you're leaking DNS queries while claiming full Tor routing.
- Reuse/adapt the existing mesh semaphore + AIMD congestion-control logic for clearnet media once it's going through Tor too — the aggressive feed preloading (`PreloadManager`) will hit the same circuit-saturation problems that necessitated that logic on the mesh side.
- Disclose the tradeoff in onboarding copy ("Private by default — feeds route through Tor. Turn off in Settings for faster loading") rather than burying the speed hit.
- Update README/marketing copy either way: "by default" is the accurate phrasing now, not "always."

Rough effort: ~1 day including testing. Worth doing before public release so the claim and the code agree from day one.

### 2. A GitHub Personal Access Token ships inside the release APK — ✅ Planned fix: self-hosted relay
In `app/build.gradle.kts`, both `debug` and `release` build types bake `GITHUB_PAT` from `local.properties` straight into `BuildConfig`, and `ReportIssueScreen.kt` sends it as `Authorization: Bearer ${BuildConfig.GITHUB_PAT}` when a user submits a bug report.

Any APK you distribute is a zip file. `BuildConfig.GITHUB_PAT` is trivially extractable (`apktool`, `jadx`, or even `strings apk`). If this token has any write scope on your repos (issue creation implies at least that), anyone who downloads the app can pull the token out and use it directly against the GitHub API — open/close issues as you, and depending on scope, potentially worse.

**Agreed direction**: route bug reports through a relay on the `noslop.me` box (nginx + cloudflared, already running), which holds the PAT server-side. The app never ships the credential. To make this actually solid rather than just relocating the problem:
- **Narrow endpoint, not a proxy** — expose exactly one route that opens an issue in this one repo with a fixed label; don't build a generic pass-through to `api.github.com`. Worst case if someone reverse-engineers the app's calls to the relay should be issue spam, not arbitrary GitHub API access.
- **Keep the PAT itself minimally scoped anyway** (fine-grained, `issues:write`, one repo, with an expiry) — defense in depth if the relay is ever compromised.
- **Rate-limit and validate on the relay** (payload size caps, per-IP limits) — Cloudflare's free-tier rate-limiting/bot-fight-mode in front of it is a good first layer before your own app logic runs.
- **Reliability caveat**: it's an old laptop on residential 5G, which is fine for a low-stakes bug-report relay, but worth a systemd unit with auto-restart, plus a client-side fallback (if the relay call fails, open the GitHub "new issue" page in-browser instead of erroring) so the feature degrades instead of breaking outright.
- **Rotate the currently-embedded token now regardless** — it should be treated as already public, independent of when the relay ships.
- Keep this box's role narrow going forward — don't stack more sensitive services (Hub, identity-related traffic) onto it later without reassessing it separately.

This is the right shape of fix. Once the narrow-endpoint + fallback pieces are in place, this item's fully closed.

### 3. SSH host-key verification is disabled during Hub deployment — ✅ Planned fix: purpose-built provisioning agent
`SshDeployer.kt` sets `session.setConfig("StrictHostKeyChecking", "no")`. This means the app will silently accept any host key when it SSHes in to deploy your Hub — a classic MITM opening. The payload it sends over that connection is your **full identity clone**, including your Ed25519 and X25519 private keys (per the "Identity Clone Architecture" described in `PROJECT_STATUS.md`). This is typically LAN-only, which lowers real-world risk, but "your private keys, sent over an unauthenticated channel" is exactly the kind of finding a security-minded viewer will call out immediately, and it undercuts the entire "sovereign identity" pitch.

**Agreed direction**: replace SSH entirely for the "Deploy" flow with a small purpose-built provisioning agent, rather than trying to harden SSH itself. The existing "Link" flow (pairing to an *already-running* Hub via mDNS + crypto handshake, no credentials at all) already proves this pattern works well and is well-liked — Deploy should get the same treatment:
- Ship a tiny standalone agent binary (a few hundred lines, Rust/Go) that advertises itself over mDNS as its own service (e.g. `_hainet-agent._tcp`), separate from raw `_ssh._tcp`.
- The agent exposes exactly **one narrow action** — "install/reset hainet-core given this identity payload" — never a general shell or arbitrary command execution. This bounds the worst-case blast radius to "can trigger a hainet-core install," not "has a root shell and your private keys."
- Authenticate via a **short-lived pairing code** the agent prints on first run (same pattern as `tailscale up`'s device auth, or a router's one-time setup PIN). The pairing code *is* the trust anchor — this sidesteps host-key/TOFU decisions entirely, since there's no long-lived key to verify in the first place.
- This preserves the "zero-terminal, tap Deploy in the app" UX you have today, while removing NoSlop from the SSH trust chain completely.
- Keep the current SSH-based `SshDeployer` path around as a de-emphasized **"Advanced / Manual Deploy"** option for users provisioning an existing box themselves — but still fix `StrictHostKeyChecking` on that path (TOFU + fingerprint confirmation) since some users will keep using it regardless of what's the default.

This is a proper (if modest) engineering project — roughly a weekend-to-a-week, not an afternoon patch — but it's the version that actually earns the "zero terminal, deploy in one tap" claim rather than just papering over the SSH trust problem. Worth treating as a fast-follow rather than a launch blocker if the Advanced/Manual path gets its TOFU fix in the meantime.

---

## 🟠 High priority (fix soon, before the video makes this app's security a talking point)

### 4. `UpdateManager$DownloadReceiver` is an exported broadcast receiver
`AndroidManifest.xml` has `android:exported="true"` on the receiver listening for `DOWNLOAD_COMPLETE`. Any other app on the device can send that broadcast. Your download-integrity checks (content-type, min file size) mitigate the worst outcomes, but this is worth tightening — either don't export it, or add a permission/signature check.

### 5. Global `cleartextTrafficPermitted="true"`
`network_security_config.xml` allows cleartext HTTP to *any* domain, not just localhost. Given `clearnetClient` already talks to arbitrary third-party feed hosts, this widens the window for downgrade/interception on non-HTTPS sources. Scope it to `127.0.0.1` (needed for the local Tor SOCKS proxy) via a `domain-config`, and let feed sources fail cleanly if they don't support HTTPS.

### 6. Silent plaintext fallback for the identity keystore
`IdentityRepository.kt` tries `EncryptedSharedPreferences` (Keystore-backed) first, but if that initialization throws, it silently falls back to plain `SharedPreferences` and just logs an error — the user is never told their private keys are now sitting unencrypted on disk. Given this is precisely the key material behind "your identity never leaves your device," a silent downgrade defeats the promise without telling anyone it happened.

**Fix**: Surface this to the user (a persistent warning banner, not just a log line) and/or block onboarding until it's resolved, rather than degrading silently.

### 7. Legacy `app/` is explicitly deprecated in your own docs
Your own `README.md` ("the canonical codebase is now the `mvp/` directory") and `docs/PROJECT_STATUS.md` ("the `app/` module is now read-only reference until it is retired") say this legacy Android app is being phased out in favor of the Kotlin Multiplatform `mvp/` rewrite. If you make TikTok videos showcasing *this* app as the current NoSlop, you're marketing something you've already told your own contributors is on its way out. Worth deciding — and stating clearly to your audience — whether this legacy build is the one you actually want people installing, or whether the honeypot should point at `mvp/` builds instead (or explicitly frame legacy `app/` as the "stable" build and `mvp/` as "in progress").

### 8. The Home Hub / Admin AI is not actually finished
NoSlop's own README calls the Hub's local LLM assistant "an almost working project and LLM management harness" and HAI-Net's own README states in bold: **"Experimental, proof of concept. NOT production ready."** If your videos lean on "deploy a private Hub with a local AI assistant" as a selling point, be careful — viewers who follow the recommended one-tap deploy flow will hit an admittedly-unfinished experience, and "not production ready" is HAI-Net's own words, not mine. Consider either holding the Hub/Admin-AI angle back from the initial push, or being upfront that it's an early-access feature.

---

## 🟡 Worth fixing, lower urgency

- **`resolveRssUrl()` dead code** (flagged in your own `docs/archived/LATEST_REVIEW.md`) — the RSS auto-discovery helper isn't called from anywhere, so "paste a bare site URL and we'll find the feed" silently doesn't work. Either wire it back in or remove it to avoid confusing future contributors.
- **`rssfeeds.webmd.com`** was flagged as intermittently failing in your own review notes — confirm the WebMD → Medical Xpress swap mentioned in `GAP_ANALYSIS.md §13.1` actually landed in `SourceLibrary.kt` for this legacy app (it may only have landed in `mvp/`).
- **`FOLLOW`/`UNFOLLOW`** and **group chats** remain unimplemented per your own `GAP_ANALYSIS.md` — fine to ship without them, just don't imply they exist in the videos.
- **Test coverage is present but thin relative to surface area** — 12 test files cover crypto, a few repositories, and the mesh/gossip pipeline, but large chunks of UI and the SSH deployer/Hub linking flow have no automated coverage. Not a blocker, but worth flagging since Hub deployment is the single highest-stakes user flow (it transmits private keys).
- **`0.3.3-alpha` versioning** — the app is explicitly alpha-tagged. Make sure that's the expectation you're setting for viewers, especially combined with items 3, 6, and 8 above.

---

## Suggested Fix Order (if you want a checklist)

1. Rotate the GitHub PAT now (independent of the relay build); stand up the `noslop.me` relay with a narrow single-purpose endpoint + client-side fallback. *(rotate: minutes; relay: an evening or two)*
2. Build the Tor-by-default toggle: settings-aware client accessor, exit-node DNS in Tor mode, reused congestion control for clearnet media, updated onboarding/README copy. *(~1 day incl. testing)*
3. Fix `StrictHostKeyChecking` on the existing SSH path first (TOFU + fingerprint confirmation) as a stopgap, since it'll be relabeled "Advanced / Manual Deploy" rather than removed. *(a few hours)*
4. Surface the EncryptedSharedPreferences fallback instead of failing silently. *(1–2 hrs)*
5. Un-export the download receiver; scope cleartext traffic to localhost. *(under an hour combined)*
6. Decide + state clearly which build (legacy `app/` vs `mvp/`) you're actually promoting, and how you'll frame the Hub/Admin AI's maturity.
7. Fast-follow: build the provisioning-agent replacement for SSH deploy (mDNS-advertised agent, single narrow action, short-lived pairing code) so "Deploy" no longer needs SSH at all. *(a weekend-to-a-week)*

Items 1–3 are the ones I'd genuinely block a launch on — everything else, including the full provisioning-agent rebuild, can ride along in a fast-follow update without embarrassing you.
