package com.privateai.vault.vectorstore

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Epic 3.3 - Data Cleaning Tool
 *
 * "As a developer, I want to build an internal script that converts raw text
 * into structured training pairs, so that I can 'bake' a new version of the
 * model for a client in under an hour."
 *
 * This module provides utilities for:
 * - Converting raw documents to training data
 * - Generating question-answer pairs
 * - Cleaning and normalizing text
 * - Exporting in various formats (JSONL, CSV, etc.)
 */

/**
 * A training pair for fine-tuning language models.
 */
@Serializable
data class TrainingPair(
    val prompt: String,
    val completion: String,
    val metadata: Map<String, String> = emptyMap()
)

/**
 * Configuration for training data generation.
 */
data class TrainingDataConfig(
    val generateQuestions: Boolean = true,
    val questionsPerChunk: Int = 2,
    val includeContext: Boolean = true,
    val maxPairLength: Int = 2048,
    val includeMetadata: Boolean = true
)

/**
 * Format for exporting training data.
 */
enum class TrainingDataFormat {
    JSONL,      // JSON Lines format (OpenAI fine-tuning)
    CSV,        // CSV format
    ALPACA,     // Alpaca instruction format
    SHAREGPT    // ShareGPT conversation format
}

/**
 * Data cleaning and preparation service.
 */
interface DataCleaner {
    /**
     * Clean and normalize text.
     *
     * Removes:
     * - Excessive whitespace
     * - Control characters
     * - Invalid Unicode
     * - HTML/XML tags (optional)
     */
    fun cleanText(text: String, removeHtml: Boolean = true): String

    /**
     * Generate training pairs from document chunks.
     *
     * @param chunks Document chunks to convert
     * @param config Generation configuration
     * @return List of training pairs
     */
    suspend fun generateTrainingPairs(
        chunks: List<DocumentChunk>,
        config: TrainingDataConfig = TrainingDataConfig()
    ): List<TrainingPair>

    /**
     * Export training pairs to specified format.
     *
     * @param pairs Training pairs
     * @param format Export format
     * @return Formatted string ready for file export
     */
    fun exportTrainingData(
        pairs: List<TrainingPair>,
        format: TrainingDataFormat = TrainingDataFormat.JSONL
    ): String

    /**
     * Generate synthetic questions from content.
     *
     * Uses simple heuristics to create questions that the content answers.
     */
    fun generateQuestions(content: String, count: Int = 2): List<String>
}

/**
 * Implementation of data cleaning service.
 */
class SimpleDataCleaner : DataCleaner {

    private val json = Json { prettyPrint = false }

    override fun cleanText(text: String, removeHtml: Boolean): String {
        var cleaned = text

        // Remove HTML tags if requested
        if (removeHtml) {
            cleaned = cleaned.replace(Regex("<[^>]+>"), " ")
        }

        // Remove control characters (except newlines and tabs)
        cleaned = cleaned.replace(Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]"), "")

        // Normalize whitespace
        cleaned = cleaned.replace(Regex("\\s+"), " ")

        // Remove excessive newlines
        cleaned = cleaned.replace(Regex("\\n{3,}"), "\n\n")

        // Trim
        cleaned = cleaned.trim()

        return cleaned
    }

    override suspend fun generateTrainingPairs(
        chunks: List<DocumentChunk>,
        config: TrainingDataConfig
    ): List<TrainingPair> {
        val pairs = mutableListOf<TrainingPair>()

        for (chunk in chunks) {
            val cleanedContent = cleanText(chunk.content)

            if (cleanedContent.length < 50) {
                // Skip very short chunks
                continue
            }

            if (config.generateQuestions) {
                // Generate question-answer pairs
                val questions = generateQuestions(
                    cleanedContent,
                    config.questionsPerChunk
                )

                for (question in questions) {
                    val prompt = if (config.includeContext) {
                        "Context: $cleanedContent\n\nQuestion: $question"
                    } else {
                        question
                    }

                    val completion = cleanedContent

                    val metadata = if (config.includeMetadata) {
                        mapOf(
                            "document_id" to chunk.documentId,
                            "chunk_index" to chunk.chunkIndex.toString(),
                            "token_count" to chunk.tokenCount.toString()
                        )
                    } else {
                        emptyMap()
                    }

                    pairs.add(TrainingPair(prompt, completion, metadata))
                }
            } else {
                // Simple prompt-completion pair
                val prompt = "Summarize the following text:"
                val completion = cleanedContent

                pairs.add(TrainingPair(prompt, completion))
            }
        }

        println("[DataCleaner] 🧹 Generated ${pairs.size} training pairs from ${chunks.size} chunks")
        return pairs
    }

