package com.unraiddesk.app.files.vfs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class PathGuardTest {

    @Test
    fun normalizesValidRelativePaths() {
        assertEquals("", PathGuard.normalizeRelativePath(""))
        assertEquals("", PathGuard.normalizeRelativePath("/"))
        assertEquals("movies/4k/clip.mkv", PathGuard.normalizeRelativePath("/movies//./4k/clip.mkv"))
        assertEquals("docs/readme.txt", PathGuard.normalizeRelativePath("docs\\readme.txt"))
    }

    @Test(expected = SecurityException::class)
    fun blocksDotDotTraversalInRelativePath() {
        PathGuard.normalizeRelativePath("movies/../../etc/passwd")
    }

    @Test(expected = SecurityException::class)
    fun blocksNullByteInjection() {
        PathGuard.normalizeRelativePath("movies/file.txt\u0000.jpg")
    }

    @Test(expected = SecurityException::class)
    fun blocksPathSeparatorsInSingleName() {
        PathGuard.validateSingleName("../evil")
    }

    @Test
    fun blocksSymlinkEscapingRootDirectory() {
        val tempRoot = Files.createTempDirectory("pathguard_root")
        val outsideDir = Files.createTempDirectory("pathguard_outside")
        val outsideFile = Files.createFile(outsideDir.resolve("secret.txt"))
        val symlink = tempRoot.resolve("escape_link")

        try {
            Files.createSymbolicLink(symlink, outsideFile)
            var blocked = false
            try {
                PathGuard.resolveSafeLocalPath(tempRoot, "escape_link")
            } catch (_: SecurityException) {
                blocked = true
            }
            assertTrue("Expected symlink escape to be blocked", blocked)
        } finally {
            Files.deleteIfExists(symlink)
            Files.deleteIfExists(outsideFile)
            Files.deleteIfExists(outsideDir)
            Files.deleteIfExists(tempRoot)
        }
    }
}
