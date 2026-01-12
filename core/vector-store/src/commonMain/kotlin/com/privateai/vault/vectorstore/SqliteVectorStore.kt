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
 * @param driver SQLDriver with encryption enabled
 * @param redactor Privacy redactor to mask sensitive data before storage
 * @param embeddingDimension Dimension of embeddings (3072 for Llama 3.2 3B, 2048 for TinyLlama)
 */
class SqliteVectorStore(
    private val driver: SqlDriver,
    private val redactor: PrivacyRedactor,
    private val embeddingDimension: Int = 3072
) : VectorStore {

    private val database = VectorDatabase(driver)
    private val queries = database.vectorStoreQueries

    init {
        println("[VectorStore] 🔒 Initialized with encryption and privacy redaction")
        println("[VectorStore]    Redaction patterns: ${redactor.getRedactionPatterns().joinToString(", ")}")
        println("[VectorStore]    Embedding dimension: $embeddingDimension")
    }

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
                        println("[VectorStore] ✅ Vector extension loaded from: $extPath")
                        break
                    } catch (e: Exception) {
                        // Try next path
                    }
                }

                if (!extensionLoaded) {
                    println("[VectorStore] ⚠️  sqlite-vec extension not found")
                    println("[VectorStore]    Tried paths: ${possibleExtensions.joinToString(", ")}")
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

                // Create virtual table with dynamic embedding dimension
                driver.execute(
                    null,
                    """
                    CREATE VIRTUAL TABLE IF NOT EXISTS vec_chunks USING vec0(
                        chunk_id TEXT PRIMARY KEY,
                        embedding FLOAT[$embeddingDimension]
                    )
                    """.trimIndent(),
                    0
                )
                println("[VectorStore] ✅ Vector table created with $embeddingDimension dimensions")
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

    override suspend fun addDocument(document: Document, chunks: List<DocumentChunk>) {
        withContext(Dispatchers.IO) {
            // Epic 2.2: Redact sensitive information before storage
            val redactedContent = redactor.redact(document.content)

            // Log if redaction occurred (for security audit)
            if (redactedContent != document.content) {
                println("[VectorStore] ⚠️  Sensitive data detected and redacted in document ${document.id}")
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
                // Validate embedding dimension matches configured dimension
                if (chunk.embedding.size != embeddingDimension) {
                    throw IllegalArgumentException(
                        "Embedding dimension mismatch: expected $embeddingDimension, got ${chunk.embedding.size}. " +
                        "Ensure your model's embedding dimension matches the SqliteVectorStore configuration."
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
                // Validate query embedding dimension
                if (queryEmbedding.size != embeddingDimension) {
                    throw IllegalArgumentException(
                        "Query embedding dimension mismatch: expected $embeddingDimension, got ${queryEmbedding.size}"
                    )
                }

                val searchResults = mutableListOf<SearchResult>()

                // NOTE: Vector search implementation using sqlite-vec
                // The sqlite-vec extension provides MATCH operator for vector similarity
                // However, SQLDelight doesn't support virtual table operations in .sq files
                //
                // This implementation is a stub that documents the intended behavior.
                // For full functionality:
                // 1. Ensure sqlite-vec extension is loaded (done in initialize())
                // 2. Ensure vec_chunks virtual table is populated (done in addDocument())
                // 3. Use JNI/FFI bindings or custom SQL execution to query vec_chunks
                //
                // Query structure (for reference):
                // SELECT chunk_id, distance
                // FROM vec_chunks
                // WHERE embedding MATCH ?
                // ORDER BY distance
                // LIMIT ?
                //
                // Then join results with chunks and documents tables.

                println("[VectorStore] ⚠️  Vector search currently stubbed")
                println("[VectorStore]    To enable: implement custom SQL execution for vec_chunks MATCH query")
                println("[VectorStore]    See: https://github.com/asg017/sqlite-vec for query examples")
                println("[VectorStore]    Returning empty results until full integration")

                return@withContext searchResults

            } catch (e: Exception) {
                println("[VectorStore] ❌ Vector search failed: ${e.message}")
                e.printStackTrace()
                return@withContext emptyList()
            }
        }
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
        val sql = "INSERT INTO vec_chunks (chunk_id, embedding) VALUES (?, ?)"
        driver.execute(null, sql, 2) {
            bindString(0, chunkId)
            bindBytes(1, floatArrayToBlob(embedding))
        }
    }

    private fun removeFromVectorIndex(chunkId: String) {
        val sql = "DELETE FROM vec_chunks WHERE chunk_id = ?"
        driver.execute(null, sql, 1) {
            bindString(0, chunkId)
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
