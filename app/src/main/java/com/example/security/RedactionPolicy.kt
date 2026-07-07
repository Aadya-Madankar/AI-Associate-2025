package com.example.security

/**
 * Tunable strictness for the on-device [ScreenRedactor].
 *
 * Redaction always removes the unambiguously-secret-shaped runs (card numbers, IBANs,
 * long digit runs, opaque tokens) via [SensitivePatterns.alwaysValuePatterns]. This
 * policy controls the *aggressive* extras that trade recall against false positives,
 * plus how a flagged element's text is replaced.
 *
 * Two named presets are provided:
 *  - [STRICT] (the default for any cloud-bound snapshot): redact short digit runs and
 *    emails everywhere — including over [com.example.accessibility.UiElement.contentDescription],
 *    since [redactContentDescription] is on — drop hints/resource-ids that look
 *    sensitive, and blank the [com.example.accessibility.UiElement.text] of
 *    password/secure-context elements wholesale.
 *  - [LENIENT]: only redact high-confidence value shapes and short-digit-bearing
 *    *text*; leave ordinary short numbers and emails intact for better model grounding.
 *    See the [LENIENT] doc for exactly which descriptor channels it leaves untouched.
 *
 * Note on the contentDescription channel: blanking of password/secure-context elements
 * applies to [com.example.accessibility.UiElement.text] only. A secret carried on
 * [com.example.accessibility.UiElement.contentDescription] is governed solely by the
 * value-pattern scrubbing gated behind [redactContentDescription]; it is never
 * force-blanked, so a short OTP/PIN/password that no value pattern matches can survive
 * on the contentDescription. This is a known limitation of the current contract, not a
 * blanket "secure-element secrets are removed" guarantee.
 *
 * @param redactShortDigitRuns when true, standalone 3–8 digit runs (OTP/CVV shaped)
 *        are redacted in ALL element text, not just labeled or secure fields.
 * @param redactEmails when true, email addresses are treated as sensitive everywhere.
 * @param redactSensitiveLabels when true, a field's own hint/contentDescription/
 *        resourceId is itself redacted if it matches a sensitive label (so the cloud
 *        never even learns "this was the OTP box"); when false the descriptor is kept
 *        but the value is still redacted.
 * @param blankSecureElementText when true, the [text] of an element that sits on a
 *        screen the detector marked [com.example.accessibility.ScreenState.secure] —
 *        or that is a password field — is replaced wholesale with the placeholder
 *        rather than pattern-redacted.
 *
 *        IMPORTANT — scope and caveats (so this doc does not over-promise):
 *         - This flag governs ONLY the element's [text]. It does NOT blank
 *           [com.example.accessibility.UiElement.contentDescription] or [hint]; those
 *           are handled separately and are at most pattern-redacted via the
 *           value patterns (see [redactContentDescription] / [redactSensitiveLabels]).
 *           A short OTP/PIN/password announced via contentDescription (e.g.
 *           "Your code is 4821" or a 5-char alphanumeric password) is not matched by
 *           [SensitivePatterns.alwaysValuePatterns] and can therefore survive on the
 *           contentDescription even on a password/secure element. Treat
 *           contentDescription as an independent, weaker channel — not covered by this
 *           "text blanked wholesale" guarantee.
 *         - Whole-text blanking of password fields is NOT unconditional via this flag:
 *           setting `blankSecureElementText = false` (permitted by the constructor)
 *           leaves a real password field's text only pattern-redacted, so a short or
 *           alphanumeric password that no value pattern matches can leak. Both named
 *           presets set this true; a custom policy can opt out. In other words, this
 *           flag is a whole-screen / secure-context blanking switch, not a hard
 *           password-field invariant.
 * @param markSecureOnSensitiveElement when true, encountering a password element (or a
 *        sensitive OTP/card label) flips the returned [com.example.accessibility.ScreenState.secure]
 *        flag to true even if the upstream detector did not set it.
 * @param redactContentDescription when true, the *aggressive* extras
 *        (short-digit runs and emails, per [redactShortDigitRuns]/[redactEmails]) are
 *        additionally applied to each element's
 *        [com.example.accessibility.UiElement.contentDescription]. When false, the
 *        contentDescription is left untouched entirely — value-pattern scrubbing is
 *        NOT run over it (see the LENIENT caveats below). This flag is therefore an
 *        opt-in for *extra recall* on the descriptor channel; it is NOT a force-blank
 *        of accessibility-announced secrets. A secret announced via contentDescription
 *        on a password/secure element is only suppressed insofar as a value pattern
 *        happens to match it — it is not blanked wholesale.
 */
data class RedactionPolicy(
    val redactShortDigitRuns: Boolean = true,
    val redactEmails: Boolean = true,
    val redactSensitiveLabels: Boolean = true,
    val blankSecureElementText: Boolean = true,
    val markSecureOnSensitiveElement: Boolean = true,
    val redactContentDescription: Boolean = true
) {
    companion object {
        /**
         * Maximum-safety preset. Use this for anything that crosses the device
         * boundary to the cloud — it is the default the [ScreenRedactor] assumes.
         */
        val STRICT: RedactionPolicy = RedactionPolicy(
            redactShortDigitRuns = true,
            redactEmails = true,
            redactSensitiveLabels = true,
            blankSecureElementText = true,
            markSecureOnSensitiveElement = true,
            redactContentDescription = true
        )

        /**
         * Lower-friction preset for on-device-only use where retaining short numbers
         * and emails materially improves model grounding.
         *
         * What it still redacts: high-confidence value shapes in element [text]
         * (cards, IBANs, long digit runs, opaque tokens, via
         * [SensitivePatterns.alwaysValuePatterns]), and — because a sensitive label
         * triggers aggressive short-run scrubbing of [text] regardless of policy — the
         * *text* of labeled OTP/card/etc. fields. Password / secure-screen element
         * [text] is still blanked wholesale ([blankSecureElementText] is true).
         *
         * What it deliberately leaves intact (so callers do not over-trust it):
         *  - With [redactContentDescription] = false, the
         *    [com.example.accessibility.UiElement.contentDescription] is NOT scrubbed at
         *    all — not even the always-on value patterns run over it. A secret announced
         *    via contentDescription on ANY element (including a password field or a
         *    card/OTP-labeled field) passes through verbatim under LENIENT. "Labeled
         *    fields" are therefore only fully redacted on their [text], not on their
         *    contentDescription.
         *  - With [redactShortDigitRuns] = false, standalone 3–8 digit OTP/CVV-shaped
         *    runs in ordinary (unlabeled) text are kept.
         *  - With [redactEmails] = false and [redactSensitiveLabels] = false, emails and
         *    sensitive hints/resource-ids are kept.
         *
         * Because of the contentDescription leak vector above, LENIENT is intended for
         * ON-DEVICE-ONLY use and must NOT be used for a cloud-bound snapshot; the
         * cloud-bound default is [STRICT]. This constraint is currently advisory (it is
         * not enforced by a guard on this type); callers crossing the device boundary
         * are responsible for selecting [STRICT].
         */
        val LENIENT: RedactionPolicy = RedactionPolicy(
            redactShortDigitRuns = false,
            redactEmails = false,
            redactSensitiveLabels = false,
            blankSecureElementText = true,
            markSecureOnSensitiveElement = true,
            redactContentDescription = false
        )

        /** The policy the redactor uses when a caller does not specify one. */
        val DEFAULT: RedactionPolicy = STRICT
    }
}
