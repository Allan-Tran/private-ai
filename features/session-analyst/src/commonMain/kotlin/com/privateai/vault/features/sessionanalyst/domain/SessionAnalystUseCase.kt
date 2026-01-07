package com.privateai.vault.features.sessionanalyst.domain

import com.privateai.vault.features.sessionanalyst.data.SessionRepository
import com.privateai.vault.inference.InferenceEngine
import com.privateai.vault.inference.GenerationParams
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * DOMAIN LAYER: Use case for analyzing training sessions.
 *
 * This is the business logic layer that orchestrates RAG and inference.
 */
class SessionAnalystUseCase(
    private val repository: SessionRepository,
    private val inferenceEngine: InferenceEngine
) {
    /**
     * Analyze a training session based on a coach's query.
     * Uses RAG to retrieve relevant context, then generates insight.
     *
     * Returns a streaming response for real-time feedback.
     */
    fun analyzeSession(query: CoachQuery): Flow<String> = flow {
        if (!inferenceEngine.isModelLoaded()) {
            emit("[Error: No AI model loaded. Please load a model first.]")
            return@flow
        }

        // Step 1: Retrieve relevant context using RAG
        emit("🔍 Searching session notes for relevant context...\n\n")

        val contextResult = repository.searchRelevantContext(
            query = query.question,
            sessionId = query.sessionId,
            limit = 5
        )

        if (contextResult.isFailure) {
            emit("[Error retrieving context: ${contextResult.exceptionOrNull()?.message}]")
            return@flow
        }

        val relevantContext = contextResult.getOrDefault(emptyList())

        if (relevantContext.isEmpty()) {
            emit("⚠️ No relevant context found in session notes.\n\n")
        } else {
            emit("✓ Found ${relevantContext.size} relevant excerpts.\n\n")
        }

        // Step 2: Build prompt with retrieved context
        val prompt = buildAnalysisPrompt(query, relevantContext)

        emit("💭 Generating analysis...\n\n")
        emit("---\n\n")

        // Step 3: Stream AI response
        inferenceEngine.generateStream(
            prompt = prompt,
            params = GenerationParams(
                maxTokens = 1024,
                temperature = 0.7f,
                stopSequences = listOf("</analysis>", "---END---")
            )
        ).collect { token ->
            emit(token)
        }
    }

    /**
     * Get a summary of all analyses for a session.
     */
    suspend fun getSessionSummary(sessionId: String): Result<String> {
        val notesResult = repository.getSessionNotes(sessionId)

        if (notesResult.isFailure) {
            return Result.failure(notesResult.exceptionOrNull()!!)
        }

        val notes = notesResult.getOrDefault(emptyList())

        if (notes.isEmpty()) {
            return Result.success("No notes available for this session.")
        }

        val summary = buildString {
            appendLine("Session Summary:")
            appendLine("================")
            appendLine()
            appendLine("Total notes: ${notes.size}")
            appendLine()

            notes.groupBy { it.sourceType }.forEach { (type, typeNotes) ->
                appendLine("- $type: ${typeNotes.size} entries")
            }
        }

        return Result.success(summary)
    }

    /**
     * Build the prompt for session analysis using RAG.
     */
    private fun buildAnalysisPrompt(query: CoachQuery, context: List<String>): String {
        return buildString {
            appendLine("You are an expert boxing coach assistant. Analyze the training session based on the following context and answer the coach's question.")
            appendLine()
            appendLine("## Session Context:")
            appendLine()

            if (context.isNotEmpty()) {
                context.forEachIndexed { index, excerpt ->
                    appendLine("### Excerpt ${index + 1}:")
                    appendLine(excerpt)
                    appendLine()
                }
            } else {
                appendLine("(No specific context found - provide general guidance)")
                appendLine()
            }

            appendLine("## Coach's Question:")
            appendLine(query.question)
            appendLine()

            query.expectationHint?.let {
                appendLine("## Expected Focus:")
                appendLine(it)
                appendLine()
            }

            appendLine("## Analysis:")
            appendLine("Provide a detailed, actionable analysis focused on the coach's question. Reference specific details from the session context when possible.")
            appendLine()
        }
    }
}
