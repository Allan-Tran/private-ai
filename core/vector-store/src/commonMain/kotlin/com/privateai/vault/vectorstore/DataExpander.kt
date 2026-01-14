package com.privateai.vault.vectorstore

import com.privateai.vault.inference.InferenceEngine
import com.privateai.vault.inference.GenerationParams
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Epic 3 - The Self-Labeling Factory
 *
 * DataExpander transforms raw documents into synthetic training data using
 * the local LLM as a "Teacher." This is the core technology that makes
 * PrivateAI an "AI Company Killer" - users can "bake" their own intelligence
 * from their private documents without any cloud dependency.
 *
 * Process:
 * 1. Takes raw text (document chunk)
 * 2. Uses the Base Model to generate diverse questions the text answers
 * 3. Uses the Base Model to generate ideal persona-styled responses
 * 4. Outputs structured training examples for LoRA fine-tuning
 *
 * This enables a user to upload a single document (e.g., "Warehouse Rules")
 * and automatically generate 50-100 instruction-response pairs for training.
 */

/**
 * A single training example in instruction-tuning format.
 * Compatible with Alpaca, ShareGPT, and JSONL formats.
 */
@Serializable
data class TrainingExample(
    val instruction: String,
    val input: String = "",
    val output: String,
    val metadata: TrainingMetadata? = null
)

/**
 * Metadata about the training example's origin.
 */
@Serializable
data class TrainingMetadata(
    val sourceChunkId: String? = null,
    val personaName: String? = null,
    val generationTimestamp: Long = System.currentTimeMillis(),
    val questionType: String? = null
)

/**
 * Persona definition for styled response generation.
 * This allows training data to be generated in specific voices/styles.
 */
@Serializable
data class Persona(
    val name: String,
    val description: String,
    val stylePrompt: String,
    val exampleResponse: String? = null
) {
    companion object {
        val PROFESSIONAL = Persona(
            name = "Professional Expert",
            description = "Clear, authoritative, and precise",
            stylePrompt = "Respond as a professional expert. Be clear, precise, and authoritative. Use formal language and provide thorough explanations.",
            exampleResponse = "Based on the established guidelines, the correct procedure is..."
        )

        val FRIENDLY_ASSISTANT = Persona(
            name = "Friendly Assistant",
            description = "Warm, helpful, and approachable",
            stylePrompt = "Respond as a friendly assistant. Be warm, helpful, and conversational. Make complex topics easy to understand.",
            exampleResponse = "Great question! Let me break this down for you..."
        )

        val STRICT_INSTRUCTOR = Persona(
            name = "Strict Instructor",
            description = "Direct, no-nonsense, educational",
            stylePrompt = "Respond as a strict instructor. Be direct and educational. Focus on accuracy and proper procedure. Correct misconceptions firmly.",
            exampleResponse = "The correct approach is as follows. Pay close attention..."
        )

        val DOMAIN_EXPERT = Persona(
            name = "Domain Expert",
            description = "Deep technical knowledge, industry terms",
            stylePrompt = "Respond as a domain expert with deep technical knowledge. Use appropriate industry terminology. Provide comprehensive, nuanced answers.",
            exampleResponse = "From a technical standpoint, this involves..."
        )

        /**
         * Create a custom persona for specialized training.
         */
        fun custom(name: String, styleDescription: String): Persona = Persona(
            name = name,
            description = styleDescription,
            stylePrompt = "Respond as $name. $styleDescription"
        )
    }
}

/**
 * Configuration for the DataExpander.
 */
data class ExpansionConfig(
    val questionsPerChunk: Int = 5,
    val minQuestionLength: Int = 10,
    val maxQuestionLength: Int = 200,
    val minAnswerLength: Int = 50,
    val maxAnswerLength: Int = 1000,
    val temperature: Float = 0.7f,
    val includeMetadata: Boolean = true,
    val questionTypes: List<QuestionType> = QuestionType.entries,
    val retryOnParseFailure: Boolean = true,
    val maxRetries: Int = 2
)

/**
 * Types of questions to generate for diverse training data.
 */
