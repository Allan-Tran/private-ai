package com.privateai.vault.vectorstore

import com.privateai.vault.inference.InferenceEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Epic 3.2 - Contextual Retrieval
 *
 * Implements RAG (Retrieval Augmented Generation) for grounding AI responses
 * in actual documents.
 *
 * Use case: "As a depot manager, I want the AI to reference specific dock rules
 * when I ask about a truck assignment."
 */

/**
 * Retrieved context with relevance information.
 */
data class RetrievedContext(
    val chunks: List<ContextChunk>,
    val totalRetrieved: Int,
    val query: String,
    val retrievalTimeMs: Long
)

/**
 * A single chunk of context with its source information.
 */
data class ContextChunk(
    val content: String,
    val sourceDocument: String,
    val chunkIndex: Int,
    val relevanceScore: Float,
    val metadata: Map<String, String>
)

/**
 * Configuration for retrieval.
 */
data class RetrievalConfig(
    val topK: Int = 5,                    // Number of chunks to retrieve
    val minRelevanceScore: Float = 0.7f,  // Minimum similarity threshold
    val includeMetadata: Boolean = true,  // Include document metadata
    val deduplicate: Boolean = true,      // Remove duplicate chunks
    val maxContextLength: Int = 2048      // Maximum total context tokens
)

/**
 * Contextual retrieval service.
 *
 * Story 3.2 - Contextual Retrieval
 * Story 3.6 - Dynamic Context Window
 */
interface ContextualRetriever {
    /**
     * Retrieve relevant context for a query.
     *
     * @param query User's question or prompt
     * @param config Retrieval configuration
     * @return Retrieved context chunks
     */
    suspend fun retrieveContext(
        query: String,
        config: RetrievalConfig = RetrievalConfig()
    ): RetrievedContext

    /**
     * Retrieve context with streaming progress.
     *
     * Useful for real-time feedback as chunks are found.
     */
    suspend fun retrieveContextStream(
        query: String,
        config: RetrievalConfig = RetrievalConfig()
    ): Flow<ContextChunk>

    /**
     * Format retrieved context for LLM prompt.
     *
     * Story 3.2: Creates a prompt section with relevant document excerpts.
     *
     * @param context Retrieved context
     * @return Formatted prompt section
     */
    fun formatContextForPrompt(context: RetrievedContext): String

    /**
     * Get context window statistics.
     *
     * Story 3.6: Dynamic context window management.
     */
    suspend fun getContextWindowStats(): ContextWindowStats
}

/**
 * Context window statistics.
 */
data class ContextWindowStats(
    val availableTokens: Int,
    val usedTokens: Int,
    val documentCount: Int,
    val oldestDocumentAge: Long,
    val newestDocumentAge: Long
)

/**
 * RAG-based contextual retriever implementation.
 */
class RagContextualRetriever(
    private val vectorStore: VectorStore,
    private val inferenceEngine: InferenceEngine
) : ContextualRetriever {

    override suspend fun retrieveContext(
        query: String,
        config: RetrievalConfig
    ): RetrievedContext {
        val startTime = System.currentTimeMillis()

        try {
            // Step 1: Generate query embedding
            val queryEmbedding = inferenceEngine.embed(query)

            // Step 2: Search vector store for similar chunks
            val searchResults = vectorStore.searchSimilar(
                queryEmbedding = queryEmbedding,
                limit = config.topK * 2, // Retrieve more for deduplication
                threshold = config.minRelevanceScore
            )

            // Step 3: Convert to context chunks
            var contextChunks = searchResults.map { result ->
                ContextChunk(
                    content = result.chunk.content,
                    sourceDocument = result.document.sourcePath,
                    chunkIndex = result.chunk.chunkIndex,
                    relevanceScore = result.similarity,
                    metadata = if (config.includeMetadata) result.document.metadata else emptyMap()
                )
            }

            // Step 4: Deduplicate if requested
            if (config.deduplicate) {
                contextChunks = deduplicateChunks(contextChunks)
            }

            // Step 5: Limit to topK
            contextChunks = contextChunks.take(config.topK)

            // Step 6: Enforce context length limit
            contextChunks = limitContextLength(contextChunks, config.maxContextLength)

            val retrievalTime = System.currentTimeMillis() - startTime

            println("[Retriever] 🔍 Retrieved ${contextChunks.size} chunks for query in ${retrievalTime}ms")

            return RetrievedContext(
                chunks = contextChunks,
                totalRetrieved = searchResults.size,
                query = query,
                retrievalTimeMs = retrievalTime
            )

        } catch (e: Exception) {
            println("[Retriever] ❌ Context retrieval failed: ${e.message}")
            return RetrievedContext(
                chunks = emptyList(),
                totalRetrieved = 0,
                query = query,
                retrievalTimeMs = System.currentTimeMillis() - startTime
            )
        }
    }

    override suspend fun retrieveContextStream(
        query: String,
        config: RetrievalConfig
    ): Flow<ContextChunk> = flow {
        val context = retrieveContext(query, config)
        context.chunks.forEach { chunk ->
            emit(chunk)
        }
    }

    override fun formatContextForPrompt(context: RetrievedContext): String {
        if (context.chunks.isEmpty()) {
            return ""
        }

        val builder = StringBuilder()
        builder.append("=== RELEVANT CONTEXT ===\n\n")
        builder.append("Retrieved ${context.chunks.size} relevant document excerpts:\n\n")

        context.chunks.forEachIndexed { index, chunk ->
            builder.append("--- Context ${index + 1} ---\n")
            builder.append("Source: ${chunk.sourceDocument}\n")
            builder.append("Relevance: ${(chunk.relevanceScore * 100).toInt()}%\n")
            if (chunk.metadata.isNotEmpty()) {
                val tags = chunk.metadata["tags"]
                if (tags != null) {
                    builder.append("Tags: $tags\n")
                }
            }
            builder.append("\n${chunk.content}\n\n")
        }

        builder.append("=== END CONTEXT ===\n\n")
        builder.append("Use the above context to answer the user's question. ")
        builder.append("Cite specific sources when possible.\n\n")
        builder.append("User Question: ${context.query}\n")

        return builder.toString()
    }

    override suspend fun getContextWindowStats(): ContextWindowStats {
        // Would query actual statistics from vector store
        return ContextWindowStats(
            availableTokens = 8192,
            usedTokens = 0,
            documentCount = 0,
            oldestDocumentAge = 0,
            newestDocumentAge = 0
        )
    }

    private fun deduplicateChunks(chunks: List<ContextChunk>): List<ContextChunk> {
        val seen = mutableSetOf<String>()
        return chunks.filter { chunk ->
            val key = "${chunk.sourceDocument}:${chunk.chunkIndex}"
            if (seen.contains(key)) {
                false
            } else {
                seen.add(key)
                true
            }
        }
    }

    private fun limitContextLength(
        chunks: List<ContextChunk>,
        maxTokens: Int
    ): List<ContextChunk> {
        var currentTokens = 0
        val result = mutableListOf<ContextChunk>()

        for (chunk in chunks) {
            val chunkTokens = TextChunker.estimateTokenCount(chunk.content)
            if (currentTokens + chunkTokens > maxTokens) {
                break
            }
            result.add(chunk)
            currentTokens += chunkTokens
        }

        return result
    }
}

