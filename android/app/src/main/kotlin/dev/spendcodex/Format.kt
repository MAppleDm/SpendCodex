package dev.spendcodex

import dev.spendcodex.data.domain.Currencies
import dev.spendcodex.data.domain.Decimal
import dev.spendcodex.data.settings.Lang
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle

/** Amounts and dates as people read them, in the chosen language. Files never use these: they have their own machine form. */
object Format {
    private const val NBSP = ' '

    /** "1 234,50 €", "−4,50 €", "+3 000 ₽"; an unknown currency code is shown as it is. */
    fun money(minor: Long, code: String, withSign: Boolean = false): String {
        val c = Currencies.of(code) ?: return "$minor $code"
        return Decimal.display(minor, c.exponent, Lang.current.decimal, withSign) + NBSP + c.sign
    }

    /** The plain number, for an input field: "4,50" in Russian, "4.50" in English. */
    fun plain(minor: Long, code: String): String {
        val c = Currencies.of(code) ?: return minor.toString()
        return Decimal.format(minor, c.exponent).let { if (Lang.current.decimal == '.') it else it.replace('.', Lang.current.decimal) }
    }

    fun sign(code: String): String = Currencies.of(code)?.sign ?: code

    private val dayMonth get() = DateTimeFormatter.ofPattern(if (Lang.en) "MMMM d" else "d MMMM", Lang.current.locale)
    private val dayMonthYear get() = DateTimeFormatter.ofPattern(if (Lang.en) "MMMM d, yyyy" else "d MMMM yyyy", Lang.current.locale)
    private val shortDay get() = DateTimeFormatter.ofPattern(if (Lang.en) "MMM d" else "d MMM", Lang.current.locale)
    private val numeric get() = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    private val clock = DateTimeFormatter.ofPattern("HH:mm")

    private fun weekday(d: LocalDate) = d.dayOfWeek.getDisplayName(TextStyle.SHORT, Lang.current.locale).lowercase().trimEnd('.')

    /** The heading of a day in the journal: "Сегодня, 20 октября", "Вчера, 19 октября", "18 октября, сб", "3 марта 2025, пн". */
    fun dayTitle(d: LocalDate, today: LocalDate): String {
        val base = if (d.year == today.year) d.format(dayMonth) else d.format(dayMonthYear)
        return when (d) {
            today -> Texts.TODAY.replaceFirstChar { it.uppercase() } + ", " + base
            today.minusDays(1) -> Texts.YESTERDAY.replaceFirstChar { it.uppercase() } + ", " + base
            else -> base + ", " + weekday(d)
        }
    }

    /** A date in a line of text: "20 октября 2026". */
    fun date(d: LocalDate): String = d.format(dayMonthYear)

    /** A short date for tokens: "сегодня", "вчера", "16 окт, пт". */
    fun shortDate(d: LocalDate, today: LocalDate): String = when (d) {
        today -> Texts.TODAY
        today.minusDays(1) -> Texts.YESTERDAY
        today.minusDays(2) -> Texts.BEFORE_YESTERDAY
        else -> d.format(shortDay).trimEnd('.') + (if (d.year != today.year) " ${d.year}" else "") + ", " + weekday(d)
    }

    fun numericDate(d: LocalDate): String = d.format(numeric)

    fun parseNumericDate(text: String): LocalDate? = runCatching { LocalDate.parse(text.trim(), numeric) }.getOrNull()
        ?: runCatching { LocalDate.parse(text.trim()) }.getOrNull()

    fun time(t: LocalTime): String = t.format(clock)

    fun parseTime(text: String): LocalTime? {
        val m = Regex("^([01]?\\d|2[0-3])[:.]([0-5]\\d)$").matchEntire(text.trim()) ?: return null
        return LocalTime.of(m.groupValues[1].toInt(), m.groupValues[2].toInt())
    }

    /** When a record was made or changed, in the phone's zone: "20.10.2026 12:15". */
    fun moment(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val local = Instant.ofEpochMilli(epochMs).atZone(zone)
        return local.toLocalDate().format(numeric) + " " + local.toLocalTime().format(clock)
    }

    /** "1 окт – 20 окт 2026" style range for previews. */
    fun range(from: LocalDate?, to: LocalDate?): String? {
        if (from == null && to == null) return null
        if (from == to) return from?.let(::date)
        return "${from?.let(::numericDate) ?: "…"} – ${to?.let(::numericDate) ?: "…"}"
    }
}
