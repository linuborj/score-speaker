// Root build file for ScoreSpeaker
// Module-specific configuration is in each module's build.gradle.kts
plugins {
    kotlin("multiplatform") apply false
    kotlin("plugin.compose") apply false
    kotlin("plugin.serialization") apply false
    id("com.android.application") apply false
    id("com.android.library") apply false
    id("org.jetbrains.compose") apply false
    id("app.cash.sqldelight") apply false
}
