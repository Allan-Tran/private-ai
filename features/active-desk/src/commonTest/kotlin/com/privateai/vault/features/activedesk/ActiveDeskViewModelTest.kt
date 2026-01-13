package com.privateai.vault.features.activedesk

import com.privateai.vault.inference.*
import com.privateai.vault.vectorstore.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.*
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ActiveDeskViewModelTest {

    private lateinit var inferenceEngine: MockInferenceEngine
    private lateinit var vectorStore: MockVectorStore
    private lateinit var contextualRetriever: MockContextualRetriever
    private lateinit var documentIngestor: MockDocumentIngestor
    private lateinit var viewModel: ActiveDeskViewModel
    
    // Test dispatcher for controlling coroutines
    private val testDispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        
        inferenceEngine = MockInferenceEngine()
        vectorStore = MockVectorStore()
        contextualRetriever = MockContextualRetriever()
        documentIngestor = MockDocumentIngestor()
        
        viewModel = ActiveDeskViewModel(
            inferenceEngine = inferenceEngine,
            vectorStore = vectorStore,
            contextualRetriever = contextualRetriever,
            documentIngestor = documentIngestor,
            scope = TestScope(testDispatcher)
        )
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `sendMessage updates state to Loading then Success`() = runTest(testDispatcher) {
        // Arrange
        val query = "What is the capital of France?"
        val expectedResponse = "The capital of France is Paris."
        inferenceEngine.mockResponse = expectedResponse
        
        // Act
        viewModel.sendMessage(query)
        
        // Assert - Loading State
        // Advance until the first part of the flow
        testScheduler.advanceUntilIdle()
        
        val state = viewModel.state.value
        assertFalse(state.isLoading)
        assertTrue(state.messages.isNotEmpty())
        
        // Verify user message
        val userMsg = state.messages.find { it.role == MessageRole.USER }
        assertEquals(query, userMsg?.content)
        
        // Verify assistant message
        val aiMsg = state.messages.find { it.role == MessageRole.ASSISTANT }
        assertEquals(expectedResponse, aiMsg?.content)
        assertFalse(aiMsg?.isStreaming ?: true)
    }

    @Test
    fun `ingestFile adds document to state`() = runTest(testDispatcher) {
        // Arrange
        val filePath = "/tmp/test_doc.txt"
        val fileName = "test_doc.txt"
        
        // Act
        viewModel.ingestFile(filePath)
        testScheduler.advanceUntilIdle()
        
        // Assert
        val state = viewModel.state.value
        val doc = state.attachedDocuments.find { it.fileName == fileName }
        
        assertTrue(doc != null, "Document should be added to state")
    }

    @Test
    fun `updateInputText updates state`() = runTest(testDispatcher) {
        val text = "Hello world"
        viewModel.onEvent(ActiveDeskEvent.UpdateInputText(text))
        assertEquals(text, viewModel.state.value.inputText)
    }

    @Test
    fun `clearChat resets messages`() = runTest(testDispatcher) {
        // Add some state first
        viewModel.onEvent(ActiveDeskEvent.UpdateInputText("Test"))
        viewModel.sendMessage("Test")
        testScheduler.advanceUntilIdle()
        
        // Clear
        viewModel.onEvent(ActiveDeskEvent.ClearChat)
        
        assertTrue(viewModel.state.value.messages.isEmpty())
        assertTrue(viewModel.state.value.activeContextChunks.isEmpty())
    }
}

// --- Mocks ---

class MockInferenceEngine : InferenceEngine {
    var mockResponse = "Mock response"
    var isLoaded = true

    override suspend fun loadModel(modelPath: String, params: ModelParams): Boolean {
        isLoaded = true
        return true
    }

    override suspend fun unloadModel() {
        isLoaded = false
    }

    override fun isModelLoaded(): Boolean = isLoaded

    override fun generateStream(prompt: String, params: GenerationParams): Flow<String> = flow {
        emit(mockResponse)
    }

    override suspend fun embed(text: String): FloatArray = FloatArray(384)

    override fun getModelInfo(): ModelInfo? = ModelInfo(
        name = "Mock Model",
        architecture = "Llama",
        contextLength = 2048,
        embeddingDimension = 384,
        parameters = 7_000_000_000L,
        quantization = "Q4_0"
    )
}

class MockVectorStore : VectorStore {
    val documents = mutableListOf<Document>()
    val chunks = mutableListOf<DocumentChunk>()

    override suspend fun initialize(requireVectorExtension: Boolean) {}

    override suspend fun addDocument(document: Document, chunks: List<DocumentChunk>) {
        documents.add(document)
        this.chunks.addAll(chunks)
    }

    override suspend fun removeDocument(documentId: String) {
        documents.removeIf { it.id == documentId }
        chunks.removeIf { it.documentId == documentId }
    }

    override suspend fun getDocument(documentId: String): Document? {
        return documents.find { it.id == documentId }
    }

    override suspend fun searchSimilar(queryEmbedding: FloatArray, limit: Int, threshold: Float): List<SearchResult> {
        return emptyList()
    }

    override suspend fun createSession(session: Session) {}
    override suspend fun getSessions(): List<Session> = emptyList()
    override suspend fun getSession(sessionId: String): Session? = null
    override suspend fun addDocumentToSession(sessionId: String, documentId: String) {}
    override suspend fun removeDocumentFromSession(sessionId: String, documentId: String) {}
    override suspend fun getSessionDocuments(sessionId: String): List<Document> = emptyList()
    override suspend fun deleteSession(sessionId: String) {}
}

class MockContextualRetriever : ContextualRetriever {
    override suspend fun retrieveContext(
        query: String,
        config: RetrievalConfig
    ): RetrievedContext {
        return RetrievedContext(
            chunks = emptyList(),
            totalRetrieved = 0,
            query = query,
            retrievalTimeMs = 10
        )
    }

    // FIX: Added missing method
    override suspend fun retrieveContextStream(
        query: String,
        config: RetrievalConfig
    ): Flow<ContextChunk> = flow {
        // No-op for test
    }

    // FIX: Added missing method
    override suspend fun getContextWindowStats(): ContextWindowStats {
        return ContextWindowStats(0, 0, 0, 0, 0)
    }

    override fun formatContextForPrompt(context: RetrievedContext): String {
        return ""
    }
}

class MockDocumentIngestor : DocumentIngestor {
    override suspend fun ingestText(
        content: String,
        metadata: DocumentMetadata,
        config: ChunkingConfig
    ): Flow<IngestionProgress> = flow {
        emit(IngestionProgress.Reading(metadata.fileName))
        emit(IngestionProgress.Complete(
            documentId = "doc_1",
            chunkCount = 1,
            durationMs = 100
        ))
    }

    override suspend fun ingestPdf(
        pdfBytes: ByteArray,
        metadata: DocumentMetadata,
        config: ChunkingConfig
    ): Flow<IngestionProgress> = flow {
        emit(IngestionProgress.Complete(
            documentId = "doc_pdf",
            chunkCount = 1,
            durationMs = 100
        ))
    }

    override suspend fun ingestFolder(
        files: List<String>,
        folderName: String,
        config: ChunkingConfig
    ): Flow<IngestionProgress> = flow {
        emit(IngestionProgress.Complete(
            documentId = "doc_folder",
            chunkCount = 5,
            durationMs = 500
        ))
    }

    override suspend fun getStats(): IngestionStats {
        return IngestionStats(0, 0, 0, 0, 0)
    }
}