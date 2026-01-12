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
 */
class SqliteVectorStore(
    private val driver: SqlDriver,
    private val redactor: PrivacyRedactor
) : VectorStore {

    private val database = VectorDatabase(driver)
    private val queries = database.vectorStoreQueries

    init {
        println("[VectorStore] 🔒 Initialized with encryption and privacy redaction")
        println("[VectorStore]    Redaction patterns: ${redactor.getRedactionPatterns().joinToString(", ")}")
    }

    override suspend fun initialize() {
        withContext(Dispatchers.IO) {
            // Enable sqlite-vec extension
            // Note: You'll need to load the extension library
            driver.execute(null, "SELECT load_extension('vec0')", 0)

            // Create virtual table for vector search
            // Using FAISS-like index for efficient similarity search
            driver.execute(
                null,
                """
                CREATE VIRTUAL TABLE IF NOT EXISTS vec_chunks USING vec0(
                    chunk_id TEXT PRIMARY KEY,
                    embedding FLOAT[384]
                )
                """.trimIndent(),
                0
            )
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
            // TODO: Implement actual vector similarity search with sqlite-vec extension
            // For now, return empty list until extension is properly configured
            // This requires:
            // 1. Loading sqlite-vec extension (.dll/.so/.dylib)
            // 2. Creating virtual table with vec0
            // 3. Implementing proper vector search query

            println("[VectorStore] ⚠️  Vector search not yet implemented - requires sqlite-vec extension")
            println("[VectorStore]    See: https://github.com/asg017/sqlite-vec")

            emptyList()
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
