package dev.spendcodex.data.repo

import dev.spendcodex.data.domain.AccountKind
import dev.spendcodex.data.domain.CategoryKind
import dev.spendcodex.data.settings.Language

/**
 * What a new journal starts with: a short list of ordinary categories, named in the language chosen at the first start, and
 * the one account every record is booked to. The journal counts spending, not money on cards or in cash, so the account is
 * never shown; it is there because the stored format has one per record. The keywords are the words the quick line knows
 * each category by; both languages are always there, so "coffee" works in a Russian journal too.
 */
internal object Starter {
    class StarterCategory(val ru: String, val en: String, val kind: CategoryKind, val keywords: List<String>)

    class StarterAccount(val ru: String, val en: String, val kind: AccountKind)

    val accounts = listOf(StarterAccount("Расходы", "Spending", AccountKind.Other))

    val categories = listOf(
        StarterCategory("Продукты", "Groceries", CategoryKind.Expense, words(
            "продукты", "магазин", "супермаркет", "пятерочка", "перекресток", "магнит", "ашан", "лента", "вкусвилл",
            "groceries", "grocery", "supermarket", "lidl", "aldi",
        )),
        StarterCategory("Кафе и рестораны", "Cafes & restaurants", CategoryKind.Expense, words(
            "кофе", "кафе", "ресторан", "обед", "ужин", "завтрак", "бар", "пицца", "суши", "бургер", "шаурма", "кофейня", "столовая",
            "coffee", "cafe", "restaurant", "lunch", "dinner", "breakfast", "pizza", "bar",
        )),
        StarterCategory("Транспорт", "Transport", CategoryKind.Expense, words(
            "такси", "метро", "автобус", "трамвай", "электричка", "бензин", "топливо", "заправка", "парковка", "каршеринг", "проезд",
            "taxi", "uber", "bus", "metro", "subway", "train", "fuel", "parking",
        )),
        StarterCategory("Жильё", "Housing", CategoryKind.Expense, words(
            "аренда", "квартплата", "ипотека", "жкх", "коммуналка", "электричество", "rent", "mortgage", "utilities",
        )),
        StarterCategory("Связь и интернет", "Phone & internet", CategoryKind.Expense, words(
            "связь", "телефон", "интернет", "мобильный", "phone", "internet", "mobile",
        )),
        StarterCategory("Здоровье", "Health", CategoryKind.Expense, words(
            "аптека", "лекарства", "врач", "стоматолог", "анализы", "клиника", "pharmacy", "doctor", "dentist", "medicine",
        )),
        StarterCategory("Покупки", "Shopping", CategoryKind.Expense, words(
            "одежда", "обувь", "техника", "маркетплейс", "озон", "wildberries", "clothes", "shoes", "amazon",
        )),
        StarterCategory("Развлечения", "Entertainment", CategoryKind.Expense, words(
            "кино", "театр", "концерт", "игры", "музей", "cinema", "movie", "concert", "games", "museum",
        )),
        StarterCategory("Подписки", "Subscriptions", CategoryKind.Expense, words(
            "подписка", "spotify", "netflix", "youtube", "icloud", "subscription",
        )),
        StarterCategory("Образование", "Education", CategoryKind.Expense, words(
            "курсы", "книги", "учеба", "обучение", "школа", "course", "books", "school", "tuition",
        )),
        StarterCategory("Путешествия", "Travel", CategoryKind.Expense, words(
            "билеты", "отель", "гостиница", "авиабилеты", "hotel", "flight", "tickets", "airbnb",
        )),
        StarterCategory("Подарки", "Gifts", CategoryKind.Expense, words("подарок", "gift")),
        StarterCategory("Другое", "Other", CategoryKind.Expense, emptyList()),
        StarterCategory("Зарплата", "Salary", CategoryKind.Income, words("зарплата", "аванс", "зп", "salary", "payroll", "paycheck")),
        StarterCategory("Подработка", "Side income", CategoryKind.Income, words("подработка", "фриланс", "freelance")),
        StarterCategory("Проценты и кешбэк", "Interest & cashback", CategoryKind.Income, words(
            "проценты", "кешбэк", "кэшбэк", "cashback", "interest",
        )),
        StarterCategory("Другие доходы", "Other income", CategoryKind.Income, emptyList()),
    )

    private fun words(vararg w: String) = w.toList()

    fun name(ru: String, en: String, language: Language) = if (language == Language.Ru) ru else en
}
