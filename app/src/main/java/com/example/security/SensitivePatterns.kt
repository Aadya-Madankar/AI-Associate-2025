package com.example.security

/**
 * The on-device library of regexes used to find sensitive text inside a
 * [com.example.accessibility.ScreenState] **before** it is serialized to the cloud.
 *
 * This object is the single source of truth for "what looks like a secret" in the
 * security-redaction stream. It is intentionally self-contained: it depends on
 * nothing but the Kotlin stdlib so it can be reasoned about, unit-tested, and audited
 * in isolation (ARCHITECTURE.md §4 "Redact on-device before any network call").
 *
 * Two complementary families of patterns are exposed:
 *
 *  - **Label patterns** ([labelPatterns]) match *descriptive* signals — a field's
 *    `hint`, `contentDescription`, or `resourceId` ("Enter OTP", "card_number",
 *    "CVV"). A label match means the *value* in/around that field should be treated
 *    as sensitive even if the value itself looks innocuous.
 *
 *  - **Value patterns** ([valuePatterns]) match *content* that is shaped like a
 *    secret regardless of any label — a 13–19 digit PAN, an IBAN, a short OTP-looking
 *    digit run, a CVV, an email (optional/lenient), or a long opaque token.
 *
 * All matching is case-insensitive and boundary-anchored where a naive substring match
 * would cause false positives (so "spin" never matches "PIN", "scarred" never matches
 * "card"). The boundary is a non-alphanumeric lookaround — `(?<![A-Za-z0-9])` /
 * `(?![A-Za-z0-9])` — rather than `\b`, because `_` is a regex word character but is
 * also the structural separator in real Android resource ids (e.g. `otp_input`,
 * `cvv_field`, `card_number_field`): `\botp\b` never matches inside `otp_input` since
 * there is no word-boundary transition either side of `_`. [com.example.accessibility.ScreenReader]
 * and [com.example.accessibility.NodeActionExecutor] feed raw `viewIdResourceName` strings
 * straight into [matchesSensitiveLabel], so this is load-bearing, not cosmetic. Patterns
 * are precompiled once and reused; callers must not mutate them.
 */
object SensitivePatterns {

    /** Not preceded by a letter/digit — used instead of `\b` so `_`/other separators
     * adjacent to a token don't defeat the match (see class doc). */
    private const val NB = "(?<![A-Za-z0-9])"

    /** Not followed by a letter/digit. */
    private const val NA = "(?![A-Za-z0-9])"

    /** Optional run of the separators seen in prose ("one time") and resource ids
     * ("one_time"/"one-time"). Zero-or-MORE — matching the old DenyLists breadth — so
     * doubled/mixed separators ("card  number", "card__number", "card - number") match. */
    private const val SEP = "[-\\s_]*"

    /**
     * The redaction token substituted in place of any sensitive run. Chosen to be
     * obviously non-sensitive, stable (so the model sees a consistent marker), and
     * unlikely to collide with real UI text.
     */
    const val PLACEHOLDER: String = "█REDACTED█"

    // ---------------------------------------------------------------------
    // Label patterns — match a field's descriptor (hint / desc / resource id)
    // ---------------------------------------------------------------------

    /** One-time-password / one-time-code labels. */
    val OTP_LABEL: Regex = Regex(
        "(?i)$NB(otp|one${SEP}time$SEP(code|password|pin|passcode)|verification${SEP}code|" +
            "auth(?:entication)?${SEP}code|2fa|mfa)$NA"
    )

    /**
     * Card-security-code labels (CVV / CVC / CVC2 / CID / security code).
     * `security code` is an UNBOUNDED substring (as in the old DenyLists regex) so
     * embedded forms ("cybersecurity code") match too; it also subsumes
     * "card security code".
     */
    val CVV_LABEL: Regex = Regex(
        "(?i)($NB(cvv|cvc|cvc2|cvv2|cid)$NA|security${SEP}code)"
    )

