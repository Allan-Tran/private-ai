package com.privateai.vault.features.sessionanalyst.data

import com.privateai.vault.features.sessionanalyst.domain.TrainingSession
import com.privateai.vault.features.sessionanalyst.domain.SessionNote
import com.privateai.vault.vectorstore.VectorStore
import com.privateai.vault.vectorstore.Session
import com.privateai.vault.vectorstore.Document
import com.privateai.vault.vectorstore.DocumentChunk
import com.privateai.vault.inference.InferenceEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * DATA LAYER: Repository for training session persistence.
 *
 * Responsibilities:
 * - Store/retrieve training sessions
 * - Chunk and embed session notes
 * - Interface with vector store and inference engine
 */
class SessionRepository(
    private val vectorStore: VectorStore,
    private val inferenceEngine: InferenceEngine
) {
    /**
     * Create a new training session in the Active Desk.
     */
    suspend fun createSession(session: TrainingSession): Result<Unit> {
        return try {
            val dbSession = Session(
                id = session.id,
                name = session.fighterName + " - " + session.date,
                description = session.notes,
                createdAt = System.currentTimeMillis()
            )
            vectorStore.createSession(dbSession)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Add a session note (from video transcript, coach notes, etc.)
     * This will chunk and embed the content for RAG.
     */
    suspend fun addSessionNote(note: SessionNote): Result<Unit> {
        return try {
            // Create document
            val document = Document(
                id = note.id,
                content = note.content,
                sourcePath = note.sourceType,
                metadata = mapOf(
                    "sessionId" to note.sessionId,
                    "type" to note.sourceType,
                    "timestamp" to note.timestamp.toString()
                )
            )

            // Chunk the content (simple chunking by paragraphs)
            val chunks = chunkContent(note.content, document.id)

            // Embed each chunk using local inference
            val embeddedChunks = chunks.mapIndexed { index, chunk ->
                val embedding = inferenceEngine.embed(chunk)
                DocumentChunk(
                    id = UUID.randomUUID().toString(),
                    documentId = document.id,
                    content = chunk,
                    chunkIndex = index,
                    tokenCount = estimateTokenCount(chunk),
                    embedding = embedding
                )
            }

            // Store in vector database
            vectorStore.addDocument(document, embeddedChunks)

            // Link to session
            vectorStore.addDocumentToSession(note.sessionId, document.id)

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get all training sessions.
     */
    suspend fun getSessions(): Result<List<TrainingSession>> {
        return try {
            val sessions = vectorStore.getSessions()
            val trainingSessions = sessions.map { session ->
                // Parse session name back to fighter name and date
                val parts = session.name.split(" - ")
                TrainingSession(
                    id = session.id,
                    fighterName = parts.getOrNull(0) ?: "Unknown",
                    date = parts.getOrNull(1) ?: "",
                    notes = session.description ?: "",
                    createdAt = session.createdAt
                )
            }
            Result.success(trainingSessions)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get session notes for a specific session.
     */
    suspend fun getSessionNotes(sessionId: String): Result<List<SessionNote>> {
        return try {
            val documents = vectorStore.getSessionDocuments(sessionId)
            val notes = documents.map { doc ->
                SessionNote(
                    id = doc.id,
                    sessionId = doc.metadata["sessionId"] ?: sessionId,
                    content = doc.content,
                    sourceType = doc.metadata["type"] ?: "unknown",
                    timestamp = doc.metadata["timestamp"]?.toLongOrNull() ?: doc.createdAt
                )
            }
            Result.success(notes)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Search for relevant session context using RAG.
     */
    suspend fun searchRelevantContext(
        query: String,
        sessionId: String,
        limit: Int = 5
    ): Result<List<String>> {
        return try {
            // Embed the query
            val queryEmbedding = inferenceEngine.embed(query)

            // Search for similar chunks
            val results = vectorStore.searchSimilar(queryEmbedding, limit)

            // Filter to current session and extract content
            val context = results
                .filter { it.document.metadata["sessionId"] == sessionId }
                .map { it.chunk.content }

            Result.success(context)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Helper functions

    private fun chunkContent(content: String, documentId: String): List<String> {
        // Simple chunking strategy: split by double newlines (paragraphs)
        // In production, use more sophisticated chunking (sentence windows, etc.)
        return content.split("\n\n")
            .filter { it.isNotBlank() }
            .map { it.trim() }
    }

    private fun estimateTokenCount(text: String): Int {
        // Rough estimation: ~4 characters per token
        return (text.length / 4).coerceAtLeast(1)
    }
}
