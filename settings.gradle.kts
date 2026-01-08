rootProject.name = "PrivateAIVault"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

// Shared KMP module
include(":shared")

// Platform-specific modules
//include(":androidApp")
//include(":desktopApp")

// Feature slices (Vertical Slice Architecture)
include(":features:session-analyst")
//include(":features:document-vault")
//include(":features:model-manager")
//include(":features:active-desk")

// Core infrastructure
include(":core:inference-engine")
include(":core:vector-store")
//include(":core:platform-bridge")
