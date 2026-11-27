package com.unraiddesk.app.ui.explorer

enum class EntryTapAction {
    OPEN,
    TOGGLE_SELECTION
}

enum class EntryLongPressAction {
    OPEN_CONTEXT_MENU,
    TOGGLE_SELECTION
}

internal fun resolveEntryTapAction(
    isDesktop: Boolean,
    hasActiveSelection: Boolean
): EntryTapAction = when {
    isDesktop -> EntryTapAction.TOGGLE_SELECTION
    hasActiveSelection -> EntryTapAction.TOGGLE_SELECTION
    else -> EntryTapAction.OPEN
}

internal fun resolveEntryLongPressAction(
    isDesktop: Boolean,
    hasActiveSelection: Boolean
): EntryLongPressAction = when {
    isDesktop -> EntryLongPressAction.OPEN_CONTEXT_MENU
    hasActiveSelection -> EntryLongPressAction.TOGGLE_SELECTION
    else -> EntryLongPressAction.TOGGLE_SELECTION
}

internal fun shouldEnableDoubleClick(isDesktop: Boolean): Boolean = isDesktop