    override fun exportTrainingData(
        pairs: List<TrainingPair>,
        format: TrainingDataFormat
    ): String {
        return when (format) {
            TrainingDataFormat.JSONL -> exportToJsonl(pairs)
            TrainingDataFormat.CSV -> exportToCsv(pairs)
            TrainingDataFormat.ALPACA -> exportToAlpaca(pairs)
            TrainingDataFormat.SHAREGPT -> exportToShareGPT(pairs)
        }
    }

    override fun generateQuestions(content: String, count: Int): List<String> {
        // Simple heuristic-based question generation
        // In production, you'd use a question generation model

        val questions = mutableListOf<String>()

        // Extract potential topics from first sentence
        val firstSentence = content.split(".").firstOrNull() ?: content.take(100)

        // Generate different question types
        val templates = listOf(
            "What does this text explain about",
            "According to the text, what is",
            "How does the text describe",
            "What information is provided about"
        )

        // Extract key nouns/topics (simplified)
        val words = firstSentence.split(" ")
            .filter { it.length > 4 }
            .filter { it[0].isUpperCase() || it.all { c -> c.isLowerCase() } }
            .take(3)

        for (i in 0 until count.coerceAtMost(templates.size)) {
            val template = templates[i % templates.size]
            val topic = words.getOrNull(i % words.size) ?: "this topic"
            questions.add("$template $topic?")
        }

        return questions.take(count)
    }

    private fun exportToJsonl(pairs: List<TrainingPair>): String {
        return pairs.joinToString("\n") { pair ->
            json.encodeToString(
                mapOf(
                    "prompt" to pair.prompt,
                    "completion" to pair.completion
                )
            )
        }
    }

    private fun exportToCsv(pairs: List<TrainingPair>): String {
        val builder = StringBuilder()
        builder.append("prompt,completion\n")

        for (pair in pairs) {
            val escapedPrompt = pair.prompt.replace("\"", "\"\"")
            val escapedCompletion = pair.completion.replace("\"", "\"\"")
            builder.append("\"$escapedPrompt\",\"$escapedCompletion\"\n")
        }

        return builder.toString()
    }

    private fun exportToAlpaca(pairs: List<TrainingPair>): String {
        val alpacaDataset = pairs.map { pair ->
            mapOf(
                "instruction" to pair.prompt,
                "input" to "",
                "output" to pair.completion
            )
        }

        return json.encodeToString(alpacaDataset)
    }

    private fun exportToShareGPT(pairs: List<TrainingPair>): String {
        val conversations = pairs.map { pair ->
            mapOf(
                "conversations" to listOf(
                    mapOf("from" to "human", "value" to pair.prompt),
                    mapOf("from" to "gpt", "value" to pair.completion)
                )
            )
        }

        return json.encodeToString(conversations)
    }
}

/**
 * Batch data processing utility.
 *
 * For processing large document collections efficiently.
 */
class BatchDataProcessor(
    private val vectorStore: VectorStore,
    private val dataCleaner: DataCleaner
) {
    /**
     * Process all documents in the vector store and generate training data.
     *
     * Story 3.3: "Bake a new version of the model for a client in under an hour."
     *
     * @param config Training data configuration
     * @param format Export format
     * @return Exported training data string
     */
    suspend fun processAllDocuments(
        config: TrainingDataConfig = TrainingDataConfig(),
        format: TrainingDataFormat = TrainingDataFormat.JSONL
    ): String {
        val startTime = System.currentTimeMillis()

        println("[BatchProcessor] 🔄 Starting batch processing...")

        // Get all documents (would need to implement in VectorStore)
        // For now, return placeholder
        val allPairs = listOf<TrainingPair>()

        val exported = dataCleaner.exportTrainingData(allPairs, format)
        val duration = System.currentTimeMillis() - startTime

        println("[BatchProcessor] ✅ Batch processing complete in ${duration}ms")
        println("[BatchProcessor]    Generated ${allPairs.size} training pairs")

        return exported
    }

    /**
     * Process specific documents by ID.
     */
    suspend fun processDocuments(
        documentIds: List<String>,
        config: TrainingDataConfig = TrainingDataConfig(),
        format: TrainingDataFormat = TrainingDataFormat.JSONL
    ): String {
        // Would fetch documents and process them
        return ""
    }
}
