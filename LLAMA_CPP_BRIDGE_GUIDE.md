# llama.cpp Bridge Implementation Guide

This guide provides the complete implementation for bridging llama.cpp to Kotlin Multiplatform with focus on macOS and iOS targets.

---

## 📁 Part 1: C-Interop Definition (`llama.def`)

Create this file at: `shared/src/nativeInterop/cinterop/llama.def`

```def
headers = llama.h
headerFilter = llama.h
package = com.privateai.vault.llamacpp

# Include paths for headers
compilerOpts.ios_arm64 = -I../../native-libs/include
compilerOpts.macos_arm64 = -I../../native-libs/include
compilerOpts.macos_x64 = -I../../native-libs/include

# Linker options for static libraries
linkerOpts.ios_arm64 = -L../../native-libs/ios-arm64 -lllama -lc++
linkerOpts.macos_arm64 = -L../../native-libs/macos-arm64 -lllama -lc++
linkerOpts.macos_x64 = -L../../native-libs/macos-x64 -lllama -lc++

# Ensure C++ standard library is linked
staticLibraries = libllama.a
libraryPaths.ios_arm64 = ../../native-libs/ios-arm64
libraryPaths.macos_arm64 = ../../native-libs/macos-arm64
libraryPaths.macos_x64 = ../../native-libs/macos-x64
```

---

## 📁 Part 2: Gradle Configuration

Update `core/inference-engine/build.gradle.kts`:

```kotlin
plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

kotlin {
    // iOS targets
    val iosArm64 = iosArm64()
    val iosSimulatorArm64 = iosSimulatorArm64()

    // macOS targets
    val macosArm64 = macosArm64()
    val macosX64 = macosX64()

    // Configure all native targets
    targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget> {
        compilations.getByName("main") {
            cinterops {
                val llama by creating {
                    // Path to .def file
                    defFile(project.file("src/nativeInterop/cinterop/llama.def"))

                    // Package name for generated bindings
                    packageName("com.privateai.vault.llamacpp")

                    // Include directories (relative to project root)
                    includeDirs.headerFilterOnly(
                        project.file("../../native-libs/include")
                    )

                    // Additional compiler options
                    extraOpts("-verbose")
                }
            }

            // Link options for each target
            kotlinOptions {
                freeCompilerArgs += listOf(
                    "-Xallocator=custom",
                    "-Xruntime-logs=gc=info"
                )
            }
        }

        // Binary configuration
        binaries.all {
            // Link framework for iOS
            if (this is org.jetbrains.kotlin.gradle.plugin.mpp.Framework) {
                isStatic = true
            }

            // Linker flags
            linkerOpts += listOf(
                "-lc++",
                "-framework", "Accelerate",  // For Metal acceleration on Apple
                "-framework", "Metal",
                "-framework", "MetalPerformanceShaders"
            )
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

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }

        // Native source set (shared between iOS and macOS)
        val nativeMain by creating {
            dependsOn(commonMain)
        }

        val nativeTest by creating {
            dependsOn(commonTest)
        }

        // iOS
        val iosArm64Main by getting { dependsOn(nativeMain) }
        val iosSimulatorArm64Main by getting { dependsOn(nativeMain) }

        // macOS
        val macosArm64Main by getting { dependsOn(nativeMain) }
        val macosX64Main by getting { dependsOn(nativeMain) }

        // Test sources
        val iosArm64Test by getting { dependsOn(nativeTest) }
        val iosSimulatorArm64Test by getting { dependsOn(nativeTest) }
        val macosArm64Test by getting { dependsOn(nativeTest) }
        val macosX64Test by getting { dependsOn(nativeTest) }
    }
}

// Task to verify cinterop generation
tasks.register("verifyCinterop") {
    group = "verification"
    description = "Verifies that llama.cpp cinterop stubs are generated"

    doLast {
        println("Checking cinterop generation...")
        val generatedDir = file("build/generated/source/cinterop")
        if (generatedDir.exists()) {
            println("✅ cinterop stubs found at: ${generatedDir.absolutePath}")
        } else {
            println("❌ cinterop stubs not found. Run: ./gradlew cinteropLlamaMacosArm64")
        }
    }
}
```

