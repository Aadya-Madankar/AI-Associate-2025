package com.example.audit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.KeyStore
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.security.spec.AlgorithmParameterSpec
import java.util.Date
import java.util.Enumeration
import javax.crypto.KeyGenerator
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey

/**
 * Unit tests for [ParamsHasher] (ARCHITECTURE.md §8 — privacy of the audit log).
 *
 * Robolectric is required for a subtle reason: [ParamsHasher.loadOrCreateKey] builds a
 * real [android.security.keystore.KeyGenParameterSpec] and reads `android.security.keystore`
 * `KeyProperties` constants, which only resolve when the Android framework classes are loaded.
 * Robolectric loads those framework classes on the host JVM so that code path compiles *and*
 * executes here.
 *
 * Robolectric does **not**, however, implement the `AndroidKeyStore` JCA provider — that
 * provider is native/TEE-backed and is not shadowed. Without it,
 * `KeyStore.getInstance("AndroidKeyStore")` throws `KeyStoreException` and
 * `KeyGenerator.getInstance("HmacSHA256", "AndroidKeyStore")` throws `NoSuchProviderException`,
 * so the lazy HMAC key would fail to resolve on the first [ParamsHasher.hash] call. To run the
 * §8 safety assertions on the host we register a self-contained substitute `AndroidKeyStore`
 * provider in [installAndroidKeyStoreProvider] before any hashing happens. It supplies an
 * in-memory keystore and an `HmacSHA256` key generator (backed by the platform HMAC-SHA256),
 * which is functionally equivalent for exercising the keyed-digest behaviour under test.
 *
 * These assert the real safety behaviour, not getters:
 *  - hashing is deterministic for equal maps (so equality / dedup work within a log);
 *  - different maps yield different digests;
 *  - the raw literal values never appear in the hex digest (the leak-resistance claim).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ParamsHasherTest {

    @Before
    fun installAndroidKeyStoreProvider() {
        // ParamsHasher resolves its HMAC key lazily on the first hash() call, so the substitute
        // provider must be in place before any test method touches ParamsHasher. Idempotent:
        // Security.addProvider is a no-op if a provider of this name is already installed (the
        // ParamsHasher object — and thus its lazily-cached key — is reused across tests in a run).
        if (Security.getProvider(PROVIDER_NAME) == null) {
            Security.addProvider(AndroidKeyStoreTestProvider())
        }
    }

    @Test
    fun `equal maps produce identical digests`() {
        val a = mapOf<String, Any?>("to" to "+15551234567", "body" to "see you at 7", "amount" to 42)
        // Distinct map instance, same logical content, keys supplied in a different order.
        val b = mapOf<String, Any?>("amount" to 42, "body" to "see you at 7", "to" to "+15551234567")

        val hashA = ParamsHasher.hash(a)
        val hashB = ParamsHasher.hash(b)

        // Deterministic: same params -> same digest, and order-independent (keys are sorted).
        assertEquals(hashA, ParamsHasher.hash(a))
        assertEquals(hashA, hashB)
    }

    @Test
    fun `different maps produce different digests`() {
        val base = mapOf<String, Any?>("otp" to "123456")
        val differentValue = mapOf<String, Any?>("otp" to "654321")
        val differentKey = mapOf<String, Any?>("code" to "123456")
        val extraEntry = mapOf<String, Any?>("otp" to "123456", "channel" to "sms")

        val hashBase = ParamsHasher.hash(base)

        assertNotEquals(hashBase, ParamsHasher.hash(differentValue))
        assertNotEquals(hashBase, ParamsHasher.hash(differentKey))
        assertNotEquals(hashBase, ParamsHasher.hash(extraEntry))

        // Length-prefixing must stop {"ab":"c"} and {"a":"bc"} from colliding.
        val splitOne = mapOf<String, Any?>("ab" to "c")
        val splitTwo = mapOf<String, Any?>("a" to "bc")
        assertNotEquals(ParamsHasher.hash(splitOne), ParamsHasher.hash(splitTwo))

        // A non-empty map must not collide with the empty-map digest.
        assertNotEquals(ParamsHasher.EMPTY, hashBase)
        assertEquals(ParamsHasher.EMPTY, ParamsHasher.hash(emptyMap()))
    }

    @Test
    fun `raw values never appear in the digest`() {
        // Low-entropy, sensitive literals the §8 privacy claim enumerates.
        val otp = "987654"
        val pin = "4242"
        val phone = "+15557654321"
        val body = "transfer 250 to mom"

        val params = mapOf<String, Any?>(
            "otp" to otp,
            "pin" to pin,
            "to" to phone,
            "body" to body
        )

        val digest = ParamsHasher.hash(params)

        // The digest is a lowercase hex HMAC-SHA256 string: 64 hex chars, nothing else.
        assertEquals(64, digest.length)
        assertTrue("digest must be lowercase hex", digest.all { it in "0123456789abcdef" })

        // None of the raw values (nor the keys) leak into the stored digest.
        for (secret in listOf(otp, pin, phone, body, "transfer", "250", "mom")) {
            assertFalse(
                "raw value '$secret' must not appear in digest",
                digest.contains(secret)
            )
        }
    }

    private companion object {
        const val PROVIDER_NAME = "AndroidKeyStore"
        const val HMAC_ALGORITHM = "HmacSHA256"
    }

    /**
     * A minimal JCA provider that stands in for the real (native/TEE-backed) `AndroidKeyStore`
     * under host-JVM tests. It registers exactly what [ParamsHasher.loadOrCreateKey] asks for:
     *
     *  - `KeyStore.AndroidKeyStore` -> an in-memory secret-key store, and
     *  - `KeyGenerator.HmacSHA256`  -> a generator that ignores the supplied
     *    `KeyGenParameterSpec` and produces a real 256-bit HMAC-SHA256 key.
     *
     * Nothing here is exported off-device or persisted, matching the production contract.
     */
    private class AndroidKeyStoreTestProvider :
        Provider(PROVIDER_NAME, 1.0, "In-memory AndroidKeyStore substitute for host tests") {
        init {
            putService(
                object : Service(
                    this, "KeyStore", PROVIDER_NAME, InMemoryKeyStoreSpi::class.java.name,
                    null, null
                ) {
                    override fun newInstance(constructorParameter: Any?) = InMemoryKeyStoreSpi()
                }
            )
            putService(
                object : Service(
                    this, "KeyGenerator", HMAC_ALGORITHM, HmacKeyGeneratorSpi::class.java.name,
                    null, null
                ) {
                    override fun newInstance(constructorParameter: Any?) = HmacKeyGeneratorSpi()
                }
            )
        }
    }

    /**
     * In-memory [KeyStoreSpi] for secret keys, shared across instances so a key generated and
     * stored in one keystore handle is visible to the next — mirroring how the real
     * AndroidKeyStore persists by alias. (ParamsHasher only ever generates-then-uses in a single
     * handle, but a shared map keeps the substitute faithful and order-independent.)
     */
    private class InMemoryKeyStoreSpi : KeyStoreSpi() {
        override fun engineLoad(stream: java.io.InputStream?, password: CharArray?) = Unit

        override fun engineGetKey(alias: String?, password: CharArray?): java.security.Key? =
            ENTRIES[alias]

        override fun engineGetCertificate(alias: String?): Certificate? = null
        override fun engineGetCertificateChain(alias: String?): Array<Certificate>? = null
        override fun engineGetCreationDate(alias: String?): Date? =
            if (ENTRIES.containsKey(alias)) Date(0L) else null

        override fun engineSetKeyEntry(
            alias: String?,
            key: java.security.Key?,
            password: CharArray?,
            chain: Array<out Certificate>?
        ) {
            if (alias != null && key is SecretKey) ENTRIES[alias] = key
        }

        override fun engineSetKeyEntry(
            alias: String?,
            key: ByteArray?,
            chain: Array<out Certificate>?
        ) = Unit

        override fun engineSetCertificateEntry(alias: String?, cert: Certificate?) = Unit
        override fun engineDeleteEntry(alias: String?) {
            if (alias != null) ENTRIES.remove(alias)
        }

        override fun engineAliases(): Enumeration<String> =
            java.util.Collections.enumeration(ENTRIES.keys.toList())

        override fun engineContainsAlias(alias: String?): Boolean = ENTRIES.containsKey(alias)
        override fun engineSize(): Int = ENTRIES.size
        override fun engineIsKeyEntry(alias: String?): Boolean = ENTRIES.containsKey(alias)
        override fun engineIsCertificateEntry(alias: String?): Boolean = false
        override fun engineGetCertificateAlias(cert: Certificate?): String? = null
        override fun engineStore(stream: java.io.OutputStream?, password: CharArray?) = Unit

        private companion object {
            // Shared store so generate-into-keystore + later lookup-by-alias agree.
            val ENTRIES = java.util.concurrent.ConcurrentHashMap<String, SecretKey>()
        }
    }

    /**
     * HMAC-SHA256 [KeyGeneratorSpi] that accepts (and ignores) the Android
     * `KeyGenParameterSpec` the production code passes, delegating to the platform
     * HmacSHA256 generator from the default JCA provider so a genuine 256-bit key results.
     */
    private class HmacKeyGeneratorSpi : KeyGeneratorSpi() {
        private val delegate: KeyGenerator = KeyGenerator.getInstance(HMAC_ALGORITHM)

        override fun engineInit(random: SecureRandom?) = Unit

        // ParamsHasher calls init(KeyGenParameterSpec); accept any spec and ignore it.
        override fun engineInit(params: AlgorithmParameterSpec?, random: SecureRandom?) = Unit

        override fun engineInit(keysize: Int, random: SecureRandom?) = Unit

        override fun engineGenerateKey(): SecretKey = delegate.generateKey()
    }
}
