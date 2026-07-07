package com.example.audit

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/**
 * Hashes the literal params of an [com.example.permission.AgentAction] into a hex digest so
 * the audit log can record *that* an action happened without persisting the raw values
 * (message bodies, numbers, amounts, OTP codes…). See ARCHITECTURE.md §8.
 *
 * The digest is a **keyed HMAC-SHA256**, not a bare SHA-256. The HMAC key is a per-install
 * secret generated once and held in the Android Keystore (TEE/StrongBox where available) —
 * it is *never* written to the audit DB, app storage, or any backup/cloud-sync target. This
 * is what gives the stored log its leak-resistance: even for the low-entropy literals the §8
 * privacy claim enumerates (a 6-digit OTP, a 4-digit PIN, a phone number, a small amount),
 * an attacker with read access to the audit DB cannot precompute or brute-force the digest
 * back to the raw value without also extracting the Keystore key, which non-exportable
 * hardware-backed keys make infeasible.
 *
 * The digest is deterministic and stable *within an install*:
 * - keys are sorted, so map iteration order never changes the digest;
 * - each key/value is length-prefixed so `{"ab":"c"}` and `{"a":"bc"}` cannot collide;
 * - `null` values are encoded distinctly from the literal string "null".
 *
 * Because the key is per-install, the same params produce *different* digests on different
 * installs, so the log cannot be correlated across devices/backups. Within a single log,
 * identical params still yield identical digests (this is deliberate: it lets equality checks
 * and dedup work). Callers that also need within-log unlinkability of identical params — e.g.
 * to avoid fingerprinting a recurring recipient or a repeated security code — should mix a
 * per-entry value they already hold (such as `AuditEntry.id` or `timestampMs`) into [params]
 * before hashing, so the same literal in two entries produces two distinct digests.
 */
object ParamsHasher {

    private const val NULL_MARKER = " null "

    /** Lowercase hex alphabet. Declared before [EMPTY] so it is non-null when [toHex] runs. */
    private val HEX = "0123456789abcdef".toCharArray()

    private const val HMAC_ALGORITHM = "HmacSHA256"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "audit_params_hasher_hmac_v1"

    /**
     * Per-install HMAC key, loaded from (or generated once into) the Android Keystore.
     *
     * Resolved lazily so the object can initialize even before the Keystore is reachable, and
     * so [EMPTY]'s eager init below does not depend on Keystore state at class-load time.
     */
    private val hmacKey: SecretKey by lazy { loadOrCreateKey() }

    /** Hash for an action with no params — a stable digest of the empty map. */
    val EMPTY: String by lazy { hash(emptyMap()) }

    /**
     * Returns the lowercase keyed HMAC-SHA256 hex digest of [params].
     *
     * @param params the literal arguments of an action; values are rendered via [toString].
     */
    fun hash(params: Map<String, Any?>): String {
        val mac = Mac.getInstance(HMAC_ALGORITHM).apply { init(hmacKey) }
        // Canonical, order-independent, injective serialization.
        params.entries
            .sortedBy { it.key }
            .forEach { (key, value) ->
                appendField(mac, key)
                appendField(mac, value?.toString() ?: NULL_MARKER)
            }
        return mac.doFinal().toHex()
    }

    /** Length-prefixed field feed so adjacent fields can't be confused for one another. */
    private fun appendField(mac: Mac, field: String) {
        val bytes = field.toByteArray(Charsets.UTF_8)
        mac.update(bytes.size.toString().toByteArray(Charsets.UTF_8))
        mac.update(':'.code.toByte())
        mac.update(bytes)
    }

    /**
     * Returns the per-install HMAC key, generating it on first use. The key material never
     * leaves the Keystore: it is non-exportable and is referenced only by [KEY_ALIAS].
     */
    private fun loadOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build()
        )
        return generator.generateKey()
    }

    private fun ByteArray.toHex(): String {
        val out = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xFF
            out.append(HEX[v ushr 4])
            out.append(HEX[v and 0x0F])
        }
        return out.toString()
    }
}