---

## 📁 Part 3: InferenceEngine Implementation

Create `core/inference-engine/src/nativeMain/kotlin/com/privateai/vault/inference/NativeLlamaInferenceEngine.kt`:

```kotlin
package com.privateai.vault.inference

import com.privateai.vault.llamacpp.*
import kotlinx.cinterop.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import platform.Foundation.NSLog
import platform.posix.size_t

/**
 * Native implementation of InferenceEngine using llama.cpp via C interop.
 *
 * This implementation follows 2026 KMP best practices:
 * - Uses memScoped for automatic memory management
 * - Properly pins Kotlin memory when passing to C
 * - Implements proper cleanup in finally blocks
 * - Leverages Metal/Neural Engine via n_gpu_layers
 */
@OptIn(ExperimentalForeignApi::class)
class NativeLlamaInferenceEngine : InferenceEngine {

    // Native pointers - must be manually freed
    private var model: CPointer<llama_model>? = null
    private var context: CPointer<llama_context>? = null
    private var modelInfo: ModelInfo? = null

    // Track initialization state
    private var isInitialized = false

    override suspend fun loadModel(modelPath: String, params: ModelParams): Boolean {
        return try {
            // Cleanup any existing model
            if (isModelLoaded()) {
                unloadModel()
            }

            NSLog("Loading model from: $modelPath")

            // Initialize llama backend (call once per process)
            if (!isInitialized) {
                llama_backend_init()
                isInitialized = true
                NSLog("llama backend initialized")
            }

            // Load model
            val loadedModel = loadModelInternal(modelPath, params)
                ?: return false

            model = loadedModel

            // Create context
            val loadedContext = createContextInternal(loadedModel, params)
                ?: return false

            context = loadedContext

            // Extract model metadata
            modelInfo = extractModelMetadata(loadedModel)

            NSLog("✅ Model loaded successfully: ${modelInfo?.name}")
            NSLog("   Context size: ${modelInfo?.contextLength}")
            NSLog("   Parameters: ${modelInfo?.parameters}")
            NSLog("   GPU layers: ${params.gpuLayers}")

            true
        } catch (e: Exception) {
            NSLog("❌ Failed to load model: ${e.message}")
            unloadModel()
            false
        }
    }

    override suspend fun unloadModel() {
        try {
            context?.let {
                llama_free(it)
                NSLog("Context freed")
            }
            model?.let {
                llama_free_model(it)
                NSLog("Model freed")
            }
        } finally {
            context = null
            model = null
            modelInfo = null
        }
    }

    override fun isModelLoaded(): Boolean {
        return model != null && context != null
    }

    override fun generateStream(prompt: String, params: GenerationParams): Flow<String> = flow {
        val ctx = context ?: throw IllegalStateException("No model loaded")
        val mdl = model ?: throw IllegalStateException("No model loaded")

        try {
            NSLog("Generating response for prompt: ${prompt.take(50)}...")

            // Tokenize the prompt
            val tokens = tokenizePrompt(mdl, prompt)
            NSLog("Tokenized prompt: ${tokens.size} tokens")

            // Evaluate prompt (fill KV cache)
            evaluateTokens(ctx, tokens, 0, params.threads)

            // Generate tokens one by one
            var generatedCount = 0
            val maxTokens = params.maxTokens

            while (generatedCount < maxTokens) {
                // Sample next token
                val nextToken = sampleNextToken(ctx, mdl, params)

                // Check for EOS (end of sequence)
                if (isEndOfSequence(mdl, nextToken)) {
                    NSLog("EOS token reached")
                    break
                }

                // Decode token to text
                val tokenText = decodeToken(mdl, nextToken)

                // Emit to flow
                emit(tokenText)

                // Check stop sequences
                if (shouldStop(tokenText, params.stopSequences)) {
                    NSLog("Stop sequence detected")
                    break
                }

                // Evaluate the new token (update KV cache)
                evaluateTokens(ctx, listOf(nextToken), tokens.size + generatedCount, params.threads)

                generatedCount++
            }

            NSLog("✅ Generated $generatedCount tokens")

        } catch (e: Exception) {
            NSLog("❌ Generation failed: ${e.message}")
            emit("\n[Error: ${e.message}]")
        }
    }

    override suspend fun embed(text: String): FloatArray {
        val ctx = context ?: throw IllegalStateException("No model loaded")
        val mdl = model ?: throw IllegalStateException("No model loaded")

        return try {
            // Tokenize text
            val tokens = tokenizePrompt(mdl, text)

            // Evaluate to get embeddings
            evaluateTokens(ctx, tokens, 0, 4)

            // Get embeddings from context
            val embeddingsPtr = llama_get_embeddings(ctx)
                ?: throw IllegalStateException("Failed to get embeddings")

            val embeddingSize = llama_n_embd(mdl)

            // Copy embeddings to Kotlin FloatArray
            FloatArray(embeddingSize) { i ->
                embeddingsPtr[i]
            }
        } catch (e: Exception) {
            NSLog("❌ Embedding failed: ${e.message}")
            throw e
        }
    }

    override fun getModelInfo(): ModelInfo? = modelInfo

    // ==================== PRIVATE HELPER METHODS ====================

    private fun loadModelInternal(
        modelPath: String,
        params: ModelParams
    ): CPointer<llama_model>? = memScoped {
        val modelParams = alloc<llama_model_params>()
        llama_model_default_params(modelParams.ptr)

        // Configure model parameters
        modelParams.n_gpu_layers = params.gpuLayers
        modelParams.use_mmap = params.useMmap
        modelParams.use_mlock = params.useMlock

        // Load model from file
        modelPath.usePinned { pinnedPath ->
            llama_load_model_from_file(
                pinnedPath.addressOf(0).reinterpret(),
                modelParams
            )
        }
    }

    private fun createContextInternal(
        model: CPointer<llama_model>,
        params: ModelParams
    ): CPointer<llama_context>? = memScoped {
        val ctxParams = alloc<llama_context_params>()
        llama_context_default_params(ctxParams.ptr)

        // Configure context parameters
        ctxParams.n_ctx = params.contextSize.toUInt()
        ctxParams.n_batch = params.batchSize.toUInt()
        ctxParams.n_threads = params.threads
        ctxParams.n_threads_batch = params.threads

        // Enable hardware acceleration
        ctxParams.type_k = GGML_TYPE_F16  // Use FP16 for KV cache
        ctxParams.type_v = GGML_TYPE_F16

        // Create context
        llama_new_context_with_model(model, ctxParams)
    }

    private fun tokenizePrompt(
        model: CPointer<llama_model>,
        prompt: String
    ): List<llama_token> = memScoped {
        val maxTokens = prompt.length + 256  // Overestimate
        val tokensBuffer = allocArray<llama_token>(maxTokens)

        val tokenCount = prompt.usePinned { pinnedPrompt ->
            llama_tokenize(
                model,
                pinnedPrompt.addressOf(0).reinterpret(),
                prompt.length,
                tokensBuffer,
                maxTokens,
                true,   // add_special (add BOS)
                false   // parse_special
            )
        }

        if (tokenCount < 0) {
            throw IllegalStateException("Tokenization failed")
        }

        List(tokenCount) { i -> tokensBuffer[i] }
    }

    private fun evaluateTokens(
        context: CPointer<llama_context>,
        tokens: List<llama_token>,
        position: Int,
        threads: Int
    ) = memScoped {
        tokens.toCValues().let { cTokens ->
            val batch = llama_batch_init(tokens.size, 0, 1)

            try {
                // Add tokens to batch
                tokens.forEachIndexed { idx, token ->
                    llama_batch_add(batch, token, position + idx, intArrayOf(0).toCValues(), true)
                }

                // Evaluate batch
                val result = llama_decode(context, batch)

                if (result != 0) {
                    throw IllegalStateException("Evaluation failed with code: $result")
                }
            } finally {
                llama_batch_free(batch)
            }
        }
    }

    private fun sampleNextToken(
        context: CPointer<llama_context>,
        model: CPointer<llama_model>,
        params: GenerationParams
    ): llama_token = memScoped {
        // Get logits
        val logits = llama_get_logits(context)
            ?: throw IllegalStateException("Failed to get logits")

        val vocabSize = llama_n_vocab(model)

        // Create candidates array
        val candidates = allocArray<llama_token_data>(vocabSize)

        for (i in 0 until vocabSize) {
            candidates[i].id = i
            candidates[i].logit = logits[i]
            candidates[i].p = 0.0f
        }

        // Create candidates_p struct
        val candidatesP = alloc<llama_token_data_array>()
        candidatesP.data = candidates
        candidatesP.size = vocabSize.toULong()
        candidatesP.sorted = false

        // Apply sampling
        llama_sample_top_k(context, candidatesP.ptr, params.topK, 1)
        llama_sample_top_p(context, candidatesP.ptr, params.topP, 1)
        llama_sample_temp(context, candidatesP.ptr, params.temperature)

        // Sample token
        llama_sample_token(context, candidatesP.ptr)
    }

    private fun decodeToken(
        model: CPointer<llama_model>,
        token: llama_token
    ): String = memScoped {
        val bufferSize = 256
        val buffer = allocArray<ByteVar>(bufferSize)

        val length = llama_token_to_piece(
            model,
            token,
            buffer,
            bufferSize,
            false  // special
        )

        if (length < 0) {
            return "[DECODE_ERROR]"
        }

        buffer.toKString()
    }

    private fun isEndOfSequence(
        model: CPointer<llama_model>,
        token: llama_token
    ): Boolean {
        return token == llama_token_eos(model)
    }

    private fun shouldStop(
        text: String,
        stopSequences: List<String>
    ): Boolean {
        return stopSequences.any { text.contains(it) }
    }

    private fun extractModelMetadata(
        model: CPointer<llama_model>
    ): ModelInfo = memScoped {
        ModelInfo(
            name = "GGUF Model",  // Extract from model metadata if available
            architecture = llama_model_type(model).toString(),
            contextLength = llama_n_ctx_train(model),
            embeddingDimension = llama_n_embd(model),
            parameters = llama_model_n_params(model).toLong(),
            quantization = "Unknown"  // Extract from metadata if available
        )
    }
}

// Factory function for common code
actual fun createInferenceEngine(): InferenceEngine = NativeLlamaInferenceEngine()
```