    /** Card / PAN labels (including a bare "card #" / "card#" suffix). */
    val CARD_LABEL: Regex = Regex(
        "(?i)$NB(card${SEP}(number|no|num|pan)|credit${SEP}card|debit${SEP}card|pan)$NA" +
            "|(?i)${NB}card[-\\s_]*#"
    )

    /**
     * PIN labels. `pin` stays boundary-anchored so "spinner"/"opinion" do not match
     * ("pin_code" still does via the non-alnum lookarounds); `passcode` is an UNBOUNDED
     * substring (as in the old DenyLists regex) so "Passcodes"/"myPasscode" match.
     */
    val PIN_LABEL: Regex = Regex(
        "(?i)(${NB}pin$NA|passcode)"
    )

    /**
     * Password / secret / credential labels. `password` is an UNBOUNDED substring (as in
     * the old DenyLists regex): camelCase resource ids ("passwordField"/"passwordInput" —
     * the most common Android spelling), plurals ("Passwords"), and embedded forms
     * ("mypassword", "password123") must all match; the word is long enough that
     * accidental substring hits are not a realistic false-positive source.
     */
    val PASSWORD_LABEL: Regex = Regex(
        "(?i)(password|$NB(passwd|pwd|secret|credential|api${SEP}key|token|seed${SEP}phrase|" +
            "recovery${SEP}phrase|mnemonic|private${SEP}key)$NA)"
    )

    /** Bank-account / IBAN / routing labels. */
    val ACCOUNT_LABEL: Regex = Regex(
        "(?i)$NB(iban|account${SEP}number|routing${SEP}number|sort${SEP}code|swift|bic)$NA"
    )

    /**
     * Government-id labels (SSN / national insurance / aadhaar / national id).
     * `social security` is an UNBOUNDED substring, as in the old DenyLists regex.
     */
    val GOV_ID_LABEL: Regex = Regex(
        "(?i)($NB(ssn|national${SEP}(insurance|id)|aadhaar|tax${SEP}id|" +
            "passport${SEP}(no|number))$NA|social${SEP}security)"
    )

    /**
     * Aggregate of every label pattern. A match against any of these on a field's
     * hint / content-description / resource-id marks the surrounding value sensitive.
     */
    val labelPatterns: List<Regex> = listOf(
        OTP_LABEL,
        CVV_LABEL,
        CARD_LABEL,
        PIN_LABEL,
        PASSWORD_LABEL,
        ACCOUNT_LABEL,
        GOV_ID_LABEL
    )

    // ---------------------------------------------------------------------
    // Value patterns — match secret-shaped content regardless of any label
    // ---------------------------------------------------------------------

    /**
     * Primary Account Number (credit/debit card): 13–19 digits, optionally grouped by
     * single spaces or hyphens. Anchored on word boundaries so it does not chop a
     * digit out of a longer non-card number.
     */
    val CARD_NUMBER_VALUE: Regex = Regex(
        """\b(?:\d[ -]?){13,19}\b"""
    )

    /**
     * IBAN: two-letter country code, two check digits, then 10–30 alphanumerics.
     * Case-insensitive; spaces inside the BBAN are tolerated.
     */
    val IBAN_VALUE: Regex = Regex(
        """(?i)\b[A-Z]{2}\d{2}(?:[ ]?[A-Z0-9]){10,30}\b"""
    )

    /**
     * CVV / CVC: an isolated 3–4 digit run. Anchored so it only matches when standing
     * alone (a nearby sensitive label should accompany this to avoid eating ordinary
     * small numbers).
     */
    val CVV_VALUE: Regex = Regex(
        """\b\d{3,4}\b"""
    )

