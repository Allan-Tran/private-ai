package com.privateai.vault.inference

import com.privateai.vault.llamacpp.*
import kotlinx.cinterop.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Native implementation of InferenceEngine using llama.cpp via C interop.
 * This implementation provides true local inference without network calls.
 */
@OptIn(ExperimentalForeignApi::class)
class LlamaCppInferenceEngine : InferenceEngine {
    private var context: CPointer<llama_context>? = null
    private var model: CPointer<llama_model>? = null
    private var currentModelInfo: ModelInfo? = null

    override suspend fun loadModel(modelPath: String, params: ModelParams): Boolean {
        return memScoped {
            try {
                // Initialize llama backend
                llama_backend_init()

                // Set up model parameters
                val modelParams = llama_model_default_params()
                modelParams.n_gpu_layers = params.gpuLayers
                modelParams.use_mmap = params.useMmap
                modelParams.use_mlock = params.useMlock

                // Load the model
                val loadedModel = llama_load_model_from_file(modelPath, modelParams)
                    ?: return@memScoped false

                model = loadedModel

                // Set up context parameters
                val ctxParams = llama_context_default_params()
                ctxParams.n_ctx = params.contextSize.toUInt()
                ctxParams.n_batch = params.batchSize.toUInt()
                ctxParams.n_threads = params.threads

                // Create context
                val loadedContext = llama_new_context_with_model(loadedModel, ctxParams)
                    ?: return@memScoped false

                context = loadedContext

                // Extract model info
                currentModelInfo = extractModelInfo(loadedModel)

                true
            } catch (e: Exception) {
                unloadModel()
                false
            }
        }
    }

    override suspend fun unloadModel() {
        context?.let { llama_free(it) }
        model?.let { llama_free_model(it) }
        context = null
        model = null
        currentModelInfo = null
        llama_backend_free()
    }

    override fun isModelLoaded(): Boolean {
        return context != null && model != null
    }

    override fun generateStream(prompt: String, params: GenerationParams): Flow<String> = flow {
        val ctx = context ?: throw IllegalStateException("No model loaded")
        val mdl = model ?: throw IllegalStateException("No model loaded")

        memScoped {
            // Tokenize the prompt
            val tokens = tokenize(prompt, mdl)

            // Evaluate the prompt
            llama_eval(ctx, tokens.toCValues(), tokens.size, 0, params.threads)

            // Generate tokens
            var generatedCount = 0
            while (generatedCount < params.maxTokens) {
                // Sample next token
                val nextToken = sampleToken(ctx, params)

                // Check for EOS
                if (nextToken == llama_token_eos(mdl)) break

                // Decode token to text
                val tokenText = decodeToken(nextToken, mdl)
                emit(tokenText)

                // Check stop sequences
                if (params.stopSequences.any { tokenText.contains(it) }) break

                // Evaluate the new token
                llama_eval(ctx, cValuesOf(nextToken), 1, tokens.size + generatedCount, params.threads)
                generatedCount++
            }
        }
    }

    override suspend fun embed(text: String): FloatArray {
        val mdl = model ?: throw IllegalStateException("No model loaded")
        val ctx = context ?: throw IllegalStateException("No model loaded")

        return memScoped {
            val tokens = tokenize(text, mdl)
            llama_eval(ctx, tokens.toCValues(), tokens.size, 0, 1)

            val embeddingSize = llama_n_embd(mdl)
            val embeddings = llama_get_embeddings(ctx)
                ?: throw IllegalStateException("Failed to get embeddings")

            FloatArray(embeddingSize) { i ->
                embeddings[i]
            }
        }
    }

    override fun getModelInfo(): ModelInfo? = currentModelInfo

    // Helper functions

    private fun MemScope.tokenize(text: String, model: CPointer<llama_model>): List<llama_token> {
        val maxTokens = text.length + 1
        val tokensBuffer = allocArray<llama_token>(maxTokens)
        val tokenCount = llama_tokenize(
            model,
            text.cstr.ptr,
            text.length,
            tokensBuffer,
            maxTokens,
            false,
            false
        )
        return List(tokenCount) { tokensBuffer[it] }
    }

    private fun MemScope.sampleToken(
        context: CPointer<llama_context>,
        params: GenerationParams
    ): llama_token {
        val samplingParams = llama_sampler_chain_default_params()
        samplingParams.temperature = params.temperature
        samplingParams.top_p = params.topP
        samplingParams.top_k = params.topK
        samplingParams.penalty_repeat = params.repeatPenalty

        return llama_sample_token(context, samplingParams)
    }

    private fun MemScope.decodeToken(token: llama_token, model: CPointer<llama_model>): String {
        val buffer = allocArray<ByteVar>(256)
        llama_token_to_piece(model, token, buffer, 256, false)
        return buffer.toKString()
    }

    private fun extractModelInfo(model: CPointer<llama_model>): ModelInfo {
        return ModelInfo(
            name = "Unknown", // Extract from metadata if available
            architecture = llama_model_type(model).toString(),
            contextLength = llama_n_ctx_train(model),
            embeddingDimension = llama_n_embd(model),
            parameters = llama_model_n_params(model),
            quantization = "Unknown"
        )
    }
}

actual fun createInferenceEngine(): InferenceEngine = LlamaCppInferenceEngine()
