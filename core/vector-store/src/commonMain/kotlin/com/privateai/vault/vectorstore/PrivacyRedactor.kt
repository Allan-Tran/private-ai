package com.privateai.vault.vectorstore

/**
 * Interface for redacting sensitive information from text before storage.
 *
 * Part of Epic 2 (The Vault) - Sovereign AI privacy requirements.
 * Ensures that sensitive data is never stored in plain text, even with encryption.
 */
interface PrivacyRedactor {
    /**
     * Redacts sensitive information from the given text.
     *
     * @param text The text to redact
     * @return The redacted text with sensitive information masked
     */
    fun redact(text: String): String

    /**
     * Returns a description of what patterns this redactor handles.
     */
    fun getRedactionPatterns(): List<String>
}

/**
 * No-op redactor that performs no redaction.
 * Useful for testing or when redaction is not required.
 */
class NoOpRedactor : PrivacyRedactor {
    override fun redact(text: String): String = text

    override fun getRedactionPatterns(): List<String> = emptyList()
}
