package com.unraiddesk.app.ui.explorer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileExplorerTouchInteractionTest {

    @Test
    fun desktopUsesClickSelectDoubleClickOpenAndLongPressContextMenu() {
        assertTrue(shouldEnableDoubleClick(isDesktop = true))
        assertEquals(
            EntryTapAction.TOGGLE_SELECTION,
            resolveEntryTapAction(isDesktop = true, hasActiveSelection = false)
        )
        assertEquals(
            EntryTapAction.TOGGLE_SELECTION,
            resolveEntryTapAction(isDesktop = true, hasActiveSelection = true)
        )
        assertEquals(
            EntryLongPressAction.OPEN_CONTEXT_MENU,
            resolveEntryLongPressAction(isDesktop = true, hasActiveSelection = false)
        )
        assertEquals(
            EntryLongPressAction.OPEN_CONTEXT_MENU,
            resolveEntryLongPressAction(isDesktop = true, hasActiveSelection = true)
        )
    }

    @Test
    fun phoneAndTabletDisableDoubleClickAndUseSingleTapOpenOrMultiSelect() {
        assertFalse(shouldEnableDoubleClick(isDesktop = false))

        // Browsing mode (no active selection): single-tap opens, long-press enters multi-select
        assertEquals(
            EntryTapAction.OPEN,
            resolveEntryTapAction(isDesktop = false, hasActiveSelection = false)
        )
        assertEquals(
            EntryLongPressAction.TOGGLE_SELECTION,
            resolveEntryLongPressAction(isDesktop = false, hasActiveSelection = false)
        )

        // Multi-select mode (active selection): single-tap and long-press both toggle selection
        assertEquals(
            EntryTapAction.TOGGLE_SELECTION,
            resolveEntryTapAction(isDesktop = false, hasActiveSelection = true)
        )
        assertEquals(
            EntryLongPressAction.TOGGLE_SELECTION,
            resolveEntryLongPressAction(isDesktop = false, hasActiveSelection = true)
        )
    }
}
