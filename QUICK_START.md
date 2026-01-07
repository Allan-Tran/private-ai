# Quick Start Guide - Private AI Vault

Get your inference engine running in **30 minutes**.

---

## ⚡ Fast Track (Desktop JVM Only)

**Skip building llama.cpp!** The Desktop implementation uses a pre-built library.

### 1. Download a Model (5 min)

```bash
mkdir models
cd models

# Download TinyLlama (669 MB - fast for testing)
# Visit: https://huggingface.co/TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF
# Download: tinyllama-1.1b-chat-v1.0.Q4_K_M.gguf
```

### 2. Create Test File (2 min)

Create `test_inference.kt`:

```kotlin
import com.privateai.vault.inference.*
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    val engine = createInferenceEngine()

    // Load model
    println("Loading model...")
    val modelPath = "models/tinyllama-1.1b-chat-v1.0.Q4_K_M.gguf"
    val params = ModelParams(
        contextSize = 1024,
        gpuLayers = 0,  // CPU only
        threads = 4
    )

    val loaded = engine.loadModel(modelPath, params)

    if (!loaded) {
        println("Failed to load model!")
        return@runBlocking
    }

    println("Model loaded! Info: ${engine.getModelInfo()}")

    // Generate text
    println("\nGenerating response...\n")
    engine.generateStream(
        "What is artificial intelligence?",
        GenerationParams(maxTokens = 100)
    ).collect { token ->
        print(token)
    }

    println("\n\nDone!")
    engine.unloadModel()
}
```

### 3. Build and Run (3 min)

```bash
# Sync dependencies
./gradlew --refresh-dependencies

# Build
./gradlew :core:inference-engine:build

# Run test
./gradlew :core:inference-engine:desktopRun
```

**Expected Output**:
```
[Desktop] ✅ Model loaded: models/tinyllama-1.1b-chat-v1.0.Q4_K_M.gguf
[Desktop]    Context size: 1024
[Desktop]    GPU layers: 0
Model loaded! Info: ModelInfo(name=GGUF Model (JVM), ...)

Generating response...

Artificial intelligence (AI) refers to the simulation of human intelligence...
[continues generating...]

[Desktop] ✅ Generated 100 tokens
Done!
```

---

## 🍎 Native Track (iOS/macOS with Metal)

For Metal/Neural Engine acceleration on Apple Silicon.

### 1. Build llama.cpp (15 min)

```bash
# Clone
git clone https://github.com/ggerganov/llama.cpp.git
cd llama.cpp

# Build for macOS ARM64
mkdir build-macos-arm64 && cd build-macos-arm64
cmake .. -DCMAKE_BUILD_TYPE=Release \
         -DCMAKE_OSX_ARCHITECTURES=arm64 \
         -DLLAMA_METAL=ON
cmake --build . --config Release

# Copy artifacts (from llama.cpp directory)
cd ..
mkdir -p ../shared/src/nativeInterop/cinterop/libs/macos-arm64
mkdir -p ../shared/src/nativeInterop/cinterop/headers

cp build-macos-arm64/libllama.a ../shared/src/nativeInterop/cinterop/libs/macos-arm64/
cp llama.h ../shared/src/nativeInterop/cinterop/headers/
```

### 2. Generate cinterop Bindings (2 min)

```bash
# From project root
./gradlew :core:inference-engine:cinteropLlamaMacosArm64

# Verify
./gradlew :core:inference-engine:verifyCinterop
```

**Expected Output**:
```
✅ cinterop stubs found at: core/inference-engine/build/generated/source/cinterop
```

### 3. Test Native Implementation (5 min)

```bash
# Build native binary
./gradlew :core:inference-engine:macosArm64Binaries

# Run native test
./gradlew :core:inference-engine:macosArm64Test
```

---

## 🎯 Next Steps

### For Boxing Coach Demo

1. **Test Session Analyst Feature**:
   ```bash
   ./gradlew :features:session-analyst:build
   ```

2. **Create Desktop App** (see [INITIALIZATION_PLAN.md](INITIALIZATION_PLAN.md) Phase 5)

3. **Test RAG Pipeline**:
   - Initialize vector store
   - Add session notes
   - Query with context

### For Production

1. **Download Better Model**:
   ```bash
   # Phi-3-mini-4k (2.3 GB - balanced)
   # Visit: https://huggingface.co/microsoft/Phi-3-mini-4k-instruct-gguf
   # Download: Phi-3-mini-4k-instruct-q4.gguf
   ```

2. **Optimize Performance**:
   ```kotlin
   ModelParams(
       contextSize = 2048,
       gpuLayers = 32,  // Offload to Metal
       threads = 8,     // Use more CPU cores
       useMmap = true
   )
   ```

3. **Build UI** (see [features/session-analyst/](features/session-analyst/))

---

## 🐛 Troubleshooting

### Desktop: "Cannot find de.kherud.llama"

```bash
# Force dependency refresh
./gradlew --refresh-dependencies
./gradlew clean build
```

### Native: "Cannot find llama.h"

```bash
# Check file exists
ls -la shared/src/nativeInterop/cinterop/headers/llama.h

# If missing, copy from llama.cpp
cp llama.cpp/llama.h shared/src/nativeInterop/cinterop/headers/
```

### Model: "Failed to load model"

```bash
# Check model file
ls -lh models/*.gguf

# Verify path in code
# Must be absolute path or relative to working directory
```

### Performance: "Inference is slow"

```kotlin
// Enable GPU acceleration (macOS/Metal)
ModelParams(
    gpuLayers = 32,  // Offload layers to GPU
    threads = 8      // Use more CPU threads
)
```

---

## 📚 Documentation

- [README.md](README.md) - Project overview
- [ARCHITECTURE.md](ARCHITECTURE.md) - Deep technical details
- [INITIALIZATION_PLAN.md](INITIALIZATION_PLAN.md) - Full setup guide (10 phases)
- [CRITICAL_FIXES_APPLIED.md](CRITICAL_FIXES_APPLIED.md) - Recent fixes
- [LLAMA_CPP_BRIDGE_GUIDE.md](LLAMA_CPP_BRIDGE_GUIDE.md) - C interop details

---

## 🎯 Recommended Path

**For Quick Demo**: Desktop Track (30 min) → Test inference → Build UI

**For Production**: Native Track (60 min) → Metal acceleration → Full app

---

## ✅ Success Criteria

You're ready to proceed when:

- [ ] Model loads without errors
- [ ] Generates coherent text
- [ ] Embeddings can be created
- [ ] No memory leaks (test with Instruments on macOS)
- [ ] Performance is acceptable (adjust gpu_layers/threads)

---

**Ready?** Start with the **Desktop Fast Track** to see results in 30 minutes! 🚀
