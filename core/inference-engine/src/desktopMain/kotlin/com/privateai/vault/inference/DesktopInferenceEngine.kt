package com.privateai.vault.inference

import de.kherud.llama.LlamaModel
import de.kherud.llama.ModelParameters
import de.kherud.llama.InferenceParameters
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

/**
 * Desktop (JVM) implementation using java-llama.cpp library.
 *
 * STRATEGIC DECISION: Instead of writing manual JNI bindings, we use the
 * pre-built java-llama.cpp library (de.kherud:llama) which provides
 * production-ready JNI bridge to llama.cpp.
 *
 * LORA SUPPORT (Persona Forge - Stage 1):
 * This implementation supports hot-swappable LoRA adapters ("Souls") that
 * can be loaded alongside the base model for personalized inference.
 *
 * Library: https://github.com/kherud/java-llama.cpp
 */
class DesktopInferenceEngine : InferenceEngine {

    private var model: LlamaModel? = null
    private var modelInfo: ModelInfo? = null
    private var currentModelPath: String? = null
    private var currentParams: ModelParams? = null
    private var adapterInfo: AdapterInfo? = null

    override suspend fun loadModel(modelPath: String, params: ModelParams): Boolean {
        return try {
            // Cleanup existing model
            model?.close()

            // Configure model parameters with LoRA support
            // API reference: https://github.com/kherud/java-llama.cpp
            val modelParams = ModelParameters()
                .setModelFilePath(modelPath)
                .setNGpuLayers(params.gpuLayers)

            // Add LoRA adapter if specified
            // Note: LoRA adapters are .gguf files that modify base model behavior
            // The java-llama.cpp library supports LoRA via reflection (API varies by version)
            if (params.adapterPath != null) {
                val adapterFile = File(params.adapterPath)
                if (!adapterFile.exists()) {
                    println("[Desktop] ❌ LoRA adapter not found: ${params.adapterPath}")
                    return false
                }

                // Try to load LoRA adapter using reflection (API varies across library versions)
                val loraLoaded = tryLoadLoraAdapter(modelParams, params.adapterPath)
                if (loraLoaded) {
                    println("[Desktop] 🔧 LoRA adapter configured: ${params.adapterPath}")
                } else {
                    println("[Desktop] ⚠️  LoRA adapter loading not supported in this library version")
                    println("[Desktop]    Alternative: Pre-merge LoRA weights into base model using:")
                    println("[Desktop]    llama.cpp/llama-export-lora -m base.gguf -o merged.gguf --lora ${params.adapterPath}")
                    // Continue without LoRA - user can use pre-merged model
                }
            }

            // Load model
            val loadedModel = LlamaModel(modelParams)
            model = loadedModel
            currentModelPath = modelPath
            currentParams = params

            // Extract embedding dimension from actual model if possible
            val embeddingDim = try {
                // Try to get actual embedding dimension by running a test embed
                val testEmbed = loadedModel.embed("test")
                testEmbed.size
            } catch (e: Exception) {
                // Fallback based on known model architectures
                guessEmbeddingDimension(modelPath)
            }

            // Build adapter info if LoRA was loaded
            adapterInfo = if (params.adapterPath != null) {
                val adapterFile = File(params.adapterPath)
                AdapterInfo(
                    name = adapterFile.nameWithoutExtension,
                    path = params.adapterPath,
                    scale = params.adapterScale,
                    sizeBytes = adapterFile.length(),
                    rank = null, // Not exposed by java-llama.cpp
                    targetModules = null
                )
            } else null

            // Extract model metadata
            modelInfo = ModelInfo(
                name = File(modelPath).nameWithoutExtension,
                architecture = guessArchitecture(modelPath),
                contextLength = params.contextSize,
                embeddingDimension = embeddingDim,
                parameters = guessParameterCount(modelPath),
                quantization = guessQuantization(modelPath),
                activeAdapter = adapterInfo
            )

            println("[Desktop] ✅ Model loaded: $modelPath")
            println("[Desktop]    Context size: ${params.contextSize}")
            println("[Desktop]    GPU layers: ${params.gpuLayers}")
            println("[Desktop]    Embedding dimension: $embeddingDim")
            if (adapterInfo != null) {
                println("[Desktop]    LoRA adapter: ${adapterInfo!!.name} (${adapterInfo!!.sizeBytes / 1024}KB)")
            }

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
            currentModelPath = null
            currentParams = null
            adapterInfo = null
        }
    }

    override fun isModelLoaded(): Boolean {
        return model != null
    }

