package dev.spendcodex.data.domain

/**
 * A currency as the journal knows it: the ISO 4217 code and how many digits its minor unit has (2 for euro cents, 0 for
 * yen, 3 for Kuwaiti fils). Amounts are whole numbers of minor units ([Long]); there is no floating point anywhere in
 * the money path. The table is fixed in the app and travels with every export (`currencies`), so a file explains itself.
 */
data class Currency(val code: String, val exponent: Int, val symbol: String? = null) {
    /** What is printed after an amount: the symbol when it is unambiguous, else the code. */
    val sign: String get() = symbol ?: code
}

object Currencies {
    /** The supported set. Symbols only where one sign means one currency to most people. */
    val all: List<Currency> = listOf(
        Currency("RUB", 2, "₽"), Currency("EUR", 2, "€"), Currency("USD", 2, "$"), Currency("GBP", 2, "£"),
        Currency("CHF", 2), Currency("JPY", 0, "¥"), Currency("CNY", 2, "CN¥"), Currency("UAH", 2, "₴"),
        Currency("KZT", 2, "₸"), Currency("BYN", 2), Currency("GEL", 2, "₾"), Currency("AMD", 2, "֏"),
        Currency("AZN", 2, "₼"), Currency("UZS", 2), Currency("KGS", 2), Currency("TJS", 2), Currency("MDL", 2),
        Currency("TRY", 2, "₺"), Currency("PLN", 2, "zł"), Currency("CZK", 2), Currency("HUF", 2), Currency("RON", 2),
        Currency("BGN", 2), Currency("RSD", 2), Currency("SEK", 2), Currency("NOK", 2), Currency("DKK", 2),
        Currency("ISK", 0), Currency("ILS", 2, "₪"), Currency("AED", 2), Currency("SAR", 2), Currency("QAR", 2),
        Currency("KWD", 3), Currency("BHD", 3), Currency("OMR", 3), Currency("JOD", 3), Currency("EGP", 2),
        Currency("INR", 2, "₹"), Currency("THB", 2, "฿"), Currency("VND", 0, "₫"), Currency("IDR", 2), Currency("MYR", 2),
        Currency("SGD", 2), Currency("HKD", 2), Currency("TWD", 2), Currency("KRW", 0, "₩"), Currency("PHP", 2, "₱"),
        Currency("AUD", 2), Currency("NZD", 2), Currency("CAD", 2), Currency("MXN", 2), Currency("BRL", 2),
        Currency("ARS", 2), Currency("CLP", 0), Currency("COP", 2), Currency("PEN", 2), Currency("UYU", 2),
        Currency("ZAR", 2), Currency("NGN", 2, "₦"), Currency("KES", 2), Currency("MAD", 2), Currency("TND", 3),
        Currency("LKR", 2), Currency("PKR", 2), Currency("BDT", 2), Currency("NPR", 2), Currency("MNT", 2, "₮"),
        Currency("IQD", 3), Currency("LYD", 3), Currency("XAF", 0), Currency("XOF", 0), Currency("PYG", 0),
        Currency("CRC", 2), Currency("DOP", 2), Currency("ALL", 2), Currency("MKD", 2), Currency("BAM", 2),
    )

    private val byCode = all.associateBy { it.code }

    fun of(code: String?): Currency? = code?.let { byCode[it.uppercase()] }

    fun require(code: String): Currency = of(code) ?: throw IllegalArgumentException("unknown currency $code")

    /** The usual ones first in pickers: a short list of what most people of this app use. */
    val common: List<String> = listOf("RUB", "EUR", "USD", "GBP", "KZT", "UAH", "BYN", "GEL", "AMD", "TRY", "CNY", "JPY")
}

/**
 * Amounts as exact decimals over whole minor units. [MAX_MINOR] keeps every amount and every sum of a realistic journal far
 * from overflow and exactly representable even by readers that turn JSON numbers into doubles (2^53).
 */
object Decimal {
    const val MAX_MINOR: Long = 1_000_000_000_000_000L // 10^15 minor units

