package com.unraiddesk.app.ui.shell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

data class PaletteAction(
    val id: String,
    val title: String,
    val category: String,
    val shortcut: String = "",
    val onExecute: () -> Unit
)

/**
 * Ctrl+K fuzzy Command Palette for jumping to shares, containers, screens, and file commands.
 */
@Composable
fun CommandPaletteDialog(
    actions: List<PaletteAction>,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, actions) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) {
            actions
        } else {
            actions.filter {
                it.title.lowercase().contains(q) || it.category.lowercase().contains(q)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Command Palette") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    leadingIcon = {
                        Icon(imageVector = Icons.Outlined.Search, contentDescription = null)
                    },
                    placeholder = { Text("Jump to share, screen, or command...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp)
                ) {
                    items(filtered, key = { it.id }) { action ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onDismiss()
                                    action.onExecute()
                                }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = action.title, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    text = action.category,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (action.shortcut.isNotEmpty()) {
                                Text(
                                    text = action.shortcut,
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontFamily = FontFamily.Monospace
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

/**
 * F1 Desktop Keyboard Shortcuts Cheatsheet dialog.
 */
@Composable
fun ShortcutCheatsheetDialog(onDismiss: () -> Unit) {
    val shortcuts = listOf(
        "Ctrl + T" to "New Explorer Tab",
        "Ctrl + W" to "Close Active Tab",
        "Ctrl + Tab" to "Next Tab",
        "Alt + 1..9" to "Switch to Tab 1..9",
        "Alt + Left" to "Back in Files",
        "Ctrl + 1 / 2" to "Details View / Grid View",
        "Ctrl + 4 / F3" to "Toggle Dual-Pane Commander",
        "F5 / F6" to "Copy / Move to Opposite Pane",
        "F2" to "Rename Selected Item",
        "Del" to "Delete Selected Item",
        "Space" to "Quick Look Preview",
        "Ctrl + L" to "Focus Path Bar",
        "Ctrl + K" to "Open Command Palette",
        "F1" to "Show Keyboard Shortcuts"
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Keyboard Shortcuts") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                shortcuts.forEach { (key, desc) ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = key,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontFamily = FontFamily.Monospace
                            )
                        )
                        Text(
                            text = desc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}
