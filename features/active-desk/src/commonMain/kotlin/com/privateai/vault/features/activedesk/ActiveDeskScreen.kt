package com.privateai.vault.features.activedesk

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Epic 4: Active Desk - Main UI Screen
 *
 * A split-pane design with:
 * - Left: Chat History (scrollable list of message bubbles)
 * - Right (collapsible): Memory View (loaded documents and active context)
 * - Bottom: Input area with text field, send button, and file drop target
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActiveDeskScreen(
    viewModel: ActiveDeskViewModel,
    onFileDrop: ((String) -> Unit)? = null
) {
    val state by viewModel.state.collectAsState()
    val scope = rememberCoroutineScope()

    // Handle effects
    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is ActiveDeskEffect.ShowToast -> {
                    // Handle toast (platform-specific)
                }
                is ActiveDeskEffect.ScrollToMessage -> {
                    // Handled in chat list
                }
                is ActiveDeskEffect.FileDropped -> {
                    onFileDrop?.invoke(effect.path)
                }
                is ActiveDeskEffect.FocusInput -> {
                    // Focus handled by platform
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Active Desk")
                        if (state.modelName != null) {
                            Spacer(Modifier.width(8.dp))
                            AssistChip(
                                onClick = {},
                                label = { Text(state.modelName!!, maxLines = 1) },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Build,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.onEvent(ActiveDeskEvent.ClearChat) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear chat")
                    }
                    IconButton(onClick = { viewModel.onEvent(ActiveDeskEvent.ToggleMemoryView) }) {
                        Icon(
                            if (state.memoryViewExpanded) Icons.Default.KeyboardArrowRight else Icons.Default.KeyboardArrowLeft,
                            contentDescription = "Toggle memory view"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Main chat area
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                // Chat messages
                ChatMessageList(
                    messages = state.messages,
                    isLoading = state.isLoading,
                    modifier = Modifier.weight(1f)
                )

                // Error banner
                AnimatedVisibility(visible = state.error != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                state.error ?: "",
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { viewModel.onEvent(ActiveDeskEvent.ClearError) }) {
                                Icon(Icons.Default.Close, contentDescription = "Dismiss")
                            }
                        }
                    }
                }

                // Input area
                ChatInputArea(
                    inputText = state.inputText,
                    isLoading = state.isLoading,
                    onInputChange = { viewModel.onEvent(ActiveDeskEvent.UpdateInputText(it)) },
                    onSend = { viewModel.onEvent(ActiveDeskEvent.SendMessage(state.inputText)) },
                    onStopGeneration = { viewModel.onEvent(ActiveDeskEvent.StopGeneration) },
                    onAttachFile = { /* File picker would be platform-specific */ }
                )
            }

            // Memory view panel (collapsible)
            AnimatedVisibility(
                visible = state.memoryViewExpanded,
                enter = expandHorizontally() + fadeIn(),
                exit = shrinkHorizontally() + fadeOut()
            ) {
                MemoryViewPanel(
                    attachedDocuments = state.attachedDocuments,
                    activeContextChunks = state.activeContextChunks,
                    onRemoveDocument = { viewModel.onEvent(ActiveDeskEvent.RemoveDocument(it)) },
                    onAttachFile = { path -> viewModel.onEvent(ActiveDeskEvent.AttachFile(path)) },
                    modifier = Modifier
                        .width(300.dp)
                        .fillMaxHeight()
                )
            }
        }
    }
}

/**
 * Chat message list with auto-scroll to bottom.
 */
@Composable
private fun ChatMessageList(
    messages: List<ChatMessage>,
    isLoading: Boolean,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Auto-scroll to bottom when new messages arrive
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            scope.launch {
                listState.animateScrollToItem(messages.size - 1)
            }
        }
    }

    if (messages.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.Email,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.outline
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "Start a conversation",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.outline
                )
                Text(
                    "Upload documents and ask questions about them",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    } else {
        LazyColumn(
            state = listState,
            modifier = modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            items(messages, key = { it.id }) { message ->
                ChatBubble(message = message)
            }

            // Typing indicator
            if (isLoading) {
                item {
                    TypingIndicator()
                }
            }
        }
    }
}

/**
 * Chat bubble with distinct styles for User (blue, right) and AI (gray, left).
 */
@Composable
private fun ChatBubble(message: ChatMessage) {
    val isUser = message.role == MessageRole.USER
    val isSystem = message.role == MessageRole.SYSTEM

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        if (!isUser && !isSystem) {
            // AI avatar
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(32.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Face,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
        }

        Column(
            modifier = Modifier.widthIn(max = 600.dp),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
        ) {
            Surface(
                shape = RoundedCornerShape(
                    topStart = 16.dp,
                    topEnd = 16.dp,
                    bottomStart = if (isUser) 16.dp else 4.dp,
                    bottomEnd = if (isUser) 4.dp else 16.dp
                ),
                color = when {
                    isSystem -> MaterialTheme.colorScheme.tertiaryContainer
                    isUser -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.surfaceVariant
                }
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = message.content,
                        color = when {
                            isSystem -> MaterialTheme.colorScheme.onTertiaryContainer
                            isUser -> MaterialTheme.colorScheme.onPrimary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )

                    // Streaming cursor
                    if (message.isStreaming) {
                        StreamingCursor()
                    }

                    // Source documents
                    if (message.sourceDocuments.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                Icons.Default.List,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.outline
                            )
                            Text(
                                "Sources: ${message.sourceDocuments.joinToString(", ") { it.substringAfterLast("/") }}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }

            // Timestamp
            Text(
                text = formatTimestamp(message.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 4.dp, start = 4.dp, end = 4.dp)
            )
        }

        if (isUser) {
            Spacer(Modifier.width(8.dp))
            // User avatar
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Person,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }
    }
}

/**
 * Animated streaming cursor.
 */
@Composable
private fun StreamingCursor() {
    val infiniteTransition = rememberInfiniteTransition()
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(500),
            repeatMode = RepeatMode.Reverse
        )
    )

    Text(
        text = "|",
        color = MaterialTheme.colorScheme.primary.copy(alpha = alpha),
        fontWeight = FontWeight.Bold
    )
}

