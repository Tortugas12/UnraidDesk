package com.unraiddesk.app.widget

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.unraiddesk.app.UnraidDeskApp
import com.unraiddesk.app.core.data.prefs.AppPreferencesRepository.ThemeMode
import com.unraiddesk.app.core.network.DockerRootVariant
import com.unraiddesk.app.core.security.LanVpnGuard
import com.unraiddesk.app.ui.theme.UnraidDeskTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Lightweight transparent dialog activity launched from the home-screen widget's Docker tab
 * so users can safely Start, Stop, or Restart containers with critical-container protection.
 */
class WidgetContainerActionActivity : ComponentActivity() {

    private enum class ContainerOp(val label: String) {
        START("Start"),
        STOP("Stop"),
        RESTART("Restart")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val containerId = intent.getStringExtra(EXTRA_CONTAINER_ID).orEmpty().trim()
        val containerName = intent.getStringExtra(EXTRA_CONTAINER_NAME).orEmpty().ifBlank { containerId }
        val containerState = intent.getStringExtra(EXTRA_CONTAINER_STATE).orEmpty()
        val containerStatus = intent.getStringExtra(EXTRA_CONTAINER_STATUS).orEmpty()

        if (containerId.isBlank()) {
            finish()
            return
        }

        val app = application as UnraidDeskApp
        val container = app.container
        val isRunning = containerState.equals("RUNNING", ignoreCase = true)

        setContent {
            val themeMode by container.preferencesRepository.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
            val criticalNames by container.preferencesRepository.criticalContainerNames.collectAsState(initial = emptySet())
            val isCritical = containerName in criticalNames
            val scope = rememberCoroutineScope()

            var isBusy by remember { mutableStateOf(false) }
            var errorMessage by remember { mutableStateOf<String?>(null) }
            var lastFailedOp by remember { mutableStateOf<ContainerOp?>(null) }
            var pendingConfirmOp by remember { mutableStateOf<ContainerOp?>(null) }

            fun executeOp(op: ContainerOp) {
                scope.launch {
                    isBusy = true
                    errorMessage = null
                    lastFailedOp = null
                    pendingConfirmOp = null

                    val opError = withContext(Dispatchers.IO) {
                        val server = container.database.serverConfigDao().getPrimaryServer()
                            ?: return@withContext "No Unraid server configured."
                        val guard = container.lanVpnGuard.validateHost(server.host)
                        if (guard is LanVpnGuard.GuardResult.Blocked) {
                            return@withContext guard.message
                        }
                        runCatching {
                            val apiKey = container.cryptoManager.decryptString(server.encryptedApiKey)
                            val baseUrl = server.baseUrl()
                            val variant = when (server.dockerRootVariant) {
                                DockerRootVariant.DOCKER_CONTAINERS.name -> DockerRootVariant.DOCKER_CONTAINERS
                                else -> DockerRootVariant.DOCKER_OBJ
                            }
                            when (op) {
                                ContainerOp.START -> container.graphQlClient.startContainer(baseUrl, apiKey, containerId)
                                ContainerOp.STOP -> container.graphQlClient.stopContainer(baseUrl, apiKey, containerId)
                                ContainerOp.RESTART -> container.graphQlClient.restartContainer(
                                    baseUrl = baseUrl,
                                    apiKey = apiKey,
                                    containerId = containerId,
                                    variant = variant
                                )
                            }
                            val syncResult = WidgetSyncWorker.syncOnce(
                                serverConfigDao = container.database.serverConfigDao(),
                                snapshotDao = container.database.cachedMetricSnapshotDao(),
                                lanVpnGuard = container.lanVpnGuard,
                                cryptoManager = container.cryptoManager,
                                graphQlClient = container.graphQlClient
                            )
                            WidgetSyncWorker.applySyncResultToWidgets(applicationContext, syncResult)
                        }.exceptionOrNull()?.message
                    }

                    if (opError != null) {
                        isBusy = false
                        errorMessage = opError
                        lastFailedOp = op
                    } else {
                        finish()
                    }
                }
            }

            fun requestOp(op: ContainerOp) {
                if (isCritical && (op == ContainerOp.STOP || op == ContainerOp.RESTART)) {
                    pendingConfirmOp = op
                } else {
                    executeOp(op)
                }
            }

            UnraidDeskTheme(themeMode = themeMode) {
                ContainerActionDialogContent(
                    containerName = containerName,
                    isRunning = isRunning,
                    statusSubtitle = containerStatus.ifBlank { containerState.ifBlank { "Unknown" } },
                    isCritical = isCritical,
                    isBusy = isBusy,
                    errorMessage = errorMessage,
                    lastFailedOp = lastFailedOp,
                    pendingConfirmOp = pendingConfirmOp,
                    onRequestOp = ::requestOp,
                    onConfirmOp = ::executeOp,
                    onCancelConfirm = { pendingConfirmOp = null },
                    onDismiss = { if (!isBusy) finish() }
                )
            }
        }
    }

