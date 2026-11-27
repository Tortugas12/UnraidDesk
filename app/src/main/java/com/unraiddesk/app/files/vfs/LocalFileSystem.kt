package com.unraiddesk.app.files.vfs

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory
import kotlin.io.path.name

class LocalFileSystem(
    private val rootDir: Path,
    override val rootId: String = "local",
    override val displayName: String = "Device Storage"
) : FileSystemProvider {

    constructor(rootFile: File, rootId: String = "local", displayName: String = "Device Storage") :
        this(rootFile.toPath(), rootId, displayName)

    override val protocol: StorageProtocol = StorageProtocol.LOCAL

    override suspend fun list(path: String): List<FileEntry> = withContext(Dispatchers.IO) {
        val dir = PathGuard.resolveSafeLocalPath(rootDir, path)
        if (!Files.exists(dir) || !dir.isDirectory()) {
            return@withContext emptyList()
        }
        val relBase = PathGuard.normalizeRelativePath(path)
        Files.list(dir).use { stream ->
            stream.map { child ->
                val childName = child.name
                val childRel = if (relBase.isEmpty()) childName else "$relBase/$childName"
                val isDir = child.isDirectory()
                FileEntry(
                    name = childName,
                    path = childRel,
                    isDirectory = isDir,
                    sizeBytes = if (isDir) 0L else runCatching { child.fileSize() }.getOrDefault(0L),
                    modifiedMillis = runCatching { child.getLastModifiedTime().toMillis() }.getOrDefault(0L),
                    mimeType = null,
                    protocol = StorageProtocol.LOCAL
                )
            }.toList()
        }
    }

    override suspend fun createDirectory(parentPath: String, name: String): FileEntry = withContext(Dispatchers.IO) {
        val cleanName = PathGuard.validateSingleName(name)
        val parentRel = PathGuard.normalizeRelativePath(parentPath)
        val targetRel = if (parentRel.isEmpty()) cleanName else "$parentRel/$cleanName"
        val target = PathGuard.resolveSafeLocalPath(rootDir, targetRel)
        Files.createDirectories(target)
        FileEntry(
            name = cleanName,
            path = targetRel,
            isDirectory = true,
            sizeBytes = 0L,
            modifiedMillis = runCatching { target.getLastModifiedTime().toMillis() }.getOrDefault(System.currentTimeMillis()),
            mimeType = null,
            protocol = StorageProtocol.LOCAL
        )
    }

    override suspend fun rename(path: String, newName: String): FileEntry = withContext(Dispatchers.IO) {
        val cleanName = PathGuard.validateSingleName(newName)
        val source = PathGuard.resolveSafeLocalPath(rootDir, path)
        val normalized = PathGuard.normalizeRelativePath(path)
        val parentRel = normalized.substringBeforeLast('/', "")
        val destRel = if (parentRel.isEmpty()) cleanName else "$parentRel/$cleanName"
        val dest = PathGuard.resolveSafeLocalPath(rootDir, destRel)
        Files.move(source, dest)
        val isDir = dest.isDirectory()
        FileEntry(
            name = cleanName,
            path = destRel,
            isDirectory = isDir,
            sizeBytes = if (isDir) 0L else runCatching { dest.fileSize() }.getOrDefault(0L),
            modifiedMillis = runCatching { dest.getLastModifiedTime().toMillis() }.getOrDefault(System.currentTimeMillis()),
            mimeType = null,
            protocol = StorageProtocol.LOCAL
        )
    }

    override suspend fun delete(path: String): Unit = withContext(Dispatchers.IO) {
        val target = PathGuard.resolveSafeLocalPath(rootDir, path)
        if (!Files.exists(target)) return@withContext
        if (target.isDirectory()) {
            Files.walk(target).use { walk ->
                walk.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        } else {
            Files.deleteIfExists(target)
        }
    }

    override suspend fun openInputStream(path: String): InputStream = withContext(Dispatchers.IO) {
        val target = PathGuard.resolveSafeLocalPath(rootDir, path)
        Files.newInputStream(target, StandardOpenOption.READ)
    }

    override suspend fun openOutputStream(path: String, overwrite: Boolean): OutputStream = withContext(Dispatchers.IO) {
        val target = PathGuard.resolveSafeLocalPath(rootDir, path)
        target.parent?.let { Files.createDirectories(it) }
        if (overwrite) {
            Files.newOutputStream(
                target,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            )
        } else {
            Files.newOutputStream(
                target,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
            )
        }
    }

    override suspend fun exists(path: String): Boolean = withContext(Dispatchers.IO) {
        val target = PathGuard.resolveSafeLocalPath(rootDir, path)
        Files.exists(target)
    }
}
