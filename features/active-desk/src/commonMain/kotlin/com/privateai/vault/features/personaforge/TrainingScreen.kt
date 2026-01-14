package com.privateai.vault.features.personaforge

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.collectLatest
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Persona Forge - Stage 1: Training Screen
 *
 * "Hacker/Pro" aesthetic UI for the Self-Labeling Factory.
 * Dark mode, monospaced fonts, terminal-style logging.
 */

// Color Palette - Hacker/Pro Dark Theme
private object ForgeColors {
    val background = Color(0xFF0D0D0D)
    val surface = Color(0xFF1A1A1A)
    val surfaceElevated = Color(0xFF242424)
    val border = Color(0xFF333333)
    val borderActive = Color(0xFF4ADE80)

    val textPrimary = Color(0xFFE5E5E5)
    val textSecondary = Color(0xFF8B8B8B)
    val textMuted = Color(0xFF525252)

    val accentGreen = Color(0xFF4ADE80)
    val accentBlue = Color(0xFF60A5FA)
    val accentYellow = Color(0xFFFBBF24)
    val accentRed = Color(0xFFEF4444)
    val accentPurple = Color(0xFFA78BFA)

    val gradientStart = Color(0xFF4ADE80)
    val gradientEnd = Color(0xFF60A5FA)
}

// Typography
private val MonoFont = FontFamily.Monospace

@Composable
fun TrainingScreen(
    viewModel: TrainingViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsState()
    val logListState = rememberLazyListState()

    // Scroll to bottom when new logs arrive
    LaunchedEffect(state.logs.size) {
        if (state.logs.isNotEmpty()) {
            logListState.animateScrollToItem(state.logs.size - 1)
        }
    }

    // Handle effects
    LaunchedEffect(Unit) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                is TrainingEffect.ScrollToBottom -> {
                    if (state.logs.isNotEmpty()) {
                        logListState.animateScrollToItem(state.logs.size - 1)
                    }
                }
                else -> {} // Handle other effects as needed
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ForgeColors.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
        ) {
            // Header
            ForgeHeader()

            Spacer(modifier = Modifier.height(24.dp))

            // Main content row
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                // Left panel - Controls
                Column(
                    modifier = Modifier
                        .weight(0.4f)
                        .fillMaxHeight()
                ) {
                    // Drop Zone
                    DropZone(
                        sourceFile = state.sourceFile,
                        isActive = state.phase is TrainingPhase.Idle || state.phase is TrainingPhase.Complete,
                        onFileDrop = { viewModel.onEvent(TrainingEvent.FileDropped(it)) },
                        modifier = Modifier.height(180.dp)
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    // Persona Selector
                    PersonaSelector(
                        selectedPersona = state.selectedPersona,
                        onPersonaSelected = { viewModel.onEvent(TrainingEvent.PersonaChanged(it)) },
                        enabled = state.phase is TrainingPhase.Idle
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    // Progress Display
                    if (state.phase !is TrainingPhase.Idle) {
                        ProgressDisplay(
                            phase = state.phase,
                            progress = state.progress
                        )

                        Spacer(modifier = Modifier.height(20.dp))
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    // Action Buttons
                    ActionButtons(
                        state = state,
                        onStartBaking = { viewModel.onEvent(TrainingEvent.StartBaking) },
                        onCancel = { viewModel.onEvent(TrainingEvent.Cancel) },
                        onReset = { viewModel.onEvent(TrainingEvent.Reset) },
                        onOpenFolder = { viewModel.onEvent(TrainingEvent.OpenOutputFolder) }
                    )
                }

                // Right panel - Terminal
                TerminalPanel(
                    logs = state.logs,
                    listState = logListState,
                    modifier = Modifier
                        .weight(0.6f)
                        .fillMaxHeight()
                )
            }
        }

        // Error Snackbar
        AnimatedVisibility(
            visible = state.error != null,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            state.error?.let { error ->
                ErrorBar(
                    message = error,
                    onDismiss = { viewModel.onEvent(TrainingEvent.ClearError) }
                )
            }
        }
    }
}

