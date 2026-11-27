package com.unraiddesk.app.ui.explorer

import com.unraiddesk.app.files.vfs.FileEntry
import com.unraiddesk.app.files.vfs.FileSystemProvider
import com.unraiddesk.app.files.vfs.StorageProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

class FileExplorerBackNavigationTest {

    private object DummyProvider : FileSystemProvider {
        override val protocol: StorageProtocol = StorageProtocol.LOCAL
        override val rootId: String = "dummy"
        override val displayName: String = "Dummy"
        override suspend fun list(path: String): List<FileEntry> = emptyList()
        override suspend fun createDirectory(parentPath: String, name: String): FileEntry =
            throw UnsupportedOperationException()
        override suspend fun rename(path: String, newName: String): FileEntry =
            throw UnsupportedOperationException()
        override suspend fun delete(path: String) = Unit
        override suspend fun openInputStream(path: String): InputStream = ByteArrayInputStream(ByteArray(0))
        override suspend fun openOutputStream(path: String, overwrite: Boolean): OutputStream = ByteArrayOutputStream()
        override suspend fun exists(path: String): Boolean = false
    }

    private fun mount(
        id: String,
        defaultLabel: String,
        customLabel: String? = null
    ): StorageMount = StorageMount(
        id = id,
        defaultLabel = defaultLabel,
        customLabel = customLabel,
        protocol = StorageProtocol.LOCAL,
        provider = DummyProvider
    )

    @Test
    fun crossMountIntoSubfolderStepsUpToRootThenReturnsToPreviousMount() {
        val local = mount("local", "Device Storage", customLabel = "My Phone")
        val games = mount("smb_unraid_Games", "Games")
        val mountsById = mapOf(local.id to local, games.id to games)

        val startTab = ExplorerTab(
            id = "tab-1",
            title = local.label,
            mountId = local.id,
            currentPath = "Download"
        )

        val afterSwitch = pushLocationHistory(
            tab = startTab,
            targetMountId = games.id,
            targetMountTitle = games.label,
            targetPath = "Steam/Mods"
        )
        assertEquals(games.id, afterSwitch.mountId)
        assertEquals("Steam/Mods", afterSwitch.currentPath)
        assertEquals(listOf(ExplorerLocation("local", "Download")), afterSwitch.historyBack)
        assertTrue(afterSwitch.canNavigateBackOrUp)

        // Back 1: Steam/Mods -> Steam (parent-first, cross-mount history preserved)
        val step1 = resolveBackStep(afterSwitch, mountsById)
        assertNotNull(step1)
        assertEquals(games.id, step1!!.mountId)
        assertEquals("Steam", step1.currentPath)
        assertEquals(listOf(ExplorerLocation("local", "Download")), step1.historyBack)

        // Back 2: Steam -> "" (mount root)
        val step2 = resolveBackStep(step1, mountsById)
        assertNotNull(step2)
        assertEquals(games.id, step2!!.mountId)
        assertEquals("", step2.currentPath)
        assertEquals(listOf(ExplorerLocation("local", "Download")), step2.historyBack)

        // Back 3: at Games root -> restores previous mount (local / Download) with custom label
        val step3 = resolveBackStep(step2, mountsById)
        assertNotNull(step3)
        assertEquals("local", step3!!.mountId)
        assertEquals("My Phone", step3.title)
        assertEquals("Download", step3.currentPath)
        assertTrue(step3.historyBack.isEmpty())

        // Back 4: local/Download -> local root ""
        val step4 = resolveBackStep(step3, mountsById)
        assertNotNull(step4)
        assertEquals("local", step4!!.mountId)
        assertEquals("", step4.currentPath)
        assertFalse(step4.canNavigateBackOrUp)

        // Back 5: at root with empty history -> null
        assertNull(resolveBackStep(step4, mountsById))
    }

    @Test
    fun pushingSameMountAndPathIsNoOpWithoutDuplicateHistoryEntry() {
        val tab = ExplorerTab(
            id = "tab-1",
            title = "Device Storage",
            mountId = "local",
            currentPath = "Docs/Work",
            historyBack = listOf(ExplorerLocation("local", ""))
        )

        val result = pushLocationHistory(
            tab = tab,
            targetMountId = "local",
            targetMountTitle = "Device Storage",
            targetPath = "/Docs//Work/"
        )
        assertEquals(tab, result)
        assertEquals(1, result.historyBack.size)
    }

    @Test
    fun drillingDownMultipleFoldersOnOneMountNeverLoopsOnRepeatedBack() {
        val local = mount("local", "Device Storage")
        val mountsById = mapOf(local.id to local)

        var tab = ExplorerTab(id = "tab-1", title = local.label, mountId = local.id, currentPath = "")
        tab = pushLocationHistory(tab, local.id, local.label, "a")
        tab = pushLocationHistory(tab, local.id, local.label, "a/b")
        tab = pushLocationHistory(tab, local.id, local.label, "a/b/c")
        assertEquals(3, tab.historyBack.size)

        // Step up: a/b/c -> a/b (same-mount trailing history dropped so it can't loop)
        val s1 = resolveBackStep(tab, mountsById)!!
        assertEquals("a/b", s1.currentPath)
        assertTrue(s1.historyBack.isEmpty())

        val s2 = resolveBackStep(s1, mountsById)!!
        assertEquals("a", s2.currentPath)
        assertTrue(s2.historyBack.isEmpty())

        val s3 = resolveBackStep(s2, mountsById)!!
        assertEquals("", s3.currentPath)
        assertTrue(s3.historyBack.isEmpty())
        assertFalse(s3.canNavigateBackOrUp)

        assertNull(resolveBackStep(s3, mountsById))
    }

    @Test
    fun resolveBackStepSkipsStaleMountsOnceAtMountRoot() {
        val local = mount("local", "Device Storage")
        val games = mount("smb_unraid_Games", "Games")
        val mountsById = mapOf(local.id to local, games.id to games)

        val tab = ExplorerTab(
            id = "tab-1",
            title = games.label,
            mountId = games.id,
            currentPath = "",
            historyBack = listOf(
                ExplorerLocation("local", "Music"),
                ExplorerLocation("saf_removed_usb", "Photos")
            )
        )

        val back = resolveBackStep(tab, mountsById)
        assertNotNull(back)
        back!!
        assertEquals("local", back.mountId)
        assertEquals("Device Storage", back.title)
        assertEquals("Music", back.currentPath)
        assertTrue(back.historyBack.isEmpty())
    }
}
