package dev.spendcodex

import dev.spendcodex.data.backup.ImportError
import dev.spendcodex.data.backup.ImportProblem
import dev.spendcodex.data.domain.AccountKind
import dev.spendcodex.data.domain.Problem
import dev.spendcodex.data.domain.TxType
import dev.spendcodex.data.parse.LineIssue
import dev.spendcodex.data.repo.EditError
import dev.spendcodex.data.settings.Lang.t
import dev.spendcodex.ui.update.UpdateProblem

/**
 * All user-facing text in one place, in Russian and English. The language is the user's choice in settings; the screens
 * are rebuilt when it changes.
 */
object Texts {
    val APP get() = "SpendCodex"

    // ---------- journal ----------
    val INPUT_PLACEHOLDER get() = t("кофе 4.50", "coffee 4.50")
    val EMPTY_TITLE get() = t("Запиши первый расход.", "Record your first expense.")
    val EMPTY_HINT get() = t(
        "Напиши, как было: «кофе 350», «вчера такси домой 820». DeepSeek разберёт расходы, выберет категории и теги. Одно нажатие — запись готова. Исправить её можно в ленте.",
        "Write what happened: “coffee 4.50”, “taxi home 12 yesterday”. DeepSeek reads the expenses and chooses categories and tags. Send once to save. Tap a record to edit it.",
    )
    val TODAY get() = t("сегодня", "today")
    val YESTERDAY get() = t("вчера", "yesterday")
    val BEFORE_YESTERDAY get() = t("позавчера", "2 days ago")
    val TODAY_NOTHING get() = t("ничего не потрачено", "nothing spent")
    val BALANCES get() = t("Остатки", "Balances")
    val BALANCE_NO_OPENING get() = t("без начального остатка", "no opening balance")
    fun balanceIncomplete(n: Int) = t("не учтено: $n в другой валюте", "not counted: $n in another currency")
    val CD_SEARCH get() = t("Поиск", "Search")
    val CD_SETTINGS get() = t("Настройки", "Settings")
    val MANUAL_ENTRY get() = t("Добавить вручную", "Add manually")
    val EDIT get() = t("исправить", "edit")
    val LOAD_MORE get() = t("ещё", "more")

    fun recorded(n: Int, what: String) = if (n == 1) t("Записано: $what", "Recorded: $what") else t("Записано операций: $n", "Recorded: $n operations")
    val SAVED get() = t("Сохранено", "Saved")
    fun deleted(what: String) = t("Удалено: $what", "Deleted: $what")
    fun repeated(what: String) = t("Повторено: $what", "Repeated: $what")
    val UNDO get() = t("отменить", "undo")
    val RESTORE_ONE get() = t("вернуть", "bring back")
    val UNDONE get() = t("Отменено", "Undone")

    // the parsed line
    val SUGGESTIONS get() = t("Как раньше", "Like before")
    val NO_CATEGORY get() = t("без категории", "no category")
    val SEND_TO_AI get() = t("Строку прочитает DeepSeek: нажми «записать».", "DeepSeek will read the line: tap “record”.")
    val AI_READING get() = t("DeepSeek разбирает расход…", "DeepSeek is reading the expense…")
    val AI_SAVING get() = t("Сохраняю…", "Saving…")
    val AI_RESULT get() = t("Уточни перед записью", "Check before recording")
    val AI_DROP get() = t("не записывать", "discard")
    val AI_RECORD get() = t("записать", "record")
    val AI_RETRY get() = t("повторить", "try again")
    val AI_CLOSE get() = t("закрыть", "close")
    val AI_AMOUNT_NOT_IN_TEXT get() = t("Этой суммы нет в тексте. Уточни расход или исправь сумму вручную.", "This amount is not in your text. Clarify the expense or correct the amount manually.")
    val AI_SEND get() = t("Записать через DeepSeek", "Record with DeepSeek")
    val AI_CONNECT get() = t("Настроить DeepSeek", "Set up DeepSeek")
    val AI_CONNECT_HINT get() = t("Для ввода текстом нужен ключ DeepSeek.", "Text entry needs a DeepSeek key.")
    val AI_INPUT_LONG get() = t("Слишком длинная запись. Раздели её на несколько сообщений.", "This note is too long. Split it into several messages.")
    val AI_EXPENSES_ONLY get() = t("Здесь записываем расходы. Доходы, переводы и остатки в журнал не добавлены. Возврат можно оформить из покупки.", "This journal records expenses. Income, transfers and balances were not added. Open a purchase to record its refund.")
    val AI_NEEDS_AMOUNT get() = t("Укажи сумму каждого расхода — DeepSeek не будет её угадывать.", "Add the amount of each expense. DeepSeek will not guess it.")
    val AI_UNCLEAR get() = t("Не удалось однозначно понять расход. Уточни сумму, покупку или дату.", "The expense is unclear. Clarify the amount, purchase or date.")
    val AI_BAD_CURRENCY get() = t("DeepSeek вернул неизвестную валюту. Уточни её в тексте.", "DeepSeek returned an unknown currency. Specify it in the text.")
    val AI_BAD_DATE get() = t("DeepSeek вернул некорректную или будущую дату. Уточни её.", "DeepSeek returned an invalid or future date. Clarify it.")
    val AI_BAD_TIME get() = t("DeepSeek вернул некорректное время. Уточни его.", "DeepSeek returned an invalid time. Clarify it.")
    val AI_BAD_TAGS get() = t("DeepSeek вернул некорректные теги. Исправь запись или повтори разбор.", "DeepSeek returned invalid tags. Edit the record or try again.")
    fun aiUnknownCategory(name: String) = t("категории «$name» нет", "no category “$name”")
    fun aiFailed(detail: String) = t("DeepSeek не помог: $detail", "DeepSeek could not help: $detail")
    val AI_NO_KEY get() = t("Нет ключа DeepSeek: добавь его в настройках.", "No DeepSeek key: add it in settings.")
    val AI_AUTH get() = t("DeepSeek не принял ключ или на нём нет баланса.", "DeepSeek refused the key, or it has no balance.")

