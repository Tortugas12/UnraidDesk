package com.unraiddesk.app.widget

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.datastore.preferences.core.Preferences
import androidx.glance.color.ColorProvider
import androidx.glance.unit.ColorProvider as GlanceColorProvider

enum class WidgetThemePreset(
    val displayName: String,
    val defaultOpacityPercent: Int,
    val defaultShowBorder: Boolean
) {
    SYSTEM("Follow System", 92, true),
    FROSTED_DARK("Frosted Dark", 82, true),
    FROSTED_LIGHT("Frosted Light", 85, true),
    UNRAID_DARK("Unraid Dark", 95, true),
    UNRAID_LIGHT("Unraid Light", 95, true),
    AMOLED("AMOLED", 100, true),
    CUSTOM("Custom", 88, true);

    companion object {
        fun fromNameOrDefault(name: String?): WidgetThemePreset =
            entries.firstOrNull { it.name == name } ?: FROSTED_DARK
    }
}

data class WidgetThemeConfig(
    val preset: WidgetThemePreset = WidgetThemePreset.FROSTED_DARK,
    val opacityPercent: Int = preset.defaultOpacityPercent,
    val showBorder: Boolean = preset.defaultShowBorder,
    val customBgHex: String = DEFAULT_CUSTOM_BG_HEX,
    val customCardHex: String = DEFAULT_CUSTOM_CARD_HEX,
    val customAccentHex: String = DEFAULT_CUSTOM_ACCENT_HEX
) {
    fun normalized(): WidgetThemeConfig = copy(
        opacityPercent = opacityPercent.coerceIn(MIN_OPACITY_PERCENT, MAX_OPACITY_PERCENT)
    )

    companion object {
        const val MIN_OPACITY_PERCENT = 20
        const val MAX_OPACITY_PERCENT = 100

        const val DEFAULT_CUSTOM_BG_HEX = "#14161D"
        const val DEFAULT_CUSTOM_CARD_HEX = "#1E222E"
        const val DEFAULT_CUSTOM_ACCENT_HEX = "#FF8C2F"

        val CUSTOM_BG_SWATCHES = listOf(
            "#14161D",
            "#0D1117",
            "#1A1423",
            "#0F1D1A",
            "#1E1B18",
            "#000000",
            "#F6F7FA",
            "#FFFFFF"
        )

        val CUSTOM_CARD_SWATCHES = listOf(
            "#1E222E",
            "#161B22",
            "#261E33",
            "#172B27",
            "#EAECEF",
            "#FFFFFF"
        )

        val CUSTOM_ACCENT_SWATCHES = listOf(
            "#FF8C2F",
            "#F15A24",
            "#3B82F6",
            "#22C55E",
            "#A855F7",
            "#EC4899"
        )

        fun fromPreferences(prefs: Preferences): WidgetThemeConfig {
            val preset = WidgetThemePreset.fromNameOrDefault(prefs[WidgetPrefKeys.THEME_PRESET])
            val opacity = (prefs[WidgetPrefKeys.THEME_OPACITY_PERCENT] ?: preset.defaultOpacityPercent)
                .coerceIn(MIN_OPACITY_PERCENT, MAX_OPACITY_PERCENT)
            val showBorder = prefs[WidgetPrefKeys.THEME_SHOW_BORDER] ?: preset.defaultShowBorder
            val customBg = prefs[WidgetPrefKeys.THEME_CUSTOM_BG_HEX] ?: DEFAULT_CUSTOM_BG_HEX
            val customCard = prefs[WidgetPrefKeys.THEME_CUSTOM_CARD_HEX] ?: DEFAULT_CUSTOM_CARD_HEX
            val customAccent = prefs[WidgetPrefKeys.THEME_CUSTOM_ACCENT_HEX] ?: DEFAULT_CUSTOM_ACCENT_HEX
            return WidgetThemeConfig(
                preset = preset,
                opacityPercent = opacity,
                showBorder = showBorder,
                customBgHex = customBg,
                customCardHex = customCard,
                customAccentHex = customAccent
            )
        }
    }
}

data class ResolvedComposePalette(
    val background: Color,
    val border: Color,
    val card: Color,
    val cardElevated: Color,
    val tabTrack: Color,
    val tabSelected: Color,
    val primary: Color,
    val onPrimary: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val statusRunning: Color,
    val statusWarning: Color,
    val statusError: Color,
    val progressTrack: Color
)