    /**
     * OTP / short numeric code: a 4–8 digit run bounded by NON-DIGITS (the old
     * DenyLists lookaround form, not `\b`), so it also fires inside mixed alphanumeric
     * tokens of any length ("orderRef12345", "abcdefghi12345") where a `\b`-anchored run
     * or a length-limited alnum class would miss. Like [CVV_VALUE] this is deliberately
     * broad and is only applied as a *value* pattern when policy allows redacting short
     * digit runs, or when a sensitive label was detected nearby.
     */
    val OTP_VALUE: Regex = Regex(
        """(?<!\d)\d{4,8}(?!\d)"""
    )

    /**
     * A long opaque digit run (>= 9 digits, allowing single spaces/hyphens as group
     * separators). Catches account numbers, long codes, and PAN-adjacent values that
     * the card pattern's upper bound misses. Always redacted (strict and lenient).
     */
    val LONG_DIGIT_RUN: Regex = Regex(
        """\b(?:\d[ -]?){8,}\d\b"""
    )

    /**
     * A high-entropy / opaque token: a 16+ char alphanumeric (with - and _) run that
     * mixes letters and digits — typical of API keys, session tokens, and recovery
     * codes. The mix requirement avoids redacting ordinary long words.
     */
    val SECRET_TOKEN_VALUE: Regex = Regex(
        """\b(?=[A-Za-z0-9_-]*[A-Za-z])(?=[A-Za-z0-9_-]*\d)[A-Za-z0-9_-]{16,}\b"""
    )

    /**
     * A very long opaque alphanumeric run (>= 24 chars of [A-Za-z0-9_-]) WITHOUT the
     * letter+digit mix requirement of [SECRET_TOKEN_VALUE]. This closes the gap where a
     * letters-only secret (an all-alpha API token, or a single long base-N blob) never
     * satisfied the two lookaheads and leaked verbatim. The high minimum length keeps
     * the false-positive rate low: legitimate UI rarely renders a single unbroken
     * 24+ character token, and ordinary prose is broken by spaces/punctuation. Always
     * redacted (strict and lenient).
     */
    val LONG_OPAQUE_TOKEN_VALUE: Regex = Regex(
        """\b[A-Za-z0-9_-]{24,}\b"""
    )

    /**
     * BIP-39 / wallet recovery-phrase (seed-phrase) heuristic. Matches a contiguous run
     * of >= 11 space-separated lowercase alphabetic words each 3–8 letters long (so a
     * standard 12–24 word mnemonic such as "witch collapse practice feed shame open
     * despair creek road again ice least" is caught even when no
     * `mnemonic`/`recovery phrase` label is present on the visible/trimmed tree).
     * Legitimate UI text rarely renders 11+ such short lowercase tokens contiguously, so
     * this stays low false-positive while preventing recovery phrases from being
     * serialized to the cloud. Always redacted.
     */
    val SEED_PHRASE_VALUE: Regex = Regex(
        """\b(?:[a-z]{3,8}\s+){10,}[a-z]{3,8}\b"""
    )

    /**
     * A short alphanumeric code that contains at least one digit (e.g. a Steam-Guard /
     * 2FA / sign-in code like `KT4QW`, `G7H2K`, `ZX9KP`, `H8KP2Q`, or the per-group
     * halves of a dashed recovery code `a1b2-c3d4`). The lookahead requires a digit so
     * this never eats a plain dictionary word, and the 4–12 length bound keeps it in the
     * same family as [OTP_VALUE]/[CVV_VALUE]. Applied ONLY in the aggressive/short-run
     * gate (labeled element or strict policy), so it inherits the same false-positive
     * envelope as the existing short-digit patterns.
     */
    val SHORT_ALNUM_CODE: Regex = Regex(
        """\b(?=[A-Za-z0-9]*\d)[A-Za-z0-9]{4,12}\b"""
    )

    /**
     * An all-letter, all-caps 5-character standalone token (e.g. an all-caps Steam-Guard
     * code). Letters-only short codes carry a high false-positive risk against ordinary
     * acronyms, so this is exposed separately and is intended to be applied ONLY when a
     * sensitive OTP-style label is present on the same element (see
     * [otpLabeledValuePatterns]); it is NOT part of the general short-run set.
     */
    val SHORT_ALPHA_CODE: Regex = Regex(
        """\b[A-Z]{5}\b"""
    )

