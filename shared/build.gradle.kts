plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.android.library")
}

kotlin {
    androidTarget {
        compilations.all {
            kotlinOptions {
                jvmTarget = "17"
            }
        }
    }

    jvm("desktop") {
        compilations.all {
            kotlinOptions {
                jvmTarget = "17"
            }
        }
    }

    // Native targets for llama.cpp integration
    listOf(
        mingwX64("windowsX64"),
        macosX64(),
        macosArm64()
    ).forEach { nativeTarget ->
        nativeTarget.apply {
            compilations.getByName("main") {
                cinterops {
                    val llamacpp by creating {
                        defFile(project.file("src/nativeInterop/cinterop/llamacpp.def"))
                        packageName("com.privateai.vault.llamacpp")
                        includeDirs.headerFilterOnly(project.file("src/nativeInterop/cinterop/headers"))
                    }
                }
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

        val androidMain by getting {
            dependencies {
                implementation("app.cash.sqldelight:android-driver:2.0.1")
            }
        }

        val desktopMain by getting {
            dependencies {
                implementation("app.cash.sqldelight:sqlite-driver:2.0.1")
            }
        }

        val nativeMain by creating {
            dependsOn(commonMain)
        }

        val windowsX64Main by getting {
            dependsOn(nativeMain)
        }

        val macosX64Main by getting {
            dependsOn(nativeMain)
        }

        val macosArm64Main by getting {
            dependsOn(nativeMain)
        }
    }
}

android {
    namespace = "com.privateai.vault.shared"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