    fun issue(i: LineIssue): String = when (i) {
        LineIssue.NoAmount -> t("Не вижу суммы.", "No amount.")
        is LineIssue.SeveralNumbers -> t("Сумма ${i.chosen}; «${i.others.joinToString(" ")}» осталось в описании.", "Amount ${i.chosen}; “${i.others.joinToString(" ")}” stays in the description.")
        is LineIssue.AmbiguousNumber -> t("«${i.text}» прочитано как тысячи. Если это дробь, напиши через запятую с двумя знаками.", "“${i.text}” read as thousands. For a fraction, write two decimals.")
        is LineIssue.AmbiguousAccount -> t("«${i.word}» подходит к нескольким счетам: выбери счёт.", "“${i.word}” fits several accounts: choose one.")
        LineIssue.TransferTarget -> t("Перевод: на какой счёт?", "Transfer: to which account?")
        is LineIssue.DateLastYear -> t("Дата в прошлом году: ${Format.date(i.date)}.", "Last year's date: ${Format.date(i.date)}.")
        is LineIssue.CategoryConflict -> t("Категория «${i.word}» не подходит к типу и не взята.", "Category “${i.word}” does not fit the type and was left out.")
    }

    // ---------- types ----------
    fun type(t: TxType) = when (t) {
        TxType.Expense -> t("расход", "expense")
        TxType.Income -> t("доход", "income")
        TxType.Transfer -> t("перевод", "transfer")
        TxType.Refund -> t("возврат", "refund")
        TxType.Adjustment -> t("корректировка", "adjustment")
    }

    fun typeTitle(t: TxType) = type(t).replaceFirstChar { it.uppercase() }

    fun accountKind(k: AccountKind) = when (k) {
        AccountKind.Cash -> t("наличные", "cash")
        AccountKind.Card -> t("карта", "card")
        AccountKind.Bank -> t("банковский счёт", "bank account")
        AccountKind.Savings -> t("накопления", "savings")
        AccountKind.Other -> t("другое", "other")
    }

    // ---------- operation form ----------
    val NEW_OPERATION get() = t("Новый расход", "New expense")
    val OPERATION get() = t("Операция", "Operation")
    val RECORD get() = t("записать", "record")
    val SAVE get() = t("сохранить", "save")
    val CANCEL get() = t("отмена", "cancel")
    val AMOUNT get() = t("Сумма", "Amount")
    val CURRENCY get() = t("Валюта", "Currency")
    /** Only old transfers and adjustments, from before 0.3, show an account; nothing else has a payment method. */
    val ACCOUNT get() = t("Счёт", "Account")
    val TO_ACCOUNT get() = t("Куда", "To")
    fun toAmount(code: String) = t("Пришло, $code", "Arrived, $code")
    val CATEGORY get() = t("Категория", "Category")
    val DATE get() = t("Дата", "Date")
    val TIME get() = t("Время", "Time")
    val TIME_NOW get() = t("сейчас", "now")
    val TIME_UNKNOWN get() = t("не указано", "not set")
    val TIME_HINT get() = t("ЧЧ:ММ", "HH:MM")
    val DESCRIPTION get() = t("Что это было", "What it was")
    val PAYEE get() = t("Магазин или получатель", "Merchant or payee")
    val TAGS get() = t("Теги", "Tags")
    val TAGS_HINT get() = t("через пробел: работа отпуск", "separated by spaces: work trip")
    val NOTE get() = t("Заметка", "Note")
    val REFUND_OF get() = t("К покупке", "For the purchase")
    val REFUND_NONE get() = t("не связывать", "not linked")
    val REPEAT get() = t("повторить сегодня", "repeat today")
    val MAKE_REFUND get() = t("оформить возврат", "make a refund")
    val DELETE get() = t("удалить", "delete")
    val OTHER_DATE get() = t("другая дата", "another date")
    val PICK get() = t("выбрать", "choose")
    fun created(at: String, origin: String) = t("Создано $at · $origin", "Created $at · $origin")
    fun updated(at: String) = t("изменено $at", "changed $at")
    fun origin(code: String, input: String?) = when (code) {
        "line" -> t("быстрой строкой", "quick line") + (input?.let { " «$it»" } ?: "")
        "ai" -> t("через DeepSeek", "via DeepSeek") + (input?.let { " «$it»" } ?: "")
        "repeat" -> t("повтором", "repeated")
        "reconcile" -> t("сверкой остатка", "balance check")
        else -> t("формой", "form")
    }

