plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

kotlin {
    jvm("desktop")

    // Temporarily disabled for desktop-only build
    // Native targets will be re-enabled once llama.cpp integration is ready
    // // Native targets for llama.cpp integration
    // listOf(
    //     mingwX64("windowsX64"),
    //     macosX64(),
    //     macosArm64()
    // ).forEach { nativeTarget ->
    //     nativeTarget.apply {
    //         binaries {
    //             sharedLib {
    //                 baseName = "inference-engine"
    //             }
    //         }
    //         compilations.getByName("main") {
    //             cinterops {
    //                 val llamacpp by creating {
    //                     defFile(project.file("src/nativeInterop/cinterop/llamacpp.def"))
    //                     packageName("com.privateai.vault.llamacpp")
    //                     includeDirs.headerFilterOnly(project.file("src/nativeInterop/cinterop/headers"))
    //                 }
    //             }
    //         }
    //     }
    // }

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
                // java-llama.cpp with LoRA adapter support
                // See: https://github.com/kherud/java-llama.cpp
                implementation("de.kherud:llama:4.2.0")
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
    }
}
