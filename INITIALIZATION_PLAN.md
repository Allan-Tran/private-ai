# Private AI Vault - Project Initialization Plan

## 🎯 Overview

This document provides a step-by-step plan to initialize and build the Private AI Vault application using Kotlin Multiplatform (KMP), llama.cpp for local inference, and Vertical Slice Architecture.

**Target Platforms:**
- ✅ Windows Desktop
- ✅ macOS Desktop
- ✅ Android

**Core Philosophy:** Sovereign Data - No data ever leaves the device.

---

## 📋 Prerequisites

### Required Tools

1. **JDK 17 or higher**
   - Download: https://adoptium.net/
   - Verify: `java -version`

2. **Kotlin 1.9.21+**
   - Installed via Gradle (automatic)

3. **Android SDK** (for Android target)
   - Android Studio or command-line tools
   - Min SDK: 26 (Android 8.0)
   - Target SDK: 34

4. **CMake** (for llama.cpp compilation)
   - Windows: `choco install cmake`
   - macOS: `brew install cmake`
   - Verify: `cmake --version`

5. **C++ Compiler**
   - Windows: MSVC (Visual Studio) or MinGW-w64
   - macOS: Xcode Command Line Tools (`xcode-select --install`)

6. **Git**
   - For cloning llama.cpp
   - Verify: `git --version`

### Development Environment

**Recommended IDE:** IntelliJ IDEA or Android Studio with KMP plugin

For **VS Code** (your setup):
- Install Kotlin Language Server
- Install Gradle extension
- Note: Limited KMP support - consider IntelliJ for better experience

---

## 🚀 Phase 1: Build llama.cpp

llama.cpp is the inference engine. We need to compile it as a native library.

### Step 1.1: Clone llama.cpp

```bash
cd c:\Users\Allan\private-ai
git clone https://github.com/ggerganov/llama.cpp.git
cd llama.cpp
```

### Step 1.2: Build for Windows (MSVC)

```bash
mkdir build
cd build
cmake .. -DBUILD_SHARED_LIBS=ON
cmake --build . --config Release
```

This produces `llama.dll` and `llama.lib` in `build/bin/Release/`

### Step 1.3: Build for macOS (if applicable)

```bash
mkdir build
cd build
cmake .. -DBUILD_SHARED_LIBS=ON
cmake --build . --config Release
```

This produces `libllama.dylib` in `build/bin/`

### Step 1.4: Copy Build Artifacts

```bash
# Create native libs directory
mkdir -p c:\Users\Allan\private-ai\native-libs\windows-x64
mkdir -p c:\Users\Allan\private-ai\native-libs\macos-x64
mkdir -p c:\Users\Allan\private-ai\native-libs\macos-arm64

# Copy Windows artifacts
cp build/bin/Release/llama.dll c:\Users\Allan\private-ai\native-libs\windows-x64\
cp build/bin/Release/llama.lib c:\Users\Allan\private-ai\native-libs\windows-x64\

# Copy headers
mkdir -p c:\Users\Allan\private-ai\shared\src\nativeInterop\cinterop\headers
cp llama.h c:\Users\Allan\private-ai\shared\src\nativeInterop\cinterop\headers\
```

---

## 🔧 Phase 2: Configure C Interop

### Step 2.1: Update llama.cpp.def

Edit `shared/src/nativeInterop/cinterop/llamacpp.def`:

```
headers = llama.h
headerFilter = llama.h
package = com.privateai.vault.llamacpp

compilerOpts.mingw = -Ic:/Users/Allan/private-ai/shared/src/nativeInterop/cinterop/headers
linkerOpts.mingw = -Lc:/Users/Allan/private-ai/native-libs/windows-x64 -lllama

compilerOpts.macos = -I/path/to/private-ai/shared/src/nativeInterop/cinterop/headers
linkerOpts.macos = -L/path/to/private-ai/native-libs/macos-x64 -lllama
```

### Step 2.2: Verify cinterop Generation

```bash
cd c:\Users\Allan\private-ai
.\gradlew :shared:cinteropLlamacppWindowsX64
```

This generates Kotlin bindings from llama.cpp C headers.

---

## 📦 Phase 3: Download SQLite Vector Extension

### Step 3.1: Get sqlite-vec

```bash
cd c:\Users\Allan\private-ai
git clone https://github.com/asg017/sqlite-vec.git
cd sqlite-vec
```

### Step 3.2: Build Extension

Follow the project's build instructions for your platform. You need `vec0.dll` (Windows) or `vec0.dylib` (macOS).

### Step 3.3: Copy Extension

```bash
mkdir -p c:\Users\Allan\private-ai\native-libs\sqlite-extensions
cp vec0.dll c:\Users\Allan\private-ai\native-libs\sqlite-extensions\
```

---

## 🏗️ Phase 4: Build the Project

### Step 4.1: Sync Gradle Dependencies

```bash
cd c:\Users\Allan\private-ai
.\gradlew --refresh-dependencies
```

