package com.privateai.vault.vectorstore

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PrivacyRedactorTest {

    private val redactor = RegexPrivacyRedactor()

    @Test
    fun `test phone number redaction`() {
        val inputs = mapOf(
            "Call 555-123-4567 today" to "Call [PHONE_REDACTED] today",
            "My number is (425) 555-0199" to "My number is [PHONE_REDACTED]",
            "International: +44 20 7123 4567" to "International: [PHONE_REDACTED]",
            "Dots format: 123.456.7890" to "Dots format: [PHONE_REDACTED]"
        )

        inputs.forEach { (input, expected) ->
            assertEquals(expected, redactor.redact(input), "Failed to redact phone: $input")
        }
    }

    @Test
    fun `test email redaction`() {
        val text = "Contact support@private-ai.com or admin@localhost"
        val expected = "Contact [EMAIL_REDACTED] or [EMAIL_REDACTED]"
        assertEquals(expected, redactor.redact(text))
    }

    @Test
    fun `test ssn redaction`() {
        val inputs = mapOf(
            "SSN is 123-45-6789" to "SSN is [SSN_REDACTED]",
            "Raw 9 digit: 987654321" to "Raw 9 digit: [SSN_REDACTED]"
        )
        inputs.forEach { (input, expected) ->
            assertEquals(expected, redactor.redact(input))
        }
    }

    @Test
    fun `test credit card redaction`() {
        val visa = "Pay with 4111 1111 1111 1111 now"
        val amex = "Or use 3782 822463 10005"
        
        assertEquals("Pay with [CARD_REDACTED] now", redactor.redact(visa))
        assertEquals("Or use [CARD_REDACTED]", redactor.redact(amex))
    }

    @Test
    fun `test ip address redaction`() {
        val ipv4 = "Server at 192.168.1.1 is down"
        val ipv6 = "IPv6: 2001:0db8:85a3:0000:0000:8a2e:0370:7334"
        
        assertEquals("Server at [IP_REDACTED] is down", redactor.redact(ipv4))
        assertEquals("IPv6: [IP_REDACTED]", redactor.redact(ipv6))
    }

    @Test
    fun `test date of birth redaction`() {
        val inputs = mapOf(
            "Born on 01/15/1990" to "Born on [DOB_REDACTED]",
            "DOB: January 15, 1990" to "DOB: [DOB_REDACTED]",
            "ISO format: 1990-01-15" to "ISO format: [DOB_REDACTED]"
        )
        inputs.forEach { (input, expected) ->
            assertEquals(expected, redactor.redact(input))
        }
    }

    @Test
    fun `test mixed content redaction`() {
        val input = """
            Student: John Doe
            Phone: 555-123-4567
            Email: john@example.com
            Card on file: 4242 4242 4242 4242
            Notes: Good jab.
        """.trimIndent()

        val redacted = redactor.redact(input)
        
        assertTrue(redacted.contains("[PHONE_REDACTED]"))
        assertTrue(redacted.contains("[EMAIL_REDACTED]"))
        assertTrue(redacted.contains("[CARD_REDACTED]"))
        assertFalse(redacted.contains("555-0199"))
        assertFalse(redacted.contains("john@example.com"))
    }

    @Test
    fun `test no false positives`() {
        val safeInputs = listOf(
            "The year was 1990",
            "I spent $50.00 today",
            "My version is 1.2.3.4",
            "Look at section 123-45"
        )

        assertEquals("The year was 1990", redactor.redact("The year was 1990"))
        assertEquals("Look at section 123-45", redactor.redact("Look at section 123-45"))
    }

    @Test
    fun `test sensitivity detection boolean`() {
        assertTrue(redactor.containsSensitiveData("My IP is 10.0.0.1"))
        assertFalse(redactor.containsSensitiveData("Just a regular boxing session note."))
    }
}