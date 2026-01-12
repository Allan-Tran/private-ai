plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    // Temporarily disabled for desktop-only build
    // id("com.android.library")
    id("app.cash.sqldelight") version "2.0.1"
}

kotlin {
    // Temporarily disabled for desktop-only build
    // androidTarget()
    jvm("desktop")

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":shared"))
                implementation(project(":core:inference-engine"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
                implementation("app.cash.sqldelight:runtime:2.0.1")
                implementation("app.cash.sqldelight:coroutines-extensions:2.0.1")
            }
        }

        val desktopMain by getting {
            dependencies {
                implementation("app.cash.sqldelight:sqlite-driver:2.0.1") {
                    exclude(group = "org.xerial", module = "sqlite-jdbc")
                }

                implementation("io.github.willena:sqlite-jdbc:3.50.1.0")
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
            }
        }

        val desktopTest by getting {
            dependsOn(commonTest)
        }
        // -----------------------------------
    }
}

// Temporarily disabled for desktop-only build
// android {
//     namespace = "com.privateai.vault.vectorstore"
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

sqldelight {
    databases {
        create("VectorDatabase") {
            packageName.set("com.privateai.vault.vectorstore.db")
            srcDirs.setFrom("src/commonMain/sqldelight")
        }
    }
}
