package com.example.permission

import android.graphics.Rect
import com.example.accessibility.ScreenState
import com.example.accessibility.UiElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [DefaultSecureContextDetector.inspect]. Robolectric is required because
 * [UiElement.bounds] is an [android.graphics.Rect], which is a framework stub on the JVM.
 *
 * These assert the real safety gate (ARCHITECTURE.md §4.3): a denylisted banking app, a
 * password field, and an OTP field each force a non-NONE [SecureReason]; a benign screen
 * and a null screen both stay [SecureReason.NONE].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DefaultSecureContextDetectorTest {

    private val detector = DefaultSecureContextDetector()

    /** Convenience: a UiElement with the noise fields defaulted and zero-area bounds. */
    private fun element(
        index: Int = 0,
        role: String = "TextView",
        text: String? = null,
        contentDescription: String? = null,
        hint: String? = null,
        resourceId: String? = null,
        editable: Boolean = false,
        password: Boolean = false
    ): UiElement = UiElement(
        index = index,
        role = role,
        text = text,
        contentDescription = contentDescription,
        hint = hint,
        resourceId = resourceId,
        bounds = Rect(0, 0, 0, 0),
        editable = editable,
        password = password
    )

    @Test
    fun `denylisted banking package yields a non-NONE reason`() {
        // com.chase.sig.android is an exact entry on the default package denylist.
        val screen = ScreenState(
            packageName = "com.chase.sig.android",
            elements = emptyList()
        )

        val reason = detector.inspect(screen)

        assertNotEquals(SecureReason.NONE, reason)
        assertEquals(SecureReason.DENYLISTED_APP, reason)
    }

    @Test
    fun `password field yields a non-NONE reason`() {
        val screen = ScreenState(
            packageName = "com.example.benign",
            elements = listOf(
                element(role = "EditText", editable = true, password = true)
            )
        )

        val reason = detector.inspect(screen)

        assertNotEquals(SecureReason.NONE, reason)
        assertEquals(SecureReason.PASSWORD_FIELD, reason)
    }

    @Test
    fun `OTP field yields a non-NONE reason`() {
        // Editable field whose visible label looks like an OTP prompt. Not a password
        // field, so this must fall through to the OTP/card verdict (not PASSWORD_FIELD).
        val screen = ScreenState(
            packageName = "com.example.benign",
            elements = listOf(
                element(
                    role = "EditText",
                    hint = "Enter OTP",
                    resourceId = "com.example.benign:id/otp_input",
                    editable = true
                )
            )
        )

        val reason = detector.inspect(screen)

        assertNotEquals(SecureReason.NONE, reason)
        assertEquals(SecureReason.OTP_OR_CARD_FIELD, reason)
    }

    @Test
    fun `benign screen yields NONE`() {
        // Plain, non-sensitive label on a non-denylisted app. No password, no sensitive
        // label/value, no FLAG_SECURE, no hidden node — the gate must allow automation.
        val screen = ScreenState(
            packageName = "com.example.benign",
            elements = listOf(
                element(role = "TextView", text = "Hello world", contentDescription = "Greeting")
            )
        )

        assertEquals(SecureReason.NONE, detector.inspect(screen))
    }

    @Test
    fun `null screen yields NONE`() {
        assertEquals(SecureReason.NONE, detector.inspect(null))
    }
}
