package com.unraiddesk.app.ui.shell

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.widget.Toast
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isCaptionBarVisible
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.unraiddesk.app.R

internal fun isDesktopOrChromeOsEnvironment(
    hasArcFeature: Boolean,
    hasArcDeviceManagementFeature: Boolean,
    hasPcFeature: Boolean,
    uiMode: Int,
    isCaptionBarVisible: Boolean
): Boolean {
    val isDeskUiMode = (uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_DESK
    return hasArcFeature || hasArcDeviceManagementFeature || hasPcFeature || isDeskUiMode || isCaptionBarVisible
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun rememberIsDesktopOrChromeOs(): Boolean {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isCaptionBarVisible = WindowInsets.isCaptionBarVisible
    val hasArc = remember(context) {
        context.packageManager.hasSystemFeature("org.chromium.arc")
    }
    val hasArcDeviceManagement = remember(context) {
        context.packageManager.hasSystemFeature("org.chromium.arc.device_management")
    }
    val hasPc = remember(context) {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_PC)
    }
    return isDesktopOrChromeOsEnvironment(
        hasArcFeature = hasArc,
        hasArcDeviceManagementFeature = hasArcDeviceManagement,
        hasPcFeature = hasPc,
        uiMode = configuration.uiMode,
        isCaptionBarVisible = isCaptionBarVisible
    )
}

internal fun showExitPromptToast(context: Context): Toast =
    Toast.makeText(context, R.string.press_back_again_to_exit, Toast.LENGTH_SHORT).also { it.show() }

/**
 * Deterministic double-back exit guard with a configurable time window (default 2000 ms).
 */
internal class BackExitGuard(
    val windowMillis: Long = 2_000L
) {
    private var lastBackPressMillis: Long? = null

    val isArmed: Boolean
        get() = lastBackPressMillis != null

    /**
     * Records a Back press at [nowMillis].
     * Returns `true` if a previous Back press occurred within [windowMillis] (confirming exit),
     * or `false` if this press arms a fresh exit window.
     */
    fun onBackPressed(nowMillis: Long): Boolean {
        val previous = lastBackPressMillis
        return if (previous != null && (nowMillis - previous) in 0L..windowMillis) {
            lastBackPressMillis = null
            true
        } else {
            lastBackPressMillis = nowMillis
            false
        }
    }

    fun reset() {
        lastBackPressMillis = null
    }
}
