plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

kotlin {
    jvm("desktop")

    listOf(
        mingwX64("windowsX64"),
        macosX64(),
        macosArm64()
    ).forEach { nativeTarget ->
        nativeTarget.apply {
            binaries {
                sharedLib {
                    baseName = "inference-engine"
                }
            }
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":shared"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
            }
        }

        val desktopMain by getting {
            dependsOn(commonMain)
            dependencies {
                // Use java-llama.cpp for JVM - pre-built JNI bindings
                // This avoids having to write JNI bridge manually
                implementation("de.kherud:llama:3.0.0")
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
