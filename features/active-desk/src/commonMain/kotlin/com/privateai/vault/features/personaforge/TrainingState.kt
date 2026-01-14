package com.privateai.vault.features.personaforge

import com.privateai.vault.vectorstore.Persona
import com.privateai.vault.vectorstore.TrainingExample
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * Persona Forge - Stage 1: Training State Management
 *
 * "The Self-Labeling Factory" - Allows users to create custom AI "Souls"
 * by uploading documents and generating synthetic training data.
 */

/**
 * Main state for the Persona Forge / Training screen.
 */
data class TrainingState(
    val phase: TrainingPhase = TrainingPhase.Idle,
    val selectedPersona: PersonaOption = PersonaOption.PROFESSIONAL,
    val sourceFile: SourceFile? = null,
    val generatedExamples: List<TrainingExample> = emptyList(),
    val logs: List<LogEntry> = emptyList(),
    val progress: TrainingProgress = TrainingProgress(),
    val error: String? = null,
    val outputPath: String? = null
)

/**
 * The current phase of the training pipeline.
 */
sealed class TrainingPhase {
    /** Initial state - waiting for user to drop a file */
    data object Idle : TrainingPhase()

    /** Reading and parsing the dropped file */
    data class ReadingFile(val fileName: String) : TrainingPhase()

    /** Generating synthetic Q&A pairs using the DataExpander */
    data class GeneratingSyntheticData(
        val currentQuestion: Int,
        val totalQuestions: Int,
        val currentType: String
    ) : TrainingPhase()

    /** Exporting training data to JSONL */
    data class ExportingData(val format: String) : TrainingPhase()

    /** Simulating LoRA training (will connect to Python trainer later) */
    data class Training(
        val epoch: Int,
        val totalEpochs: Int,
        val loss: Float
    ) : TrainingPhase()

    /** Training complete - Soul is ready */
    data class Complete(
        val soulName: String,
        val examplesGenerated: Int,
        val trainingTimeMs: Long
    ) : TrainingPhase()

    /** Error state */
    data class Error(val message: String) : TrainingPhase()
}

/**
 * Persona options for training data generation style.
 */
enum class PersonaOption(
    val displayName: String,
    val description: String,
    val icon: String,
    val persona: Persona
) {
    PROFESSIONAL(
        displayName = "Professional Expert",
        description = "Clear, authoritative, precise responses",
        icon = "briefcase",
        persona = Persona.PROFESSIONAL
    ),
    FRIENDLY(
        displayName = "Friendly Assistant",
        description = "Warm, helpful, conversational tone",
        icon = "smile",
        persona = Persona.FRIENDLY_ASSISTANT
    ),
    STRICT(
        displayName = "Strict Instructor",
        description = "Direct, educational, no-nonsense",
        icon = "school",
        persona = Persona.STRICT_INSTRUCTOR
    ),
    DOMAIN_EXPERT(
        displayName = "Domain Expert",
        description = "Deep technical knowledge, industry terms",
        icon = "brain",
        persona = Persona.DOMAIN_EXPERT
    ),
    CUSTOM(
        displayName = "Custom Persona",
        description = "Define your own personality style",
        icon = "edit",
        persona = Persona.PROFESSIONAL // Will be replaced with custom
    );

    companion object {
        fun fromPersona(persona: Persona): PersonaOption {
            return entries.find { it.persona.name == persona.name } ?: PROFESSIONAL
        }
    }
}

/**
 * Source file information.
 */
data class SourceFile(
    val path: String,
    val name: String,
    val size: Long,
    val type: SourceFileType,
    val content: String? = null,
    val chunkCount: Int = 0
)

/**
 * Supported source file types.
 */
enum class SourceFileType(val extensions: List<String>) {
    TEXT(listOf("txt")),
    MARKDOWN(listOf("md", "markdown")),
    PDF(listOf("pdf")),
    UNKNOWN(emptyList());

