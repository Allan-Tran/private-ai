plugins {
    // Kotlin Multiplatform - use latest 2.1.x for Java 25 support
    kotlin("multiplatform") version "2.1.0" apply false
    kotlin("plugin.serialization") version "2.1.0" apply false
    kotlin("plugin.compose") version "2.1.0" apply false

    // Android
    id("com.android.application") version "8.5.0" apply false
    id("com.android.library") version "8.5.0" apply false

    // Compose Multiplatform
    id("org.jetbrains.compose") version "1.7.1" apply false
}

allprojects {
    group = "com.privateai.vault"
    version = "1.0.0"

    // Use Java 17 for all projects
    plugins.withType<JavaPlugin> {
        extensions.configure<JavaPluginExtension> {
            toolchain {
                languageVersion.set(JavaLanguageVersion.of(17))
            }
        }
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
