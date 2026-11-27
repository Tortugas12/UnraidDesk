package com.unraiddesk.app.ui.explorer

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Side-by-side Commander layout (`Ctrl+4` / `F3`) with F5 Copy and F6 Move actions.
 */
@Composable
fun DualPaneLayout(
    activePane: PaneSlot,
    primaryTitle: String,
    secondaryTitle: String,
    onSelectPane: (PaneSlot) -> Unit,
    onCopyToOpposite: () -> Unit,
    onMoveToOpposite: () -> Unit,
    primaryContent: @Composable () -> Unit,
    secondaryContent: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Dual-Pane Commander",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            OutlinedButton(onClick = onCopyToOpposite) {
                Text("F5 Copy ->")
            }
            Button(onClick = onMoveToOpposite) {
                Text("F6 Move ->")
            }
        }

        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            PaneWrapper(
                title = primaryTitle,
                isActive = activePane == PaneSlot.PRIMARY,
                onActivate = { onSelectPane(PaneSlot.PRIMARY) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
                content = primaryContent
            )
            VerticalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
            PaneWrapper(
                title = secondaryTitle,
                isActive = activePane == PaneSlot.SECONDARY,
                onActivate = { onSelectPane(PaneSlot.SECONDARY) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
                content = secondaryContent
            )
        }
    }
}

@Composable
private fun PaneWrapper(
    title: String,
    isActive: Boolean,
    onActivate: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Surface(
        border = if (isActive) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = modifier.clickable(onClick = onActivate)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Surface(
                color = if (isActive) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainer
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
            content()
        }
    }
}
