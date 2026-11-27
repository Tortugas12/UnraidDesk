package com.unraiddesk.app.files.vfs

import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path

enum class StorageProtocol {
    LOCAL,
    SAF,
    SMB,
    WEBDAV,
    FTP
}

data class FileEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long = 0L,
    val modifiedMillis: Long = 0L,
    val mimeType: String? = null,
    val protocol: StorageProtocol
)

interface FileSystemProvider {
    val protocol: StorageProtocol
    val rootId: String
    val displayName: String

    suspend fun list(path: String): List<FileEntry>
    suspend fun createDirectory(parentPath: String, name: String): FileEntry
    suspend fun rename(path: String, newName: String): FileEntry
    suspend fun delete(path: String)
    suspend fun openInputStream(path: String): InputStream
    suspend fun openOutputStream(path: String, overwrite: Boolean = true): OutputStream
    suspend fun exists(path: String): Boolean
}

/**
 * Prevents `..` directory traversal, null-byte injection, and symlink escapes across all VFS engines.
 */
object PathGuard {

    fun normalizeRelativePath(rawPath: String): String {
        if (rawPath.indexOf('\u0000') >= 0) {
            throw SecurityException("Null byte in path is not allowed.")
        }
        val unified = rawPath.replace('\\', '/').trim()
        if (unified.isEmpty() || unified == "/") {
            return ""
        }
        val segments = unified.split('/')
        val clean = mutableListOf<String>()
        for (seg in segments) {
            if (seg.isEmpty() || seg == ".") continue
            if (seg == "..") {
                throw SecurityException("Path traversal ('..') is not allowed.")
            }
            clean.add(seg)
        }
        return clean.joinToString("/")
    }

    fun validateSingleName(name: String): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed == "." || trimmed == "..") {
            throw SecurityException("Invalid file or folder name: '$name'")
        }
        if (trimmed.contains('/') || trimmed.contains('\\') || trimmed.indexOf('\u0000') >= 0) {
            throw SecurityException("File or folder name cannot contain path separators.")
        }
        return trimmed
    }

    fun resolveSafeLocalPath(rootDir: Path, rawRelativePath: String): Path {
        val realRoot = rootDir.toRealPath()
        val normalized = normalizeRelativePath(rawRelativePath)
        if (normalized.isEmpty()) {
            return realRoot
        }

        val candidate = realRoot.resolve(normalized).normalize()
        if (!candidate.startsWith(realRoot)) {
            throw SecurityException("Resolved path escapes root directory.")
        }

        // Verify no existing path prefix or target is a symlink escaping realRoot
        var probe = realRoot
        for (part in candidate.relativize(realRoot).let { candidate.subpath(realRoot.nameCount, candidate.nameCount) }) {
            probe = probe.resolve(part)
            if (Files.isSymbolicLink(probe)) {
                val target = probe.toRealPath()
                if (!target.startsWith(realRoot)) {
                    throw SecurityException("Symlink escape outside root directory is blocked.")
                }
            } else if (Files.exists(probe)) {
                val resolved = probe.toRealPath()
                if (!resolved.startsWith(realRoot)) {
                    throw SecurityException("Path escapes root directory.")
                }
            }
        }
        return candidate
    }
}