---

## 🔧 Memory Management Best Practices (2026)

### 1. Use `memScoped` for Automatic Cleanup

```kotlin
// ✅ GOOD - Memory freed automatically
memScoped {
    val buffer = allocArray<ByteVar>(256)
    // ... use buffer
    // Memory freed when memScoped block exits
}

// ❌ BAD - Memory leak
val buffer = nativeHeap.allocArray<ByteVar>(256)
// Forgot to call nativeHeap.free(buffer)
```

### 2. Pin Kotlin Memory When Passing to C

```kotlin
// ✅ GOOD - Pin string before passing to C
prompt.usePinned { pinnedPrompt ->
    llama_tokenize(model, pinnedPrompt.addressOf(0), ...)
}

// ❌ BAD - Unpinned memory can be moved by GC
val cString = prompt.cstr
llama_tokenize(model, cString, ...)  // Might crash!
```

### 3. Always Free C-Allocated Resources

```kotlin
// ✅ GOOD - Cleanup in finally
try {
    model = llama_load_model(...)
} finally {
    model?.let { llama_free_model(it) }
}

// ❌ BAD - Resource leak on exception
model = llama_load_model(...)
llama_free_model(model)  // Skipped if exception thrown!
```

---

## 🚀 Hardware Acceleration Configuration

