# Private AI Vault - Architecture Documentation

## 🏛️ System Architecture

This document describes the architectural decisions, patterns, and design principles used in the Private AI Vault.

---

## 🎯 Core Architectural Principles

### 1. Sovereign Data
- **All data stays on device** - No cloud sync, no external APIs
- **Local inference only** - Models run on the user's hardware
- **Privacy by design** - No telemetry, tracking, or data collection

### 2. Vertical Slice Architecture
- **Feature-based organization** - Each feature is self-contained
- **Full-stack slices** - Data → Domain → UI in one module
- **Independent evolution** - Features can be added/removed without affecting others

### 3. Platform Abstraction
- **Kotlin Multiplatform** - Write once, run on Android/Windows/macOS
- **Platform expectations** - Abstract platform-specific code with `expect/actual`
- **Native interop** - C++ bridge for high-performance inference

---

## 📐 System Layers

```
┌─────────────────────────────────────────────────────────┐
│                     UI Layer                             │
│  (Compose Multiplatform - Material 3)                    │
│  - SessionAnalystScreen.kt                               │
│  - DocumentVaultScreen.kt                                │
│  - ActiveDeskScreen.kt                                   │
└─────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────┐
│                  Domain Layer                            │
│  (Business Logic - Use Cases)                            │
│  - SessionAnalystUseCase.kt                              │
│  - DocumentProcessingUseCase.kt                          │
│  - RAG orchestration & prompt building                   │
└─────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────┐
│                   Data Layer                             │
│  (Repositories - Data Access)                            │
│  - SessionRepository.kt                                  │
│  - DocumentRepository.kt                                 │
│  - Chunking, embedding coordination                      │
└─────────────────────────────────────────────────────────┘
                          ↓
┌──────────────────────┬──────────────────────────────────┐
│   Inference Engine   │      Vector Store                │
│   (llama.cpp)        │      (SQLite + sqlite-vec)       │
│                      │                                   │
│  - Model loading     │  - Document storage              │
│  - Text generation   │  - Embedding storage             │
│  - Embedding gen.    │  - Similarity search             │
└──────────────────────┴──────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────┐
│              Platform Layer (expect/actual)              │
│  - FileSystemAccess                                      │
│  - Platform detection                                    │
│  - Native library loading                                │
└─────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────┐
│                  Native Layer                            │
│  - llama.cpp (C++)                                       │
│  - SQLite + vec0 extension                               │
│  - OS-specific APIs                                      │
└─────────────────────────────────────────────────────────┘
```

---

## 🗂️ Module Organization

### Shared Module
**Purpose**: Platform-agnostic code shared across all targets

**Structure**:
```
shared/
├── src/
│   ├── commonMain/          # Pure Kotlin (no platform deps)
│   ├── androidMain/         # Android implementations
│   ├── desktopMain/         # JVM implementations
│   └── nativeMain/          # Native (Windows/macOS) implementations
└── nativeInterop/
    └── cinterop/
        ├── llamacpp.def     # C interop definition
        └── headers/         # C header files
```

**Key Abstractions**:
- `Platform` - Platform detection
- `FileSystemAccess` - File I/O abstraction

### Core Modules

#### 1. Inference Engine (`core/inference-engine`)
**Purpose**: Bridge between Kotlin and llama.cpp for local LLM inference

**Components**:
- `InferenceEngine` interface - Platform-agnostic API
- `LlamaCppInferenceEngine` - Native implementation using C interop
- `DesktopInferenceEngine` - JVM wrapper (loads native library)

**Key Operations**:
```kotlin
interface InferenceEngine {
    suspend fun loadModel(path: String, params: ModelParams): Boolean
    fun generateStream(prompt: String, params: GenerationParams): Flow<String>
    suspend fun embed(text: String): FloatArray
}
```

**Native Binding**:
```
Kotlin (expect/actual)
    ↓
cinterop (FFI)
    ↓
llama.cpp (C++)
    ↓
GGUF Model Files
```

#### 2. Vector Store (`core/vector-store`)
**Purpose**: RAG storage with semantic search capabilities

**Components**:
- `VectorStore` interface - Platform-agnostic API
- `SqliteVectorStore` - Implementation using SQLDelight
- SQL schema with virtual table for vector search

