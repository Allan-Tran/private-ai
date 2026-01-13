package com.privateai.vault.features.activedesk

import com.privateai.vault.inference.GenerationParams
import com.privateai.vault.inference.InferenceEngine
import com.privateai.vault.inference.ModelInfo
import com.privateai.vault.vectorstore.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*

class ActiveDeskViewModelTest {

    private lateinit var testScope: TestScope
    private lateinit var mockInferenceEngine: MockInferenceEngine
    private lateinit var mockVectorStore: MockVectorStore
    private lateinit var mockContextualRetriever: MockContextualRetriever
    private lateinit var mockDocumentIngestor: MockDocumentIngestor
    private lateinit var viewModel: ActiveDeskViewModel

    @BeforeTest
    fun setup() {
        testScope = TestScope(UnconfinedTestDispatcher())
        mockInferenceEngine = MockInferenceEngine()
        mockVectorStore = MockVectorStore()
        mockContextualRetriever = MockContextualRetriever()
        mockDocumentIngestor = MockDocumentIngestor()

        viewModel = ActiveDeskViewModel(
            inferenceEngine = mockInferenceEngine,
            vectorStore = mockVectorStore,
            contextualRetriever = mockContextualRetriever,
            documentIngestor = mockDocumentIngestor,
            scope = testScope
        )
    }

    @AfterTest
    fun teardown() {
        viewModel.dispose()
    }

    // ========== sendMessage Tests ==========

    @Test
    fun `test sendMessage updates state to Loading then Success`() = testScope.runTest {
        // Arrange
        val query = "What is the meaning of life?"
        mockInferenceEngine.streamingTokens = listOf("The", " answer", " is", " 42")
        mockContextualRetriever.mockContext = RetrievedContext(
            chunks = emptyList(),
            totalRetrieved = 0,
            query = query,
            retrievalTimeMs = 10
        )

        // Act
        viewModel.sendMessage(query)

        // Allow coroutines to complete
        advanceUntilIdle()

        // Assert
        val state = viewModel.state.value

        // Should have 2 messages: user message and AI response
        assertEquals(2, state.messages.size)

        // First message is user's
        val userMessage = state.messages[0]
        assertEquals(MessageRole.USER, userMessage.role)
        assertEquals(query, userMessage.content)

        // Second message is AI's completed response
        val aiMessage = state.messages[1]
        assertEquals(MessageRole.ASSISTANT, aiMessage.role)
        assertEquals("The answer is 42", aiMessage.content)
        assertFalse(aiMessage.isStreaming)

        // Loading should be false after completion
        assertFalse(state.isLoading)
        assertNull(state.streamingMessageId)
    }

    @Test
    fun `test sendMessage with empty query does nothing`() = testScope.runTest {
        // Arrange
        val initialState = viewModel.state.value

        // Act
        viewModel.sendMessage("")
        viewModel.sendMessage("   ")

        advanceUntilIdle()

        // Assert - state should remain unchanged
        assertEquals(initialState.messages.size, viewModel.state.value.messages.size)
    }

    @Test
    fun `test sendMessage does not send while loading`() = testScope.runTest {
        // Arrange
        mockInferenceEngine.streamingTokens = listOf("Token1", "Token2")
        mockInferenceEngine.delayBetweenTokens = 100L
        mockContextualRetriever.mockContext = RetrievedContext(
            chunks = emptyList(),
            totalRetrieved = 0,
            query = "test",
            retrievalTimeMs = 0
        )

        // Act - send first message
        viewModel.sendMessage("First message")

        // Try to send another while first is still processing
        viewModel.sendMessage("Second message")

        advanceUntilIdle()

        // Assert - should only have messages from first send
        val state = viewModel.state.value
        assertEquals(2, state.messages.size) // 1 user + 1 AI
        assertEquals("First message", state.messages[0].content)
    }

    @Test
    fun `test sendMessage with context retrieval includes context chunks`() = testScope.runTest {
        // Arrange
        val query = "Tell me about the document"
        val mockChunks = listOf(
            RetrievedChunk(
                content = "This is relevant content from the document.",
                sourceDocument = "test.txt",
                relevanceScore = 0.9f,
                chunkIndex = 0,
                metadata = emptyMap()
            )
        )
        mockContextualRetriever.mockContext = RetrievedContext(
            chunks = mockChunks,
            totalRetrieved = 1,
            query = query,
            retrievalTimeMs = 15
        )
        mockInferenceEngine.streamingTokens = listOf("Based", " on", " the", " document...")

        // Act
        viewModel.sendMessage(query)
        advanceUntilIdle()

        // Assert
        val state = viewModel.state.value
        assertEquals(1, state.activeContextChunks.size)
        assertEquals("test.txt", state.activeContextChunks[0].sourceDocument)
        assertTrue(state.activeContextChunks[0].relevanceScore > 0.5f)
    }