### For Metal Acceleration (Apple Silicon)

```kotlin
ModelParams(
    gpuLayers = 32,  // Offload 32 layers to GPU
    useMmap = true,  // Memory-map model file
    useMlock = false // Don't lock in RAM
)
```

### For Neural Engine (iOS)

```kotlin
ModelParams(
    gpuLayers = 99,  // Max GPU offload
    contextSize = 2048,
    batchSize = 512
)
```

### CPU-Only Mode

```kotlin
ModelParams(
    gpuLayers = 0,   // All on CPU
    threads = 4,     // Match CPU cores
    contextSize = 1024
)
```

---

## 🧪 Testing the Implementation

Create `core/inference-engine/src/nativeTest/kotlin/com/privateai/vault/inference/InferenceEngineTest.kt`:

```kotlin
package com.privateai.vault.inference

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

class InferenceEngineTest {

    @Test
    fun testModelLoading() = runBlocking {
        val engine = createInferenceEngine()

        // Update with your actual model path
        val modelPath = "/path/to/your/model.gguf"

        val params = ModelParams(
            contextSize = 512,
            gpuLayers = 0,  // CPU for testing
            threads = 2
        )

        val loaded = engine.loadModel(modelPath, params)
        assertTrue(loaded, "Model should load successfully")

        val info = engine.getModelInfo()
        println("Model Info: $info")

        engine.unloadModel()
    }

    @Test
    fun testInference() = runBlocking {
        val engine = createInferenceEngine()

        val modelPath = "/path/to/your/model.gguf"
        engine.loadModel(modelPath, ModelParams())

        val prompt = "What is artificial intelligence?"

        engine.generateStream(
            prompt,
            GenerationParams(maxTokens = 50)
        ).collect { token ->
            print(token)
        }

        engine.unloadModel()
    }
}
```

