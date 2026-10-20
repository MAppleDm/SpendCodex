package dev.spendcodex.data.db

import dev.spendcodex.data.domain.Account
import dev.spendcodex.data.domain.AccountKind
import dev.spendcodex.data.domain.Category
import dev.spendcodex.data.domain.CategoryKind
import dev.spendcodex.data.domain.Currencies
import dev.spendcodex.data.domain.Decimal
import dev.spendcodex.data.domain.Origin
import dev.spendcodex.data.domain.Tag
import dev.spendcodex.data.domain.Transaction
import dev.spendcodex.data.domain.TxType
import dev.spendcodex.data.domain.normalize
import java.time.LocalDate
import java.time.LocalTime

fun AccountEntity.toDomain() = Account(
    id = id, name = name, kind = AccountKind.of(kind) ?: AccountKind.Other, currency = currency, openingMinor = openingMinor,
    createdAt = createdAt, updatedAt = updatedAt, archived = archived, sort = sort,
)

fun Account.toEntity() = AccountEntity(
    id = id, name = name, nameNorm = normalize(name), kind = kind.code, currency = currency, openingMinor = openingMinor,
    createdAt = createdAt, updatedAt = updatedAt, archived = archived, sort = sort,
)

fun CategoryEntity.toDomain() = Category(
    id = id, name = name, kind = CategoryKind.of(kind) ?: CategoryKind.Expense, parentId = parentId,
    keywords = keywords.split('\n').map { it.trim() }.filter { it.isNotEmpty() },
    createdAt = createdAt, updatedAt = updatedAt, archived = archived, sort = sort,
)

fun Category.toEntity() = CategoryEntity(
    id = id, name = name, nameNorm = normalize(name), kind = kind.code, parentId = parentId,
    keywords = keywords.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n"),
    createdAt = createdAt, updatedAt = updatedAt, archived = archived, sort = sort,
)

fun TagEntity.toDomain() = Tag(id = id, name = name, createdAt = createdAt, updatedAt = updatedAt, archived = archived)

fun Tag.toEntity() = TagEntity(id = id, name = name, nameNorm = normalize(name), createdAt = createdAt, updatedAt = updatedAt, archived = archived)

fun TransactionEntity.toDomain(tagIds: List<String>) = Transaction(
    id = id,
    type = TxType.of(type) ?: TxType.Expense,
    date = LocalDate.parse(date),
    time = timeMin?.let { LocalTime.of(it / 60, it % 60) },
    timeZone = timeZone,
    amountMinor = amountMinor,
    currency = currency,
    accountId = accountId,
    accountAmountMinor = accountAmountMinor,
    toAccountId = toAccountId,
    toAmountMinor = toAmountMinor,
    categoryId = categoryId,
    payee = payee,
    description = description,
    note = note,
    tagIds = tagIds.sorted(),
    refundOf = refundOf,
    origin = Origin.of(origin) ?: Origin.Form,
    inputText = inputText,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun Transaction.toEntity(): TransactionEntity {
    val exponent = Currencies.of(currency)?.exponent ?: 2
    val plain = Decimal.format(kotlin.math.abs(amountMinor), exponent)
    // the amount is searchable as written either way: "4.50" and "4,50"
    val search = normalize(listOfNotNull(description, payee, note, inputText, plain, plain.replace('.', ',')).joinToString(" "))
    return TransactionEntity(
        id = id,
        type = type.code,
        date = date.toString(),
        timeMin = time?.let { it.hour * 60 + it.minute },
        timeZone = timeZone,
        amountMinor = amountMinor,
        currency = currency,
        accountId = accountId,
        accountAmountMinor = accountAmountMinor,
        toAccountId = toAccountId,
        toAmountMinor = toAmountMinor,
        categoryId = categoryId,
        payee = payee,
        description = description,
        note = note,
        refundOf = refundOf,
        origin = origin.code,
        inputText = inputText,
        createdAt = createdAt,
        updatedAt = updatedAt,
        searchText = search,
        descriptionNorm = description?.let(::normalize)?.ifEmpty { null },
    )
}