    fun problem(p: Problem): String = when (p) {
        Problem.AmountMissing -> t("Нужна сумма.", "An amount is needed.")
        Problem.AmountInvalid -> t("Сумма не читается или в ней больше знаков после запятой, чем у валюты.", "The amount cannot be read, or it has more decimals than the currency.")
        Problem.AmountNotPositive -> t("Сумма должна быть больше нуля.", "The amount must be above zero.")
        Problem.AmountTooLarge -> t("Слишком большая сумма.", "The amount is too large.")
        Problem.AdjustmentZero -> t("Корректировка на ноль ничего не меняет.", "An adjustment of zero changes nothing.")
        Problem.CurrencyUnknown -> t("Неизвестная валюта.", "Unknown currency.")
        Problem.AccountMissing -> t("Нужен счёт.", "An account is needed.")
        Problem.AccountUnknown -> t("Такого счёта нет.", "No such account.")
        Problem.AccountArchived -> t("Счёт в архиве: верни его из архива или выбери другой.", "The account is archived: unarchive it or choose another.")
        Problem.AccountAmountInvalid -> t("Сумма списания не читается.", "The account amount cannot be read.")
        Problem.AccountAmountUnneeded -> t("Сумма списания нужна только для другой валюты.", "The account amount is only for another currency.")
        Problem.AdjustmentCurrency -> t("Корректировка делается в валюте счёта.", "An adjustment is in the account's currency.")
        Problem.TransferTargetMissing -> t("Куда перевести?", "Transfer to which account?")
        Problem.TransferTargetUnknown -> t("Счёта назначения нет.", "No such target account.")
        Problem.TransferTargetArchived -> t("Счёт назначения в архиве.", "The target account is archived.")
        Problem.TransferSameAccount -> t("Перевод на тот же счёт.", "A transfer to the same account.")
        Problem.TransferCurrency -> t("Перевод уходит в валюте счёта списания.", "A transfer leaves in its account's currency.")
        Problem.TransferAmountMissing -> t("Сколько пришло на счёт в другой валюте?", "How much arrived in the other currency?")
        Problem.TransferAmountInvalid -> t("Пришедшая сумма не читается.", "The arrived amount cannot be read.")
        Problem.TransferAmountUnneeded -> t("Пришедшая сумма нужна только между валютами.", "The arrived amount is only for different currencies.")
        Problem.CategoryUnknown -> t("Такой категории нет.", "No such category.")
        Problem.CategoryWrongKind -> t("Категория не того вида: расходная или доходная.", "The category is of the other kind (expense or income).")
        Problem.CategoryNotAllowed -> t("У переводов и корректировок нет категории.", "Transfers and adjustments have no category.")
        Problem.CategoryArchived -> t("Категория в архиве.", "The category is archived.")
        Problem.RefundTargetUnknown -> t("Исходной покупки нет.", "The purchase is not there.")
        Problem.RefundTargetNotExpense -> t("Возврат связывается только с расходом.", "A refund links only to an expense.")
        Problem.RefundLinkNotRefund -> t("Связь с покупкой бывает только у возврата.", "Only a refund links to a purchase.")
        Problem.TagUnknown -> t("Такого тега нет.", "No such tag.")
        Problem.DateOutOfRange -> t("Дата вне допустимого диапазона.", "The date is out of range.")
        Problem.TextTooLong -> t("Слишком длинный текст.", "The text is too long.")
    }

