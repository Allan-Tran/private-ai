package com.privateai.vault.features.activedesk

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * Epic 4: Active Desk - State Management
 *
 * Data classes representing the UI state for the main chat interface.
 */

/**
 * Main state container for the Active Desk screen.
 */
data class ActiveDeskState(
    val messages: List<ChatMessage> = emptyList(),
    val attachedDocuments: List<AttachedDocument> = emptyList(),
    val inputText: String = "",
    val isLoading: Boolean = false,
    val isModelLoaded: Boolean = false,
    val modelName: String? = null,
    val error: String? = null,
    val memoryViewExpanded: Boolean = true,
    val activeContextChunks: List<ActiveContextChunk> = emptyList(),
    val streamingMessageId: String? = null
)

/**
 * Represents a single chat message (User or AI).
 */
data class ChatMessage(
    val id: String,
    val content: String,
    val role: MessageRole,
    val timestamp: Instant = Clock.System.now(),
    val isStreaming: Boolean = false,
    val sourceDocuments: List<String> = emptyList(),
    val metadata: Map<String, String> = emptyMap()
) {
    companion object {
        fun user(content: String): ChatMessage = ChatMessage(
            id = generateId(),
            content = content,
            role = MessageRole.USER
        )

        fun assistant(content: String = "", isStreaming: Boolean = false): ChatMessage = ChatMessage(
            id = generateId(),
            content = content,
            role = MessageRole.ASSISTANT,
            isStreaming = isStreaming
        )

        fun system(content: String): ChatMessage = ChatMessage(
            id = generateId(),
            content = content,
            role = MessageRole.SYSTEM
        )

        private fun generateId(): String = "msg_${Clock.System.now().toEpochMilliseconds()}_${(Math.random() * 1000).toInt()}"
    }
}

/**
 * Message sender role.
 */
enum class MessageRole {
    USER,
    ASSISTANT,
    SYSTEM
}

/**
 * Represents a document attached/uploaded in the current session.
 */
data class AttachedDocument(
    val id: String,
    val fileName: String,
    val filePath: String,
    val fileSize: Long,
    val fileType: DocumentType,
    val uploadedAt: Instant = Clock.System.now(),
    val status: DocumentStatus = DocumentStatus.PENDING,
    val chunkCount: Int = 0,
    val errorMessage: String? = null
) {
    companion object {
        fun fromPath(path: String): AttachedDocument {
            val fileName = path.substringAfterLast("/").substringAfterLast("\\")
            val extension = fileName.substringAfterLast(".", "").lowercase()
            val fileType = when (extension) {
                "pdf" -> DocumentType.PDF
                "txt" -> DocumentType.TEXT
                "md" -> DocumentType.MARKDOWN
                else -> DocumentType.UNKNOWN
            }
            return AttachedDocument(
                id = "doc_${Clock.System.now().toEpochMilliseconds()}_${(Math.random() * 1000).toInt()}",
                fileName = fileName,
                filePath = path,
                fileSize = 0, // Will be updated when reading file
                fileType = fileType
            )
        }
    }
}

/**
 * Document file type.
 */
enum class DocumentType {
    PDF,
    TEXT,
    MARKDOWN,
    UNKNOWN
}

/**
 * Document processing status.
 */
enum class DocumentStatus {
    PENDING,
    PROCESSING,
    READY,
    ERROR
}

/**
 * Represents a context chunk currently being used for RAG.
 */
data class ActiveContextChunk(
    val id: String,
    val content: String,
    val sourceDocument: String,
    val relevanceScore: Float,
    val chunkIndex: Int
)

/**
 * UI events that can be triggered from the Active Desk screen.
 */
sealed class ActiveDeskEvent {
    data class SendMessage(val message: String) : ActiveDeskEvent()
    data class AttachFile(val filePath: String) : ActiveDeskEvent()
    data class RemoveDocument(val documentId: String) : ActiveDeskEvent()
    data class UpdateInputText(val text: String) : ActiveDeskEvent()
    data object ToggleMemoryView : ActiveDeskEvent()
    data object ClearChat : ActiveDeskEvent()
    data object ClearError : ActiveDeskEvent()
    data object StopGeneration : ActiveDeskEvent()
}

/**
 * Side effects that the ViewModel can emit.
 */
sealed class ActiveDeskEffect {
    data class ShowToast(val message: String) : ActiveDeskEffect()
    data class ScrollToMessage(val messageId: String) : ActiveDeskEffect()
    data class FileDropped(val path: String) : ActiveDeskEffect()
    data object FocusInput : ActiveDeskEffect()
}
