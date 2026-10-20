package dev.spendcodex.ui.categories

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.spendcodex.Texts
import dev.spendcodex.coreui.AppTheme
import dev.spendcodex.coreui.ChevronIcon
import dev.spendcodex.coreui.ChoiceRow
import dev.spendcodex.coreui.Hairline
import dev.spendcodex.coreui.Hint
import dev.spendcodex.coreui.PageHeader
import dev.spendcodex.coreui.SectionCaption
import dev.spendcodex.coreui.StatRow
import dev.spendcodex.coreui.TextAction
import dev.spendcodex.coreui.UnderlineField
import dev.spendcodex.coreui.tap
import dev.spendcodex.data.domain.Category
import dev.spendcodex.data.domain.CategoryKind

@Composable
fun CategoriesScreen(
    state: CategoriesUiState,
    vm: CategoriesViewModel,
    onBack: () -> Unit,
    onOpen: (id: String?, kind: CategoryKind, parentId: String?) -> Unit,
    onOpenTag: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().background(AppTheme.colors.background).statusBarsPadding().navigationBarsPadding()) {
        PageHeader(Texts.CATEGORIES_AND_TAGS, onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            listOf(CategoryKind.Expense to Texts.EXPENSES, CategoryKind.Income to Texts.INCOMES).forEach { (kind, title) ->
                Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    SectionCaption(title, Modifier.weight(1f))
                    TextAction(Texts.ADD_CATEGORY, { onOpen(null, kind, null) }, Modifier.testTag("add-category-${kind.code}"))
                }
                val active = state.categories.filter { it.kind == kind && !it.archived }
                active.filter { it.parentId == null }.forEach { parent ->
                    CategoryRow(parent, state, 0) { onOpen(parent.id, kind, null) }
                    active.filter { it.parentId == parent.id }.forEach { child -> CategoryRow(child, state, 1) { onOpen(child.id, kind, parent.id) } }
                }
                Hairline(Modifier.padding(top = 8.dp))
            }
            val archived = state.categories.filter { it.archived }
            if (archived.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().tap(onClick = vm::onToggleArchived).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${Texts.ARCHIVED} (${archived.size})", style = AppTheme.type.secondary, modifier = Modifier.weight(1f))
                    ChevronIcon(AppTheme.colors.secondary, up = state.archivedOpen)
                }
                if (state.archivedOpen) archived.forEach { c -> CategoryRow(c, state, 0) { onOpen(c.id, c.kind, c.parentId) } }
                Hairline()
            }
            SectionCaption(Texts.TAGS_SECTION)
            if (state.tags.isEmpty()) Hint(Texts.NO_TAGS)
            state.tags.forEach { tag ->
                StatRow("#" + tag.name, Texts.uses(state.tagUsage[tag.id] ?: 0), Modifier.testTag("tag-row"),
                    valueColor = AppTheme.colors.tertiary, enabled = true, onClick = { onOpenTag(tag.id) })
            }
            Spacer(Modifier.padding(bottom = 24.dp))
        }
    }
}

@Composable
private fun CategoryRow(c: Category, state: CategoriesUiState, depth: Int, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().tap(onClick = onClick).padding(start = 16.dp + (depth * 20).dp, end = 16.dp, top = 10.dp, bottom = 10.dp).testTag("category-row"),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(c.name, style = AppTheme.type.body.copy(color = if (c.archived) AppTheme.colors.tertiary else AppTheme.colors.foreground), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (c.keywords.isNotEmpty()) Text(c.keywords.joinToString(", "), style = AppTheme.type.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(12.dp))
        Text((state.usage[c.id] ?: 0).toString(), style = AppTheme.type.monoSecondary)
    }
}