/**
 * RAG orchestrator that combines ingestion and retrieval.
 *
 * Story 3.5 - Real-Time Upload & Chat
 * Story 3.6 - Dynamic Context Window
 */
class RagOrchestrator(
    private val ingestor: DocumentIngestor,
    private val retriever: ContextualRetriever,
    private val inferenceEngine: InferenceEngine
) {
    /**
     * Upload a document and immediately make it available for chat.
     *
     * Story 3.5: "I want to 'Upload & Chat' immediately, so that I can get
     * answers about a document I received 30 seconds ago."
     */
    suspend fun uploadAndChat(
        content: String,
        metadata: DocumentMetadata,
        query: String
    ): Flow<RagChatProgress> = flow {
        // Step 1: Ingest document
        emit(RagChatProgress.Ingesting("Processing ${metadata.fileName}..."))

        var ingestedDocId: String? = null
        ingestor.ingestText(content, metadata).collect { progress ->
            when (progress) {
                is IngestionProgress.Complete -> {
                    ingestedDocId = progress.documentId
                    emit(RagChatProgress.Ingesting("✅ Document ready"))
                }
                is IngestionProgress.Error -> {
                    emit(RagChatProgress.Error(progress.error))
                    return@collect
                }
                else -> {
                    // Continue ingestion
                }
            }
        }

        if (ingestedDocId == null) {
            emit(RagChatProgress.Error("Failed to ingest document"))
            return@flow
        }

        // Step 2: Retrieve relevant context
        emit(RagChatProgress.Retrieving("Finding relevant context..."))
        val context = retriever.retrieveContext(query)

        if (context.chunks.isEmpty()) {
            emit(RagChatProgress.NoContext("No relevant context found"))
            return@flow
        }

        emit(RagChatProgress.ContextRetrieved(context.chunks.size))

        // Step 3: Generate response with context
        emit(RagChatProgress.Generating("Generating response..."))

        val promptWithContext = retriever.formatContextForPrompt(context)

        // Stream LLM response
        inferenceEngine.generateStream(
            prompt = promptWithContext,
            params = com.privateai.vault.inference.GenerationParams(
                maxTokens = 512,
                temperature = 0.7f
            )
        ).collect { token ->
            emit(RagChatProgress.GeneratingToken(token))
        }

        emit(RagChatProgress.Complete)
    }
}

/**
 * Progress updates for RAG chat.
 */
sealed class RagChatProgress {
    data class Ingesting(val message: String) : RagChatProgress()
    data class Retrieving(val message: String) : RagChatProgress()
    data class NoContext(val message: String) : RagChatProgress()
    data class ContextRetrieved(val chunkCount: Int) : RagChatProgress()
    data class Generating(val message: String) : RagChatProgress()
    data class GeneratingToken(val token: String) : RagChatProgress()
    data object Complete : RagChatProgress()
    data class Error(val message: String) : RagChatProgress()
}
