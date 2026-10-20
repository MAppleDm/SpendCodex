package dev.spendcodex.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.spendcodex.coreui.AppColors
import dev.spendcodex.coreui.Palette
import dev.spendcodex.coreui.colorsOf
import dev.spendcodex.data.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Every palette, light and dark, keeps the journal readable: WCAG contrast of each text colour on its background. */
@RunWith(AndroidJUnit4::class)
class PaletteTest {
    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance() + 0.05
        val lb = b.luminance() + 0.05
        return maxOf(la, lb).toDouble() / minOf(la, lb)
    }

    private fun check(name: String, c: AppColors) {
        fun text(what: String, fg: Color, bg: Color = c.background, min: Double = 4.5) {
            val r = contrast(fg, bg)
            assertTrue("$name: $what is ${"%.2f".format(r)}:1, needs $min", r >= min)
        }
        text("text", c.foreground)
        text("secondary text", c.secondary)
        text("time", c.time)
        text("income", c.income)
        text("error", c.error)
        text("text on the accent", c.onAccent, c.accent)
        // placeholders and archived names are deliberately quiet, but never invisible
        text("tertiary", c.tertiary, min = 2.4)
        text("actions and chosen chips against the page", c.accent)
    }

    @Test
    fun `every palette is readable in light and dark`() {
        for (p in Palette.entries) for (dark in listOf(false, true)) {
            val c = colorsOf(p, dark)
            assertEquals(dark, c.isDark)
            check("${p.code} ${if (dark) "dark" else "light"}", c)
        }
    }

    @Test
    fun `the black and white palette looks as it always did`() {
        val light = colorsOf(Palette.Mono, false)
        assertEquals(light.foreground, light.accent)
        assertEquals(light.background, light.onAccent)
        val dark = colorsOf(Palette.Mono, true)
        assertEquals(Color(0xFF000000), dark.background)
        assertEquals(dark.foreground, dark.accent)
    }

    @Test
    fun `the chosen look is kept on the phone and survives erasing the journal`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val settings = Settings(context)
        settings.setTheme(Palette.Avocado.code)
        assertEquals(Palette.Avocado, Palette.of(Settings(context).theme.value))
        settings.reset()
        assertEquals(Palette.Avocado, Palette.of(Settings(context).theme.value))
        settings.setTheme(Palette.Mono.code)
        assertEquals(Palette.Mono, Palette.of(null))
        assertEquals(Palette.Mono, Palette.of("unknown"))
    }
}
