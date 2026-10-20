package dev.spendcodex.data.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoneyTest {
    @Test
    fun `typed amounts are read exactly in minor units`() {
        assertEquals(450L, Decimal.parseHuman("4.50", 2))
        assertEquals(450L, Decimal.parseHuman("4,5", 2))
        assertEquals(400L, Decimal.parseHuman("4", 2))
        assertEquals(50L, Decimal.parseHuman(".5", 2))
        assertEquals(120_000L, Decimal.parseHuman("1 200", 2))
        assertEquals(120_050L, Decimal.parseHuman("1 200,50", 2))
        assertEquals(120_050L, Decimal.parseHuman("1,200.50", 2))
        assertEquals(120_050L, Decimal.parseHuman("1.200,50", 2))
        assertEquals(1_200_000L, Decimal.parseHuman("12к", 2))
        assertEquals(1_250_000L, Decimal.parseHuman("12.5k", 2))
        assertEquals(-2_000L, Decimal.parseHuman("−20", 2))
        assertEquals(1_234_567_800L, Decimal.parseHuman("12.345.678", 2))
    }

    @Test
    fun `a comma with three digits is a thousands separator for cents currencies only`() {
        assertEquals(120_000L, Decimal.parseHuman("1,200", 2))
        assertEquals(1_200L, Decimal.parseHuman("1,200", 3)) // Kuwaiti dinar: 1.200 KWD
    }

    @Test
    fun `more decimals than the currency has are refused, never rounded`() {
        assertNull(Decimal.parseHuman("4.5051", 2))
        assertNull(Decimal.parseHuman("0.505", 2))
        // three digits after one separator read as thousands (the quick line marks this as doubtful)
        assertEquals(450_500L, Decimal.parseHuman("4.505", 2))
        assertNull(Decimal.parseHuman("100.5", 0))
        assertEquals(100L, Decimal.parseHuman("100", 0))
    }

    @Test
    fun `garbage and overflow are refused`() {
        listOf("", "abc", "4.5.6.7", "1e5", "--5", "99999999999999999999", "1234,567").forEach {
            assertNull(it, Decimal.parseHuman(it, 2))
        }
        assertNull(Decimal.parseHuman("10000000000001", 2)) // above 10^15 minor units
    }

    @Test
    fun `strict machine form round-trips`() {
        assertEquals(450L, Decimal.parseStrict("4.50", 2))
        assertEquals(450L, Decimal.parseStrict("4.5", 2))
        assertEquals(-2_000L, Decimal.parseStrict("-20.00", 2))
        assertNull(Decimal.parseStrict("4,50", 2))
        assertNull(Decimal.parseStrict("04.50", 2))
        assertNull(Decimal.parseStrict("4.501", 2))
        assertNull(Decimal.parseStrict(" 4", 2))
        for (minor in listOf(0L, 1L, 9L, 10L, 99L, 100L, 123_456L, -1L, -100_001L, Decimal.MAX_MINOR)) {
            for (exp in 0..3) assertEquals(minor, Decimal.parseStrict(Decimal.format(minor, exp), exp))
        }
    }

    @Test
    fun `formatting for files and for people`() {
        assertEquals("4.50", Decimal.format(450, 2))
        assertEquals("0.05", Decimal.format(5, 2))
        assertEquals("-12.00", Decimal.format(-1200, 2))
        assertEquals("1200", Decimal.format(1200, 0))
        assertEquals("1.234", Decimal.format(1234, 3))
        assertEquals("1 234,50", Decimal.display(123_450, 2, ','))
        assertEquals("−1 234.50", Decimal.display(-123_450, 2, '.'))
        assertEquals("+3 000,00", Decimal.display(300_000, 2, ',', withSign = true))
        assertEquals("1 000 000", Decimal.display(1_000_000, 0, ','))
    }

    @Test
    fun `currencies know their minor digits`() {
        assertEquals(2, Currencies.require("EUR").exponent)
        assertEquals(0, Currencies.require("JPY").exponent)
        assertEquals(3, Currencies.require("KWD").exponent)
        assertEquals("€", Currencies.require("eur").sign)
        assertEquals("CHF", Currencies.require("CHF").sign)
        assertNull(Currencies.of("XYZ"))
        assertEquals(Currencies.all.size, Currencies.all.map { it.code }.distinct().size)
    }
}
