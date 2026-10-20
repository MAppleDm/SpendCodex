package dev.spendcodex.ui

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.spendcodex.data.backup.ExportScope
import dev.spendcodex.data.domain.CategoryKind
import dev.spendcodex.data.domain.Draft
import dev.spendcodex.data.domain.Filter
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** What the operation page opens for: a new record (with what is already known) or a stored one. */
sealed interface OperationRequest {
    data class New(val draft: Draft) : OperationRequest
    data class Edit(val id: String) : OperationRequest
}

/**
 * The pages, as a stack: the journal at the bottom, everything else on top of it, back pops. Each page carries what it was
 * opened with and a [key], so a page opened twice in a row is two openings, and a rotation is not a new one.
 */
sealed interface Screen {
    val key: String

    data object Journal : Screen { override val key = "journal" }
    /** [fromLine]: opened with what the quick line had; recording it here clears the line. */
    data class Operation(val request: OperationRequest, val fromLine: Boolean = false, override val key: String = UUID.randomUUID().toString()) : Screen
    data class Search(val filter: Filter = Filter(), override val key: String = UUID.randomUUID().toString()) : Screen
    data object Categories : Screen { override val key = "categories" }
    data class CategoryEdit(val id: String?, val kind: CategoryKind, val parentId: String?, override val key: String = UUID.randomUUID().toString()) : Screen
    data class TagEdit(val id: String, override val key: String = UUID.randomUUID().toString()) : Screen
    data class Data(val scope: ExportScope? = null, override val key: String = UUID.randomUUID().toString()) : Screen
    data object Settings : Screen { override val key = "settings" }
    data object Ai : Screen { override val key = "ai" }
    data object FormatDoc : Screen { override val key = "format" }
}

@HiltViewModel
class NavViewModel @Inject constructor() : ViewModel() {
    private val _stack = MutableStateFlow<List<Screen>>(listOf(Screen.Journal))
    val stack: StateFlow<List<Screen>> = _stack.asStateFlow()

    val top: Screen get() = _stack.value.last()

    fun push(screen: Screen) = _stack.update { it + screen }

    /** False when there is nothing to go back to (the journal itself). */
    fun back(): Boolean {
        if (_stack.value.size <= 1) return false
        _stack.update { it.dropLast(1) }
        return true
    }

    fun home() = _stack.update { listOf(Screen.Journal) }
}

/** A line above the input that says what just happened, with a way to take it back. */
data class Notice(val text: String, val action: String? = null, val undo: (suspend () -> Unit)? = null, val id: Long = System.nanoTime())

/** The one notice shown at a time, shared by the pages: a deletion on the operation page is undone on the journal. */
@Singleton
class Notices @Inject constructor() {
    private val _current = MutableStateFlow<Notice?>(null)
    val current: StateFlow<Notice?> = _current.asStateFlow()

    fun show(notice: Notice) {
        _current.value = notice
    }

    fun dismiss(id: Long) = _current.update { if (it?.id == id) null else it }
}