    /**
     * Strict machine form, as in exports: optional "-", digits, optional "." and at most [exponent] digits. "4.5" and "4.50" are
     * the same 450 cents; "4.505" is refused for a 2-digit currency (never rounded); "1e3", "4,50", " 4" are refused.
     */
    fun parseStrict(text: String, exponent: Int): Long? {
        if (!Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?").matches(text)) return null
        val negative = text.startsWith('-')
        val body = text.removePrefix("-")
        val whole = body.substringBefore('.')
        val fraction = body.substringAfter('.', "")
        if (fraction.length > exponent) return null
        return combine(whole, fraction.padEnd(exponent, '0'), negative)
    }

    /**
     * What a person types: "4.50", "4,5", "1 200", "1 200,50", "1,200.50", "12к" are all read; the decimal separator is the last
     * "." or "," when followed by at most [exponent] digits. Returns null when it is not a number or has more decimals than
     * the currency has (no silent rounding).
     */
    fun parseHuman(input: String, exponent: Int): Long? {
        var text = input.trim().replace('−', '-').replace(" ", "").replace(" ", "").replace(" ", "").replace(" ", "")
        var multiplier = 1L
        if (text.endsWith("к", ignoreCase = true) || text.endsWith("k", ignoreCase = true)) {
            multiplier = 1000L
            text = text.dropLast(1)
        }
        val negative = text.startsWith('-')
        text = text.removePrefix("-").removePrefix("+")
        if (text.isEmpty() || !text.all { it.isDigit() || it == '.' || it == ',' }) return null
        val lastSep = maxOf(text.lastIndexOf('.'), text.lastIndexOf(','))
        val whole: String
        val fraction: String
        if (lastSep < 0) {
            whole = text
            fraction = ""
        } else {
            val after = text.substring(lastSep + 1)
            val before = text.substring(0, lastSep)
            val groups = before.split('.', ',')
            val grouped = groups.first().length in 1..3 && !groups.first().startsWith('0') && groups.drop(1).all { it.length == 3 }
            if (after.length == 3 && exponent < 3 && grouped) {
                // "1,200", "1.200" or "12.345.678": thousands separators, the only reading that fits a 2-digit currency
                whole = groups.joinToString("") + after
                fraction = ""
            } else {
                // every other separator before the decimal one must be grouping, in threes
                if (groups.size > 1 && !grouped) return null
                whole = groups.joinToString("")
                fraction = after
            }
        }
        if (whole.isEmpty() && fraction.isEmpty()) return null
        if (!whole.all { it.isDigit() } || !fraction.all { it.isDigit() }) return null
        if (fraction.length > exponent) return null
        val base = combine(whole.ifEmpty { "0" }.trimStart('0').ifEmpty { "0" }, fraction.padEnd(exponent, '0'), negative) ?: return null
        val result = try {
            Math.multiplyExact(base, multiplier)
        } catch (e: ArithmeticException) {
            return null
        }
        return result.takeIf { kotlin.math.abs(it) <= MAX_MINOR }
    }

    private fun combine(whole: String, fraction: String, negative: Boolean): Long? {
        if (whole.length > 16) return null
        return try {
            var value = whole.toLong()
            repeat(fraction.length) { value = Math.multiplyExact(value, 10L) }
            value = Math.addExact(value, if (fraction.isEmpty()) 0 else fraction.toLong())
            if (value > MAX_MINOR) null else if (negative) -value else value
        } catch (e: NumberFormatException) {
            null
        } catch (e: ArithmeticException) {
            null
        }
    }

    /** Machine form for files: "4.50", "-12.00", "1200" (JPY). Always every minor digit, never grouping. */
    fun format(minor: Long, exponent: Int): String {
        val negative = minor < 0
        val digits = kotlin.math.abs(minor).toString().padStart(exponent + 1, '0')
        val body = if (exponent == 0) digits else digits.dropLast(exponent) + "." + digits.takeLast(exponent)
        return if (negative) "-$body" else body
    }

    /**
     * For people: "1 234,50" with non-breaking spaces between thousands, so an amount never wraps in the middle, and the
     * language's decimal separator. Minus is the real minus sign.
     */
    fun display(minor: Long, exponent: Int, decimalSeparator: Char, withSign: Boolean = false): String {
        val abs = kotlin.math.abs(minor)
        val digits = abs.toString().padStart(exponent + 1, '0')
        val whole = if (exponent == 0) digits else digits.dropLast(exponent)
        val grouped = whole.reversed().chunked(3).joinToString(" ").reversed()
        val body = if (exponent == 0) grouped else grouped + decimalSeparator + digits.takeLast(exponent)
        return when {
            minor < 0 -> "−$body"
            withSign && minor > 0 -> "+$body"
            else -> body
        }
    }
}

/** An amount with its currency. */
data class Money(val minor: Long, val currency: Currency) {
    fun format(): String = Decimal.format(minor, currency.exponent)

    companion object {
        fun parseStrict(text: String, currencyCode: String): Money? {
            val c = Currencies.of(currencyCode) ?: return null
            return Decimal.parseStrict(text, c.exponent)?.let { Money(it, c) }
        }
    }
}