**Key Operations**:
```kotlin
interface VectorStore {
    suspend fun addDocument(doc: Document, chunks: List<DocumentChunk>)
    suspend fun searchSimilar(embedding: FloatArray, limit: Int): List<SearchResult>
    suspend fun createSession(session: Session)
}
```

**Storage**:
```
SQLite Database
├── documents          # Raw documents
├── chunks             # Text chunks with metadata
├── vec_chunks         # Virtual table (sqlite-vec)
├── sessions           # Active Desk sessions
└── session_documents  # Many-to-many linking
```

### Feature Modules (Vertical Slices)

#### Session Analyst (`features/session-analyst`)
**Purpose**: Boxing coach training session analysis

**Vertical Structure**:
```
session-analyst/
├── data/
│   └── SessionRepository.kt       # Data access
├── domain/
│   ├── Models.kt                  # Domain entities
│   └── SessionAnalystUseCase.kt   # Business logic
└── ui/
    ├── SessionAnalystScreen.kt    # Compose UI
    └── SessionAnalystViewModel.kt # State management
```

**Data Flow**:
```
User Input (UI)
    ↓
ViewModel (state management)
    ↓
Use Case (RAG orchestration)
    ↓
Repository (data coordination)
    ↓ ↘
VectorStore  InferenceEngine
(context)    (generation)
    ↘ ↓
   Response (streaming)
```

---

## 🔄 RAG (Retrieval Augmented Generation) Flow

### 1. Indexing Phase (Data Ingestion)

```
User uploads document
    ↓
Repository.addSessionNote()
    ↓
Chunk content (paragraphs/sentences)
    ↓
For each chunk:
    InferenceEngine.embed(chunk) → FloatArray
    ↓
Store in VectorStore
    Document → chunks table
    Embeddings → vec_chunks (virtual table)
```

### 2. Query Phase (Inference with Context)

```
User asks question
    ↓
Use Case.analyzeSession(query)
    ↓
InferenceEngine.embed(query) → FloatArray
    ↓
VectorStore.searchSimilar(queryEmbedding)
    ↓ (cosine similarity search)
Retrieve top K relevant chunks
    ↓
Build prompt with context:
    "Context: [chunk1, chunk2, ...]
     Question: [user query]"
    ↓
InferenceEngine.generateStream(prompt)
    ↓ (streaming tokens)
Display in UI (real-time)
```

### 3. Context Window Management

```kotlin
// Prompt structure
val prompt = """
You are an expert boxing coach assistant.

## Session Context:
${retrievedChunks.joinToString("\n\n")}

## Question:
${userQuestion}

## Analysis:
""".trimIndent()

// Stream generation
inferenceEngine.generateStream(prompt, GenerationParams(
    maxTokens = 1024,
    temperature = 0.7f
))
```

---

## 🔌 C Interop (Kotlin ↔ llama.cpp)

### Binding Generation

**Definition File** (`llamacpp.def`):
```
headers = llama.h
package = com.privateai.vault.llamacpp
compilerOpts = -I/path/to/headers
linkerOpts = -L/path/to/libs -lllama
```

**Gradle Task**:
```bash
./gradlew :shared:cinteropLlamacppWindowsX64
```

**Generated Bindings**:
```kotlin
// Auto-generated by cinterop
@CPointer llama_context
@CPointer llama_model

external fun llama_load_model_from_file(...)
external fun llama_new_context_with_model(...)
external fun llama_eval(...)
```

### Usage in Kotlin

```kotlin
@OptIn(ExperimentalForeignApi::class)
class LlamaCppInferenceEngine : InferenceEngine {
    private var context: CPointer<llama_context>? = null

    override suspend fun loadModel(path: String, params: ModelParams): Boolean {
        return memScoped {
            val modelParams = llama_model_default_params()
            modelParams.n_gpu_layers = params.gpuLayers

            val model = llama_load_model_from_file(path, modelParams)
                ?: return false

            // ... create context, etc.
        }
    }
}
```

---

## 🎨 Active Desk Concept

The **Active Desk** is a workspace where users can temporarily expand the AI's context by dragging files.

### Workflow

