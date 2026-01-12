package com.privateai.vault.vectorstore

import com.privateai.vault.inference.InferenceEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File
import java.util.UUID

/**
 * RAG-enabled document ingestor.
 *
 * Epic 3 Implementation:
 * - Story 3.1: Document ingestion (PDF & text)
 * - Story 3.4: Multi-file folder ingestion
 * - Story 3.5: Real-time upload & chat
 * - Story 3.6: Dynamic context window
 *
 * @param vectorStore Encrypted vector database
 * @param inferenceEngine Embedding model
 * @param pdfExtractor PDF text extraction service
 */
class RagDocumentIngestor(
    private val vectorStore: VectorStore,
    private val inferenceEngine: InferenceEngine,
    private val pdfExtractor: PdfExtractor?
) : DocumentIngestor {

    override suspend fun ingestText(
        content: String,
        metadata: DocumentMetadata,
        config: ChunkingConfig
    ): Flow<IngestionProgress> = flow {
        val startTime = System.currentTimeMillis()

        try {
            emit(IngestionProgress.Reading(metadata.fileName))

            // Step 1: Chunk the text
            emit(IngestionProgress.Chunking(metadata.fileName, content.length.toLong()))
            val textChunks = TextChunker.chunkText(content, config)

            if (textChunks.isEmpty()) {
                emit(IngestionProgress.Error(
                    metadata.fileName,
                    "No valid chunks generated from document"
                ))
                return@flow
            }

            // Step 2: Generate embeddings for each chunk
            val documentChunks = mutableListOf<DocumentChunk>()

            textChunks.forEachIndexed { index, chunkText ->
                emit(IngestionProgress.Embedding(
                    metadata.fileName,
                    index + 1,
                    textChunks.size
                ))

                try {
                    val embedding = inferenceEngine.embed(chunkText)

                    val chunk = DocumentChunk(
                        id = UUID.randomUUID().toString(),
                        documentId = metadata.fileName, // Will be updated with actual doc ID
                        content = chunkText,
                        chunkIndex = index,
                        tokenCount = TextChunker.estimateTokenCount(chunkText),
                        embedding = embedding,
                        createdAt = System.currentTimeMillis()
                    )

                    documentChunks.add(chunk)
                } catch (e: Exception) {
                    println("[Ingestor] ⚠️  Failed to embed chunk $index: ${e.message}")
                    // Continue with other chunks
                }
            }

            if (documentChunks.isEmpty()) {
                emit(IngestionProgress.Error(
                    metadata.fileName,
                    "Failed to generate embeddings for any chunks"
                ))
                return@flow
            }

            // Step 3: Store in vector database
            emit(IngestionProgress.Storing(metadata.fileName))

            val documentId = UUID.randomUUID().toString()

            // Update chunk document IDs
            val updatedChunks = documentChunks.map { it.copy(documentId = documentId) }

            val document = Document(
                id = documentId,
                content = content,
                sourcePath = metadata.fileName,
                metadata = buildMetadataMap(metadata),
                chunkCount = updatedChunks.size,
                createdAt = metadata.uploadedAt,
                updatedAt = System.currentTimeMillis()
            )

            vectorStore.addDocument(document, updatedChunks)

            val duration = System.currentTimeMillis() - startTime
            emit(IngestionProgress.Complete(
                documentId,
                updatedChunks.size,
                duration
            ))

            println("[Ingestor] ✅ Ingested '${metadata.fileName}': ${updatedChunks.size} chunks in ${duration}ms")

        } catch (e: Exception) {
            emit(IngestionProgress.Error(
                metadata.fileName,
                "Ingestion failed: ${e.message}"
            ))
            println("[Ingestor] ❌ Error ingesting '${metadata.fileName}': ${e.message}")
            e.printStackTrace()
        }
    }

    override suspend fun ingestPdf(
        pdfBytes: ByteArray,
        metadata: DocumentMetadata,
        config: ChunkingConfig
    ): Flow<IngestionProgress> = flow {
        if (pdfExtractor == null) {
            emit(IngestionProgress.Error(
                metadata.fileName,
                "PDF extraction not available - no PdfExtractor configured"
            ))
            return@flow
        }

        try {
            emit(IngestionProgress.Reading(metadata.fileName))

            // Extract text from PDF
            val extractionResult = pdfExtractor.extractText(pdfBytes)

            // Update metadata with PDF-specific info
            val enrichedMetadata = metadata.copy(
                pageCount = extractionResult.pageCount,
                customFields = metadata.customFields + extractionResult.metadata
            )

            // Use text ingestion for the extracted content
            ingestText(extractionResult.text, enrichedMetadata, config).collect { progress ->
                emit(progress)
            }

        } catch (e: Exception) {
            emit(IngestionProgress.Error(
                metadata.fileName,
                "PDF extraction failed: ${e.message}"
            ))
        }
    }

    override suspend fun ingestFolder(
        files: List<String>,
        folderName: String,
        config: ChunkingConfig
    ): Flow<IngestionProgress> = flow {
        println("[Ingestor] 📁 Ingesting folder '$folderName' with ${files.size} files")

        var successCount = 0
        var failCount = 0

        for (filePath in files) {
            try {
                val file = File(filePath)

                // Check if file exists
                if (!file.exists()) {
                    emit(IngestionProgress.Error(
                        filePath,
                        "File not found: $filePath"
                    ))
                    failCount++
                    continue
                }

                // Check if it's a file (not directory)
                if (!file.isFile) {
                    emit(IngestionProgress.Error(
                        filePath,
                        "Path is not a file: $filePath"
                    ))
                    failCount++
                    continue
                }

                // Determine file type and ingest
                val fileName = file.name
                val fileType = fileName.substringAfterLast(".", "txt")

                val metadata = DocumentMetadata(
                    fileName = fileName,
                    fileType = fileType,
                    fileSize = file.length(),
                    uploadedAt = System.currentTimeMillis(),
                    tags = listOf("folder:$folderName")
                )

                // Story 3.4: Treat folder as single logical source
                when (fileType.lowercase()) {
                    "txt", "md" -> {
                        try {
                            // Read text file content
                            val content = file.readText(Charsets.UTF_8)

                            // Use text ingestion for the file content
                            ingestText(content, metadata, config).collect { progress ->
                                emit(progress)

                                // Track success/failure
                                when (progress) {
                                    is IngestionProgress.Complete -> successCount++
                                    is IngestionProgress.Error -> failCount++
                                    else -> { /* Continue */ }
                                }
                            }
                        } catch (e: Exception) {
                            emit(IngestionProgress.Error(
                                fileName,
                                "Failed to read text file: ${e.message}"
                            ))
                            failCount++
                        }
                    }
                    "pdf" -> {
                        // PDF ingestion - delegate to ingestPdf if pdfExtractor is available
                        if (pdfExtractor == null) {
                            emit(IngestionProgress.Error(
                                fileName,
                                "PDF extraction not available - no PdfExtractor configured"
                            ))
                            failCount++
                        } else {
                            try {
                                val pdfBytes = file.readBytes()
                                ingestPdf(pdfBytes, metadata, config).collect { progress ->
                                    emit(progress)

                                    when (progress) {
                                        is IngestionProgress.Complete -> successCount++
                                        is IngestionProgress.Error -> failCount++
                                        else -> { /* Continue */ }
                                    }
                                }
                            } catch (e: Exception) {
                                emit(IngestionProgress.Error(
                                    fileName,
                                    "Failed to read PDF file: ${e.message}"
                                ))
                                failCount++
                            }
                        }
                    }
                    else -> {
                        emit(IngestionProgress.Error(
                            fileName,
                            "Unsupported file type: $fileType (supported: txt, md, pdf)"
                        ))
                        failCount++
                    }
                }

            } catch (e: Exception) {
                emit(IngestionProgress.Error(filePath, e.message ?: "Unknown error"))
                failCount++
            }
        }

        println("[Ingestor] 📁 Folder ingestion complete: $successCount success, $failCount failed")
    }

    override suspend fun getStats(): IngestionStats {
        // Would query vector store for actual stats
        return IngestionStats(
            totalDocuments = 0,
            totalChunks = 0,
            totalSizeBytes = 0,
            averageChunkSize = 0,
            lastIngestionTime = 0
        )
    }

    private fun buildMetadataMap(metadata: DocumentMetadata): Map<String, String> {
        return buildMap {
            put("file_name", metadata.fileName)
            put("file_type", metadata.fileType)
            put("file_size", metadata.fileSize.toString())
            put("uploaded_at", metadata.uploadedAt.toString())
            metadata.author?.let { put("author", it) }
            metadata.title?.let { put("title", it) }
            metadata.pageCount?.let { put("page_count", it.toString()) }
            if (metadata.tags.isNotEmpty()) {
                put("tags", metadata.tags.joinToString(","))
            }
            putAll(metadata.customFields)
        }
    }
}
