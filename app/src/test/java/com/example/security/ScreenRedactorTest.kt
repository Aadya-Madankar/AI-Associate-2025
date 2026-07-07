package com.example.security

import android.graphics.Rect
import com.example.accessibility.ScreenState
import com.example.accessibility.UiElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [ScreenRedactor]: the on-device scrubber that removes sensitive text
 * from a [ScreenState] before it is serialized to the cloud.
 *
 * [UiElement.bounds] is a real [android.graphics.Rect], so these tests run under
 * Robolectric to provide the framework class.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScreenRedactorTest {

    private fun element(
        index: Int,
        role: String,
        text: String? = null,
        contentDescription: String? = null,
        hint: String? = null,
        resourceId: String? = null,
        password: Boolean = false
    ): UiElement = UiElement(
        index = index,
        role = role,
        text = text,
        contentDescription = contentDescription,
        hint = hint,
        resourceId = resourceId,
        bounds = Rect(0, 0, 100, 50),
        password = password
    )

    /**
     * A screen with a password field, an OTP-labeled element showing a 6-digit code,
     * and a benign label. Use the non-denylisted package so the assertions exercise the
     * password / OTP / benign logic rather than the fail-closed denylist path.
     */
    private fun mixedScreen(): ScreenState = ScreenState(
        packageName = "com.example.notes",
        elements = listOf(
            // 0: a real password field carrying an entered value.
            element(index = 0, role = "EditText", text = "hunter2pass", password = true),
            // 1: an OTP field labeled as such, displaying a 6-digit one-time code.
            element(
                index = 1,
                role = "EditText",
                text = "483920",
                hint = "Enter OTP",
                resourceId = "com.example.notes:id/otp_input"
            ),
            // 2: benign, non-sensitive UI text that must be preserved verbatim.
            element(index = 2, role = "TextView", text = "Account settings")
        )
    )

    @Test
    fun `redact masks password field text and marks screen secure`() {
        val redacted = ScreenRedactor().redact(mixedScreen())

        // The whole screen is flagged secure because a password element was present.
        assertTrue("password field must flip screen secure", redacted.secure)

        val passwordEl = redacted.elements.single { it.index == 0 }
        // Password value is blanked wholesale with the placeholder — the secret is gone.
        assertEquals(SensitivePatterns.PLACEHOLDER, passwordEl.text)
        assertFalse(
            "original password must not survive",
            passwordEl.text!!.contains("hunter2")
        )
        // The password flag itself is preserved (grounding metadata is kept).
        assertTrue(passwordEl.password)
    }

    @Test
    fun `redact removes OTP-looking value from labeled field`() {
        val redacted = ScreenRedactor().redact(mixedScreen())

        val otpEl = redacted.elements.single { it.index == 1 }
        // The 6-digit OTP value must be masked, not passed through verbatim.
        assertFalse(
            "OTP digits must not leak: ${otpEl.text}",
            otpEl.text!!.contains("483920")
        )
        assertEquals(SensitivePatterns.PLACEHOLDER, otpEl.text)
    }

    @Test
    fun `redact preserves benign text and indices`() {
        val original = mixedScreen()
        val redacted = ScreenRedactor().redact(original)

        val benign = redacted.elements.single { it.index == 2 }
        assertEquals("benign text must be preserved", "Account settings", benign.text)

        // Indices/roles are preserved so the executor's index->Rect grounding still works.
        assertEquals(original.elements.map { it.index }, redacted.elements.map { it.index })
        assertEquals(original.elements.map { it.role }, redacted.elements.map { it.role })
    }

    @Test
    fun `redact does not mutate the input screen`() {
        val original = mixedScreen()
        val originalPasswordText = original.elements.single { it.index == 0 }.text
        val originalOtpText = original.elements.single { it.index == 1 }.text

        ScreenRedactor().redact(original)

        // Pure / side-effect-free contract: the source ScreenState is untouched.
        assertEquals("hunter2pass", originalPasswordText)
        assertEquals("483920", original.elements.single { it.index == 1 }.text)
        assertEquals(originalOtpText, original.elements.single { it.index == 1 }.text)
        assertFalse("input must not be flagged secure", original.secure)
    }

    @Test
    fun `redact returns a new ScreenState copy`() {
        val original = mixedScreen()
        val redacted = ScreenRedactor().redact(original)

        assertNotEquals(original, redacted)
    }

    @Test
    fun `redact of null returns empty non-secure state`() {
        val redacted = ScreenRedactor().redact(null)

        assertEquals(ScreenState.EMPTY, redacted)
        assertFalse(redacted.secure)
        assertTrue(redacted.elements.isEmpty())
    }
}
