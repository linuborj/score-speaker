import java.util.Properties

plugins {
    kotlin("multiplatform")
    kotlin("plugin.compose")
    id("com.android.application")
    id("org.jetbrains.compose")
}

kotlin {
    androidTarget()

    sourceSets {
        val androidMain by getting {
            dependencies {
                implementation(project(":shared"))
                // ComponentActivity/setContent + ContextCompat live here,
                // not in shared — no shared code touches Android UI glue.
                implementation(libs.androidx.activity.compose)
                implementation(libs.androidx.core)
            }
        }
    }
}

android {
    namespace = "se.linusborjesson.scorespeaker"
    // Compose Material3 1.4.x requires compileSdk >= 35; android-36 is installed.
    compileSdk = 37

    // KMP's androidTarget() points the Android source set at src/androidMain/
    // by default, but this repo keeps the conventional Android layout under
    // src/main/. Override here rather than moving files.
    sourceSets["main"].apply {
        manifest.srcFile("src/main/AndroidManifest.xml")
        java.srcDirs("src/main/kotlin")
        res.srcDirs("src/main/res")
        assets.srcDirs("src/main/assets")
    }

    defaultConfig {
        applicationId = "se.linusborjesson.scorespeaker"
        // 26+ so the adaptive launcher icon needs no legacy PNG mipmaps.
        minSdk = 26
        // Google Play requires 36+ for new apps/updates since Aug 2026.
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        // OpenCV's AAR ships natives for four ABIs (~163 MB together, most
        // of it emulator-only x86). Real phones are arm64; debug adds
        // x86_64 below for emulators. 32-bit armeabi-v7a is deliberately
        // dropped — Play requires 64-bit and the app targets modern devices.
        ndk { abiFilters += "arm64-v8a" }
    }

    buildFeatures {
        // BuildConfig.DEBUG gates the developer tooling (debug overlays,
        // auto-capture) out of release builds.
        buildConfig = true
    }

    // Release signing reads keystore.properties (never committed) from the
    // repo root:
    //   storeFile=/absolute/path/to/scorespeaker.jks
    //   storePassword=...
    //   keyAlias=...
    //   keyPassword=...
    // Without the file, release builds are simply unsigned (CI, contributors).
    val keystoreFile = rootProject.file("keystore.properties")
    if (keystoreFile.exists()) {
        val props = Properties().apply { keystoreFile.inputStream().use { s -> load(s) } }
        signingConfigs.create("release") {
            storeFile = file(props.getProperty("storeFile"))
            storePassword = props.getProperty("storePassword")
            keyAlias = props.getProperty("keyAlias")
            keyPassword = props.getProperty("keyPassword")
        }
    }

    buildTypes {
        debug {
            // Emulator images are x86_64; merged with defaultConfig's arm64.
            ndk { abiFilters += "x86_64" }
        }
        release {
            // No minification: OpenCV loads its natives reflectively and the
            // APK-size win isn't worth maintaining keep rules yet.
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(17)
    }
}
