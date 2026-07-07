package com.example.security

import com.example.accessibility.ScreenState
import com.example.accessibility.UiElement
import com.example.permission.DenyLists

/**
 * On-device redactor that scrubs sensitive text out of a [ScreenState] **before** it
 * is serialized and sent to the cloud model (ARCHITECTURE.md §4 "Redact on-device
 * before any network call"; §3.3 the screen representation handed to the model).
 *
 * Guarantees:
 *  - Pure and side-effect-free: [redact] returns a NEW [ScreenState] / [UiElement]
 *    copies; the input is never mutated. Indices, bounds, roles, and capability flags
 *    are preserved so the executor's index→Rect grounding still resolves.
 *  - Secrets never leave the device: card numbers, IBANs, long digit runs, and opaque
 *    tokens are always replaced with [SensitivePatterns.PLACEHOLDER]; short numeric
 *    codes, emails, and sensitive descriptors are additionally scrubbed under the
 *    active [RedactionPolicy].
 *  - Secure marking: when any element is a password field, carries a sensitive
 *    OTP/card label, or displays a secret-shaped VALUE (PAN/IBAN/OTP/token, and short
 *    codes under the active short-digit policy), the returned [ScreenState.secure] is
 *    set true (subject to [RedactionPolicy.markSecureOnSensitiveElement]); an
 *    already-secure input stays secure. Text on password / secure-screen elements is
 *    blanked wholesale.
 *  - Denylist fail-closed: any screen belonging to a denylisted banking / wallet /
 *    authenticator package ([DenyLists.isDenylistedPackage]) is forced
 *    [ScreenState.secure] and has every element's text/contentDescription blanked,
 *    regardless of FLAG_SECURE / keyguard or the presence of a password field — so an
 *    AFTER-navigation screen reached mid-task is gated before it is serialized.
 *
 * The redactor is stateless and thread-safe; a single shared instance ([INSTANCE])
 * can be reused across reads.
 */
