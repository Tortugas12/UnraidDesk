package com.unraiddesk.app.files.transfer

import com.unraiddesk.app.files.vfs.FileEntry
import com.unraiddesk.app.files.vfs.LocalFileSystem
import com.unraiddesk.app.files.vfs.StorageProtocol
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class TransferQueueManagerTest {

    @Test
    fun copyWithRenameConflictAndMoveBetweenLocalRoots() = runBlocking {
        val srcRoot = Files.createTempDirectory("vfs_src")
        val dstRoot = Files.createTempDirectory("vfs_dst")
        try {
            val srcFs = LocalFileSystem(srcRoot, rootId = "src", displayName = "Source")
            val dstFs = LocalFileSystem(dstRoot, rootId = "dst", displayName = "Dest")

            srcRoot.resolve("notes.txt").toFile().writeText("new-content")
            dstRoot.resolve("notes.txt").toFile().writeText("existing-content")

            val entry = FileEntry(
                name = "notes.txt",
                path = "notes.txt",
                isDirectory = false,
                sizeBytes = 11L,
                protocol = StorageProtocol.LOCAL
            )

            val manager = TransferQueueManager()
            val copyJobId = manager.enqueueCopy(
                sourceProvider = srcFs,
                sourceEntry = entry,
                destProvider = dstFs,
                destDirectoryPath = "",
                conflictPolicy = ConflictPolicy.RENAME
            )

            // Wait for background IO coroutine completion
            repeat(50) {
                val state = manager.jobs.value.first { it.id == copyJobId }
                if (state.status == TransferStatus.COMPLETED) return@repeat
                delay(20)
            }

            assertEquals(TransferStatus.COMPLETED, manager.jobs.value.first { it.id == copyJobId }.status)
            assertEquals("existing-content", dstRoot.resolve("notes.txt").toFile().readText())
            assertEquals("new-content", dstRoot.resolve("notes (1).txt").toFile().readText())
            assertTrue(Files.exists(srcRoot.resolve("notes.txt")))

            // Now test move with OVERWRITE
            val moveJobId = manager.enqueueMove(
                sourceProvider = srcFs,
                sourceEntry = entry,
                destProvider = dstFs,
                destDirectoryPath = "",
                conflictPolicy = ConflictPolicy.OVERWRITE
            )
            repeat(50) {
                val state = manager.jobs.value.first { it.id == moveJobId }
                if (state.status == TransferStatus.COMPLETED) return@repeat
                delay(20)
            }

            assertEquals(TransferStatus.COMPLETED, manager.jobs.value.first { it.id == moveJobId }.status)
            assertEquals("new-content", dstRoot.resolve("notes.txt").toFile().readText())
            assertFalse(Files.exists(srcRoot.resolve("notes.txt")))
        } finally {
            srcRoot.toFile().deleteRecursively()
            dstRoot.toFile().deleteRecursively()
        }
    }
}
