package com.unraiddesk.app.ui.shell

import android.content.res.Configuration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackNavigationPolicyTest {

    @Test
    fun isDesktopOrChromeOsEnvironmentReturnsFalseOnNormalPhoneOrTablet() {
        assertFalse(
            isDesktopOrChromeOsEnvironment(
                hasArcFeature = false,
                hasArcDeviceManagementFeature = false,
                hasPcFeature = false,
                uiMode = Configuration.UI_MODE_TYPE_NORMAL,
                isCaptionBarVisible = false
            )
        )
    }

    @Test
    fun isDesktopOrChromeOsEnvironmentDetectsChromeOsPcDeskModeAndCaptionBar() {
        assertTrue(
            isDesktopOrChromeOsEnvironment(
                hasArcFeature = true,
                hasArcDeviceManagementFeature = false,
                hasPcFeature = false,
                uiMode = Configuration.UI_MODE_TYPE_NORMAL,
                isCaptionBarVisible = false
            )
        )
        assertTrue(
            isDesktopOrChromeOsEnvironment(
                hasArcFeature = false,
                hasArcDeviceManagementFeature = true,
                hasPcFeature = false,
                uiMode = Configuration.UI_MODE_TYPE_NORMAL,
                isCaptionBarVisible = false
            )
        )
        assertTrue(
            isDesktopOrChromeOsEnvironment(
                hasArcFeature = false,
                hasArcDeviceManagementFeature = false,
                hasPcFeature = true,
                uiMode = Configuration.UI_MODE_TYPE_NORMAL,
                isCaptionBarVisible = false
            )
        )
        assertTrue(
            isDesktopOrChromeOsEnvironment(
                hasArcFeature = false,
                hasArcDeviceManagementFeature = false,
                hasPcFeature = false,
                uiMode = Configuration.UI_MODE_TYPE_DESK or Configuration.UI_MODE_NIGHT_YES,
                isCaptionBarVisible = false
            )
        )
        assertTrue(
            isDesktopOrChromeOsEnvironment(
                hasArcFeature = false,
                hasArcDeviceManagementFeature = false,
                hasPcFeature = false,
                uiMode = Configuration.UI_MODE_TYPE_NORMAL,
                isCaptionBarVisible = true
            )
        )
    }

    @Test
    fun backExitGuardArmsConfirmsWithinWindowReArmsAfterExpiryAndResets() {
        val guard = BackExitGuard(windowMillis = 2_000L)
        assertFalse(guard.isArmed)

        // First press arms the guard
        assertFalse(guard.onBackPressed(nowMillis = 10_000L))
        assertTrue(guard.isArmed)

        // Second press within 2000ms confirms exit and disarms
        assertTrue(guard.onBackPressed(nowMillis = 11_500L))
        assertFalse(guard.isArmed)

        // Arm again, then press after window expires (> 2000ms) -> re-arms without exiting
        assertFalse(guard.onBackPressed(nowMillis = 20_000L))
        assertTrue(guard.isArmed)
        assertFalse(guard.onBackPressed(nowMillis = 22_001L))
        assertTrue(guard.isArmed)

        // Explicit reset clears armed state
        guard.reset()
        assertFalse(guard.isArmed)
        assertFalse(guard.onBackPressed(nowMillis = 22_500L))
    }
}
