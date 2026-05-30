# Building NoSlop Android

This document outlines the steps to build, compile, and successfully run the **NoSlop** serverless node and feed aggregator on Android.

---

## 1. Prerequisites

To build and run NoSlop locally, ensure your development environment has the following software installed:

*   **Java Development Kit (JDK 17)**: Recommended version is JDK 17 (Eclipse Temurin or OpenJDK), as modern Android Gradle Plugin (AGP) and Kotlin versions are strictly optimized for Java 17.
*   **Android Studio (Koala or newer)**: Standard IDE for Android developers. It supplies the required compilation Android SDK, tools, and virtual emulators.
*   **Android SDK (API Level 34)**:
    *   `compileSdk = 34`
    *   `minSdk = 26` (Android 8.0 Oreo - required for cryptographic APIs and background networking routines)
*   **Gradle**: Configured dynamically. The project uses Gradle Kotlin DSL (`build.gradle.kts` configuration).
*   **Orbot Fallback (Optional)**: NoSlop includes an embedded Tor client. No separate app is required to connect to the mesh network. However, for power users who prefer external routing, falling back to an external Orbot client (listening on local port 9050) is still optionally supported.

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
    *   Verify the app presents you with a custom registration card showing your combined `Display Handle` (e.g. `alice.a1b2c3`) and your truncated, copyable onion address.
2.  **Step 2: Feed Selection**
    *   Select categories of interest from the built-in, operational feeds list (including Tech, Security, World News).
    *   Confirm a checkbox appears on each chosen feed card.
3.  **Step 3: Connection Walkthrough**
    *   Read the peer mesh information guidelines.
    *   Click **Enter App** to write settings to the SQLite database and enter the dashboard. Subsequent launches must completely skip onboarding.

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
*   **Cause**: Orbot application is either not running, or is configured on a non-standard SOCKS port.
*   **Fix**: Launch the Orbot application on your phone, click the large gray Onion button to activate the VPN/Proxy tunnel, and verify that the SOCKS port displayed is set to `9050`.

### 3. SQLite Database Migration Crash
*   **Symptom**: App crashes upon launching after a package update.
*   **Cause**: The database entity schema was modified, but Room database version was not incremented, or a migration configuration was omitted.
*   **Fix**: During early R&D phases, you can clear the app's cache and local storage data via **App Info** -> **Storage** -> **Clear Data** on the device to drop local files and allow Room to re-create the tables cleanly.

### 4. Manifest Query Blocks Orbot App Detection
*   **Symptom**: The app reports Orbot is "Not Installed" even when it is actively installed.
*   **Cause**: Android 11+ (API 30+) introduces package visibility safety restrictions, blocking apps from querying installed applications.
*   **Fix**: Verify that the `<queries>` element is correctly configured in `app/src/main/AndroidManifest.xml` targeting package `org.torproject.android`:
    ```xml
    <queries>
        <package android:name="org.torproject.android" />
    </queries>
    ```

### 5. Network Clearnet Traffic Blocked
*   **Symptom**: Custom RSS clearnet feeds fail to fetch with `IOException: Cleartext HTTP traffic not permitted`.
*   **Cause**: Android blocks standard non-HTTPS traffic (`http://`) by default to prevent injection attacks.
*   **Fix**: Update your feed source URLs to use secure `https://` schemas, or use a network security configuration file.
