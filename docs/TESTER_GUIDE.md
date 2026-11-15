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

1. Open the [releases page](https://github.com/gaborkukucska/NoSlop/releases) and download the `.apk`
   and the `.sha256` file that belongs to it (`NoSlop-<version>.apk` and `NoSlop-<version>.apk.sha256`).
2. On a computer, put both files in one folder and run `sha256sum -c NoSlop-<version>.apk.sha256`. It
   should print `OK`. If it doesn't, don't install the file.
3. Copy the APK to your phone, open it from your Files app, and allow that app to install unknown apps
   when Android asks. There is no store listing.
4. **Play Protect.** Android may scan or warn about an app that didn't come from the Play Store. Google
   Play Protect has blocked earlier NoSlop builds as harmful; the history is in
   [PROJECT_STATUS.md](PROJECT_STATUS.md). Since v0.7.4-alpha the GitHub build no longer asks for the
   install-packages permission or installs updates itself, which Play Protect had flagged. If you
   still see a block, please [open an issue](https://github.com/gaborkukucska/NoSlop/issues) with your
   phone model, Android version and the NoSlop version. Don't switch Play Protect off just to get past
   a warning you don't understand.

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

When an update is available, NoSlop downloads it over Tor, checks it against the published checksum
and your installed signing certificate, and saves it to your Download folder. It can't start the install
itself: tap **Open Downloads**, then tap the NoSlop APK. Details are in
[SUPPORT.md](SUPPORT.md#installing-an-update).

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
