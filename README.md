# Private AI Vault 🔐

> **Sovereign AI for Private Data** - A Kotlin Multiplatform application that enables users to "bake" their private data into local Small Language Models (SLMs) for offline, privacy-first AI assistance.

## 🎯 Philosophy

**Sovereign Data**: Your data never leaves your device. No cloud sync, no external APIs, complete privacy.

**Active Desk**: A non-conventional UI workspace where you drag and drop files to temporarily expand the AI's context.

**Vertical Slice Architecture**: Features are organized by business capability, not by technical layers.

---

## 🏗️ Architecture

### Project Structure

```
private-ai/
├── shared/                          # Shared KMP code
│   ├── src/
│   │   ├── commonMain/             # Platform-agnostic code
│   │   ├── androidMain/            # Android-specific implementations
│   │   ├── desktopMain/            # Desktop (JVM) implementations
│   │   └── nativeMain/             # Native (Windows/macOS) implementations
│   └── nativeInterop/              # C interop for llama.cpp
│
├── core/                            # Core infrastructure
│   ├── inference-engine/           # Local LLM inference (llama.cpp bridge)
│   ├── vector-store/               # RAG with SQLite + sqlite-vec
│   └── platform-bridge/            # Platform-specific utilities
│
├── features/                        # Vertical slices (features)
│   ├── session-analyst/            # 🥊 Boxing coach use case
│   │   ├── data/                   # Data layer (repositories)
│   │   ├── domain/                 # Business logic (use cases)
│   │   └── ui/                     # Presentation layer (Compose UI)
│   ├── document-vault/             # 📁 File management
│   ├── model-manager/              # 🤖 Model download/management
│   └── active-desk/                # 🖥️ Drag-drop workspace
│
├── androidApp/                      # Android application
├── desktopApp/                      # Desktop application (Windows/macOS)
├── native-libs/                     # Compiled native libraries
│   ├── windows-x64/
│   ├── macos-x64/
│   └── macos-arm64/
└── models/                          # Downloaded GGUF models (not in git)
```

### Tech Stack

- **Framework**: Kotlin Multiplatform (KMP) 1.9.21
- **UI**: Compose Multiplatform
- **Inference**: llama.cpp (C++ via cinterop)
- **Database**: SQLite with sqlite-vec extension
- **Targets**: Android, Windows, macOS

---

## 🚀 Quick Start

### Prerequisites

- JDK 17+
- CMake 3.20+
- C++ compiler (MSVC/MinGW for Windows, Xcode for macOS)
- Android SDK (for Android target)

### 1. Clone and Setup

```bash
git clone <your-repo-url>
cd private-ai
```

### 2. Build llama.cpp

```bash
git clone https://github.com/ggerganov/llama.cpp.git
cd llama.cpp
mkdir build && cd build
cmake .. -DBUILD_SHARED_LIBS=ON
cmake --build . --config Release

# Copy artifacts
cp bin/Release/llama.dll ../native-libs/windows-x64/
cp llama.h ../shared/src/nativeInterop/cinterop/headers/
```

### 3. Download a Model

