package com.unraiddesk.app.ui.docker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.unit.dp
import com.unraiddesk.app.core.network.DockerContainer

@Composable
fun FolderEditorDialog(
    initialFolderName: String,
    initialSelectedContainers: Set<String>,
    allContainers: List<DockerContainer>,
    onSave: (name: String, selectedContainers: Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var folderName by remember { mutableStateOf(initialFolderName) }
    var selected by remember { mutableStateOf(initialSelectedContainers) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (initialFolderName.isBlank()) "Create Docker Folder" else "Edit Docker Folder")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    label = { Text("Folder Name (e.g. Media Stack)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "Assign Containers",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                ) {
                    items(allContainers, key = { it.id }) { container ->
                        val isChecked = container.name in selected
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = isChecked,
                                onCheckedChange = { checked ->
                                    selected = if (checked) {
                                        selected + container.name
                                    } else {
                                        selected - container.name
                                    }
                                }
                            )
                            Text(
                                text = container.name,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(start = 6.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(folderName, selected) },
                enabled = folderName.isNotBlank()
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
