package com.privateai.vault.vectorstore

/**
 * Regex-based privacy redactor that masks common PII patterns.
 *
 * Part of Epic 2.2 - Redaction Story
 * Implements defense-in-depth: even with encryption, sensitive data is masked.
 *
 * Patterns detected and redacted:
 * - Phone numbers (US and international formats)
 * - Email addresses
 * - Social Security Numbers (SSN)
 * - Credit card numbers
 * - IP addresses
 * - Dates of birth
 */
class RegexPrivacyRedactor : PrivacyRedactor {

companion object {
        private val PHONE_PATTERNS = listOf(
            Regex("""(?:\+?1[-.\s]?)?\(?([0-9]{3})\)?[-.\s]?([0-9]{3})[-.\s]?([0-9]{4})"""),
            
            Regex("""\+[0-9]{1,3}[-.\s]?\(?[0-9]{1,4}\)?[-.\s]?[0-9]{1,4}[-.\s]?[0-9]{1,9}""")
        )

        private val EMAIL_PATTERN = Regex(
            """[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+(\.[a-zA-Z]{2,})?"""
        )

        // Social Security Number patterns
        private val SSN_PATTERNS = listOf(
            // US SSN: 123-45-6789
            Regex("""\b[0-9]{3}-[0-9]{2}-[0-9]{4}\b"""),
            // Without dashes: 123456789
            Regex("""\b[0-9]{9}\b""")
        )

        // Credit card number patterns (Visa, MasterCard, Amex, Discover)
        private val CREDIT_CARD_PATTERNS = listOf(
            // 16 digit cards with spaces or dashes: 4111 1111 1111 1111 or 4111-1111-1111-1111
            Regex("""\b[0-9]{4}[\s-][0-9]{4}[\s-][0-9]{4}[\s-][0-9]{4}\b"""),

            // 15 digit Amex with space: 3782 822463 10005
            Regex("""\b[0-9]{4}[\s-][0-9]{6}[\s-][0-9]{5}\b"""),

            // 16 digit cards continuous (Visa/Mastercard): 4111111111111111
            Regex("""\b(?:4[0-9]{3}|5[1-5][0-9]{2}|6011|35\d{2})[0-9]{12}\b"""),

            // 15 digit Amex continuous: 378282246310005
            Regex("""\b3[47][0-9]{13}\b""")
        )

        // IP address patterns (IPv4 and IPv6)
        private val IP_PATTERNS = listOf(
            // IPv4: 192.168.1.1
            Regex("""\b(?:[0-9]{1,3}\.){3}[0-9]{1,3}\b"""),
            // IPv6: 2001:0db8:85a3:0000:0000:8a2e:0370:7334
            Regex("""\b(?:[0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}\b""")
        )

        // Date of birth patterns
        private val DOB_PATTERNS = listOf(
            // MM/DD/YYYY, DD/MM/YYYY
            Regex("""\b[0-9]{1,2}/[0-9]{1,2}/[0-9]{4}\b"""),
            // YYYY-MM-DD (ISO format)
            Regex("""\b[0-9]{4}-[0-9]{2}-[0-9]{2}\b"""),
            // Month DD, YYYY (e.g., January 15, 1990)
            Regex("""\b(January|February|March|April|May|June|July|August|September|October|November|December)\s+[0-9]{1,2},?\s+[0-9]{4}\b""")
        )

        // Replacement masks
        private const val PHONE_MASK = "[PHONE_REDACTED]"
        private const val EMAIL_MASK = "[EMAIL_REDACTED]"
        private const val SSN_MASK = "[SSN_REDACTED]"
        private const val CREDIT_CARD_MASK = "[CARD_REDACTED]"
        private const val IP_MASK = "[IP_REDACTED]"
        private const val DOB_MASK = "[DOB_REDACTED]"
    }

    override fun redact(text: String): String {
        var redacted = text

        // IMPORTANT: Order matters! Longer patterns should be checked first
        // to avoid partial matches by shorter patterns.

        // Redact credit card numbers FIRST (before phone numbers)
        // Credit cards can look like phone numbers if not matched first
        CREDIT_CARD_PATTERNS.forEach { pattern ->
            redacted = pattern.replace(redacted, CREDIT_CARD_MASK)
        }

        // Redact SSNs (before phone numbers - 9 digits could look like phone)
        SSN_PATTERNS.forEach { pattern ->
            redacted = pattern.replace(redacted, SSN_MASK)
        }

        // Redact phone numbers
        PHONE_PATTERNS.forEach { pattern ->
            redacted = pattern.replace(redacted, PHONE_MASK)
        }

        // Redact email addresses
        redacted = EMAIL_PATTERN.replace(redacted, EMAIL_MASK)

        // Redact IP addresses
        IP_PATTERNS.forEach { pattern ->
            redacted = pattern.replace(redacted, IP_MASK)
        }

        // Redact dates of birth
        DOB_PATTERNS.forEach { pattern ->
            redacted = pattern.replace(redacted, DOB_MASK)
        }

        return redacted
    }

    override fun getRedactionPatterns(): List<String> = listOf(
        "Phone Numbers (US & International)",
        "Email Addresses",
        "Social Security Numbers (SSN)",
        "Credit Card Numbers",
        "IP Addresses (IPv4 & IPv6)",
        "Dates of Birth"
    )

    /**
     * Validates if the text contains any redactable patterns.
     * Useful for logging/debugging to know if redaction occurred.
     */
    fun containsSensitiveData(text: String): Boolean {
        return PHONE_PATTERNS.any { it.containsMatchIn(text) } ||
                EMAIL_PATTERN.containsMatchIn(text) ||
                SSN_PATTERNS.any { it.containsMatchIn(text) } ||
                CREDIT_CARD_PATTERNS.any { it.containsMatchIn(text) } ||
                IP_PATTERNS.any { it.containsMatchIn(text) } ||
                DOB_PATTERNS.any { it.containsMatchIn(text) }
    }
}