data class ResolvedGlancePalette(
    val background: GlanceColorProvider,
    val border: GlanceColorProvider,
    val card: GlanceColorProvider,
    val cardElevated: GlanceColorProvider,
    val tabTrack: GlanceColorProvider,
    val tabSelected: GlanceColorProvider,
    val primary: GlanceColorProvider,
    val onPrimary: GlanceColorProvider,
    val onSurface: GlanceColorProvider,
    val onSurfaceVariant: GlanceColorProvider,
    val statusRunning: GlanceColorProvider,
    val statusWarning: GlanceColorProvider,
    val statusError: GlanceColorProvider,
    val progressTrack: GlanceColorProvider
)

object WidgetTheme {
    private val UnraidOrange = Color(0xFFFF8C2F)
    private val UnraidOrangeDeep = Color(0xFFF15A24)
    private val StatusGreenDark = Color(0xFF34D399)
    private val StatusGreenLight = Color(0xFF15803D)
    private val StatusAmberDark = Color(0xFFFBBF24)
    private val StatusAmberLight = Color(0xFFB45309)
    private val StatusRedDark = Color(0xFFF87171)
    private val StatusRedLight = Color(0xFFDC2626)

    fun parseHexColor(hex: String, fallback: Color): Color {
        val cleaned = hex.trim().removePrefix("#")
        val value = cleaned.toLongOrNull(16) ?: return fallback
        return when (cleaned.length) {
            6 -> Color(0xFF000000L or value)
            8 -> Color(value)
            else -> fallback
        }
    }

    fun resolveComposePalette(
        config: WidgetThemeConfig,
        isSystemDark: Boolean
    ): ResolvedComposePalette {
        val normalized = config.normalized()
        val alpha = normalized.opacityPercent / 100f
        val cardAlpha = (alpha * 0.92f).coerceIn(0.28f, 0.96f)
        val elevatedAlpha = (alpha * 0.96f).coerceIn(0.35f, 1.0f)

        return when (normalized.preset) {
            WidgetThemePreset.SYSTEM -> if (isSystemDark) {
                darkPalette(
                    bgBase = Color(0xFF161922),
                    cardBase = Color(0xFF212634),
                    accent = UnraidOrange,
                    alpha = alpha,
                    cardAlpha = cardAlpha,
                    elevatedAlpha = elevatedAlpha,
                    showBorder = normalized.showBorder
                )
            } else {
                lightPalette(
                    bgBase = Color(0xFFF5F6FA),
                    cardBase = Color(0xFFFFFFFF),
                    accent = UnraidOrangeDeep,
                    alpha = alpha,
                    cardAlpha = cardAlpha,
                    elevatedAlpha = elevatedAlpha,
                    showBorder = normalized.showBorder
                )
            }

            WidgetThemePreset.FROSTED_DARK -> darkPalette(
                bgBase = Color(0xFF12151E),
                cardBase = Color(0xFF1E2433),
                accent = UnraidOrange,
                alpha = alpha,
                cardAlpha = cardAlpha,
                elevatedAlpha = elevatedAlpha,
                showBorder = normalized.showBorder
            )

            WidgetThemePreset.FROSTED_LIGHT -> lightPalette(
                bgBase = Color(0xFFF6F7FB),
                cardBase = Color(0xFFFFFFFF),
                accent = UnraidOrangeDeep,
                alpha = alpha,
                cardAlpha = cardAlpha,
                elevatedAlpha = elevatedAlpha,
                showBorder = normalized.showBorder
            )

            WidgetThemePreset.UNRAID_DARK -> darkPalette(
                bgBase = Color(0xFF171412),
                cardBase = Color(0xFF241F1C),
                accent = UnraidOrange,
                alpha = alpha,
                cardAlpha = cardAlpha,
                elevatedAlpha = elevatedAlpha,
                showBorder = normalized.showBorder
            )

            WidgetThemePreset.UNRAID_LIGHT -> lightPalette(
                bgBase = Color(0xFFFBF8F5),
                cardBase = Color(0xFFFFFFFF),
                accent = UnraidOrangeDeep,
                alpha = alpha,
                cardAlpha = cardAlpha,
                elevatedAlpha = elevatedAlpha,
                showBorder = normalized.showBorder
            )

            WidgetThemePreset.AMOLED -> darkPalette(
                bgBase = Color(0xFF000000),
                cardBase = Color(0xFF121419),
                accent = UnraidOrange,
                alpha = alpha,
                cardAlpha = (alpha * 0.96f).coerceIn(0.35f, 1.0f),
                elevatedAlpha = elevatedAlpha,
                showBorder = normalized.showBorder
            )

            WidgetThemePreset.CUSTOM -> {
                val bg = parseHexColor(normalized.customBgHex, Color(0xFF14161D))
                val card = parseHexColor(normalized.customCardHex, Color(0xFF1E222E))
                val accent = parseHexColor(normalized.customAccentHex, UnraidOrange)
                val isDark = bg.luminance() < 0.45f
                if (isDark) {
                    darkPalette(
                        bgBase = bg,
                        cardBase = card,
                        accent = accent,
                        alpha = alpha,
                        cardAlpha = cardAlpha,
                        elevatedAlpha = elevatedAlpha,
                        showBorder = normalized.showBorder
                    )
                } else {
                    lightPalette(
                        bgBase = bg,
                        cardBase = card,
                        accent = accent,
                        alpha = alpha,
                        cardAlpha = cardAlpha,
                        elevatedAlpha = elevatedAlpha,
                        showBorder = normalized.showBorder
                    )
                }
            }
        }
    }