enum class QuestionType(val prompt: String) {
    FACTUAL("Ask a direct factual question that this text answers."),
    PROCEDURAL("Ask a 'how to' question about a process described in the text."),
    EXPLANATORY("Ask a 'why' or 'explain' question about concepts in the text."),
    COMPARATIVE("Ask a question comparing or contrasting elements mentioned."),
    SCENARIO("Ask a practical scenario-based question applying the information.")
}

/**
 * Result of expansion including statistics.
 */
data class ExpansionResult(
    val examples: List<TrainingExample>,
    val stats: ExpansionStats
)

/**
 * Statistics about the expansion process.
 */
data class ExpansionStats(
    val totalGenerated: Int,
    val successfulParsed: Int,
    val failedParsed: Int,
    val durationMs: Long,
    val tokensGenerated: Int
)

/**
 * The Self-Labeling Factory - generates synthetic training data from documents.
 *
 * Uses the local LLM as a "Teacher" to:
 * 1. Generate diverse questions from source text
 * 2. Generate persona-styled answers
 * 3. Output structured training examples
 *
 * @param engine The InferenceEngine for local LLM inference
 */
class DataExpander(
    private val engine: InferenceEngine
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = false
    }

    /**
     * Expand a text chunk into multiple training examples.
     *
     * @param sourceText The raw text to generate training data from
     * @param persona The persona/style for response generation
     * @param config Expansion configuration
     * @param chunkId Optional chunk ID for metadata
     * @return List of training examples
     */
    suspend fun expand(
        sourceText: String,
        persona: Persona = Persona.PROFESSIONAL,
        config: ExpansionConfig = ExpansionConfig(),
        chunkId: String? = null
    ): ExpansionResult {
        val startTime = System.currentTimeMillis()
        val examples = mutableListOf<TrainingExample>()
        var tokensGenerated = 0
        var failedParses = 0

        println("[DataExpander] 🧠 Expanding text (${sourceText.length} chars) with persona: ${persona.name}")

        // Clean the source text
        val cleanedText = cleanSourceText(sourceText)
        if (cleanedText.length < 50) {
            println("[DataExpander] ⚠️  Text too short for expansion")
            return ExpansionResult(
                examples = emptyList(),
                stats = ExpansionStats(0, 0, 0, 0, 0)
            )
        }

        // Generate questions of different types for diversity
        val questionTypes = config.questionTypes.take(config.questionsPerChunk)

        for (questionType in questionTypes) {
            try {
                // Step 1: Generate a question
                val question = generateQuestion(cleanedText, questionType, config)
                if (question == null) {
                    failedParses++
                    continue
                }
                tokensGenerated += estimateTokens(question)

                // Step 2: Generate the persona-styled answer
                val answer = generateAnswer(cleanedText, question, persona, config)
                if (answer == null) {
                    failedParses++
                    continue
                }
                tokensGenerated += estimateTokens(answer)

                // Step 3: Create the training example
                val example = TrainingExample(
                    instruction = question,
                    input = "", // Could include context if needed
                    output = answer,
                    metadata = if (config.includeMetadata) {
                        TrainingMetadata(
                            sourceChunkId = chunkId,
                            personaName = persona.name,
                            questionType = questionType.name
                        )
                    } else null
                )

                examples.add(example)
                println("[DataExpander]    ✅ Generated ${questionType.name} example")

            } catch (e: Exception) {
                println("[DataExpander]    ❌ Failed to generate ${questionType.name}: ${e.message}")
                failedParses++
            }
        }

        val duration = System.currentTimeMillis() - startTime
        println("[DataExpander] 📊 Expansion complete: ${examples.size} examples in ${duration}ms")

        return ExpansionResult(
            examples = examples,
            stats = ExpansionStats(
                totalGenerated = questionTypes.size,
                successfulParsed = examples.size,
                failedParsed = failedParses,
                durationMs = duration,
                tokensGenerated = tokensGenerated
            )
        )
    }

    /**
     * Batch expand multiple text chunks.
     */
    suspend fun expandBatch(
        chunks: List<Pair<String, String?>>, // text to chunkId
        persona: Persona = Persona.PROFESSIONAL,
        config: ExpansionConfig = ExpansionConfig()
    ): ExpansionResult {
        val allExamples = mutableListOf<TrainingExample>()
        var totalStats = ExpansionStats(0, 0, 0, 0, 0)

        for ((index, chunk) in chunks.withIndex()) {
            println("[DataExpander] 📄 Processing chunk ${index + 1}/${chunks.size}")

            val result = expand(chunk.first, persona, config, chunk.second)
            allExamples.addAll(result.examples)

            totalStats = ExpansionStats(
                totalGenerated = totalStats.totalGenerated + result.stats.totalGenerated,
                successfulParsed = totalStats.successfulParsed + result.stats.successfulParsed,
                failedParsed = totalStats.failedParsed + result.stats.failedParsed,
                durationMs = totalStats.durationMs + result.stats.durationMs,
                tokensGenerated = totalStats.tokensGenerated + result.stats.tokensGenerated
            )
        }

        return ExpansionResult(allExamples, totalStats)
    }

    /**
     * Generate a question of the specified type from the source text.
     */
    private suspend fun generateQuestion(
        sourceText: String,
        questionType: QuestionType,
        config: ExpansionConfig
    ): String? {
        val prompt = buildQuestionPrompt(sourceText, questionType)

        val params = GenerationParams(
            maxTokens = 100,
            temperature = config.temperature,
            topP = 0.9f,
            stopSequences = listOf("\n\n", "Question 2", "2.")
        )

        val response = generateAndCollect(prompt, params)
        return parseQuestion(response, config)
    }

    /**
     * Generate a persona-styled answer to the question.
     */
    private suspend fun generateAnswer(
        sourceText: String,
        question: String,
        persona: Persona,
        config: ExpansionConfig
    ): String? {
        val prompt = buildAnswerPrompt(sourceText, question, persona)

        val params = GenerationParams(
            maxTokens = config.maxAnswerLength,
            temperature = config.temperature * 0.8f, // Slightly lower for answers
            topP = 0.9f,
            stopSequences = listOf("\n\nQuestion:", "\n\n---", "Human:", "User:")
        )

        val response = generateAndCollect(prompt, params)
        return parseAnswer(response, config)
    }

    /**
     * Build prompt for question generation.
     */
    private fun buildQuestionPrompt(sourceText: String, questionType: QuestionType): String {
        return """You are a Teacher creating training questions. Given the following text, generate ONE clear, specific question.

TEXT:
\"\"\"
${sourceText.take(1500)}
\"\"\"

TASK: ${questionType.prompt}

Generate exactly ONE question. The question must be directly answerable using ONLY the information in the text above.

Question:"""
    }

    /**
     * Build prompt for answer generation with persona.
     */
    private fun buildAnswerPrompt(sourceText: String, question: String, persona: Persona): String {
        val examplePart = persona.exampleResponse?.let {
            "\nExample of your style: \"$it\""
        } ?: ""

        return """You are an AI assistant with a specific persona.

PERSONA: ${persona.name}
STYLE: ${persona.stylePrompt}$examplePart

REFERENCE INFORMATION:
\"\"\"
${sourceText.take(1500)}
\"\"\"

USER QUESTION: $question

Provide a helpful, accurate answer based ONLY on the reference information above. Stay in character.

ANSWER:"""
    }

    /**
     * Collect streaming generation into a single string.
     */
    private suspend fun generateAndCollect(prompt: String, params: GenerationParams): String {
        if (!engine.isModelLoaded()) {
            throw IllegalStateException("No model loaded for data expansion")
        }

        val tokens = engine.generateStream(prompt, params).toList()
        return tokens.joinToString("")
    }

    /**
     * Parse and validate a generated question.
     */
    private fun parseQuestion(response: String, config: ExpansionConfig): String? {
        // Clean up the response
        var question = response.trim()

        // Remove common prefixes
        question = question
            .removePrefix("Question:")
            .removePrefix("Q:")
            .removePrefix("1.")
            .removePrefix("-")
            .trim()

        // Remove quotes if wrapped
        if (question.startsWith("\"") && question.endsWith("\"")) {
            question = question.drop(1).dropLast(1)
        }

        // Take only the first question if multiple were generated
        question = question.split("\n").first().trim()

        // Validate
        if (question.length < config.minQuestionLength) {
            return null
        }
        if (question.length > config.maxQuestionLength) {
            question = question.take(config.maxQuestionLength)
        }

        // Ensure it ends with a question mark
        if (!question.endsWith("?")) {
            question = "$question?"
        }

        return question
    }

    /**
     * Parse and validate a generated answer.
     */
    private fun parseAnswer(response: String, config: ExpansionConfig): String? {
        // Clean up the response
        var answer = response.trim()

        // Remove common prefixes
        answer = answer
            .removePrefix("Answer:")
            .removePrefix("A:")
            .removePrefix("Response:")
            .trim()

        // Take first coherent paragraph block
        val paragraphs = answer.split("\n\n")
        if (paragraphs.size > 3) {
            answer = paragraphs.take(3).joinToString("\n\n")
        }

        // Validate
        if (answer.length < config.minAnswerLength) {
            return null
        }
        if (answer.length > config.maxAnswerLength) {
            answer = answer.take(config.maxAnswerLength)
            // Try to end at a sentence
            val lastPeriod = answer.lastIndexOf('.')
            if (lastPeriod > config.minAnswerLength) {
                answer = answer.take(lastPeriod + 1)
            }
        }

        return answer
    }

    /**
     * Clean source text for prompting.
     */
    private fun cleanSourceText(text: String): String {
        return text
            .replace(Regex("\\s+"), " ")
            .replace(Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]"), "")
            .trim()
    }

    /**
     * Estimate token count (rough approximation: 4 chars = 1 token).
     */
    private fun estimateTokens(text: String): Int = text.length / 4

    /**
     * Export training examples to various formats.
     */
    fun export(examples: List<TrainingExample>, format: TrainingDataFormat): String {
        return when (format) {
            TrainingDataFormat.JSONL -> exportToJsonl(examples)
            TrainingDataFormat.ALPACA -> exportToAlpaca(examples)
            TrainingDataFormat.SHAREGPT -> exportToShareGpt(examples)
            TrainingDataFormat.CSV -> exportToCsv(examples)
        }
    }

    private fun exportToJsonl(examples: List<TrainingExample>): String {
        return examples.joinToString("\n") { example ->
            json.encodeToString(
                mapOf(
                    "instruction" to example.instruction,
                    "input" to example.input,
                    "output" to example.output
                )
            )
        }
    }

    private fun exportToAlpaca(examples: List<TrainingExample>): String {
        val alpacaFormat = examples.map { example ->
            mapOf(
                "instruction" to example.instruction,
                "input" to example.input,
                "output" to example.output
            )
        }
        return Json { prettyPrint = true }.encodeToString(alpacaFormat)
    }

    private fun exportToShareGpt(examples: List<TrainingExample>): String {
        val shareGptFormat = examples.map { example ->
            mapOf(
                "conversations" to listOf(
                    mapOf("from" to "human", "value" to example.instruction),
                    mapOf("from" to "gpt", "value" to example.output)
                )
            )
        }
        return Json { prettyPrint = true }.encodeToString(shareGptFormat)
    }

    private fun exportToCsv(examples: List<TrainingExample>): String {
        val builder = StringBuilder()
        builder.appendLine("instruction,input,output")
        examples.forEach { example ->
            val instruction = example.instruction.replace("\"", "\"\"")
            val input = example.input.replace("\"", "\"\"")
            val output = example.output.replace("\"", "\"\"")
            builder.appendLine("\"$instruction\",\"$input\",\"$output\"")
        }
        return builder.toString()
    }
}

/**
 * Extension function to expand DocumentChunks directly.
 */
suspend fun DataExpander.expandChunks(
    chunks: List<DocumentChunk>,
    persona: Persona = Persona.PROFESSIONAL,
    config: ExpansionConfig = ExpansionConfig()
): ExpansionResult {
    val chunkPairs = chunks.map { it.content to it.id }
    return expandBatch(chunkPairs, persona, config)
}
