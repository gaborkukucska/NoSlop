import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.devtools.ksp)
}

// Read GitHub config from local.properties (outside android{} to avoid DSL scope issues)
val localProps = Properties()
val localPropsFile = rootProject.file("local.properties")
if (localPropsFile.exists()) {
    localPropsFile.reader().use { localProps.load(it) }
}
val githubPat: String = localProps.getProperty("GITHUB_PAT", "")
val githubAssignee: String = localProps.getProperty("GITHUB_ASSIGNEE", "")

android {
    namespace = "com.noslop.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.noslop.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 23
        versionName = "0.2.3-alpha"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64"))
        }
    }
    
    signingConfigs {
        create("release") {
            storeFile = file(project.property("NOSLOP_STORE_FILE") as String)
            storePassword = project.property("NOSLOP_STORE_PASSWORD") as String
            keyAlias = project.property("NOSLOP_KEY_ALIAS") as String
            keyPassword = project.property("NOSLOP_KEY_PASSWORD") as String
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")  // add this
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Local bind port — Tor maps external 9999 → this port.
            buildConfigField("int", "MESH_LISTEN_PORT", "9999")
            buildConfigField("int", "MEDIA_PROXY_PORT", "8080")
            // Tor proxy ports
            buildConfigField("int", "TOR_SOCKS_PORT", "9050")
            buildConfigField("int", "TOR_CONTROL_PORT", "9051")
            // GitHub issue submission
            buildConfigField("String", "GITHUB_PAT", "\"$githubPat\"")
            buildConfigField("String", "GITHUB_ASSIGNEE", "\"$githubAssignee\"")
        }
        debug {
            isDebuggable = true
            // No signingConfig needed — Android uses the default debug keystore automatically

            // Gives the debug build its own package name (com.noslop.app.debug)
            // and a distinct app label, so it installs side-by-side with the
            // Release build instead of overwriting it.
            applicationIdSuffix = ".debug"
            resValue("string", "app_name", "NoSlop Debug")
            // Different local bind ports so debug and release can run side-by-side.
            // Tor will map external 9999 → local 9998 for this build.
            buildConfigField("int", "MESH_LISTEN_PORT", "9998")
            buildConfigField("int", "MEDIA_PROXY_PORT", "8081")
            // Tor proxy ports for debug build
            buildConfigField("int", "TOR_SOCKS_PORT", "9052")
            buildConfigField("int", "TOR_CONTROL_PORT", "9053")
            // GitHub issue submission
            buildConfigField("String", "GITHUB_PAT", "\"$githubPat\"")
            buildConfigField("String", "GITHUB_ASSIGNEE", "\"$githubAssignee\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            // WHY: pure-JVM unit tests exercise core logic (crypto, mnemonic, wire protocol) that
            // transitively touches lightweight Android APIs — notably Logger -> android.util.Log.
            // Without this, those stubbed methods throw "not mocked" RuntimeExceptions. Returning
            // default values makes them no-ops so the core can be tested without Robolectric.
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    // --- Compose BOM (manages all compose versions) ---
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended) // Full icon set
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // --- Core Android ---
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.common)
    implementation(libs.androidx.navigation.compose)

    // --- Room (SQLite) ---
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // --- Security: EncryptedSharedPreferences for private key storage ---
    implementation(libs.androidx.security.crypto)

    // --- Coroutines ---
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)

    // --- Networking: OkHttp with SOCKS5 proxy support ---
    implementation(libs.okhttp)
    implementation(libs.logging.interceptor)
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")

    // --- Image loading ---
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)

    // --- Media (ExoPlayer) ---
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.3.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.3.1")

    // --- Bouncy Castle ---
    implementation("org.bouncycastle:bcprov-jdk15to18:1.78.1")

    // --- Lazysodium ---
    implementation("net.java.dev.jna:jna:5.13.0")
    implementation("com.goterl:lazysodium-android:5.1.0@aar")

    // --- Tor ---
    implementation("info.guardianproject:tor-android:0.4.8.16")
    implementation("info.guardianproject:jtorctl:0.4.5.7")
    implementation("info.guardianproject.netcipher:netcipher:2.1.0")

    // --- CameraX / Accompanist permissions for QR code / scanning support if needed ---
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.accompanist.permissions)

    // --- WorkManager for Background Feed Sync ---
    implementation(libs.androidx.work.runtime.ktx)

    // --- WebView Proxy Support ---
    implementation("androidx.webkit:webkit:1.11.0")

    // --- QR Scanning and QR Code Generation ---
    implementation(libs.google.mlkit.barcode.scanning)
    implementation(libs.zxing.core)

    // --- Testing ---
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.roboelectric)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