### Step 4.2: Build Shared Module

```bash
.\gradlew :shared:build
```

This compiles the KMP shared code for all targets.

### Step 4.3: Build Inference Engine

```bash
.\gradlew :core:inference-engine:build
```

### Step 4.4: Build Vector Store

```bash
.\gradlew :core:vector-store:build
```

### Step 4.5: Build Session Analyst Feature

```bash
.\gradlew :features:session-analyst:build
```

---

## 🖥️ Phase 5: Create Desktop Application

### Step 5.1: Create Desktop Module

Create `desktopApp/build.gradle.kts`:

```kotlin
plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":core:inference-engine"))
    implementation(project(":core:vector-store"))
    implementation(project(":features:session-analyst"))

    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
}

compose.desktop {
    application {
        mainClass = "com.privateai.vault.desktop.MainKt"

        nativeDistributions {
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb
            )
            packageName = "PrivateAIVault"
            packageVersion = "1.0.0"

            windows {
                iconFile.set(project.file("icon.ico"))
            }
        }
    }
}
```

### Step 5.2: Create Main Entry Point

Create `desktopApp/src/main/kotlin/Main.kt`:

```kotlin
package com.privateai.vault.desktop

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.material3.MaterialTheme
import com.privateai.vault.features.sessionanalyst.ui.SessionAnalystScreen

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Private AI Vault"
    ) {
        MaterialTheme {
            // Initialize and show Session Analyst
            SessionAnalystScreen(viewModel = createViewModel())
        }
    }
}
```

### Step 5.3: Run Desktop App

```bash
.\gradlew :desktopApp:run
```

---

## 📱 Phase 6: Create Android Application

### Step 6.1: Create Android Module

The `androidApp` module is already defined in `settings.gradle.kts`.

Create `androidApp/build.gradle.kts`:

```kotlin
plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.compose")
}

android {
    namespace = "com.privateai.vault.android"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.privateai.vault"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":core:inference-engine"))
    implementation(project(":core:vector-store"))
    implementation(project(":features:session-analyst"))

    implementation("androidx.activity:activity-compose:1.8.2")
    implementation(compose.material3)
}
```

### Step 6.2: Bundle Native Libraries

Add native libraries to Android app:

```
androidApp/
  src/
    main/
      jniLibs/
        arm64-v8a/
          libllama.so
        armeabi-v7a/
          libllama.so
```

You'll need to cross-compile llama.cpp for Android using Android NDK.

---

## 📥 Phase 7: Download AI Models

### Step 7.1: Choose Models

For the Private AI Vault, use Small Language Models (SLMs) that run efficiently on device:

**Recommended Models:**
- **Phi-3-mini-4k-instruct** (3.8B params, ~2.3GB GGUF Q4_K_M)
- **TinyLlama-1.1B** (1.1B params, ~669MB GGUF Q4_K_M)
- **Mistral-7B-Instruct** (7B params, ~4.1GB GGUF Q4_K_M) - for more powerful devices

### Step 7.2: Download GGUF Models

```bash
# Create models directory
mkdir c:\Users\Allan\private-ai\models

# Download from Hugging Face (example)
# Visit: https://huggingface.co/models?library=gguf
# Download your chosen model's GGUF file
```

### Step 7.3: Configure Model Path

In your app, configure the model path:

```kotlin
val modelPath = "c:/Users/Allan/private-ai/models/phi-3-mini-4k-instruct-q4.gguf"
```

---

## 🧪 Phase 8: Testing the Setup

### Step 8.1: Create Test Script

Create `desktopApp/src/test/kotlin/InferenceTest.kt`:

```kotlin
import com.privateai.vault.inference.*
import kotlinx.coroutines.runBlocking
import org.junit.Test

class InferenceTest {
    @Test
    fun testModelLoading() = runBlocking {
        val engine = createInferenceEngine()

        val modelPath = "c:/Users/Allan/private-ai/models/phi-3-mini-4k-instruct-q4.gguf"
        val params = ModelParams(
            contextSize = 2048,
            threads = 4
        )

        val loaded = engine.loadModel(modelPath, params)
        assert(loaded) { "Model failed to load" }

        val info = engine.getModelInfo()
        println("Model: ${info?.name}")
        println("Context: ${info?.contextLength}")

        engine.unloadModel()
    }

    @Test
    fun testInference() = runBlocking {
        val engine = createInferenceEngine()
        engine.loadModel("path/to/model.gguf", ModelParams())

        engine.generateStream(
            "What is boxing?",
            GenerationParams(maxTokens = 100)
        ).collect { token ->
            print(token)
        }

        engine.unloadModel()
    }
}
```

### Step 8.2: Run Tests

```bash
.\gradlew :desktopApp:test
```

---

## 📚 Phase 9: Usage Example

### Step 9.1: Initialize the System

