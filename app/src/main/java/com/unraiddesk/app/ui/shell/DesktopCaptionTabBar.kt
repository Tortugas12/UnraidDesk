package com.unraiddesk.app.ui.shell

import android.graphics.Rect
import android.os.Build
import android.view.WindowInsets
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

data class CaptionTabItem(
    val id: String,
    val title: String,
    val closable: Boolean = true
)

/**
 * Compact desktop tab strip that integrates into the transparent caption bar on API 35+
 * (avoiding Close/Maximize/Minimize bounding rects and excluding tab bounds from system window-drag gestures)
 * and renders cleanly below the OS title bar on Android 14 (DeX / Smart Connect / PC Mode).
 */
@Composable
fun DesktopCaptionTabBar(
    tabs: List<CaptionTabItem>,
    activeTabId: String,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    modifier: Modifier = Modifier,
    onNewTab: (() -> Unit)? = null
) {
    val view = LocalView.current
    val density = LocalDensity.current
    var startInsetPx by remember { mutableIntStateOf(0) }
    var endInsetPx by remember { mutableIntStateOf(0) }
    var barHeightPx by remember { mutableIntStateOf(with(density) { 40.dp.roundToPx() }) }
    var tabStripBounds by remember { mutableStateOf<Rect?>(null) }

    DisposableEffect(view) {
        fun updateInsets() {
            val rootInsets = view.rootWindowInsets ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                val captionVisible = rootInsets.isVisible(WindowInsets.Type.captionBar())
                if (captionVisible) {
                    val captionInsets = rootInsets.getInsets(WindowInsets.Type.captionBar())
                    barHeightPx = captionInsets.top.coerceAtLeast(with(density) { 36.dp.roundToPx() })
                    val rects = rootInsets.getBoundingRects(WindowInsets.Type.captionBar())
                    val viewWidth = view.width.coerceAtLeast(1)
                    var leftPad = 0
                    var rightPad = 0
                    rects.forEach { rect ->
                        if (rect.left <= viewWidth / 2) {
                            leftPad = maxOf(leftPad, rect.right)
                        } else {
                            rightPad = maxOf(rightPad, viewWidth - rect.left)
                        }
                    }
                    startInsetPx = leftPad
                    endInsetPx = rightPad
                } else {
                    startInsetPx = 0
                    endInsetPx = 0
                }
            }
        }
        updateInsets()
        val listener = android.view.View.OnApplyWindowInsetsListener { _, insets ->
            updateInsets()
            insets
        }
        view.setOnApplyWindowInsetsListener(listener)
        onDispose {
            view.setOnApplyWindowInsetsListener(null)
            view.systemGestureExclusionRects = emptyList()
        }
    }

    DisposableEffect(view, tabStripBounds) {
        val rect = tabStripBounds
        if (rect != null) {
            view.systemGestureExclusionRects = listOf(rect)
        }
        onDispose { }
    }

    val startPadding = with(density) { startInsetPx.toDp() }
    val endPadding = with(density) { endInsetPx.toDp() }
    val barHeight = with(density) { barHeightPx.toDp() }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth()
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(barHeight)
                    .padding(start = startPadding + 8.dp, end = endPadding + 8.dp)
                    .horizontalScroll(rememberScrollState())
                    .onGloballyPositioned { coords ->
                        val b = coords.boundsInWindow()
                        tabStripBounds = Rect(
                            b.left.roundToInt(),
                            b.top.roundToInt(),
                            b.right.roundToInt(),
                            b.bottom.roundToInt()
                        )
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                tabs.forEach { tab ->
                    val isActive = tab.id == activeTabId
                    val bgColor = if (isActive) {
                        MaterialTheme.colorScheme.surface
                    } else {
                        MaterialTheme.colorScheme.surfaceContainer
                    }
                    Row(
                        modifier = Modifier
                            .height(30.dp)
                            .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                            .background(bgColor)
                            .clickable { onSelectTab(tab.id) }
                            .padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = tab.title,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (isActive) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                        if (tab.closable) {
                            IconButton(
                                onClick = { onCloseTab(tab.id) },
                                modifier = Modifier.size(18.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Close,
                                    contentDescription = "Close ${tab.title} tab",
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }

                if (onNewTab != null) {
                    IconButton(
                        onClick = onNewTab,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Add,
                            contentDescription = "New tab",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
            HorizontalDivider(
                modifier = Modifier.align(Alignment.BottomCenter),
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant
            )
        }
    }
}
