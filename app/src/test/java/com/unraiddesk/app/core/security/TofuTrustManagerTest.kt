package com.unraiddesk.app.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.Principal
import java.security.PublicKey
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

class TofuTrustManagerTest {

    @Test
    fun systemTrustedCertificatePassesWithoutPinning() {
        val systemTm = FakeSystemTrustManager(shouldTrust = true)
        val store = TofuTrustManager.InMemoryPinnedCertStore()
        val tofu = TofuTrustManager(
            systemTrustManager = systemTm,
            pinnedCertStore = store,
            hostProvider = { "192.168.1.10" }
        )

        tofu.checkServerTrusted(arrayOf(FakeX509Certificate("CN=tower.local", byteArrayOf(1, 2, 3))), "RSA")
    }

    @Test
    fun untrustedSelfSignedCertThrowsTofuExceptionThenPassesAfterPinning() {
        val systemTm = FakeSystemTrustManager(shouldTrust = false)
        val store = TofuTrustManager.InMemoryPinnedCertStore()
        val cert = FakeX509Certificate("CN=tower.local", byteArrayOf(10, 20, 30, 40))
        val tofu = TofuTrustManager(
            systemTrustManager = systemTm,
            pinnedCertStore = store,
            hostProvider = { "192.168.1.10" }
        )

        var caught: TofuTrustManager.TofuUntrustedCertException? = null
        try {
            tofu.checkServerTrusted(arrayOf(cert), "RSA")
        } catch (e: TofuTrustManager.TofuUntrustedCertException) {
            caught = e
        }

        assertTrue(caught != null)
        val info = caught!!.certInfo
        assertEquals("192.168.1.10", info.host)
        assertEquals("CN=tower.local", info.subject)
        assertEquals(TofuTrustManager.sha256Fingerprint(cert), info.sha256Fingerprint)

        // Pin for 192.168.1.10
        store.pin(info.host, info.sha256Fingerprint)
        tofu.checkServerTrusted(arrayOf(cert), "RSA")

        // Different host with same cert must still be rejected until explicitly pinned
        var differentHostError: TofuTrustManager.TofuUntrustedCertException? = null
        try {
            TofuTrustManager.withHost("192.168.1.20") {
                val tofuDynamic = TofuTrustManager(systemTm, store)
                tofuDynamic.checkServerTrusted(arrayOf(cert), "RSA")
            }
        } catch (e: TofuTrustManager.TofuUntrustedCertException) {
            differentHostError = e
        }
        assertTrue(differentHostError != null)
    }

    private class FakeSystemTrustManager(private val shouldTrust: Boolean) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            if (!shouldTrust) {
                throw CertificateException("Untrusted self-signed certificate")
            }
        }
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private class FakeX509Certificate(
        dn: String,
        private val rawEncoded: ByteArray
    ) : X509Certificate() {
        private val principal = X500Principal(dn)
        private val now = Date()

        override fun getEncoded(): ByteArray = rawEncoded
        override fun getSubjectX500Principal(): X500Principal = principal
        override fun getIssuerX500Principal(): X500Principal = principal
        override fun getNotBefore(): Date = now
        override fun getNotAfter(): Date = Date(now.time + 86_400_000L)

        override fun checkValidity() = Unit
        override fun checkValidity(date: Date?) = Unit
        override fun getVersion(): Int = 3
        override fun getSerialNumber(): BigInteger = BigInteger.ONE
        @Suppress("DEPRECATION")
        @Deprecated("Deprecated in Java")
        override fun getIssuerDN(): Principal = principal
        @Suppress("DEPRECATION")
        @Deprecated("Deprecated in Java")
        override fun getSubjectDN(): Principal = principal
        override fun getSigAlgName(): String = "SHA256withRSA"
        override fun getSigAlgOID(): String = "1.2.840.113549.1.1.11"
        override fun getSigAlgParams(): ByteArray? = null
        override fun getIssuerUniqueID(): BooleanArray? = null
        override fun getSubjectUniqueID(): BooleanArray? = null
        override fun getKeyUsage(): BooleanArray? = null
        override fun getBasicConstraints(): Int = -1
        override fun getTBSCertificate(): ByteArray = rawEncoded
        override fun getSignature(): ByteArray = ByteArray(0)
        override fun verify(key: PublicKey?) = Unit
        override fun verify(key: PublicKey?, sigProvider: String?) = Unit
        override fun toString(): String = principal.name
        override fun getPublicKey(): PublicKey = throw UnsupportedOperationException()
        override fun hasUnsupportedCriticalExtension(): Boolean = false
        override fun getCriticalExtensionOIDs(): MutableSet<String> = mutableSetOf()
        override fun getNonCriticalExtensionOIDs(): MutableSet<String> = mutableSetOf()
        override fun getExtensionValue(oid: String?): ByteArray? = null
    }
}