```kotlin
// 1. Create file system access
val fileSystem = FileSystemAccess()
val dataDir = fileSystem.getPrivateDataDirectory()

// 2. Initialize vector store
val vectorStore = createVectorStore("$dataDir/vectors.db")
vectorStore.initialize()

// 3. Load AI model
val inferenceEngine = createInferenceEngine()
inferenceEngine.loadModel(
    modelPath = "$dataDir/models/phi-3-mini-4k-instruct-q4.gguf",
    params = ModelParams(
        contextSize = 2048,
        threads = 4,
        gpuLayers = 0 // CPU-only for now
    )
)

// 4. Create repository and use case
val repository = SessionRepository(vectorStore, inferenceEngine)
val useCase = SessionAnalystUseCase(repository, inferenceEngine)
```

### Step 9.2: Create a Training Session

```kotlin
// Create session
val session = TrainingSession(
    id = UUID.randomUUID().toString(),
    fighterName = "Mike Tyson",
    date = "2025-01-07",
    notes = "Heavy bag and sparring session"
)

repository.createSession(session)

// Add session notes
val note = SessionNote(
    id = UUID.randomUUID().toString(),
    sessionId = session.id,
    content = """
        Training Session Notes:
        - Worked on left hook combinations
        - Power output: Excellent on heavy bag
        - Footwork: Needs improvement on lateral movement
        - Sparring: Good head movement, but dropping right hand after jabs
    """.trimIndent(),
    sourceType = "coach_notes"
)

repository.addSessionNote(note)
```

### Step 9.3: Analyze the Session

```kotlin
// Ask a question
val query = CoachQuery(
    question = "What improvements does the fighter need to work on?",
    sessionId = session.id
)

// Get streaming analysis
useCase.analyzeSession(query).collect { token ->
    print(token) // Displays AI response in real-time
}
```

---

## 🎨 Phase 10: UI Customization

### Active Desk Concept

The "Active Desk" is a workspace where users drag-and-drop files to expand AI context:

```kotlin
@Composable
fun ActiveDeskWorkspace() {
    var droppedFiles by remember { mutableStateOf<List<File>>(emptyList()) }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .onExternalDrag { /* Handle drag */ }
    ) {
        Column {
            // Drop zone
            DropZone(
                onFilesDropped = { files ->
                    droppedFiles = files
                    // Process and embed files
                }
            )

            // Active context display
            ActiveContextCards(files = droppedFiles)

            // Chat interface
            ChatInterface()
        }
    }
}
```

---

## 🔐 Security Considerations

1. **Data Encryption at Rest**
   - Encrypt SQLite database with SQLCipher
   - Encrypt model files if needed

2. **Memory Protection**
   - Clear sensitive data from memory after use
   - Use secure memory allocation for embeddings

3. **File Permissions**
   - Restrict access to private data directory
   - Validate all file inputs

---

## 📊 Performance Optimization

### Model Quantization

Use quantized models for better performance:
- **Q4_K_M**: Good balance (recommended)
- **Q5_K_M**: Better quality, slower
- **Q3_K_M**: Faster, lower quality

### Context Window Management

```kotlin
val params = ModelParams(
    contextSize = 2048, // Smaller = faster
    batchSize = 512,
    threads = Runtime.getRuntime().availableProcessors()
)
```

### Vector Search Optimization

```kotlin
// Adjust similarity threshold
vectorStore.searchSimilar(
    queryEmbedding = embedding,
    limit = 5, // Fewer results = faster
    threshold = 0.75f // Higher = more selective
)
```

---

## 🐛 Troubleshooting

### Common Issues

1. **"Cannot find llama.dll"**
   - Ensure DLL is in `native-libs` directory
   - Check path in `llamacpp.def`

2. **"cinterop failed"**
   - Verify CMake is installed
   - Check C++ compiler is available
   - Ensure headers are in correct location

3. **"SQLite extension not loaded"**
   - Verify `vec0.dll` is in correct path
   - Check `load_extension` SQL command

4. **Out of Memory**
   - Use smaller model
   - Reduce context size
   - Lower batch size

---

## 📖 Next Steps

1. **Add More Features**
   - Document Vault (file management)
   - Model Manager (download/switch models)
   - Active Desk (drag-drop interface)

2. **Improve RAG**
   - Implement chunking strategies
   - Add re-ranking
   - Implement hybrid search (keyword + vector)

3. **Add iOS Support**
   - Configure Kotlin/Native for iOS
   - Use Metal for GPU acceleration

4. **Packaging**
   - Create installers for Windows (MSI)
   - Create DMG for macOS
   - Publish to Google Play Store

---

## 📞 Support

For issues with:
- **KMP**: https://kotlinlang.org/docs/multiplatform.html
- **llama.cpp**: https://github.com/ggerganov/llama.cpp
- **sqlite-vec**: https://github.com/asg017/sqlite-vec

---

**Last Updated:** 2025-01-07

**Project Status:** ✅ Architecture Complete, Ready for Implementation
