package com.example.permission

/**
 * Curated, user-editable denylists and sensitive-keyword patterns that gate the
 * permission engine. These are the hard ethical boundaries from ARCHITECTURE.md §4.3
 * and §8: banking / wallet / authenticator apps are never automated, and any field
 * whose text/hint/resource-id looks like an OTP, CVV, PIN, password, card number, or
 * IBAN is treated as a secure context.
 *
 * Nothing here performs detection itself — [DefaultSecureContextDetector] and
 * [DefaultPermissionEngine] consume these sets. They are exposed as mutable, snapshot
 * copies so a future settings screen can let the user add/remove entries at runtime
 * without recompiling, while reads stay cheap and allocation-free on the hot path.
 */
object DenyLists {

    /**
     * Package prefixes/exact ids for finance, payment, wallet and 2FA/authenticator
     * apps that must never be driven by the agent. Matching is case-insensitive and
     * prefix-based (see [isDenylistedPackage]) so family packages and regional clones
     * (e.g. `com.google.android.apps.walletnfcrel`) are covered without exhaustive
     * enumeration. This list is deliberately conservative and intentionally curated;
     * it is not meant to be exhaustive of every bank on earth.
     */
    private val defaultDenylistedPackages: Set<String> = setOf(
        // --- Generic / OEM wallets & payments ---
        "com.google.android.apps.walletnfcrel",   // Google Wallet
        "com.google.android.apps.wallet",
        "com.google.android.apps.nbu.paisa.user", // Google Pay (India)
        "com.samsung.android.spay",               // Samsung Wallet / Pay
        "com.samsung.android.spayfw",
        "com.paypal.android.p2pmobile",           // PayPal
        "com.squareup.cash",                      // Cash App
        "com.venmo",                              // Venmo
        "com.zellepay.zelle",                     // Zelle
        "me.lyft.android.financialhealth",
        "com.stripe",                             // Stripe (prefix)
        "com.revolut",                            // Revolut (prefix)
        "com.wise.android",                       // Wise
        "com.transferwise",
        "com.coinbase.android",                   // Coinbase
        "com.binance",                            // Binance (prefix)
        "com.crypto.android",                     // Crypto.com
        "co.mona.android",
        "com.robinhood.android",                  // Robinhood
        // --- Banks (US/EU/IN representative prefixes) ---
        "com.chase.sig.android",                  // Chase
        "com.infonow.bofa",                       // Bank of America
        "com.wf.wellsfargomobile",                // Wells Fargo
        "com.usaa.mobile.android.usaa",           // USAA
        "com.citi.citimobile",                    // Citi
        "com.konylabs.capitalone",                // Capital One
        "com.barclays",                           // Barclays (prefix)
        "com.grppl.android.shell.cmblloyds",      // Lloyds
        "com.htsu.hsbcpersonalbanking",           // HSBC
        "com.starlingbank.android",               // Starling
        "co.uk.Nationwide.Mobile",
        "com.monzo.android",                      // Monzo
        "com.snapwork.hdfc",                      // HDFC
        "com.csam.icici.bank.imobile",            // ICICI
        "com.sbi.lotusintouch",                   // SBI / YONO
        "com.axis.mobile",                        // Axis
        "net.one97.paytm",                        // Paytm
        "in.org.npci.upiapp",                     // BHIM UPI
        "com.phonepe.app",                        // PhonePe
        // --- Authenticators / 2FA ---
        "com.google.android.apps.authenticator2", // Google Authenticator
        "com.azure.authenticator",                // Microsoft Authenticator
        "com.authy.authy",                        // Authy
        "com.duosecurity.duomobile",              // Duo
        "org.thoughtcrime.securesms",             // (Signal — has 2FA / sealed sender)
        "com.lastpass.lpandroid",                 // LastPass
        "com.agilebits.onepassword",              // 1Password
        "com.bitwarden",                          // Bitwarden (prefix)
        "com.yubico.yubioath"                     // Yubico Authenticator
    )

    /**
     * Live, mutable copy of the denylisted package set. A settings UI may add/remove
     * entries. Reads on the hot path use [isDenylistedPackage] which iterates this set.
     */
    val denylistedPackages: MutableSet<String> =
        java.util.Collections.synchronizedSet(defaultDenylistedPackages.toMutableSet())

    /**
     * Substrings that, if present in a package id, also flag it as denylisted. Catches
     * the long tail of "...bank...", "...wallet..." style packages without enumerating
     * every institution. Case-insensitive.
     */
    val denylistedPackageSubstrings: MutableSet<String> =
        java.util.Collections.synchronizedSet(
            mutableSetOf(
                "bank", "banking", "wallet", "authenticator", "crypto", "payment", "paybank"
            )
        )

    /**
     * Action types that are categorically refused regardless of app or mode. These are
     * the mechanisms ARCHITECTURE.md §8 lists as "hard rules" (silent send / destructive
     * delete) that the agent must never perform autonomously even in BYPASS.
     */
    val denylistedActionTypes: MutableSet<ActionType> =
        java.util.Collections.synchronizedSet(
            mutableSetOf(
                ActionType.DELETE_DATA,
                ActionType.CHANGE_SECURITY_SETTING
            )
        )