@Composable
private fun ForgeHeader() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = "PERSONA FORGE",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = MonoFont,
                color = ForgeColors.textPrimary
            )
            Text(
                text = "Self-Labeling Factory // Stage 1",
                fontSize = 12.sp,
                fontFamily = MonoFont,
                color = ForgeColors.textSecondary
            )
        }

        // Status indicator
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(ForgeColors.accentGreen, RoundedCornerShape(4.dp))
            )
            Text(
                text = "READY",
                fontSize = 11.sp,
                fontFamily = MonoFont,
                fontWeight = FontWeight.Medium,
                color = ForgeColors.accentGreen
            )
        }
    }
}

@Composable
private fun DropZone(
    sourceFile: SourceFile?,
    isActive: Boolean,
    onFileDrop: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor by animateColorAsState(
        targetValue = if (sourceFile != null) ForgeColors.accentGreen else ForgeColors.border,
        animationSpec = tween(300)
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(ForgeColors.surface)
            .border(
                width = 2.dp,
                brush = if (sourceFile != null) {
                    Brush.linearGradient(listOf(ForgeColors.accentGreen, ForgeColors.accentBlue))
                } else {
                    Brush.linearGradient(listOf(borderColor, borderColor))
                },
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(enabled = isActive) {
                // TODO: Implement file picker
            },
        contentAlignment = Alignment.Center
    ) {
        if (sourceFile != null) {
            // File loaded state
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "FILE LOADED",
                    fontSize = 10.sp,
                    fontFamily = MonoFont,
                    fontWeight = FontWeight.Medium,
                    color = ForgeColors.accentGreen
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = sourceFile.name,
                    fontSize = 16.sp,
                    fontFamily = MonoFont,
                    fontWeight = FontWeight.Bold,
                    color = ForgeColors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${formatFileSize(sourceFile.size)} // ${sourceFile.chunkCount} chunks",
                    fontSize = 11.sp,
                    fontFamily = MonoFont,
                    color = ForgeColors.textSecondary
                )
            }
        } else {
            // Empty state
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "[ DROP FILE ]",
                    fontSize = 18.sp,
                    fontFamily = MonoFont,
                    fontWeight = FontWeight.Bold,
                    color = ForgeColors.textMuted
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Drop .txt, .md, or .pdf to begin",
                    fontSize = 11.sp,
                    fontFamily = MonoFont,
                    color = ForgeColors.textSecondary
                )
            }
        }
    }
}

@Composable
private fun PersonaSelector(
    selectedPersona: PersonaOption,
    onPersonaSelected: (PersonaOption) -> Unit,
    enabled: Boolean
) {
    Column {
        Text(
            text = "TARGET PERSONA",
            fontSize = 10.sp,
            fontFamily = MonoFont,
            fontWeight = FontWeight.Medium,
            color = ForgeColors.textSecondary,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PersonaOption.entries.filter { it != PersonaOption.CUSTOM }.forEach { persona ->
                PersonaChip(
                    persona = persona,
                    isSelected = selectedPersona == persona,
                    onClick = { onPersonaSelected(persona) },
                    enabled = enabled
                )
            }
        }
    }
}

@Composable
private fun PersonaChip(
    persona: PersonaOption,
    isSelected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean
) {
    val backgroundColor by animateColorAsState(
        targetValue = if (isSelected) ForgeColors.surfaceElevated else ForgeColors.surface,
        animationSpec = tween(200)
    )
    val borderColor by animateColorAsState(
        targetValue = if (isSelected) ForgeColors.accentGreen else ForgeColors.border,
        animationSpec = tween(200)
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor)
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Selection indicator
        Box(
            modifier = Modifier
                .size(12.dp)
                .border(
                    width = 2.dp,
                    color = if (isSelected) ForgeColors.accentGreen else ForgeColors.textMuted,
                    shape = RoundedCornerShape(6.dp)
                )
                .padding(2.dp)
        ) {
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(ForgeColors.accentGreen, RoundedCornerShape(4.dp))
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = persona.displayName,
                fontSize = 13.sp,
                fontFamily = MonoFont,
                fontWeight = FontWeight.Medium,
                color = if (isSelected) ForgeColors.textPrimary else ForgeColors.textSecondary
            )
            Text(
                text = persona.description,
                fontSize = 10.sp,
                fontFamily = MonoFont,
                color = ForgeColors.textMuted
            )
        }
    }
}

