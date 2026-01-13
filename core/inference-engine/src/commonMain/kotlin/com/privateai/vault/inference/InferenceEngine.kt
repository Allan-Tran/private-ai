package com.privateai.vault.inference

import kotlinx.coroutines.flow.Flow

/**
 * Inference Engine abstraction for local LLM execution.
 * Platform-specific implementations use llama.cpp via C interop.
 */
interface InferenceEngine {
    /**
     * Load a GGUF model from the specified path.
     * @param modelPath Absolute path to the .gguf model file
     * @param params Model loading parameters (context size, threads, etc.)
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
 */
data class ModelParams(
    val contextSize: Int = 2048,
    val batchSize: Int = 512,
    val threads: Int = 4,
    val gpuLayers: Int = 0, // 0 = CPU only
    val useMmap: Boolean = true,
    val useMlock: Boolean = false,
    val embedding: Boolean = false
)

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
    val quantization: String
)

/**
 * Factory to create platform-specific inference engine.
 */
expect fun createInferenceEngine(): InferenceEngine
