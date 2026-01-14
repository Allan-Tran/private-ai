package com.privateai.vault.inference

import kotlinx.coroutines.flow.Flow

/**
 * Inference Engine abstraction for local LLM execution.
 * Platform-specific implementations use llama.cpp via C interop.
 *
 * Supports hot-swappable LoRA adapters ("Souls") for personalized inference.
 */
interface InferenceEngine {
    /**
     * Load a GGUF model from the specified path.
     * @param modelPath Absolute path to the .gguf model file
     * @param params Model loading parameters (context size, threads, LoRA adapter, etc.)
     * @return True if model loaded successfully
     */
    suspend fun loadModel(modelPath: String, params: ModelParams): Boolean

    /**
     * Unload the currently loaded model and free resources.
     */
    suspend fun unloadModel()

    /**
     * Check if a model is currently loaded.
     */
    fun isModelLoaded(): Boolean

    /**
     * Hot-swap the LoRA adapter without reloading the base model.
     * This enables rapid "Soul" switching for different personas/specializations.
     *
     * @param adapterPath Path to the new LoRA adapter (.gguf), or null to remove adapter
     * @param scale Scaling factor for adapter weights (0.0-1.0)
     * @return True if adapter was successfully swapped
     */
    suspend fun swapAdapter(adapterPath: String?, scale: Float = 1.0f): Boolean

    /**
     * Check if a LoRA adapter is currently active.
     */
    fun hasActiveAdapter(): Boolean

    /**
     * Get information about the currently loaded adapter.
     */
    fun getAdapterInfo(): AdapterInfo?

    /**
     * Generate text completion with streaming response.
     * @param prompt The input prompt
     * @param params Generation parameters (temperature, top_p, etc.)
     * @return Flow of token strings as they are generated
     */
    fun generateStream(prompt: String, params: GenerationParams): Flow<String>

    /**
     * Generate embeddings for the given text (used for RAG).
     * @param text The text to embed
     * @return Float array of embeddings
     */
    suspend fun embed(text: String): FloatArray

    /**
     * Get the current model's metadata.
     */
    fun getModelInfo(): ModelInfo?
}

/**
 * Model loading parameters.
 *
 * @param contextSize Maximum context window size in tokens
 * @param batchSize Batch size for prompt processing
 * @param threads Number of CPU threads to use
 * @param gpuLayers Number of layers to offload to GPU (0 = CPU only)
 * @param useMmap Use memory-mapped file for model loading
 * @param useMlock Lock model in memory (prevents swapping)
 * @param embedding Enable embedding mode for vector generation
 * @param adapterPath Optional path to a LoRA adapter (.gguf format)
 * @param adapterScale Scaling factor for LoRA adapter weights (0.0-1.0, default 1.0)
 */
data class ModelParams(
    val contextSize: Int = 2048,
    val batchSize: Int = 512,
    val threads: Int = 4,
    val gpuLayers: Int = 0,
    val useMmap: Boolean = true,
    val useMlock: Boolean = false,
    val embedding: Boolean = false,
    val adapterPath: String? = null,
    val adapterScale: Float = 1.0f
) {
    /**
     * Check if a LoRA adapter is configured.
     */
    fun hasAdapter(): Boolean = adapterPath != null
}

/**
 * Generation parameters for inference.
 */
data class GenerationParams(
    val maxTokens: Int = 512,
    val temperature: Float = 0.7f,
    val topP: Float = 0.9f,
    val topK: Int = 40,
    val repeatPenalty: Float = 1.1f,
    val stopSequences: List<String> = emptyList()
)

/**
 * Model metadata information.
 */
data class ModelInfo(
    val name: String,
    val architecture: String,
    val contextLength: Int,
    val embeddingDimension: Int,
    val parameters: Long,
    val quantization: String,
    val activeAdapter: AdapterInfo? = null
)

/**
 * LoRA adapter ("Soul") metadata.
 *
 * In the Persona Forge system, each adapter represents a specialized
 * personality or skill set that can be hot-swapped onto the base model.
 */
data class AdapterInfo(
    val name: String,
    val path: String,
    val scale: Float,
    val sizeBytes: Long,
    val rank: Int? = null,
    val targetModules: List<String>? = null
)

/**
 * Factory to create platform-specific inference engine.
 */
expect fun createInferenceEngine(): InferenceEngine