    // ---------- search ----------
    val SEARCH_PLACEHOLDER get() = t("Поиск: слово, сумма, категория, тег", "Search: a word, an amount, a category, a tag")
    val PERIOD get() = t("Период", "Period")
    val TYPES get() = t("Тип", "Type")
    val LEGACY_TYPES get() = t("Ранее записанные операции", "Previously recorded operations")
    val CATEGORIES get() = t("Категории", "Categories")
    val CURRENCIES get() = t("Валюта", "Currency")
    val RESET get() = t("сбросить", "reset")
    val EXPORT_FOUND get() = t("выгрузить", "export")
    val NOTHING_FOUND get() = t("Ничего не найдено.", "Nothing found.")
    val SEARCH_HINT get() = t("Ищет в описании, получателе, заметке, сумме, а также в названиях категории и тегов.",
        "Looks in the description, payee, note, amount, and the names of category and tags.")
    fun found(n: Int) = t("$n ${plural(n, "операция", "операции", "операций")}", "$n ${if (n == 1) "operation" else "operations"}")
    val SPENT get() = t("расходы", "spent")
    val EARNED get() = t("доходы", "earned")
    val REFUNDED get() = t("возвраты", "refunds")
    val ALL_TIME get() = t("всё время", "all time")
    val THIS_MONTH get() = t("этот месяц", "this month")
    val LAST_MONTH get() = t("прошлый месяц", "last month")
    val THIS_YEAR get() = t("этот год", "this year")
    val LAST_12 get() = t("последние 12 месяцев", "last 12 months")
    val CUSTOM_PERIOD get() = t("свой период", "custom period")
    val FROM get() = t("с", "from")
    val TO get() = t("по", "to")
    val DATE_HINT get() = t("ДД.ММ.ГГГГ", "DD.MM.YYYY")

    // ---------- lists ----------
    val NAME get() = t("Название", "Name")
    val KIND get() = t("Вид", "Kind")
    val ARCHIVE get() = t("в архив", "archive")
    val UNARCHIVE get() = t("вернуть из архива", "unarchive")
    val ARCHIVED get() = t("Архив", "Archived")
    val DELETE_LOCKED get() = t("Удалить нельзя: на это ссылаются операции. Можно убрать в архив, история останется.",
        "Cannot delete: operations point to it. It can be archived; the history stays.")

    // ---------- categories and tags ----------
    val CATEGORIES_AND_TAGS get() = t("Категории и теги", "Categories and tags")
    val EXPENSES get() = t("Расходы", "Expenses")
    val INCOMES get() = t("Доходы", "Income")
    val TAGS_SECTION get() = t("Теги", "Tags")
    val ADD_CATEGORY get() = t("+ категория", "+ category")
    val ADD_SUBCATEGORY get() = t("+ подкатегория", "+ subcategory")
    val NEW_CATEGORY get() = t("Новая категория", "New category")
    val PARENT get() = t("Внутри категории", "Inside category")
    val PARENT_NONE get() = t("нет, верхний уровень", "none, top level")
    val KEYWORDS get() = t("Слова для быстрой строки", "Words for the quick line")
    val KEYWORDS_HINT get() = t("Через запятую: кофе, капучино. Подсказки для выбора категории в DeepSeek.", "Comma separated: coffee, latte. Hints for DeepSeek when choosing a category.")
    val KIND_LOCKED get() = t("Вид нельзя сменить: категория используется.", "The kind cannot change: the category is in use.")
    val TAG get() = t("Тег", "Tag")
    val NO_TAGS get() = t("DeepSeek добавит теги при записи расходов. Свои можно указать через # или в форме.", "DeepSeek adds tags when recording expenses. Add your own with # or in the form.")
    fun uses(n: Int) = t("$n ${plural(n, "операция", "операции", "операций")}", "$n ${if (n == 1) "operation" else "operations"}")

    fun editError(e: EditError) = when (e) {
        EditError.NameMissing -> t("Нужно название.", "A name is needed.")
        EditError.NameTaken -> t("Такое название уже есть.", "This name is taken.")
        EditError.CurrencyUnknown -> t("Неизвестная валюта.", "Unknown currency.")
        EditError.CurrencyLocked -> t("Валюту нельзя сменить: есть операции.", "The currency cannot change: there are operations.")
        EditError.AmountInvalid -> t("Сумма не читается.", "The amount cannot be read.")
        EditError.ParentInvalid -> t("Так вложить нельзя: один уровень и тот же вид.", "Cannot nest like that: one level, same kind.")
        EditError.KindLocked -> KIND_LOCKED
        EditError.InUse -> DELETE_LOCKED
        EditError.NotFound -> t("Уже удалено.", "Already gone.")
    }

