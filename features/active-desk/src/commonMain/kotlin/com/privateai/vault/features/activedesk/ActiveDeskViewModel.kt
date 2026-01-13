package com.privateai.vault.features.activedesk

import com.privateai.vault.inference.GenerationParams
import com.privateai.vault.inference.InferenceEngine
import com.privateai.vault.vectorstore.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File

/**
 * Epic 4: Active Desk - ViewModel
 *
 * Manages the state and business logic for the main chat interface.
 * Handles RAG flow: vectorStore.search -> Construct Prompt -> inferenceEngine.generate
 */
class ActiveDeskViewModel(
    private val inferenceEngine: InferenceEngine,
    private val vectorStore: VectorStore,
    private val contextualRetriever: ContextualRetriever,
    private val documentIngestor: DocumentIngestor,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {
    private val _state = MutableStateFlow(ActiveDeskState())
    val state: StateFlow<ActiveDeskState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<ActiveDeskEffect>()
    val effects: SharedFlow<ActiveDeskEffect> = _effects.asSharedFlow()

    private var generationJob: Job? = null

    init {
        // Check if model is loaded on init
        updateModelStatus()
    }

    /**
     * Handle UI events from the screen.
     */
    fun onEvent(event: ActiveDeskEvent) {
        when (event) {
            is ActiveDeskEvent.SendMessage -> sendMessage(event.message)
            is ActiveDeskEvent.AttachFile -> ingestFile(event.filePath)
            is ActiveDeskEvent.RemoveDocument -> removeDocument(event.documentId)
            is ActiveDeskEvent.UpdateInputText -> updateInputText(event.text)
            is ActiveDeskEvent.ToggleMemoryView -> toggleMemoryView()
            is ActiveDeskEvent.ClearChat -> clearChat()
            is ActiveDeskEvent.ClearError -> clearError()
            is ActiveDeskEvent.StopGeneration -> stopGeneration()
        }
    }

    /**
     * Send a message and trigger the RAG flow.
     */
    fun sendMessage(query: String) {
        if (query.isBlank()) return
        if (_state.value.isLoading) return

        scope.launch {
            try {
                // Add user message
                val userMessage = ChatMessage.user(query)
                addMessage(userMessage)

                // Clear input
                _state.update { it.copy(inputText = "", isLoading = true, error = null) }

                // Create placeholder for AI response
                val aiMessage = ChatMessage.assistant(isStreaming = true)
                addMessage(aiMessage)
                _state.update { it.copy(streamingMessageId = aiMessage.id) }

                // Step 1: Retrieve relevant context
                val context = retrieveContext(query)
                updateActiveContext(context)

                // Step 2: Build prompt with context
                val prompt = buildPromptWithContext(query, context)

                // Step 3: Generate response with streaming
                generationJob = scope.launch {
                    generateStreamingResponse(aiMessage.id, prompt, context)
                }
                generationJob?.join()

            } catch (e: CancellationException) {
                // Generation was cancelled
                finalizeStreamingMessage("(Generation stopped)")
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isLoading = false,
                        error = "Failed to generate response: ${e.message}",
                        streamingMessageId = null
                    )
                }
            }
        }
    }

    /**
     * Ingest a file into the vector store.
     */
    fun ingestFile(filePath: String) {
        val document = AttachedDocument.fromPath(filePath)

        // Add document to state immediately
        _state.update { state ->
            state.copy(
                attachedDocuments = state.attachedDocuments + document.copy(status = DocumentStatus.PROCESSING)
            )
        }

        scope.launch {
            try {
                val file = File(filePath)
                if (!file.exists()) {
                    updateDocumentStatus(document.id, DocumentStatus.ERROR, "File not found")
                    return@launch
                }

                val content = file.readText()
                val metadata = DocumentMetadata(
                    fileName = document.fileName,
                    fileType = document.fileType.name.lowercase(),
                    fileSize = file.length(),
                    uploadedAt = System.currentTimeMillis()
                )

                var chunkCount = 0
                documentIngestor.ingestText(content, metadata).collect { progress ->
                    when (progress) {
                        is IngestionProgress.Complete -> {
                            chunkCount = progress.chunkCount
                            updateDocumentStatus(document.id, DocumentStatus.READY, null, chunkCount)

                            // Add system message about ingestion
                            val systemMessage = ChatMessage.system(
                                "Document '${document.fileName}' has been processed (${chunkCount} chunks). You can now ask questions about it."
                            )
                            addMessage(systemMessage)

                            _effects.emit(ActiveDeskEffect.ShowToast("Document ready: ${document.fileName}"))
                        }
                        is IngestionProgress.Error -> {
                            updateDocumentStatus(document.id, DocumentStatus.ERROR, progress.error)
                        }
                        is IngestionProgress.Embedding -> {
                            // Update progress if needed
                        }
                        else -> {}
                    }
                }
            } catch (e: Exception) {
                updateDocumentStatus(document.id, DocumentStatus.ERROR, e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Remove a document from the session.
     */
    private fun removeDocument(documentId: String) {
        _state.update { state ->
            state.copy(
                attachedDocuments = state.attachedDocuments.filter { it.id != documentId }
            )
        }
    }

    /**
     * Update the input text.
     */
    private fun updateInputText(text: String) {
        _state.update { it.copy(inputText = text) }
    }

    /**
     * Toggle the memory view panel.
     */
    private fun toggleMemoryView() {
        _state.update { it.copy(memoryViewExpanded = !it.memoryViewExpanded) }
    }

    /**
     * Clear all chat messages.
     */
    private fun clearChat() {
        _state.update { it.copy(messages = emptyList(), activeContextChunks = emptyList()) }
    }

    /**
     * Clear the error message.
     */
    private fun clearError() {
        _state.update { it.copy(error = null) }
    }

    /**
     * Stop the current generation.
     */
    private fun stopGeneration() {
        generationJob?.cancel()
        generationJob = null
        _state.update { it.copy(isLoading = false, streamingMessageId = null) }
    }

    // Private helper methods

    private fun updateModelStatus() {
        val isLoaded = inferenceEngine.isModelLoaded()
        val modelInfo = inferenceEngine.getModelInfo()
        _state.update {
            it.copy(
                isModelLoaded = isLoaded,
                modelName = modelInfo?.name
            )
        }
    }

    private fun addMessage(message: ChatMessage) {
        _state.update { state ->
            state.copy(messages = state.messages + message)
        }
        scope.launch {
            _effects.emit(ActiveDeskEffect.ScrollToMessage(message.id))
        }
    }

    private suspend fun retrieveContext(query: String): RetrievedContext {
        return try {
            contextualRetriever.retrieveContext(
                query = query,
                config = RetrievalConfig(
                    topK = 5,
                    minRelevanceScore = 0.5f,
                    maxContextLength = 2048
                )
            )
        } catch (e: Exception) {
            println("[ViewModel] Context retrieval failed: ${e.message}")
            RetrievedContext(
                chunks = emptyList(),
                totalRetrieved = 0,
                query = query,
                retrievalTimeMs = 0
            )
        }
    }

    private fun updateActiveContext(context: RetrievedContext) {
        val activeChunks = context.chunks.mapIndexed { index, chunk ->
            ActiveContextChunk(
                id = "ctx_$index",
                content = chunk.content.take(200) + if (chunk.content.length > 200) "..." else "",
                sourceDocument = chunk.sourceDocument,
                relevanceScore = chunk.relevanceScore,
                chunkIndex = chunk.chunkIndex
            )
        }
        _state.update { it.copy(activeContextChunks = activeChunks) }
    }

    private fun buildPromptWithContext(query: String, context: RetrievedContext): String {
        return if (context.chunks.isNotEmpty()) {
            contextualRetriever.formatContextForPrompt(context)
        } else {
            // No context available, just use the query directly
            """You are a helpful AI assistant. Please answer the following question:

$query

If you don't have enough information to answer, please say so."""
        }
    }

    private suspend fun generateStreamingResponse(
        messageId: String,
        prompt: String,
        context: RetrievedContext
    ) {
        val responseBuilder = StringBuilder()
        val sourceDocuments = context.chunks.map { it.sourceDocument }.distinct()

        try {
            inferenceEngine.generateStream(
                prompt = prompt,
                params = GenerationParams(
                    maxTokens = 1024,
                    temperature = 0.7f,
                    topP = 0.9f,
                    stopSequences = listOf("\n\nUser:", "\n\nHuman:")
                )
            ).collect { token ->
                responseBuilder.append(token)

                // Update the message with new content
                _state.update { state ->
                    val updatedMessages = state.messages.map { msg ->
                        if (msg.id == messageId) {
                            msg.copy(
                                content = responseBuilder.toString(),
                                isStreaming = true
                            )
                        } else msg
                    }
                    state.copy(messages = updatedMessages)
                }
            }

            // Finalize the message
            _state.update { state ->
                val updatedMessages = state.messages.map { msg ->
                    if (msg.id == messageId) {
                        msg.copy(
                            content = responseBuilder.toString(),
                            isStreaming = false,
                            sourceDocuments = sourceDocuments
                        )
                    } else msg
                }
                state.copy(
                    messages = updatedMessages,
                    isLoading = false,
                    streamingMessageId = null
                )
            }

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { state ->
                val updatedMessages = state.messages.map { msg ->
                    if (msg.id == messageId) {
                        msg.copy(
                            content = responseBuilder.toString() + "\n\n(Error: ${e.message})",
                            isStreaming = false
                        )
                    } else msg
                }
                state.copy(
                    messages = updatedMessages,
                    isLoading = false,
                    streamingMessageId = null,
                    error = "Generation error: ${e.message}"
                )
            }
        }
    }

    private fun finalizeStreamingMessage(suffix: String) {
        val messageId = _state.value.streamingMessageId ?: return
        _state.update { state ->
            val updatedMessages = state.messages.map { msg ->
                if (msg.id == messageId) {
                    msg.copy(
                        content = msg.content + suffix,
                        isStreaming = false
                    )
                } else msg
            }
            state.copy(
                messages = updatedMessages,
                isLoading = false,
                streamingMessageId = null
            )
        }
    }

    private fun updateDocumentStatus(
        documentId: String,
        status: DocumentStatus,
        errorMessage: String?,
        chunkCount: Int = 0
    ) {
        _state.update { state ->
            val updatedDocs = state.attachedDocuments.map { doc ->
                if (doc.id == documentId) {
                    doc.copy(
                        status = status,
                        errorMessage = errorMessage,
                        chunkCount = chunkCount
                    )
                } else doc
            }
            state.copy(attachedDocuments = updatedDocs)
        }
    }

    /**
     * Clean up resources when ViewModel is disposed.
     */
    fun dispose() {
        generationJob?.cancel()
        scope.cancel()
    }
}
