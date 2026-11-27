package com.unraiddesk.app.core.security

import android.annotation.SuppressLint
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Trust-On-First-Use (TOFU) X509TrustManager.
 * Trusts system CAs first; if untrusted on LAN/VPN, checks SHA-256 fingerprint against pinned certs
 * or throws [TofuUntrustedCertException] so the UI can prompt the user for explicit approval.
 */
@SuppressLint("CustomX509TrustManager")
class TofuTrustManager(
    private val systemTrustManager: X509TrustManager = defaultSystemTrustManager(),
    private val pinnedCertStore: PinnedCertStore,
    private val hostProvider: () -> String? = { currentHost.get() }
) : X509TrustManager {

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        systemTrustManager.checkClientTrusted(chain, authType)
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        if (chain.isNullOrEmpty()) {
            throw CertificateException("Empty server certificate chain.")
        }

        try {
            systemTrustManager.checkServerTrusted(chain, authType)
            return
        } catch (systemError: CertificateException) {
            val leaf = chain[0]
            val fingerprint = sha256Fingerprint(leaf)
            val host = hostProvider()?.trim().orEmpty()

            if (host.isNotEmpty() && pinnedCertStore.isPinned(host, fingerprint)) {
                return
            }

            throw TofuUntrustedCertException(
                certInfo = UntrustedCertInfo(
                    host = host,
                    subject = leaf.subjectX500Principal.name,
                    issuer = leaf.issuerX500Principal.name,
                    sha256Fingerprint = fingerprint,
                    notBeforeMillis = leaf.notBefore.time,
                    notAfterMillis = leaf.notAfter.time,
                    encodedCert = leaf.encoded
                ),
                cause = systemError
            )
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> =
        systemTrustManager.acceptedIssuers

    fun isHostPinned(host: String, cert: X509Certificate): Boolean =
        pinnedCertStore.isPinned(host, sha256Fingerprint(cert))

    interface PinnedCertStore {
        fun isPinned(host: String, sha256Fingerprint: String): Boolean
        fun pin(host: String, sha256Fingerprint: String)
    }

    class InMemoryPinnedCertStore : PinnedCertStore {
        private val pins = mutableMapOf<String, String>()

        @Synchronized
        override fun isPinned(host: String, sha256Fingerprint: String): Boolean =
            pins[host.lowercase()] == sha256Fingerprint

        @Synchronized
        override fun pin(host: String, sha256Fingerprint: String) {
            pins[host.lowercase()] = sha256Fingerprint
        }
    }

    data class UntrustedCertInfo(
        val host: String,
        val subject: String,
        val issuer: String,
        val sha256Fingerprint: String,
        val notBeforeMillis: Long,
        val notAfterMillis: Long,
        val encodedCert: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is UntrustedCertInfo) return false
            return host == other.host &&
                subject == other.subject &&
                issuer == other.issuer &&
                sha256Fingerprint == other.sha256Fingerprint &&
                notBeforeMillis == other.notBeforeMillis &&
                notAfterMillis == other.notAfterMillis &&
                encodedCert.contentEquals(other.encodedCert)
        }

        override fun hashCode(): Int {
            var result = host.hashCode()
            result = 31 * result + subject.hashCode()
            result = 31 * result + issuer.hashCode()
            result = 31 * result + sha256Fingerprint.hashCode()
            result = 31 * result + notBeforeMillis.hashCode()
            result = 31 * result + notAfterMillis.hashCode()
            result = 31 * result + encodedCert.contentHashCode()
            return result
        }
    }

    class TofuUntrustedCertException(
        val certInfo: UntrustedCertInfo,
        cause: Throwable? = null
    ) : CertificateException(
        "Untrusted certificate for '${certInfo.host}' (SHA-256: ${certInfo.sha256Fingerprint})",
        cause
    )

    companion object {
        private val currentHost = ThreadLocal<String?>()

        fun <T> withHost(host: String, block: () -> T): T {
            val previous = currentHost.get()
            currentHost.set(host)
            return try {
                block()
            } finally {
                currentHost.set(previous)
            }
        }

        fun sha256Fingerprint(cert: X509Certificate): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
            return digest.joinToString(":") { byte -> "%02X".format(byte) }
        }

        fun defaultSystemTrustManager(): X509TrustManager {
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            factory.init(null as KeyStore?)
            return factory.trustManagers
                .filterIsInstance<X509TrustManager>()
                .first()
        }
    }
}
