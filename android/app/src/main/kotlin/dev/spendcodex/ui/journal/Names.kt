package dev.spendcodex.ui.journal

import dev.spendcodex.data.domain.Account
import dev.spendcodex.data.domain.Category
import dev.spendcodex.data.domain.Refs
import dev.spendcodex.data.domain.Tag

/** Names used by the journal and forms, including archived references on older records. */
data class Names(val accounts: List<Account>, val categories: List<Category>, val tags: List<Tag>) {
    val active: List<Account> get() = accounts.filter { !it.archived }
    fun account(id: String?) = accounts.firstOrNull { it.id == id }
    fun category(id: String?) = categories.firstOrNull { it.id == id }
    fun refs(): Refs = Refs(accounts.associateBy { it.id }, categories.associateBy { it.id }, tags.map { it.id }.toSet()) { null }
    fun categoryPath(id: String?): String? {
        val c = category(id) ?: return null
        val parent = c.parentId?.let { category(it) }
        return if (parent != null) "${parent.name} › ${c.name}" else c.name
    }
}
