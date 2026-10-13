# Release smoke test (minified build)

Run this before publishing a release, and after any change to `proguard-rules.pro`, a dependency
version, Gson models, or code that loads classes by name. It catches what unit tests can't: they run
the debug build, which R8 never shrinks or renames.

It takes about 20 minutes with two devices (or one device and an emulator). Device B can be a debug
build.

## 1. Build and install

```
./gradlew clean assembleGithubRelease assemblePlayRelease
adb install -r app/build/outputs/apk/github/release/NoSlop-github-release.apk
```

Use the APK name Gradle prints if it differs. An unsigned release APK (no keystore properties)
installs only after you sign it. Keep `app/build/outputs/mapping/githubRelease/mapping.txt`: a
release stack trace can only be read with it (`retrace`).

Clear logcat, then start capturing it for the whole run:

```
adb logcat -c
adb logcat -v time > release-smoke.log
```

## 2. What to check

Mark each item as passed, or write down what happened.

### Startup and Tor
- [ ] Fresh install: onboarding finishes. You get a handle with an 8-character tag and a Word Cloud.
- [ ] Tor reaches ready, and the feed loads over Tor.
- [ ] Kill the app and start it again: it starts without the onboarding screen.

### Identity and keys (Bouncy Castle)
- [ ] Settings → identity shows the safety fingerprint and the check code.
- [ ] Restore from the Word Cloud on a second install. You get the same handle, tag and fingerprint.
- [ ] Turn on Discoverable (creates the secondary identity), then turn it off.

### Contacts and messages
- [ ] Device A sends a connection request to B. B sees it pending, accepts it, and both show the
  contact as a friend.
- [ ] Exchange direct messages both ways. Each message shows Delivered.
- [ ] Group: create one, invite B, B accepts, and messages go both ways.
- [ ] Send an image and a video in a DM. They arrive and play.

### Media (Media3 / ExoPlayer)
- [ ] Feed: swipe through at least ten videos. Each starts, and the next one starts quickly
  (preloaded).
- [ ] Seek back 20 seconds in a playing video. It doesn't rebuffer (60 s back buffer).
- [ ] An audio post plays, pauses and seeks.
- [ ] Attach a video to a post. Compression shows progress, finishes, and the post plays.
- [ ] Pick a video with HLS in its URL (most YouTube results). It plays.

### Search and settings (Gson models)
- [ ] Search "All" and "Videos": results appear, and more arrive as you swipe.
- [ ] Settings → content: change categories, genres, keywords and a banned channel. Restart the
  app. The changes are still there.
- [ ] Change the app language, then restart. The language is kept.

### Backups
- [ ] Export a backup, then import it on a second install. Contacts, posts and messages come back.
- [ ] Export the creator identity archive, then import it.

### Other
- [ ] QR: show your code on A and scan it with B.
- [ ] Update check (GitHub flavor): open Settings → About. There's no crash.

## 3. Read the log

Search `release-smoke.log` for these strings. Any match is a failure, even if the screen looked fine:

```
ClassNotFoundException
NoSuchMethodException
NoSuchFieldException
NoClassDefFoundError
Missing type parameter
TypeToken must be created
AbstractMethodError
not implemented
```

(`not implemented` is what Media3's LoadControl throws when a pre-1.4 method is reached.)

Then run `retrace` against `mapping.txt` on any obfuscated stack trace you find.

## 4. Record the result

Add a dated line to `PROJECT_STATUS.md`: the build version, the devices used, the items that
passed, and any failures and their stack traces.
