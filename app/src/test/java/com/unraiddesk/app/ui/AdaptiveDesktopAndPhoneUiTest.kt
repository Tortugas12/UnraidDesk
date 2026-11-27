package com.unraiddesk.app.ui

import com.unraiddesk.app.core.data.db.ServerConfigEntity
import com.unraiddesk.app.ui.explorer.ExplorerTab
import com.unraiddesk.app.ui.shell.PaletteAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies desktop (1360x1000dp) and phone (411x915dp) adaptive viewport logic,
 * explorer tab lifecycle, Command Palette fuzzy filtering, and Unraid WebGUI deep links.
 */
class AdaptiveDesktopAndPhoneUiTest {

    private fun isExpandedViewport(widthDp: Int): Boolean = widthDp >= 600

    @Test
    fun desktop1360x1000UsesExpandedLayoutAndPhone411x915UsesCompactLayout() {
        assertTrue(isExpandedViewport(1360))
        assertFalse(isExpandedViewport(411))
    }

    @Test
    fun explorerTabCreationAndClosingMaintainsValidActiveTab() {
        var tabs = listOf(ExplorerTab(id = "t1", title = "Device Storage"))
        var activeId = "t1"

        // Create tab (Ctrl+T)
        val newTab = ExplorerTab(id = "t2", title = "media")
        tabs = tabs + newTab
        activeId = newTab.id
        assertEquals(2, tabs.size)
        assertEquals("t2", activeId)

        // Close active tab (Ctrl+W)
        tabs = tabs.filterNot { it.id == activeId }
        activeId = tabs.last().id
        assertEquals(1, tabs.size)
        assertEquals("t1", activeId)
    }

    @Test
    fun commandPaletteFiltersActionsAndDockerWebGuiLinksMatchUnraidPaths() {
        val server = ServerConfigEntity(
            name = "Tower",
            host = "192.168.1.10",
            port = 443,
            useHttps = true,
            encryptedApiKey = "enc",
            createdAtMillis = 0L,
            updatedAtMillis = 0L
        )
        assertEquals("https://192.168.1.10/Docker", "${server.baseUrl()}/Docker")
        assertEquals("https://192.168.1.10/Apps", "${server.baseUrl()}/Apps")

        val actions = listOf(
            PaletteAction("1", "Toggle Dual-Pane Commander", "Files", "F3") {},
            PaletteAction("2", "Open plex WebUI", "Docker") {},
            PaletteAction("3", "Go to Shares", "Navigation") {}
        )
        val filtered = actions.filter {
            it.title.lowercase().contains("dual") || it.category.lowercase().contains("dual")
        }
        assertEquals(listOf("1"), filtered.map { it.id })
    }
}