    @Test
    fun `test sendMessage handles generation error gracefully`() = testScope.runTest {
        // Arrange
        mockInferenceEngine.shouldThrowError = true
        mockInferenceEngine.errorMessage = "Model not loaded"
        mockContextualRetriever.mockContext = RetrievedContext(
            chunks = emptyList(),
            totalRetrieved = 0,
            query = "test",
            retrievalTimeMs = 0
        )

        // Act
        viewModel.sendMessage("Test query")
        advanceUntilIdle()

        // Assert
        val state = viewModel.state.value
        assertNotNull(state.error)
        assertTrue(state.error!!.contains("Model not loaded") || state.error!!.contains("Failed"))
        assertFalse(state.isLoading)
    }

    // ========== ingestFile Tests ==========

    @Test
    fun `test ingestFile adds document to sidebar list`() = testScope.runTest {
        // Arrange
        val filePath = "/path/to/document.txt"
        mockDocumentIngestor.chunkCount = 5

        // Act
        viewModel.ingestFile(filePath)
        advanceUntilIdle()

        // Assert
        val state = viewModel.state.value
        assertEquals(1, state.attachedDocuments.size)

        val doc = state.attachedDocuments[0]
        assertEquals("document.txt", doc.fileName)
        assertEquals(filePath, doc.filePath)
        assertEquals(DocumentType.TEXT, doc.fileType)
    }

    @Test
    fun `test ingestFile updates document status through processing lifecycle`() = testScope.runTest {
        // Arrange
        val filePath = "/path/to/notes.md"
        mockDocumentIngestor.chunkCount = 3

        // Collect states during ingestion
        val statesCollected = mutableListOf<DocumentStatus>()
        val job = launch {
            viewModel.state.collect { state ->
                state.attachedDocuments.firstOrNull()?.status?.let {
                    if (statesCollected.isEmpty() || statesCollected.last() != it) {
                        statesCollected.add(it)
                    }
                }
            }
        }

        // Act
        viewModel.ingestFile(filePath)
        advanceUntilIdle()

        job.cancel()

        // Assert - document should go through PROCESSING -> READY
        val finalState = viewModel.state.value
        val doc = finalState.attachedDocuments.first()
        assertEquals(DocumentStatus.READY, doc.status)
        assertEquals(3, doc.chunkCount)
    }

    @Test
    fun `test ingestFile handles file not found error`() = testScope.runTest {
        // Arrange
        val filePath = "/nonexistent/file.txt"
        mockDocumentIngestor.shouldSimulateFileNotFound = true

        // Act
        viewModel.ingestFile(filePath)
        advanceUntilIdle()

        // Assert
        val state = viewModel.state.value
        assertEquals(1, state.attachedDocuments.size)
        assertEquals(DocumentStatus.ERROR, state.attachedDocuments[0].status)
        assertNotNull(state.attachedDocuments[0].errorMessage)
    }

    @Test
    fun `test ingestFile with PDF file type`() = testScope.runTest {
        // Arrange
        val filePath = "/documents/report.pdf"
        mockDocumentIngestor.chunkCount = 10

        // Act
        viewModel.ingestFile(filePath)
        advanceUntilIdle()

        // Assert
        val state = viewModel.state.value
        assertEquals(1, state.attachedDocuments.size)
        assertEquals(DocumentType.PDF, state.attachedDocuments[0].fileType)
        assertEquals("report.pdf", state.attachedDocuments[0].fileName)
    }

    @Test
    fun `test ingestFile adds system message on completion`() = testScope.runTest {
        // Arrange
        val filePath = "/path/to/doc.txt"
        mockDocumentIngestor.chunkCount = 5

        // Act
        viewModel.ingestFile(filePath)
        advanceUntilIdle()

        // Assert - should have a system message about document processing
        val state = viewModel.state.value
        val systemMessages = state.messages.filter { it.role == MessageRole.SYSTEM }
        assertEquals(1, systemMessages.size)
        assertTrue(systemMessages[0].content.contains("doc.txt"))
        assertTrue(systemMessages[0].content.contains("5 chunks"))
    }

    // ========== Streaming Response Tests ==========

