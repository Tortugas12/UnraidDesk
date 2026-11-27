package com.unraiddesk.app.ui.explorer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.unraiddesk.app.files.vfs.StorageProtocol

@Composable
fun ConnectServerDialog(
    onConnectNetwork: (
        protocol: StorageProtocol,
        label: String,
        hostOrUrl: String,
        port: Int,
        shareOrPath: String,
        username: String,
        password: String,
        useTls: Boolean
    ) -> Unit,
    onPickSafFolder: () -> Unit,
    onDismiss: () -> Unit
) {
    var selectedProtocol by remember { mutableStateOf(StorageProtocol.SMB) }
    var label by remember { mutableStateOf("") }
    var hostOrUrl by remember { mutableStateOf("") }
    var portText by remember { mutableStateOf("445") }
    var shareOrPath by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var useTls by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connect Storage / Cloud") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = {
                        onDismiss()
                        onPickSafFolder()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Mount Cloud / USB via Android SAF")
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(StorageProtocol.SMB, StorageProtocol.WEBDAV, StorageProtocol.FTP).forEach { proto ->
                        FilterChip(
                            selected = selectedProtocol == proto,
                            onClick = {
                                selectedProtocol = proto
                                portText = when (proto) {
                                    StorageProtocol.SMB -> "445"
                                    StorageProtocol.WEBDAV -> "443"
                                    StorageProtocol.FTP -> "21"
                                    else -> "445"
                                }
                            },
                            label = { Text(proto.name) }
                        )
                    }
                }

                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Display Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = hostOrUrl,
                        onValueChange = { hostOrUrl = it },
                        label = {
                            Text(if (selectedProtocol == StorageProtocol.WEBDAV) "WebDAV URL" else "LAN/VPN Host")
                        },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    if (selectedProtocol != StorageProtocol.WEBDAV) {
                        OutlinedTextField(
                            value = portText,
                            onValueChange = { portText = it.filter(Char::isDigit) },
                            label = { Text("Port") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(0.45f)
                        )
                    }
                }

                if (selectedProtocol == StorageProtocol.SMB) {
                    OutlinedTextField(
                        value = shareOrPath,
                        onValueChange = { shareOrPath = it },
                        label = { Text("SMB Share Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )

                if (selectedProtocol == StorageProtocol.FTP) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Explicit FTPS (TLS)")
                        Switch(checked = useTls, onCheckedChange = { useTls = it })
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val port = portText.toIntOrNull() ?: 445
                    onConnectNetwork(
                        selectedProtocol,
                        label,
                        hostOrUrl.trim(),
                        port,
                        shareOrPath.trim(),
                        username.trim(),
                        password,
                        useTls
                    )
                    onDismiss()
                },
                enabled = hostOrUrl.isNotBlank() &&
                    (selectedProtocol != StorageProtocol.SMB || shareOrPath.isNotBlank())
            ) {
                Text("Connect")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