    // ---------- data ----------
    val DATA get() = t("Данные", "Data")
    val RECEIPTS get() = t("Фото чеков", "Receipt photos")
    val RECEIPT_CAMERA get() = t("Камера", "Camera")
    val RECEIPT_GALLERY get() = t("Из галереи", "From gallery")
    val RECEIPT_HINT get() = t("Фото сжимаются на телефоне и сохраняются вместе с операцией. Нажми на фото, чтобы прочитать чек.",
        "Photos are compressed on this phone and saved with the operation. Tap a photo to read the receipt.")
    val RECEIPT_PROCESSING get() = t("Подготавливаю фото…", "Preparing photos…")
    val RECEIPT_FAILED get() = t("Не удалось прочитать фото. Выбери другой снимок или обрежь его в галерее.", "Could not read the photo. Choose another image or crop it in your gallery.")
    val RECEIPT_CAMERA_FAILED get() = t("Не удалось открыть камеру. Можно выбрать фото из галереи.", "Could not open the camera. You can choose a photo from your gallery.")
    val RECEIPT_DUPLICATE get() = t("Это фото уже прикреплено.", "This photo is already attached.")
    val RECEIPT_LIMIT get() = t("К одной операции можно прикрепить до 10 фото.", "You can attach up to 10 photos to one operation.")
    val RECEIPT_REMOVED get() = t("Фото убрано. Изменение применится при сохранении.", "Photo removed. The change takes effect when you save.")
    val RECEIPT_UNAVAILABLE get() = t("Загрузка фото… Если снимок не откроется, закрой его и попробуй снова.", "Loading photo… If it does not open, close it and try again.")
    val RECEIPT_ZOOM get() = t("Разведи пальцы, чтобы увеличить. Двойное нажатие меняет масштаб.", "Pinch to zoom. Double tap to change the scale.")
    val OPERATION_SAVE_FAILED get() = t("Не удалось сохранить операцию. Изменения остались в форме — попробуй ещё раз.", "Could not save the operation. Your changes are still in the form; try again.")
    fun receiptPhoto(n: Int) = t("Фото чека $n", "Receipt photo $n")
    fun receiptCount(n: Int) = t("фото: $n", "photos: $n")
    fun restorePhotos(saved: Int, current: Int) = t("Фото в копии: $saved. На телефоне сейчас: $current. При восстановлении они тоже заменятся.",
        "Photos in the backup: $saved. Currently on this phone: $current. Restoring replaces them too.")
    val RECEIPTS_EXPORT_HINT get() = t("Фото чеков входят только в полную копию. Выгрузки для анализа содержат описание вложений без изображений.",
        "Receipt images are included only in a full backup. Analysis exports contain attachment metadata without the images.")
    val BACKUP get() = t("Резервная копия", "Backup")
    val BACKUP_HINT get() = t(
        "Полная копия журнала: операции, категории, теги, фото чеков и настройки, с описанием формата. Из неё восстанавливается всё, на этом или другом телефоне.",
        "A full copy of the journal: operations, categories, tags, receipt photos and settings, with the format described. Everything can be restored from it, on this phone or another.",
    )
    fun lastBackup(at: String?) = if (at == null) t("Копия ещё не сохранялась.", "No backup saved yet.") else t("Последняя копия: $at", "Last backup: $at")
    val BACKUP_SAVE get() = t("Сохранить копию", "Save a backup")
    val BACKUP_SHARE get() = t("Отправить копию", "Send a backup")
    val RESTORE get() = t("Восстановить из копии", "Restore from a backup")
    val RESTORE_READING get() = t("Читаю и проверяю копию…", "Reading and checking the backup…")
    fun restoreSummary(date: String, version: String, categories: Int, tags: Int, ops: Int, range: String?) = t(
        "Копия от $date (SpendCodex $version): категорий $categories, тегов $tags, операций $ops" + (range?.let { ", $it" } ?: "") + ".",
        "Backup of $date (SpendCodex $version): $categories categories, $tags tags, $ops operations" + (range?.let { ", $it" } ?: "") + ".",
    )
    fun restoreReplaces(n: Int) = t(
        "Сейчас в журнале операций: $n. Всё будет заменено содержимым копии. Текущие данные сначала сохранятся в страховочную копию на телефоне.",
        "The journal now has $n operations. Everything is replaced by the backup. The current data is first saved to a safety copy on the phone.",
    )
    val RESTORE_DO get() = t("заменить данные", "replace the data")
    val RESTORE_CONFIRM get() = t("точно заменить?", "really replace?")
    val RESTORED get() = t("Данные восстановлены.", "The data is restored.")
    val RESTORE_FAILED get() = t("Копию нельзя восстановить:", "This backup cannot be restored:")
    val SAFETY_COPY get() = t("Копия до восстановления", "Copy from before the restore")
    val SAFETY_HINT get() = t("Журнал, каким он был перед последним восстановлением.", "The journal as it was before the last restore.")
    val EXPORT get() = t("Выгрузка для анализа", "Export for analysis")
    val EXPORT_HINT get() = t(
        "Для ChatGPT, Claude, Gemini, таблиц и своих скриптов. Приложение ничего никуда не отправляет само: файл сохраняешь или отправляешь ты.",
        "For ChatGPT, Claude, Gemini, spreadsheets and your own scripts. The app sends nothing anywhere by itself: you save or send the file.",
    )
    val NOTES get() = t("Заметки и набранный текст", "Notes and typed text")
    val INCLUDED get() = t("включать", "include")
    val EXCLUDED get() = t("не включать", "leave out")
    fun exportPreview(n: Int, range: String?, notes: Boolean) = t(
        "Будет выгружено: операций $n" + (range?.let { ", $it" } ?: "") + "; заметки: ${if (notes) "да" else "нет"}. Ключ DeepSeek и настройка защиты не выгружаются никогда.",
        "To be exported: $n operations" + (range?.let { ", $it" } ?: "") + "; notes: ${if (notes) "yes" else "no"}. The DeepSeek key and the lock setting are never exported.",
    )
    val FROM_SEARCH get() = t("По поиску", "From the search")
    fun kindTitle(kind: dev.spendcodex.data.backup.ExportKind) = when (kind) {
        dev.spendcodex.data.backup.ExportKind.Ai -> t("Для ИИ (.zip)", "For AI (.zip)")
        dev.spendcodex.data.backup.ExportKind.Json -> "JSON (.json)"
        dev.spendcodex.data.backup.ExportKind.CsvPackage -> t("Таблицы CSV (.zip)", "CSV tables (.zip)")
        dev.spendcodex.data.backup.ExportKind.TransactionsCsv -> t("Операции одной таблицей (.csv)", "Operations as one table (.csv)")
        dev.spendcodex.data.backup.ExportKind.Backup -> BACKUP
    }
    fun kindHint(kind: dev.spendcodex.data.backup.ExportKind) = when (kind) {
        dev.spendcodex.data.backup.ExportKind.Ai -> t("инструкция для модели, готовый запрос, таблицы и JSON", "instructions for the model, a ready request, tables and JSON")
        dev.spendcodex.data.backup.ExportKind.Json -> t("все сущности и связи, для скриптов", "every entity and link, for scripts")
        dev.spendcodex.data.backup.ExportKind.CsvPackage -> t("операции, счета, категории, теги, описание", "operations, accounts, categories, tags, the description")
        dev.spendcodex.data.backup.ExportKind.TransactionsCsv -> t("для Excel и Google Таблиц", "for Excel and Google Sheets")
        dev.spendcodex.data.backup.ExportKind.Backup -> BACKUP_HINT
    }
    val SAVE_FILE get() = t("сохранить", "save")
    val SHARE_FILE get() = t("отправить", "send")
    val FILE_SAVED get() = t("Файл сохранён.", "File saved.")
    val FILE_FAILED get() = t("Не удалось записать файл.", "Could not write the file.")
    val NOTHING_TO_EXPORT get() = t("В выбранном периоде нет операций.", "There are no operations in this selection.")
    val FORMAT_DOC get() = t("Описание формата", "The format")
    val FORMAT_DOC_HINT get() = t("Как устроены файлы: поля, типы, валюты, даты. Этот же текст лежит в каждой выгрузке.", "How the files are built: fields, types, currencies, dates. The same text is inside every export.")
    fun importError(e: ImportError): String {
        val what = when (e.problem) {
            ImportProblem.NotReadable -> t("файл не читается как копия SpendCodex", "the file cannot be read as a SpendCodex backup")
            ImportProblem.NotSpendCodex -> t("это не копия SpendCodex", "this is not a SpendCodex backup")
            ImportProblem.TooNew -> t("копия сделана более новой версией приложения: обнови SpendCodex", "made by a newer version of the app: update SpendCodex")
            ImportProblem.UnsupportedVersion -> t("неизвестная версия формата", "unknown format version")
            ImportProblem.Partial -> t("это выгрузка части данных для анализа, а не резервная копия", "this is a partial export for analysis, not a backup")
            ImportProblem.BadField -> t("неверное значение", "a wrong value")
            ImportProblem.DuplicateId -> t("повторяющийся id", "a repeated id")
            ImportProblem.DuplicateName -> t("повторяющееся название", "a repeated name")
            ImportProblem.MissingReference -> t("ссылка на то, чего нет в файле", "a reference to something not in the file")
            ImportProblem.RuleBroken -> t("запись нарушает правила журнала", "a record breaks a rule of the journal")
            ImportProblem.Damaged -> t("архив повреждён (контрольная сумма не сходится)", "the archive is damaged (a checksum does not match)")
            ImportProblem.TooLarge -> t("файл слишком большой", "the file is too large")
        }
        return listOfNotNull(what, e.where, e.detail).joinToString(": ")
    }

