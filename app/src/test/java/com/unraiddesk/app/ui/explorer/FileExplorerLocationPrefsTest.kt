package com.unraiddesk.app.ui.explorer

import com.unraiddesk.app.core.data.prefs.decodeLocationDisplayNames
import com.unraiddesk.app.core.data.prefs.encodeLocationDisplayNames
import com.unraiddesk.app.files.vfs.FileEntry
import com.unraiddesk.app.files.vfs.FileSystemProvider
import com.unraiddesk.app.files.vfs.StorageProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

class FileExplorerLocationPrefsTest {

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
        customLabel: String? = null,
        isUnraidShare: Boolean = false,
        isPinned: Boolean = false,
        isHidden: Boolean = false
    ): StorageMount = StorageMount(
        id = id,
        defaultLabel = defaultLabel,
        customLabel = customLabel,
        protocol = if (isUnraidShare) StorageProtocol.SMB else StorageProtocol.LOCAL,
        isUnraidShare = isUnraidShare,
        isPinned = isPinned,
        isHidden = isHidden,
        provider = DummyProvider
    )

    @Test
    fun displayNameJsonEncodingRoundTripsAndHandlesMalformedOrBlankInput() {
        val original = mapOf(
            "local" to "Phone Storage",
            "smb_unraid_Games" to "Steam Library"
        )
        val encoded = encodeLocationDisplayNames(original)
        assertEquals(original, decodeLocationDisplayNames(encoded))

        // Removing key on reset produces clean map without removed entry
        val afterReset = original - "smb_unraid_Games"
        val reEncoded = encodeLocationDisplayNames(afterReset)
        assertEquals(mapOf("local" to "Phone Storage"), decodeLocationDisplayNames(reEncoded))

        // Blank, null, and malformed JSON safely fall back to emptyMap
        assertEquals(emptyMap<String, String>(), decodeLocationDisplayNames(null))
        assertEquals(emptyMap<String, String>(), decodeLocationDisplayNames(""))
        assertEquals(emptyMap<String, String>(), decodeLocationDisplayNames("   "))
        assertEquals(emptyMap<String, String>(), decodeLocationDisplayNames("{not-valid-json"))
    }

    @Test
    fun storageMountLabelPrefersNonBlankCustomLabelOverDefaultLabel() {
        val defaultOnly = mount(id = "local", defaultLabel = "Device Storage")
        assertEquals("Device Storage", defaultOnly.label)

        val renamed = defaultOnly.copy(customLabel = "My Fold")
        assertEquals("My Fold", renamed.label)

        val blankCustom = defaultOnly.copy(customLabel = "   ")
        assertEquals("Device Storage", blankCustom.label)
    }

    @Test
    fun partitionLocationsHandlesPinnedHiddenAndEmptySafeguard() {
        val mounts = listOf(
            mount(id = "local", defaultLabel = "Device Storage"),
            mount(id = "smb_unraid_Games", defaultLabel = "Games", isUnraidShare = true, isPinned = true),
            mount(id = "smb_unraid_Media", defaultLabel = "Media", isUnraidShare = true, isHidden = true),
            mount(id = "smb_unraid_Backups", defaultLabel = "Backups", isUnraidShare = true, isPinned = true, isHidden = true)
        )

        // Default (showHidden = false): hidden items (including pinned+hidden) excluded
        val defaultPartition = partitionLocations(mounts, showHidden = false)
        assertEquals(listOf("smb_unraid_Games"), defaultPartition.pinned.map { it.id })
        assertEquals(listOf("local"), defaultPartition.others.map { it.id })
        assertFalse(defaultPartition.isEmpty)

        // Show hidden = true: all items included, pinned+hidden appears in pinned group
        val withHidden = partitionLocations(mounts, showHidden = true)
        assertEquals(
            listOf("smb_unraid_Games", "smb_unraid_Backups"),
            withHidden.pinned.map { it.id }
        )
        assertEquals(
            listOf("local", "smb_unraid_Media"),
            withHidden.others.map { it.id }
        )

        // All hidden safeguard
        val allHidden = mounts.map { it.copy(isHidden = true) }
        val hiddenPartition = partitionLocations(allHidden, showHidden = false)
        assertTrue(hiddenPartition.isEmpty)

        val uiState = ExplorerUiState(mounts = allHidden)
        assertEquals(4, uiState.hiddenMountCount)
        assertFalse(uiState.isExpandedSidebarCollapsed)
        assertFalse(uiState.showHiddenLocations)
    }

    @Test
    fun findUnraidShareMountMatchesByIdEvenAfterRenameOrHide() {
        val mounts = listOf(
            mount(id = "local", defaultLabel = "Device Storage"),
            mount(
                id = "smb_unraid_Games",
                defaultLabel = "Games",
                customLabel = "PC Games Vault",
                isUnraidShare = true,
                isHidden = true
            ),
            mount(
                id = "custom_id_media",
                defaultLabel = "Media",
                customLabel = "Movies & TV",
                isUnraidShare = true
            )
        )

        val games = findUnraidShareMount(mounts, "Games")
        assertNotNull(games)
        assertEquals("smb_unraid_Games", games!!.id)
        assertEquals("PC Games Vault", games.label)

        // Fallback match by defaultLabel (case-insensitive)
        val media = findUnraidShareMount(mounts, "media")
        assertNotNull(media)
        assertEquals("custom_id_media", media!!.id)
    }
}