    override suspend fun swapAdapter(adapterPath: String?, scale: Float): Boolean {
        val currentPath = currentModelPath
        val currentP = currentParams

        if (currentPath == null || currentP == null) {
            println("[Desktop] ❌ Cannot swap adapter: no model loaded")
            return false
        }

        // Reload model with new adapter configuration
        // Note: java-llama.cpp doesn't support true hot-swap yet,
        // so we reload the model with the new adapter
        val newParams = currentP.copy(
            adapterPath = adapterPath,
            adapterScale = scale
        )

        println("[Desktop] 🔄 Swapping adapter...")
        val startTime = System.currentTimeMillis()
        val success = loadModel(currentPath, newParams)
        val elapsed = System.currentTimeMillis() - startTime

        if (success) {
            println("[Desktop] ✅ Adapter swapped in ${elapsed}ms")
        } else {
            println("[Desktop] ❌ Adapter swap failed")
        }

        return success
    }

    override fun hasActiveAdapter(): Boolean {
        return adapterInfo != null
    }

    override fun getAdapterInfo(): AdapterInfo? {
        return adapterInfo
    }

    override fun generateStream(prompt: String, params: GenerationParams): Flow<String> = flow {
        val llamaModel = model ?: throw IllegalStateException("No model loaded")

        try {
            println("[Desktop] Generating response for: ${prompt.take(50)}...")

            // Configure inference parameters
            val inferenceParams = InferenceParameters(prompt)
                .setNPredict(params.maxTokens)
                .setTemperature(params.temperature)
                .setTopP(params.topP)
                .setTopK(params.topK)
                .setRepeatPenalty(params.repeatPenalty)

            // Add stop sequences
            params.stopSequences.forEach { stopSeq ->
                inferenceParams.setStopStrings(stopSeq)
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

    // LoRA adapter loading via reflection (API varies across library versions)

    /**
     * Attempts to load a LoRA adapter using reflection.
     * The java-llama.cpp library API has changed across versions:
     * - Some versions use addLoraAdapter(String)
     * - Some versions use setLoraAdapter(String) + setLoraBase(String)
     *
     * @return true if adapter was successfully configured
     */
    private fun tryLoadLoraAdapter(modelParams: ModelParameters, adapterPath: String): Boolean {
        val clazz = modelParams::class.java

        // Try different method names that have been used in various versions
        val methodNames = listOf("addLoraAdapter", "setLoraAdapter")

        for (methodName in methodNames) {
            try {
                val method = clazz.getMethod(methodName, String::class.java)
                method.invoke(modelParams, adapterPath)
                return true
            } catch (e: NoSuchMethodException) {
                // Method doesn't exist in this version, try next
                continue
            } catch (e: Exception) {
                // Method exists but failed - log and continue
                println("[Desktop] ⚠️  $methodName failed: ${e.message}")
                continue
            }
        }

        return false
    }

    // Helper functions to extract model metadata from filename patterns

    private fun guessEmbeddingDimension(modelPath: String): Int {
        val name = modelPath.lowercase()
        return when {
            "llama-3" in name || "llama3" in name -> 3072  // Llama 3.2 3B
            "llama-2" in name || "llama2" in name -> 4096  // Llama 2 7B
            "tinyllama" in name -> 2048                     // TinyLlama
            "phi" in name -> 2560                           // Phi-2
            "mistral" in name -> 4096                       // Mistral 7B
            "qwen" in name -> 2048                          // Qwen
            else -> 2048  // Conservative default
        }
    }

    private fun guessArchitecture(modelPath: String): String {
        val name = modelPath.lowercase()
        return when {
            "llama-3" in name || "llama3" in name -> "Llama3"
            "llama-2" in name || "llama2" in name -> "Llama2"
            "tinyllama" in name -> "TinyLlama"
            "phi" in name -> "Phi"
            "mistral" in name -> "Mistral"
            "qwen" in name -> "Qwen"
            else -> "Unknown"
        }
    }

    private fun guessParameterCount(modelPath: String): Long {
        val name = modelPath.lowercase()
        return when {
            "3b" in name -> 3_000_000_000L
            "7b" in name -> 7_000_000_000L
            "13b" in name -> 13_000_000_000L
            "1b" in name -> 1_000_000_000L
            "tinyllama" in name -> 1_100_000_000L
            else -> 0L
        }
    }

    private fun guessQuantization(modelPath: String): String {
        val name = modelPath.uppercase()
        return when {
            "Q8_0" in name -> "Q8_0"
            "Q6_K" in name -> "Q6_K"
            "Q5_K_M" in name -> "Q5_K_M"
            "Q5_K_S" in name -> "Q5_K_S"
            "Q5_0" in name -> "Q5_0"
            "Q4_K_M" in name -> "Q4_K_M"
            "Q4_K_S" in name -> "Q4_K_S"
            "Q4_0" in name -> "Q4_0"
            "Q3_K_M" in name -> "Q3_K_M"
            "Q2_K" in name -> "Q2_K"
            "F16" in name -> "F16"
            "F32" in name -> "F32"
            else -> "Unknown"
        }
    }
}

actual fun createInferenceEngine(): InferenceEngine = DesktopInferenceEngine()
