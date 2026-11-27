package com.unraiddesk.app.widget

import com.unraiddesk.app.core.data.db.DockerFolderEntity
import com.unraiddesk.app.core.data.db.DockerFolderMembershipEntity
import com.unraiddesk.app.core.network.DockerContainer
import com.unraiddesk.app.core.network.UnraidShare
import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetStateReducerTest {

    @Test
    fun filtersMonitoredSharesWhenSelectionIsNonEmpty() {
        val shares = listOf(
            UnraidShare(name = "appdata"),
            UnraidShare(name = "media"),
            UnraidShare(name = "backups")
        )
        val filtered = WidgetStateReducer.filterMonitoredShares(shares, setOf("media", "backups"))
        assertEquals(listOf("media", "backups"), filtered.map { it.name })

        val allWhenEmpty = WidgetStateReducer.filterMonitoredShares(shares, emptySet())
        assertEquals(3, allWhenEmpty.size)
    }

    @Test
    fun groupsContainersByVisibleDockerFolders() {
        val folders = listOf(
            DockerFolderEntity(id = 1L, name = "Media"),
            DockerFolderEntity(id = 2L, name = "Infra")
        )
        val memberships = listOf(
            DockerFolderMembershipEntity(folderId = 1L, containerName = "plex"),
            DockerFolderMembershipEntity(folderId = 2L, containerName = "pihole")
        )
        val containers = listOf(
            DockerContainer(id = "c1", name = "plex", state = "RUNNING"),
            DockerContainer(id = "c2", name = "pihole", state = "RUNNING")
        )

        val grouped = WidgetStateReducer.groupContainersByFolder(
            folders = folders,
            memberships = memberships,
            containers = containers,
            visibleFolderIds = setOf("1")
        )

        assertEquals(1, grouped.size)
        assertEquals("Media", grouped.first().first.name)
        assertEquals(listOf("plex"), grouped.first().second.map { it.name })
    }

    @Test
    fun partitionsUngroupedContainersWhenNoFoldersExist() {
        val containers = listOf(
            DockerContainer(id = "c2", name = "sonarr", state = "RUNNING"),
            DockerContainer(id = "c1", name = "plex", state = "EXITED")
        )

        val partition = WidgetStateReducer.partitionDockerContainers(
            folders = emptyList(),
            memberships = emptyList(),
            containers = containers,
            visibleFolderIds = emptySet()
        )

        assertEquals(0, partition.groupedFolders.size)
        assertEquals(listOf("plex", "sonarr"), partition.ungroupedContainers.map { it.name })
    }

    @Test
    fun partitionsMixedGroupedAndUngroupedContainersRespectingVisibleFolders() {
        val folders = listOf(
            DockerFolderEntity(id = 1L, name = "Media"),
            DockerFolderEntity(id = 2L, name = "Infra")
        )
        val memberships = listOf(
            DockerFolderMembershipEntity(folderId = 1L, containerName = "plex"),
            DockerFolderMembershipEntity(folderId = 2L, containerName = "pihole")
        )
        val containers = listOf(
            DockerContainer(id = "c1", name = "plex", state = "RUNNING"),
            DockerContainer(id = "c2", name = "pihole", state = "RUNNING"),
            DockerContainer(id = "c3", name = "jellyfin", state = "EXITED")
        )

        val partition = WidgetStateReducer.partitionDockerContainers(
            folders = folders,
            memberships = memberships,
            containers = containers,
            visibleFolderIds = setOf("1")
        )

        assertEquals(1, partition.groupedFolders.size)
        assertEquals("Media", partition.groupedFolders.first().first.name)
        assertEquals(listOf("plex"), partition.groupedFolders.first().second.map { it.name })
        assertEquals(listOf("jellyfin"), partition.ungroupedContainers.map { it.name })
    }
}