@Composable
private fun ProgressDisplay(
    phase: TrainingPhase,
    progress: TrainingProgress
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(ForgeColors.surface)
            .border(1.dp, ForgeColors.border, RoundedCornerShape(8.dp))
            .padding(16.dp)
    ) {
        // Phase indicator
        val phaseText = when (phase) {
            is TrainingPhase.ReadingFile -> "READING: ${phase.fileName}"
            is TrainingPhase.GeneratingSyntheticData -> "GENERATING Q&A: ${phase.currentQuestion}/${phase.totalQuestions}"
            is TrainingPhase.ExportingData -> "EXPORTING: ${phase.format}"
            is TrainingPhase.Training -> "TRAINING: Epoch ${phase.epoch}/${phase.totalEpochs}"
            is TrainingPhase.Complete -> "COMPLETE: ${phase.soulName}"
            is TrainingPhase.Error -> "ERROR"
            else -> "IDLE"
        }

        Text(
            text = phaseText,
            fontSize = 11.sp,
            fontFamily = MonoFont,
            fontWeight = FontWeight.Medium,
            color = when (phase) {
                is TrainingPhase.Complete -> ForgeColors.accentGreen
                is TrainingPhase.Error -> ForgeColors.accentRed
                else -> ForgeColors.accentBlue
            }
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Progress bar
        val progressValue by animateFloatAsState(
            targetValue = progress.percentComplete,
            animationSpec = tween(300)
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(ForgeColors.surfaceElevated)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progressValue)
                    .fillMaxHeight()
                    .background(
                        Brush.horizontalGradient(
                            listOf(ForgeColors.accentGreen, ForgeColors.accentBlue)
                        )
                    )
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Stats
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            StatItem("Questions", "${progress.questionsGenerated}/${progress.questionsTotal}")
            StatItem("Chunks", "${progress.chunksProcessed}/${progress.chunksTotal}")
            StatItem("Time", formatDuration(progress.elapsedMs))
        }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            fontSize = 14.sp,
            fontFamily = MonoFont,
            fontWeight = FontWeight.Bold,
            color = ForgeColors.textPrimary
        )
        Text(
            text = label,
            fontSize = 9.sp,
            fontFamily = MonoFont,
            color = ForgeColors.textMuted
        )
    }
}

@Composable
private fun ActionButtons(
    state: TrainingState,
    onStartBaking: () -> Unit,
    onCancel: () -> Unit,
    onReset: () -> Unit,
    onOpenFolder: () -> Unit
) {
    val isIdle = state.phase is TrainingPhase.Idle
    val isProcessing = state.phase is TrainingPhase.GeneratingSyntheticData ||
            state.phase is TrainingPhase.Training ||
            state.phase is TrainingPhase.ExportingData
    val isComplete = state.phase is TrainingPhase.Complete
    val hasFile = state.sourceFile != null

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Main action button
        Button(
            onClick = {
                when {
                    isProcessing -> onCancel()
                    isComplete -> onReset()
                    else -> onStartBaking()
                }
            },
            enabled = hasFile || isProcessing || isComplete,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = when {
                    isProcessing -> ForgeColors.accentRed
                    isComplete -> ForgeColors.surfaceElevated
                    else -> Color.Transparent
                },
                contentColor = ForgeColors.textPrimary,
                disabledContainerColor = ForgeColors.surface,
                disabledContentColor = ForgeColors.textMuted
            ),
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(
                2.dp,
                if (hasFile && isIdle) {
                    Brush.linearGradient(listOf(ForgeColors.accentGreen, ForgeColors.accentBlue))
                } else {
                    Brush.linearGradient(listOf(ForgeColors.border, ForgeColors.border))
                }
            )
        ) {
            Text(
                text = when {
                    isProcessing -> "[ CANCEL ]"
                    isComplete -> "[ NEW SOUL ]"
                    else -> "[ BAKE SOUL ]"
                },
                fontSize = 14.sp,
                fontFamily = MonoFont,
                fontWeight = FontWeight.Bold
            )
        }

        // Secondary actions
        if (isComplete) {
            OutlinedButton(
                onClick = onOpenFolder,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = ForgeColors.textSecondary
                ),
                border = BorderStroke(1.dp, ForgeColors.border),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = "Open Output Folder",
                    fontSize = 12.sp,
                    fontFamily = MonoFont
                )
            }
        }
    }
}

