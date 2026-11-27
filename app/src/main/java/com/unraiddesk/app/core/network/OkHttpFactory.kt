package com.unraiddesk.app.core.network

import com.unraiddesk.app.AppBranding
import com.unraiddesk.app.core.security.LanVpnGuard
import com.unraiddesk.app.core.security.LanVpnInterceptor
import com.unraiddesk.app.core.security.TofuTrustManager
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.internal.tls.OkHostnameVerifier
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext

/**
 * Builds OkHttpClient instances hardened with LanVpnGuard, TOFU TLS pinning, and branded User-Agent.
 */
class OkHttpFactory(
    private val lanVpnGuard: LanVpnGuard,
    private val tofuTrustManager: TofuTrustManager
) {
    fun createClient(
        connectTimeoutSeconds: Long = 10L,
        readTimeoutSeconds: Long = 15L,
        writeTimeoutSeconds: Long = 15L
    ): OkHttpClient {
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(tofuTrustManager), SecureRandom())
        }

        val tofuHostnameVerifier = HostnameVerifier { hostname, session ->
            if (OkHostnameVerifier.verify(hostname, session)) {
                return@HostnameVerifier true
            }
            val peerCert = session.peerCertificates.firstOrNull() as? X509Certificate
                ?: return@HostnameVerifier false
            tofuTrustManager.isHostPinned(hostname, peerCert)
        }

        val hostContextInterceptor = Interceptor { chain ->
            val request = chain.request()
            TofuTrustManager.withHost(request.url.host) {
                chain.proceed(request)
            }
        }

        val userAgentInterceptor = Interceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", AppBranding.USER_AGENT)
                .build()
            chain.proceed(request)
        }

        return OkHttpClient.Builder()
            .addInterceptor(LanVpnInterceptor(lanVpnGuard))
            .addInterceptor(userAgentInterceptor)
            .addInterceptor(hostContextInterceptor)
            .sslSocketFactory(sslContext.socketFactory, tofuTrustManager)
            .hostnameVerifier(tofuHostnameVerifier)
            .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(writeTimeoutSeconds, TimeUnit.SECONDS)
            .build()
    }
}