Download a GGUF model (e.g., Phi-3-mini-4k-instruct) from [Hugging Face](https://huggingface.co/models?library=gguf):

```bash
mkdir models
# Place your .gguf file in the models/ directory
```

### 4. Build the Project

```bash
./gradlew build
```

### 5. Run Desktop App

```bash
./gradlew :desktopApp:run
```

See [INITIALIZATION_PLAN.md](INITIALIZATION_PLAN.md) for detailed setup instructions.

---

## 📚 Features

### ✅ Session Analyst (Boxing Coach Use Case)

The flagship feature demonstrating the full vertical slice architecture.

**Use Case**: A boxing coach analyzes training sessions using private AI.

**Workflow**:
1. Coach creates a training session for a fighter
2. Adds session notes (video transcripts, observations, metrics)
3. Content is chunked and embedded locally using llama.cpp
4. Coach asks questions: "What improvements does the fighter need?"
5. AI retrieves relevant context via RAG and generates insights
6. All data stays on device

**Code Location**: [`features/session-analyst/`](features/session-analyst/)

**Layers**:
- **Data**: [SessionRepository.kt](features/session-analyst/src/commonMain/kotlin/com/privateai/vault/features/sessionanalyst/data/SessionRepository.kt)
- **Domain**: [SessionAnalystUseCase.kt](features/session-analyst/src/commonMain/kotlin/com/privateai/vault/features/sessionanalyst/domain/SessionAnalystUseCase.kt)
- **UI**: [SessionAnalystScreen.kt](features/session-analyst/src/commonMain/kotlin/com/privateai/vault/features/sessionanalyst/ui/SessionAnalystScreen.kt)

---

## 🧩 Core Modules

### Inference Engine

Local LLM inference using llama.cpp through C interop.

**Key Files**:
- [InferenceEngine.kt](core/inference-engine/src/commonMain/kotlin/com/privateai/vault/inference/InferenceEngine.kt) - Interface
- [LlamaCppInferenceEngine.kt](core/inference-engine/src/nativeMain/kotlin/com/privateai/vault/inference/LlamaCppInferenceEngine.kt) - Native implementation

**Features**:
- Load GGUF models from disk
- Streaming text generation
- Generate embeddings for RAG
- CPU-only (GPU support coming)

**Usage**:
```kotlin
val engine = createInferenceEngine()
engine.loadModel("models/phi-3-mini-4k-instruct-q4.gguf", ModelParams(
    contextSize = 2048,
    threads = 4
))

engine.generateStream("What is boxing?", GenerationParams()).collect { token ->
    print(token)
}
```

### Vector Store

RAG (Retrieval Augmented Generation) with SQLite + sqlite-vec.

**Key Files**:
- [VectorStore.kt](core/vector-store/src/commonMain/kotlin/com/privateai/vault/vectorstore/VectorStore.kt) - Interface
- [SqliteVectorStore.kt](core/vector-store/src/commonMain/kotlin/com/privateai/vault/vectorstore/SqliteVectorStore.kt) - Implementation
- [VectorStore.sq](core/vector-store/src/commonMain/sqldelight/com/privateai/vault/vectorstore/VectorStore.sq) - SQL schema

**Features**:
- Store documents with chunked embeddings
- Cosine similarity search
- Session management (Active Desk)
- All data persisted locally

**Usage**:
```kotlin
val vectorStore = createVectorStore("data/vectors.db")
vectorStore.initialize()

// Add document with embeddings
vectorStore.addDocument(document, embeddedChunks)

// Search
val results = vectorStore.searchSimilar(queryEmbedding, limit = 5)
```

---

## 🎨 Vertical Slice Architecture

Unlike traditional N-tier architecture (Controllers → Services → Repositories), this project uses **Vertical Slice Architecture** where each feature is self-contained.

### Traditional N-Tier (❌ Not Used)

```
controllers/
  - SessionController
  - DocumentController
services/
  - SessionService
  - DocumentService
repositories/
  - SessionRepository
  - DocumentRepository
```

### Vertical Slice (✅ Used Here)

```
features/
  session-analyst/
    - data/SessionRepository
    - domain/SessionAnalystUseCase
    - ui/SessionAnalystScreen
  document-vault/
    - data/DocumentRepository
    - domain/DocumentUseCase
    - ui/DocumentVaultScreen
```

**Benefits**:
- Features are isolated and can evolve independently
- Easy to add/remove features
- Clear boundaries reduce merge conflicts
- Easier to understand: everything for one feature is in one place

---

## 🔐 Security & Privacy

### Data Sovereignty

- ✅ All data stored locally in SQLite
- ✅ Models run on-device (no API calls)
- ✅ Embeddings generated locally
- ✅ No telemetry or analytics
- ✅ No network access required (after initial model download)

### Future Enhancements

- [ ] Encrypt SQLite database with SQLCipher
- [ ] Encrypt model files
- [ ] Secure memory allocation for embeddings
- [ ] File permission validation

---

## 📊 Performance

### Recommended Models

| Model | Size | RAM | Speed | Use Case |
|-------|------|-----|-------|----------|
| TinyLlama-1.1B | 669 MB | 2 GB | Fast | Quick answers |
| Phi-3-mini-4k | 2.3 GB | 4 GB | Medium | Balanced |
| Mistral-7B | 4.1 GB | 8 GB | Slow | High quality |

### Optimization Tips

1. **Use Quantized Models**: Q4_K_M is the sweet spot
2. **Adjust Context Size**: Smaller = faster
3. **Set Thread Count**: Match your CPU cores
4. **Limit Vector Search**: Fewer results = faster

```kotlin
ModelParams(
    contextSize = 2048,     // Lower for speed
    threads = 4,            // Match CPU cores
    gpuLayers = 0           // CPU-only for now
)
```

---

## 🛠️ Development

### Adding a New Feature Slice

1. **Create module in `features/`**:
   ```bash
   mkdir -p features/new-feature/src/commonMain/kotlin/com/privateai/vault/features/newfeature/{data,domain,ui}
   ```

2. **Create `build.gradle.kts`**:
   ```kotlin
   plugins {
       kotlin("multiplatform")
       id("org.jetbrains.compose")
   }
   ```

3. **Implement layers**:
   - **Data**: Repository for persistence
   - **Domain**: Use case for business logic
   - **UI**: Composable screens

4. **Add to `settings.gradle.kts`**:
   ```kotlin
   include(":features:new-feature")
   ```

### Building for Different Targets

```bash
# Desktop (Windows/macOS)
./gradlew :desktopApp:run

# Android
./gradlew :androidApp:installDebug

# All targets
./gradlew build
```

---

## 📖 Documentation

- [INITIALIZATION_PLAN.md](INITIALIZATION_PLAN.md) - Detailed setup guide
- [Architecture Decision Records](docs/adr/) - Coming soon
- [API Documentation](docs/api/) - Coming soon

---

## 🗺️ Roadmap

### Phase 1: Core Infrastructure ✅
- [x] KMP project structure
- [x] llama.cpp C interop
- [x] SQLite + sqlite-vec integration
- [x] Session Analyst feature slice

### Phase 2: Essential Features 🚧
- [ ] Model Manager (download/switch models)
- [ ] Document Vault (file management)
- [ ] Active Desk (drag-drop UI)

### Phase 3: Platform Support 📅
- [ ] iOS support
- [ ] Linux desktop support
- [ ] GPU acceleration (Metal/CUDA)

### Phase 4: Advanced Features 🔮
- [ ] Multi-modal support (images, audio)
- [ ] Advanced RAG (re-ranking, hybrid search)
- [ ] Fine-tuning on user data
- [ ] Export/import encrypted vaults

---

## 🤝 Contributing

This is a personal/educational project, but suggestions and discussions are welcome!

### Code Style

- Follow [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html)
- Use meaningful names
- Document public APIs
- Keep slices isolated

---

## 📄 License

[Choose appropriate license]

---

## 🙏 Acknowledgments

- [llama.cpp](https://github.com/ggerganov/llama.cpp) - Fast LLM inference
- [sqlite-vec](https://github.com/asg017/sqlite-vec) - Vector search for SQLite
- [Kotlin Multiplatform](https://kotlinlang.org/docs/multiplatform.html) - Cross-platform framework
- [Compose Multiplatform](https://www.jetbrains.com/lp/compose-multiplatform/) - UI framework

---

**Built with ❤️ using Kotlin Multiplatform**

**Status**: 🚀 Architecture Complete - Ready for Implementation