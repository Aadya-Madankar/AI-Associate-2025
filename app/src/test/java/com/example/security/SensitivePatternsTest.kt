package com.example.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Focused unit tests for [SensitivePatterns].
 *
 * [SensitivePatterns] is pure Kotlin (stdlib regexes only, no android.* / Context),
 * so these run as plain host JVM JUnit4 tests with no Robolectric runner.
 *
 * Asserted safety behaviors:
 *  - matches card numbers (spaced / dashed) as always-on value patterns;
 *  - matches 4-8 digit OTPs when short-digit gating is enabled;
 *  - matches "OTP is 123456" prose (both via the OTP label and the OTP value);
 *  - matches IBAN-shaped strings as always-on value patterns;
 *  - does NOT match ordinary words.
 */
class SensitivePatternsTest {

    // ---------------------------------------------------------------------
    // Card numbers (spaced / dashed) — always-on value patterns
    // ---------------------------------------------------------------------

    @Test
    fun matchesSpacedCardNumber() {
        val spaced = "4111 1111 1111 1111"
        // Direct regex assertion.
        assertTrue(
            "CARD_NUMBER_VALUE should match a 16-digit space-grouped PAN",
            SensitivePatterns.CARD_NUMBER_VALUE.containsMatchIn(spaced)
        )
        // Always-applied even when short digits are NOT included.
        assertTrue(
            "A spaced card number is a sensitive value",
            SensitivePatterns.matchesSensitiveValue(includeShortDigits = false, spaced)
        )
    }

    @Test
    fun matchesDashedCardNumber() {
        val dashed = "4111-1111-1111-1111"
        assertTrue(
            "CARD_NUMBER_VALUE should match a 16-digit hyphen-grouped PAN",
            SensitivePatterns.CARD_NUMBER_VALUE.containsMatchIn(dashed)
        )
        assertTrue(
            "A dashed card number is a sensitive value",
            SensitivePatterns.matchesSensitiveValue(includeShortDigits = false, dashed)
        )
    }

    @Test
    fun matchesCardNumberEmbeddedInText() {
        // CARD_NUMBER_VALUE is always part of alwaysValuePatterns.
        assertTrue(
            SensitivePatterns.matchesSensitiveValue(
                includeShortDigits = false,
                "Card 4111 1111 1111 1111 saved"
            )
        )
        assertTrue(SensitivePatterns.CARD_NUMBER_VALUE in SensitivePatterns.alwaysValuePatterns)
    }

    // ---------------------------------------------------------------------
    // 4-8 digit OTPs — short-digit gated value patterns
    // ---------------------------------------------------------------------

    @Test
    fun matchesSixDigitOtpValue() {
        val otp = "123456"
        assertTrue(
            "OTP_VALUE should match a standalone 6-digit run",
            SensitivePatterns.OTP_VALUE.containsMatchIn(otp)
        )
    }

    @Test
    fun matchesFourDigitOtpValue() {
        assertTrue(SensitivePatterns.OTP_VALUE.containsMatchIn("1234"))
    }

    @Test
    fun matchesEightDigitOtpValue() {
        assertTrue(SensitivePatterns.OTP_VALUE.containsMatchIn("12345678"))
    }

    @Test
    fun otpValueIsGatedBehindShortDigits() {
        val otp = "123456"
        // With short-digit gating enabled the standalone OTP run is sensitive.
        assertTrue(
            "A displayed OTP code is sensitive when short digits are included",
            SensitivePatterns.matchesSensitiveValue(includeShortDigits = true, otp)
        )
        // A bare 6-digit OTP is NOT shaped like a card/IBAN/long-run, so without
        // short-digit gating it is intentionally not flagged by the always set.
        assertFalse(
            "A bare 6-digit OTP is not an always-on secret shape",
            SensitivePatterns.matchesSensitiveValue(includeShortDigits = false, otp)
        )
    }

    @Test
    fun otpValueIsInShortDigitPatterns() {
        assertTrue(SensitivePatterns.OTP_VALUE in SensitivePatterns.shortDigitPatterns)
    }

    // ---------------------------------------------------------------------
    // "OTP is 123456" prose — matched by both the label and the value side
    // ---------------------------------------------------------------------

