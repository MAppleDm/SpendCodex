package dev.spendcodex.coreui

/** The few words the shared components write themselves. The app switches [english] with the user's language. */
object CoreTexts {
    @Volatile var english: Boolean = false

    private fun t(ru: String, en: String) = if (english) en else ru

    val send get() = t("Записать", "Record")
    val form get() = t("Форма", "Form")
    val back get() = t("Назад", "Back")
    val close get() = t("Закрыть", "Close")
}
