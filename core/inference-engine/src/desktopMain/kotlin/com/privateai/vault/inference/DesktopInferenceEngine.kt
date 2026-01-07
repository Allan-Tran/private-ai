package com.privateai.vault.inference

import de.kherud.llama.LlamaModel
import de.kherud.llama.ModelParameters
import de.kherud.llama.InferenceParameters
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Desktop (JVM) implementation using java-llama.cpp library.
 *
 * STRATEGIC DECISION: Instead of writing manual JNI bindings, we use the
 * pre-built java-llama.cpp library (de.kherud:llama) which provides
 * production-ready JNI bridge to llama.cpp.
 *
 * This allows Desktop (Windows/macOS/Linux JVM) to work immediately while
 * keeping the custom Native implementation for iOS/Android with Metal acceleration.
 *
 * Library: https://github.com/kherud/java-llama.cpp
 */
class DesktopInferenceEngine : InferenceEngine {

    private var model: LlamaModel? = null
    private var modelInfo: ModelInfo? = null

    override suspend fun loadModel(modelPath: String, params: ModelParams): Boolean {
        return try {
            // Cleanup existing model
            model?.close()

            // Configure model parameters
            val modelParams = ModelParameters().apply {
                setNGpuLayers(params.gpuLayers)
                setUseMemorymap(params.useMmap)
                setUseMemoryLock(params.useMlock)
            }

            // Load model
            val loadedModel = LlamaModel(modelPath, modelParams)
            model = loadedModel

            // Extract model metadata
            modelInfo = ModelInfo(
                name = "GGUF Model (JVM)",
                architecture = "Unknown", // java-llama.cpp doesn't expose this
                contextLength = params.contextSize,
                embeddingDimension = 384, // Default, actual value from model
                parameters = 0L, // Not exposed by library
                quantization = "Q4_K_M" // Common quantization
            )

            println("[Desktop] ✅ Model loaded: $modelPath")
            println("[Desktop]    Context size: ${params.contextSize}")
            println("[Desktop]    GPU layers: ${params.gpuLayers}")

            true
        } catch (e: Exception) {
            println("[Desktop] ❌ Model loading failed: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    override suspend fun unloadModel() {
        try {
            model?.close()
            println("[Desktop] Model unloaded")
        } catch (e: Exception) {
            println("[Desktop] Error unloading model: ${e.message}")
        } finally {
            model = null
            modelInfo = null
        }
    }

    override fun isModelLoaded(): Boolean {
        return model != null
    }

    override fun generateStream(prompt: String, params: GenerationParams): Flow<String> = flow {
        val llamaModel = model ?: throw IllegalStateException("No model loaded")

        try {
            println("[Desktop] Generating response for: ${prompt.take(50)}...")

            // Configure inference parameters
            val inferenceParams = InferenceParameters(prompt).apply {
                setNPredict(params.maxTokens)
                setTemperature(params.temperature)
                setTopP(params.topP)
                setTopK(params.topK)
                setRepeatPenalty(params.repeatPenalty)

                // Add stop sequences
                params.stopSequences.forEach { stopSeq ->
                    addStopString(stopSeq)
                }
            }

            // Stream generation
            var tokenCount = 0
            for (output in llamaModel.generate(inferenceParams)) {
                emit(output.text)
                tokenCount++
            }

            println("[Desktop] ✅ Generated $tokenCount tokens")

        } catch (e: Exception) {
            println("[Desktop] ❌ Generation failed: ${e.message}")
            emit("\n[Error: ${e.message}]")
        }
    }

    override suspend fun embed(text: String): FloatArray {
        val llamaModel = model ?: throw IllegalStateException("No model loaded")

        return try {
            // java-llama.cpp provides embedding generation
            val embedding = llamaModel.embed(text)
            embedding
        } catch (e: Exception) {
            println("[Desktop] ❌ Embedding failed: ${e.message}")
            throw IllegalStateException("Embedding generation failed: ${e.message}")
        }
    }

    override fun getModelInfo(): ModelInfo? = modelInfo
}

actual fun createInferenceEngine(): InferenceEngine = DesktopInferenceEngine()
