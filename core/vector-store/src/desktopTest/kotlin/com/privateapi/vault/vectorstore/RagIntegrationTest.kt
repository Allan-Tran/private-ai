package com.privateai.vault.vectorstore

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RagIntegrationTest {

    @Test
    fun `test end-to-end rag flow`() = runTest {
        // 1. Setup
        val dbPath = "build/rag-test.db"
        File(dbPath).delete()
        
        // Load the DLL (Optional for the Fallback search, but good to test)
        // Ensure vec0.dll is in your project root
        
        // 2. Initialize Store with 3072 dimensions (Llama 3.2 3B size)
        val driver = createVectorStoreDriver(dbPath, "test-passphrase")
        
        // Use the Fallback search logic Claude implemented
        val store = SqliteVectorStore(driver, RegexPrivacyRedactor(), embeddingDimension = 3072)
        store.initialize(requireVectorExtension = false) // Allow fallback mode

        // 3. Fake Embedding (3072 floats)
        val fakeEmbedding = FloatArray(3072) { 0.1f }
        
        // 4. Add Document
        val docId = "doc-1"
        store.addDocument(
            Document(docId, "Boxing Drill: Left Hook", "test.txt", emptyMap(), 1, 0, 0),
            listOf(
                DocumentChunk("chunk-1", docId, "Boxing Drill: Left Hook", 0, 10, fakeEmbedding, 0)
            )
        )

        // 5. Search
        val results = store.searchSimilar(fakeEmbedding, limit = 1)
        
        // 6. Verify
        assertEquals(1, results.size, "Should find 1 result")
        assertEquals("chunk-1", results[0].chunk.id)
        println("✅ RAG Search verified!")
    }
}