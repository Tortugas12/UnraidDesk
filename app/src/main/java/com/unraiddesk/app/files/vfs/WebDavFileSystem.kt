package com.unraiddesk.app.files.vfs

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.StringReader
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * WebDAV / WebDAVS file system provider using OkHttp (protected by LanVpnGuard & TofuTrustManager)
 * and safe XmlPullParser for PROPFIND responses.
 */
class WebDavFileSystem(
    private val baseUrl: String,
    private val okHttpClient: OkHttpClient,
    private val username: String? = null,
    private val password: String? = null,
    override val rootId: String = baseUrl,
    override val displayName: String = baseUrl
) : FileSystemProvider {

    override val protocol: StorageProtocol = StorageProtocol.WEBDAV

    private val authHeader: String? = if (!username.isNullOrBlank()) {
        Credentials.basic(username, password.orEmpty())
    } else {
        null
    }

    override suspend fun list(path: String): List<FileEntry> = withContext(Dispatchers.IO) {
        val normalized = PathGuard.normalizeRelativePath(path)
        val url = buildUrl(normalized, trailingSlash = true)
        val req = requestBuilder(url)
            .header("Depth", "1")
            .method("PROPFIND", PROPFIND_BODY.toRequestBody(XML_MEDIA_TYPE))
            .build()

        okHttpClient.newCall(req).execute().use { response ->
            if (!response.isSuccessful && response.code != 207) {
                throw IOException("WebDAV PROPFIND failed: HTTP ${response.code}")
            }
            val xml = response.body?.string().orEmpty()
            parsePropfindResponse(xml, normalized)
        }
    }

    override suspend fun createDirectory(parentPath: String, name: String): FileEntry = withContext(Dispatchers.IO) {
        val cleanName = PathGuard.validateSingleName(name)
        val parentNorm = PathGuard.normalizeRelativePath(parentPath)
        val childRel = if (parentNorm.isEmpty()) cleanName else "$parentNorm/$cleanName"
        val req = requestBuilder(buildUrl(childRel, trailingSlash = true))
            .method("MKCOL", null)
            .build()

        okHttpClient.newCall(req).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("WebDAV MKCOL failed: HTTP ${response.code}")
            }
        }
        FileEntry(
            name = cleanName,
            path = childRel,
            isDirectory = true,
            modifiedMillis = System.currentTimeMillis(),
            protocol = StorageProtocol.WEBDAV
        )
    }

    override suspend fun rename(path: String, newName: String): FileEntry = withContext(Dispatchers.IO) {
        val cleanName = PathGuard.validateSingleName(newName)
        val normalized = PathGuard.normalizeRelativePath(path)
        val parentRel = normalized.substringBeforeLast('/', "")
        val destRel = if (parentRel.isEmpty()) cleanName else "$parentRel/$cleanName"

        val req = requestBuilder(buildUrl(normalized, trailingSlash = false))
            .header("Destination", buildUrl(destRel, trailingSlash = false))
            .header("Overwrite", "T")
            .method("MOVE", null)
            .build()

        okHttpClient.newCall(req).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("WebDAV MOVE failed: HTTP ${response.code}")
            }
        }
        FileEntry(
            name = cleanName,
            path = destRel,
            isDirectory = false,
            modifiedMillis = System.currentTimeMillis(),
            protocol = StorageProtocol.WEBDAV
        )
    }

    override suspend fun delete(path: String): Unit = withContext(Dispatchers.IO) {
        val normalized = PathGuard.normalizeRelativePath(path)
        val req = requestBuilder(buildUrl(normalized, trailingSlash = false))
            .delete()
            .build()
        okHttpClient.newCall(req).execute().use { response ->
            if (!response.isSuccessful && response.code != 404) {
                throw IOException("WebDAV DELETE failed: HTTP ${response.code}")
            }
        }
    }

    override suspend fun openInputStream(path: String): InputStream = withContext(Dispatchers.IO) {
        val normalized = PathGuard.normalizeRelativePath(path)
        val req = requestBuilder(buildUrl(normalized, trailingSlash = false))
            .get()
            .build()
        val response = okHttpClient.newCall(req).execute()
        if (!response.isSuccessful) {
            response.close()
            throw IOException("WebDAV GET failed: HTTP ${response.code}")
        }
        response.body?.byteStream() ?: run {
            response.close()
            throw IOException("Empty WebDAV response body")
        }
    }

    override suspend fun openOutputStream(path: String, overwrite: Boolean): OutputStream = withContext(Dispatchers.IO) {
        val normalized = PathGuard.normalizeRelativePath(path)
        if (!overwrite && exists(normalized)) {
            throw IOException("Destination already exists: $path")
        }
        val targetUrl = buildUrl(normalized, trailingSlash = false)
        val buffer = ByteArrayOutputStream()
        object : OutputStream() {
            private var closed = false
            override fun write(b: Int) {
                buffer.write(b)
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                buffer.write(b, off, len)
            }

            override fun close() {
                if (closed) return
                closed = true
                val bytes = buffer.toByteArray()
                val body = object : RequestBody() {
                    override fun contentType() = OCTET_STREAM
                    override fun contentLength(): Long = bytes.size.toLong()
                    override fun writeTo(sink: BufferedSink) {
                        sink.write(bytes)
                    }
                }
                val req = requestBuilder(targetUrl).put(body).build()
                okHttpClient.newCall(req).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("WebDAV PUT failed: HTTP ${response.code}")
                    }
                }
            }
        }
    }

    override suspend fun exists(path: String): Boolean = withContext(Dispatchers.IO) {
        val normalized = PathGuard.normalizeRelativePath(path)
        val req = requestBuilder(buildUrl(normalized, trailingSlash = false))
            .header("Depth", "0")
            .method("PROPFIND", PROPFIND_BODY.toRequestBody(XML_MEDIA_TYPE))
            .build()
        okHttpClient.newCall(req).execute().use { response ->
            response.isSuccessful || response.code == 207
        }
    }

    private fun requestBuilder(url: String): Request.Builder =
        Request.Builder().url(url).apply {
            authHeader?.let { header("Authorization", it) }
        }

    private fun buildUrl(normalizedRelative: String, trailingSlash: Boolean): String {
        val cleanBase = baseUrl.trimEnd('/')
        val full = if (normalizedRelative.isEmpty()) cleanBase else "$cleanBase/$normalizedRelative"
        return if (trailingSlash && !full.endsWith("/")) "$full/" else full
    }

    companion object {
        private val XML_MEDIA_TYPE = "application/xml; charset=utf-8".toMediaType()
        private val OCTET_STREAM = "application/octet-stream".toMediaType()
        private val PROPFIND_BODY = """
            <?xml version="1.0" encoding="utf-8" ?>
            <D:propfind xmlns:D="DAV:">
              <D:prop>
                <D:displayname/>
                <D:getcontentlength/>
                <D:getlastmodified/>
                <D:resourcetype/>
                <D:getcontenttype/>
              </D:prop>
            </D:propfind>
        """.trimIndent()

        fun parsePropfindResponse(xml: String, currentRelativePath: String): List<FileEntry> {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            }
            val doc = factory.newDocumentBuilder().parse(InputSource(StringReader(xml.trim())))
            val responseNodes = doc.getElementsByTagNameNS("*", "response")
            val results = mutableListOf<FileEntry>()
            val normalizedBase = PathGuard.normalizeRelativePath(currentRelativePath)
            val baseLast = normalizedBase.substringAfterLast('/')

            for (i in 0 until responseNodes.length) {
                val respEl = responseNodes.item(i) as? Element ?: continue
                val href = firstChildText(respEl, "href")
                val displayName = firstChildText(respEl, "displayname")
                val contentLength = firstChildText(respEl, "getcontentlength").toLongOrNull() ?: 0L
                val modifiedMillis = firstChildText(respEl, "getlastmodified")
                    .takeIf { it.isNotEmpty() }
                    ?.let(::parseHttpDate) ?: 0L
                val mimeType = firstChildText(respEl, "getcontenttype").ifEmpty { null }
                val isCollection = respEl.getElementsByTagNameNS("*", "collection").length > 0

                val decodedHref = runCatching {
                    URLDecoder.decode(href, Charsets.UTF_8.name())
                }.getOrDefault(href)
                val cleanHref = decodedHref.trimEnd('/')
                val segmentName = cleanHref.substringAfterLast('/')
                val resolvedName = displayName.ifEmpty { segmentName }

                val isSelfRow = cleanHref.isEmpty() ||
                    (normalizedBase.isNotEmpty() && segmentName == baseLast)

                if (resolvedName.isNotEmpty() && !isSelfRow) {
                    val entryPath = if (normalizedBase.isEmpty()) {
                        resolvedName
                    } else {
                        "$normalizedBase/$resolvedName"
                    }
                    results.add(
                        FileEntry(
                            name = resolvedName,
                            path = entryPath,
                            isDirectory = isCollection,
                            sizeBytes = if (isCollection) 0L else contentLength,
                            modifiedMillis = modifiedMillis,
                            mimeType = mimeType,
                            protocol = StorageProtocol.WEBDAV
                        )
                    )
                }
            }
            return results
        }

        private fun firstChildText(parent: Element, localName: String): String {
            val nodes = parent.getElementsByTagNameNS("*", localName)
            if (nodes.length == 0) return ""
            val node = nodes.item(0)
            return if (node.nodeType == Node.ELEMENT_NODE) node.textContent?.trim().orEmpty() else ""
        }

        private fun parseHttpDate(raw: String): Long {
            val formats = listOf(
                "EEE, dd MMM yyyy HH:mm:ss zzz",
                "yyyy-MM-dd'T'HH:mm:ss'Z'"
            )
            for (pattern in formats) {
                val parsed = runCatching {
                    SimpleDateFormat(pattern, Locale.US).parse(raw)?.time
                }.getOrNull()
                if (parsed != null) return parsed
            }
            return 0L
        }
    }
}
