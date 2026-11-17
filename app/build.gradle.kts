plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.devtools.ksp)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

base {
    archivesName.set("NoSlop")
}

configurations.all {
    resolutionStrategy {
        force("androidx.concurrent:concurrent-futures:1.2.0")
        force("androidx.concurrent:concurrent-futures-ktx:1.2.0")
    }
}

android {
    namespace = "com.noslop.app"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.noslop.me.app"
        minSdk = 24
        targetSdk = 36
        versionCode = 80
        versionName = "0.8.0-alpha"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64"))
        }

        // NOSLOP_PROXY_SECRET_V1, NOSLOP_OPTIONAL_PROXY_V1
        // The optional API proxy (the Worker in proxy/) and its HMAC key. Both are empty by
        // default: a build uses no proxy unless NOSLOP_PROXY_URL and NOSLOP_PROXY_SECRET are set
        // (in ~/.gradle/gradle.properties, not committed), or the user enters them in Settings.
        //
        // NOSLOP_PROXY_LEGACY_SECRET controls whether the raw secret is still
        // sent as an X-Proxy-Secret header. Defaults to false.
        buildConfigField(
            "String",
            "PROXY_URL",
            "\"" + (project.findProperty("NOSLOP_PROXY_URL") ?: "") + "\""
        )
        buildConfigField(
            "String",
            "PROXY_SECRET",
            "\"" + (project.findProperty("NOSLOP_PROXY_SECRET") ?: "") + "\""
        )
        buildConfigField(
            "boolean",
            "PROXY_SEND_LEGACY_SECRET",
            (project.findProperty("NOSLOP_PROXY_LEGACY_SECRET") ?: "false").toString()
        )

        // NOSLOP_HUB_BUILD_SWITCH_V1
        // The HAI-Net HUB page and its functions are built in only with NOSLOP_ENABLE_HUB=true, as an
        // environment variable or a Gradle property (-PNOSLOP_ENABLE_HUB=true). Off by default.
        val enableHub = (System.getenv("NOSLOP_ENABLE_HUB")
            ?: project.findProperty("NOSLOP_ENABLE_HUB")?.toString() ?: "false")
            .trim().equals("true", ignoreCase = true)
        buildConfigField("boolean", "ENABLE_HUB", enableHub.toString())

        // Optional build-time switch to auto-fill the Word Cloud in Export Profile / backup popups for testing.
        val autofillBackupMnemonic = (System.getenv("NOSLOP_AUTOFILL_BACKUP_MNEMONIC")
            ?: project.findProperty("NOSLOP_AUTOFILL_BACKUP_MNEMONIC")?.toString() ?: "false")
            .trim().equals("true", ignoreCase = true)
        buildConfigField("boolean", "AUTOFILL_BACKUP_MNEMONIC", autofillBackupMnemonic.toString())
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("play") {
            dimension = "distribution"
            applicationId = "com.noslop.me.app"
            buildConfigField("boolean", "ENABLE_IN_APP_UPDATER", "false")
        }
        create("github") {
            dimension = "distribution"
            applicationId = "com.noslop.app"
            buildConfigField("boolean", "ENABLE_IN_APP_UPDATER", "true")
        }
    }
    
    // NOSLOP_CONDITIONAL_SIGNING_V1
    // project.property() throws when the property is absent, and signingConfigs is
    // evaluated during the configuration phase — so a clone without the keystore
    // properties failed on EVERY task, including assembleDebug and test. Configure
    // the release signing config only when all four properties are present.
    val hasReleaseSigning = listOf(
        "NOSLOP_STORE_FILE", "NOSLOP_STORE_PASSWORD",
        "NOSLOP_KEY_ALIAS", "NOSLOP_KEY_PASSWORD"
    ).all { project.hasProperty(it) }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(project.property("NOSLOP_STORE_FILE") as String)
                storePassword = project.property("NOSLOP_STORE_PASSWORD") as String
                keyAlias = project.property("NOSLOP_KEY_ALIAS") as String
                keyPassword = project.property("NOSLOP_KEY_PASSWORD") as String
            }
        }
    }

    buildTypes {
        release {
            // Unsigned when the keystore properties are absent; assembleRelease then
            // produces an unsigned APK instead of failing configuration outright.
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            ndk {
                debugSymbolLevel = "SYMBOL_TABLE"
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Loopback ports (mesh listener, media proxy, Tor SOCKS and control) are set per
            // variant in noslopVariantPorts below (NOSLOP_VARIANT_PORTS_V1).
            
            val showSensitiveLogs = System.getenv("NOSLOP_SHOW_SENSITIVE_LOGS") == "true"
            buildConfigField("boolean", "SHOW_SENSITIVE_LOGS", showSensitiveLogs.toString())
        }
        debug {
            isDebuggable = true
            // No signingConfig needed — Android uses the default debug keystore automatically

            // Gives the debug build its own package name (com.noslop.app.debug)
            // and a distinct app label, so it installs side-by-side with the
            // Release build instead of overwriting it.
            applicationIdSuffix = ".debug"
            resValue("string", "app_name", "NoSlop Debug")
            // Loopback ports are set per variant in noslopVariantPorts below (NOSLOP_VARIANT_PORTS_V1).
            
            buildConfigField("boolean", "SHOW_SENSITIVE_LOGS", "true")
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

    sourceSets {
        getByName("androidTest") {
            assets.srcDirs(files("$projectDir/schemas"))
        }
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

    lint {
        lintConfig = file("lint.xml")
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

// NOSLOP_VARIANT_PORTS_V1
// Every installable variant (flavor x build type) has its own loopback ports, so any two of them can
// be installed side by side. Ports used to be set per build type only, so the GitHub and Play release
// builds shared 9999/8080/9050/9051: on a phone with both, the second app could bind none of them,
// borrowed the first app's Tor for its outgoing traffic and published no reachable onion, so it could
// send but never receive (one-way DMs, 2026-11-17).
// Order: mesh listener (Tor maps the onion's 9999 here), media proxy, Tor SOCKS, Tor control.
// Rules, checked below and by VariantPortsArchitectureTest: no port shared between variants, none of
// the ports other Tor apps use by default (Orbot 9050/9051/8118, Tor Browser 9150/9151) or 8080, and
// all below 32768 so they never fall in Android's ephemeral range (32768-60999).
// A new flavor or build type fails the build until it has a row here.
val noslopVariantPorts: Map<String, List<Int>> = mapOf(
    "githubRelease" to listOf(29601, 29602, 29603, 29604),
    "githubDebug" to listOf(29611, 29612, 29613, 29614),
    "playRelease" to listOf(29621, 29622, 29623, 29624),
    "playDebug" to listOf(29631, 29632, 29633, 29634),
)
run {
    val all = noslopVariantPorts.values.flatten()
    require(noslopVariantPorts.values.all { it.size == 4 }) {
        "NOSLOP_VARIANT_PORTS_V1: every variant needs exactly 4 ports: $noslopVariantPorts"
    }
    require(all.size == all.toSet().size) {
        "NOSLOP_VARIANT_PORTS_V1: two variants share a loopback port: $noslopVariantPorts"
    }
    val reserved = setOf(8080, 8118, 9050, 9051, 9150, 9151)
    require(all.none { it in reserved || it < 1024 || it >= 32768 }) {
        "NOSLOP_VARIANT_PORTS_V1: a port is reserved by another Tor app, privileged or ephemeral: $noslopVariantPorts"
    }
}

androidComponents {
    onVariants { variant ->
        val ports = noslopVariantPorts[variant.name]
            ?: error("NOSLOP_VARIANT_PORTS_V1: no loopback ports for variant '${variant.name}'. Add it to noslopVariantPorts in app/build.gradle.kts.")
        listOf("MESH_LISTEN_PORT", "MEDIA_PROXY_PORT", "TOR_SOCKS_PORT", "TOR_CONTROL_PORT")
            .forEachIndexed { i, key ->
                variant.buildConfigFields?.put(
                    key,
                    com.android.build.api.variant.BuildConfigField("int", ports[i].toString(), "NOSLOP_VARIANT_PORTS_V1 (${variant.name})")
                )
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
    implementation("androidx.appcompat:appcompat:1.7.0")
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
    // NOSLOP_DB_ENCRYPTION_V1: SQLCipher encrypts mesh.db (TECHNICAL_REFERENCE §16.51). 4.20.0 is built
    // against androidx.sqlite 2.7.0; both sqlite artifacts are named so they stay on one version.
    implementation("net.zetetic:sqlcipher-android:4.20.0@aar")
    implementation("androidx.sqlite:sqlite:2.7.0")
    implementation("androidx.sqlite:sqlite-framework:2.7.0")
    testImplementation("androidx.room:room-testing:2.7.0")
    androidTestImplementation("androidx.room:room-testing:2.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")

    // --- Security: EncryptedSharedPreferences for private key storage ---
    implementation(libs.androidx.security.crypto)

    // --- Coroutines ---
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)

    // --- Networking: OkHttp with SOCKS5 proxy support ---
    implementation(libs.okhttp)
    implementation(libs.logging.interceptor)
    implementation(libs.okhttp.dnsoverhttps)
    implementation("com.google.code.gson:gson:2.10.1")
    // NOSLOP_EXIF_ORIENTATION_V1 — BitmapFactory ignores EXIF orientation, so
    // every photo the app re-encoded came out sideways. See ui/ExifUtils.kt.
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // --- Image loading ---
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)

    // --- Media (ExoPlayer) ---
    // E5 (review C24/D11): was 1.3.1. Keep every Media3 artifact on one version (ReleaseBuildRulesE5Test).
    // 1.4 changed the LoadControl interface (see AdaptiveTorLoadControl); 1.9 raised minSdk to 23.
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-transformer:1.11.1")
    implementation("androidx.media3:media3-effect:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.11.1")

    // --- Bouncy Castle ---
    implementation("org.bouncycastle:bcprov-jdk15to18:1.78.1")

    // --- Lazysodium ---

    // --- Tor ---
    implementation("info.guardianproject:tor-android:0.4.9.14")

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

    // --- QR Scanning and QR Code Generation (ZXing only, tracker-free) ---
    implementation(libs.zxing.core)

    // --- SSH Client ---
    // NOSLOP_JSCH_FORK_V1 — com.jcraft:jsch has been unmaintained since 2018 and
    // supports neither rsa-sha2-* nor ssh-ed25519 host keys, so Hub deployment
    // failed key exchange against any current OpenSSH server. This fork is a
    // drop-in replacement and keeps the com.jcraft.jsch package names.
    implementation("com.github.mwiede:jsch:0.2.17")

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
