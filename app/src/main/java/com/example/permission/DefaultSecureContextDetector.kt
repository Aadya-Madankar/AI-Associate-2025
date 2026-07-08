package com.example.permission

import com.example.accessibility.ScreenState
import com.example.accessibility.UiElement
import com.example.security.SensitivePatterns

/**
 * Default [SecureContextDetector]: inspects a [ScreenState] and returns the first
 * [SecureReason] that applies, in priority order (ARCHITECTURE.md §4.3). The screen is
 * already a compact, on-device snapshot — this runs *before* any text could leave the
 * device, so it is the gate that decides whether automation is allowed at all and
 * whether element text must be redacted.
 *
 * Detection order (first match wins):
 *  1. Foreground app on the [DenyLists] banking/wallet/authenticator set.
 *  2. Secure window — [ScreenState.secure] (FLAG_SECURE reported by the service).
 *  2b. Android-14 hidden sensitive node — [ScreenState.hasHiddenSensitiveNode]
 *      (a node whose `isAccessibilityDataSensitive` was set, so it never reached the
 *      element list); avoids the SAFE-because-empty trap.
 *  3. Any password field present ([UiElement.password]) OR the reader's full-tree side
 *      channel [ScreenState.sawPasswordField] (a field trimmed by the cap/visibility
 *      filter still counts).
 *  4. Any OTP/CVV/PIN/card/IBAN-shaped field by label/hint/resource-id/value regex OR
 *      the side channel [ScreenState.sawSensitiveLabel].
 *
 * IMPORTANT ordering invariant: the value-shaped backstop in [isSensitiveLabeledField]
 * (a displayed OTP/PAN/IBAN in a plain TextView with no label/flag) can only fire on the
 * RAW, pre-redaction screen — once [com.example.security.ScreenRedactor] replaces the
 * text with [SensitivePatterns.PLACEHOLDER] the value no longer matches. Callers MUST
 * run this gate on a non-redacted read; redaction is for the cloud boundary, not the
 * security decision. As a defensive floor, a screen still carrying the redaction
 * placeholder is itself treated as evidence of suppressed sensitive content.
 *
 * Keyguard ([SecureReason.KEYGUARD]) is surfaced via [inspectWithKeyguard] because the
 * bare [ScreenState] contract carries no explicit keyguard flag; the accessibility
 * service supplies that out of band when it has it.
 *
 * Pure and stateless. The denylisted-package source lives in [DenyLists] (user-editable
 * at runtime); the OTP/CVV/PIN/card/IBAN "what looks like a secret" patterns live solely
 * in [SensitivePatterns].
 */
class DefaultSecureContextDetector : SecureContextDetector {

    override fun inspect(screen: ScreenState?): SecureReason {
        if (screen == null) return SecureReason.NONE

        // 1. Denylisted foreground app (banking / wallet / 2FA).
        if (DenyLists.isDenylistedPackage(screen.packageName)) {
            return SecureReason.DENYLISTED_APP
        }

        // 2. Secure window: the service set FLAG_SECURE on the active window.
        if (screen.secure) {
            return SecureReason.FLAG_SECURE_WINDOW
        }

        // 2b. Android 14+ hidden sensitive node: the framework hid a node whose
        // `isAccessibilityDataSensitive` was set, so it never reached [screen.elements].
        // Without this, a banking webview / password manager / OTP screen that hides its
        // secrets from accessibility would look merely empty — the SAFE-because-empty
        // trap. The reader observes this over EVERY visited node and stamps it on the
        // screen so the decision (not just an unused side method) consumes it.
        if (screen.hasHiddenSensitiveNode) {
            return SecureReason.SENSITIVE_HIDDEN_NODE
        }

        // 3. A real password field. OR the reader's full-tree side channel
        // ([ScreenState.sawPasswordField]) with the emitted elements so a password input
        // dropped by the MAX_ELEMENTS cap or the visibility filter still counts.
        if (screen.sawPasswordField || screen.elements.any { it.password }) {
            return SecureReason.PASSWORD_FIELD
        }

        // 4. OTP / CVV / PIN / card-number / IBAN-shaped field by label/id/value. Same as
        // above: the reader's full-tree side channel ([ScreenState.sawSensitiveLabel])
        // catches a sensitive-labeled field that was trimmed out of the element list.
        if (screen.sawSensitiveLabel || screen.elements.any { isSensitiveLabeledField(it) }) {
            return SecureReason.OTP_OR_CARD_FIELD
        }

        // 5. Defensive floor: if this screen still carries the on-device redaction
        // sentinel, sensitive content was present and scrubbed upstream. The gate is meant
        // to run on the RAW, pre-redaction screen so the value-shaped OTP/PAN/IBAN backstop
        // in [isSensitiveLabeledField] can see verbatim text; if it is nonetheless handed a
        // redacted copy, the placeholder is itself proof of suppressed secrets — fail
        // closed rather than returning NONE for a screen that was displaying a live secret.
        if (screen.elements.any {
                it.text?.contains(SensitivePatterns.PLACEHOLDER) == true ||
                    it.contentDescription?.contains(SensitivePatterns.PLACEHOLDER) == true
            }
        ) {
            return SecureReason.OTP_OR_CARD_FIELD
        }

        return SecureReason.NONE
    }

