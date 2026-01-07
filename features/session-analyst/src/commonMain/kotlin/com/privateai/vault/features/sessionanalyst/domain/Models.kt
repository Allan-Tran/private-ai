package com.privateai.vault.features.sessionanalyst.domain

import kotlinx.serialization.Serializable

/**
 * DOMAIN LAYER: Business entities for Session Analyst feature.
 */

/**
 * Represents a training session for a fighter.
 */
@Serializable
data class TrainingSession(
    val id: String,
    val fighterName: String,
    val date: String,
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * A note or observation from a training session.
 * Can come from video transcripts, coach notes, performance metrics, etc.
 */
@Serializable
data class SessionNote(
    val id: String,
    val sessionId: String,
    val content: String,
    val sourceType: String, // "video_transcript", "coach_notes", "metrics", etc.
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * An AI-generated analysis of a training session.
 */
@Serializable
data class SessionAnalysis(
    val sessionId: String,
    val query: String,
    val analysis: String,
    val relevantContext: List<String> = emptyList(),
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Represents a question the coach asks about the session.
 */
@Serializable
data class CoachQuery(
    val question: String,
    val sessionId: String,
    val expectationHint: String? = null // Optional hint about what kind of answer is expected
)