    // ---------- settings ----------
    val SETTINGS get() = t("Настройки", "Settings")
    val LANGUAGE get() = t("Язык", "Language")
    val LANGUAGE_NAME get() = t("Русский", "English")
    val THEME get() = t("Тема", "Theme")
    fun themeName(p: dev.spendcodex.coreui.Palette) = when (p) {
        dev.spendcodex.coreui.Palette.Mono -> t("чёрно-белая", "black & white")
        dev.spendcodex.coreui.Palette.Avocado -> t("авокадо 🥑", "avocado 🥑")
    }
    val DEFAULT_CURRENCY get() = t("Валюта по умолчанию", "Default currency")
    val AI get() = "DeepSeek"
    val ON get() = t("вкл", "on")
    val OFF get() = t("выкл", "off")
    val LOCK get() = t("Блокировка", "Lock")
    val LOCK_HINT get() = t("Отпечаток, лицо или PIN телефона при запуске и после минуты в фоне. Картинка приложения скрыта в списке недавних.",
        "Fingerprint, face or the phone's PIN at start and after a minute away. The app's picture is hidden in recent apps.")
    val LOCK_NOT_SET get() = t("На телефоне нет блокировки экрана: защите не с чем сверять.", "The phone has no screen lock: there is nothing to check against.")
    val LOCK_UNAVAILABLE get() = t("Телефон не умеет проверять владельца.", "This phone cannot check its owner.")
    val LOCKED get() = t("Журнал закрыт", "The journal is locked")
    val UNLOCK get() = t("Открыть", "Open")
    val LOCK_TITLE_OPEN get() = t("Открыть SpendCodex", "Open SpendCodex")
    val LOCK_TITLE_ON get() = t("Включить блокировку", "Turn the lock on")
    val LOCK_TITLE_OFF get() = t("Выключить блокировку", "Turn the lock off")
    val ABOUT get() = t("О приложении", "About")
    val ABOUT_TEXT get() = t(
        "Журнал расходов с вводом через DeepSeek. Пиши обычным текстом — сумма, категория и теги появятся в записи. Для анализа выгрузи данные в любую модель или таблицу.",
        "An expense journal with DeepSeek text entry. Write naturally to capture the amount, category and tags. Export your data to any model or spreadsheet for analysis.",
    )
    fun version(v: String) = t("Версия $v · MIT", "Version $v · MIT")
    val SOURCE get() = t("Исходный код на GitHub", "Source code on GitHub")
    val ERASE get() = t("стереть всё", "erase everything")
    val ERASE_CONFIRM get() = t("нажми ещё раз, чтобы стереть", "tap again to erase")
    val ERASE_HINT get() = t("Журнал хранится только здесь. Без резервной копии стёртое не вернуть.", "The journal is kept only here. Without a backup, erased data is gone.")

