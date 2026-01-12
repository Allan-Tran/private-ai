package com.privateai.vault.vectorstore

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Epic 3 - Story 3.3: Data Cleaning Tool Tests
 *
 * Tests the training data generation and export functionality.
 */
class DataCleaningTest {

    private val cleaner = SimpleDataCleaner()

    @Test
    fun `test text cleaning removes HTML tags`() {
        val dirtyHtml = "<p>This is <b>bold</b> text with <a href='link'>links</a>.</p>"
        val clean = cleaner.cleanText(dirtyHtml, removeHtml = true)

        assertFalse(clean.contains("<p>"), "Should remove HTML tags")
        assertFalse(clean.contains("<b>"), "Should remove HTML tags")
        assertFalse(clean.contains("</p>"), "Should remove HTML tags")
        assertTrue(clean.contains("bold"), "Should keep text content")
        assertTrue(clean.contains("links"), "Should keep text content")
    }

    @Test
    fun `test text cleaning preserves content when HTML removal disabled`() {
        val htmlContent = "<div>Important <code>data</code></div>"
        val clean = cleaner.cleanText(htmlContent, removeHtml = false)

        assertTrue(clean.contains("<div>"), "Should preserve HTML when disabled")
        assertTrue(clean.contains("<code>"), "Should preserve HTML when disabled")
    }

    @Test
    fun `test text cleaning normalizes whitespace`() {
        val messyText = "Multiple    spaces\n\n\nand   excessive    newlines\t\ttabs"
        val clean = cleaner.cleanText(messyText)

        assertFalse(clean.contains("    "), "Should normalize multiple spaces")
        assertFalse(clean.contains("\n\n\n"), "Should normalize excessive newlines")
        assertFalse(clean.contains("\t\t"), "Should normalize tabs")

        // Should have single spaces
        assertTrue(clean.contains(" and "), "Should have normalized spaces")
    }

    @Test
    fun `test text cleaning removes control characters`() {
        // ASCII control characters (except newline/tab)
        val textWithControl = "Normal text\u0000with\u0001control\u0007chars"
        val clean = cleaner.cleanText(textWithControl)

        assertFalse(clean.contains("\u0000"), "Should remove null character")
        assertFalse(clean.contains("\u0001"), "Should remove control characters")
        assertFalse(clean.contains("\u0007"), "Should remove bell character")
        assertTrue(clean.contains("Normal"), "Should keep normal text")
    }

    @Test
    fun `test text cleaning trims whitespace`() {
        val paddedText = "   \n  Important content here   \n  "
        val clean = cleaner.cleanText(paddedText)

        assertEquals("Important content here", clean.trim(), "Should trim leading/trailing whitespace")
        assertFalse(clean.startsWith(" "), "Should not start with space")
        assertFalse(clean.endsWith(" "), "Should not end with space")
    }

    @Test
    fun `test question generation creates multiple questions`() {
        val content = "The Llama model is a large language model trained on diverse data. " +
                     "It excels at natural language understanding and generation tasks."

        val questions = cleaner.generateQuestions(content, count = 3)

        assertEquals(3, questions.size, "Should generate requested number of questions")

        questions.forEach { question ->
            assertTrue(question.isNotBlank(), "Questions should not be blank")
            assertTrue(question.endsWith("?"), "Questions should end with ?")
        }
    }

    @Test
    fun `test question generation uses content keywords`() {
        val content = "Python programming is essential for machine learning development."

        val questions = cleaner.generateQuestions(content, count = 2)

        // Questions should reference the content topic
        val combinedQuestions = questions.joinToString(" ").lowercase()
        val hasRelevantWord = combinedQuestions.contains("python") ||
                             combinedQuestions.contains("programming") ||
                             combinedQuestions.contains("machine") ||
                             combinedQuestions.contains("learning")

        assertTrue(hasRelevantWord, "Questions should reference content topics")
    }

    @Test
    fun `test question generation handles short content`() {
        val shortContent = "Brief note."

        val questions = cleaner.generateQuestions(shortContent, count = 2)

        assertEquals(2, questions.size, "Should still generate requested questions")
        questions.forEach { question ->
            assertTrue(question.contains("?"), "Should be valid questions")
        }
    }

