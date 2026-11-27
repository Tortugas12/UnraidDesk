package com.unraiddesk.app.widget

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetThemeTest {

    @Test
    fun opacityClampsToSupportedRange() {
        val low = WidgetThemeConfig(opacityPercent = 5).normalized()
        val high = WidgetThemeConfig(opacityPercent = 140).normalized()
        val normal = WidgetThemeConfig(opacityPercent = 75).normalized()

        assertEquals(WidgetThemeConfig.MIN_OPACITY_PERCENT, low.opacityPercent)
        assertEquals(WidgetThemeConfig.MAX_OPACITY_PERCENT, high.opacityPercent)
        assertEquals(75, normal.opacityPercent)
    }

    @Test
    fun systemPresetAdaptsBetweenLightAndDarkMode() {
        val config = WidgetThemeConfig(preset = WidgetThemePreset.SYSTEM)
        val light = WidgetTheme.resolveComposePalette(config, isSystemDark = false)
        val dark = WidgetTheme.resolveComposePalette(config, isSystemDark = true)

        assertNotEquals(light.background, dark.background)
        assertNotEquals(light.onSurface, dark.onSurface)
        assertEquals(0.92f, light.background.alpha, 0.01f)
    }

    @Test
    fun customPresetParsesHexSwatchesAndFallsBackOnInvalidHex() {
        val valid = WidgetThemeConfig(
            preset = WidgetThemePreset.CUSTOM,
            opacityPercent = 80,
            showBorder = false,
            customBgHex = "#FFFFFF",
            customCardHex = "#EAECEF",
            customAccentHex = "#3B82F6"
        )
        val resolved = WidgetTheme.resolveComposePalette(valid, isSystemDark = true)
        assertEquals(0.80f, resolved.background.alpha, 0.01f)
        assertEquals(Color.Transparent, resolved.border)
        assertEquals(Color(0xFF3B82F6), resolved.primary)
        // Light custom background should yield dark foreground text
        assertTrue(resolved.onSurface.red < 0.2f)

        val fallback = WidgetTheme.parseHexColor("not-a-color", Color(0xFF14161D))
        assertEquals(Color(0xFF14161D), fallback)
    }

    @Test
    fun amoledPresetUsesPureBlackBaseWithFullDefaultOpacity() {
        val config = WidgetThemeConfig(preset = WidgetThemePreset.AMOLED)
        val palette = WidgetTheme.resolveComposePalette(config, isSystemDark = false)
        assertEquals(Color(0xFF000000), palette.background)
    }
}