    /**
     * Email address. Redacted under the strict on-device policy (it is PII but frequently
     * benign on a UI).
     */
    val EMAIL_VALUE: Regex = Regex(
        """(?i)\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b"""
    )

    /**
     * Value patterns that are ALWAYS applied — these are unambiguously secret-shaped
     * and carry a very low false-positive rate (card numbers, IBANs, long digit runs,
     * opaque tokens including letters-only long runs, and seed phrases). Short numeric
     * runs ([OTP_VALUE]/[CVV_VALUE]) and [EMAIL_VALUE] are intentionally excluded here
     * and only applied under the label-gated short-run set.
     */
    val alwaysValuePatterns: List<Regex> = listOf(
        CARD_NUMBER_VALUE,
        IBAN_VALUE,
        LONG_DIGIT_RUN,
        SECRET_TOKEN_VALUE,
        LONG_OPAQUE_TOKEN_VALUE,
        SEED_PHRASE_VALUE
    )

    /**
     * Short-run value patterns (OTP / CVV / short alphanumeric-code shaped). High recall
     * but also higher false-positive risk on ordinary small numbers/codes, so these are
     * only applied when a sensitive label is present nearby, or on a password/secure field.
     *
     * [SHORT_ALNUM_CODE] catches short mixed letter+digit codes (Steam-Guard / 2FA /
     * sign-in / recovery codes such as `KT4QW`) that the digit-only patterns miss; it
     * requires at least one digit so plain words are not eaten, and so inherits the same
     * false-positive envelope as the existing short-digit runs.
     */
    val shortDigitPatterns: List<Regex> = listOf(
        OTP_VALUE,
        CVV_VALUE,
        SHORT_ALNUM_CODE
    )

    /**
     * Value patterns applied to an element's text ONLY when that same element carries a
     * sensitive OTP-style label ([OTP_LABEL]). These are the highest-false-positive
     * shapes — notably all-letter short codes ([SHORT_ALPHA_CODE], e.g. an all-caps
     * Steam-Guard code) that would collide with ordinary acronyms in unlabeled text — so
     * they are gated behind an explicit OTP label rather than the broader short-run gate.
     */
    val otpLabeledValuePatterns: List<Regex> = listOf(
        SHORT_ALPHA_CODE
    )

    /**
     * Returns true if any [labelPatterns] entry matches one of the supplied field
     * descriptors (text / hint / content-description / resource-id). Null/blank
     * signals are skipped.
     */
    fun matchesSensitiveLabel(vararg signals: String?): Boolean =
        signals.any { s -> !s.isNullOrBlank() && labelPatterns.any { it.containsMatchIn(s) } }

    /**
     * Returns true if any supplied *value* signal (typically an element's `text` /
     * `contentDescription`) is shaped like a secret. Always runs
     * [alwaysValuePatterns] (cards / IBAN / long digit runs / opaque tokens); when
     * [includeShortDigits] is set it additionally runs [shortDigitPatterns]
     * (OTP/CVV-shaped 3–8 digit runs) so a displayed code flips the screen secure.
     *
     * This is the value-side complement to [matchesSensitiveLabel]: it lets a
     * displayed secret VALUE (not just a sensitive label or password field) raise the
     * screen-level secure flag, so the serializer's secure-screen backstop blanks the
     * screen even when an individual scrub pattern under-matches. Null/blank signals
     * are skipped.
     */
    fun matchesSensitiveValue(
        includeShortDigits: Boolean,
        vararg signals: String?
    ): Boolean =
        signals.any { s ->
            !s.isNullOrBlank() && (
                alwaysValuePatterns.any { it.containsMatchIn(s) } ||
                    (includeShortDigits && shortDigitPatterns.any { it.containsMatchIn(s) })
            )
        }
}