    // DeepSeek
    val AI_TITLE get() = t("Разбор строки через DeepSeek", "Reading the line with DeepSeek")
    val AI_EXPLAIN get() = t(
        "Нажатие на стрелку отправляет твой текст в DeepSeek с твоим ключом. Модель выделяет расходы, выбирает категории и добавляет теги. " +
            "После проверки записи сохраняются сразу; отмена доступна в ленте. Неоднозначный ответ потребует уточнения. " +
            "Вместе с текстом отправляются текущая дата, часовой пояс, основная валюта, категории с ключевыми словами и названия тегов. История и фото чеков не отправляются. Нужны интернет и средства на API-аккаунте DeepSeek.",
        "The send arrow submits your text to DeepSeek using your key. The model extracts expenses, chooses categories and adds tags. " +
            "Validated records save immediately, with undo in the journal. Unclear answers need clarification. " +
            "The request includes today's date, time zone, default currency, categories with keywords and tag names. Past records and receipt photos are not sent. Internet and a funded DeepSeek API account are required.",
    )
    val AI_KEY get() = t("Ключ DeepSeek", "DeepSeek key")
    val AI_GET_KEY get() = t("Получить ключ DeepSeek ↗", "Get a DeepSeek key ↗")
    val AI_KEY_HINT get() = t("Хранится зашифрованным только на этом телефоне; не попадает ни в копии, ни в выгрузки.", "Kept encrypted on this phone only; never in backups or exports.")
    val AI_KEY_SET get() = t("ключ сохранён", "key saved")
    val AI_KEY_BAD get() = t("Это не похоже на ключ.", "This does not look like a key.")
    val AI_KEY_STORE_FAILED get() = t("Телефон не смог надёжно сохранить ключ.", "The phone could not store the key securely.")
    val AI_KEY_CLEAR get() = t("удалить ключ", "remove the key")
    val AI_ENABLE get() = t("Разбирать непонятные строки через DeepSeek", "Read unclear lines with DeepSeek")
    val AI_NEEDS_KEY get() = t("Сначала сохрани ключ.", "Save a key first.")

