package com.privateai.vault.features.sessionanalyst.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.privateai.vault.features.sessionanalyst.domain.CoachQuery
import com.privateai.vault.features.sessionanalyst.domain.TrainingSession

/**
 * UI LAYER: Main screen for Session Analyst feature.
 *
 * This demonstrates the "Active Desk" concept where coaches can
 * interact with their training session data through AI.
 */
@Composable
fun SessionAnalystScreen(
    viewModel: SessionAnalystViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Header
        Text(
            text = "🥊 Session Analyst",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Text(
            text = "Analyze training sessions with private AI",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Session selection
        SessionSelector(
            sessions = uiState.sessions,
            selectedSession = uiState.selectedSession,
            onSessionSelected = { viewModel.selectSession(it) },
            onNewSession = { viewModel.createNewSession() }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Active Desk area
        if (uiState.selectedSession != null) {
            ActiveDeskArea(
                session = uiState.selectedSession!!,
                analysis = uiState.currentAnalysis,
                isAnalyzing = uiState.isAnalyzing,
                onQuerySubmit = { question ->
                    viewModel.analyzeSession(question)
                },
                onAddNote = { content, sourceType ->
                    viewModel.addSessionNote(content, sourceType)
                }
            )
        } else {
            EmptyStateMessage()
        }
    }
}

@Composable
private fun SessionSelector(
    sessions: List<TrainingSession>,
    selectedSession: TrainingSession?,
    onSessionSelected: (TrainingSession) -> Unit,
    onNewSession: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Training Sessions",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )

                Button(onClick = onNewSession) {
                    Text("+ New Session")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (sessions.isEmpty()) {
                Text(
                    text = "No sessions yet. Create your first session!",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 200.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(sessions) { session ->
                        SessionCard(
                            session = session,
                            isSelected = session.id == selectedSession?.id,
                            onClick = { onSessionSelected(session) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionCard(
    session: TrainingSession,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Text(
                text = session.fighterName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = session.date,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ActiveDeskArea(
    session: TrainingSession,
    analysis: String,
    isAnalyzing: Boolean,
    onQuerySubmit: (String) -> Unit,
    onAddNote: (String, String) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // Session header
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "Active Session: ${session.fighterName}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = session.date,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Analysis output
        Card(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                item {
                    if (analysis.isEmpty() && !isAnalyzing) {
                        Text(
                            text = "Ask a question about this training session...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            text = analysis,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    if (isAnalyzing) {
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Query input
        QueryInput(
            enabled = !isAnalyzing,
            onSubmit = onQuerySubmit
        )
    }
}

@Composable
private fun QueryInput(
    enabled: Boolean,
    onSubmit: (String) -> Unit
) {
    var queryText by remember { mutableStateOf("") }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = queryText,
            onValueChange = { queryText = it },
            modifier = Modifier.weight(1f),
            placeholder = { Text("Ask about technique, performance, improvements...") },
            enabled = enabled,
            maxLines = 3
        )

        Button(
            onClick = {
                if (queryText.isNotBlank()) {
                    onSubmit(queryText)
                    queryText = ""
                }
            },
            enabled = enabled && queryText.isNotBlank()
        ) {
            Text("Analyze")
        }
    }
}

@Composable
private fun EmptyStateMessage() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "📋",
                style = MaterialTheme.typography.displayLarge
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Select or create a training session to begin",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
