# Building NoSlop Android

This document outlines the steps to build, compile, and run **NoSlop** — a privacy-first Android application for peer-to-peer chat and social sharing over Tor, with a built-in clearnet content aggregator.

---

## 1. Prerequisites

To build and run NoSlop locally, ensure your development environment has the following software installed:

*   **Java Development Kit (JDK 17)**: Recommended version is JDK 17 (Eclipse Temurin or OpenJDK), as modern Android Gradle Plugin (AGP) and Kotlin versions are strictly optimized for Java 17.
*   **Android Studio (Koala or newer)**: Standard IDE for Android developers. It supplies the required compilation Android SDK, tools, and virtual emulators.
*   **Android SDK (API Level 37.1)**:
    *   `compileSdk` = 37, minor level 1 (`tor-android` 0.4.9.13+ requires it)
    *   `targetSdk = 36`
    *   `minSdk = 24` (Android 7.0 Nougat — see [`app/build.gradle.kts`](../app/build.gradle.kts) for the authoritative value; cryptographic APIs and `EncryptedSharedPreferences` are available from API 23+, with the Ed25519 KeyPairGenerator path using Lazysodium with a Bouncy Castle fallback on all API levels, see [TECHNICAL_REFERENCE.md §3.2](TECHNICAL_REFERENCE.md#32-key-generation-cryptoservicegenerateidentity))
*   **Gradle**: Configured dynamically. The project uses Gradle Kotlin DSL (`build.gradle.kts` configuration).
*   **Embedded Tor Daemon**: NoSlop includes a fully native, embedded Tor daemon (`tor-android`). No separate Orbot app or external VPN is required to connect to the mesh network.

### Release signing (optional)

`assembleDebug` and `test` need nothing extra. To produce a *signed* release
build, put these four properties in `~/.gradle/gradle.properties` (not in the
repo — `local.properties` and `*.keystore` are gitignored):

```properties
NOSLOP_STORE_FILE=/absolute/path/to/noslop-release.keystore
NOSLOP_STORE_PASSWORD=...
NOSLOP_KEY_ALIAS=noslop
NOSLOP_KEY_PASSWORD=...
```

If any of the four are missing, the release signing config is skipped and
`assembleRelease` produces an unsigned APK. It no longer fails configuration,
so a fresh clone builds and tests without a keystore.

The same four properties sign the Play bundle (`bundlePlayRelease`). With Play App Signing, that key is
only the *upload key*, and a separate keystore for it is recommended (§8.5).

---

## 2. Clone and Installation

1.  **Clone the Repository**:
    ```bash
    git clone https://github.com/DeveLooped-INC/NoSlop.git
    cd NoSlop
    ```

2.  **Open in Android Studio**:
    *   Launch Android Studio.
    *   Select **Open** and select the root directory containing `settings.gradle.kts` and the `app` folder.
    *   Allow the IDE to complete syncing the project dependencies via Gradle.

---

## 3. First Run on an Emulator

1.  **Configure an AVD (Android Virtual Device)**:
    *   In Android Studio, open the **Device Manager** (Tools -> Device Manager).
    *   Click **Create Device**, select a modern phone (e.g., Pixel 7), and choose system image **API Level 34 (Upside Down Cake)**.
    *   Press finish and start the emulator by pressing the green play button.

2.  **Compile and Launch the App**:
    *   Select the `app` run configuration in the top toolbar.
    *   Click the **Run** button (green play icon or Shift + F10).
    *   Android Studio will compile all Kotlin files, package them into a debug APK, install it onto the active emulator, and launch the login screen.

---

## 4. First Run on a Physical Device

Using a physical Android device is highly recommended for testing Tor SOCKS5 packet proxying.

1.  **Enable USB Debugging**:
    *   On your Android phone, navigate to **Settings** -> **About Phone**.
    *   Find the **Build Number** entry and tap it **7 times** until a toast declares *"You are now a developer!"*.
    *   Go back to the main Settings page, search for **Developer Options**, and toggle **USB Debugging** on.

2.  **Verify ADB Connection**:
    *   Connect your device to your compiler machine via a USB cable.
    *   Open your command-line terminal and run:
        ```bash
        adb devices
        ```
    *   Confirm your device appears in the authorized list. Approve any authorization dialog on your phone screen.

3.  **Deploy to Phone**:
    *   In Android Studio, select your physical device from the target dropdown.
    *   Press the green Run button to sideload the package.

---

## 5. First Launch Checklist

When you boot NoSlop for the first time, verify the following checklist of screens to confirm proper initialization:

1.  **Step 1: Identity Generation**
    *   Input your chosen handle (e.g., `alice`) into the validated input box.
    *   Press **Generate Keypair**.
    *   Verify the app presents you with a registration card showing your combined `Display Handle` (e.g., `alice.a1b2c3`) and your truncated, copyable onion address.
2.  **Step 2: Recovery Phrase (BIP39 Word Cloud)**
    *   A 12-word mnemonic phrase is displayed. Tap to copy.
    *   Write this down — it is the only way to recover your account.
3.  **Step 3: Interest Selection**
    *   Select categories of interest from the built-in library (Tech, Science, Music, Gaming, Photography, Lifestyle, and more).
    *   Confirm a checkbox border highlights each chosen category card.
4.  **Step 4: Genre Refinement** (conditional)
    *   If you selected "Music" or "Video Platforms" in Step 3, a genre picker appears.
    *   Choose specific genres (e.g., Electronic, Lo-Fi, Jazz, Education, Documentary) to fine-tune your feed.
5.  **Step 5: Connection Walkthrough**
    *   Review the peer mesh information guidelines.
6.  **Step 6: Enter App**
    *   Click **Enter App** to save all preferences and enter the main dashboard. The app pre-loads 50+ feed items in the background. Subsequent launches skip onboarding entirely.

---

## 6. Building from the Command Line (APK)

If you need to distribute or test a standalone binary without loading Android Studio, you can build directly via the terminal.

Since the project currently contains two parallel codebases (the legacy Android app and the new Kotlin Multiplatform app), make sure you use the correct commands for the version you want to test.

### Option A: Build the Original (Legacy) Android App
This is the fully featured, production-ready original application located in the `app/` directory.

1.  **Compile with Gradle**:
    ```bash
    ./gradlew :app:assembleGithubDebug
    ```

2.  **Locate output binary**:
    `app/build/outputs/apk/github/debug/NoSlop-github-debug.apk`

3.  **Install via ADB**:
    ```bash
    adb install -r app/build/outputs/apk/github/debug/NoSlop-github-debug.apk
    ```

There are two flavours: `github` (`com.noslop.app`, with the in-app update check) and `play` (`com.noslop.me.app`, without it). Plain `assembleDebug` builds both.

**HAI-Net HUB (off by default).** The HUBs page and everything that depends on a Hub are built in only when you ask for them, with an environment variable or a Gradle property:

```bash
NOSLOP_ENABLE_HUB=true ./gradlew :app:assembleGithubDebug
./gradlew -PNOSLOP_ENABLE_HUB=true :app:assembleGithubDebug
```

Without it there is no HUBs tab, the Alerts icon sits in the bottom menu on every page, and the app ignores any Hub it was linked to before (the link is kept for a later build with the HUB). The value is read at configuration time, so change it with a clean build or `--rerun-tasks` if Gradle reuses an old `BuildConfig`. See `docs/TECHNICAL_REFERENCE.md` §16.40.

### Option B: Build the New KMP App
This is the new Kotlin Multiplatform application currently under development in the `mvp/` directory. Note that this requires the `NOSLOP_STORE_FILE` property to be configured in your `~/.gradle/gradle.properties` for release builds.

1.  **Compile with Gradle**:
    ```bash
    # Build debug version
    ./gradlew -p mvp :composeApp:assembleDebug
    
    # Or build release version
    ./gradlew -p mvp :composeApp:assembleRelease
    ```

2.  **Locate output binary**:
    *   Debug: `mvp/composeApp/build/outputs/apk/debug/composeApp-debug.apk`
    *   Release: `mvp/composeApp/build/outputs/apk/release/composeApp-release.apk`

3.  **Install via ADB**:
    ```bash
    adb install -r mvp/composeApp/build/outputs/apk/debug/composeApp-debug.apk
    ```

---

## 6.1. GitHub Issue Submission

The **File a Report** screen (Settings) needs no build-time keys. It posts the report (title and body: description, optional name, device information and recent error logs) to the NoSlop relay at `https://noslop.me/api/relay/github-issue`, which files the issue in `DeveLooped-INC/NoSlop`. If the relay can't be reached, the app opens GitHub's "New issue" page in the browser with the title and body filled in, so the report isn't lost.

> [!NOTE]
> Older versions of this guide asked for `GITHUB_PAT` and `GITHUB_ASSIGNEE` in `local.properties`. The app no longer reads them; remove them from your `local.properties` (a personal access token should never be built into an APK).

---

## 7. Common Errors and Fixes

Here are the top 5 common build/runtime issues and how to resolve them:

### 1. Gradle Version / JDK Conflict Error
*   **Symptom**: Build fails immediately with message `Unsupported class file major version` or compiler complaint regarding AGP dependency versions.
*   **Cause**: The compiler Java Version is pointing to JDK 8, 11, or 21 instead of JDK 17.
*   **Fix**: In Android Studio, go to **Settings** -> **Build, Execution, Deployment** -> **Build Tools** -> **Gradle**. Change the **Gradle JDK** to point to JDK 17.

### 2. SOCKS5 Proxy SocketException (Tor Connection Failure)
*   **Symptom**: Diagnostic logs print `Connection refused` or `Socket timeout` during "Test Tor" pings.
*   **Cause**: The embedded Tor daemon is either still bootstrapping circuits or failed to bind to its SOCKS proxy port (`Constants.TOR_SOCKS_PORT`).
*   **Fix**: Wait an additional 30 seconds for the internal daemon to achieve 100% bootstrap. If it continues to fail, fully force-close the app and reopen it to restart the native Tor process.

### 3. SQLite Database Migration Crash
*   **Symptom**: App crashes upon launching after a package update with an `IllegalStateException` stating `A migration from X to Y was required but not found`.
*   **Cause**: The database entity schema was modified, and the Room database version was incremented, but no explicit `Migration` object was provided to `NoSlopDatabase.addMigrations()`.
*   **Fix**: Write a proper SQL migration path from the old version to the new version in `NoSlopDatabase.kt`. 
    *   > [!CAUTION]
    *   > **NEVER use `.fallbackToDestructiveMigration()` in production.** Doing so will silently drop all tables and completely wipe the user's local peer connections, feed history, and stored mesh posts. While a cryptographic identity survives a destructive migration, the social graph will be irrevocably lost. Always prefer a crash over silent data destruction during beta testing.

### 4. MediaCodec Exhaustion (Video Black Screens)
*   **Symptom**: Videos in the feed fail to play, showing a black screen and logging `Media Quality Service not found`.
*   **Cause**: The device has exhausted its limit of hardware video decoders.
*   **Fix**: Verify that `VerticalPager` is properly releasing ExoPlayer instances (`player.release()`) when `isVisible` is false, to ensure hardware decoders are freed for the incoming slides.

### 5. Network Clearnet Traffic Blocked
*   **Symptom**: Custom RSS clearnet feeds fail to fetch with `IOException: Cleartext HTTP traffic not permitted`.
*   **Cause**: Android blocks standard non-HTTPS traffic (`http://`) by default to prevent injection attacks.
*   **Fix**: Update your feed source URLs to use secure `https://` schemas, or use a network security configuration file.

---

## 8. Releasing an Update

### 8.1 Bump the version

In `app/build.gradle.kts`, raise `versionCode` by one and set `versionName` (the current form is `0.8.3-alpha`; both flavours share these two values, and Play refuses a `versionCode` it has already seen, so never reuse one). Update the badge in `README.md` and the version in `docs/TECHNICAL_REFERENCE.md` (the header and §12).

Then run the unit tests:

```bash
./gradlew testGithubDebugUnitTest testPlayDebugUnitTest --rerun-tasks
```

### 8.2 Smoke test the minified build

Run this before publishing a release, and after any change to `proguard-rules.pro`, a dependency
version, Gson models, or code that loads classes by name. It catches what unit tests can't: they run
the debug build, which R8 never shrinks or renames.

It takes about 20 minutes with two devices (or one device and an emulator). Device B can be a debug
build.

#### Build and install

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

#### What to check

Mark each item as passed, or write down what happened.

##### Startup and Tor
- [ ] Fresh install: onboarding finishes. You get a handle with an 8-character tag and a Word Cloud.
- [ ] Tor reaches ready, and the feed loads over Tor.
- [ ] Kill the app and start it again: it starts without the onboarding screen.

##### Identity and keys (Bouncy Castle)
- [ ] Settings → identity shows the safety fingerprint and the check code.
- [ ] Restore from the Word Cloud on a second install. You get the same handle, tag and fingerprint.
- [ ] Turn on Discoverable (creates the secondary identity), then turn it off.

##### Contacts and messages
- [ ] Device A sends a connection request to B. B sees it pending, accepts it, and both show the
  contact as a friend.
- [ ] Exchange direct messages both ways. Each message shows Delivered.
- [ ] Group: create one, invite B, B accepts, and messages go both ways.
- [ ] Send an image and a video in a DM. They arrive and play.

##### Media (Media3 / ExoPlayer)
- [ ] Feed: swipe through at least ten videos with Route Clearnet via Tor on. Each starts, and the
  next one starts quickly (preloaded). No swipe stops part way.
- [ ] Turn on Background Playback and Foreground Service. Leave the app while a video plays, for
  about a minute. It keeps playing. Come back through the launcher, then repeat through the
  notification. No "Finding stream" either way.
- [ ] Open a video from search, so it isn't a warm slide, and seek back 20 seconds. It doesn't
  rebuffer (60 s back buffer). A slide reached by swiping is warm and does rebuffer: a known gap.
- [ ] An audio post plays, pauses and seeks.
- [ ] Attach a video to a post. Compression shows progress, finishes, and the post plays.
- [ ] Pick a video with HLS in its URL (most YouTube results). It plays.
- [ ] Swipe through several clearnet videos of people talking, with Route Clearnet via Tor on. Sound and picture stay in sync on warm slides, with no slower start.
- [ ] Watch a long video until it stalls, if one does. The picture stays on screen with a spinner, the video carries on from the same point, and "Finding stream" doesn't appear.

##### Search and settings (Gson models)
- [ ] Search "All" and "Videos": results appear, and more arrive as you swipe.
- [ ] Settings → content: change categories, genres, keywords and a banned channel. Restart the
  app. The changes are still there.
- [ ] Change the app language, then restart. The language is kept.

##### Backups
- [ ] Export a backup, then import it on a second install. Contacts, posts and messages come back.
- [ ] Export the creator identity archive, then import it.

##### Upgrade over an existing install (0.8.0: database and media encryption)

Run this with the previous release installed and holding real data: a large feed history, contacts, DMs, a group, and mesh photos, videos and audio. Install the new build over it with `adb install -r`, as above. Use a debug build for the file checks, because a release build refuses `run-as` (`SUPPORT.md` section 4).

- [ ] The app opens on the feed, not on the database recovery screen (Close / Restore from Backup / Start Over). Contacts, DMs, groups and posts are all still there.
- [ ] Settings → General says the database and the media are encrypted, or says why not.
- [ ] Photos, videos and audio from mesh posts and DMs that arrived before the upgrade still open and play.
- [ ] Debug build only: no `mesh.db.plain-bak` is left beside `mesh.db` after the first successful start, and no `.enc-tmp` or `.plain-bak` files are left in the `NoSlop/` media folder once the background conversion has finished.
- [ ] Kill the app during the first start after the upgrade, then start it again. It recovers by itself and nothing is lost (the plaintext original is kept until the encrypted copy has been opened).
- [ ] Restore a backup made by 0.7.x onto the new build. Then export a backup from the new build and restore it on a second device.
- [ ] With very little free storage the app still starts, and Settings says the database was not encrypted because of space.

##### Other
- [ ] QR: show your code on A and scan it with B.
- [ ] Update check (GitHub flavor): open Settings → About. There's no crash.

#### Read the log

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

Two more searches, which don't depend on R8:

* `NoSlop/JANK`: there should be no matches. Each one names code that froze the main thread.
* `Feed retention:`: at most one line a day, and it should report rows removed or none, with no
  `Feed retention failed`. Note the `Feed batch built` times too.

#### Record the result

Add a dated line to `PROJECT_STATUS.md`: the build version, the devices used, the items that
passed, and any failures and their stack traces.

### 8.3 Publish on GitHub

Releases are published on the public repository, [DeveLooped-INC/NoSlop](https://github.com/DeveLooped-INC/NoSlop) (since 0.8.3-alpha; earlier releases were on `gaborkukucska/NoSlop`). The in-app updater reads that repository's latest release. Until v0.8.4-alpha, publish each release on `gaborkukucska/NoSlop` too: versions before 0.8.3-alpha check only there.

Sign with the same keystore as every earlier release. Android refuses an update signed with another key, and so does the app's own certificate check.

```bash
VERSION=0.8.3-alpha   # the versionName from app/build.gradle.kts
./gradlew clean :app:assembleGithubRelease
cp app/build/outputs/apk/github/release/NoSlop-github-release.apk NoSlop-$VERSION.apk
sha256sum NoSlop-$VERSION.apk > NoSlop-$VERSION.apk.sha256
gh release create v$VERSION NoSlop-$VERSION.apk NoSlop-$VERSION.apk.sha256 --repo DeveLooped-INC/NoSlop --title "NoSlop $VERSION" --notes-file release-notes.md
```

* **Keep the version in the tag.** The app reads it from the tag (for example `v0.8.3-alpha`); the tag must match `versionName`.
* **Don't mark it as a pre-release or a draft.** The app asks GitHub for `/releases/latest`, which skips both.
* **Upload one `.apk`.** The app takes the first one it finds.
* **Upload the `.sha256`.** Any name ending in `.sha256` works, and a file named after the APK wins. The app can also read a digest from the release notes. Without a checksum, it refuses the download.
* Keep `app/build/outputs/mapping/githubRelease/mapping.txt` for this version.

### 8.4 noslop.me

**The GitHub build takes updates from GitHub alone** (`NOSLOP_GITHUB_UPDATES_V1`), so a release reaches it without touching the website. It checks at most once an hour, plus a daily background check.

**0.7.5 and older still read `https://noslop.me/content.json` first.** If `hero.apkUrl` there has a version in it, they don't ask GitHub for the release. For those apps, either set `hero.apkSha256` to the bare digest, or publish the checksum at exactly `<apk URL>.sha256`.

If `docs/PRIVACY_POLICY.md` changed, update the copy at `https://noslop.me/privacy` too.

An installed app that finds the update downloads it over the user's route, verifies it, and saves it to the Download folder. The user installs it from there.

### 8.5 Publish on Google Play (internal testing)

The Play build is the `play` flavour: app ID `com.noslop.me.app`, no in-app updater (Google Play delivers its updates). It is a separate app from the GitHub APK, with its own data and its own signing key held by Google. Nothing in this section touches the GitHub release in §8.3.

#### Build the bundle

Play takes an Android App Bundle (`.aab`), not an APK.

```bash
./gradlew clean :app:bundlePlayRelease
ls app/build/outputs/bundle/playRelease/
```

Use the file name Gradle prints if it differs from the examples below. Keep `app/build/outputs/mapping/playRelease/mapping.txt` for this version. The bundle carries it too, and the release block sets `debugSymbolLevel = SYMBOL_TABLE` for the native libraries, so Play's crash reports can be read.

Without the four signing properties (§1) Gradle builds an **unsigned** bundle, and Play Console refuses it. Check that it is signed, and which certificate signed it:

```bash
jarsigner -verify app/build/outputs/bundle/playRelease/NoSlop-play-release.aab
keytool -printcert -jarfile app/build/outputs/bundle/playRelease/NoSlop-play-release.aab
```

`jarsigner` should print `jar verified.` Compare the SHA-256 from `keytool` with your upload keystore's.

`versionCode` must be one Play has not seen for this app, on any track, so never reuse one (§8.1).

#### Which key signs the upload

Play Console enrols a new app in **Play App Signing**: Google keeps the key that signs what users install, and the key in your keystore becomes the **upload key**, which only proves that an upload comes from you. Unlike the GitHub release key (§8.3), a lost upload key can be reset through Play Console support.

Use a separate upload keystore for Play rather than the GitHub release keystore. That one can never be lost or replaced, so it is better kept out of one more workflow. Using it as the upload key also works.

```bash
keytool -genkeypair -v -keystore noslop-play-upload.keystore -alias noslop-upload \
  -keyalg RSA -keysize 2048 -validity 10000
```

Build with it by setting the four properties from §1 for this one build, without writing the passwords into your shell history. Gradle reads `ORG_GRADLE_PROJECT_<name>` environment variables as project properties:

```bash
export ORG_GRADLE_PROJECT_NOSLOP_STORE_FILE=/absolute/path/noslop-play-upload.keystore
export ORG_GRADLE_PROJECT_NOSLOP_KEY_ALIAS=noslop-upload
read -rs ORG_GRADLE_PROJECT_NOSLOP_STORE_PASSWORD; export ORG_GRADLE_PROJECT_NOSLOP_STORE_PASSWORD
read -rs ORG_GRADLE_PROJECT_NOSLOP_KEY_PASSWORD;   export ORG_GRADLE_PROJECT_NOSLOP_KEY_PASSWORD
./gradlew clean :app:bundlePlayRelease
```

If `~/.gradle/gradle.properties` already holds the GitHub keystore, confirm with the `keytool` command above that the bundle came out signed with the upload key you meant. Close the shell afterwards.

#### Try the bundle on a phone before uploading (optional)

[bundletool](https://github.com/google/bundletool/releases) turns the bundle into APKs for the connected phone, the way Play will:

```bash
java -jar bundletool.jar build-apks --bundle=NoSlop-play-release.aab --output=noslop.apks --connected-device
java -jar bundletool.jar install-apks --apks=noslop.apks
```

This installs `com.noslop.me.app`, next to any GitHub build, with separate data. Without `--ks` options bundletool signs with a debug key, which is fine for a local test.

#### Set up Play Console (once)

1. **Create the app.** Name, default language, app (not game), free. The app ID comes from the first bundle you upload and cannot be changed afterwards: `com.noslop.me.app`.
2. **App content.** Policy and programs, App content (menu names move around; the dashboard checklist lists what is still open). Answer to match [PRIVACY_POLICY.md](PRIVACY_POLICY.md), and keep the copy at `https://noslop.me/privacy` in sync (§8.4):
   * Privacy policy: `https://noslop.me/privacy`.
   * App access: the app has no accounts or logins. Say so.
   * Ads: none.
   * Content rating questionnaire and target audience: the app shows third-party feed content and lets people message each other. Answer for that, and pick an adult audience.
   * Data safety form: it must agree with the privacy policy, including the encryption described there.
   * Foreground service permissions: see below.
3. **Internal testing.** Testing, Internal testing.
   * Testers tab: create an email list. Testers are Google accounts, up to 100.
   * Releases tab: create a release, accept Play App Signing, upload the `.aab`, add release notes, save, review, start the rollout.
   * Testers tab: copy the opt-in link. A tester opens it signed in with an invited account, joins, and installs from Play.
   * Internal tests are not reviewed by Google, so they go out quickly.
4. **Internal app sharing** is a faster route for one-off checks: upload a bundle and share the link. It needs no release or tester list.

#### Foreground service declaration

The manifest declares two foreground services, both of type `specialUse` with the `FOREGROUND_SERVICE_SPECIAL_USE` permission: `org.torproject.jni.TorService` and `.mesh.NoSlopForegroundService`. Once you upload a bundle that uses a foreground service type, Play Console asks for a declaration on the App content page. For each type it wants:

* a description of the feature that uses the service,
* the user impact if that work were interrupted or deferred,
* a link to a video that shows the steps a user takes to trigger the feature.

`specialUse` is not one of Google's pre-set use cases, so you describe it yourself, and a person reviews it. Draft wording, to be checked against what the app really does before you submit:

* **Feature:** NoSlop is a peer-to-peer messenger and feed reader that runs over Tor. The foreground service keeps the embedded Tor daemon and the app's onion-service listener running, so the user can receive direct messages and posts from contacts while the app is in the background. It shows a persistent notification, and the user can switch it off in Settings.
* **Impact if deferred:** Android stops the process, Tor and the listener close, and contacts cannot reach the user until the app is opened again.
* **Video:** screen-record these steps: turn the foreground service on in Settings, show the notification, press Home, send a direct message from a second phone, and show it arriving. Share the link so a reviewer can open it without signing in.

There is no `REQUEST_INSTALL_PACKAGES` permission in either flavour, so the Play "install unknown apps" declaration does not apply.

#### Check before you rely on the Play build

* **16 KB memory pages.** Apps that target Android 15 or newer must support 16 KB page sizes on 64-bit devices, and Google's page gives 1 February 2027 as the date after which updates that don't can't be released. NoSlop ships native libraries (`tor-android`, SQLCipher), and nobody has checked them yet. Build `assemblePlayRelease`, check each `arm64-v8a` and `x86_64` `.so` with Google's `check_elf_alignment.sh` ([guide](https://developer.android.com/guide/practices/page-sizes)), and run the app on a 16 KB emulator image. Because `useLegacyPackaging = true` stores the libraries compressed, the ELF alignment is what matters. Play Console also warns at upload.
* **Target API.** `targetSdk = 36` meets Google's current rule that new apps and updates target Android 16 (API 36). Check [Google's page](https://developer.android.com/google/play/requirements/target-sdk) each August.
* **Upgrade on Play (0.7.9 to 0.8.0).** To test the database and media conversion through Play itself, first upload an older Play-flavour bundle to the internal track (build it from the `v0.7.9-alpha` tag with a `versionCode` of its own), install it from Play, add data, then upload the new bundle with a higher `versionCode` and update from Play. Then run the upgrade checklist in §8.2.
* **Production access.** A personal Play Console account created after 13 November 2023 must run a **closed** test with at least 12 testers opted in for 14 continuous days before it can apply for production access. Internal testers don't count. The rule applies per app, and organisation accounts are exempt. Until then the Play build stays in testing by invitation, as the README says.
* **User-generated content.** Check Play's User Generated Content policy against the app's report and block features before going past testing.

Record the result in `PROJECT_STATUS.md` like the §8.2 smoke test: the build version, the device, and what passed or failed.

---

**Related docs**: [TECHNICAL_REFERENCE.md §12](TECHNICAL_REFERENCE.md#12-build-configuration) for the full build-config/dependency reference · [SUPPORT.md](SUPPORT.md#debug-logs) if a build succeeds but the app misbehaves at runtime · [SUPPORT.md](SUPPORT.md) for user-facing troubleshooting once the app is running.