@Composable
fun CategoryEditScreen(state: CategoriesUiState, vm: CategoriesViewModel, onBack: () -> Unit, onAddSub: (CategoryKind, String) -> Unit, modifier: Modifier = Modifier) {
    val f = state.form
    Column(modifier.fillMaxSize().background(AppTheme.colors.background).statusBarsPadding().imePadding()) {
        PageHeader(if (f.id == null) Texts.NEW_CATEGORY else f.name.ifBlank { Texts.CATEGORY }, onBack) {
            TextAction(Texts.SAVE, vm::onSave, Modifier.testTag("category-save"))
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                UnderlineField(f.name, { v -> vm.edit { it.copy(name = v) } }, Modifier.testTag("category-name"), placeholder = Texts.NAME,
                    imeAction = ImeAction.Next, capitalization = KeyboardCapitalization.Sentences)
            }
            SectionCaption(Texts.KIND)
            val kindLocked = f.used > 0 || f.hasChildren || f.parentId != null
            listOf(CategoryKind.Expense to Texts.EXPENSES, CategoryKind.Income to Texts.INCOMES).forEach { (k, label) ->
                ChoiceRow(label, f.kind == k, { if (!kindLocked) vm.edit { it.copy(kind = k) } })
            }
            if (kindLocked && f.id != null) Hint(Texts.KIND_LOCKED)
            SectionCaption(Texts.PARENT)
            val parent = state.categories.firstOrNull { it.id == f.parentId }
            if (!f.hasChildren) {
                StatRow(Texts.PARENT, parent?.name ?: Texts.PARENT_NONE, Modifier.testTag("category-parent"), onClick = { vm.edit { it.copy(parentOpen = !it.parentOpen) } })
                if (f.parentOpen) {
                    ChoiceRow(Texts.PARENT_NONE, f.parentId == null, { vm.edit { it.copy(parentId = null, parentOpen = false) } })
                    state.categories.filter { it.kind == f.kind && it.parentId == null && it.id != f.id && !it.archived }.forEach { p ->
                        ChoiceRow(p.name, f.parentId == p.id, { vm.edit { it.copy(parentId = p.id, parentOpen = false) } })
                    }
                }
            }
            SectionCaption(Texts.KEYWORDS)
            Column(Modifier.padding(horizontal = 16.dp)) {
                UnderlineField(f.keywords, { v -> vm.edit { it.copy(keywords = v) } }, Modifier.testTag("category-keywords"), placeholder = "кофе, капучино",
                    singleLine = false, imeAction = ImeAction.Default)
            }
            Hint(Texts.KEYWORDS_HINT)
            f.error?.let { Hint(it, Modifier.testTag("category-error"), color = AppTheme.colors.error) }
            if (f.id != null) {
                Hairline(Modifier.padding(top = 12.dp))
                if (f.parentId == null && !f.archived) {
                    StatRow(Texts.ADD_SUBCATEGORY, "›", onClick = { onAddSub(f.kind, f.id) })
                }
                Hint(Texts.uses(f.used))
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                    TextAction(if (f.archived) Texts.UNARCHIVE else Texts.ARCHIVE, vm::onArchive, Modifier.testTag("category-archive"))
                    Spacer(Modifier.weight(1f))
                    TextAction(Texts.DELETE, vm::onDelete, Modifier.testTag("category-delete"),
                        color = if (f.used > 0 || f.hasChildren) AppTheme.colors.tertiary else AppTheme.colors.error)
                }
                if (f.used > 0 || f.hasChildren) Hint(Texts.DELETE_LOCKED)
            }
            Spacer(Modifier.padding(bottom = 24.dp))
        }
    }
}

@Composable
fun TagEditScreen(state: CategoriesUiState, vm: CategoriesViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val f = state.tagForm
    Column(modifier.fillMaxSize().background(AppTheme.colors.background).statusBarsPadding().imePadding()) {
        PageHeader("#" + f.name, onBack) { TextAction(Texts.SAVE, vm::onSaveTag, Modifier.testTag("tag-save")) }
        Column(Modifier.padding(horizontal = 16.dp)) {
            UnderlineField(f.name, vm::editTag, Modifier.testTag("tag-name"), placeholder = Texts.NAME)
        }
        Hint(Texts.uses(f.used))
        f.error?.let { Hint(it, color = AppTheme.colors.error) }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            TextAction(if (f.archived) Texts.UNARCHIVE else Texts.ARCHIVE, vm::onArchiveTag)
            Spacer(Modifier.weight(1f))
            TextAction(Texts.DELETE, vm::onDeleteTag, color = if (f.used > 0) AppTheme.colors.tertiary else AppTheme.colors.error)
        }
        if (f.used > 0) Hint(Texts.DELETE_LOCKED)
    }
}
