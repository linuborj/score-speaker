import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("multiplatform")
    kotlin("plugin.compose")
    kotlin("plugin.serialization")
    id("org.jetbrains.compose")
}

kotlin {
    jvm()

    sourceSets {
        val jvmMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                // compose.material3 comes transitively from :shared (api dep)
                implementation(project(":shared"))

                // Coroutines (GUI background work)
                implementation(libs.kotlinx.coroutines.core)

                // FileKit - native file picker dialogs
                implementation(libs.filekit.core)
                implementation(libs.filekit.dialogs)
                implementation(libs.filekit.dialogs.compose)

                // JSON serialization for labels
                implementation(libs.kotlinx.serialization.json)

                // Zoomable image support
                implementation(libs.zoomable)

                // OpenCV comes transitively from :shared (api dep)
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(kotlin("test-junit5"))
                // junit-jupiter aggregates the API, params, and engine.
                implementation(libs.junit.jupiter)
            }
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    maxHeapSize = "2g"
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
    }
}

compose.desktop {
    application {
        mainClass = "se.linusborjesson.scorespeaker.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "ScoreSpeaker"
            // Can't track androidApp's 0.x versionName: Dmg/Msi packaging
            // requires MAJOR > 0. Meaningless anyway — the harness isn't
            // distributed.
            packageVersion = "1.0.0"
        }
    }
}