@Composable
private fun TerminalPanel(
    logs: List<LogEntry>,
    listState: LazyListState,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(ForgeColors.surface)
            .border(1.dp, ForgeColors.border, RoundedCornerShape(12.dp))
    ) {
        // Terminal header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ForgeColors.surfaceElevated)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Terminal dots
                Box(modifier = Modifier.size(10.dp).background(ForgeColors.accentRed, RoundedCornerShape(5.dp)))
                Box(modifier = Modifier.size(10.dp).background(ForgeColors.accentYellow, RoundedCornerShape(5.dp)))
                Box(modifier = Modifier.size(10.dp).background(ForgeColors.accentGreen, RoundedCornerShape(5.dp)))
            }

            Text(
                text = "forge://terminal",
                fontSize = 11.sp,
                fontFamily = MonoFont,
                color = ForgeColors.textMuted
            )

            Text(
                text = "${logs.size} lines",
                fontSize = 10.sp,
                fontFamily = MonoFont,
                color = ForgeColors.textMuted
            )
        }

        // Terminal content
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            if (logs.isEmpty()) {
                item {
                    Text(
                        text = "// Waiting for commands...",
                        fontSize = 12.sp,
                        fontFamily = MonoFont,
                        color = ForgeColors.textMuted,
                        modifier = Modifier.alpha(0.5f)
                    )
                }
            }

            items(logs, key = { it.id }) { entry ->
                LogLine(entry)
            }

            // Cursor
            item {
                BlinkingCursor()
            }
        }
    }
}

@Composable
private fun LogLine(entry: LogEntry) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Timestamp
        Text(
            text = formatLogTime(entry.timestamp),
            fontSize = 10.sp,
            fontFamily = MonoFont,
            color = ForgeColors.textMuted
        )

        // Level badge
        Text(
            text = "[${entry.level.prefix}]",
            fontSize = 10.sp,
            fontFamily = MonoFont,
            fontWeight = FontWeight.Medium,
            color = Color(entry.level.colorHex)
        )

        // Message
        Column {
            Text(
                text = entry.message,
                fontSize = 11.sp,
                fontFamily = MonoFont,
                color = ForgeColors.textPrimary
            )
            entry.details?.let { details ->
                Text(
                    text = details,
                    fontSize = 10.sp,
                    fontFamily = MonoFont,
                    color = ForgeColors.textSecondary
                )
            }
        }
    }
}

@Composable
private fun BlinkingCursor() {
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
        text = "_",
        fontSize = 12.sp,
        fontFamily = MonoFont,
        fontWeight = FontWeight.Bold,
        color = ForgeColors.accentGreen,
        modifier = Modifier.alpha(alpha)
    )
}

@Composable
private fun ErrorBar(
    message: String,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(ForgeColors.accentRed.copy(alpha = 0.2f))
            .border(1.dp, ForgeColors.accentRed, RoundedCornerShape(8.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = message,
            fontSize = 12.sp,
            fontFamily = MonoFont,
            color = ForgeColors.accentRed,
            modifier = Modifier.weight(1f)
        )

        TextButton(onClick = onDismiss) {
            Text(
                text = "DISMISS",
                fontSize = 11.sp,
                fontFamily = MonoFont,
                fontWeight = FontWeight.Medium,
                color = ForgeColors.accentRed
            )
        }
    }
}

// Utility functions
private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "${"%.2f".format(bytes / (1024.0 * 1024.0))} MB"
    }
}

private fun formatDuration(ms: Long): String {
    val seconds = ms / 1000
    val minutes = seconds / 60
    val remainingSeconds = seconds % 60
    return if (minutes > 0) {
        "${minutes}m ${remainingSeconds}s"
    } else {
        "${seconds}s"
    }
}

private fun formatLogTime(instant: Instant): String {
    val local = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    return "%02d:%02d:%02d".format(local.hour, local.minute, local.second)
}