/**
 * Typing indicator animation.
 */
@Composable
private fun TypingIndicator() {
    val infiniteTransition = rememberInfiniteTransition()

    Row(
        modifier = Modifier.padding(start = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        repeat(3) { index ->
            val delay = index * 150
            val scale by infiniteTransition.animateFloat(
                initialValue = 0.5f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(600, delayMillis = delay),
                    repeatMode = RepeatMode.Reverse
                )
            )

            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary.copy(alpha = scale),
                modifier = Modifier.size((8 * scale).dp)
            ) {}
        }
    }
}

/**
 * Chat input area with text field and buttons.
 */
@Composable
private fun ChatInputArea(
    inputText: String,
    isLoading: Boolean,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStopGeneration: () -> Unit,
    onAttachFile: () -> Unit
) {
    Surface(
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Attach file button
            IconButton(
                onClick = onAttachFile,
                enabled = !isLoading
            ) {
                Icon(Icons.Default.Add, contentDescription = "Attach file")
            }

            // Text input
            OutlinedTextField(
                value = inputText,
                onValueChange = onInputChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask a question...") },
                enabled = !isLoading,
                maxLines = 5,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
                shape = RoundedCornerShape(24.dp)
            )

            // Send or Stop button
            if (isLoading) {
                FilledIconButton(
                    onClick = onStopGeneration,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Default.Clear, contentDescription = "Stop generation")
                }
            } else {
                FilledIconButton(
                    onClick = onSend,
                    enabled = inputText.isNotBlank()
                ) {
                    Icon(Icons.Default.Send, contentDescription = "Send message")
                }
            }
        }
    }
}

/**
 * Memory view panel showing documents and active context.
 */
@Composable
private fun MemoryViewPanel(
    attachedDocuments: List<AttachedDocument>,
    activeContextChunks: List<ActiveContextChunk>,
    onRemoveDocument: (String) -> Unit,
    onAttachFile: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        tonalElevation = 1.dp,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // Documents section
            Text(
                "Documents",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(8.dp))

            if (attachedDocuments.isEmpty()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.outline
                            )
                            Text(
                                "Drop files here",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(attachedDocuments, key = { it.id }) { doc ->
                        DocumentCard(
                            document = doc,
                            onRemove = { onRemoveDocument(doc.id) }
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            // Active context section
            Text(
                "Active Context",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(8.dp))

            if (activeContextChunks.isEmpty()) {
                Text(
                    "No active context",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(activeContextChunks, key = { it.id }) { chunk ->
                        ContextChunkCard(chunk = chunk)
                    }
                }
            }
        }
    }
}

/**
 * Document card in the memory view.
 */
@Composable
private fun DocumentCard(
    document: AttachedDocument,
    onRemove: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (document.status) {
                DocumentStatus.READY -> MaterialTheme.colorScheme.primaryContainer
                DocumentStatus.ERROR -> MaterialTheme.colorScheme.errorContainer
                DocumentStatus.PROCESSING -> MaterialTheme.colorScheme.secondaryContainer
                DocumentStatus.PENDING -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                when (document.fileType) {
                    DocumentType.PDF -> Icons.Default.Info
                    DocumentType.TEXT -> Icons.Default.Create
                    DocumentType.MARKDOWN -> Icons.Default.Edit
                    DocumentType.UNKNOWN -> Icons.Default.Search
                },
                contentDescription = null,
                modifier = Modifier.size(24.dp)
            )

            Spacer(Modifier.width(8.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    document.fileName,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    when (document.status) {
                        DocumentStatus.READY -> "${document.chunkCount} chunks"
                        DocumentStatus.PROCESSING -> "Processing..."
                        DocumentStatus.ERROR -> document.errorMessage ?: "Error"
                        DocumentStatus.PENDING -> "Pending"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            if (document.status == DocumentStatus.PROCESSING) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp
                )
            } else {
                IconButton(
                    onClick = onRemove,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Remove",
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

/**
 * Context chunk card in the memory view.
 */
@Composable
private fun ContextChunkCard(chunk: ActiveContextChunk) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    chunk.sourceDocument.substringAfterLast("/"),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.primary.copy(
                        alpha = chunk.relevanceScore
                    )
                ) {
                    Text(
                        "${(chunk.relevanceScore * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                chunk.content,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Format timestamp for display.
 */
private fun formatTimestamp(instant: Instant): String {
    val localDateTime = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    return "${localDateTime.hour.toString().padStart(2, '0')}:${localDateTime.minute.toString().padStart(2, '0')}"
}
