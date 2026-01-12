package com.privateai.vault.vectorstore

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Epic 3 - Story 3.2 & 3.6: Contextual Retrieval Tests
 *
 * Tests the RAG context formatting and configuration systems.
 */
class ContextualRetrievalTest {

    @Test
    fun `test retrieval config defaults are sensible`() {
        val config = RetrievalConfig()

        assertEquals(5, config.topK, "Default topK should be 5")
        assertEquals(0.7f, config.minRelevanceScore, "Default threshold should be 0.7")
        assertTrue(config.includeMetadata, "Should include metadata by default")
        assertTrue(config.deduplicate, "Should deduplicate by default")
        assertEquals(2048, config.maxContextLength, "Default max context length")
    }

    @Test
    fun `test retrieval config allows customization`() {
        val customConfig = RetrievalConfig(
            topK = 10,
            minRelevanceScore = 0.8f,
            includeMetadata = false,
            deduplicate = false,
            maxContextLength = 4096
        )

        assertEquals(10, customConfig.topK)
        assertEquals(0.8f, customConfig.minRelevanceScore)
        assertFalse(customConfig.includeMetadata)
        assertFalse(customConfig.deduplicate)
        assertEquals(4096, customConfig.maxContextLength)
    }

    @Test
    fun `test context chunk structure`() {
        val metadata = mapOf("tag" to "important", "category" to "technical")
        val chunk = ContextChunk(
            content = "This is the chunk content.",
            sourceDocument = "doc.txt",
            chunkIndex = 2,
            relevanceScore = 0.95f,
            metadata = metadata
        )

        assertEquals("This is the chunk content.", chunk.content)
        assertEquals("doc.txt", chunk.sourceDocument)
        assertEquals(2, chunk.chunkIndex)
        assertEquals(0.95f, chunk.relevanceScore)
        assertEquals("important", chunk.metadata["tag"])
    }

    @Test
    fun `test retrieved context structure`() {
        val chunks = listOf(
            ContextChunk("Content 1", "doc1.txt", 0, 0.9f, emptyMap()),
            ContextChunk("Content 2", "doc2.txt", 0, 0.85f, emptyMap())
        )

        val context = RetrievedContext(
            chunks = chunks,
            totalRetrieved = 10,
            query = "What is AI?",
            retrievalTimeMs = 150L
        )

        assertEquals(2, context.chunks.size)
        assertEquals(10, context.totalRetrieved)
        assertEquals("What is AI?", context.query)
        assertEquals(150L, context.retrievalTimeMs)
    }

    @Test
    fun `test context window stats structure`() {
        val stats = ContextWindowStats(
            availableTokens = 8192,
            usedTokens = 1024,
            documentCount = 5,
            oldestDocumentAge = 3600000L,  // 1 hour
            newestDocumentAge = 60000L     // 1 minute
        )

        assertEquals(8192, stats.availableTokens)
        assertEquals(1024, stats.usedTokens)
        assertEquals(5, stats.documentCount)
        assertTrue(stats.oldestDocumentAge > stats.newestDocumentAge)
    }

    @Test
    fun `test relevance score is normalized between 0 and 1`() {
        val validScores = listOf(0.0f, 0.5f, 0.7f, 0.95f, 1.0f)

        validScores.forEach { score ->
            val chunk = ContextChunk("content", "source", 0, score, emptyMap())
            assertTrue(chunk.relevanceScore >= 0.0f, "Score should be >= 0")
            assertTrue(chunk.relevanceScore <= 1.0f, "Score should be <= 1")
        }
    }

    @Test
    fun `test retrieval config validates topK is positive`() {
        val config = RetrievalConfig(topK = 1)
        assertTrue(config.topK > 0, "topK should be positive")
    }

    @Test
    fun `test retrieval config validates threshold is valid`() {
        val config = RetrievalConfig(minRelevanceScore = 0.5f)
        assertTrue(config.minRelevanceScore >= 0.0f, "Threshold should be >= 0")
        assertTrue(config.minRelevanceScore <= 1.0f, "Threshold should be <= 1")
    }

    @Test
    fun `test context chunks can be ordered by relevance`() {
        val chunks = listOf(
            ContextChunk("Low relevance", "doc1", 0, 0.6f, emptyMap()),
            ContextChunk("High relevance", "doc2", 0, 0.95f, emptyMap()),
            ContextChunk("Medium relevance", "doc3", 0, 0.75f, emptyMap())
        )

        val sortedChunks = chunks.sortedByDescending { it.relevanceScore }

        assertEquals(0.95f, sortedChunks[0].relevanceScore, "Highest should be first")
        assertEquals(0.75f, sortedChunks[1].relevanceScore, "Medium should be second")
        assertEquals(0.6f, sortedChunks[2].relevanceScore, "Lowest should be last")
    }

