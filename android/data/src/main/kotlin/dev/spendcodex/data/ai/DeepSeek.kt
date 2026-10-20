package dev.spendcodex.data.ai

import dev.spendcodex.data.domain.Account
import dev.spendcodex.data.domain.Category
import dev.spendcodex.data.domain.Currencies
import dev.spendcodex.data.domain.Decimal
import dev.spendcodex.data.domain.Draft
import dev.spendcodex.data.domain.DraftTime
import dev.spendcodex.data.domain.Origin
import dev.spendcodex.data.domain.TxType
import dev.spendcodex.data.domain.normalize
import dev.spendcodex.data.parse.QuickLine
import java.io.IOException
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** What DeepSeek is told besides the line: the journal's names and today. Never amounts, balances or past records. */
data class AiContext(
    val today: LocalDate,
    val timeZone: String,
    val english: Boolean,
    val accounts: List<Account>,
    val categories: List<Category>,
    val defaultCurrency: String?,
)

/** One operation DeepSeek read. [amountInText]: its amount is a number that is in the line itself, not one the model made up. */
data class AiDraft(val draft: Draft, val amountInText: Boolean, val unknownAccount: String?, val unknownCategory: String?)

/** Why DeepSeek could not help. The line stays in the input for another try. */
sealed class AiFailure(val detail: String, val retryable: Boolean) : Exception(detail) {
    class NoKey : AiFailure("no key", false)
    class Unavailable(detail: String) : AiFailure(detail, true)
    class Auth(detail: String) : AiFailure(detail, false)
    class Rejected(detail: String) : AiFailure(detail, false)
    class BadOutput(detail: String) : AiFailure(detail, true)
}

/**
 * Reading a line with DeepSeek, with the user's own key, when they turned it on and the quick line could not read it
 * itself. The model only fills in the fields of the operations the line describes, by a forced function call; it does not
 * talk, advise or analyse. Everything it returns is checked like typed input and waits for the user's "записать".
 */
