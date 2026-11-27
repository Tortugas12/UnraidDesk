package com.unraiddesk.app.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.unraiddesk.app.core.data.db.PinnedCertEntity
import com.unraiddesk.app.core.data.db.ServerConfigEntity
import com.unraiddesk.app.core.data.prefs.AppPreferencesRepository.ThemeMode

@Composable
fun SettingsScreen(
    serverConfig: ServerConfigEntity?,
    pinnedCerts: List<PinnedCertEntity>,
    pollingSeconds: Int,
    widgetRefreshMinutes: Int,
    dualPaneDefault: Boolean,
    themeMode: ThemeMode,
    onPollingSecondsChange: (Int) -> Unit,
    onWidgetRefreshMinutesChange: (Int) -> Unit,
    onDualPaneDefaultChange: (Boolean) -> Unit,
    onThemeModeChange: (ThemeMode) -> Unit,
    onRevokePinnedCert: (String) -> Unit,
    onReconfigureServer: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 720.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.headlineMedium
            )

            // Server section
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(text = "Unraid Server", style = MaterialTheme.typography.titleMedium)
                    if (serverConfig != null) {
                        Text(
                            text = "${serverConfig.name} (${serverConfig.baseUrl()})",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "API Key & SMB Credentials: Encrypted via AndroidKeyStore AES-256-GCM",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            text = "No server configured",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(
                        onClick = onReconfigureServer,
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        Text("Reconfigure Server")
                    }
                }
            }

            // Security & Pinned Certs
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(imageVector = Icons.Outlined.Security, contentDescription = null)
                        Text(text = "Security & Pinned Certificates", style = MaterialTheme.typography.titleMedium)
                    }
                    Text(
                        text = "LAN / VPN Guardrail: Active (public endpoints blocked)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (pinnedCerts.isEmpty()) {
                        Text(
                            text = "No self-signed TOFU certificates pinned.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        pinnedCerts.forEachIndexed { idx, cert ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = cert.host, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        text = cert.sha256Fingerprint,
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = FontFamily.Monospace
                                        ),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(onClick = { onRevokePinnedCert(cert.host) }) {
                                    Icon(
                                        imageVector = Icons.Outlined.Delete,
                                        contentDescription = "Revoke pin for ${cert.host}"
                                    )
                                }
                            }
                            if (idx < pinnedCerts.lastIndex) {
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }

            // Polling & Widget Cadence
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(text = "Polling & Widget Defaults", style = MaterialTheme.typography.titleMedium)

                    Text(text = "Foreground Polling Interval", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(5, 10, 15, 30).forEach { sec ->
                            FilterChip(
                                selected = pollingSeconds == sec,
                                onClick = { onPollingSecondsChange(sec) },
                                label = { Text("${sec}s") }
                            )
                        }
                    }

                    Text(text = "Widget Background Sync", style = MaterialTheme.typography.labelMedium)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(1, 5, 10, 15, 30, 60).forEach { min ->
                            FilterChip(
                                selected = widgetRefreshMinutes == min,
                                onClick = { onWidgetRefreshMinutesChange(min) },
                                label = { Text("${min}m") }
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Dual-Pane File Explorer by Default",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Switch(
                            checked = dualPaneDefault,
                            onCheckedChange = onDualPaneDefaultChange
                        )
                    }
                }
            }

            // Theme
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(text = "Appearance", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThemeMode.entries.forEach { mode ->
                            FilterChip(
                                selected = themeMode == mode,
                                onClick = { onThemeModeChange(mode) },
                                label = {
                                    Text(
                                        mode.name.lowercase().replaceFirstChar { it.uppercase() }
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
