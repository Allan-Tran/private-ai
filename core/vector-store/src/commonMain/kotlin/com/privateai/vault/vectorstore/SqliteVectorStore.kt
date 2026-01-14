package com.privateai.vault.vectorstore

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.db.SqlDriver
import com.privateai.vault.vectorstore.db.VectorDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * SQLite implementation of VectorStore using sqlite-vec extension.
 *
 * Epic 2 (The Vault) - Sovereign AI Implementation:
 * - Uses encrypted SQLite database via SQLCipher (Story 2.1)
 * - Redacts sensitive PII before storage (Story 2.2)
 * - Defense-in-depth: encryption + redaction
 *
 * DIMENSION HANDLING:
 * - If embeddingDimension is provided, validates all embeddings match
 * - If null, auto-detects from first embedding and creates vector table dynamically
 * - This allows seamless switching between models (TinyLlama 2048, Llama 3 3072, etc.)
 *
 * @param driver SQLDriver with encryption enabled
 * @param redactor Privacy redactor to mask sensitive data before storage
 * @param embeddingDimension Dimension of embeddings, or null for auto-detection
 */
class SqliteVectorStore(
    private val driver: SqlDriver,
    private val redactor: PrivacyRedactor,
    private var embeddingDimension: Int? = null
) : VectorStore {

    private val database = VectorDatabase(driver)
    private val queries = database.vectorStoreQueries

    // Track whether vector extension was successfully loaded
    private var vectorExtensionLoaded = false

    // Track whether dimensions have been locked (after first insert)
    private var dimensionsLocked = false

    init {
        println("[VectorStore] 🔒 Initialized with encryption and privacy redaction")
        println("[VectorStore]    Redaction patterns: ${redactor.getRedactionPatterns().joinToString(", ")}")
        if (embeddingDimension != null) {
            println("[VectorStore]    Embedding dimension: $embeddingDimension (fixed)")
        } else {
            println("[VectorStore]    Embedding dimension: auto-detect on first insert")
        }
    }

    /**
     * Get the current embedding dimension (may be null if not yet detected).
     */
    fun getEmbeddingDimension(): Int? = embeddingDimension

override suspend fun initialize(requireVectorExtension: Boolean) {
        withContext(Dispatchers.IO) {
            try {
                // Try multiple possible extension paths
                var extensionLoaded = false
                val possibleExtensions = listOf(
                    "vec0",                                    // Already in library path
                    "./vec0",                                  // Current directory
                    "./sqlite-vec/vec0",                       // Common subdirectory
                    System.getProperty("user.dir") + "/vec0"   // User directory
                )

                for (extPath in possibleExtensions) {
                    try {
                        driver.execute(null, "SELECT load_extension('$extPath')", 0)
                        extensionLoaded = true
                        vectorExtensionLoaded = true
                        println("[VectorStore] ✅ Vector extension loaded from: $extPath")
                        break
                    } catch (e: Exception) {
                        // Try next path
                    }
                }

                if (!extensionLoaded) {
                    println("[VectorStore] ⚠️  sqlite-vec extension not found")
                    println("[VectorStore]    Tried paths: ${possibleExtensions.joinToString(", ")}")
                    vectorExtensionLoaded = false
                    if (requireVectorExtension) {
                        println("[VectorStore]    ERROR: Extension required but not found")
                        println("[VectorStore]    Download from: https://github.com/asg017/sqlite-vec/releases")
                        println("[VectorStore]    Place vec0.dll (Windows) / vec0.dylib (Mac) / vec0.so (Linux) in project root")
                        throw IllegalStateException("sqlite-vec extension required for vector search")
                    } else {
                        println("[VectorStore]    Continuing without vector search capability (test mode)")
                        return@withContext
                    }
                }

                // If dimension is already known, create vector table now
                // Otherwise, defer until first insert (auto-detection)
                if (embeddingDimension != null) {
                    createVectorTable(embeddingDimension!!)
                } else {
                    // Try to detect dimension from existing chunks
                    val existingDimension = detectExistingDimension()
                    if (existingDimension != null) {
                        embeddingDimension = existingDimension
                        dimensionsLocked = true
                        createVectorTable(existingDimension)
                    } else {
                        println("[VectorStore] 📏 Vector table creation deferred until first embedding insert")
                    }
                }
            } catch (e: Exception) {
                if (requireVectorExtension) {
                    println("[VectorStore] ❌ Vector extension initialization failed: ${e.message}")
                    throw e
                } else {
                    println("[VectorStore] ⚠️  Skipping vector extension (test mode): ${e.message}")
                }
            }
        }
    }

    /**
     * Create the vector virtual table with the specified dimension.
     */
    private fun createVectorTable(dimension: Int) {
        if (!vectorExtensionLoaded) return

        try {
            driver.execute(
                null,
                """
                CREATE VIRTUAL TABLE IF NOT EXISTS vec_chunks USING vec0(
                    chunk_id TEXT PRIMARY KEY,
                    embedding FLOAT[$dimension]
                )
                """.trimIndent(),
                0
            )
            println("[VectorStore] ✅ Vector table created with $dimension dimensions")
            dimensionsLocked = true
        } catch (e: Exception) {
            println("[VectorStore] ⚠️  Vector table creation failed: ${e.message}")
        }
    }

    /**
     * Detect embedding dimension from existing chunks in the database.
     */
    private fun detectExistingDimension(): Int? {
        return try {
            val chunks = queries.getAllChunks().executeAsList()
            if (chunks.isEmpty()) return null

            val firstChunkWithEmbedding = chunks.firstOrNull { it.embedding != null }
            firstChunkWithEmbedding?.embedding?.let { blob ->
                val dimension = blob.size / 4 // 4 bytes per float
                println("[VectorStore] 📏 Detected existing embedding dimension: $dimension")
                dimension
            }
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun addDocument(document: Document, chunks: List<DocumentChunk>) {
        withContext(Dispatchers.IO) {
            // Epic 2.2: Redact sensitive information before storage
            val redactedContent = redactor.redact(document.content)

            // Log if redaction occurred (for security audit)
            if (redactedContent != document.content) {
                println("[VectorStore] ⚠️  Sensitive data detected and redacted in document ${document.id}")
            }

            // Auto-detect embedding dimension from first chunk if not set
            if (chunks.isNotEmpty() && embeddingDimension == null) {
                val detectedDimension = chunks.first().embedding.size
                embeddingDimension = detectedDimension
                println("[VectorStore] 📏 Auto-detected embedding dimension: $detectedDimension")

                // Create vector table with detected dimension
                if (vectorExtensionLoaded) {
                    createVectorTable(detectedDimension)
                }
            }

            // Insert document with redacted content
            queries.insertDocument(
                id = document.id,
                content = redactedContent,
                source_path = document.sourcePath,
                metadata = Json.encodeToString(document.metadata),
                chunk_count = chunks.size.toLong(),
                created_at = document.createdAt,
                updated_at = document.updatedAt
            )

            // Insert chunks with embeddings (also redacted)
            chunks.forEach { chunk ->
                // Validate embedding dimension matches configured/detected dimension
                val expectedDim = embeddingDimension
                if (expectedDim != null && chunk.embedding.size != expectedDim) {
                    throw IllegalArgumentException(
                        "Embedding dimension mismatch: expected $expectedDim, got ${chunk.embedding.size}. " +
                        "All embeddings in a VectorStore must have the same dimension. " +
                        "If switching models, create a new database or clear existing embeddings."
                    )
                }

                val redactedChunkContent = redactor.redact(chunk.content)

                queries.insertChunk(
                    id = chunk.id,
                    document_id = chunk.documentId,
                    content = redactedChunkContent,
                    chunk_index = chunk.chunkIndex.toLong(),
                    token_count = chunk.tokenCount.toLong(),
                    embedding = floatArrayToBlob(chunk.embedding),
                    created_at = chunk.createdAt
                )

                // Insert into vector index
                insertIntoVectorIndex(chunk.id, chunk.embedding)
            }
        }
    }

    override suspend fun removeDocument(documentId: String) {
        withContext(Dispatchers.IO) {
            // Get chunks to remove from vector index
            val chunks = queries.getChunksByDocument(documentId).executeAsList()

            // Remove from vector index
            chunks.forEach { chunk ->
                removeFromVectorIndex(chunk.id)
            }

            // Delete chunks (cascade will handle this, but explicit is clear)
            queries.deleteChunksByDocument(documentId)

            // Delete document
            queries.deleteDocument(documentId)
        }
    }

    override suspend fun getDocument(documentId: String): Document? {
        return withContext(Dispatchers.IO) {
            queries.getDocumentById(documentId).executeAsOneOrNull()?.let {
                Document(
                    id = it.id,
                    content = it.content,
                    sourcePath = it.source_path,
                    metadata = it.metadata?.let { Json.decodeFromString(it) } ?: emptyMap(),
                    chunkCount = it.chunk_count.toInt(),
                    createdAt = it.created_at,
                    updatedAt = it.updated_at
                )
            }
        }
    }

    override suspend fun searchSimilar(
        queryEmbedding: FloatArray,
        limit: Int,
        threshold: Float
    ): List<SearchResult> {
        return withContext(Dispatchers.IO) {
            try {
                // Validate query embedding dimension if we have a known dimension
                val expectedDim = embeddingDimension
                if (expectedDim != null && queryEmbedding.size != expectedDim) {
                    throw IllegalArgumentException(
                        "Query embedding dimension mismatch: expected $expectedDim, got ${queryEmbedding.size}. " +
                        "Ensure you're using the same model for embedding queries as was used for document ingestion."
                    )
                }

                // Fetch all chunks and compute cosine similarity
                // Note: For large datasets, sqlite-vec extension would be more efficient
                // This fallback implementation works without the extension
                val allChunks = queries.getAllChunks().executeAsList()

                if (allChunks.isEmpty()) {
                    println("[VectorStore] 🔍 No chunks in database to search")
                    return@withContext emptyList()
                }

                // Compute similarity for each chunk
                val similarities = allChunks.mapNotNull { chunk ->
                    val embedding = chunk.embedding?.let { blobToFloatArray(it) }
                    if (embedding == null || embedding.size != embeddingDimension) {
                        null
                    } else {
                        val similarity = cosineSimilarity(queryEmbedding, embedding)
                        if (similarity >= threshold) {
                            chunk to similarity
                        } else {
                            null
                        }
                    }
                }

                // Sort by similarity (descending) and take top results
                val topResults = similarities
                    .sortedByDescending { it.second }
                    .take(limit)

                // Build search results with full document info
                val searchResults = topResults.mapNotNull { (chunk, similarity) ->
                    val document = queries.getDocumentById(chunk.document_id).executeAsOneOrNull()
                    if (document != null) {
                        SearchResult(
                            document = Document(
                                id = document.id,
                                content = document.content,
                                sourcePath = document.source_path,
                                metadata = document.metadata?.let { Json.decodeFromString(it) } ?: emptyMap(),
                                chunkCount = document.chunk_count.toInt(),
                                createdAt = document.created_at,
                                updatedAt = document.updated_at
                            ),
                            chunk = DocumentChunk(
                                id = chunk.id,
                                documentId = chunk.document_id,
                                content = chunk.content,
                                chunkIndex = chunk.chunk_index.toInt(),
                                tokenCount = chunk.token_count.toInt(),
                                embedding = blobToFloatArray(chunk.embedding ?: ByteArray(0)),
                                createdAt = chunk.created_at
                            ),
                            similarity = similarity
                        )
                    } else {
                        null
                    }
                }

                println("[VectorStore] 🔍 Found ${searchResults.size} similar chunks (threshold: $threshold)")
                return@withContext searchResults

            } catch (e: Exception) {
                println("[VectorStore] ❌ Vector search failed: ${e.message}")
                e.printStackTrace()
                return@withContext emptyList()
            }
        }
    }

    /**
     * Compute cosine similarity between two vectors.
     * Returns a value between -1 and 1, where 1 is identical.
     */
    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f

        var dotProduct = 0f
        var normA = 0f
        var normB = 0f

        for (i in a.indices) {
            dotProduct += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }

        val denominator = kotlin.math.sqrt(normA) * kotlin.math.sqrt(normB)
        return if (denominator > 0) dotProduct / denominator else 0f
    }

    override suspend fun createSession(session: Session) {
        withContext(Dispatchers.IO) {
            queries.insertSession(
                id = session.id,
                name = session.name,
                description = session.description,
                created_at = session.createdAt,
                last_accessed = session.lastAccessed
            )
        }
    }

    override suspend fun getSessions(): List<Session> {
        return withContext(Dispatchers.IO) {
            queries.getAllSessions().executeAsList().map {
                Session(
                    id = it.id,
                    name = it.name,
                    description = it.description,
                    createdAt = it.created_at,
                    lastAccessed = it.last_accessed
                )
            }
        }
    }

    override suspend fun getSession(sessionId: String): Session? {
        return withContext(Dispatchers.IO) {
            queries.getSessionById(sessionId).executeAsOneOrNull()?.let {
                Session(
                    id = it.id,
                    name = it.name,
                    description = it.description,
                    createdAt = it.created_at,
                    lastAccessed = it.last_accessed
                )
            }
        }
    }

    override suspend fun addDocumentToSession(sessionId: String, documentId: String) {
        withContext(Dispatchers.IO) {
            queries.addDocumentToSession(
                session_id = sessionId,
                document_id = documentId,
                added_at = System.currentTimeMillis()
            )
            queries.updateSessionAccess(System.currentTimeMillis(), sessionId)
        }
    }

    override suspend fun removeDocumentFromSession(sessionId: String, documentId: String) {
        withContext(Dispatchers.IO) {
            queries.removeDocumentFromSession(sessionId, documentId)
        }
    }

    override suspend fun getSessionDocuments(sessionId: String): List<Document> {
        return withContext(Dispatchers.IO) {
            queries.getDocumentsBySession(sessionId).executeAsList().map {
                Document(
                    id = it.id,
                    content = it.content,
                    sourcePath = it.source_path,
                    metadata = it.metadata?.let { Json.decodeFromString(it) } ?: emptyMap(),
                    chunkCount = it.chunk_count.toInt(),
                    createdAt = it.created_at,
                    updatedAt = it.updated_at
                )
            }
        }
    }

    override suspend fun deleteSession(sessionId: String) {
        withContext(Dispatchers.IO) {
            queries.deleteSession(sessionId)
        }
    }

    // Helper functions for vector operations

    private fun insertIntoVectorIndex(chunkId: String, embedding: FloatArray) {
        // Skip if vector extension not loaded
        if (!vectorExtensionLoaded) return

        try {
            val sql = "INSERT INTO vec_chunks (chunk_id, embedding) VALUES (?, ?)"
            driver.execute(null, sql, 2) {
                bindString(0, chunkId)
                bindBytes(1, floatArrayToBlob(embedding))
            }
        } catch (e: Exception) {
            println("[VectorStore] ⚠️  Failed to insert into vector index: ${e.message}")
        }
    }

    private fun removeFromVectorIndex(chunkId: String) {
        // Skip if vector extension not loaded
        if (!vectorExtensionLoaded) return

        try {
            val sql = "DELETE FROM vec_chunks WHERE chunk_id = ?"
            driver.execute(null, sql, 1) {
                bindString(0, chunkId)
            }
        } catch (e: Exception) {
            println("[VectorStore] ⚠️  Failed to remove from vector index: ${e.message}")
        }
    }

    private fun floatArrayToBlob(array: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(array.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        array.forEach { buffer.putFloat(it) }
        return buffer.array()
    }

    private fun blobToFloatArray(blob: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(blob.size / 4) { buffer.getFloat() }
    }
}

/**
 * Creates an encrypted SQLDriver for the vector store.
 *
 * Epic 2.1 - Encryption Story
 * @param path Database file path
 * @param passphrase Encryption passphrase for SQLCipher
 */
expect fun createVectorStoreDriver(path: String, passphrase: String): SqlDriver
