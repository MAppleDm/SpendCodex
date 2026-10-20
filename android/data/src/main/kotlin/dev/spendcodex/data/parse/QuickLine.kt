package dev.spendcodex.data.parse

import dev.spendcodex.data.domain.Account
import dev.spendcodex.data.domain.Category
import dev.spendcodex.data.domain.CategoryKind
import dev.spendcodex.data.domain.Currencies
import dev.spendcodex.data.domain.Draft
import dev.spendcodex.data.domain.DraftTime
import dev.spendcodex.data.domain.Origin
import dev.spendcodex.data.domain.TxType
import dev.spendcodex.data.domain.normalize
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

/** What the journal remembers about a description it has seen: where such a thing went last time. */
data class Hint(val categoryId: String?, val accountId: String?)

/** What the line is read against. Only active accounts and categories are offered. */
class LineContext(
    val accounts: List<Account>,
    val categories: List<Category>,
    val today: LocalDate,
    /** The learned category and account of a normalised description, if it was written before. */
    val hint: (String) -> Hint? = { null },
)

/** Something in the line that was read one way but could be read another, or is missing. Shown, never hidden. */
sealed interface LineIssue {
    /** No number that could be the amount. Blocks recording. */
    data object NoAmount : LineIssue

    /** Several numbers: [chosen] was taken as the amount, [others] stayed in the description. */
    data class SeveralNumbers(val chosen: String, val others: List<String>) : LineIssue

    /** "1,200": read as 1 200, could have meant 1.2. */
    data class AmbiguousNumber(val text: String) : LineIssue

    /** A word that fits several accounts: none was taken. */
    data class AmbiguousAccount(val word: String) : LineIssue

    /** A transfer without the account it goes to. Blocks recording. */
    data object TransferTarget : LineIssue

    /** A day and month without a year that would be in the future: taken as last year. */
    data class DateLastYear(val date: LocalDate) : LineIssue

    /** A category word that does not fit the type ("+кафе"): the category was left out. */
    data class CategoryConflict(val word: String) : LineIssue
}

/** Where the category came from: written in the line, or remembered from the last time this description was written. */
enum class CategorySource { Written, Learned }

/**
 * One operation read from the line. [draft] is ready for [dev.spendcodex.data.domain.DraftBuilder]; the flags say what was written and
 * what is a default, so the preview can show defaults as defaults.
 */
data class LineResult(
    val draft: Draft,
    val issues: List<LineIssue>,
    val typeWritten: Boolean,
    val currencyWritten: Boolean,
    val accountWritten: Boolean,
    val accountLearned: Boolean,
    val categorySource: CategorySource?,
    val dateWritten: Boolean,
) {
    val blocking: Boolean get() = issues.any { it == LineIssue.NoAmount || it == LineIssue.TransferTarget }
}

/**
 * The quick line: "кофе 4.50", "такси 12 вчера", "+зарплата 3000", "карта → наличные 100", "возврат кроссовки 59.99",
 * "обед 12 #работа // с коллегой". Deterministic and local: the same text and the same journal always give the same reading,
 * nothing leaves the phone. Several operations can go in one line, one per line break or ";", or after ", " when every part
 * has its own amount ("кофе 4.50, круассан 3").
 */
object QuickLine {

    fun parse(text: String, ctx: LineContext): List<LineResult> =
        split(text).map { parseOne(it, ctx) }

    // ---------- splitting ----------

