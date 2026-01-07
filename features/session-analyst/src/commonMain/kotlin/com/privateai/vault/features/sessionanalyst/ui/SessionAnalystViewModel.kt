package com.privateai.vault.features.sessionanalyst.ui

import com.privateai.vault.features.sessionanalyst.data.SessionRepository
import com.privateai.vault.features.sessionanalyst.domain.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * UI LAYER: ViewModel for Session Analyst screen.
 *
 * Manages UI state and coordinates between UI and domain layers.
 */
class SessionAnalystViewModel(
    private val repository: SessionRepository,
    private val useCase: SessionAnalystUseCase,
    private val scope: CoroutineScope
) {
    private val _uiState = MutableStateFlow(SessionAnalystUiState())
    val uiState: StateFlow<SessionAnalystUiState> = _uiState.asStateFlow()

    init {
        loadSessions()
    }

    fun loadSessions() {
        scope.launch {
            repository.getSessions().fold(
                onSuccess = { sessions ->
                    _uiState.update { it.copy(sessions = sessions) }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(error = "Failed to load sessions: ${error.message}")
                    }
                }
            )
        }
    }

    fun selectSession(session: TrainingSession) {
        _uiState.update {
            it.copy(
                selectedSession = session,
                currentAnalysis = "",
                error = null
            )
        }
    }

    fun createNewSession() {
        scope.launch {
            val newSession = TrainingSession(
                id = UUID.randomUUID().toString(),
                fighterName = "New Fighter", // In real app, show dialog to input
                date = getCurrentDate(),
                notes = ""
            )

            repository.createSession(newSession).fold(
                onSuccess = {
                    loadSessions()
                    selectSession(newSession)
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(error = "Failed to create session: ${error.message}")
                    }
                }
            )
        }
    }

    fun analyzeSession(question: String) {
        val session = _uiState.value.selectedSession ?: return

        _uiState.update {
            it.copy(
                isAnalyzing = true,
                currentAnalysis = "",
                error = null
            )
        }

        scope.launch {
            val query = CoachQuery(
                question = question,
                sessionId = session.id
            )

            try {
                useCase.analyzeSession(query).collect { token ->
                    _uiState.update { state ->
                        state.copy(
                            currentAnalysis = state.currentAnalysis + token
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(error = "Analysis failed: ${e.message}")
                }
            } finally {
                _uiState.update {
                    it.copy(isAnalyzing = false)
                }
            }
        }
    }

    fun addSessionNote(content: String, sourceType: String) {
        val session = _uiState.value.selectedSession ?: return

        scope.launch {
            val note = SessionNote(
                id = UUID.randomUUID().toString(),
                sessionId = session.id,
                content = content,
                sourceType = sourceType
            )

            repository.addSessionNote(note).fold(
                onSuccess = {
                    // Note added successfully
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(error = "Failed to add note: ${error.message}")
                    }
                }
            )
        }
    }

    private fun getCurrentDate(): String {
        // Simple date formatting - use kotlinx-datetime in production
        return "2025-01-07" // Placeholder
    }
}

/**
 * UI state for Session Analyst screen.
 */
data class SessionAnalystUiState(
    val sessions: List<TrainingSession> = emptyList(),
    val selectedSession: TrainingSession? = null,
    val currentAnalysis: String = "",
    val isAnalyzing: Boolean = false,
    val error: String? = null
)