class AiReader(
    private val http: OkHttpClient,
    private val baseUrl: String,
    private val key: () -> String?,
    private val model: String = "deepseek-flash",
) {
    suspend fun read(text: String, ctx: AiContext): List<AiDraft> {
        val apiKey = key()?.takeIf { it.isNotBlank() } ?: throw AiFailure.NoKey()
        val answer = call(apiKey, body(text, ctx))
        val args = arguments(answer)
        val operations = (args["operations"] as? JsonArray) ?: throw AiFailure.BadOutput("no operations")
        if (operations.isEmpty()) throw AiFailure.BadOutput("no operations")
        return operations.mapNotNull { (it as? JsonObject)?.let { op -> toDraft(op, text, ctx) } }
            .ifEmpty { throw AiFailure.BadOutput("no usable operations") }
    }

    internal fun body(text: String, ctx: AiContext): JsonObject = buildJsonObject {
        put("model", model)
        put("temperature", 0)
        put("max_tokens", 2048)
        putJsonObject("thinking") { put("type", "disabled") }
        put("messages", buildJsonArray {
            add(buildJsonObject { put("role", "system"); put("content", SYSTEM) })
            add(buildJsonObject {
                put("role", "user")
                put("content", "context: " + context(ctx).toString() + "\nmessage: " + text)
            })
        })
        put("tools", buildJsonArray { add(TOOL) })
        putJsonObject("tool_choice") {
            put("type", "function")
            putJsonObject("function") { put("name", "record_operations") }
        }
    }

    private fun context(ctx: AiContext) = buildJsonObject {
        put("today", ctx.today.toString())
        put("weekday", ctx.today.dayOfWeek.name.lowercase())
        put("time_zone", ctx.timeZone)
        put("language", if (ctx.english) "en" else "ru")
        ctx.defaultCurrency?.let { put("default_currency", it) }
        putJsonArray("accounts") {
            ctx.accounts.filter { !it.archived }.forEach { a -> add(buildJsonObject { put("name", a.name); put("currency", a.currency) }) }
        }
        putJsonArray("categories") {
            ctx.categories.filter { !it.archived }.forEach { c -> add(buildJsonObject { put("name", c.name); put("kind", c.kind.code) }) }
        }
    }

    private fun toDraft(op: JsonObject, text: String, ctx: AiContext): AiDraft? {
        fun s(name: String) = (op[name] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.trim()?.ifEmpty { null }
        val type = when (s("type")) {
            "income" -> TxType.Income
            "transfer" -> TxType.Transfer
            "refund" -> TxType.Refund
            else -> TxType.Expense
        }
        val amount = s("amount")?.removePrefix("+") ?: return null
        val active = ctx.accounts.filter { !it.archived }
        fun account(name: String?): Account? = name?.let { n ->
            active.firstOrNull { normalize(it.name) == normalize(n) } ?: active.singleOrNull { a -> QuickLine.wordMatches(normalize(n), normalize(a.name)) }
        }
        val accountName = s("account")
        val toName = s("to_account")
        val account = account(accountName)
        val to = account(toName)
        val categoryName = s("category")
        val wantedKind = type.categoryKind
        val category = categoryName?.let { n ->
            ctx.categories.filter { !it.archived && it.kind == wantedKind }.firstOrNull { normalize(it.name) == normalize(n) }
        }
        val currency = s("currency")?.uppercase()?.takeIf { Currencies.of(it) != null }
        val date = s("date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.takeIf { !it.isAfter(ctx.today) }
        val time = s("time")?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
        val tags = (op["tags"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content?.trim()?.removePrefix("#")?.ifEmpty { null } }
        val draft = Draft(
            type = type,
            amount = amount,
            currency = currency,
            accountId = account?.id,
            toAccountId = if (type == TxType.Transfer) to?.id else null,
            toAmount = if (type == TxType.Transfer) s("to_amount").orEmpty() else "",
            categoryId = category?.id,
            description = s("description").orEmpty(),
            payee = s("payee").orEmpty(),
            note = s("note").orEmpty(),
            tags = tags,
            date = date,
            time = when {
                time != null -> DraftTime.At(time)
                date != null && date != ctx.today -> DraftTime.Unknown
                else -> DraftTime.Now
            },
            origin = Origin.Ai,
            inputText = text.trim(),
        )
        return AiDraft(
            draft = draft,
            amountInText = numberInText(amount, text),
            unknownAccount = accountName?.takeIf { account == null },
            unknownCategory = categoryName?.takeIf { category == null },
        )
    }

    // ---------- transport ----------

    private suspend fun call(key: String, body: JsonObject): JsonObject {
        val request = Request.Builder().url(baseUrl.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer $key")
            .post(body.toString().toRequestBody(JSON_TYPE)).build()
        val response = try {
            http.newCall(request).await()
        } catch (e: IOException) {
            throw AiFailure.Unavailable("${e.javaClass.simpleName}: ${e.message}")
        }
        response.use {
            val text = try {
                it.body.string()
            } catch (e: IOException) {
                throw AiFailure.Unavailable("${e.javaClass.simpleName}: ${e.message}")
            }
            val code = it.code
            if (code in 200..299) {
                return try {
                    COMPACT.parseToJsonElement(text).jsonObject
                } catch (e: Exception) {
                    throw AiFailure.BadOutput("HTTP $code, not JSON")
                }
            }
            val why = "HTTP $code: " + reason(text).replace(key, "sk-…")
            throw when (code) {
                401, 402, 403 -> AiFailure.Auth(why)
                408, 429, in 500..599 -> AiFailure.Unavailable(why)
                else -> AiFailure.Rejected(why)
            }
        }
    }

    private fun arguments(answer: JsonObject): JsonObject {
        val message = ((answer["choices"] as? JsonArray)?.firstOrNull() as? JsonObject)?.get("message") as? JsonObject
            ?: throw AiFailure.BadOutput("no choices")
        val raw = (((message["tool_calls"] as? JsonArray)?.firstOrNull() as? JsonObject)?.get("function") as? JsonObject)
            ?.get("arguments")?.let { (it as? JsonPrimitive)?.content }
            ?: (message["content"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
            ?: throw AiFailure.BadOutput("no tool call")
        return try {
            COMPACT.parseToJsonElement(raw).jsonObject
        } catch (e: Exception) {
            val start = raw.indexOf('{')
            val end = raw.lastIndexOf('}')
            if (start in 0 until end) runCatching { COMPACT.parseToJsonElement(raw.substring(start, end + 1)).jsonObject }.getOrNull()
                ?: throw AiFailure.BadOutput("arguments not JSON")
            else throw AiFailure.BadOutput("arguments not JSON")
        }
    }

    private fun reason(text: String): String {
        val fromJson = runCatching {
            when (val error = COMPACT.parseToJsonElement(text).jsonObject["error"]) {
                is JsonObject -> (error["message"] as? JsonPrimitive)?.content
                is JsonPrimitive -> error.content
                else -> null
            }
        }.getOrNull()
        return (fromJson ?: text).trim().replace(Regex("\\s+"), " ").take(160).ifEmpty { "no details" }
    }

    companion object {
        private val JSON_TYPE = "application/json".toMediaType()
        private val COMPACT = Json { explicitNulls = true }

        /**
         * True when [amount] is the value of a number written in [text] ("4.5" for "4,50"): the model may only copy amounts,
         * never compute or invent them. Compared as exact decimals with three places, enough for every currency.
         */
        fun numberInText(amount: String, text: String): Boolean {
            val wanted = Decimal.parseHuman(amount, 3) ?: return false
            return QuickLine.tokenize(text).filter { it.kind == QuickLine.Kind.Number }.any { Decimal.parseHuman(it.text.replace('−', '-').removePrefix("+").removePrefix("-"), 3) == wanted }
        }

        private val SYSTEM = """
            You turn one short note about money, written by the user in Russian or English, into the operations it describes,
            by calling record_operations. You only copy what the note says; you never compute, round or invent amounts.
            - type: "expense" (spent), "income" (received from outside), "refund" (money returned for a purchase),
              "transfer" (moved between two of the user's own accounts from the list; paying a person is an expense).
            - amount: the number exactly as written for that operation, as a string. If the note gives one total for several
              things, that is one operation. If there is no amount for something, leave it out.
            - currency: an ISO 4217 code only if the note names a currency; otherwise null.
            - account, to_account: a name from context.accounts only if the note clearly refers to it; otherwise null.
            - category: a name from context.categories of the matching kind if one clearly fits; otherwise null.
            - description: a few words of what it was, in the note's language. payee: who was paid or paid, if named.
            - date (YYYY-MM-DD) and time (HH:MM) only if the note says when; relative words are counted from context.today.
            - tags: words written with #, without it. note: only text the user marked as a comment.
        """.trimIndent()

        private val TOOL: JsonObject = buildJsonObject {
            put("type", "function")
            putJsonObject("function") {
                put("name", "record_operations")
                put("description", "The operations the note describes, field by field, as written.")
                putJsonObject("parameters") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("operations") {
                            put("type", "array")
                            putJsonObject("items") {
                                put("type", "object")
                                putJsonObject("properties") {
                                    putJsonObject("type") { putJsonArray("enum") { add("expense"); add("income"); add("transfer"); add("refund") } }
                                    putJsonObject("amount") { put("type", "string") }
                                    nullable("currency"); nullable("account"); nullable("to_account"); nullable("to_amount")
                                    nullable("category"); putJsonObject("description") { put("type", "string") }; nullable("payee")
                                    nullable("date"); nullable("time"); nullable("note")
                                    putJsonObject("tags") { put("type", "array"); putJsonObject("items") { put("type", "string") } }
                                }
                                putJsonArray("required") { add("type"); add("amount"); add("description") }
                            }
                        }
                    }
                    putJsonArray("required") { add("operations") }
                }
            }
        }

        private fun kotlinx.serialization.json.JsonObjectBuilder.nullable(name: String) = putJsonObject(name) {
            putJsonArray("type") { add("string"); add("null") }
        }
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { runCatching { cancel() } }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWith(Result.failure(e))
        }

        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) cont.resumeWith(Result.success(response)) else response.close()
        }
    })
}