    @Test
    fun matchesOtpProseViaLabel() {
        val prose = "OTP is 123456"
        // The word "OTP" is an OTP_LABEL hit.
        assertTrue(
            "OTP_LABEL should match the word OTP in prose",
            SensitivePatterns.OTP_LABEL.containsMatchIn(prose)
        )
        assertTrue(
            "matchesSensitiveLabel should flag prose containing an OTP label",
            SensitivePatterns.matchesSensitiveLabel(prose)
        )
    }

    @Test
    fun matchesOtpProseViaValueWhenShortDigitsIncluded() {
        val prose = "OTP is 123456"
        // The embedded 123456 is an OTP_VALUE hit once short digits are gated in.
        assertTrue(
            "OTP_VALUE should match the embedded code in prose",
            SensitivePatterns.OTP_VALUE.containsMatchIn(prose)
        )
        assertTrue(
            SensitivePatterns.matchesSensitiveValue(includeShortDigits = true, prose)
        )
    }

    // ---------------------------------------------------------------------
    // IBAN-shaped strings — always-on value patterns
    // ---------------------------------------------------------------------

    @Test
    fun matchesCompactIban() {
        val iban = "DE89370400440532013000"
        assertTrue(
            "IBAN_VALUE should match a compact IBAN",
            SensitivePatterns.IBAN_VALUE.containsMatchIn(iban)
        )
        assertTrue(
            "An IBAN is an always-on sensitive value",
            SensitivePatterns.matchesSensitiveValue(includeShortDigits = false, iban)
        )
    }

    @Test
    fun matchesSpacedIban() {
        val iban = "GB29 NWBK 6016 1331 9268 19"
        assertTrue(
            "IBAN_VALUE should tolerate single spaces in the BBAN",
            SensitivePatterns.IBAN_VALUE.containsMatchIn(iban)
        )
        assertTrue(
            SensitivePatterns.matchesSensitiveValue(includeShortDigits = false, iban)
        )
    }

    @Test
    fun ibanValueIsInAlwaysValuePatterns() {
        assertTrue(SensitivePatterns.IBAN_VALUE in SensitivePatterns.alwaysValuePatterns)
    }

    // ---------------------------------------------------------------------
    // Ordinary words — must NOT match (no false positives)
    // ---------------------------------------------------------------------

    @Test
    fun ordinaryWordsAreNotSensitiveValues() {
        val ordinary = arrayOf(
            "Settings",
            "Continue",
            "hello world",
            "username",
            "Welcome back",
            "Submit"
        )
        for (word in ordinary) {
            // Not sensitive in the lenient (always-only) mode...
            assertFalse(
                "'$word' should not be an always-on sensitive value",
                SensitivePatterns.matchesSensitiveValue(includeShortDigits = false, word)
            )
            // ...and not even with short-digit gating, since none contain digit runs.
            assertFalse(
                "'$word' should not be a short-digit sensitive value",
                SensitivePatterns.matchesSensitiveValue(includeShortDigits = true, word)
            )
        }
    }

    @Test
    fun ordinaryWordsDoNotMatchCardOrIbanRegex() {
        val ordinary = arrayOf("Settings", "Continue", "hello world", "username")
        for (word in ordinary) {
            assertFalse(
                "CARD_NUMBER_VALUE must not match '$word'",
                SensitivePatterns.CARD_NUMBER_VALUE.containsMatchIn(word)
            )
            assertFalse(
                "IBAN_VALUE must not match '$word'",
                SensitivePatterns.IBAN_VALUE.containsMatchIn(word)
            )
            assertFalse(
                "OTP_VALUE must not match '$word'",
                SensitivePatterns.OTP_VALUE.containsMatchIn(word)
            )
        }
    }

    @Test
    fun wordBoundaryAvoidsLabelFalsePositives() {
        // Per the source contract: "spin" must never match PIN, "scarred" never card.
        assertFalse(
            "PIN_LABEL must not match the substring inside 'spinner'",
            SensitivePatterns.PIN_LABEL.containsMatchIn("spinner")
        )
        assertFalse(
            "CARD_LABEL must not match the substring inside 'scarred'",
            SensitivePatterns.CARD_LABEL.containsMatchIn("scarred")
        )
        // Blank / null signals are skipped, not matched.
        assertFalse(SensitivePatterns.matchesSensitiveLabel(null, "   "))
        assertFalse(SensitivePatterns.matchesSensitiveValue(includeShortDigits = true, null, ""))
    }
}
