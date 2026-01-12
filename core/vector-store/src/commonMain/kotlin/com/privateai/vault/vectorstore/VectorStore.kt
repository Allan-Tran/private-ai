package com.privateai.vault.vectorstore

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * Vector Store for RAG (Retrieval Augmented Generation).
 * Stores document chunks with embeddings for semantic search.
 *
 * Philosophy: All data stays on device. No cloud sync.
 */
interface VectorStore {
    /**
     * Initialize the vector store with sqlite-vec extension.
     * @param requireVectorExtension If true, throws exception if extension not found. If false, continues without vector search.
     */
    suspend fun initialize(requireVectorExtension: Boolean = true)

    /**
     * Add a document and its chunks to the store.
     */
    suspend fun addDocument(document: Document, chunks: List<DocumentChunk>)

    /**
     * Remove a document and all its chunks.
     */
    suspend fun removeDocument(documentId: String)

    /**
     * Get a document by ID.
     */
    suspend fun getDocument(documentId: String): Document?

    /**
     * Perform semantic search using vector similarity.
     * @param queryEmbedding The embedding vector of the search query
     * @param limit Maximum number of results to return
     * @param threshold Minimum similarity score (0.0 to 1.0)
     */
    suspend fun searchSimilar(
        queryEmbedding: FloatArray,
        limit: Int = 5,
        threshold: Float = 0.7f
    ): List<SearchResult>

    /**
     * Create a new Active Desk session.
     */
    suspend fun createSession(session: Session)

    /**
     * Get all sessions.
     */
    suspend fun getSessions(): List<Session>

    /**
     * Get a session by ID.
     */
    suspend fun getSession(sessionId: String): Session?

    /**
     * Add a document to a session (expands AI context).
     */
    suspend fun addDocumentToSession(sessionId: String, documentId: String)

    /**
     * Remove a document from a session.
     */
    suspend fun removeDocumentFromSession(sessionId: String, documentId: String)

    /**
     * Get all documents in a session.
     */
    suspend fun getSessionDocuments(sessionId: String): List<Document>

    /**
     * Delete a session.
     */
    suspend fun deleteSession(sessionId: String)
}

@Serializable
data class Document(
    val id: String,
    val content: String,
    val sourcePath: String,
    val metadata: Map<String, String> = emptyMap(),
    val chunkCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Serializable
data class DocumentChunk(
    val id: String,
    val documentId: String,
    val content: String,
    val chunkIndex: Int,
    val tokenCount: Int,
    val embedding: FloatArray,
    val createdAt: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as DocumentChunk

        if (id != other.id) return false
        if (!embedding.contentEquals(other.embedding)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + embedding.contentHashCode()
        return result
    }
}

@Serializable
data class Session(
    val id: String,
    val name: String,
    val description: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val lastAccessed: Long = System.currentTimeMillis()
)

@Serializable
data class SearchResult(
    val chunk: DocumentChunk,
    val document: Document,
    val similarity: Float
)

/**
 * Factory function for creating an encrypted VectorStore with privacy redaction.
 *
 * Epic 2 (The Vault) - Sovereign AI Implementation
 * @param databasePath Path to the encrypted database file
 * @param passphrase Encryption passphrase for SQLCipher
 * @param redactor Privacy redactor for masking sensitive data (defaults to RegexPrivacyRedactor)
 */
expect fun createVectorStore(
    databasePath: String,
    passphrase: String,
    redactor: PrivacyRedactor = RegexPrivacyRedactor()
): VectorStore
