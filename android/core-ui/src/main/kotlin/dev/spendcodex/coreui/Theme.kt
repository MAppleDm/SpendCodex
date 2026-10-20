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

    /** Avocado: the pale flesh by day, the dark skin at night, the brown of the stone for time, its green for what is chosen. */
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

/** Avocado by day: the pale yellow-green of the flesh, text in the dark green of the skin, the stone's brown for time. */
val AvocadoLightColors = AppColors(
    background = Color(0xFFF2F5E1),
    foreground = Color(0xFF1E2914),
    secondary = Color(0xFF55663F),
    tertiary = Color(0xFF8C9A72),
    border = Color(0xFFD9E1BF),
    pressed = Color(0xFFE2E9C8),
    error = Color(0xFFB3261E),
    confirm = Color(0xFF3B6E22),
    income = Color(0xFF2E6A1C),
    time = Color(0xFF985423),
    accent = Color(0xFF4F7A1F),
    onAccent = Color(0xFFF7FAEA),
    isDark = false,
)

/** Avocado at night: the near-black green of the skin, text in the colour of the flesh, the bright green of a ripe one. */
val AvocadoDarkColors = AppColors(
    background = Color(0xFF10160A),
    foreground = Color(0xFFEBF1D0),
    secondary = Color(0xFFA5B389),
    tertiary = Color(0xFF6B7756),
    border = Color(0xFF263019),
    pressed = Color(0xFF263019),
    error = Color(0xFFFF8A80),
    confirm = Color(0xFFA6D46B),
    income = Color(0xFFC5E48A),
    time = Color(0xFFE0A86A),
    accent = Color(0xFFA4C639),
    onAccent = Color(0xFF10160A),
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