    @Composable
    private fun ContainerActionDialogContent(
        containerName: String,
        isRunning: Boolean,
        statusSubtitle: String,
        isCritical: Boolean,
        isBusy: Boolean,
        errorMessage: String?,
        lastFailedOp: ContainerOp?,
        pendingConfirmOp: ContainerOp?,
        onRequestOp: (ContainerOp) -> Unit,
        onConfirmOp: (ContainerOp) -> Unit,
        onCancelConfirm: () -> Unit,
        onDismiss: () -> Unit
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                ),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .widthIn(max = 320.dp)
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    ),
                shape = RoundedCornerShape(20.dp),
                tonalElevation = 6.dp,
                shadowElevation = 12.dp
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isRunning) Color(0xFF22C55E) else MaterialTheme.colorScheme.outline
                                )
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = containerName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = statusSubtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    if (isCritical && pendingConfirmOp == null) {
                        Text(
                            text = "Protected critical container — confirmation required for stop or restart.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    if (errorMessage != null) {
                        Text(
                            text = errorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    when {
                        isBusy -> {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = "Applying change…",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }

                        pendingConfirmOp != null -> {
                            Text(
                                text = "Confirm ${pendingConfirmOp.label.lowercase()} for critical container \"$containerName\"?",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(onClick = onCancelConfirm) {
                                    Text("Back")
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(
                                    onClick = { onConfirmOp(pendingConfirmOp) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.error
                                    )
                                ) {
                                    Text("Confirm ${pendingConfirmOp.label}")
                                }
                            }
                        }

                        else -> {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (isRunning) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        OutlinedButton(
                                            onClick = { onRequestOp(ContainerOp.RESTART) },
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text("Restart")
                                        }
                                        Button(
                                            onClick = { onRequestOp(ContainerOp.STOP) },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = MaterialTheme.colorScheme.error
                                            ),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text("Stop")
                                        }
                                    }
                                } else {
                                    Button(
                                        onClick = { onRequestOp(ContainerOp.START) },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("Start Container")
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    if (lastFailedOp != null) {
                                        TextButton(onClick = { onConfirmOp(lastFailedOp) }) {
                                            Text("Retry")
                                        }
                                    }
                                    TextButton(onClick = onDismiss) {
                                        Text("Cancel")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_CONTAINER_ID = "extra_container_id"
        const val EXTRA_CONTAINER_NAME = "extra_container_name"
        const val EXTRA_CONTAINER_STATE = "extra_container_state"
        const val EXTRA_CONTAINER_STATUS = "extra_container_status"

        fun createIntent(
            context: Context,
            containerId: String,
            containerName: String,
            containerState: String,
            containerStatus: String
        ): Intent = Intent(context, WidgetContainerActionActivity::class.java).apply {
            putExtra(EXTRA_CONTAINER_ID, containerId)
            putExtra(EXTRA_CONTAINER_NAME, containerName)
            putExtra(EXTRA_CONTAINER_STATE, containerState)
            putExtra(EXTRA_CONTAINER_STATUS, containerStatus)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
    }
}