    internal fun split(text: String): List<String> {
        val parts = text.split('\n', ';').map { it.trim() }.filter { it.isNotEmpty() }
        return parts.flatMap { part ->
            val pieces = part.split(Regex(",\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
            // only when every piece has its own number: "кофе 4.50, круассан 3" is two, "кофе, круассан 7.50" is one
            if (pieces.size > 1 && pieces.all { p -> tokenize(p.substringBefore("//")).any { it.kind == Kind.Number } }) pieces else listOf(part)
        }.ifEmpty { listOf("") }
    }

    // ---------- tokens ----------

    internal enum class Kind { Arrow, Equals, Tag, IsoDate, DmyDate, Time, Number, Symbol, Sign, Word }

    internal class Token(val kind: Kind, val text: String, val start: Int) {
        var used = false
        val norm: String by lazy { normalize(text.trimEnd('.')) }
    }

    private val TOKEN = Regex(
        listOf(
            "(?<arrow>→|->|=>|⟶|>)",
            "(?<eq>=)",
            "(?<tag>#[\\p{L}\\p{N}_\\-]+)",
            "(?<iso>\\d{4}-\\d{2}-\\d{2})(?![\\d])",
            "(?<dmy>\\d{1,2}[./]\\d{1,2}[./](?:\\d{4}|\\d{2})|\\d{1,2}/\\d{1,2})(?![\\d./])",
            "(?<time>(?:[01]?\\d|2[0-3]):[0-5]\\d)(?![\\d])",
            // a space groups thousands only where it cannot be two numbers: "1 200", "12 300", "250 000", or a non-breaking
            // space as banks print it; "100 108" stays two numbers
            "(?<num>[+\\-−]?(?:\\d{1,3}(?:[  ]\\d{3})+(?:[.,]\\d+)?|\\d{1,2}(?: \\d{3})+(?:[.,]\\d+)?|\\d{3}(?: 000)+(?:[.,]\\d+)?|\\d+(?:[.,]\\d+)*)(?:[кkКK](?![\\p{L}]))?)",
            "(?<sym>[₽€$£¥₴₸₾֏₼₺₹฿₫₩₪₱₦₮])",
            "(?<sign>[+\\-−])",
            "(?<word>[\\p{L}][\\p{L}\\p{N}'’\\-]*\\.?)",
        ).joinToString("|"),
    )

    internal fun tokenize(text: String): List<Token> = TOKEN.findAll(text).map { m ->
        val g = m.groups
        val kind = when {
            g["arrow"] != null -> Kind.Arrow
            g["eq"] != null -> Kind.Equals
            g["tag"] != null -> Kind.Tag
            g["iso"] != null -> Kind.IsoDate
            g["dmy"] != null -> Kind.DmyDate
            g["time"] != null -> Kind.Time
            g["num"] != null -> Kind.Number
            g["sym"] != null -> Kind.Symbol
            g["sign"] != null -> Kind.Sign
            else -> Kind.Word
        }
        Token(kind, m.value, m.range.first)
    }.toList()

    // ---------- reading one operation ----------

    private class Amount(val token: Token, var currency: String?)

    private fun parseOne(segment: String, ctx: LineContext): LineResult {
        val issues = ArrayList<LineIssue>()
        val noteAt = segment.indexOf("//")
        val note = if (noteAt >= 0) segment.substring(noteAt + 2).trim() else ""
        val body = if (noteAt >= 0) segment.substring(0, noteAt) else segment
        val tokens = tokenize(body)

        // the type
        var type: TxType? = null
        var transferMarked = false
        // "перевод" is a transfer between own accounts only when two of them are named; "перевод маме 100" is an expense
        var transferWord: Token? = null
        tokens.firstOrNull()?.let { first ->
            if (first.kind == Kind.Sign) {
                type = if (first.text == "+") TxType.Income else TxType.Expense
                first.used = true
            }
        }
        for (t in tokens) {
            if (t.used) continue
            when {
                t.kind == Kind.Arrow -> { transferMarked = true; t.used = true }
                t.kind == Kind.Word && t.norm in Words.REFUND -> { type = TxType.Refund; t.used = true }
                t.kind == Kind.Word && t.norm in Words.TRANSFER && transferWord == null -> transferWord = t
                t.kind == Kind.Word && t.norm in Words.ADJUSTMENT -> { type = TxType.Adjustment; t.used = true }
                t.kind == Kind.Word && t.norm in Words.INCOME && type == null -> { type = TxType.Income; t.used = true }
            }
        }

        // tags
        val tags = tokens.filter { it.kind == Kind.Tag }.map { it.used = true; it.text.removePrefix("#") }

        // when
        var date: LocalDate? = null
        var time: LocalTime? = null
        fun useLead(i: Int) {
            val prev = tokens.getOrNull(i - 1)
            if (prev != null && !prev.used && prev.kind == Kind.Word && prev.norm in Words.LEADS) prev.used = true
        }
        for ((i, t) in tokens.withIndex()) {
            if (t.used) continue
            when (t.kind) {
                Kind.IsoDate -> runCatching { LocalDate.parse(t.text) }.getOrNull()?.let { date = it; t.used = true; useLead(i) }
                Kind.DmyDate -> dmy(t.text, ctx.today, issues)?.let { date = it; t.used = true; useLead(i) }
                Kind.Time -> runCatching { LocalTime.parse(t.text.padStart(5, '0')) }.getOrNull()?.let { time = it; t.used = true; useLead(i) }
                Kind.Word -> when {
                    t.norm in Words.TODAY -> { date = ctx.today; t.used = true; useLead(i) }
                    t.norm in Words.YESTERDAY -> { date = ctx.today.minusDays(1); t.used = true; useLead(i) }
                    t.norm in Words.BEFORE_YESTERDAY -> { date = ctx.today.minusDays(2); t.used = true; useLead(i) }
                    t.norm in Words.WEEKDAYS -> {
                        date = ctx.today.with(TemporalAdjusters.previousOrSame(Words.WEEKDAYS.getValue(t.norm)))
                        t.used = true
                        useLead(i)
                    }
                    else -> Unit
                }
                else -> Unit
            }
        }
        // "3 дня назад", "12 окт", "oct 12", "12 октября 2025"
        for ((i, t) in tokens.withIndex()) {
            if (t.used || t.kind != Kind.Number) continue
            val n = t.text.toIntOrNull() ?: continue
            val next = tokens.getOrNull(i + 1)
            val prev = tokens.getOrNull(i - 1)
            if (next != null && !next.used && next.kind == Kind.Word && next.norm in Words.DAYS) {
                val ago = tokens.getOrNull(i + 2)
                if (ago != null && ago.kind == Kind.Word && ago.norm in Words.AGO && n in 0..3660) {
                    date = ctx.today.minusDays(n.toLong())
                    t.used = true; next.used = true; ago.used = true
                }
            } else if (next != null && !next.used && next.kind == Kind.Word && next.norm in Words.MONTHS && n in 1..31) {
                val year = tokens.getOrNull(i + 2)?.takeIf { it.kind == Kind.Number && it.text.length == 4 }?.text?.toIntOrNull()
                monthDay(Words.MONTHS.getValue(next.norm), n, year, ctx.today, issues)?.let {
                    date = it; t.used = true; next.used = true
                    if (year != null) tokens[i + 2].used = true
                    useLead(i)
                }
            } else if (prev != null && !prev.used && prev.kind == Kind.Word && prev.norm in Words.MONTHS && n in 1..31) {
                monthDay(Words.MONTHS.getValue(prev.norm), n, null, ctx.today, issues)?.let { date = it; t.used = true; prev.used = true }
            }
        }

        // currencies, then amounts with the currency next to them
        val currencyAt = HashMap<Int, String>()
        for ((i, t) in tokens.withIndex()) {
            if (t.used) continue
            currencyOf(t)?.let { currencyAt[i] = it }
        }
        val numbers = tokens.withIndex().filter { (_, t) -> !t.used && t.kind == Kind.Number }
        val amounts = numbers.map { (i, t) ->
            val near = currencyAt[i + 1] ?: currencyAt[i - 1]
            Amount(t, near).also {
                if (currencyAt[i + 1] != null) tokens[i + 1].used = true else if (currencyAt[i - 1] != null) tokens[i - 1].used = true
            }
        }
        val equalsAt = tokens.indexOfFirst { it.kind == Kind.Equals && !it.used }

        // date-like numbers ("3.10") are a date only when another number is the amount
        fun dateShaped(t: Token) = Regex("(\\d{1,2})[.](\\d{1,2})").matchEntire(t.text)?.let { m ->
            val d = m.groupValues[1].toInt(); val mo = m.groupValues[2].toInt()
            d in 1..31 && mo in 1..12 && m.groupValues[2].length <= 2
        } ?: false

        var candidates = amounts.toMutableList()
        if (date == null && candidates.size > 1) {
            val shaped = candidates.filter { it.currency == null && dateShaped(it.token) }
            val rest = candidates - shaped.toSet()
            if (shaped.size == 1 && rest.isNotEmpty()) {
                val t = shaped.single().token
                val (d, mo) = t.text.split('.').map { it.toInt() }
                monthDay(mo, d, null, ctx.today, issues)?.let { date = it; t.used = true; candidates.remove(shaped.single()) }
            }
        }
        // accounts, before the amount: a transfer can carry two amounts ("100 € → 108 $")
        val accountHits = findAccounts(tokens, ctx.accounts, issues)
        var accountId: String? = null
        var toAccountId: String? = null
        val arrowAt = tokens.indexOfFirst { it.kind == Kind.Arrow }
        val fromHit = accountHits.firstOrNull { it.lead in Words.FROM }
        val toHit = accountHits.firstOrNull { it.lead in Words.TO && it !== fromHit }
        if (fromHit != null && toHit != null && fromHit.account.id != toHit.account.id && type == null) transferMarked = true
        if (transferWord != null && type == null && accountHits.map { it.account.id }.distinct().size >= 2) {
            transferMarked = true
            transferWord.used = true
        }
        val isTransfer = transferMarked && type == null
        if (isTransfer) {
            val ordered = when {
                arrowAt >= 0 -> {
                    val left = accountHits.lastOrNull { it.index < arrowAt }
                    val right = accountHits.firstOrNull { it.index > arrowAt }
                    listOf(left, right)
                }
                fromHit != null || toHit != null -> listOf(fromHit ?: accountHits.firstOrNull { it !== toHit }, toHit ?: accountHits.firstOrNull { it !== fromHit })
                else -> listOf(accountHits.getOrNull(0), accountHits.getOrNull(1))
            }
            accountId = ordered[0]?.account?.id
            toAccountId = ordered[1]?.account?.id
            if (toAccountId == null || toAccountId == accountId) {
                toAccountId = null
                issues += LineIssue.TransferTarget
            }
        } else {
            accountId = accountHits.firstOrNull()?.account?.id
        }
        accountHits.forEach { hit ->
            hit.tokens.forEach { it.used = true }
            hit.leadToken?.used = true
        }

        // which number is the amount
        var amount: Amount? = null
        var accountAmount: Amount? = null
        var toAmount: Amount? = null
        if (equalsAt >= 0) {
            // "12 $ = 11,05 €": the first is what was paid, the one after "=" is what the account moved
            tokens[equalsAt].used = true
            val before = candidates.filter { it.token.start < tokens[equalsAt].start }
            val after = candidates.filter { it.token.start > tokens[equalsAt].start }
            amount = before.lastOrNull()
            accountAmount = after.firstOrNull()
            candidates = (candidates - setOfNotNull(amount, accountAmount)).toMutableList()
        } else if (isTransfer && candidates.size >= 2) {
            amount = candidates[0]
            toAmount = candidates[1]
            candidates = candidates.drop(2).toMutableList()
        } else if (candidates.isNotEmpty()) {
            amount = candidates.firstOrNull { it.currency != null }
                ?: candidates.lastOrNull { Regex("[.,]\\d{1,2}$").containsMatchIn(it.token.text) }
                ?: candidates.last()
            candidates.remove(amount)
        }
        amount?.token?.used = true
        accountAmount?.token?.used = true
        toAmount?.token?.used = true
        if (amount == null) issues += LineIssue.NoAmount
        if (candidates.isNotEmpty() && amount != null) issues += LineIssue.SeveralNumbers(amount.token.text, candidates.map { it.token.text })
        listOfNotNull(amount, accountAmount, toAmount).forEach { a ->
            if (Regex("^[+\\-−]?\\d{1,3}[.,]\\d{3}$").matches(a.token.text)) issues += LineIssue.AmbiguousNumber(a.token.text)
        }
        // a loose currency word anywhere belongs to the amount
        if (amount != null && amount.currency == null) {
            currencyAt.entries.firstOrNull { !tokens[it.key].used }?.let { (i, code) -> amount.currency = code; tokens[i].used = true }
        }

        // the sign of the amount: "+3000" is income, "-20" a correction downwards
        var amountText = amount?.token?.text.orEmpty().replace('−', '-')
        if (amountText.startsWith("+")) {
            amountText = amountText.drop(1)
            if (type == null && !isTransfer) type = TxType.Income
        } else if (amountText.startsWith("-") && type != TxType.Adjustment) {
            amountText = amountText.drop(1)
            if (type == null && !isTransfer) type = TxType.Expense
        }

        val finalType = when {
            isTransfer -> TxType.Transfer
            else -> type ?: TxType.Expense
        }

        // categories: they stay in the description ("кофе" is both the category's word and what it was)
        val description = describe(tokens)
        var categoryId: String? = null
        var categorySource: CategorySource? = null
        var typeOut = finalType
        if (finalType.categoryKind != null || type == null) {
            val hit = findCategory(tokens, ctx.categories)
            if (hit != null) {
                val wanted = finalType.categoryKind
                when {
                    type == null && !isTransfer && hit.kind == CategoryKind.Income -> {
                        typeOut = TxType.Income
                        categoryId = hit.id
                    }
                    wanted != null && hit.kind == wanted -> categoryId = hit.id
                    wanted != null -> issues += LineIssue.CategoryConflict(hit.name)
                }
                if (categoryId != null) categorySource = CategorySource.Written
            }
        }
        var accountLearned = false
        val hint = description.takeIf { it.isNotBlank() }?.let { ctx.hint(normalize(it)) }
        if (hint != null) {
            if (categoryId == null && typeOut.categoryKind != null) {
                val learned = ctx.categories.firstOrNull { it.id == hint.categoryId }
                if (learned != null && learned.kind == typeOut.categoryKind) {
                    categoryId = learned.id
                    categorySource = CategorySource.Learned
                }
            }
            if (accountId == null && typeOut != TxType.Transfer && hint.accountId != null && ctx.accounts.any { it.id == hint.accountId }) {
                accountId = hint.accountId
                accountLearned = true
            }
        }
        val category = categoryId?.let { id -> ctx.categories.firstOrNull { it.id == id } }
        val cleanDescription = description.takeUnless { category != null && normalize(it) == normalize(category.name) }.orEmpty()

        val draft = Draft(
            type = typeOut,
            amount = amountText,
            currency = amount?.currency,
            accountId = accountId,
            accountAmount = accountAmount?.token?.text.orEmpty(),
            toAccountId = toAccountId,
            toAmount = toAmount?.token?.text.orEmpty(),
            categoryId = categoryId,
            description = cleanDescription,
            note = note,
            tags = tags,
            date = date,
            time = when {
                time != null -> DraftTime.At(time!!)
                date != null && date != ctx.today -> DraftTime.Unknown
                else -> DraftTime.Now
            },
            origin = Origin.Line,
            inputText = segment.trim(),
        )
        return LineResult(
            draft = draft,
            issues = issues.distinct(),
            typeWritten = type != null || transferMarked,
            currencyWritten = amount?.currency != null,
            accountWritten = accountHits.isNotEmpty() && !accountLearned,
            accountLearned = accountLearned,
            categorySource = categorySource,
            dateWritten = date != null || time != null,
        )
    }

    // ---------- pieces ----------

    private fun dmy(text: String, today: LocalDate, issues: MutableList<LineIssue>): LocalDate? {
        val p = text.split('.', '/')
        val d = p[0].toIntOrNull() ?: return null
        val m = p.getOrNull(1)?.toIntOrNull() ?: return null
        if (p.size == 2) return monthDay(m, d, null, today, issues)
        if (p.size != 3) return null
        var y = p[2].toIntOrNull() ?: return null
        if (p[2].length == 2) y += 2000
        return runCatching { LocalDate.of(y, m, d) }.getOrNull()
    }

    /** A day without a year is the latest one not in the future. */
    private fun monthDay(month: Int, day: Int, year: Int?, today: LocalDate, issues: MutableList<LineIssue>): LocalDate? {
        if (year != null) return runCatching { LocalDate.of(year, month, day) }.getOrNull()
        val thisYear = runCatching { LocalDate.of(today.year, month, day) }.getOrNull()
        if (thisYear != null && !thisYear.isAfter(today)) return thisYear
        val last = runCatching { LocalDate.of(today.year - 1, month, day) }.getOrNull() ?: return null
        issues += LineIssue.DateLastYear(last)
        return last
    }

    private fun currencyOf(t: Token): String? = when (t.kind) {
        Kind.Symbol -> Words.CURRENCY[t.text]
        Kind.Word -> {
            val raw = t.text.trimEnd('.')
            when {
                raw.length == 3 && raw.uppercase() == raw && Currencies.of(raw) != null -> raw
                raw.length == 3 && raw.uppercase() !in Words.CODES_ONLY_UPPER && Currencies.of(raw) != null -> raw.uppercase()
                else -> Words.CURRENCY[t.norm] ?: Words.CURRENCY[normalize(t.text)]
            }
        }
        else -> null
    }

    private class AccountHit(val account: Account, val index: Int, val tokens: List<Token>, val leadToken: Token?) {
        val lead: String? get() = leadToken?.norm
    }

    /** Accounts named in the line, in the order written. A word that fits several accounts names none of them. */
    private fun findAccounts(tokens: List<Token>, accounts: List<Account>, issues: MutableList<LineIssue>): List<AccountHit> {
        val hits = ArrayList<AccountHit>()
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            if (t.used || t.kind != Kind.Word || t.norm in Words.LEADS) { i++; continue }
            // the longest account name that starts here
            var best: List<Pair<Account, Int>> = emptyList()
            var bestLen = 0
            for (a in accounts) {
                val words = normalize(a.name).split(' ').filter { it.isNotEmpty() }
                // a whole multi-word name, or one distinctive word of it
                val whole = words.size > 1 && words.indices.all { k -> tokens.getOrNull(i + k)?.let { it.kind == Kind.Word && !it.used && wordMatches(it.norm, words[k]) } == true }
                val len = when {
                    whole -> words.size
                    words.any { wordMatches(t.norm, it) } -> 1
                    else -> 0
                }
                if (len > bestLen) { best = listOf(a to len); bestLen = len } else if (len == bestLen && len > 0) best = best + (a to len)
            }
            if (best.size == 1) {
                val (account, len) = best.single()
                val lead = tokens.getOrNull(i - 1)?.takeIf { !it.used && it.kind == Kind.Word && it.norm in Words.LEADS }
                hits += AccountHit(account, i, tokens.subList(i, i + len).toList(), lead)
                i += len
                continue
            }
            if (best.size > 1) {
                // a whole name beats a part of one: "карта сбер" is that card even if "карта" alone is several
                issues += LineIssue.AmbiguousAccount(t.text)
                t.used = true
            }
            i++
        }
        // the same account named twice counts once
        return hits.distinctBy { it.account.id to it.lead }
    }

    /**
     * A written word against a word of a name: the same, an abbreviation ("нал" for "наличные"), or the same word in another
     * case ("карты", "картой" for "карта"; not "картошка").
     */
    internal fun wordMatches(token: String, word: String): Boolean {
        if (token == word) return true
        if (token.length >= 3 && token.length < word.length && word.startsWith(token)) return true
        if (kotlin.math.abs(token.length - word.length) > 2) return false
        val shorter = minOf(token.length, word.length)
        if (shorter < 4) return false
        val common = token.zip(word).takeWhile { (a, b) -> a == b }.count()
        // short words may differ only in their last letter ("карта", "карты"; not "карман"), long ones in the last two
        return common >= if (shorter >= 7) shorter - 2 else shorter - 1
    }

    /** The category the line names, by its name or one of its words; exact words beat inflected ones; a tie names none. */
    private fun findCategory(tokens: List<Token>, categories: List<Category>): Category? {
        val words = tokens.filter { it.kind == Kind.Word && !it.used }.map { it.norm }
        if (words.isEmpty()) return null
        val text = words.joinToString(" ")
        var best: List<Category> = emptyList()
        var bestScore = 0
        for (c in categories) {
            val names = (listOf(c.name) + c.keywords).map(::normalize).filter { it.isNotEmpty() }
            var score = 0
            for (n in names) {
                score = maxOf(score, when {
                    n.contains(' ') && (" $text ").contains(" $n ") -> 4
                    n in words -> 3
                    !n.contains(' ') && words.any { wordMatches(it, n) && it.length >= 4 } -> 2
                    // a long word of a longer name: "рестораны" of "Кафе и рестораны"
                    n.contains(' ') && n.split(' ').any { part -> part.length >= 5 && words.any { wordMatches(it, part) } } -> 1
                    else -> 0
                })
            }
            if (score > bestScore) { best = listOf(c); bestScore = score } else if (score == bestScore && score > 0) best = best + c
        }
        // a subcategory and its parent both fitting: the more specific one
        if (best.size > 1) {
            val children = best.filter { b -> b.parentId != null && best.any { it.id == b.parentId } }
            if (children.size == 1) return children.single()
        }
        return best.singleOrNull()
    }

    /** What is left of the line, in the order written, without the little words that led to something taken. */
    private fun describe(tokens: List<Token>): String {
        val left = tokens.filter { !it.used && (it.kind == Kind.Word || it.kind == Kind.Number) }
        val words = left.map { it.text }.toMutableList()
        while (words.isNotEmpty() && normalize(words.first()) in Words.LEADS) words.removeAt(0)
        while (words.isNotEmpty() && normalize(words.last()) in Words.LEADS) words.removeAt(words.lastIndex)
        return words.joinToString(" ").trim()
    }
}
