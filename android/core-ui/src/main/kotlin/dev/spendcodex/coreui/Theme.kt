package dev.spendcodex.coreui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The journal's colours. The default palette is black, white and three greys, the same as the family's food diary; the only
 * colours are state colours: error, confirm, the green tint of money that came in and the orange of the time an operation
 * happened. An expense is plain text: it is most of the journal, and spending is information, not a warning.
 * [accent] fills what is chosen or ready (the send button, a chosen chip, the bar of a question); in the default palette it
 * is simply the text colour. There is deliberately no dynamic colour and no tonal elevation.
 */
@Immutable
class AppColors(
    val background: Color,
    val foreground: Color,
    val secondary: Color,
    val tertiary: Color,
    val border: Color,
    val pressed: Color,
    val error: Color,
    val confirm: Color,
    /** Money that came in (income, a refund): the foreground with a touch of green. */
    val income: Color,
    /** The time of an operation: the one warm colour of the journal. */
    val time: Color,
    /** What is chosen or ready to act: the send button, a chosen chip. */
    val accent: Color,
    /** Text and icons on [accent]. */
    val onAccent: Color,
    val isDark: Boolean,
)

/** The looks the person can choose. Both follow the phone's light or dark mode. */
enum class Palette(val code: String) {
    /** Black and white, as in the family's food diary. */
    Mono("mono"),

    /** Avocado, adapted from the published "Avocado" themes for KDE Plasma (light) and Omarchy (dark). */
    Avocado("avocado");

    companion object {
        fun of(code: String?): Palette = entries.firstOrNull { it.code == code } ?: Mono
    }
}

val LightColors = AppColors(
    background = Color(0xFFFFFFFF),
    foreground = Color(0xFF0A0A0A),
    secondary = Color(0xFF6B6B6B),
    tertiary = Color(0xFFA3A3A3),
    border = Color(0xFFE5E5E5),
    pressed = Color(0xFFEBEBEB),
    error = Color(0xFFC62828),
    confirm = Color(0xFF2E7D32),
    income = Color(0xFF1E4D2E),
    time = Color(0xFFC2570C),
    accent = Color(0xFF0A0A0A),
    onAccent = Color(0xFFFFFFFF),
    isDark = false,
)

/** Pure black background: on an OLED screen those pixels are off. */
val DarkColors = AppColors(
    background = Color(0xFF000000),
    foreground = Color(0xFFF2F2F2),
    secondary = Color(0xFF9A9A9A),
    tertiary = Color(0xFF666666),
    border = Color(0xFF262626),
    pressed = Color(0xFF262626),
    error = Color(0xFFFF6B6B),
    confirm = Color(0xFF5BC27C),
    income = Color(0xFFBFEFCF),
    time = Color(0xFFFF9F43),
    accent = Color(0xFFF2F2F2),
    onAccent = Color(0xFF000000),
    isDark = true,
)

/*
 * Avocado is not invented here: it adapts two published themes of that name (see docs/licenses.md).
 * Colours of the originals are kept where they read well on this page; where they do not (green or red text on the
 * yellow flesh), the same hue is darkened until it reaches 4.5:1. PaletteTest checks every pair.
 */

/**
 * Avocado by day, after the "Avocado" colour scheme for KDE Plasma (Sandrone69), on a light cream of avocado flesh: the
 * scheme's flesh #DDD48F draws the lines, the stone #4B4035 the text and #704012 the time, the skin #4A7337 what is chosen.
 */
val AvocadoLightColors = AppColors(
    background = Color(0xFFF3F1D6), // a light cream of the flesh, lighter than the scheme's #DDD48F
    foreground = Color(0xFF4B4035), // View: ForegroundNormal
    secondary = Color(0xFF5F5242), // ForegroundNormal lightened; the scheme's grey #7F8C8D is too faint here
    tertiary = Color(0xFF8C7F62),
    border = Color(0xFFDDD48F), // View: BackgroundNormal, the flesh
    pressed = Color(0xFFE8E3B5),
    error = Color(0xFF9C2A35), // ForegroundNegative #DA4453, darkened
    confirm = Color(0xFF2F5F1F),
    income = Color(0xFF2F5F1F), // ForegroundPositive's green, darkened
    time = Color(0xFF704012), // WM: inactiveBackground, the stone
    accent = Color(0xFF4A7337), // DecorationFocus, the skin
    onAccent = Color(0xFFFCFCFC), // Selection: ForegroundActive
    isDark = false,
)

