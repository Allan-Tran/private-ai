package com.privateai.vault.vectorstore

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Epic 3 (The Knowledge Base) - Document Ingestion System
 *
 * Handles importing documents into the RAG system with:
 * - Text chunking for optimal retrieval
 * - Embedding generation
 * - Metadata extraction
 * - Progress tracking
 */

/**
 * Progress update for document ingestion.
 */
sealed class IngestionProgress {
    data class Reading(val fileName: String) : IngestionProgress()
    data class Chunking(val fileName: String, val totalBytes: Long) : IngestionProgress()
    data class Embedding(val fileName: String, val chunkIndex: Int, val totalChunks: Int) : IngestionProgress()
    data class Storing(val fileName: String) : IngestionProgress()
    data class Complete(val documentId: String, val chunkCount: Int, val durationMs: Long) : IngestionProgress()
    data class Error(val fileName: String, val error: String) : IngestionProgress()
}

/**
 * Configuration for document chunking.
 */
data class ChunkingConfig(
    val maxChunkSize: Int = 512,           // Maximum tokens per chunk
    val overlapSize: Int = 50,             // Overlap between chunks for context
    val preserveParagraphs: Boolean = true, // Try to keep paragraphs together
    val minChunkSize: Int = 100            // Minimum viable chunk size
)

/**
 * Metadata extracted from documents.
 */
data class DocumentMetadata(
    val fileName: String,
    val fileType: String,
    val fileSize: Long,
    val uploadedAt: Long,
    val author: String? = null,
    val title: String? = null,
    val pageCount: Int? = null,
    val tags: List<String> = emptyList(),
    val customFields: Map<String, String> = emptyMap()
)

/**
 * Document ingestion service interface.
 *
 * Story 3.1 - Document Ingestion
 * Story 3.5 - Real-Time Data Ingestion
 */
interface DocumentIngestor {
    /**
     * Ingest a single text document.
     *
     * @param content Raw text content
     * @param metadata Document metadata
     * @param config Chunking configuration
     * @return Flow of progress updates
     */
    suspend fun ingestText(
        content: String,
        metadata: DocumentMetadata,
        config: ChunkingConfig = ChunkingConfig()
    ): Flow<IngestionProgress>

    /**
     * Ingest a PDF document.
     *
     * @param pdfBytes PDF file bytes
     * @param metadata Document metadata
     * @param config Chunking configuration
     * @return Flow of progress updates
     */
    suspend fun ingestPdf(
        pdfBytes: ByteArray,
        metadata: DocumentMetadata,
        config: ChunkingConfig = ChunkingConfig()
    ): Flow<IngestionProgress>

    /**
     * Ingest multiple files from a folder (Story 3.4).
     *
     * @param files List of file paths
     * @param folderName Logical folder name
     * @param config Chunking configuration
     * @return Flow of progress updates for all files
     */
    suspend fun ingestFolder(
        files: List<String>,
        folderName: String,
        config: ChunkingConfig = ChunkingConfig()
    ): Flow<IngestionProgress>

    /**
     * Get ingestion statistics.
     */
    suspend fun getStats(): IngestionStats
}

/**
 * Statistics about ingested documents.
 */
data class IngestionStats(
    val totalDocuments: Int,
    val totalChunks: Int,
    val totalSizeBytes: Long,
    val averageChunkSize: Int,
    val lastIngestionTime: Long
)

/**
 * Text chunking utilities.
 *
 * Splits documents into optimal chunks for retrieval.
 */
object TextChunker {
    /**
     * Split text into chunks with overlap.
     *
     * Algorithm:
     * 1. Split on paragraph boundaries first
     * 2. If paragraph too large, split on sentence boundaries
     * 3. If sentence too large, split on word boundaries
     * 4. Add overlap from previous chunk for context continuity
     */
    fun chunkText(
        text: String,
        config: ChunkingConfig
    ): List<String> {
        val chunks = mutableListOf<String>()

        // Normalize whitespace
        val normalizedText = text.replace(Regex("\\s+"), " ").trim()

        // Split into paragraphs
        val paragraphs = normalizedText.split(Regex("\\.\\s+|\\n\\n+"))

        var currentChunk = StringBuilder()
        var previousChunk = ""

        for (paragraph in paragraphs) {
            val words = paragraph.split(" ")
            val estimatedTokens = words.size // Rough estimate: 1 word ≈ 1 token

            if (currentChunk.isNotEmpty() && estimatedTokens + currentChunk.length / 4 > config.maxChunkSize) {
                // Current chunk is full, save it
                val chunkText = currentChunk.toString().trim()
                if (chunkText.length >= config.minChunkSize) {
                    chunks.add(chunkText)
                    previousChunk = chunkText
                }
                currentChunk.clear()

                // Add overlap from previous chunk
                if (previousChunk.isNotEmpty()) {
                    val overlapWords = previousChunk.split(" ").takeLast(config.overlapSize)
                    currentChunk.append(overlapWords.joinToString(" ")).append(" ")
                }
            }

            currentChunk.append(paragraph).append(". ")
        }

        // Add final chunk
        val finalChunk = currentChunk.toString().trim()
        if (finalChunk.length >= config.minChunkSize) {
            chunks.add(finalChunk)
        }

        return chunks
    }

    /**
     * Estimate token count for text.
     * Rough approximation: 1 token ≈ 4 characters or 0.75 words
     */
    fun estimateTokenCount(text: String): Int {
        return text.length / 4
    }
}

/**
 * PDF text extraction interface.
 */
interface PdfExtractor {
    /**
     * Extract text from PDF bytes.
     *
     * @param pdfBytes PDF file content
     * @return Extracted text and metadata
     */
    suspend fun extractText(pdfBytes: ByteArray): PdfExtractionResult
}

/**
 * Result of PDF text extraction.
 */
data class PdfExtractionResult(
    val text: String,
    val pageCount: Int,
    val metadata: Map<String, String> = emptyMap()
)
