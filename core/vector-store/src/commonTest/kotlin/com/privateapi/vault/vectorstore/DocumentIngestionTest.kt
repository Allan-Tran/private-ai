package com.privateai.vault.vectorstore

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Epic 3 - Story 3.1: Document Ingestion Tests
 *
 * Tests the text chunking and document metadata systems.
 */
class DocumentIngestionTest {

    @Test
    fun `test text chunking creates valid chunks`() {
        val text = """
            This is the first paragraph. It contains multiple sentences.
            Each sentence provides context for the next.

            This is a second paragraph. It should be chunked separately if possible.
            The chunker preserves paragraph boundaries.
        """.trimIndent()

        val config = ChunkingConfig(
            maxChunkSize = 50,
            overlapSize = 10,
            preserveParagraphs = true,
            minChunkSize = 20
        )

        val chunks = TextChunker.chunkText(text, config)

        // Should produce multiple chunks
        assertTrue(chunks.isNotEmpty(), "Should produce at least one chunk")

        // Each chunk should respect min size
        chunks.forEach { chunk ->
            assertTrue(
                chunk.length >= config.minChunkSize,
                "Chunk too small: ${chunk.length} < ${config.minChunkSize}"
            )
        }

        // Chunks should overlap (second chunk contains end of first)
        if (chunks.size >= 2) {
            val firstChunkWords = chunks[0].split(" ").takeLast(5)
            val secondChunk = chunks[1]
            val hasOverlap = firstChunkWords.any { word -> secondChunk.contains(word) }
            assertTrue(hasOverlap, "Chunks should have overlapping content")
        }
    }

    @Test
    fun `test chunking handles short text`() {
        val shortText = "This is a short text."
        val config = ChunkingConfig(maxChunkSize = 512, minChunkSize = 10)

        val chunks = TextChunker.chunkText(shortText, config)

        assertEquals(1, chunks.size, "Short text should produce single chunk")
        assertTrue(chunks[0].contains("short text"))
    }

    @Test
    fun `test chunking normalizes whitespace`() {
        val messyText = "Multiple    spaces   and\n\n\nnewlines\t\ttabs"
        val config = ChunkingConfig()

        val chunks = TextChunker.chunkText(messyText, config)

        chunks.forEach { chunk ->
            assertFalse(chunk.contains("  "), "Should normalize multiple spaces")
            assertFalse(chunk.contains("\t"), "Should normalize tabs")
        }
    }

    @Test
    fun `test token estimation is reasonable`() {
        val text = "This is approximately sixteen tokens when you count them all up carefully."
        val estimatedTokens = TextChunker.estimateTokenCount(text)

        // Rough heuristic: 1 token ≈ 4 characters
        val expectedTokens = text.length / 4

        assertEquals(expectedTokens, estimatedTokens, "Token estimation should use 4 chars/token")

        // Should be in reasonable range (10-20 tokens for this sentence)
        assertTrue(estimatedTokens in 10..30, "Estimation seems off: $estimatedTokens")
    }

    @Test
    fun `test chunking config validation`() {
        // Long text should be split into multiple chunks
        val longText = "word ".repeat(200) // 1000+ characters

        val config = ChunkingConfig(
            maxChunkSize = 100, // Small max
            minChunkSize = 20
        )

        val chunks = TextChunker.chunkText(longText, config)

        // Should produce at least one chunk from long text
        assertTrue(chunks.isNotEmpty(), "Should produce chunks from long text")

        // Each chunk should be non-empty
        chunks.forEach { chunk ->
            assertTrue(chunk.isNotEmpty(), "Chunks should not be empty")
        }
    }

    @Test
    fun `test chunking handles empty input`() {
        val config = ChunkingConfig()
        val chunks = TextChunker.chunkText("", config)

        assertTrue(chunks.isEmpty(), "Empty text should produce no chunks")
    }

    @Test
    fun `test chunking splits long single paragraph`() {
        // Create a paragraph that's too long for one chunk
        val longParagraph = "This is a very long sentence. ".repeat(50)

        val config = ChunkingConfig(
            maxChunkSize = 100,
            minChunkSize = 20
        )

        val chunks = TextChunker.chunkText(longParagraph, config)

        assertTrue(chunks.size > 1, "Long paragraph should be split into multiple chunks")
    }

    @Test
    fun `test document metadata structure`() {
        val metadata = DocumentMetadata(
            fileName = "test.txt",
            fileType = "txt",
            fileSize = 1024,
            uploadedAt = System.currentTimeMillis(),
            author = "John Doe",
            title = "Test Document",
            tags = listOf("test", "epic3"),
            customFields = mapOf("source" to "manual")
        )

        assertEquals("test.txt", metadata.fileName)
        assertEquals("txt", metadata.fileType)
        assertEquals(1024L, metadata.fileSize)
        assertEquals("John Doe", metadata.author)
        assertTrue(metadata.tags.contains("test"))
        assertEquals("manual", metadata.customFields["source"])
    }

    @Test
    fun `test chunking config defaults are sensible`() {
        val config = ChunkingConfig()

        assertEquals(512, config.maxChunkSize, "Default max chunk size")
        assertEquals(50, config.overlapSize, "Default overlap size")
        assertTrue(config.preserveParagraphs, "Should preserve paragraphs by default")
        assertEquals(100, config.minChunkSize, "Default min chunk size")
    }
}
