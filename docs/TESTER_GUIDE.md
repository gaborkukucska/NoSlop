# NoSlop Tester Guide

Thanks for testing NoSlop. This page takes you from download to a useful bug report. It covers the
Android app in this repository (`app/`), not the iOS and desktop port under `mvp/`.

## Read this first

* **It is alpha software.** Expect bugs. Data loss and protocol changes that force everyone to update
  are possible. No server holds a copy of your identity, so **write down your 12-word Word Cloud**:
  without it there is no recovery.
* **The cryptography and protocol have never been reviewed by a human professional.** Don't use NoSlop
  for anything where a mistake could put you or someone else in danger. See the [FAQ](FAQ.md).
* **Android 7.0 or newer** (`minSdk` 24).
* **Your phone's clock must be within an hour of the real time.** Packets from a device with a wrong
  clock are dropped.
* **Both sides must be current.** Versions before v0.7.0-alpha can't connect to this one.

## 1. Install

### Pick a build

NoSlop comes in two builds. They are two separate apps, so pick one.

| | GitHub APK | Play build |
|---|---|---|
| App ID | `com.noslop.app` | `com.noslop.me.app` |
| Where from | The [releases page](https://github.com/DeveLooped-INC/NoSlop/releases) | Google Play, by invitation while it is in testing |
| Updates | NoSlop checks GitHub over Tor and saves the update for you to install | Google Play |
| Install | Sideload (steps below) | Normal Play install |

Because the app IDs differ, the two builds install side by side and share nothing: not your identity,
contacts or messages, and neither can update the other. To move from one to the other, export a backup
in the first (Settings → Backup) and restore it in the second with your Word Cloud. Don't run the same
identity in both at once: an identity's onion address can only be hosted by one copy.

Since 0.8.1-alpha each build (and each debug build) uses its own network ports, so both can run at the
same time. On older versions, with both installed, one of them could send messages but not receive them.

For the Play build, ask for an invitation. You will get a link that works for the Google account the
invitation was sent to; open it, join the test, and install from Play. The rest of this section is about
the GitHub APK.

### Install the GitHub APK

1. Open the [releases page](https://github.com/DeveLooped-INC/NoSlop/releases) and download the `.apk`
   and the `.sha256` file that belongs to it (`NoSlop-<version>.apk` and `NoSlop-<version>.apk.sha256`).
2. On a computer, put both files in one folder and check the checksum. It must match, or don't install
   the file.
   * Linux: `sha256sum -c NoSlop-<version>.apk.sha256` should print `OK`.
   * macOS: `shasum -a 256 -c NoSlop-<version>.apk.sha256` should print `OK`.
   * Windows (PowerShell): `certutil -hashfile NoSlop-<version>.apk SHA256`, then compare the result
     with the text in the `.sha256` file.
3. Copy the APK to your phone and open it from your Files app.
4. **Allow installing unknown apps.** Android asks for this once, for the app you opened the APK from.
   * **Android 8 and newer:** tap **Settings** in the prompt and allow it for that app (Files, a browser,
     and so on). You can also find it under Settings → Apps → Special app access → Install unknown apps.
     Afterwards you can switch it off again for that app.
   * **Android 7:** Settings → Security → **Unknown sources**. This is one switch for the whole phone.
   * The exact menu names differ between phone makers. Searching Settings for `unknown` usually finds it.
5. **Play Protect.** Android may scan or warn about an app that didn't come from the Play Store. Google
   Play Protect has blocked earlier NoSlop builds as harmful; the history is in
   [PROJECT_STATUS.md](PROJECT_STATUS.md). Since v0.7.4-alpha the GitHub build no longer asks for the
   install-packages permission or installs updates itself, which Play Protect had flagged. If you
   still see a block, please [open an issue](https://github.com/DeveLooped-INC/NoSlop/issues) with your
   phone model, Android version and the NoSlop version. Don't switch Play Protect off just to get past
   a warning you don't understand.

### Android's new limits on sideloading

Google is rolling out **developer verification** on certified Android devices: an app installed from
anywhere, including a file you downloaded yourself, is expected to come from a developer who has
registered with Google. This is not a ban on sideloading, and you can still install unverified apps, but
it takes extra steps. Per Google's announced plan, it applies since 30 September 2026 in Brazil,
Indonesia, Singapore and Thailand, and worldwide from 2027. Dates and details can change, so check
[Google's page](https://support.google.com/android-developer-console/answer/16561738) for the current
picture. Whether NoSlop's own package names are registered is tracked in
[PROJECT_STATUS.md](PROJECT_STATUS.md).

If your phone refuses an app from an unregistered developer, you have two options:

* **The advanced flow**, a setting Google has been rolling out since August 2026. As reported, you turn
  on Developer options, confirm that nobody is pressuring you, restart the phone, wait 24 hours, and
  authenticate with your fingerprint or PIN. You can then allow unverified apps for a limited time or
  with no time limit, and switch Developer options off again afterwards. Menu names and steps may vary by
  phone and Android version.
* **ADB.** Installing from a computer with USB debugging on skips both verification and the wait:
  `adb install NoSlop-<version>.apk` (add `-r` to update an installed copy, and `-s <serial>` if more
  than one device is connected).

The Play build comes through Google Play, so it is the simplest route if sideloading gets in your way.

### Upgrading from an older version

You can install a newer APK over the old one without uninstalling; it keeps your data. Before you
upgrade to 0.8.0-alpha or newer from an older version:

1. **Back up first** (Settings → Backup) and keep your Word Cloud. Uninstalling erases everything on the
   phone that isn't in a backup.
2. **Expect a first start that is slower than usual.** 0.8.0-alpha encrypts your database (`mesh.db`)
   once, the first time it opens, and converts your stored photos, videos and audio in the background
   afterwards. Open the app and give it a few minutes; don't force-stop it during the first start.
3. **Check Settings → General** afterwards. It says whether database and media encryption are active on
   your phone.
4. **If a recovery screen appears** instead of the feed, the app couldn't open the database and has left
   it untouched. Choose **Restore from Backup** or **Close**, and please report it. **Start Over** sets
   the old database aside rather than deleting it.
5. **Going back isn't supported.** An older version can't read the encrypted database, and Android won't
   install a lower version over a higher one without uninstalling it first.

Versions before v0.7.0-alpha can't connect to current ones, and both sides must update.

## 2. First run

* Onboarding asks for your language, shows your 12-word **Word Cloud** (write it on paper), and lets you
  choose topics and creators for the built-in clearnet aggregator and tune your content mix.
* Everything goes over Tor by default, so the first start takes a while while Tor connects. Check the
  status indicator at the top of the feed.
* The aggregator gives you a feed with no friends needed. Videos can take several seconds to start,
  because some sites refuse Tor exits and NoSlop tries other circuits. See
  [SUPPORT.md](SUPPORT.md#media-playback-issues).
* Settings → General shows whether database and media encryption are active on your phone.

## 3. Try the mesh (you need a second person)

The mesh is peer to peer, so chat and sharing only work with someone else running NoSlop.

1. You both install the app and finish onboarding.
2. Exchange Contact Card QR codes in person: one of you shows the code, the other scans it. The person
   being added approves the request after comparing your name and check code. Once approved, you both
   show as verified in person.
3. Try a direct message, a group chat, sharing something you found in the feed, and a backup export
   and restore (Settings → Backup).

On your own, you can still test the aggregator, search, filters, saving and backup. You can also
follow a creator by their Creator ID if you have one.

## 4. Updating

This section is about the GitHub APK. The Play build is updated by Google Play and has no in-app update
check.

When an update is available, NoSlop downloads it over Tor, checks it against the published checksum
and your installed signing certificate, and saves it to your Download folder. It can't start the install
itself: tap **Open Downloads**, then tap the NoSlop APK. Details are in
[SUPPORT.md](SUPPORT.md#installing-an-update).

**Moving to the new repository (from v0.8.3-alpha).** NoSlop's home is now
[DeveLooped-INC/NoSlop](https://github.com/DeveLooped-INC/NoSlop). Versions before v0.8.3-alpha look for
updates at the old address, `gaborkukucska/NoSlop`, which carries v0.8.3-alpha too but stops receiving
releases after v0.8.4-alpha. If you are on v0.8.2-alpha or older, update to v0.8.3-alpha or later once
(in the app, or by hand from the [releases page](https://github.com/DeveLooped-INC/NoSlop/releases)); from then on the app finds
updates at the new address. Anyone still on an older version after that has to download the new version
from the releases page and install it by hand.

## 5. Reporting a problem

Please include:

* the NoSlop version (Settings) and where you got it (GitHub APK, Play build, or built from source),
* your phone model and Android version,
* what you did, what you expected, and what happened.

How to send it:

* **Settings → Report an Issue** sends your report through a relay to a **public** GitHub issue. Leave
  out anything private.
* Or open a GitHub issue yourself; there are templates for bugs and feature requests.
* **Logs:** Settings → Structured Debug Logs → Copy Logs. NoSlop masks onion addresses and long
  key-like strings, but it can't catch everything. **Read the log before you post it.**
* **Screenshots of messages are black on purpose.** To take one for a bug report, turn off *Hide
  messages from screenshots* in Settings.
* **Security problems: don't post them publicly.** Follow [SECURITY.md](../SECURITY.md).

## 6. Known rough edges

The full list of open work is under "Work Remaining" in [PROJECT_STATUS.md](PROJECT_STATUS.md), and
[SUPPORT.md](SUPPORT.md) has troubleshooting for an empty feed, video playback and notifications. Two
things worth knowing up front: direct messages have no forward secrecy yet, and updates are checked
but not authenticated (see [SECURITY.md](../SECURITY.md)).
