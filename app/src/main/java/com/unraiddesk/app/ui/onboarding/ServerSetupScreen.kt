package com.unraiddesk.app.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.unraiddesk.app.R
import com.unraiddesk.app.core.data.db.RoomPinnedCertStore
import com.unraiddesk.app.core.data.db.ServerConfigEntity
import com.unraiddesk.app.core.data.db.ShareMountEntity
import com.unraiddesk.app.core.data.db.UnraidDeskDatabase
import com.unraiddesk.app.core.network.ApiKeyAuthCoordinator
import com.unraiddesk.app.core.network.DockerRootVariant
import com.unraiddesk.app.core.network.UnraidGraphQlClient
import com.unraiddesk.app.core.network.UnraidShare
import com.unraiddesk.app.core.security.KeystoreCryptoManager
import com.unraiddesk.app.core.security.LanVpnGuard
import com.unraiddesk.app.core.security.TofuTrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import com.unraiddesk.app.core.data.db.ServerConfigDao
import com.unraiddesk.app.core.data.db.ShareMountDao

/**
 * Persists server configuration (with Keystore-encrypted API key and SMB credentials)
 * and auto-populated ShareMountEntity rows from discovered Unraid shares.
 */
suspend fun persistOnboardingConfig(
    serverConfigDao: ServerConfigDao,
    shareMountDao: ShareMountDao,
    cryptoManager: KeystoreCryptoManager,
    serverName: String,
    host: String,
    port: Int,
    useHttps: Boolean,
    apiKey: String,
    dockerRootVariant: DockerRootVariant,
    shares: List<UnraidShare>,
    selectedShareNames: Set<String>,
    smbUsername: String,
    smbPassword: String,
    smbDomain: String
) = withContext(Dispatchers.IO) {
    val now = System.currentTimeMillis()
    val encryptedApiKey = cryptoManager.encryptString(apiKey.trim())
    val encryptedUser = smbUsername.trim().takeIf { it.isNotEmpty() }?.let { cryptoManager.encryptString(it) }
    val encryptedPass = smbPassword.takeIf { it.isNotEmpty() }?.let { cryptoManager.encryptString(it) }
    val cleanDomain = smbDomain.trim().takeIf { it.isNotEmpty() }

    val config = ServerConfigEntity(
        id = 1L,
        name = serverName.trim().ifEmpty { host.trim() },
        host = host.trim(),
        port = port,
        useHttps = useHttps,
        allowInsecureHttp = !useHttps,
        encryptedApiKey = encryptedApiKey,
        encryptedSmbUsername = encryptedUser,
        encryptedSmbPassword = encryptedPass,
        smbDomain = cleanDomain,
        dockerRootVariant = dockerRootVariant.name,
        createdAtMillis = now,
        updatedAtMillis = now
    )
    serverConfigDao.upsert(config)

    val shareEntities = shares.map { share ->
        ShareMountEntity(
            serverId = 1L,
            shareName = share.name,
            comment = share.comment,
            totalBytes = share.sizeBytes,
            freeBytes = share.freeBytes,
            usedBytes = share.usedBytes,
            includeDisks = share.include.joinToString(","),
            excludeDisks = share.exclude.joinToString(","),
            cacheMode = share.cache,
            smbEnabled = true,
            isMonitored = share.name in selectedShareNames
        )
    }
    shareMountDao.deleteForServer(1L)
    if (shareEntities.isNotEmpty()) {
        shareMountDao.upsertAll(shareEntities)
    }
}

suspend fun persistOnboardingConfig(
    database: UnraidDeskDatabase,
    cryptoManager: KeystoreCryptoManager,
    serverName: String,
    host: String,
    port: Int,
    useHttps: Boolean,
    apiKey: String,
    dockerRootVariant: DockerRootVariant,
    shares: List<UnraidShare>,
    selectedShareNames: Set<String>,
    smbUsername: String,
    smbPassword: String,
    smbDomain: String
) = persistOnboardingConfig(
    serverConfigDao = database.serverConfigDao(),
    shareMountDao = database.shareMountDao(),
    cryptoManager = cryptoManager,
    serverName = serverName,
    host = host,
    port = port,
    useHttps = useHttps,
    apiKey = apiKey,
    dockerRootVariant = dockerRootVariant,
    shares = shares,
    selectedShareNames = selectedShareNames,
    smbUsername = smbUsername,
    smbPassword = smbPassword,
    smbDomain = smbDomain
)

