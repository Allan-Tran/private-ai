plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    // Temporarily disabled for desktop-only build
    // id("com.android.library")
}

kotlin {
    // Temporarily disabled for desktop-only build
    // androidTarget {
    //     compilations.all {
    //         kotlinOptions {
    //             jvmTarget = "17"
    //         }
    //     }
    // }

    jvm("desktop") {
        compilations.all {
            kotlinOptions {
                jvmTarget = "17"
            }
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.5.0")

                // SQLDelight for SQLite + vector extension
                implementation("app.cash.sqldelight:runtime:2.0.1")
                implementation("app.cash.sqldelight:coroutines-extensions:2.0.1")
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
            }
        }

        // Temporarily disabled for desktop-only build
        // val androidMain by getting {
        //     dependencies {
        //         implementation("app.cash.sqldelight:android-driver:2.0.1")
        //     }
        // }

        val desktopMain by getting {
            dependencies {
                implementation("app.cash.sqldelight:sqlite-driver:2.0.1")
            }
        }
    }
}

// Temporarily disabled for desktop-only build
// android {
//     namespace = "com.privateai.vault.shared"
//     compileSdk = 34
//
//     defaultConfig {
//         minSdk = 26
//     }
//
//     compileOptions {
//         sourceCompatibility = JavaVersion.VERSION_17
//         targetCompatibility = JavaVersion.VERSION_17
//     }
// }