```
User drags file → Active Desk
    ↓
File processed (OCR, transcription, etc.)
    ↓
Content chunked & embedded
    ↓
Added to current Session
    ↓
AI context expanded
    ↓
User asks questions → AI uses new context
    ↓
User removes file → Context reduced
```

### Implementation (Planned)

```kotlin
@Composable
fun ActiveDeskWorkspace() {
    var activeFiles by remember { mutableStateOf<List<File>>(emptyList()) }

    DropZone(
        onDrop = { files ->
            files.forEach { file ->
                // Process and add to session
                viewModel.addFileToActiveDesk(file)
            }
            activeFiles += files
        }
    )

    // Display active context
    activeFiles.forEach { file ->
        ActiveFileCard(
            file = file,
            onRemove = { viewModel.removeFileFromActiveDesk(it) }
        )
    }
}
```

---

## 🔐 Security Architecture

### Data Protection

1. **Encryption at Rest** (Future)
   - SQLCipher for database encryption
   - AES-256 for model file encryption

2. **Memory Protection**
   - Clear sensitive data after use
   - Use `SecureString` for prompts with PII

3. **File System Isolation**
   - All data in app-private directory
   - No world-readable files

### Privacy Guarantees

```
✅ No network calls (after model download)
✅ No telemetry
✅ No crash reporting
✅ No analytics
✅ No cloud sync
✅ No external dependencies at runtime
```

---

## ⚡ Performance Considerations

### Model Quantization

Use quantized models for speed:
- **Q4_K_M**: 4-bit quantization (recommended)
- **Q5_K_M**: 5-bit (better quality)
- **Q8_0**: 8-bit (highest quality)

### Context Window Optimization

```kotlin
// Balance between quality and speed
ModelParams(
    contextSize = 2048,   // Smaller = faster
    batchSize = 512,      // Affects throughput
    threads = 4           // Match CPU cores
)
```

### Vector Search Optimization

```kotlin
// Limit search results
vectorStore.searchSimilar(
    embedding,
    limit = 5,           // Fewer = faster
    threshold = 0.75f    // Higher = more selective
)
```

---

## 🧪 Testing Strategy

### Unit Tests
- Repository logic
- Use case orchestration
- Chunking algorithms
- Prompt building

### Integration Tests
- Inference engine with real models
- Vector store operations
- End-to-end RAG flow

### Platform Tests
- File system access on each platform
- Native library loading
- C interop correctness

---

## 🚀 Deployment

### Desktop (Windows/macOS)

**Package Structure**:
```
PrivateAIVault.app/ (or .msi)
├── app/
│   ├── PrivateAIVault.exe
│   ├── native-libs/
│   │   ├── llama.dll
│   │   └── vec0.dll
│   └── runtime/
└── models/          # User downloads separately
```

**Installation**:
1. Install application
2. User downloads preferred model
3. First run: configure model path

### Android

**APK Structure**:
```
.apk
├── classes.dex
├── lib/
│   ├── arm64-v8a/
│   │   └── libllama.so
│   └── armeabi-v7a/
│       └── libllama.so
└── assets/
    └── (no models bundled - downloaded on demand)
```

---

## 📊 Scalability

### Model Management
- Download models on-demand
- Store multiple models
- Switch between models at runtime

### Vector Store Growth
- Efficient indexing with sqlite-vec
- Automatic cleanup of old sessions
- Configurable retention policies

### Performance Scaling
- CPU: More threads for faster inference
- GPU: Future support for Metal/CUDA
- Memory: Efficient model quantization

---

## 🔮 Future Enhancements

### Multi-Modal Support
- Image analysis with vision models
- Audio transcription with Whisper
- Video processing

### Advanced RAG
- Hybrid search (keyword + vector)
- Re-ranking with cross-encoder
- Query expansion

### Platform Expansion
- iOS (Kotlin/Native)
- Linux desktop
- Web (WASM)

---

## 📚 References

- [Kotlin Multiplatform Docs](https://kotlinlang.org/docs/multiplatform.html)
- [llama.cpp GitHub](https://github.com/ggerganov/llama.cpp)
- [sqlite-vec Documentation](https://github.com/asg017/sqlite-vec)
- [Vertical Slice Architecture](https://www.jimmybogard.com/vertical-slice-architecture/)

---

**Last Updated**: 2025-01-07
**Architecture Version**: 1.0
