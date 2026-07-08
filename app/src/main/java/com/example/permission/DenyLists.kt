package com.example.permission

/**
 * Curated, user-editable denylists that gate the permission engine: banking / wallet /
 * authenticator apps and the categorically-refused action types from ARCHITECTURE.md
 * §4.3 and §8. "What looks like a secret" (OTP / CVV / PIN / password / card / IBAN
 * field or value) is NOT here — that lives solely in
 * [com.example.security.SensitivePatterns], the single secret-shape library consumed
 * by [DefaultSecureContextDetector], the accessibility layer, and the redactor alike.
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
     * Labels of UI controls that commit an irreversible/outbound action when tapped —
     * "Send", "Pay", "Transfer", "Confirm", "Delete", "Authorize"… Per ARCHITECTURE.md
     * §5/§8 these must ALWAYS confirm and NEVER auto-run, even in AUTO/ASK_LESS/BYPASS.
     * The agent drives apps by tapping their own buttons (not high-level send_* tools),
     * so a raw TAP on a button whose text/content-description matches this pattern is
     * treated as a forced-ask commit by [DefaultPermissionEngine] Layer 2b — the single
     * commit-tap enforcement point (a former second copy of this list, keyed on
     * different words, lived in `RiskClassifier.dangerousTapLabel` and has been merged
     * in here so a tap can no longer slip past one list by matching only the other).
     * Word-boundary anchored so "resend" does not match "send" inside another word is
     * NOT a concern here — we deliberately fail closed and confirm on any whole-word
     * match.
     */
    val commitButtonRegex: Regex = Regex(
        """(?i)\b(send|pay|transfer|confirm|place\s*order|place\s*call|call\s*now|buy|checkout|check\s*out|delete|remove|purchase|wire|withdraw|book|submit|authori[sz]e)\b"""
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
}
