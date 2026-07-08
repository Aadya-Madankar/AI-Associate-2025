package com.example.permission

import android.graphics.Rect
import com.example.accessibility.ScreenState
import com.example.accessibility.UiElement
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Focused safety tests for [DefaultPermissionEngine] Layer 2b — the single commit-tap
 * enforcement point after Task 7 merged `RiskClassifier.dangerousTapLabel` into
 * [DenyLists.commitButtonRegex] and moved the "unresolved tap label fails closed" rule
 * out of the classifier and into here.
 *
 * These are regression locks for the exact bug class the merge closes: before the
 * merge, a tap whose label matched only ONE of the two word lists — or an
 * unresolvable tap target — could silently auto-run in [AutonomyMode.BYPASS], because
 * only [DenyLists.commitButtonRegex] routed through the every-mode Layer 2b Confirm;
 * the classifier's own word list and its fail-closed rule only affected
 * ASK/ASK_LESS/AUTO via the effective [RiskTier], not BYPASS.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DefaultPermissionEngineTest {

    private val engine = DefaultPermissionEngine()

    private fun tapElement(index: Int, label: String): UiElement = UiElement(
        index = index,
        role = "Button",
        text = label,
        bounds = Rect(0, 0, 100, 50),
        clickable = true
    )

    private fun screenWith(vararg elements: UiElement): ScreenState = ScreenState(
        packageName = "com.example.benign",
        elements = elements.toList()
    )

    private fun tap(index: Int) = AgentAction(type = ActionType.TAP, params = mapOf("index" to index))

    @Test
    fun `tap on word only in the old classifier list still confirms in BYPASS`() {
        // "Authorize" was only in RiskClassifier.dangerousTapLabel pre-merge, never in
        // DenyLists.commitButtonRegex — the pre-merge Layer 2b (keyed on DenyLists only)
        // would have let this auto-run under BYPASS.
        val screen = screenWith(tapElement(0, "Authorize"))
        val decision = engine.decide(tap(0), AutonomyMode.BYPASS, screen)
        assertTrue(
            "A tap on an 'Authorize' button must still require confirmation in BYPASS",
            decision is PermissionDecision.Confirm
        )
    }

    @Test
    fun `tap on word only in the old DenyLists list still confirms in BYPASS`() {
        // "Withdraw" was only in the old DenyLists.commitButtonRegex, never in
        // RiskClassifier.dangerousTapLabel — regression-lock that the merge/refactor
        // didn't drop pre-existing DenyLists-side coverage.
        val screen = screenWith(tapElement(0, "Withdraw"))
        val decision = engine.decide(tap(0), AutonomyMode.BYPASS, screen)
        assertTrue(
            "A tap on a 'Withdraw' button must still require confirmation in BYPASS",
            decision is PermissionDecision.Confirm
        )
    }

    @Test
    fun `unresolvable tap target fails closed to confirm even in BYPASS`() {
        // index 99 does not exist on the screen — the tap target cannot be resolved.
        val screen = screenWith(tapElement(0, "Continue"))
        val decision = engine.decide(tap(99), AutonomyMode.BYPASS, screen)
        assertTrue(
            "An unresolvable tap target must fail closed to Confirm, even in BYPASS",
            decision is PermissionDecision.Confirm
        )
    }

    @Test
    fun `ordinary resolved tap on a non-commit label still auto-runs under AUTO`() {
        // Sanity check: the widened fail-closed/union rules above must not blanket-block
        // ordinary automation — a resolved, non-commit-labelled tap stays SAFE.
        val screen = screenWith(tapElement(0, "Continue"))
        val decision = engine.decide(tap(0), AutonomyMode.AUTO, screen)
        assertTrue(
            "An ordinary resolved tap on a benign label should auto-run under AUTO",
            decision is PermissionDecision.Allow
        )
    }

    @Test
    fun `locked keyguard blocks with SecureReason KEYGUARD even for a benign tap`() {
        // Bug B: DefaultSecureContextDetector.inspectWithKeyguard already implements
        // "locked -> KEYGUARD", but nothing on the decide() path called it. A benign,
        // non-commit tap that would otherwise auto-run under AUTO must instead block.
        val screen = screenWith(tapElement(0, "Continue"))
        val decision = engine.decide(tap(0), AutonomyMode.AUTO, screen, keyguardLocked = true)
        assertTrue(
            "A locked device must block with SecureReason.KEYGUARD",
            decision is PermissionDecision.Block &&
                (decision as PermissionDecision.Block).secureReason == SecureReason.KEYGUARD
        )
    }
}