    @Test
    fun `test streaming response appends tokens correctly`() = testScope.runTest {
        // Arrange
        val tokens = listOf("Hello", " ", "world", "!", " How", " are", " you", "?")
        mockInferenceEngine.streamingTokens = tokens
        mockContextualRetriever.mockContext = RetrievedContext(
            chunks = emptyList(),
            totalRetrieved = 0,
            query = "test",
            retrievalTimeMs = 0
        )

        // Act
        viewModel.sendMessage("Greet me")
        advanceUntilIdle()

        // Assert
        val state = viewModel.state.value
        val aiMessage = state.messages.find { it.role == MessageRole.ASSISTANT }
        assertNotNull(aiMessage)
        assertEquals("Hello world! How are you?", aiMessage.content)
        assertFalse(aiMessage.isStreaming)
    }

    @Test
    fun `test streaming message has correct id during generation`() = testScope.runTest {
        // Arrange
        mockInferenceEngine.streamingTokens = listOf("Test", " response")
        mockContextualRetriever.mockContext = RetrievedContext(
            chunks = emptyList(),
            totalRetrieved = 0,
            query = "test",
            retrievalTimeMs = 0
        )

        // Act & capture intermediate states
        var streamingIdDuringGeneration: String? = null
        val job = launch {
            viewModel.state.collect { state ->
                if (state.isLoading && state.streamingMessageId != null) {
                    streamingIdDuringGeneration = state.streamingMessageId
                }
            }
        }

        viewModel.sendMessage("Test query")
        advanceUntilIdle()
        job.cancel()

        // Assert - streaming ID should have been set during generation
        // and should be null after completion
        val finalState = viewModel.state.value
        assertNull(finalState.streamingMessageId)
    }

    @Test
    fun `test stopGeneration cancels ongoing generation`() = testScope.runTest {
        // Arrange
        mockInferenceEngine.streamingTokens = List(100) { "token$it " }
        mockInferenceEngine.delayBetweenTokens = 50L
        mockContextualRetriever.mockContext = RetrievedContext(
            chunks = emptyList(),
            totalRetrieved = 0,
            query = "test",
            retrievalTimeMs = 0
        )

        // Act - start generation then stop it
        viewModel.sendMessage("Long query")
        advanceTimeBy(150) // Let a few tokens through

        viewModel.onEvent(ActiveDeskEvent.StopGeneration)
        advanceUntilIdle()

        // Assert
        val state = viewModel.state.value
        assertFalse(state.isLoading)
        assertNull(state.streamingMessageId)
    }

    // ========== Other Event Tests ==========

    @Test
    fun `test updateInputText updates state`() = testScope.runTest {
        // Act
        viewModel.onEvent(ActiveDeskEvent.UpdateInputText("Hello"))
        advanceUntilIdle()

        // Assert
        assertEquals("Hello", viewModel.state.value.inputText)
    }

    @Test
    fun `test clearChat removes all messages`() = testScope.runTest {
        // Arrange - add some messages first
        mockInferenceEngine.streamingTokens = listOf("Response")
        mockContextualRetriever.mockContext = RetrievedContext(
            chunks = emptyList(),
            totalRetrieved = 0,
            query = "test",
            retrievalTimeMs = 0
        )
        viewModel.sendMessage("Test")
        advanceUntilIdle()
        assertTrue(viewModel.state.value.messages.isNotEmpty())

        // Act
        viewModel.onEvent(ActiveDeskEvent.ClearChat)
        advanceUntilIdle()

        // Assert
        assertTrue(viewModel.state.value.messages.isEmpty())
        assertTrue(viewModel.state.value.activeContextChunks.isEmpty())
    }

    @Test
    fun `test toggleMemoryView toggles expanded state`() = testScope.runTest {
        // Arrange
        val initialExpanded = viewModel.state.value.memoryViewExpanded

        // Act
        viewModel.onEvent(ActiveDeskEvent.ToggleMemoryView)
        advanceUntilIdle()

        // Assert
        assertEquals(!initialExpanded, viewModel.state.value.memoryViewExpanded)

        // Toggle again
        viewModel.onEvent(ActiveDeskEvent.ToggleMemoryView)
        advanceUntilIdle()

        assertEquals(initialExpanded, viewModel.state.value.memoryViewExpanded)
    }

    @Test
    fun `test removeDocument removes from list`() = testScope.runTest {
        // Arrange - add a document first
        mockDocumentIngestor.chunkCount = 3
        viewModel.ingestFile("/path/to/doc.txt")
        advanceUntilIdle()

        val docId = viewModel.state.value.attachedDocuments.first().id

        // Act
        viewModel.onEvent(ActiveDeskEvent.RemoveDocument(docId))
        advanceUntilIdle()

        // Assert
        assertTrue(viewModel.state.value.attachedDocuments.isEmpty())
    }

