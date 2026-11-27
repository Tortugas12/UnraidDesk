package com.unraiddesk.app.core.network

import android.content.Context
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import com.unraiddesk.app.AppBranding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.Base64

/**
 * Coordinates 1-click Unraid scoped API key authorization via Chrome Custom Tabs
 * and an ephemeral localhost callback server, plus a manual copy-paste fallback.
 */
class ApiKeyAuthCoordinator {

    data class AuthSession(
        val serverSocket: ServerSocket,
        val stateNonce: String,
        val redirectUri: String,
        val authorizeUrl: String
    ) : AutoCloseable {
        override fun close() {
            runCatching { serverSocket.close() }
        }
    }

    fun generateStateNonce(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun startLocalhostAuthSession(baseUrl: String): AuthSession {
        val socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        socket.soTimeout = CALLBACK_TIMEOUT_MILLIS
        val state = generateStateNonce()
        val redirectUri = "http://localhost:${socket.localPort}/callback"
        val authorizeUrl = buildAuthorizeUrl(
            baseUrl = baseUrl,
            redirectUri = redirectUri,
            state = state
        )
        return AuthSession(
            serverSocket = socket,
            stateNonce = state,
            redirectUri = redirectUri,
            authorizeUrl = authorizeUrl
        )
    }

    fun buildAuthorizeUrl(
        baseUrl: String,
        redirectUri: String? = null,
        state: String? = null
    ): String {
        val cleanBase = baseUrl.trimEnd('/')
        val builder = "$cleanBase/ApiKeyAuthorize".toUri().buildUpon()
            .appendQueryParameter("name", AppBranding.API_KEY_CLIENT_NAME)
            .appendQueryParameter("description", AppBranding.API_KEY_CLIENT_DESCRIPTION)
            .appendQueryParameter("scopes", LEAST_PRIVILEGE_SCOPES)
        if (!redirectUri.isNullOrBlank()) {
            builder.appendQueryParameter("redirect_uri", redirectUri)
        }
        if (!state.isNullOrBlank()) {
            builder.appendQueryParameter("state", state)
        }
        return builder.build().toString()
    }

    fun launchCustomTab(context: Context, url: String) {
        val intent = CustomTabsIntent.Builder()
            .setShowTitle(true)
            .build()
        intent.launchUrl(context, url.toUri())
    }

    suspend fun awaitApiKeyCallback(session: AuthSession): String = withContext(Dispatchers.IO) {
        session.use { active ->
            try {
                active.serverSocket.accept().use { client ->
                    client.soTimeout = 5_000
                    val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                    val requestLine = reader.readLine().orEmpty()
                    val targetPath = requestLine.split(" ").getOrNull(1).orEmpty()
                    val queryParams = parseQueryParams(targetPath)

                    val returnedState = queryParams["state"].orEmpty()
                    val apiKey = queryParams["apiKey"]
                        ?: queryParams["api_key"]
                        ?: queryParams["key"]
                        ?: queryParams["token"]

                    val isValid = returnedState == active.stateNonce && !apiKey.isNullOrBlank()
                    val responseHtml = if (isValid) SUCCESS_HTML else ERROR_HTML
                    val statusLine = if (isValid) "HTTP/1.1 200 OK" else "HTTP/1.1 400 Bad Request"
                    val httpResponse = buildString {
                        append(statusLine).append("\r\n")
                        append("Content-Type: text/html; charset=UTF-8\r\n")
                        append("Content-Length: ").append(responseHtml.toByteArray(Charsets.UTF_8).size).append("\r\n")
                        append("Connection: close\r\n\r\n")
                        append(responseHtml)
                    }
                    client.getOutputStream().write(httpResponse.toByteArray(Charsets.UTF_8))
                    client.getOutputStream().flush()

                    if (!isValid) {
                        throw IllegalStateException(
                            "Authorization callback failed state verification or did not include an API key."
                        )
                    }
                    apiKey!!
                }
            } catch (e: SocketTimeoutException) {
                throw IllegalStateException("Authorization timed out after 180 seconds.", e)
            }
        }
    }

    internal fun parseQueryParams(pathWithQuery: String): Map<String, String> {
        val query = pathWithQuery.substringAfter("?", "")
        if (query.isEmpty()) return emptyMap()
        return query.split("&").mapNotNull { pair ->
            val idx = pair.indexOf('=')
            if (idx <= 0) return@mapNotNull null
            val key = URLDecoder.decode(pair.substring(0, idx), Charsets.UTF_8.name())
            val value = URLDecoder.decode(pair.substring(idx + 1), Charsets.UTF_8.name())
            key to value
        }.toMap()
    }

    companion object {
        const val LEAST_PRIVILEGE_SCOPES =
            "system:read,array:read,share:read,disk:read,docker:read,docker:update"
        private const val CALLBACK_TIMEOUT_MILLIS = 180_000

        private const val SUCCESS_HTML =
            "<!DOCTYPE html><html><body style='font-family:sans-serif;padding:24px'>" +
                "<h2>UnraidDesk Authorized</h2>" +
                "<p>You may close this tab and return to UnraidDesk.</p>" +
                "</body></html>"

        private const val ERROR_HTML =
            "<!DOCTYPE html><html><body style='font-family:sans-serif;padding:24px'>" +
                "<h2>Authorization Failed</h2>" +
                "<p>Invalid state or missing API key. Please try again in UnraidDesk.</p>" +
                "</body></html>"
    }
}
