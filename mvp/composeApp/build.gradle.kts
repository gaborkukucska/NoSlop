import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.net.URI

buildscript {
    // SQLDelight's Gradle plugin drags in Gradle's embedded Kotlin, which strictly pins
    // org.jetbrains:annotations to 13.0; AGP 9.2.1 needs 23.0.0. Force the newer one on the
    // buildscript classpath so plugin resolution doesn't fail.
    configurations.classpath {
        resolutionStrategy { force("org.jetbrains:annotations:23.0.0") }
    }
}

plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.serialization)
    alias(libs.plugins.sqldelight)
}

sqldelight {
    databases {
        create("MeshDatabase") {
            packageName.set("com.noslop.mvp.db")
        }
    }
}

kotlin {
    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    // Headless JVM target — the always-on desktop HUB (ADR-002) that iOS leaves dial into.
    jvm {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlincrypto.sha3)
            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.primitive.adapters)
            implementation(libs.ktor.network)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        androidMain.dependencies {
            implementation(compose.uiTooling)
            implementation(libs.androidx.activity.compose)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.bouncycastle)
            implementation(libs.androidx.security.crypto)
            implementation(libs.sqldelight.android.driver)
        }
        // Android unit tests run on the JVM — use the JDBC (in-memory) driver, no Robolectric/device needed.
        androidUnitTest.dependencies {
            implementation(libs.sqldelight.sqlite.driver)
        }
        // The desktop HUB: BouncyCastle crypto, JDBC SQLite, OkHttp — the JVM actuals of the shared expects.
        jvmMain.dependencies {
            implementation(libs.bouncycastle)
            implementation(libs.sqldelight.sqlite.driver)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.zxing.core)
        }
        jvmTest.dependencies {
            implementation(libs.sqldelight.sqlite.driver)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
            implementation(libs.sqldelight.native.driver)
        }
    }
}

// --- Bundled Tor (ADR-009) ------------------------------------------------------------------------
// Fetch the pinned Tor Expert Bundle for the host platform into build/tor (not committed; cached after
// first run). The HUB launches build/tor/tor/tor to publish its onion. Pinned version = reproducible +
// supply-chain hygiene; bump deliberately. (Hardening TODO: verify the bundle's published sha256/signature.)
val torExpertBundleVersion = "14.5.1"
val torPlatform: String = run {
    val os = System.getProperty("os.name").lowercase()
    val arch = if (System.getProperty("os.arch").lowercase().let { it.contains("aarch64") || it.contains("arm") }) "aarch64" else "x86_64"
    when {
        os.contains("mac") -> "macos-$arch"
        os.contains("win") -> "windows-x86_64"
        else -> "linux-$arch"
    }
}

val downloadTor = tasks.register("downloadTor") {
    group = "application"
    description = "Download + prepare the bundled Tor binary for the HUB (build/tor/tor/tor)."
    val outDir = layout.buildDirectory.dir("tor").get().asFile
    val binary = File(outDir, "tor/tor")
    val version = torExpertBundleVersion
    val platform = torPlatform
    val isMac = System.getProperty("os.name").lowercase().contains("mac")
    outputs.file(binary)
    // Plain java.io + ProcessBuilder only — no Project execution-time APIs (Gradle 9 / config-cache safe).
    doLast {
        outDir.mkdirs()
        val tarball = File(outDir, "tor-expert-bundle.tar.gz")
        val url = "https://archive.torproject.org/tor-package-archive/torbrowser/" +
            "$version/tor-expert-bundle-$platform-$version.tar.gz"
        logger.lifecycle("Downloading Tor Expert Bundle $version ($platform)…")
        URI(url).toURL().openStream().use { input -> tarball.outputStream().use { input.copyTo(it) } }
        // Extract just the tor/ subtree (binary + libevent + pluggable transports).
        check(ProcessBuilder("tar", "xzf", tarball.absolutePath, "-C", outDir.absolutePath, "tor")
            .inheritIO().start().waitFor() == 0) { "tar extract failed" }
        tarball.delete()
        val torDir = File(outDir, "tor")
        torDir.walkTopDown().filter { it.isFile }.forEach { it.setExecutable(true) }
        // macOS Gatekeeper SIGKILLs unsigned downloaded binaries — ad-hoc sign tor + its dylib(s)/PTs.
        if (isMac) {
            torDir.walkTopDown()
                .filter { it.isFile && (it.name == "tor" || it.extension == "dylib" || it.parentFile.name == "pluggable_transports") }
                .forEach { f -> ProcessBuilder("codesign", "--force", "--sign", "-", f.absolutePath).start().waitFor() }
        }
        logger.lifecycle("Tor ready at ${binary.absolutePath}")
    }
}

// Run the HUB:  ./gradlew :composeApp:runHub --args="9876"      (plain TCP)
//               ./gradlew :composeApp:runHub --args="9876 tor"  (publishes an onion via the bundled tor)
tasks.register<JavaExec>("runHub") {
    group = "application"
    description = "Run the NoSlop desktop HUB (always-on relay node)."
    val jvmMain = kotlin.targets.getByName("jvm").compilations.getByName("main")
    dependsOn(jvmMain.compileTaskProvider, downloadTor)
    classpath = files(jvmMain.output.allOutputs, jvmMain.runtimeDependencyFiles)
    mainClass.set("com.noslop.mvp.HubMainKt")
    // Point TorProcess at the bundled binary, robust to working directory.
    environment("NOSLOP_TOR_BINARY", layout.buildDirectory.file("tor/tor/tor").get().asFile.absolutePath)
}

android {
    namespace = "com.noslop.mvp"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.noslop.mvp"
        minSdk = libs.versions.androidMinSdk.get().toInt()
        targetSdk = libs.versions.androidCompileSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}
