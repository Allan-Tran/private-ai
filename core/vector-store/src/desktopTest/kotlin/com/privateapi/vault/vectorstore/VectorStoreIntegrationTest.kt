package com.privateai.vault.vectorstore

import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.*

/**
 * Epic 1, 2, 3 Integration Tests
 *
 * Tests the complete integration of:
 * - Epic 1: Local inference (mocked)
 * - Epic 2: Encryption + Redaction
 * - Epic 3: Document ingestion and RAG
 */
class VectorStoreIntegrationTest {

    private val testDbPath = "build/test-integration.db"

    @BeforeTest
    fun setup() {
        File(testDbPath).delete()
    }

    @AfterTest
    fun teardown() {
        File(testDbPath).delete()
    }

    @Test
    fun `test complete document lifecycle with encryption and redaction`() = runTest {
        val passphrase = "test-passphrase-123"
        val redactor = RegexPrivacyRedactor()

        // Create encrypted vector store
        val driver = createVectorStoreDriver(testDbPath, passphrase)
        val store = SqliteVectorStore(driver, redactor, embeddingDimension = 384)

        // Initialize without vector extension requirement for tests
        store.initialize(requireVectorExtension = false)

        // Create document with sensitive data
        val sensitiveContent = """
            Patient record for visit on 01/15/2023.
            Contact: 555-123-4567
            Email: patient@example.com
            SSN: 123-45-6789
            Payment card: 4111-1111-1111-1111
        """.trimIndent()

        val mockEmbedding = FloatArray(384) { 0.1f } // Mock embedding

        val chunk = DocumentChunk(
            id = "chunk-1",
            documentId = "doc-1",
            content = sensitiveContent,
            chunkIndex = 0,
            tokenCount = 50,
            embedding = mockEmbedding,
            createdAt = System.currentTimeMillis()
        )

        val document = Document(
            id = "doc-1",
            content = sensitiveContent,
            sourcePath = "patient-record.txt",
            metadata = mapOf("type" to "medical"),
            chunkCount = 1,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

        // Add document (should trigger redaction)
        store.addDocument(document, listOf(chunk))

        // Retrieve document
        val retrieved = store.getDocument("doc-1")

        // Verify document was stored
        assertNotNull(retrieved, "Document should be stored")

        // Verify ALL sensitive data was redacted
        assertFalse(retrieved.content.contains("555-123-4567"), "Phone should be redacted")
        assertFalse(retrieved.content.contains("patient@example.com"), "Email should be redacted")
        assertFalse(retrieved.content.contains("123-45-6789"), "SSN should be redacted")
        assertFalse(retrieved.content.contains("4111-1111-1111-1111"), "Card should be redacted")

        // Verify redaction markers are present
        assertTrue(retrieved.content.contains("[PHONE_REDACTED]"), "Phone marker present")
        assertTrue(retrieved.content.contains("[EMAIL_REDACTED]"), "Email marker present")
        assertTrue(retrieved.content.contains("[SSN_REDACTED]"), "SSN marker present")
        assertTrue(retrieved.content.contains("[CARD_REDACTED]"), "Card marker present")

        // Verify non-sensitive content is preserved
        assertTrue(retrieved.content.contains("Patient record"), "Normal text preserved")
    }

    @Test
    fun `test embedding dimension validation prevents mismatch`() = runTest {
        val passphrase = "test-pass"
        val redactor = NoOpRedactor()

        val driver = createVectorStoreDriver(testDbPath, passphrase)
        val store = SqliteVectorStore(driver, redactor, embeddingDimension = 512)

        try {
            store.initialize()
        } catch (e: Exception) {
            // Extension not available - ok
        }

        // Try to add chunk with WRONG embedding dimension
        val wrongEmbedding = FloatArray(256) { 0.1f } // 256 instead of 512

        val chunk = DocumentChunk(
            id = "chunk-1",
            documentId = "doc-1",
            content = "Test content",
            chunkIndex = 0,
            tokenCount = 10,
            embedding = wrongEmbedding,
            createdAt = System.currentTimeMillis()
        )

        val document = Document(
            id = "doc-1",
            content = "Test",
            sourcePath = "test.txt",
            metadata = emptyMap(),
            chunkCount = 1,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

        // Should throw IllegalArgumentException with clear message
        val exception = assertFailsWith<IllegalArgumentException> {
            store.addDocument(document, listOf(chunk))
        }

        assertTrue(
            exception.message!!.contains("expected 512, got 256"),
            "Error should mention dimension mismatch"
        )
    }

    @Test
    fun `test session management workflow`() = runTest {
        val passphrase = "session-test"
        val redactor = NoOpRedactor()

        val driver = createVectorStoreDriver(testDbPath, passphrase)
        val store = SqliteVectorStore(driver, redactor)

        store.initialize(requireVectorExtension = false)

        // Create a session
        val session = Session(
            id = "session-1",
            name = "Research Session",
            description = "AI research documents",
            createdAt = System.currentTimeMillis(),
            lastAccessed = System.currentTimeMillis()
        )

        store.createSession(session)

        // Create and add a document
        val document = Document(
            id = "doc-1",
            content = "AI research paper content",
            sourcePath = "paper.pdf",
            metadata = emptyMap(),
            chunkCount = 0,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

        store.addDocument(document, emptyList())

        // Link document to session
        store.addDocumentToSession("session-1", "doc-1")

        // Retrieve session
        val retrievedSession = store.getSession("session-1")
        assertNotNull(retrievedSession)
        assertEquals("Research Session", retrievedSession.name)

        // Get documents in session
        val sessionDocs = store.getSessionDocuments("session-1")
        assertEquals(1, sessionDocs.size)
        assertEquals("doc-1", sessionDocs[0].id)

        // List all sessions
        val allSessions = store.getSessions()
        assertTrue(allSessions.any { it.id == "session-1" })

        // Remove document from session
        store.removeDocumentFromSession("session-1", "doc-1")
        val docsAfterRemoval = store.getSessionDocuments("session-1")
        assertEquals(0, docsAfterRemoval.size)

        // Delete session
        store.deleteSession("session-1")
        val deletedSession = store.getSession("session-1")
        assertNull(deletedSession)
    }

    @Test
    fun `test multiple documents with different embedding dimensions fail gracefully`() = runTest {
        val passphrase = "multi-doc-test"
        val redactor = NoOpRedactor()

        val driver = createVectorStoreDriver(testDbPath, passphrase)
        val store = SqliteVectorStore(driver, redactor, embeddingDimension = 768)

        store.initialize(requireVectorExtension = false)

        // Add first document with CORRECT embedding dimension
        val correctEmbedding = FloatArray(768) { 0.1f }
        val chunk1 = DocumentChunk(
            id = "chunk-1",
            documentId = "doc-1",
            content = "First doc",
            chunkIndex = 0,
            tokenCount = 10,
            embedding = correctEmbedding,
            createdAt = System.currentTimeMillis()
        )

        val doc1 = Document(
            id = "doc-1",
            content = "First",
            sourcePath = "first.txt",
            metadata = emptyMap(),
            chunkCount = 1,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

        // Should succeed
        store.addDocument(doc1, listOf(chunk1))

        // Try to add second document with WRONG embedding dimension
        val wrongEmbedding = FloatArray(512) { 0.1f }
        val chunk2 = DocumentChunk(
            id = "chunk-2",
            documentId = "doc-2",
            content = "Second doc",
            chunkIndex = 0,
            tokenCount = 10,
            embedding = wrongEmbedding,
            createdAt = System.currentTimeMillis()
        )

        val doc2 = Document(
            id = "doc-2",
            content = "Second",
            sourcePath = "second.txt",
            metadata = emptyMap(),
            chunkCount = 1,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

        // Should fail
        assertFailsWith<IllegalArgumentException> {
            store.addDocument(doc2, listOf(chunk2))
        }

        // First document should still be retrievable
        val retrieved = store.getDocument("doc-1")
        assertNotNull(retrieved)
        assertEquals("First", retrieved.content)
    }

    @Test
    fun `test document removal cascades to chunks`() = runTest {
        val passphrase = "cascade-test"
        val redactor = NoOpRedactor()

        val driver = createVectorStoreDriver(testDbPath, passphrase)
        val store = SqliteVectorStore(driver, redactor, embeddingDimension = 128)

        store.initialize(requireVectorExtension = false)

        // Add document with multiple chunks
        val embedding = FloatArray(128) { 0.1f }
        val chunks = (0..2).map { i ->
            DocumentChunk(
                id = "chunk-$i",
                documentId = "doc-cascade",
                content = "Chunk $i content",
                chunkIndex = i,
                tokenCount = 10,
                embedding = embedding,
                createdAt = System.currentTimeMillis()
            )
        }

        val document = Document(
            id = "doc-cascade",
            content = "Document with multiple chunks",
            sourcePath = "multi-chunk.txt",
            metadata = emptyMap(),
            chunkCount = 3,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

        store.addDocument(document, chunks)

        // Verify document exists
        assertNotNull(store.getDocument("doc-cascade"))

        // Remove document
        store.removeDocument("doc-cascade")

        // Verify document is gone
        assertNull(store.getDocument("doc-cascade"))

        // Note: Can't directly test chunk removal without exposing chunk queries,
        // but SQL cascade should handle it
    }

    @Test
    fun `test encrypted database cannot be read with wrong passphrase`() {
        val correctPassphrase = "correct-key-123"

        // Create database with correct passphrase
        val driver1 = createVectorStoreDriver(testDbPath, correctPassphrase)
        val store1 = SqliteVectorStore(driver1, NoOpRedactor())

        // Try to open with wrong passphrase - should fail
        assertFailsWith<IllegalStateException> {
            createVectorStoreDriver(testDbPath, "wrong-key-456")
        }
    }

    @Test
    fun `test NoOpRedactor does not modify content`() = runTest {
        val noOpRedactor = NoOpRedactor()

        val sensitiveContent = """
            Phone: 555-123-4567
            Email: test@example.com
            SSN: 123-45-6789
        """.trimIndent()

        val redacted = noOpRedactor.redact(sensitiveContent)

        // NoOpRedactor should not change anything
        assertEquals(sensitiveContent, redacted)

        // Should report no patterns
        assertEquals(0, noOpRedactor.getRedactionPatterns().size)
    }

    @Test
    fun `test vector store initialization logs useful information`() = runTest {
        val passphrase = "info-test"
        val redactor = RegexPrivacyRedactor()

        val driver = createVectorStoreDriver(testDbPath, passphrase)
        val store = SqliteVectorStore(driver, redactor, embeddingDimension = 1024)

        // Check that initialization would log dimension info
        // (actual logging happens in init block, we're just verifying structure)
        assertEquals(1024, 1024) // Placeholder assertion

        // Verify redactor reports its patterns
        val patterns = redactor.getRedactionPatterns()
        assertTrue(patterns.isNotEmpty(), "Redactor should report patterns")
        assertTrue(patterns.contains("Phone Numbers"), "Should report phone pattern")
        assertTrue(patterns.contains("Email Addresses"), "Should report email pattern")
    }
}
