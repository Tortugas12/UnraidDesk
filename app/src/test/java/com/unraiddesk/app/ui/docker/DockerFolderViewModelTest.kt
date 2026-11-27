package com.unraiddesk.app.ui.docker

import com.unraiddesk.app.core.data.db.DockerFolderEntity
import com.unraiddesk.app.core.data.db.DockerFolderMembershipEntity
import com.unraiddesk.app.core.network.DockerContainer
import org.junit.Assert.assertEquals
import org.junit.Test

class DockerFolderViewModelTest {

    @Test
    fun folderGroupingCountsRunningContainersAndLeavesUngroupedContainers() {
        val folders = listOf(
            DockerFolderEntity(id = 1L, name = "Media Stack", sortOrder = 0, isExpanded = true),
            DockerFolderEntity(id = 2L, name = "Utilities", sortOrder = 1, isExpanded = false)
        )
        val memberships = listOf(
            DockerFolderMembershipEntity(folderId = 1L, containerName = "plex"),
            DockerFolderMembershipEntity(folderId = 1L, containerName = "sonarr"),
            DockerFolderMembershipEntity(folderId = 2L, containerName = "portainer")
        )
        val containers = listOf(
            DockerContainer(id = "1", name = "plex", state = "RUNNING"),
            DockerContainer(id = "2", name = "sonarr", state = "EXITED"),
            DockerContainer(id = "3", name = "portainer", state = "RUNNING"),
            DockerContainer(id = "4", name = "vaultwarden", state = "RUNNING")
        )

        val byName = containers.associateBy { it.name }
        val assigned = mutableSetOf<String>()
        val groups = folders.map { folder ->
            val members = memberships
                .filter { it.folderId == folder.id }
                .mapNotNull { m -> byName[m.containerName]?.also { assigned.add(m.containerName) } }
            DockerFolderGroup(folder = folder, containers = members)
        }
        val ungrouped = containers.filter { it.name !in assigned }

        assertEquals(2, groups.size)
        assertEquals(2, groups[0].containers.size)
        assertEquals(1, groups[0].runningCount)
        assertEquals(1, groups[1].containers.size)
        assertEquals(1, groups[1].runningCount)
        assertEquals(listOf("vaultwarden"), ungrouped.map { it.name })
    }
}
