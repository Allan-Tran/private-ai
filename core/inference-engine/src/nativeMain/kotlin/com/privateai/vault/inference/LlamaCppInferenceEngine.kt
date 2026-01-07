package com.privateai.vault.inference

import com.privateai.vault.llamacpp.*
import kotlinx.cinterop.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Native implementation of InferenceEngine using llama.cpp via C interop.
 * Updated to use modern llama_batch/llama_decode API (post-2023).
 *
 * CRITICAL: This uses the NEW API - llama_eval was removed in late 2023.
 */
@OptIn(ExperimentalForeignApi::class)
class LlamaCppInferenceEngine : InferenceEngine {
    private var context: CPointer<llama_context>? = null
    private var model: CPointer<llama_model>? = null
    private var currentModelInfo: ModelInfo? = null
    private var isBackendInitialized = false

    override suspend fun loadModel(modelPath: String, params: ModelParams): Boolean {
        return try {
            // Cleanup any existing model
            if (isModelLoaded()) {
                unloadModel()
            }

            // Initialize llama backend (once per process)
            if (!isBackendInitialized) {
                llama_backend_init()
                isBackendInitialized = true
            }

            // Load model
            val loadedModel = loadModelInternal(modelPath, params) ?: return false
            model = loadedModel

            // Create context
            val loadedContext = createContextInternal(loadedModel, params) ?: return false
            context = loadedContext

            // Extract model info
            currentModelInfo = extractModelInfo(loadedModel)

            true
        } catch (e: Exception) {
            unloadModel()
            false
        }
    }

    override suspend fun unloadModel() {
        try {
            context?.let { llama_free(it) }
            model?.let { llama_free_model(it) }
        } finally {
            context = null
            model = null
            currentModelInfo = null
        }
    }

    override fun isModelLoaded(): Boolean {
        return context != null && model != null
    }

    override fun generateStream(prompt: String, params: GenerationParams): Flow<String> = flow {
        val ctx = context ?: throw IllegalStateException("No model loaded")
        val mdl = model ?: throw IllegalStateException("No model loaded")

        try {
            // Tokenize the prompt
            val tokens = tokenizePrompt(mdl, prompt)

            // Evaluate prompt using modern batch API
            evaluateTokensBatch(ctx, tokens, 0)

            // Generate tokens
            var generatedCount = 0
            while (generatedCount < params.maxTokens) {
                // Sample next token
                val nextToken = sampleNextToken(ctx, mdl, params)

                // Check for EOS
                if (isEndOfSequence(mdl, nextToken)) break

                // Decode token to text
                val tokenText = decodeToken(mdl, nextToken)
                emit(tokenText)

                // Check stop sequences
                if (shouldStop(tokenText, params.stopSequences)) break

                // Evaluate the new token using batch API
                evaluateTokensBatch(ctx, listOf(nextToken), tokens.size + generatedCount)
                generatedCount++
            }
        } catch (e: Exception) {
            emit("\n[Generation Error: ${e.message}]")
        }
    }

    override suspend fun embed(text: String): FloatArray {
        val mdl = model ?: throw IllegalStateException("No model loaded")
        val ctx = context ?: throw IllegalStateException("No model loaded")

        return try {
            val tokens = tokenizePrompt(mdl, text)
            evaluateTokensBatch(ctx, tokens, 0)

            val embeddingSize = llama_n_embd(mdl)
            val embeddings = llama_get_embeddings(ctx)
                ?: throw IllegalStateException("Failed to get embeddings")

            FloatArray(embeddingSize) { i -> embeddings[i] }
        } catch (e: Exception) {
            throw IllegalStateException("Embedding generation failed: ${e.message}")
        }
    }

    override fun getModelInfo(): ModelInfo? = currentModelInfo

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

        // Load model from file - pin string memory
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

        // Enable FP16 for KV cache (better performance on Apple Silicon)
        ctxParams.type_k = GGML_TYPE_F16
        ctxParams.type_v = GGML_TYPE_F16

        llama_new_context_with_model(model, ctxParams)
    }

    /**
     * Modern API: Use llama_batch and llama_decode instead of deprecated llama_eval.
     * This is the CRITICAL fix for the deprecated API issue.
     */
    private fun evaluateTokensBatch(
        context: CPointer<llama_context>,
        tokens: List<llama_token>,
        position: Int
    ) = memScoped {
        // Create batch for tokens
        val batch = llama_batch_init(tokens.size, 0, 1)

        try {
            // Add tokens to batch
            tokens.forEachIndexed { idx, token ->
                val seq_ids = allocArray<llama_seq_id>(1)
                seq_ids[0] = 0

                llama_batch_add(
                    batch = batch,
                    id = token,
                    pos = position + idx,
                    seq_ids = seq_ids,
                    logits = (idx == tokens.size - 1) // Only compute logits for last token
                )
            }

            // Decode batch (replaces llama_eval)
            val result = llama_decode(context, batch)

            if (result != 0) {
                throw IllegalStateException("llama_decode failed with code: $result")
            }
        } finally {
            llama_batch_free(batch)
        }
    }

    private fun tokenizePrompt(
        model: CPointer<llama_model>,
        prompt: String
    ): List<llama_token> = memScoped {
        val maxTokens = prompt.length + 256
        val tokensBuffer = allocArray<llama_token>(maxTokens)

        val tokenCount = prompt.usePinned { pinnedPrompt ->
            llama_tokenize(
                model,
                pinnedPrompt.addressOf(0).reinterpret(),
                prompt.length,
                tokensBuffer,
                maxTokens,
                true,   // add_special (add BOS token)
                false   // parse_special
            )
        }

        if (tokenCount < 0) {
            throw IllegalStateException("Tokenization failed")
        }

        List(tokenCount) { i -> tokensBuffer[i] }
    }

    private fun sampleNextToken(
        context: CPointer<llama_context>,
        model: CPointer<llama_model>,
        params: GenerationParams
    ): llama_token = memScoped {
        // Get logits for last token
        val logits = llama_get_logits_ith(context, -1)
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

        // Apply sampling (top-k, top-p, temperature)
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

    private fun extractModelInfo(model: CPointer<llama_model>): ModelInfo {
        return ModelInfo(
            name = "GGUF Model",
            architecture = llama_model_type(model).toString(),
            contextLength = llama_n_ctx_train(model),
            embeddingDimension = llama_n_embd(model),
            parameters = llama_model_n_params(model).toLong(),
            quantization = "Unknown"
        )
    }
}

actual fun createInferenceEngine(): InferenceEngine = LlamaCppInferenceEngine()