    /**
     * Sensitive-keyword regex applied to a field's text, hint, content-description and
     * resource-id. A match marks the surrounding context as [SecureReason.OTP_OR_CARD_FIELD].
     *
     * Boundaries use a *non-alphanumeric* lookaround [(?<![A-Za-z0-9]) ... (?![A-Za-z0-9])]
     * rather than `\b`, because `_`, `/` and `-` are the structural separators in real
     * Android resource ids (e.g. `com.shop:id/otp_input`, `cvv_field`, `card_number_field`,
     * `otp_digit_1`). In regex `_` is a word char, so `\bOTP\b` does NOT match `otp_input`
     * — the lookarounds treat `_`/`/`/`-` as separators so those structural ids are caught,
     * while "spinner", "scarred", "cardiac", "account_balance" and "opinion" still don't
     * match. Multi-word tokens allow `[\s_-]` between parts. Order is irrelevant — any match
     * wins.
     */
    val sensitiveFieldRegex: Regex = Regex(
        """(?i)((?<![A-Za-z0-9])OTP(?![A-Za-z0-9])|(?<![A-Za-z0-9])CVV(?![A-Za-z0-9])|(?<![A-Za-z0-9])CVC(?![A-Za-z0-9])|(?<![A-Za-z0-9])PIN(?![A-Za-z0-9])|password|passcode|(?<![A-Za-z0-9])card[\s_-]*number(?![A-Za-z0-9])|(?<![A-Za-z0-9])card[\s_-]*((?<![A-Za-z0-9])(no|num)(?![A-Za-z0-9])|#)|(?<![A-Za-z0-9])(routing|account)[\s_-]*number(?![A-Za-z0-9])|(?<![A-Za-z0-9])IBAN(?![A-Za-z0-9])|security[\s_-]*code|(?<![A-Za-z0-9])verification[\s_-]*code(?![A-Za-z0-9])|(?<![A-Za-z0-9])(2fa|mfa|auth(?:entication)?)[\s_-]*code(?![A-Za-z0-9])|one[-\s_]?time[\s_-]*(code|password|pin)|(?<![A-Za-z0-9])SSN(?![A-Za-z0-9])|social[\s_-]*security)"""
    )

    /**
     * Tighter pattern for *values* that look like an OTP / card number / IBAN even
     * without a label — e.g. a 6-digit code or a 13–19 digit PAN. Used as a backstop
     * when a field has no descriptive label. Kept separate so callers can choose how
     * aggressively to redact.
     */
    val sensitiveValueRegex: Regex = Regex(
        """(?i)((?<!\d)\d{4,8}(?!\d))|(\b\d{9}\b)|(\b(?:\d[ -]?){13,19}\b)|(\b[A-Z]{2}\d{2}[A-Z0-9]{10,30}\b)"""
    )

    /**
     * Labels of UI controls that commit an irreversible/outbound action when tapped —
     * "Send", "Pay", "Transfer", "Confirm", "Delete"… Per ARCHITECTURE.md §5/§8 these
     * must ALWAYS confirm and NEVER auto-run, even in AUTO/ASK_LESS. The agent drives
     * apps by tapping their own buttons (not high-level send_* tools), so a raw TAP on a
     * button whose text/content-description matches this pattern is treated as a
     * forced-ask commit by [DefaultPermissionEngine]. Word-boundary anchored so "resend"
     * does not match "send" inside another word is NOT a concern here — we deliberately
     * fail closed and confirm on any whole-word match.
     */
    val commitButtonRegex: Regex = Regex(
        """(?i)\b(send|pay|transfer|confirm|place\s*order|buy|checkout|check\s*out|delete|remove|purchase|wire|withdraw|book|submit)\b"""
    )

    /**
     * Returns true if any of the provided element labels (text / content-description /
     * hint) looks like an irreversible commit control. Used by the engine to force a
     * confirm on raw TAP/LONG_PRESS of a "Send"/"Pay"/"Confirm"/"Delete" button.
     */
    fun matchesCommitButton(vararg labels: String?): Boolean =
        labels.any { !it.isNullOrBlank() && commitButtonRegex.containsMatchIn(it) }

    /**
     * Returns true if [packageName] is on the denylist, by exact id, known prefix, or a
     * denylisted substring. Null/blank packages are treated as not denylisted (the
     * caller decides how to handle unknown contexts).
     */
    fun isDenylistedPackage(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        val pkg = packageName.lowercase()
        synchronized(denylistedPackages) {
            for (entry in denylistedPackages) {
                val e = entry.lowercase()
                if (pkg == e || pkg.startsWith("$e.") || pkg.startsWith(e)) return true
            }
        }
        synchronized(denylistedPackageSubstrings) {
            for (sub in denylistedPackageSubstrings) {
                if (pkg.contains(sub.lowercase())) return true
            }
        }
        return false
    }

    /** Returns true if [type] is categorically refused regardless of context. */
    fun isDenylistedActionType(type: ActionType): Boolean =
        denylistedActionTypes.contains(type)

    /**
     * Returns true if any of the provided field signals (text/hint/desc/resource-id)
     * matches the sensitive-keyword pattern. Convenience for the detector.
     */
    fun matchesSensitiveField(vararg signals: String?): Boolean =
        signals.any { !it.isNullOrBlank() && sensitiveFieldRegex.containsMatchIn(it) }
}
