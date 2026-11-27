package com.unraiddesk.app.files.vfs

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.protocol.commons.EnumWithValue
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.unraiddesk.app.core.security.LanVpnBlockedException
import com.unraiddesk.app.core.security.LanVpnGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/**
 * Pure-JVM SMB 2/3 client powered by smbj 0.15.0 with connection pooling and LanVpnGuard enforcement.
 */
class SmbFileSystem(
    private val host: String,
    private val shareName: String,
    private val port: Int = 445,
    private val lanVpnGuard: LanVpnGuard,
    private val credentialProvider: () -> SmbCredentials?,
    override val rootId: String = "smb://$host/$shareName",
    override val displayName: String = shareName
) : FileSystemProvider, Closeable {

    data class SmbCredentials(
        val username: String,
        val password: String,
        val domain: String? = null
    )

    override val protocol: StorageProtocol = StorageProtocol.SMB

    private val client = SMBClient(
        SmbConfig.builder()
            .withTimeout(15, TimeUnit.SECONDS)
            .withSoTimeout(20, TimeUnit.SECONDS)
            .build()
    )

    private val poolMutex = Mutex()
    private var connection: Connection? = null
    private var session: Session? = null
    private var diskShare: DiskShare? = null

    private suspend fun getShare(): DiskShare = poolMutex.withLock {
        val guardResult = lanVpnGuard.validateHost(host)
        if (guardResult is LanVpnGuard.GuardResult.Blocked) {
            throw LanVpnBlockedException(guardResult.message)
        }

        val existingShare = diskShare
        if (existingShare != null && existingShare.isConnected && connection?.isConnected == true) {
            return existingShare
        }

        closePoolLocked()

        val conn = client.connect(host, port)
        val creds = credentialProvider()
        val auth = if (creds == null || creds.username.isBlank()) {
            AuthenticationContext.anonymous()
        } else {
            AuthenticationContext(creds.username, creds.password.toCharArray(), creds.domain.orEmpty())
        }
        val sess = conn.authenticate(auth)
        val share = sess.connectShare(shareName) as DiskShare

        connection = conn
        session = sess
        diskShare = share
        share
    }

    override suspend fun list(path: String): List<FileEntry> = withContext(Dispatchers.IO) {
        val share = getShare()
        val normalized = PathGuard.normalizeRelativePath(path)
        val smbPath = toSmbPath(normalized)
        share.list(smbPath).mapNotNull { info ->
            val name = info.fileName
            if (name == "." || name == "..") return@mapNotNull null
            val isDir = EnumWithValue.EnumUtils.isSet(
                info.fileAttributes,
                FileAttributes.FILE_ATTRIBUTE_DIRECTORY
            )
            val childPath = if (normalized.isEmpty()) name else "$normalized/$name"
            FileEntry(
                name = name,
                path = childPath,
                isDirectory = isDir,
                sizeBytes = if (isDir) 0L else info.endOfFile,
                modifiedMillis = info.changeTime.toEpochMillis(),
                mimeType = null,
                protocol = StorageProtocol.SMB
            )
        }
    }

    override suspend fun createDirectory(parentPath: String, name: String): FileEntry = withContext(Dispatchers.IO) {
        val share = getShare()
        val cleanName = PathGuard.validateSingleName(name)
        val parentNorm = PathGuard.normalizeRelativePath(parentPath)
        val childRel = if (parentNorm.isEmpty()) cleanName else "$parentNorm/$cleanName"
        share.mkdir(toSmbPath(childRel))
        FileEntry(
            name = cleanName,
            path = childRel,
            isDirectory = true,
            modifiedMillis = System.currentTimeMillis(),
            protocol = StorageProtocol.SMB
        )
    }

    override suspend fun rename(path: String, newName: String): FileEntry = withContext(Dispatchers.IO) {
        val share = getShare()
        val cleanName = PathGuard.validateSingleName(newName)
        val normalized = PathGuard.normalizeRelativePath(path)
        val smbSrc = toSmbPath(normalized)
        val parentRel = normalized.substringBeforeLast('/', "")
        val destRel = if (parentRel.isEmpty()) cleanName else "$parentRel/$cleanName"
        val smbDest = toSmbPath(destRel)

        val isDir = share.folderExists(smbSrc)
        if (isDir) {
            share.openDirectory(
                smbSrc,
                EnumSet.of(AccessMask.DELETE, AccessMask.GENERIC_WRITE),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null
            ).use { dir ->
                dir.rename(smbDest, true)
            }
        } else {
            share.openFile(
                smbSrc,
                EnumSet.of(AccessMask.DELETE, AccessMask.GENERIC_WRITE),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null
            ).use { file ->
                file.rename(smbDest, true)
            }
        }
        FileEntry(
            name = cleanName,
            path = destRel,
            isDirectory = isDir,
            modifiedMillis = System.currentTimeMillis(),
            protocol = StorageProtocol.SMB
        )
    }

    override suspend fun delete(path: String): Unit = withContext(Dispatchers.IO) {
        val share = getShare()
        val smbPath = toSmbPath(PathGuard.normalizeRelativePath(path))
        if (smbPath.isEmpty()) return@withContext
        if (share.folderExists(smbPath)) {
            share.rmdir(smbPath, true)
        } else if (share.fileExists(smbPath)) {
            share.rm(smbPath)
        }
    }

    override suspend fun openInputStream(path: String): InputStream = withContext(Dispatchers.IO) {
        val share = getShare()
        val smbPath = toSmbPath(PathGuard.normalizeRelativePath(path))
        val smbFile = share.openFile(
            smbPath,
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null
        )
        object : FilterInputStream(smbFile.inputStream) {
            override fun close() {
                try {
                    super.close()
                } finally {
                    runCatching { smbFile.close() }
                }
            }
        }
    }

    override suspend fun openOutputStream(path: String, overwrite: Boolean): OutputStream = withContext(Dispatchers.IO) {
        val share = getShare()
        val smbPath = toSmbPath(PathGuard.normalizeRelativePath(path))
        val disposition = if (overwrite) {
            SMB2CreateDisposition.FILE_OVERWRITE_IF
        } else {
            SMB2CreateDisposition.FILE_CREATE
        }
        val smbFile = share.openFile(
            smbPath,
            EnumSet.of(AccessMask.GENERIC_WRITE),
            null,
            SMB2ShareAccess.ALL,
            disposition,
            null
        )
        object : FilterOutputStream(smbFile.outputStream) {
            override fun close() {
                try {
                    super.close()
                } finally {
                    runCatching { smbFile.close() }
                }
            }
        }
    }

    override suspend fun exists(path: String): Boolean = withContext(Dispatchers.IO) {
        val share = getShare()
        val smbPath = toSmbPath(PathGuard.normalizeRelativePath(path))
        if (smbPath.isEmpty()) true else share.fileExists(smbPath) || share.folderExists(smbPath)
    }

    override fun close() {
        closePoolLocked()
        runCatching { client.close() }
    }

    private fun closePoolLocked() {
        runCatching { diskShare?.close() }
        runCatching { session?.close() }
        runCatching { connection?.close() }
        diskShare = null
        session = null
        connection = null
    }

    private fun toSmbPath(normalizedRelative: String): String =
        normalizedRelative.replace('/', '\\')
}