    @Test
    fun `test training pair export to JSONL format`() {
        val pairs = listOf(
            TrainingPair("What is AI?", "AI is artificial intelligence.", emptyMap()),
            TrainingPair("Define ML", "ML is machine learning.", emptyMap())
        )

        val jsonl = cleaner.exportTrainingData(pairs, TrainingDataFormat.JSONL)

        // Each pair should be on its own line
        val lines = jsonl.split("\n").filter { it.isNotBlank() }
        assertEquals(2, lines.size, "JSONL should have one line per pair")

        // Each line should be valid JSON
        lines.forEach { line ->
            assertTrue(line.contains("\"prompt\""), "Should have prompt field")
            assertTrue(line.contains("\"completion\""), "Should have completion field")
        }
    }

    @Test
    fun `test training pair export to CSV format`() {
        val pairs = listOf(
            TrainingPair("Question 1", "Answer 1", emptyMap()),
            TrainingPair("Question 2", "Answer 2", emptyMap())
        )

        val csv = cleaner.exportTrainingData(pairs, TrainingDataFormat.CSV)

        // Should have header row
        assertTrue(csv.startsWith("prompt,completion"), "CSV should have header")

        // Should have data rows
        val lines = csv.split("\n").filter { it.isNotBlank() }
        assertEquals(3, lines.size, "CSV should have header + 2 data rows")

        // Should properly escape quotes
        assertTrue(csv.contains("\"Question 1\""), "Should quote fields")
    }

    @Test
    fun `test training pair export to Alpaca format`() {
        val pairs = listOf(
            TrainingPair("Instruction here", "Output here", emptyMap())
        )

        val alpaca = cleaner.exportTrainingData(pairs, TrainingDataFormat.ALPACA)

        // Alpaca format uses "instruction", "input", "output"
        assertTrue(alpaca.contains("\"instruction\""), "Should have instruction field")
        assertTrue(alpaca.contains("\"input\""), "Should have input field")
        assertTrue(alpaca.contains("\"output\""), "Should have output field")

        // Should be valid JSON array
        assertTrue(alpaca.startsWith("["), "Should start with array bracket")
        assertTrue(alpaca.endsWith("]"), "Should end with array bracket")
    }

    @Test
    fun `test training pair export to ShareGPT format`() {
        val pairs = listOf(
            TrainingPair("Human prompt", "Assistant response", emptyMap())
        )

        val sharegpt = cleaner.exportTrainingData(pairs, TrainingDataFormat.SHAREGPT)

        // ShareGPT format uses "conversations" with "from" and "value"
        assertTrue(sharegpt.contains("\"conversations\""), "Should have conversations field")
        assertTrue(sharegpt.contains("\"from\""), "Should have from field")
        assertTrue(sharegpt.contains("\"value\""), "Should have value field")
        assertTrue(sharegpt.contains("\"human\""), "Should mark human messages")
        assertTrue(sharegpt.contains("\"gpt\""), "Should mark GPT messages")
    }

    @Test
    fun `test training pair structure`() {
        val metadata = mapOf("source" to "test", "version" to "1.0")
        val pair = TrainingPair(
            prompt = "What is the capital of France?",
            completion = "The capital of France is Paris.",
            metadata = metadata
        )

        assertEquals("What is the capital of France?", pair.prompt)
        assertEquals("The capital of France is Paris.", pair.completion)
        assertEquals("test", pair.metadata["source"])
        assertEquals("1.0", pair.metadata["version"])
    }

    @Test
    fun `test training data config defaults`() {
        val config = TrainingDataConfig()

        assertTrue(config.generateQuestions, "Should generate questions by default")
        assertEquals(2, config.questionsPerChunk, "Default questions per chunk")
        assertTrue(config.includeContext, "Should include context by default")
        assertEquals(2048, config.maxPairLength, "Default max pair length")
        assertTrue(config.includeMetadata, "Should include metadata by default")
    }

    @Test
    fun `test CSV export escapes special characters`() {
        val pairs = listOf(
            TrainingPair(
                prompt = "Question with \"quotes\" inside",
                completion = "Answer with, commas, inside",
                metadata = emptyMap()
            )
        )

        val csv = cleaner.exportTrainingData(pairs, TrainingDataFormat.CSV)

        // CSV should escape quotes by doubling them
        assertTrue(csv.contains("\"\"quotes\"\""), "Should escape quotes in CSV")
    }

    @Test
    fun `test export handles empty list`() {
        val emptyPairs = emptyList<TrainingPair>()

        val jsonl = cleaner.exportTrainingData(emptyPairs, TrainingDataFormat.JSONL)
        val csv = cleaner.exportTrainingData(emptyPairs, TrainingDataFormat.CSV)

        assertTrue(jsonl.isBlank() || jsonl.isEmpty(), "Empty JSONL for empty input")
        assertTrue(csv.contains("prompt,completion"), "CSV should still have header")
    }
}
