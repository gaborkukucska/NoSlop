# Building NoSlop Android

This document outlines the steps to build, compile, and run **NoSlop** — a privacy-first Android application combining an immersive vertical media feed, clearnet content aggregation, and serverless encrypted social networking over Tor.

---

## 1. Prerequisites

To build and run NoSlop locally, ensure your development environment has the following software installed:

*   **Java Development Kit (JDK 17)**: Recommended version is JDK 17 (Eclipse Temurin or OpenJDK), as modern Android Gradle Plugin (AGP) and Kotlin versions are strictly optimized for Java 17.
*   **Android Studio (Koala or newer)**: Standard IDE for Android developers. It supplies the required compilation Android SDK, tools, and virtual emulators.
*   **Android SDK (API Level 35)**:
    *   `compileSdk = 35`
    *   `minSdk = 24` (Android 7.0 Nougat — see [`app/build.gradle.kts`](../app/build.gradle.kts) for the authoritative value; cryptographic APIs and `EncryptedSharedPreferences` are available from API 23+, with the Ed25519 KeyPairGenerator path using Bouncy Castle on API 24–32 and the platform Conscrypt provider on API 33+, see [TECHNICAL_REFERENCE.md §3.2](TECHNICAL_REFERENCE.md#32-key-generation-cryptoservicegenerateidentity))
*   **Gradle**: Configured dynamically. The project uses Gradle Kotlin DSL (`build.gradle.kts` configuration).
*   **Embedded Tor Daemon**: NoSlop includes a fully native, embedded Tor daemon (`tor-android`). No separate Orbot app or external VPN is required to connect to the mesh network.

---

## 2. Clone and Installation

1.  **Clone the Repository**:
    ```bash
    git clone https://github.com/your-username/noslop-android.git
    cd noslop-android
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

## 6. Build a Debug APK

If you need to distribute a standalone debug binary without loading Android Studio:

1.  **Compile with Gradle**:
    Open the terminal inside the root package directory and trigger the assemble task:
    ```bash
    # Standard local terminal command
    ./gradlew assembleDebug
    ```
    *(If building in the AI Studio cloud environment, run the dedicated system tool or `gradle assembleDebug` directly)*

2.  **Locate output binary**:
    The compiled debug APK will be stored at:
    `app/build/outputs/apk/debug/app-debug.apk`

3.  **Install via ADB**:
    Install the binary directly onto your connected testing device:
    ```bash
    adb install app/build/outputs/apk/debug/app-debug.apk
    ```

---

## 7. Common Errors and Fixes

Here are the top 5 common build/runtime issues and how to resolve them:

### 1. Gradle Version / JDK Conflict Error
*   **Symptom**: Build fails immediately with message `Unsupported class file major version` or compiler complaint regarding AGP dependency versions.
*   **Cause**: The compiler Java Version is pointing to JDK 8, 11, or 21 instead of JDK 17.
*   **Fix**: In Android Studio, go to **Settings** -> **Build, Execution, Deployment** -> **Build Tools** -> **Gradle**. Change the **Gradle JDK** to point to JDK 17.

### 2. SOCKS5 Proxy SocketException (Tor Connection Failure)
*   **Symptom**: Diagnostic logs print `Connection refused` or `Socket timeout` during "Test Tor" pings.
*   **Cause**: The embedded Tor daemon is either still bootstrapping circuits or failed to bind to port 9050.
*   **Fix**: Wait an additional 30 seconds for the internal daemon to achieve 100% bootstrap. If it continues to fail, fully force-close the app and reopen it to restart the native Tor process.

### 3. SQLite Database Migration Crash
*   **Symptom**: App crashes upon launching after a package update.
*   **Cause**: The database entity schema was modified, but Room database version was not incremented, or a migration configuration was omitted.
*   **Fix**: During early R&D phases, you can clear the app's cache and local storage data via **App Info** -> **Storage** -> **Clear Data** on the device to drop local files and allow Room to re-create the tables cleanly.

### 4. MediaCodec Exhaustion (Video Black Screens)
*   **Symptom**: Videos in the feed fail to play, showing a black screen and logging `Media Quality Service not found`.
*   **Cause**: The device has exhausted its limit of hardware video decoders.
*   **Fix**: Verify that `VerticalPager` is properly releasing ExoPlayer instances (`player.release()`) when `isVisible` is false, to ensure hardware decoders are freed for the incoming slides.

### 5. Network Clearnet Traffic Blocked
*   **Symptom**: Custom RSS clearnet feeds fail to fetch with `IOException: Cleartext HTTP traffic not permitted`.
*   **Cause**: Android blocks standard non-HTTPS traffic (`http://`) by default to prevent injection attacks.
*   **Fix**: Update your feed source URLs to use secure `https://` schemas, or use a network security configuration file.

---

**Related docs**: [TECHNICAL_REFERENCE.md §12](TECHNICAL_REFERENCE.md#12-build-configuration) for the full build-config/dependency reference · [DEBUG.md](DEBUG.md) if a build succeeds but the app misbehaves at runtime · [SUPPORT.md](SUPPORT.md) for user-facing troubleshooting once the app is running.
