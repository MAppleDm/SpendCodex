package dev.spendcodex.data.parse

import java.time.DayOfWeek

/** The fixed vocabulary of the quick line, in Russian and English. Everything is compared after [dev.spendcodex.data.domain.normalize]. */
internal object Words {
    val REFUND = setOf("возврат", "возврата", "вернули", "вернул", "вернула", "refund", "refunded", "returned")
    val TRANSFER = setOf("перевод", "перевел", "перевела", "перевёл", "перевести", "transfer", "transferred", "move", "moved")
    val INCOME = setOf("доход", "income", "получил", "получила", "received")
    val ADJUSTMENT = setOf("корректировка", "корр", "adjustment", "adjust")

    val TODAY = setOf("сегодня", "today")
    val YESTERDAY = setOf("вчера", "yesterday")
    val BEFORE_YESTERDAY = setOf("позавчера")
    val DAYS = setOf("день", "дня", "дней", "дн", "day", "days")
    val AGO = setOf("назад", "ago")

    /** Little words that only lead to the next one: dropped when what they lead to is taken ("в пятницу", "с карты"). */
    val LEADS = setOf("в", "во", "на", "с", "со", "за", "по", "из", "at", "on", "in", "from", "to", "into", "for", "by", "via")
    val FROM = setOf("с", "со", "из", "from")
    val TO = setOf("на", "в", "во", "to", "into")

    val WEEKDAYS: Map<String, DayOfWeek> = buildMap {
        fun add(day: DayOfWeek, vararg words: String) = words.forEach { put(it, day) }
        add(DayOfWeek.MONDAY, "понедельник", "пн", "monday", "mon")
        add(DayOfWeek.TUESDAY, "вторник", "вт", "tuesday", "tue", "tues")
        add(DayOfWeek.WEDNESDAY, "среда", "среду", "ср", "wednesday", "wed")
        add(DayOfWeek.THURSDAY, "четверг", "чт", "thursday", "thu", "thur", "thurs")
        add(DayOfWeek.FRIDAY, "пятница", "пятницу", "пт", "friday", "fri")
        add(DayOfWeek.SATURDAY, "суббота", "субботу", "сб", "saturday")
        add(DayOfWeek.SUNDAY, "воскресенье", "вс", "sunday")
    }

    /** Month words; used only next to a day number ("12 окт", "oct 12"), so "may" alone stays a word. */
    val MONTHS: Map<String, Int> = buildMap {
        fun add(month: Int, vararg words: String) = words.forEach { put(it, month) }
        add(1, "январь", "января", "янв", "january", "jan")
        add(2, "февраль", "февраля", "фев", "february", "feb")
        add(3, "март", "марта", "мар", "march", "mar")
        add(4, "апрель", "апреля", "апр", "april", "apr")
        add(5, "май", "мая", "may")
        add(6, "июнь", "июня", "июн", "june", "jun")
        add(7, "июль", "июля", "июл", "july", "jul")
        add(8, "август", "августа", "авг", "august", "aug")
        add(9, "сентябрь", "сентября", "сен", "сент", "september", "sep", "sept")
        add(10, "октябрь", "октября", "окт", "october", "oct")
        add(11, "ноябрь", "ноября", "ноя", "нояб", "november", "nov")
        add(12, "декабрь", "декабря", "дек", "december", "dec")
    }

    /** Currency words and signs the line understands besides the ISO codes themselves. */
    val CURRENCY: Map<String, String> = buildMap {
        fun add(code: String, vararg words: String) = words.forEach { put(it, code) }
        add("RUB", "₽", "р", "р.", "руб", "руб.", "рубль", "рубля", "рублей", "ruble", "rubles", "rouble", "roubles")
        add("USD", "$", "долл", "доллар", "доллара", "долларов", "бакс", "бакса", "баксов", "dollar", "dollars", "bucks")
        add("EUR", "€", "евро", "euro", "euros")
        add("GBP", "£", "фунт", "фунта", "фунтов", "pound", "pounds")
        add("JPY", "¥", "иена", "иены", "иен", "йена", "йены", "йен", "yen")
        add("CNY", "юань", "юаня", "юаней", "yuan", "rmb")
        add("UAH", "₴", "грн", "гривна", "гривны", "гривен", "hryvnia")
        add("KZT", "₸", "тенге", "tenge")
        add("GEL", "₾", "лари", "lari")
        add("AMD", "֏", "драм", "драма", "драмов", "dram")
        add("AZN", "₼", "манат", "маната", "манатов", "manat")
        add("TRY", "₺", "лира", "лиры", "лир", "lira", "liras")
        add("PLN", "zł", "злотый", "злотых", "zloty")
        add("CHF", "франк", "франка", "франков", "franc", "francs")
        add("INR", "₹", "рупия", "рупий", "rupee", "rupees")
        add("THB", "฿", "бат", "бата", "батов", "baht")
        add("VND", "₫", "донг", "донгов", "dong")
        add("KRW", "₩", "вон", "won")
        add("ILS", "₪", "шекель", "шекеля", "шекелей", "shekel", "shekels")
        add("AED", "дирхам", "дирхама", "дирхамов", "dirham", "dirhams")
        add("UZS", "сум", "сума", "сумов")
        add("KGS", "сом", "сома", "сомов")
        add("PHP", "₱", "песо")
        add("NGN", "₦")
        add("MNT", "₮", "тугрик", "тугриков")
    }

    /** ISO codes that are also English words: understood only when written in capitals. */
    val CODES_ONLY_UPPER = setOf("TRY", "ALL", "PEN", "CUP", "TOP", "MOP", "BOB", "SOS", "MAD", "PHP", "CAD", "BAM")
}