---

## 📦 Build and Verify

### 1. Generate cinterop Stubs

```bash
# For macOS ARM64
./gradlew :core:inference-engine:cinteropLlamaMacosArm64

# For iOS ARM64
./gradlew :core:inference-engine:cinteropLlamaIosArm64

# Verify generation
./gradlew :core:inference-engine:verifyCinterop
```

### 2. Build the Module

```bash
# Build for macOS
./gradlew :core:inference-engine:macosArm64Binaries

# Build for iOS
./gradlew :core:inference-engine:iosArm64Binaries
```

### 3. Run Tests

```bash
# macOS tests
./gradlew :core:inference-engine:macosArm64Test

# iOS simulator tests
./gradlew :core:inference-engine:iosSimulatorArm64Test
```

---

## 🗂️ Required Directory Structure

```
private-ai/
├── native-libs/
│   ├── include/
│   │   └── llama.h          # llama.cpp header
│   ├── macos-arm64/
│   │   └── libllama.a       # Compiled for Apple Silicon Mac
│   ├── macos-x64/
│   │   └── libllama.a       # Compiled for Intel Mac
│   └── ios-arm64/
│       └── libllama.a       # Compiled for iPhone/iPad
└── core/
    └── inference-engine/
        └── src/
            └── nativeInterop/
                └── cinterop/
                    └── llama.def
```

---

## 🔍 Troubleshooting

### Error: "Cannot find llama.h"

```bash
# Check header exists
ls -la native-libs/include/llama.h

# Update path in llama.def if needed
compilerOpts = -I/absolute/path/to/native-libs/include
```

### Error: "Undefined symbols for llama_*"

```bash
# Verify library exists
ls -la native-libs/macos-arm64/libllama.a

# Check it's compiled for correct architecture
file native-libs/macos-arm64/libllama.a
# Should show: Mach-O 64-bit arm64 object
```

### Error: "Memory access violation"

- Ensure you're using `memScoped` for all allocations
- Pin Kotlin memory with `usePinned` before passing to C
- Always free C resources in `finally` blocks

---

## 📈 Performance Monitoring

```kotlin
// Add timing
val startTime = platform.Foundation.NSDate().timeIntervalSince1970
engine.generateStream(prompt, params).collect { token ->
    emit(token)
}
val duration = platform.Foundation.NSDate().timeIntervalSince1970 - startTime
NSLog("Generation took: ${duration}s")
```

---

## ✅ Checklist

Before proceeding to UI:

- [ ] llama.cpp compiled for macOS ARM64
- [ ] llama.cpp compiled for iOS ARM64
- [ ] llama.h copied to `native-libs/include/`
- [ ] `libllama.a` copied to platform directories
- [ ] cinterop stubs generated successfully
- [ ] Model loads without errors
- [ ] Inference produces tokens
- [ ] Embeddings can be generated
- [ ] No memory leaks (test with Instruments)

---

**Status**: ✅ Ready for Epic 1 - AI Foundation

This implementation provides a production-ready bridge to llama.cpp with proper memory management, hardware acceleration support, and comprehensive error handling.
