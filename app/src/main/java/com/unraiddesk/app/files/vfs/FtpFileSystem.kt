package com.unraiddesk.app.files.vfs

import com.unraiddesk.app.core.security.LanVpnBlockedException
import com.unraiddesk.app.core.security.LanVpnGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Pure-JVM FTP / explicit FTPS file system provider backed by Apache Commons Net 3.11.1
 * with LanVpnGuard enforcement.
 */
class FtpFileSystem(
    private val host: String,
    private val port: Int = 21,
    private val useFtps: Boolean = true,
    private val username: String = "anonymous",
    private val password: String = "",
    private val lanVpnGuard: LanVpnGuard,
    override val rootId: String = "${if (useFtps) "ftps" else "ftp"}://$host:$port",
    override val displayName: String = host
) : FileSystemProvider {

    override val protocol: StorageProtocol = StorageProtocol.FTP

    private fun connectClient(): FTPClient {
        val guardResult = lanVpnGuard.validateHost(host)
        if (guardResult is LanVpnGuard.GuardResult.Blocked) {
            throw LanVpnBlockedException(guardResult.message)
        }

        val client: FTPClient = if (useFtps) FTPSClient(false) else FTPClient()
        client.connectTimeout = 10_000
        client.connect(host, port)
        if (!FTPReply.isPositiveCompletion(client.replyCode)) {
            client.disconnect()
            throw IOException("FTP connection refused (reply ${client.replyCode}).")
        }
        if (!client.login(username, password)) {
            client.disconnect()
            throw IOException("FTP login failed for user '$username'.")
        }
        if (client is FTPSClient) {
            client.execPBSZ(0)
            client.execPROT("P")
        }
        client.enterLocalPassiveMode()
        client.setFileType(FTP.BINARY_FILE_TYPE)
        return client
    }

    private inline fun <T> withClient(block: (FTPClient) -> T): T {
        val client = connectClient()
        return try {
            block(client)
        } finally {
            runCatching { client.logout() }
            runCatching { client.disconnect() }
        }
    }

    override suspend fun list(path: String): List<FileEntry> = withContext(Dispatchers.IO) {
        val normalized = PathGuard.normalizeRelativePath(path)
        val remoteDir = if (normalized.isEmpty()) "/" else "/$normalized"
        withClient { client ->
            client.listFiles(remoteDir).mapNotNull { ftpFile ->
                val name = ftpFile.name ?: return@mapNotNull null
                if (name == "." || name == "..") return@mapNotNull null
                val childRel = if (normalized.isEmpty()) name else "$normalized/$name"
                FileEntry(
                    name = name,
                    path = childRel,
                    isDirectory = ftpFile.isDirectory,
                    sizeBytes = if (ftpFile.isDirectory) 0L else ftpFile.size,
                    modifiedMillis = ftpFile.timestamp?.timeInMillis ?: 0L,
                    protocol = StorageProtocol.FTP
                )
            }
        }
    }

    override suspend fun createDirectory(parentPath: String, name: String): FileEntry = withContext(Dispatchers.IO) {
        val cleanName = PathGuard.validateSingleName(name)
        val parentNorm = PathGuard.normalizeRelativePath(parentPath)
        val childRel = if (parentNorm.isEmpty()) cleanName else "$parentNorm/$cleanName"
        withClient { client ->
            if (!client.makeDirectory("/$childRel")) {
                throw IOException("FTP MKD failed for '/$childRel': ${client.replyString}")
            }
        }
        FileEntry(
            name = cleanName,
            path = childRel,
            isDirectory = true,
            modifiedMillis = System.currentTimeMillis(),
            protocol = StorageProtocol.FTP
        )
    }

    override suspend fun rename(path: String, newName: String): FileEntry = withContext(Dispatchers.IO) {
        val cleanName = PathGuard.validateSingleName(newName)
        val normalized = PathGuard.normalizeRelativePath(path)
        val parentRel = normalized.substringBeforeLast('/', "")
        val destRel = if (parentRel.isEmpty()) cleanName else "$parentRel/$cleanName"
        withClient { client ->
            if (!client.rename("/$normalized", "/$destRel")) {
                throw IOException("FTP rename failed: ${client.replyString}")
            }
        }
        FileEntry(
            name = cleanName,
            path = destRel,
            isDirectory = false,
            modifiedMillis = System.currentTimeMillis(),
            protocol = StorageProtocol.FTP
        )
    }

    override suspend fun delete(path: String): Unit = withContext(Dispatchers.IO) {
        val normalized = PathGuard.normalizeRelativePath(path)
        if (normalized.isEmpty()) return@withContext
        withClient { client ->
            val remote = "/$normalized"
            if (!client.deleteFile(remote)) {
                client.removeDirectory(remote)
            }
        }
    }

    override suspend fun openInputStream(path: String): InputStream = withContext(Dispatchers.IO) {
        val normalized = PathGuard.normalizeRelativePath(path)
        val client = connectClient()
        val rawStream = client.retrieveFileStream("/$normalized")
            ?: run {
                runCatching { client.disconnect() }
                throw IOException("FTP RETR failed for '/$normalized': ${client.replyString}")
            }
        object : FilterInputStream(rawStream) {
            override fun close() {
                try {
                    super.close()
                    client.completePendingCommand()
                } finally {
                    runCatching { client.logout() }
                    runCatching { client.disconnect() }
                }
            }
        }
    }

    override suspend fun openOutputStream(path: String, overwrite: Boolean): OutputStream = withContext(Dispatchers.IO) {
        val normalized = PathGuard.normalizeRelativePath(path)
        if (!overwrite && exists(normalized)) {
            throw IOException("Destination already exists: $path")
        }
        val client = connectClient()
        val rawStream = client.storeFileStream("/$normalized")
            ?: run {
                runCatching { client.disconnect() }
                throw IOException("FTP STOR failed for '/$normalized': ${client.replyString}")
            }
        object : FilterOutputStream(rawStream) {
            override fun close() {
                try {
                    super.close()
                    if (!client.completePendingCommand()) {
                        throw IOException("FTP transfer completion failed: ${client.replyString}")
                    }
                } finally {
                    runCatching { client.logout() }
                    runCatching { client.disconnect() }
                }
            }
        }
    }

    override suspend fun exists(path: String): Boolean = withContext(Dispatchers.IO) {
        val normalized = PathGuard.normalizeRelativePath(path)
        if (normalized.isEmpty()) return@withContext true
        withClient { client ->
            client.listFiles("/$normalized").isNotEmpty() ||
                client.changeWorkingDirectory("/$normalized")
        }
    }
}
