package com.unraiddesk.app.core.security

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Enforces LAN / VPN-only network access before any GraphQL, SMB, WebDAV, FTP, or WebGUI call.
 */
class LanVpnGuard(
    private val dnsResolver: DnsResolver = SystemDnsResolver,
    private val vpnStatusProvider: VpnStatusProvider
) {
    constructor(context: Context) : this(
        dnsResolver = SystemDnsResolver,
        vpnStatusProvider = AndroidVpnStatusProvider(context.applicationContext)
    )

    sealed class GuardResult {
        data class Allowed(val reason: String) : GuardResult()
        data class Blocked(val message: String) : GuardResult()
    }

    fun validateUrl(url: String): GuardResult {
        val parsed = url.trim().toHttpUrlOrNull()
            ?: return GuardResult.Blocked("Invalid URL. Enter a valid LAN or VPN server address.")
        return validateHost(parsed.host)
    }

    fun validateUrl(url: HttpUrl): GuardResult = validateHost(url.host)

    fun validateHost(rawHost: String): GuardResult {
        val host = rawHost.trim().removePrefix("[").removeSuffix("]")
        if (host.isEmpty()) {
            return GuardResult.Blocked("Server host cannot be empty.")
        }

        val isMdnsLocal = host.endsWith(".local", ignoreCase = true)
        val addresses = try {
            dnsResolver.resolve(host)
        } catch (_: UnknownHostException) {
            if (isMdnsLocal) {
                return GuardResult.Allowed("mDNS .local hostname")
            }
            if (vpnStatusProvider.isVpnActive()) {
                return GuardResult.Allowed("Active VPN transport")
            }
            return GuardResult.Blocked(
                "Unable to resolve '$host'. Connect to your LAN or VPN and verify the hostname."
            )
        }

        if (addresses.isEmpty()) {
            return if (isMdnsLocal) {
                GuardResult.Allowed("mDNS .local hostname")
            } else {
                GuardResult.Blocked("No addresses resolved for '$host'.")
            }
        }

        val allPrivateOrLocal = addresses.all { isPrivateOrLocalAddress(it) }
        if (allPrivateOrLocal) {
            return GuardResult.Allowed("Private LAN / Tailscale / local address")
        }

        if (vpnStatusProvider.isVpnActive()) {
            return GuardResult.Allowed("Active VPN transport")
        }

        return GuardResult.Blocked(PUBLIC_BLOCKED_MESSAGE)
    }

    internal fun isPrivateOrLocalAddress(address: InetAddress): Boolean {
        if (address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress) {
            return true
        }
        return when (address) {
            is Inet4Address -> isAllowedIpv4(address.address)
            is Inet6Address -> isAllowedIpv6(address.address)
            else -> false
        }
    }

    private fun isAllowedIpv4(bytes: ByteArray): Boolean {
        if (bytes.size != 4) return false
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF

        // 10.0.0.0/8 (RFC 1918)
        if (b0 == 10) return true
        // 172.16.0.0/12 (RFC 1918)
        if (b0 == 172 && b1 in 16..31) return true
        // 192.168.0.0/16 (RFC 1918)
        if (b0 == 192 && b1 == 168) return true
        // 100.64.0.0/10 (CGNAT / Tailscale)
        if (b0 == 100 && b1 in 64..127) return true
        // 169.254.0.0/16 (Link-local)
        if (b0 == 169 && b1 == 254) return true
        // 127.0.0.0/8 (Loopback)
        if (b0 == 127) return true

        return false
    }

    private fun isAllowedIpv6(bytes: ByteArray): Boolean {
        if (bytes.size != 16) return false
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF

        // fc00::/7 (Unique Local Address)
        if ((b0 and 0xFE) == 0xFC) return true
        // fe80::/10 (Link-local)
        if (b0 == 0xFE && (b1 and 0xC0) == 0x80) return true

        return false
    }

    fun interface DnsResolver {
        @Throws(UnknownHostException::class)
        fun resolve(host: String): List<InetAddress>
    }

    fun interface VpnStatusProvider {
        fun isVpnActive(): Boolean
    }

    private object SystemDnsResolver : DnsResolver {
        override fun resolve(host: String): List<InetAddress> =
            InetAddress.getAllByName(host).toList()
    }

    private class AndroidVpnStatusProvider(
        private val context: Context
    ) : VpnStatusProvider {
        override fun isVpnActive(): Boolean {
            val connectivityManager =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                    ?: return false
            val activeNetwork = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
            return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
    }

    companion object {
        const val PUBLIC_BLOCKED_MESSAGE =
            "Public endpoints are blocked for security. Connect to your LAN or VPN."
    }
}

class LanVpnBlockedException(message: String) : IOException(message)

class LanVpnInterceptor(
    private val guard: LanVpnGuard
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        when (val result = guard.validateUrl(request.url)) {
            is LanVpnGuard.GuardResult.Allowed -> return chain.proceed(request)
            is LanVpnGuard.GuardResult.Blocked -> throw LanVpnBlockedException(result.message)
        }
    }
}
