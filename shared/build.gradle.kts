plugins {
    kotlin("multiplatform")
    kotlin("plugin.compose")
    kotlin("plugin.serialization")
    id("com.android.library")
    id("org.jetbrains.compose")
    id("app.cash.sqldelight")
}

sqldelight {
    databases {
        create("ScoreSpeakerDb") {
            packageName.set("se.linusborjesson.scorespeaker.db")
        }
    }
}

kotlin {
    // Target platforms: Android and Desktop (JVM)
    androidTarget()
    jvm("desktop")

    sourceSets {
        val commonMain by getting {
            dependencies {
                // Compose — `api` so downstream apps (:androidApp, :desktopApp)
                // can write @Composable UI without re-declaring these deps.
                api(compose.runtime)
                api(compose.foundation)
                api(compose.material3)

                // Serialization (for annotation JSON)
                implementation(libs.kotlinx.serialization.json)

                // SQLDelight runtime (driver lives per-platform).
                api(libs.sqldelight.runtime)
            }
        }

        // Intermediate source set holding the OpenCV-based vision pipeline.
        // Both Android and Desktop targets depend on it; each supplies its
        // own OpenCV jar (the API surface — org.opencv.* — is identical
        // across org.openpnp:opencv and org.opencv:opencv-android).
        val jvmAndAndroidMain by creating {
            dependsOn(commonMain)
        }

        val androidMain by getting {
            dependsOn(jvmAndAndroidMain)
            dependencies {
                // OpenCV — official Android distribution. Same org.opencv.*
                // API as the desktop openpnp build.
                api(libs.opencv.android)

                // CameraX — built-in rear camera. camera2 is the runtime
                // implementation; no code imports it, but it must ship.
                api(libs.camerax.core)
                api(libs.camerax.camera2)
                api(libs.camerax.lifecycle)
                api(libs.camerax.view)

                // SQLDelight Android driver (writes to app-private SQLite).
                implementation(libs.sqldelight.android.driver)
            }
        }

        val desktopMain by getting {
            dependsOn(jvmAndAndroidMain)
            dependencies {
                // OpenCV — JVM-only build. Same org.opencv.* API as the
                // Android distribution above. Exposed as `api` so the
                // desktopApp module can use OpenCV directly without
                // declaring its own dependency.
                api(libs.opencv.jvm)

                // SQLDelight JVM driver — used by desktopApp and by jvm tests
                // (in-memory SQLite by default).
                implementation(libs.sqldelight.sqlite.driver)
            }
        }
    }
}

android {
    namespace = "se.linusborjesson.scorespeaker.shared"
    // Matches :androidApp — Compose Material3 1.4.x requires compileSdk >= 35.
    compileSdk = 37

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(17)
    }
}