class ScreenRedactor(
    private val policy: RedactionPolicy = RedactionPolicy.DEFAULT
) {

    /**
     * Produce a redacted copy of [screen]. If [screen] is null or empty, an empty
     * (non-secure) [ScreenState] is returned unchanged in spirit.
     *
     * @param screen the raw observation from the accessibility reader.
     * @return a deep-copied [ScreenState] safe to serialize to the cloud.
     */
    fun redact(screen: ScreenState?): ScreenState {
        if (screen == null) return ScreenState.EMPTY

        // Fail closed on denylisted banking / wallet / authenticator surfaces: any
        // screen belonging to such a package is treated as secure regardless of
        // FLAG_SECURE / keyguard, so an AFTER-screen navigated into mid-task is gated
        // before it can be serialized to the cloud. (Closes the AFTER-screen re-gating
        // gap: the engine only gated the action on the BEFORE screen.)
        val denylisted = DenyLists.isDenylistedPackage(screen.packageName)

        if (screen.elements.isEmpty()) {
            // Nothing to scrub, but honour an upstream secure flag — and force secure
            // on a denylisted package even with no readable elements.
            return if (denylisted && !screen.secure) screen.copy(secure = true) else screen
        }

        var anySensitive = false
        val redactedElements = ArrayList<UiElement>(screen.elements.size)
        // On a denylisted surface, all element text/contentDescription is blanked
        // wholesale (independent of password/secure flags), so free-text payee names,
        // balances, merchant strings, and notification bodies cannot leak.
        val screenSecureBlank = denylisted || (screen.secure && policy.blankSecureElementText)

        for (element in screen.elements) {
            val labeled = hasSensitiveLabel(element)
            // An OTP-style label on this same element additionally licenses the
            // highest-false-positive value shapes (an all-letter short code such as an
            // all-caps Steam-Guard code) that are too risky to redact in unlabeled text.
            val otpLabeled = hasOtpLabel(element)
            // A displayed secret VALUE (PAN/IBAN/OTP/token, and short codes under the
            // active short-digit policy) must also flip the screen secure, so the
            // serializer's secure backstop blanks every channel even when an individual
            // scrub pattern under-matches. This is the value-side complement to label
            // detection and is a structural backstop, not a per-regex guarantee.
            val valueSensitive = SensitivePatterns.matchesSensitiveValue(
                policy.redactShortDigitRuns,
                element.text,
                element.contentDescription
            )
            // Refusing an actual password field must not be defeasible by any policy
            // toggle; blankSecureElementText only gates the weaker secure-screen branch.
            // A denylisted surface always force-blanks (fail closed).
            val forceBlankValue = element.password || screenSecureBlank
            if (element.password || labeled || valueSensitive) anySensitive = true

            redactedElements += redactElement(
                element = element,
                forceBlankValue = forceBlankValue,
                labeled = labeled,
                otpLabeled = otpLabeled
            )
        }

        val secure = screen.secure ||
            denylisted ||
            (policy.markSecureOnSensitiveElement && anySensitive)

        return screen.copy(
            elements = redactedElements,
            secure = secure
        )
    }

    /**
     * True if any descriptor of [element] (hint / content-description / resource-id /
     * text) matches a sensitive label pattern, meaning the value should be treated as
     * a secret even if it looks innocuous.
     */
    private fun hasSensitiveLabel(element: UiElement): Boolean =
        SensitivePatterns.matchesSensitiveLabel(
            element.hint,
            element.contentDescription,
            element.resourceId,
            element.text
        )

    /**
     * True if any descriptor of [element] (hint / content-description / resource-id /
     * text) matches an OTP-style label ([SensitivePatterns.OTP_LABEL]). Used to gate the
     * highest-false-positive value shapes (all-letter short codes) so they are only
     * redacted on a field the prose already marks as a one-time/verification code.
     */
    private fun hasOtpLabel(element: UiElement): Boolean =
        sequenceOf(
            element.hint,
            element.contentDescription,
            element.resourceId,
            element.text
        ).any { !it.isNullOrBlank() && SensitivePatterns.OTP_LABEL.containsMatchIn(it) }

    /**
     * Build the redacted copy of a single [element].
     *
     * @param forceBlankValue when true the [UiElement.text] is replaced wholesale with
     *        the placeholder (password / secure-screen fields).
     * @param labeled when true a sensitive label was detected, so even short/innocuous
     *        values in this element's text are redacted regardless of policy.
     * @param otpLabeled when true an OTP-style label was detected on this element, which
     *        additionally licenses the all-letter short-code shape ([SensitivePatterns.otpLabeledValuePatterns]).
     */
    private fun redactElement(
        element: UiElement,
        forceBlankValue: Boolean,
        labeled: Boolean,
        otpLabeled: Boolean
    ): UiElement {
        val newText = when {
            element.text.isNullOrEmpty() -> element.text
            forceBlankValue -> SensitivePatterns.PLACEHOLDER
            else -> scrubValue(element.text, aggressiveShortRuns = labeled, otpLabeled = otpLabeled)
        }

        val newContentDescription = when {
            element.contentDescription.isNullOrEmpty() -> element.contentDescription
            // Accessibility nodes routinely mirror the entered value into the
            // contentDescription (e.g. "Password, hunter2"); blank it wholesale for
            // password / secure-screen elements, independent of policy.
            forceBlankValue -> SensitivePatterns.PLACEHOLDER
            policy.redactContentDescription ->
                scrubValue(element.contentDescription, aggressiveShortRuns = labeled, otpLabeled = otpLabeled)
            else -> element.contentDescription
        }

        val newHint =
            if (policy.redactSensitiveLabels &&
                !element.hint.isNullOrEmpty() &&
                SensitivePatterns.matchesSensitiveLabel(element.hint)
            ) {
                SensitivePatterns.PLACEHOLDER
            } else {
                element.hint
            }

        // Resource ids are not user-visible secrets, but a sensitive descriptor can
        // leak intent ("otp_input"); strip it under strict labeling policy.
        val newResourceId =
            if (policy.redactSensitiveLabels &&
                !element.resourceId.isNullOrEmpty() &&
                SensitivePatterns.matchesSensitiveLabel(element.resourceId)
            ) {
                SensitivePatterns.PLACEHOLDER
            } else {
                element.resourceId
            }

        // Avoid allocating a copy when nothing changed.
        if (newText === element.text &&
            newContentDescription === element.contentDescription &&
            newHint === element.hint &&
            newResourceId === element.resourceId
        ) {
            return element
        }

        return element.copy(
            text = newText,
            contentDescription = newContentDescription,
            hint = newHint,
            resourceId = newResourceId
        )
    }

    /**
     * Replace every sensitive-shaped run inside [value] with the placeholder.
     *
     * Always applies [SensitivePatterns.alwaysValuePatterns] (cards / IBAN / long digit
     * runs / opaque tokens / letters-only long runs / seed phrases). Applies short-run
     * and email patterns when the active policy enables them, or — for short runs — when
     * [aggressiveShortRuns] is set because a sensitive label was detected on the same
     * element. When [otpLabeled] is set, the OTP-label-gated value shapes (all-letter
     * short codes) are additionally applied.
     */
    private fun scrubValue(
        value: String,
        aggressiveShortRuns: Boolean,
        otpLabeled: Boolean
    ): String {
        var result = value

        for (pattern in SensitivePatterns.alwaysValuePatterns) {
            result = pattern.replace(result, SensitivePatterns.PLACEHOLDER)
        }

        if (policy.redactEmails) {
            result = SensitivePatterns.EMAIL_VALUE.replace(result, SensitivePatterns.PLACEHOLDER)
        }

        if (policy.redactShortDigitRuns || aggressiveShortRuns) {
            for (pattern in SensitivePatterns.shortDigitPatterns) {
                result = pattern.replace(result, SensitivePatterns.PLACEHOLDER)
            }
        }

        // All-letter short codes are too false-positive-prone for the general short-run
        // gate; only redact them when this element itself carries an OTP-style label.
        if (otpLabeled) {
            for (pattern in SensitivePatterns.otpLabeledValuePatterns) {
                result = pattern.replace(result, SensitivePatterns.PLACEHOLDER)
            }
        }

        return result
    }

    companion object {
        /** Shared strict-policy redactor for the common cloud-bound path. */
        val INSTANCE: ScreenRedactor = ScreenRedactor(RedactionPolicy.STRICT)
    }
}