    // ---------- first start ----------
    val SETUP_TAGLINE get() = t("Просто расходы. Пиши, как было, — DeepSeek разберёт и расставит теги.", "Just expenses. Write what happened. DeepSeek sorts the details and tags.")
    val SETUP_CURRENCY get() = t("Основная валюта", "Main currency")
    val SETUP_CURRENCY_HINT get() = t("Используется, если валюта не указана в тексте. Для ввода нужен твой ключ DeepSeek; его можно добавить на следующем экране. Ручной ввод — рядом с поиском.",
        "Used when your text does not name a currency. Add your DeepSeek key on the next screen. Manual entry is beside search.")
    val OTHER_CURRENCIES get() = t("другие валюты", "other currencies")
    val START get() = t("Начать", "Start")
    val SETUP_RESTORE get() = t("восстановить из резервной копии", "restore from a backup")

    // ---------- updates (from the family's diary) ----------
    val UPDATES get() = t("Обновления", "Updates")
    val UPDATE_HINT get() = t("Новые версии из GitHub. Проверка и скачивание — по нажатию.", "New versions from GitHub. Check and download when you choose.")
    val UPDATE_CHECK get() = t("Проверить обновления", "Check for updates")
    val UPDATE_CHECKING get() = t("Проверяю GitHub…", "Checking GitHub…")
    val UPDATE_CURRENT get() = t("Установлена актуальная версия.", "You're up to date.")
    val UPDATE_NO_RELEASES get() = t("На GitHub пока нет опубликованных стабильных релизов.", "No stable releases have been published on GitHub yet.")
    fun updateAvailable(tag: String) = t("Доступна версия $tag", "Version $tag is available")
    val UPDATE_DOWNLOAD get() = t("Скачать обновление", "Download update")
    val UPDATE_DOWNLOADING get() = t("Скачиваю обновление…", "Downloading update…")
    val UPDATE_WAITING get() = t("Загрузка приостановлена Android; продолжится автоматически.", "Download paused by Android; it will resume automatically.")
    val UPDATE_VERIFYING get() = t("Проверяю файл и подпись…", "Checking file and signature…")
    val UPDATE_READY get() = t("Обновление скачано и проверено.", "Update downloaded and verified.")
    val UPDATE_INSTALL get() = t("Установить обновление", "Install update")
    val UPDATE_INSTALL_HINT get() = t("Android попросит подтвердить установку. При необходимости разреши установку из SpendCodex.", "Android will ask you to confirm. If needed, allow installation from SpendCodex.")
    val UPDATE_REMOVE get() = t("Удалить скачанный файл", "Remove downloaded file")
    val UPDATE_RELEASE_PAGE get() = t("Релизы на GitHub", "Releases on GitHub")
    val UPDATE_NO_APK get() = t("В релизе нет однозначно подходящего APK. Выбери файл на странице релизов.", "This release has no unambiguous APK. Choose a file on the releases page.")
    fun updateProblem(problem: UpdateProblem): String = when (problem) {
        UpdateProblem.Network -> t("Не удалось связаться с GitHub. Проверь интернет и повтори.", "Could not reach GitHub. Check your connection and try again.")
        UpdateProblem.RateLimit -> t("GitHub временно ограничил запросы. Попробуй позже.", "GitHub is limiting requests. Try again later.")
        UpdateProblem.Release -> t("Не удалось прочитать релиз. Открой GitHub или повтори позже.", "Could not read the release. Open GitHub or try again later.")
        UpdateProblem.Download -> t("Не удалось скачать файл. Можно повторить загрузку.", "Download failed. You can try again.")
        UpdateProblem.Storage -> t("Не хватает места или хранилище недоступно.", "Storage is full or unavailable.")
        UpdateProblem.Integrity -> t("Файл повреждён или не совпадает с релизом. Скачай заново.", "The file is damaged or does not match the release. Download it again.")
        UpdateProblem.Incompatible -> t("Этот APK не подходит приложению или версии Android.", "This APK does not match the app or Android version.")
        UpdateProblem.Signature -> t("APK подписан другим ключом: поверх не установить. Сохрани резервную копию; не удаляй приложение ради установки.", "This APK has a different signing key. Save a backup; do not uninstall the app to install it.")
        UpdateProblem.Permission -> t("Установка из SpendCodex пока не разрешена. Нажми «Установить» и разреши её в настройках Android.", "Installation from SpendCodex is not allowed yet. Tap Install and allow it in Android settings.")
        UpdateProblem.Installer -> t("Не удалось открыть установщик Android. Файл сохранён, попробуй ещё раз.", "Could not open the Android installer. The download is saved; try again.")
    }

    // ---------- misc ----------
    val CD_BACK get() = t("Назад", "Back")
    val SHARE_TITLE get() = t("Отправить", "Send")
    val DONE get() = t("готово", "done")
    val YES get() = t("да", "yes")
    val NO get() = t("нет", "no")

    fun plural(n: Int, one: String, few: String, many: String): String {
        val n10 = n % 10
        val n100 = n % 100
        return when {
            n10 == 1 && n100 != 11 -> one
            n10 in 2..4 && n100 !in 12..14 -> few
            else -> many
        }
    }
}