/**
 * Avocado at night, after the "Avocado" theme for Omarchy/Hyprland (evanklem): true black, the light yellow-green #F2FFBE
 * for text, the bright green #B5E631 for what is chosen, the muted green #99A91D, the warm yellow #EAA621 for time.
 */
val AvocadoDarkColors = AppColors(
    background = Color(0xFF000000), // Background
    foreground = Color(0xFFF2FFBE), // Foreground
    secondary = Color(0xFF99A91D), // Secondary (dim foreground)
    tertiary = Color(0xFF5F6912),
    border = Color(0xFF1F2410),
    pressed = Color(0xFF160000), // Secondary background, the deep red-black
    error = Color(0xFFE6654F), // Error
    confirm = Color(0xFF3EAE0A), // Success
    income = Color(0xFF3EAE0A), // Success
    time = Color(0xFFEAA621), // Warning, the warm yellow
    accent = Color(0xFFB5E631), // Primary
    onAccent = Color(0xFF000000),
    isDark = true,
)

fun colorsOf(palette: Palette, dark: Boolean): AppColors = when (palette) {
    Palette.Mono -> if (dark) DarkColors else LightColors
    Palette.Avocado -> if (dark) AvocadoDarkColors else AvocadoLightColors
}

/**
 * Inter (SIL Open Font License, see docs/licenses.md): the one face of the app, with Cyrillic. "Mono" styles are Inter with
 * tabular figures: every digit is as wide as the next, so amounts line up in their column and a changing total never jitters.
 */
val Inter = FontFamily(Font(R.font.inter_regular, FontWeight.Normal))

private const val TABULAR = "tnum"

@Immutable
class AppType(
    val body: TextStyle,
    val secondary: TextStyle,
    val caption: TextStyle,
    val mono: TextStyle,
    val monoSecondary: TextStyle,
)

private fun appType(colors: AppColors, font: FontFamily = Inter) = AppType(
    body = TextStyle(fontFamily = font, fontSize = 16.sp, lineHeight = 22.sp, color = colors.foreground),
    secondary = TextStyle(fontFamily = font, fontSize = 14.sp, lineHeight = 20.sp, color = colors.secondary),
    caption = TextStyle(fontFamily = font, fontSize = 12.sp, lineHeight = 16.sp, color = colors.secondary),
    mono = TextStyle(fontFamily = font, fontSize = 15.sp, lineHeight = 20.sp, color = colors.foreground,
        fontWeight = FontWeight.Normal, fontFeatureSettings = TABULAR),
    monoSecondary = TextStyle(fontFamily = font, fontSize = 14.sp, lineHeight = 20.sp, color = colors.secondary,
        fontFeatureSettings = TABULAR),
)

private val LocalColors = staticCompositionLocalOf { LightColors }
private val LocalType = staticCompositionLocalOf { appType(LightColors) }

object AppTheme {
    val colors: AppColors
        @Composable @ReadOnlyComposable get() = LocalColors.current
    val type: AppType
        @Composable @ReadOnlyComposable get() = LocalType.current
}

@Composable
fun AppTheme(darkTheme: Boolean = isSystemInDarkTheme(), palette: Palette = Palette.Mono, content: @Composable () -> Unit) {
    val c = colorsOf(palette, darkTheme)
    // Material 3 is only the foundation: every surface is the background colour, nothing is tinted.
    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = c.accent, onPrimary = c.onAccent, background = c.background, onBackground = c.foreground,
            surface = c.background, onSurface = c.foreground, surfaceVariant = c.pressed, onSurfaceVariant = c.secondary,
            surfaceTint = Color.Transparent, outline = c.border, outlineVariant = c.border, error = c.error,
            secondaryContainer = c.pressed, onSecondaryContainer = c.foreground, primaryContainer = c.pressed,
            onPrimaryContainer = c.foreground, surfaceContainerHigh = c.background, surfaceContainer = c.background,
            surfaceContainerHighest = c.pressed,
        )
    } else {
        lightColorScheme(
            primary = c.accent, onPrimary = c.onAccent, background = c.background, onBackground = c.foreground,
            surface = c.background, onSurface = c.foreground, surfaceVariant = c.pressed, onSurfaceVariant = c.secondary,
            surfaceTint = Color.Transparent, outline = c.border, outlineVariant = c.border, error = c.error,
            secondaryContainer = c.pressed, onSecondaryContainer = c.foreground, primaryContainer = c.pressed,
            onPrimaryContainer = c.foreground, surfaceContainerHigh = c.background, surfaceContainer = c.background,
            surfaceContainerHighest = c.pressed,
        )
    }
    CompositionLocalProvider(LocalColors provides c, LocalType provides appType(c)) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