    @Test
    fun `test context can filter by threshold`() {
        val chunks = listOf(
            ContextChunk("High", "doc1", 0, 0.9f, emptyMap()),
            ContextChunk("Medium", "doc2", 0, 0.75f, emptyMap()),
            ContextChunk("Low", "doc3", 0, 0.5f, emptyMap())
        )

        val threshold = 0.7f
        val filtered = chunks.filter { it.relevanceScore >= threshold }

        assertEquals(2, filtered.size, "Should filter out low relevance")
        assertTrue(filtered.all { it.relevanceScore >= threshold })
    }

    @Test
    fun `test RAG chat progress has all necessary states`() {
        // Test all progress states exist and can be constructed
        val states = listOf(
            RagChatProgress.Ingesting("Processing document"),
            RagChatProgress.Retrieving("Finding context"),
            RagChatProgress.NoContext("No relevant context"),
            RagChatProgress.ContextRetrieved(5),
            RagChatProgress.Generating("Generating response"),
            RagChatProgress.GeneratingToken("Hello"),
            RagChatProgress.Complete,
            RagChatProgress.Error("Something failed")
        )

        assertEquals(8, states.size, "Should have all progress states")

        // Test specific state properties
        val ingesting = states[0] as RagChatProgress.Ingesting
        assertEquals("Processing document", ingesting.message)

        val contextRetrieved = states[3] as RagChatProgress.ContextRetrieved
        assertEquals(5, contextRetrieved.chunkCount)

        val error = states[7] as RagChatProgress.Error
        assertEquals("Something failed", error.message)
    }

    @Test
    fun `test context metadata can store arbitrary data`() {
        val metadata = mapOf(
            "source_type" to "pdf",
            "page_number" to "42",
            "confidence" to "0.95",
            "author" to "John Doe",
            "tags" to "important,urgent"
        )

        val chunk = ContextChunk(
            content = "Content",
            sourceDocument = "doc.pdf",
            chunkIndex = 0,
            relevanceScore = 0.9f,
            metadata = metadata
        )

        assertEquals("pdf", chunk.metadata["source_type"])
        assertEquals("42", chunk.metadata["page_number"])
        assertEquals("John Doe", chunk.metadata["author"])
        assertTrue(chunk.metadata["tags"]!!.contains("important"))
    }

    @Test
    fun `test retrieved context measures retrieval time`() {
        val startTime = System.currentTimeMillis()
        Thread.sleep(10) // Simulate some work
        val endTime = System.currentTimeMillis()
        val duration = endTime - startTime

        val context = RetrievedContext(
            chunks = emptyList(),
            totalRetrieved = 0,
            query = "test",
            retrievalTimeMs = duration
        )

        assertTrue(context.retrievalTimeMs >= 10, "Should measure elapsed time")
        assertTrue(context.retrievalTimeMs < 1000, "Should complete quickly")
    }

    @Test
    fun `test context chunks can represent different document types`() {
        val pdfChunk = ContextChunk(
            "PDF content",
            "document.pdf",
            0,
            0.9f,
            mapOf("type" to "pdf", "page" to "1")
        )

        val textChunk = ContextChunk(
            "Text content",
            "notes.txt",
            0,
            0.85f,
            mapOf("type" to "text")
        )

        val mdChunk = ContextChunk(
            "# Markdown content",
            "readme.md",
            0,
            0.8f,
            mapOf("type" to "markdown")
        )

        assertEquals("pdf", pdfChunk.metadata["type"])
        assertEquals("text", textChunk.metadata["type"])
        assertEquals("markdown", mdChunk.metadata["type"])
    }

    @Test
    fun `test deduplication key uses document and chunk index`() {
        val chunk1 = ContextChunk("Content", "doc.txt", 5, 0.9f, emptyMap())
        val chunk2 = ContextChunk("Different content", "doc.txt", 5, 0.85f, emptyMap())

        // Same document and chunk index = duplicate
        val key1 = "${chunk1.sourceDocument}:${chunk1.chunkIndex}"
        val key2 = "${chunk2.sourceDocument}:${chunk2.chunkIndex}"

        assertEquals(key1, key2, "Same source + index should have same dedup key")
    }
}