@Composable
fun ServerSetupScreen(
    lanVpnGuard: LanVpnGuard,
    graphQlClient: UnraidGraphQlClient,
    authCoordinator: ApiKeyAuthCoordinator,
    pinnedCertStore: RoomPinnedCertStore,
    database: UnraidDeskDatabase,
    cryptoManager: KeystoreCryptoManager,
    onSetupComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var serverName by remember { mutableStateOf("Unraid") }
    var host by remember { mutableStateOf("") }
    var portText by remember { mutableStateOf("443") }
    var useHttps by remember { mutableStateOf(true) }
    var manualApiKey by remember { mutableStateOf("") }
    var showManualEntry by remember { mutableStateOf(false) }

    var isConnecting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var pendingCert by remember { mutableStateOf<TofuTrustManager.UntrustedCertInfo?>(null) }
    var pendingApiKey by remember { mutableStateOf("") }

    var stepTwoVisible by remember { mutableStateOf(false) }
    var verifiedApiKey by remember { mutableStateOf("") }
    var detectedDockerVariant by remember { mutableStateOf(DockerRootVariant.DOCKER_OBJ) }
    var discoveredShares by remember { mutableStateOf<List<UnraidShare>>(emptyList()) }
    var selectedShares by remember { mutableStateOf<Set<String>>(emptySet()) }

    var smbUsername by remember { mutableStateOf("") }
    var smbPassword by remember { mutableStateOf("") }
    var smbDomain by remember { mutableStateOf("") }
    var isSaving by remember { mutableStateOf(false) }

    fun sanitizedHost(): String = host
        .trim()
        .removePrefix("https://")
        .removePrefix("http://")
        .substringBefore("/")
        .trim()

    fun buildBaseUrl(): String? {
        val cleanHost = sanitizedHost()
        if (cleanHost.isEmpty()) return null
        val port = portText.toIntOrNull() ?: return null
        val scheme = if (useHttps) "https" else "http"
        val isDefault = (useHttps && port == 443) || (!useHttps && port == 80)
        return if (isDefault) "$scheme://$cleanHost" else "$scheme://$cleanHost:$port"
    }

    fun verifyAndDiscover(apiKey: String) {
        val cleanHost = sanitizedHost()
        val baseUrl = buildBaseUrl()
        if (baseUrl == null) {
            errorMessage = "Enter a valid host and port number."
            return
        }
        pendingApiKey = apiKey
        scope.launch {
            isConnecting = true
            errorMessage = null
            try {
                val guardResult = withContext(Dispatchers.IO) { lanVpnGuard.validateHost(cleanHost) }
                if (guardResult is LanVpnGuard.GuardResult.Blocked) {
                    errorMessage = guardResult.message
                    isConnecting = false
                    return@launch
                }

                val variant = graphQlClient.probeDockerRoot(baseUrl, apiKey)
                val shares = graphQlClient.fetchShares(baseUrl, apiKey)
                verifiedApiKey = apiKey
                detectedDockerVariant = variant
                discoveredShares = shares
                selectedShares = shares.map { it.name }.toSet()
                stepTwoVisible = true
            } catch (e: Exception) {
                val tofu = findTofuException(e)
                if (tofu != null) {
                    pendingCert = tofu.certInfo
                } else {
                    errorMessage = e.message ?: "Unable to connect to Unraid server."
                }
            } finally {
                isConnecting = false
            }
        }
    }

    pendingCert?.let { certInfo ->
        TofuCertDialog(
            certInfo = certInfo,
            onTrustAndPin = {
                scope.launch {
                    withContext(Dispatchers.IO) { pinnedCertStore.pinCertInfo(certInfo) }
                    pendingCert = null
                    val keyToRetry = pendingApiKey.ifBlank {
                        verifiedApiKey.ifBlank { manualApiKey.trim() }
                    }
                    if (keyToRetry.isNotBlank()) {
                        verifyAndDiscover(keyToRetry)
                    }
                }
            },
            onDismiss = { pendingCert = null }
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (!stepTwoVisible) {
                Text(
                    text = "Connect to Unraid Server",
                    style = MaterialTheme.typography.headlineMedium
                )
                Text(
                    text = stringResource(R.string.app_tagline),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "UnraidDesk connects only over your local network (LAN) or VPN using " +
                        "Unraid 7.2+ scoped API keys.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                errorMessage?.let { msg ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = msg,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                }

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedTextField(
                            value = serverName,
                            onValueChange = { serverName = it },
                            label = { Text("Server Label") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedTextField(
                                value = host,
                                onValueChange = { host = it },
                                label = { Text("LAN IP or Hostname (e.g. 192.168.1.10 or tower.local)") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = portText,
                                onValueChange = { portText = it.filter(Char::isDigit) },
                                label = { Text("Port") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.widthIn(min = 88.dp, max = 110.dp)
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Security,
                                    contentDescription = null
                                )
                                Text("Use HTTPS (Recommended + TOFU Pinning)")
                            }
                            Switch(
                                checked = useHttps,
                                onCheckedChange = { checked ->
                                    useHttps = checked
                                    if (checked && portText == "80") portText = "443"
                                    if (!checked && portText == "443") portText = "80"
                                }
                            )
                        }

                        if (!useHttps) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.WarningAmber,
                                    contentDescription = "Warning",
                                    tint = MaterialTheme.colorScheme.tertiary
                                )
                                Text(
                                    text = "Plain HTTP transmits API keys unencrypted on your LAN.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.tertiary
                                )
                            }
                        }
                    }
                }

                Button(
                    onClick = {
                        val baseUrl = buildBaseUrl()
                        if (host.isBlank() || baseUrl == null) {
                            errorMessage = "Enter a valid LAN/VPN host and port first."
                            return@Button
                        }
                        scope.launch {
                            errorMessage = null
                            isConnecting = true
                            val guardResult = withContext(Dispatchers.IO) {
                                lanVpnGuard.validateHost(sanitizedHost())
                            }
                            if (guardResult is LanVpnGuard.GuardResult.Blocked) {
                                errorMessage = guardResult.message
                                isConnecting = false
                                return@launch
                            }
                            try {
                                val session = withContext(Dispatchers.IO) {
                                    authCoordinator.startLocalhostAuthSession(baseUrl)
                                }
                                authCoordinator.launchCustomTab(context, session.authorizeUrl)
                                val receivedKey = authCoordinator.awaitApiKeyCallback(session)
                                verifyAndDiscover(receivedKey)
                            } catch (e: Exception) {
                                isConnecting = false
                                errorMessage = e.message ?: "1-click authorization failed."
                            }
                        }
                    },
                    enabled = !isConnecting,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isConnecting) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 8.dp),
                            strokeWidth = 2.dp
                        )
                    }
                    Text("1-Click Authorize with Unraid")
                }

                TextButton(
                    onClick = { showManualEntry = !showManualEntry },
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Key,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 6.dp)
                    )
                    Text(if (showManualEntry) "Hide Manual API Key" else "Or Paste API Key Manually")
                }

                if (showManualEntry) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    val baseUrl = buildBaseUrl()
                                    if (host.isBlank() || baseUrl == null) {
                                        errorMessage = "Enter a valid host and port first."
                                    } else {
                                        val url = authCoordinator.buildAuthorizeUrl(baseUrl)
                                        authCoordinator.launchCustomTab(context, url)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Open Unraid API Key Page")
                            }

                            OutlinedTextField(
                                value = manualApiKey,
                                onValueChange = { manualApiKey = it },
                                label = { Text("Scoped Unraid API Key") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Button(
                                onClick = { verifyAndDiscover(manualApiKey.trim()) },
                                enabled = !isConnecting && manualApiKey.isNotBlank(),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Connect & Discover Shares")
                            }
                        }
                    }
                }
            } else {
                ShareAutoConfigStep(
                    discoveredShares = discoveredShares,
                    selectedShareNames = selectedShares,
                    onToggleShare = { name, checked ->
                        selectedShares = if (checked) {
                            selectedShares + name
                        } else {
                            selectedShares - name
                        }
                    },
                    smbUsername = smbUsername,
                    onSmbUsernameChange = { smbUsername = it },
                    smbPassword = smbPassword,
                    onSmbPasswordChange = { smbPassword = it },
                    smbDomain = smbDomain,
                    onSmbDomainChange = { smbDomain = it },
                    isSaving = isSaving,
                    onBack = { stepTwoVisible = false },
                    onFinish = {
                        val port = portText.toIntOrNull() ?: 443
                        scope.launch {
                            isSaving = true
                            try {
                                persistOnboardingConfig(
                                    database = database,
                                    cryptoManager = cryptoManager,
                                    serverName = serverName,
                                    host = sanitizedHost(),
                                    port = port,
                                    useHttps = useHttps,
                                    apiKey = verifiedApiKey,
                                    dockerRootVariant = detectedDockerVariant,
                                    shares = discoveredShares,
                                    selectedShareNames = selectedShares,
                                    smbUsername = smbUsername,
                                    smbPassword = smbPassword,
                                    smbDomain = smbDomain
                                )
                                onSetupComplete()
                            } finally {
                                isSaving = false
                            }
                        }
                    }
                )
            }
        }
    }
}

private fun findTofuException(error: Throwable?): TofuTrustManager.TofuUntrustedCertException? {
    var current = error
    while (current != null) {
        if (current is TofuTrustManager.TofuUntrustedCertException) return current
        current = current.cause
    }
    return null
}
