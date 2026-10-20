package dev.spendcodex.ui.categories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.spendcodex.Texts
import dev.spendcodex.data.domain.Category
import dev.spendcodex.data.domain.CategoryKind
import dev.spendcodex.data.domain.Tag
import dev.spendcodex.data.repo.CategoryEdit
import dev.spendcodex.data.repo.EditResult
import dev.spendcodex.data.repo.Ledger
import dev.spendcodex.ui.Notice
import dev.spendcodex.ui.Notices
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CategoryForm(
    val key: String? = null,
    val id: String? = null,
    val name: String = "",
    val kind: CategoryKind = CategoryKind.Expense,
    val parentId: String? = null,
    val keywords: String = "",
    val archived: Boolean = false,
    val used: Int = 0,
    val hasChildren: Boolean = false,
    val parentOpen: Boolean = false,
    val error: String? = null,
)

data class TagForm(val key: String? = null, val id: String? = null, val name: String = "", val archived: Boolean = false, val used: Int = 0, val error: String? = null)

data class CategoriesUiState(
    val categories: List<Category> = emptyList(),
    val tags: List<Tag> = emptyList(),
    val usage: Map<String, Int> = emptyMap(),
    val tagUsage: Map<String, Int> = emptyMap(),
    val form: CategoryForm = CategoryForm(),
    val tagForm: TagForm = TagForm(),
    val archivedOpen: Boolean = false,
)

@HiltViewModel
class CategoriesViewModel @Inject constructor(private val ledger: Ledger, private val notices: Notices) : ViewModel() {
    private val form = MutableStateFlow(CategoryForm())
    private val tagForm = MutableStateFlow(TagForm())
    private val archivedOpen = MutableStateFlow(false)
    private val _closed = Channel<Unit>(Channel.BUFFERED)
    val closed: Flow<Unit> = _closed.receiveAsFlow()

    private val lists = combine(ledger.categories, ledger.tags, ledger.categoryUsage, ledger.tagUsage) { c, t, u, tu -> CategoriesUiState(c, t, u, tu) }

    val state: StateFlow<CategoriesUiState> = combine(lists, form, tagForm, archivedOpen) { l, f, tf, open ->
        l.copy(form = f, tagForm = tf, archivedOpen = open)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, CategoriesUiState())

    fun onToggleArchived() = archivedOpen.update { !it }

    fun open(key: String, id: String?, kind: CategoryKind, parentId: String?) {
        if (form.value.key == key) return
        form.value = CategoryForm(key = key, kind = kind, parentId = parentId)
        if (id == null) return
        viewModelScope.launch {
            val all = ledger.categoriesNow()
            val c = all.firstOrNull { it.id == id } ?: return@launch
            form.update {
                if (it.key != key) it else it.copy(
                    id = c.id, name = c.name, kind = c.kind, parentId = c.parentId, keywords = c.keywords.joinToString(", "),
                    archived = c.archived, used = state.value.usage[c.id] ?: 0, hasChildren = all.any { ch -> ch.parentId == c.id },
                )
            }
        }
    }

    fun edit(change: (CategoryForm) -> CategoryForm) = form.update { change(it).copy(error = null) }

    fun onSave() {
        val f = form.value
        viewModelScope.launch {
            val keywords = f.keywords.split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }
            when (val r = ledger.saveCategory(CategoryEdit(f.id, f.name, f.kind, f.parentId, keywords))) {
                is EditResult.Error -> form.update { it.copy(error = Texts.editError(r.error)) }
                is EditResult.Ok -> {
                    notices.show(Notice(Texts.SAVED))
                    form.value = CategoryForm()
                    _closed.send(Unit)
                }
            }
        }
    }

    fun onArchive() {
        val f = form.value
        val id = f.id ?: return
        viewModelScope.launch {
            ledger.setCategoryArchived(id, !f.archived)
            form.update { it.copy(archived = !f.archived) }
        }
    }

    fun onDelete() {
        val id = form.value.id ?: return
        viewModelScope.launch {
            when (val r = ledger.deleteCategory(id)) {
                is EditResult.Error -> form.update { it.copy(error = Texts.editError(r.error)) }
                is EditResult.Ok -> {
                    form.value = CategoryForm()
                    _closed.send(Unit)
                }
            }
        }
    }

    // ---------- tags ----------

    fun openTag(key: String, id: String) {
        if (tagForm.value.key == key) return
        val tag = state.value.tags.firstOrNull { it.id == id }
        tagForm.value = TagForm(key, id, tag?.name.orEmpty(), tag?.archived ?: false, state.value.tagUsage[id] ?: 0)
    }

    fun editTag(name: String) = tagForm.update { it.copy(name = name, error = null) }

    fun onSaveTag() {
        val f = tagForm.value
        val id = f.id ?: return
        viewModelScope.launch {
            when (val r = ledger.renameTag(id, f.name)) {
                is EditResult.Error -> tagForm.update { it.copy(error = Texts.editError(r.error)) }
                is EditResult.Ok -> {
                    tagForm.value = TagForm()
                    _closed.send(Unit)
                }
            }
        }
    }

    fun onArchiveTag() {
        val f = tagForm.value
        val id = f.id ?: return
        viewModelScope.launch {
            ledger.setTagArchived(id, !f.archived)
            tagForm.update { it.copy(archived = !f.archived) }
        }
    }

    fun onDeleteTag() {
        val id = tagForm.value.id ?: return
        viewModelScope.launch {
            when (val r = ledger.deleteTag(id)) {
                is EditResult.Error -> tagForm.update { it.copy(error = Texts.editError(r.error)) }
                is EditResult.Ok -> {
                    tagForm.value = TagForm()
                    _closed.send(Unit)
                }
            }
        }
    }
}