    /**
     * As [inspect], but lets the caller (the accessibility service, which knows the
     * keyguard state) force a [SecureReason.KEYGUARD] verdict, which outranks
     * everything else.
     */
    fun inspectWithKeyguard(screen: ScreenState?, keyguardShowing: Boolean): SecureReason {
        if (keyguardShowing) return SecureReason.KEYGUARD
        return inspect(screen)
    }

    /**
     * True if this element's editable target or any of its descriptive signals
     * (text / hint / content-description / resource-id) match the sensitive-field
     * regex. We only flag fields that are *editable or look like a value entry*, to
     * avoid blocking on, say, a help article that merely mentions the word "password".
     */
    private fun isSensitiveLabeledField(el: UiElement): Boolean {
        // A focusable/editable input whose label/visible prompt looks sensitive is the
        // strongest signal. el.text is included because many OTP/CVV/PIN/"Card Number"/
        // "Password" screens carry their prompt in the element's text (a TextView label,
        // or an EditText's own visible prompt that the framework maps to
        // AccessibilityNodeInfo.text rather than hintText — hintText is null on API<26).
        // The resource-id is normalized so the sensitive-field regex sees token-separated
        // words regardless of Android naming style: the 'pkg:id/' prefix is stripped and
        // '_', '/', '-' and '.' become spaces, turning 'com.shop:id/otp_input' into
        // 'com shop id otp input' and 'card_number_field' into 'card number field'. This
        // makes the structural-signal path robust even though the raw id is also passed.
        val normalizedResourceId = normalizeResourceId(el.resourceId)
        val labelMatch = SensitivePatterns.matchesSensitiveLabel(
            el.text,
            el.hint,
            el.contentDescription,
            el.resourceId,
            normalizedResourceId
        )
        // An editable field whose text/hint/desc/id looks sensitive is sensitive. Keeping
        // the editable gate here avoids tripping on a help article that merely mentions
        // "password" in a non-editable paragraph.
        if (el.editable && labelMatch) return true

        // A resource-id match is an unambiguous structural signal (e.g. .../otp_input), so
        // it counts even when the element is non-editable.
        if (SensitivePatterns.matchesSensitiveLabel(el.resourceId, normalizedResourceId)) return true

        // Non-editable but explicitly labeled (e.g. an "Enter OTP" prompt next to a field).
        if (labelMatch) return true

        // Backstop: a displayed value that looks like a one-time code, card number or IBAN
        // — flagged whether the field is editable or not, since ScreenSerializer emits
        // el.text verbatim and a displayed OTP/PAN must not leave the device unredacted.
        if (SensitivePatterns.matchesSensitiveValue(includeShortDigits = true, el.text)) {
            return true
        }
        return false
    }

    /**
     * Splits a resource id into space-separated tokens so the sensitive-field regex is
     * not defeated by Android naming conventions. Strips the leading 'pkg:id/' (or any
     * 'pkg:type/') prefix and replaces the structural separators '_', '/', '-' and '.'
     * with spaces. Returns null for null/blank input so callers can skip it cheaply.
     * Example: 'com.shop:id/otp_input' -> 'otp input'; 'cvv_field' -> 'cvv field'.
     */
    private fun normalizeResourceId(resourceId: String?): String? {
        if (resourceId.isNullOrBlank()) return null
        // Drop the 'pkg:id/' (or similar) prefix; keep only the local entry name.
        val localName = resourceId.substringAfterLast('/').ifBlank { resourceId }
        val normalized = localName.replace(Regex("[_/\\-.]"), " ").trim()
        return normalized.ifBlank { null }
    }
}
