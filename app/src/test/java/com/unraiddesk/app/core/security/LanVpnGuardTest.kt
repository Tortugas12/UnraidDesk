package com.unraiddesk.app.core.security

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

class LanVpnGuardTest {

    private fun guardWith(
        vpnActive: Boolean = false,
        dnsMap: Map<String, List<InetAddress>> = emptyMap()
    ): LanVpnGuard {
        val resolver = LanVpnGuard.DnsResolver { host ->
            dnsMap[host] ?: run {
                // Parse numeric IPs without network lookup; throw UnknownHostException for hostnames
                if (host.matches(Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+$")) || host.contains(":")) {
                    listOf(InetAddress.getByName(host))
                } else {
                    throw UnknownHostException(host)
                }
            }
        }
        return LanVpnGuard(
            dnsResolver = resolver,
            vpnStatusProvider = LanVpnGuard.VpnStatusProvider { vpnActive }
        )
    }

    @Test
    fun allowsRfc1918PrivateIpv4Ranges() {
        val guard = guardWith(vpnActive = false)
        listOf(
            "10.0.0.1",
            "10.255.255.254",
            "172.16.0.1",
            "172.31.255.254",
            "192.168.0.1",
            "192.168.1.100"
        ).forEach { ip ->
            val result = guard.validateHost(ip)
            assertTrue("Expected $ip to be allowed, got $result", result is LanVpnGuard.GuardResult.Allowed)
        }
    }

    @Test
    fun allowsTailscaleCgnatRangeAndBlocksAdjacentPublicRanges() {
        val guard = guardWith(vpnActive = false)
        listOf("100.64.0.1", "100.100.100.100", "100.127.255.254").forEach { ip ->
            val result = guard.validateHost(ip)
            assertTrue("Expected CGNAT $ip to be allowed, got $result", result is LanVpnGuard.GuardResult.Allowed)
        }

        listOf("100.63.255.254", "100.128.0.1").forEach { ip ->
            val result = guard.validateHost(ip)
            assertTrue("Expected non-CGNAT $ip to be blocked, got $result", result is LanVpnGuard.GuardResult.Blocked)
        }
    }

    @Test
    fun allowsMdnsLocalHostnamesUnlessResolvedToPublicIp() {
        val guard = guardWith(
            vpnActive = false,
            dnsMap = mapOf(
                "unraid.local" to listOf(InetAddress.getByName("192.168.1.50")),
                "spoofed.local" to listOf(InetAddress.getByName("8.8.8.8"))
            )
        )

        assertTrue(guard.validateHost("tower.local") is LanVpnGuard.GuardResult.Allowed)
        assertTrue(guard.validateHost("unraid.local") is LanVpnGuard.GuardResult.Allowed)
        assertTrue(guard.validateHost("spoofed.local") is LanVpnGuard.GuardResult.Blocked)
    }

    @Test
    fun allowsIpv6UlaAndLinkLocal() {
        val guard = guardWith(vpnActive = false)
        assertTrue(guard.validateHost("fd00::1") is LanVpnGuard.GuardResult.Allowed)
        assertTrue(guard.validateHost("fc00::1") is LanVpnGuard.GuardResult.Allowed)
        assertTrue(guard.validateHost("fe80::1") is LanVpnGuard.GuardResult.Allowed)
    }

    @Test
    fun blocksPublicIpsWhenVpnIsInactive() {
        val guard = guardWith(
            vpnActive = false,
            dnsMap = mapOf(
                "public.example.com" to listOf(InetAddress.getByName("93.184.216.34"))
            )
        )

        listOf("8.8.8.8", "1.1.1.1", "172.32.0.1", "public.example.com").forEach { host ->
            val result = guard.validateHost(host)
            assertTrue("Expected $host to be blocked, got $result", result is LanVpnGuard.GuardResult.Blocked)
        }
        val blocked = guard.validateHost("8.8.8.8") as LanVpnGuard.GuardResult.Blocked
        assertEquals(LanVpnGuard.PUBLIC_BLOCKED_MESSAGE, blocked.message)
    }

    @Test
    fun allowsPublicOrVpnRoutedHostsWhenVpnIsActive() {
        val guard = guardWith(
            vpnActive = true,
            dnsMap = mapOf(
                "my-unraid.ts.net" to listOf(InetAddress.getByName("100.88.10.5")),
                "vpn-gateway.example.com" to listOf(InetAddress.getByName("8.8.8.8"))
            )
        )

        assertTrue(guard.validateHost("my-unraid.ts.net") is LanVpnGuard.GuardResult.Allowed)
        assertTrue(guard.validateHost("vpn-gateway.example.com") is LanVpnGuard.GuardResult.Allowed)
        assertTrue(guard.validateHost("8.8.8.8") is LanVpnGuard.GuardResult.Allowed)
    }

    @Test
    fun interceptorBlocksPublicHttpRequestsBeforeNetworkIo() {
        val guard = guardWith(vpnActive = false)
        val client = OkHttpClient.Builder()
            .addInterceptor(LanVpnInterceptor(guard))
            .build()

        val request = Request.Builder()
            .url("https://8.8.8.8/graphql")
            .build()

        var thrown: Throwable? = null
        try {
            client.newCall(request).execute()
        } catch (e: LanVpnBlockedException) {
            thrown = e
        }
        assertTrue(thrown is LanVpnBlockedException)
    }
}
