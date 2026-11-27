package com.unraiddesk.app.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.unraiddesk.app.core.security.TofuTrustManager
import java.text.DateFormat
import java.util.Date

@Composable
fun TofuCertDialog(
    certInfo: TofuTrustManager.UntrustedCertInfo,
    onTrustAndPin: () -> Unit,
    onDismiss: () -> Unit
) {
    val dateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM)
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Outlined.VerifiedUser,
                contentDescription = "Certificate verification"
            )
        },
        title = {
            Text("Verify Server Certificate")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "This server presented a self-signed or untrusted TLS certificate. " +
                        "Verify the SHA-256 fingerprint matches your Unraid server before trusting it.",
                    style = MaterialTheme.typography.bodyMedium
                )
                CertField(label = "Host", value = certInfo.host)
                CertField(label = "Subject", value = certInfo.subject)
                CertField(label = "Issuer", value = certInfo.issuer)
                CertField(
                    label = "Valid",
                    value = "${dateFormat.format(Date(certInfo.notBeforeMillis))} – " +
                        dateFormat.format(Date(certInfo.notAfterMillis))
                )
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = "SHA-256 Fingerprint",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = certInfo.sha256Fingerprint,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontFeatureSettings = "tnum"
                            ),
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onTrustAndPin) {
                Text("Trust & Pin")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun CertField(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value.ifBlank { "Unknown" },
            style = MaterialTheme.typography.bodySmall
        )
    }
}
