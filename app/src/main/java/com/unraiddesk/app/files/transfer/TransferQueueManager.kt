package com.unraiddesk.app.files.transfer

import com.unraiddesk.app.files.vfs.FileEntry
import com.unraiddesk.app.files.vfs.FileSystemProvider
import com.unraiddesk.app.files.vfs.PathGuard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

enum class ConflictPolicy {
    OVERWRITE,
    SKIP,
    RENAME
}

enum class TransferStatus {
    QUEUED,
    RUNNING,
    COMPLETED,
    SKIPPED,
    FAILED,
    CANCELLED
}

data class TransferJobState(
    val id: String,
    val fileName: String,
    val sourcePath: String,
    val destDirectoryPath: String,
    val isMove: Boolean,
    val conflictPolicy: ConflictPolicy,
    val status: TransferStatus,
    val bytesTransferred: Long,
    val totalBytes: Long,
    val errorMessage: String? = null
) {
    val progressFraction: Float
        get() = if (totalBytes > 0L) {
            (bytesTransferred.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        } else if (status == TransferStatus.COMPLETED) {
            1f
        } else {
            0f
        }
}

/**
 * Cross-protocol streaming copy/move transfer queue with progress StateFlow,
 * cancellation, and OVERWRITE / SKIP / RENAME conflict handling.
 */
class TransferQueueManager(
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val activeCoroutines = mutableMapOf<String, Job>()

    private val _jobs = MutableStateFlow<List<TransferJobState>>(emptyList())
    val jobs: StateFlow<List<TransferJobState>> = _jobs.asStateFlow()

    fun enqueueCopy(
        sourceProvider: FileSystemProvider,
        sourceEntry: FileEntry,
        destProvider: FileSystemProvider,
        destDirectoryPath: String,
        conflictPolicy: ConflictPolicy = ConflictPolicy.RENAME
    ): String = enqueueInternal(
        sourceProvider = sourceProvider,
        sourceEntry = sourceEntry,
        destProvider = destProvider,
        destDirectoryPath = destDirectoryPath,
        isMove = false,
        conflictPolicy = conflictPolicy
    )

    fun enqueueMove(
        sourceProvider: FileSystemProvider,
        sourceEntry: FileEntry,
        destProvider: FileSystemProvider,
        destDirectoryPath: String,
        conflictPolicy: ConflictPolicy = ConflictPolicy.RENAME
    ): String = enqueueInternal(
        sourceProvider = sourceProvider,
        sourceEntry = sourceEntry,
        destProvider = destProvider,
        destDirectoryPath = destDirectoryPath,
        isMove = true,
        conflictPolicy = conflictPolicy
    )

    fun cancel(jobId: String) {
        activeCoroutines[jobId]?.cancel()
        updateJob(jobId) { it.copy(status = TransferStatus.CANCELLED) }
    }

    fun clearCompleted() {
        _jobs.update { list ->
            list.filterNot {
                it.status == TransferStatus.COMPLETED ||
                    it.status == TransferStatus.SKIPPED ||
                    it.status == TransferStatus.CANCELLED
            }
        }
    }

    private fun enqueueInternal(
        sourceProvider: FileSystemProvider,
        sourceEntry: FileEntry,
        destProvider: FileSystemProvider,
        destDirectoryPath: String,
        isMove: Boolean,
        conflictPolicy: ConflictPolicy
    ): String {
        val jobId = UUID.randomUUID().toString()
        val initial = TransferJobState(
            id = jobId,
            fileName = sourceEntry.name,
            sourcePath = sourceEntry.path,
            destDirectoryPath = PathGuard.normalizeRelativePath(destDirectoryPath),
            isMove = isMove,
            conflictPolicy = conflictPolicy,
            status = TransferStatus.QUEUED,
            bytesTransferred = 0L,
            totalBytes = sourceEntry.sizeBytes
        )
        _jobs.update { it + initial }

        val coroutineJob = scope.launch {
            executeTransfer(
                jobId = jobId,
                sourceProvider = sourceProvider,
                sourceEntry = sourceEntry,
                destProvider = destProvider,
                destDir = initial.destDirectoryPath,
                isMove = isMove,
                conflictPolicy = conflictPolicy
            )
        }
        activeCoroutines[jobId] = coroutineJob
        return jobId
    }

    private suspend fun executeTransfer(
        jobId: String,
        sourceProvider: FileSystemProvider,
        sourceEntry: FileEntry,
        destProvider: FileSystemProvider,
        destDir: String,
        isMove: Boolean,
        conflictPolicy: ConflictPolicy
    ) {
        updateJob(jobId) { it.copy(status = TransferStatus.RUNNING) }
        try {
            if (sourceEntry.isDirectory) {
                transferDirectory(
                    jobId = jobId,
                    sourceProvider = sourceProvider,
                    sourceDirPath = sourceEntry.path,
                    destProvider = destProvider,
                    destParentPath = destDir,
                    dirName = sourceEntry.name,
                    isMove = isMove,
                    conflictPolicy = conflictPolicy
                )
            } else {
                val targetPath = resolveTargetPath(
                    destProvider = destProvider,
                    destDir = destDir,
                    fileName = sourceEntry.name,
                    conflictPolicy = conflictPolicy
                )
                if (targetPath == null) {
                    updateJob(jobId) { it.copy(status = TransferStatus.SKIPPED) }
                    return
                }
                streamFile(
                    jobId = jobId,
                    sourceProvider = sourceProvider,
                    sourcePath = sourceEntry.path,
                    destProvider = destProvider,
                    destPath = targetPath
                )
                if (isMove) {
                    sourceProvider.delete(sourceEntry.path)
                }
            }
            updateJob(jobId) { it.copy(status = TransferStatus.COMPLETED) }
        } catch (_: CancellationException) {
            updateJob(jobId) { it.copy(status = TransferStatus.CANCELLED) }
        } catch (e: Exception) {
            updateJob(jobId) {
                it.copy(
                    status = TransferStatus.FAILED,
                    errorMessage = e.message ?: "Transfer failed"
                )
            }
        } finally {
            activeCoroutines.remove(jobId)
        }
    }

    private suspend fun transferDirectory(
        jobId: String,
        sourceProvider: FileSystemProvider,
        sourceDirPath: String,
        destProvider: FileSystemProvider,
        destParentPath: String,
        dirName: String,
        isMove: Boolean,
        conflictPolicy: ConflictPolicy
    ) {
        val created = destProvider.createDirectory(destParentPath, dirName)
        val children = sourceProvider.list(sourceDirPath)
        for (child in children) {
            withContext(Dispatchers.IO) { coroutineContext.ensureActive() }
            if (child.isDirectory) {
                transferDirectory(
                    jobId = jobId,
                    sourceProvider = sourceProvider,
                    sourceDirPath = child.path,
                    destProvider = destProvider,
                    destParentPath = created.path,
                    dirName = child.name,
                    isMove = isMove,
                    conflictPolicy = conflictPolicy
                )
            } else {
                val targetPath = resolveTargetPath(
                    destProvider = destProvider,
                    destDir = created.path,
                    fileName = child.name,
                    conflictPolicy = conflictPolicy
                ) ?: continue
                streamFile(
                    jobId = jobId,
                    sourceProvider = sourceProvider,
                    sourcePath = child.path,
                    destProvider = destProvider,
                    destPath = targetPath
                )
            }
        }
        if (isMove) {
            sourceProvider.delete(sourceDirPath)
        }
    }

    private suspend fun resolveTargetPath(
        destProvider: FileSystemProvider,
        destDir: String,
        fileName: String,
        conflictPolicy: ConflictPolicy
    ): String? {
        val cleanName = PathGuard.validateSingleName(fileName)
        val candidate = if (destDir.isEmpty()) cleanName else "$destDir/$cleanName"
        if (!destProvider.exists(candidate)) {
            return candidate
        }
        return when (conflictPolicy) {
            ConflictPolicy.OVERWRITE -> candidate
            ConflictPolicy.SKIP -> null
            ConflictPolicy.RENAME -> {
                val dotIdx = cleanName.lastIndexOf('.')
                val stem = if (dotIdx > 0) cleanName.substring(0, dotIdx) else cleanName
                val ext = if (dotIdx > 0) cleanName.substring(dotIdx) else ""
                var counter = 1
                var resolved: String? = null
                while (resolved == null) {
                    val renamed = "$stem ($counter)$ext"
                    val renamedPath = if (destDir.isEmpty()) renamed else "$destDir/$renamed"
                    if (!destProvider.exists(renamedPath)) {
                        resolved = renamedPath
                    } else {
                        counter++
                    }
                }
                resolved
            }
        }
    }

    private suspend fun streamFile(
        jobId: String,
        sourceProvider: FileSystemProvider,
        sourcePath: String,
        destProvider: FileSystemProvider,
        destPath: String
    ) = withContext(Dispatchers.IO) {
        val buffer = ByteArray(BUFFER_SIZE)
        sourceProvider.openInputStream(sourcePath).use { input ->
            destProvider.openOutputStream(destPath, overwrite = true).use { output ->
                while (true) {
                    coroutineContext.ensureActive()
                    val read = input.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    updateJob(jobId) { job ->
                        job.copy(bytesTransferred = job.bytesTransferred + read)
                    }
                }
                output.flush()
            }
        }
    }

    private fun updateJob(jobId: String, transform: (TransferJobState) -> TransferJobState) {
        _jobs.update { list ->
            list.map { if (it.id == jobId) transform(it) else it }
        }
    }

    companion object {
        private const val BUFFER_SIZE = 64 * 1024
    }
}