    fun resolveGlancePalette(config: WidgetThemeConfig): ResolvedGlancePalette {
        val day = resolveComposePalette(config, isSystemDark = false)
        val night = resolveComposePalette(config, isSystemDark = true)
        return ResolvedGlancePalette(
            background = ColorProvider(day = day.background, night = night.background),
            border = ColorProvider(day = day.border, night = night.border),
            card = ColorProvider(day = day.card, night = night.card),
            cardElevated = ColorProvider(day = day.cardElevated, night = night.cardElevated),
            tabTrack = ColorProvider(day = day.tabTrack, night = night.tabTrack),
            tabSelected = ColorProvider(day = day.tabSelected, night = night.tabSelected),
            primary = ColorProvider(day = day.primary, night = night.primary),
            onPrimary = ColorProvider(day = day.onPrimary, night = night.onPrimary),
            onSurface = ColorProvider(day = day.onSurface, night = night.onSurface),
            onSurfaceVariant = ColorProvider(day = day.onSurfaceVariant, night = night.onSurfaceVariant),
            statusRunning = ColorProvider(day = day.statusRunning, night = night.statusRunning),
            statusWarning = ColorProvider(day = day.statusWarning, night = night.statusWarning),
            statusError = ColorProvider(day = day.statusError, night = night.statusError),
            progressTrack = ColorProvider(day = day.progressTrack, night = night.progressTrack)
        )
    }

    private fun darkPalette(
        bgBase: Color,
        cardBase: Color,
        accent: Color,
        alpha: Float,
        cardAlpha: Float,
        elevatedAlpha: Float,
        showBorder: Boolean
    ): ResolvedComposePalette {
        val onAccent = if (accent.luminance() > 0.55f) Color(0xFF14161D) else Color(0xFFFFFFFF)
        return ResolvedComposePalette(
            background = bgBase.copy(alpha = alpha),
            border = if (showBorder) Color.White.copy(alpha = 0.14f) else Color.Transparent,
            card = cardBase.copy(alpha = cardAlpha),
            cardElevated = cardBase.copy(alpha = elevatedAlpha),
            tabTrack = Color.White.copy(alpha = 0.08f),
            tabSelected = accent,
            primary = accent,
            onPrimary = onAccent,
            onSurface = Color(0xFFF3F5F9),
            onSurfaceVariant = Color(0xFFA6B0C3),
            statusRunning = StatusGreenDark,
            statusWarning = StatusAmberDark,
            statusError = StatusRedDark,
            progressTrack = Color.White.copy(alpha = 0.14f)
        )
    }

    private fun lightPalette(
        bgBase: Color,
        cardBase: Color,
        accent: Color,
        alpha: Float,
        cardAlpha: Float,
        elevatedAlpha: Float,
        showBorder: Boolean
    ): ResolvedComposePalette {
        val onAccent = if (accent.luminance() > 0.55f) Color(0xFF14161D) else Color(0xFFFFFFFF)
        return ResolvedComposePalette(
            background = bgBase.copy(alpha = alpha),
            border = if (showBorder) Color(0xFF0F172A).copy(alpha = 0.12f) else Color.Transparent,
            card = cardBase.copy(alpha = cardAlpha),
            cardElevated = cardBase.copy(alpha = elevatedAlpha),
            tabTrack = Color(0xFF0F172A).copy(alpha = 0.07f),
            tabSelected = accent,
            primary = accent,
            onPrimary = onAccent,
            onSurface = Color(0xFF121622),
            onSurfaceVariant = Color(0xFF556074),
            statusRunning = StatusGreenLight,
            statusWarning = StatusAmberLight,
            statusError = StatusRedLight,
            progressTrack = Color(0xFF0F172A).copy(alpha = 0.12f)
        )
    }
}
