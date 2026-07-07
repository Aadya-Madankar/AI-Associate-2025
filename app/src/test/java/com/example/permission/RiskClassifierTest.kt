package com.example.permission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Focused safety tests for [RiskClassifier].
 *
 * The target is pure, stateless logic operating on enums + [AgentAction], so no
 * Robolectric / Android runtime is required. We exercise the real public API:
 * [RiskClassifier.staticTier] and [RiskClassifier.classify] (the 2-arg
 * `classify(action, secureReason)` overload).
 */
class RiskClassifierTest {

    private val classifier = RiskClassifier()

    /** Irreversible/outbound types named in the spec, asserted to never be SAFE. */
    private val irreversibleOutboundTypes = listOf(
        ActionType.SEND_MESSAGE,
        ActionType.PLACE_CALL,
        ActionType.MAKE_PURCHASE,
        ActionType.DELETE_DATA
    )

    /** Benign types named in the spec, asserted to classify SAFE on a clean screen. */
    private val benignTypes = listOf(
        ActionType.OPEN_APP,
        ActionType.SET_ALARM,
        ActionType.TORCH
    )

    private fun action(type: ActionType, reversible: Boolean = true) =
        AgentAction(type = type, reversible = reversible)

    @Test
    fun irreversibleOutboundTypes_areNeverSafe_byStaticTier() {
        for (type in irreversibleOutboundTypes) {
            val tier = classifier.staticTier(type)
            assertNotEquals(
                "staticTier($type) must never be SAFE",
                RiskTier.SAFE,
                tier
            )
        }
    }

    @Test
    fun irreversibleOutboundTypes_areNeverSafe_evenOnCleanScreen() {
        for (type in irreversibleOutboundTypes) {
            // NONE = non-secure screen: the irreversibility floor must still apply.
            val tier = classifier.classify(action(type), SecureReason.NONE)
            assertNotEquals(
                "classify($type, NONE) must never be SAFE",
                RiskTier.SAFE,
                tier
            )
        }
    }

    @Test
    fun irreversibleOutboundTypes_areNeverSafe_evenWhenCallerMarksReversible() {
        // A caller wrongly passing reversible = true must NOT be able to make an
        // inherently irreversible type SAFE — reversibility-by-type dominates.
        for (type in irreversibleOutboundTypes) {
            val tier = classifier.classify(action(type, reversible = true), SecureReason.NONE)
            assertTrue(
                "classify($type, NONE, reversible=true) must be at least GUARDED, was $tier",
                tier.ordinal >= RiskTier.GUARDED.ordinal
            )
        }
    }

    @Test
    fun blockedTierTypes_classifyBlocked() {
        // CHANGE_SECURITY_SETTING and DELETE_DATA sit in the BLOCKED tier of the table.
        assertEquals(
            RiskTier.BLOCKED,
            classifier.classify(action(ActionType.CHANGE_SECURITY_SETTING), SecureReason.NONE)
        )
        assertEquals(
            RiskTier.BLOCKED,
            classifier.classify(action(ActionType.DELETE_DATA), SecureReason.NONE)
        )
    }

    @Test
    fun blockedTierTypes_staticTierIsBlocked() {
        assertEquals(RiskTier.BLOCKED, classifier.staticTier(ActionType.CHANGE_SECURITY_SETTING))
        assertEquals(RiskTier.BLOCKED, classifier.staticTier(ActionType.DELETE_DATA))
    }

    @Test
    fun benignTypes_classifySafe_onCleanScreen() {
        for (type in benignTypes) {
            val tier = classifier.classify(action(type), SecureReason.NONE)
            assertEquals(
                "classify($type, NONE) should be SAFE",
                RiskTier.SAFE,
                tier
            )
        }
    }

    @Test
    fun benignTypes_staticTierIsSafe() {
        for (type in benignTypes) {
            assertEquals(
                "staticTier($type) should be SAFE",
                RiskTier.SAFE,
                classifier.staticTier(type)
            )
        }
    }
}