    companion object {
        fun fromExtension(ext: String): SourceFileType {
            val lower = ext.lowercase()
            return entries.find { lower in it.extensions } ?: UNKNOWN
        }
    }
}

/**
 * Progress tracking for the training pipeline.
 */
data class TrainingProgress(
    val questionsGenerated: Int = 0,
    val questionsTotal: Int = 0,
    val tokensGenerated: Int = 0,
    val chunksProcessed: Int = 0,
    val chunksTotal: Int = 0,
    val startTime: Instant? = null,
    val elapsedMs: Long = 0
) {
    val percentComplete: Float
        get() = if (questionsTotal > 0) questionsGenerated.toFloat() / questionsTotal else 0f

    val questionsPerSecond: Float
        get() = if (elapsedMs > 0) questionsGenerated * 1000f / elapsedMs else 0f
}

/**
 * Log entry for the terminal display.
 */
data class LogEntry(
    val id: String = generateLogId(),
    val timestamp: Instant = Clock.System.now(),
    val level: LogLevel,
    val message: String,
    val details: String? = null
) {
    companion object {
        private var counter = 0L
        private fun generateLogId() = "log_${++counter}"

        fun info(message: String, details: String? = null) = LogEntry(
            level = LogLevel.INFO,
            message = message,
            details = details
        )

        fun success(message: String, details: String? = null) = LogEntry(
            level = LogLevel.SUCCESS,
            message = message,
            details = details
        )

        fun warning(message: String, details: String? = null) = LogEntry(
            level = LogLevel.WARNING,
            message = message,
            details = details
        )

        fun error(message: String, details: String? = null) = LogEntry(
            level = LogLevel.ERROR,
            message = message,
            details = details
        )

        fun debug(message: String, details: String? = null) = LogEntry(
            level = LogLevel.DEBUG,
            message = message,
            details = details
        )

        fun generation(questionNum: Int, questionType: String, preview: String) = LogEntry(
            level = LogLevel.GENERATION,
            message = "Q$questionNum [$questionType]",
            details = preview.take(80) + if (preview.length > 80) "..." else ""
        )
    }
}

/**
 * Log severity levels with associated colors.
 */
enum class LogLevel(val prefix: String, val colorHex: Long) {
    INFO("INFO", 0xFF8B8B8B),
    SUCCESS("OK", 0xFF4ADE80),
    WARNING("WARN", 0xFFFBBF24),
    ERROR("ERR", 0xFFEF4444),
    DEBUG("DBG", 0xFF6B7280),
    GENERATION("GEN", 0xFF60A5FA)
}

/**
 * UI events from the Training screen.
 */
sealed class TrainingEvent {
    /** User dropped a file */
    data class FileDropped(val path: String) : TrainingEvent()

    /** User selected a file via picker */
    data class FileSelected(val path: String) : TrainingEvent()

    /** User changed the target persona */
    data class PersonaChanged(val persona: PersonaOption) : TrainingEvent()

    /** User set custom persona text */
    data class CustomPersonaSet(val name: String, val style: String) : TrainingEvent()

    /** Start the "baking" process */
    data object StartBaking : TrainingEvent()

    /** Cancel current operation */
    data object Cancel : TrainingEvent()

    /** Reset to initial state */
    data object Reset : TrainingEvent()

    /** Clear error */
    data object ClearError : TrainingEvent()

    /** Export generated data */
    data class ExportData(val format: String) : TrainingEvent()

    /** Open output folder */
    data object OpenOutputFolder : TrainingEvent()
}

/**
 * Side effects emitted by the ViewModel.
 */
sealed class TrainingEffect {
    data class ShowToast(val message: String, val isError: Boolean = false) : TrainingEffect()
    data class OpenFolder(val path: String) : TrainingEffect()
    data class FileSaved(val path: String) : TrainingEffect()
    data object ScrollToBottom : TrainingEffect()
    data class PlaySound(val sound: SoundType) : TrainingEffect()
}

/**
 * Sound effects for feedback.
 */
enum class SoundType {
    START,
    COMPLETE,
    ERROR,
    TICK
}