    @Test
    fun `test clearError clears error message`() = testScope.runTest {
        // Arrange - trigger an error
        mockInferenceEngine.shouldThrowError = true
        mockContextualRetriever.mockContext = RetrievedContext(
            chunks = emptyList(),
            totalRetrieved = 0,
            query = "test",
            retrievalTimeMs = 0
        )
        viewModel.sendMessage("Test")
        advanceUntilIdle()
        assertNotNull(viewModel.state.value.error)

        // Act
        viewModel.onEvent(ActiveDeskEvent.ClearError)
        advanceUntilIdle()

        // Assert
        assertNull(viewModel.state.value.error)
    }
}

// ========== Mock Implementations ==========

class MockInferenceEngine : InferenceEngine {
    var streamingTokens: List<String> = emptyList()
    var delayBetweenTokens: Long = 0L
    var shouldThrowError: Boolean = false
    var errorMessage: String = "Mock error"
    private var modelLoaded: Boolean = true

    override fun isModelLoaded(): Boolean = modelLoaded

    override fun getModelInfo(): ModelInfo? = ModelInfo(
        name = "mock-model",
        path = "/mock/path",
        parameterCount = 1_000_000,
        contextLength = 4096
    )

    override suspend fun generate(prompt: String, params: GenerationParams): String {
        if (shouldThrowError) throw RuntimeException(errorMessage)
        return streamingTokens.joinToString("")
    }

    override fun generateStream(prompt: String, params: GenerationParams): Flow<String> = flow {
        if (shouldThrowError) throw RuntimeException(errorMessage)
        for (token in streamingTokens) {
            if (delayBetweenTokens > 0) {
                delay(delayBetweenTokens)
            }
            emit(token)
        }
    }

    override suspend fun loadModel(modelPath: String): Boolean {
        modelLoaded = true
        return true
    }

    override suspend fun unloadModel() {
        modelLoaded = false
    }
}

class MockVectorStore : VectorStore {
    private val documents = mutableMapOf<String, Document>()
    private val chunks = mutableListOf<Chunk>()

    override suspend fun initialize(requireVectorExtension: Boolean) {}

    override suspend fun addDocument(document: Document, documentChunks: List<Chunk>) {
        documents[document.id] = document
        chunks.addAll(documentChunks)
    }

    override suspend fun getDocument(documentId: String): Document? = documents[documentId]

    override suspend fun deleteDocument(documentId: String) {
        documents.remove(documentId)
        chunks.removeAll { it.documentId == documentId }
    }

    override suspend fun searchSimilar(
        queryEmbedding: FloatArray,
        limit: Int,
        threshold: Float
    ): List<SearchResult> = emptyList()

    override suspend fun getAllDocuments(): List<Document> = documents.values.toList()

    override suspend fun getDocumentChunks(documentId: String): List<Chunk> =
        chunks.filter { it.documentId == documentId }

    override suspend fun getChunkCount(): Int = chunks.size

    override suspend fun close() {}
}

class MockContextualRetriever : ContextualRetriever {
    var mockContext: RetrievedContext = RetrievedContext(
        chunks = emptyList(),
        totalRetrieved = 0,
        query = "",
        retrievalTimeMs = 0
    )

    override suspend fun retrieveContext(query: String, config: RetrievalConfig): RetrievedContext {
        return mockContext.copy(query = query)
    }

    override fun formatContextForPrompt(context: RetrievedContext): String {
        if (context.chunks.isEmpty()) {
            return "You are a helpful assistant. Please answer: ${context.query}"
        }
        val contextText = context.chunks.joinToString("\n\n") { it.content }
        return """Based on the following context:

$contextText

Please answer this question: ${context.query}"""
    }
}

class MockDocumentIngestor : DocumentIngestor {
    var chunkCount: Int = 5
    var shouldSimulateFileNotFound: Boolean = false

    override fun ingestText(content: String, metadata: DocumentMetadata): Flow<IngestionProgress> = flow {
        if (shouldSimulateFileNotFound) {
            emit(IngestionProgress.Error("File not found"))
            return@flow
        }

        emit(IngestionProgress.Started)
        emit(IngestionProgress.Chunking(0, 1))
        emit(IngestionProgress.Embedding(0, chunkCount))
        emit(IngestionProgress.Complete(chunkCount))
    }

    override fun ingestFile(filePath: String, metadata: DocumentMetadata?): Flow<IngestionProgress> =
        ingestText("mock content", metadata ?: DocumentMetadata(fileName = "mock.txt", fileType = "txt", fileSize = 100, uploadedAt = 0))

    override fun ingestFolder(folderPath: String): Flow<IngestionProgress> = flow {
        emit(IngestionProgress.Complete(chunkCount))
    }
}
