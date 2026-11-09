package vn.servio.customer.ui.category

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import vn.servio.core.data.model.Category
import vn.servio.core.data.repository.CategoryRepository
import vn.servio.core.network.ApiResult
import vn.servio.core.utils.UiState
import java.text.Normalizer
import javax.inject.Inject

/**
 * CS-07: level-1 groups on the left, level-2 services of the selected group on the right.
 * Reads the same in-memory tree as CS-06 through [CategoryRepository], so opening this screen after Home costs no
 * request and still works offline. Search filters that tree on the device; it never calls the API per keystroke.
 */
@HiltViewModel
class CategoryListViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val categoryRepository: CategoryRepository,
) : ViewModel() {

    /** A level-2 service; [group] is filled only in search results, where services of every group are mixed. */
    data class ServiceRow(val service: Category, val group: Category?)

    data class Content(
        val groups: List<Category>,
        /** Null while searching: the left column then shows no selection. */
        val selectedGroup: Category?,
        val services: List<ServiceRow>,
        val keyword: String,
    ) {
        val isSearching: Boolean get() = keyword.isNotBlank()
    }

    private val tree = MutableStateFlow<UiState<List<Category>>>(UiState.Loading)

    // Kept in SavedStateHandle so rotation and process restore keep the selection and the typed keyword.
    private val selectedGroupId = savedStateHandle.getStateFlow<String?>(KEY_SELECTED, savedStateHandle[ARG_CATEGORY_ID])
    private val keyword = savedStateHandle.getStateFlow(KEY_KEYWORD, savedStateHandle.get<String>(ARG_KEYWORD).orEmpty())

    /** Keyword the screen opened with, to prefill the search box once. */
    val initialKeyword: String get() = keyword.value

    val state: StateFlow<UiState<Content>> = combine(tree, selectedGroupId, keyword) { tree, selectedId, keyword ->
        when (tree) {
            UiState.Loading -> UiState.Loading
            is UiState.Error -> tree
            is UiState.Content -> UiState.Content(buildContent(tree.data, selectedId, keyword))
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState.Loading)

    init {
        load()
    }

    fun load() {
        tree.value = UiState.Loading
        viewModelScope.launch {
            tree.value = when (val result = categoryRepository.getCategoryTree()) {
                is ApiResult.Failure -> UiState.Error(result.message)
                is ApiResult.Success -> UiState.Content(
                    result.data.sortedBy { it.displayOrder }
                        .map { group -> group.copy(children = group.children.sortedBy { it.displayOrder }) },
                )
            }
        }
    }

    /** Picking a group leaves search mode, so the right column shows that group's services again. */
    fun selectGroup(id: String) {
        savedStateHandle[KEY_SELECTED] = id
        savedStateHandle[KEY_KEYWORD] = ""
    }

    fun setKeyword(value: String) {
        savedStateHandle[KEY_KEYWORD] = value
    }

    private fun buildContent(groups: List<Category>, selectedId: String?, keyword: String): Content {
        val query = normalize(keyword)
        if (query.isNotEmpty()) {
            // A keyword matching the group name ("dien lanh") lists every service of that group.
            val matches = groups.flatMap { group ->
                val groupMatches = normalize(group.name).contains(query)
                group.children.filter { groupMatches || normalize(it.name).contains(query) }.map { ServiceRow(it, group) }
            }
            return Content(groups, selectedGroup = null, services = matches, keyword = keyword)
        }
        // An unknown or level-2 id (or none) falls back to the first group.
        val selected = groups.firstOrNull { it.id == selectedId } ?: groups.firstOrNull()
        val services = selected?.children.orEmpty().map { ServiceRow(it, group = null) }
        return Content(groups, selected, services, keyword)
    }

    companion object {
        const val ARG_CATEGORY_ID = "categoryId"
        const val ARG_KEYWORD = "keyword"
        private const val KEY_SELECTED = "selectedGroupId"
        private const val KEY_KEYWORD = "searchKeyword"

        private val combiningMarks = Regex("\\p{Mn}+")

        /** "Máy Lạnh", "may lanh" and "MÁY LẠNH" all become "may lanh"; "đ" is not a combining mark, so it is mapped. */
        fun normalize(text: String): String =
            Normalizer.normalize(text.trim().lowercase(), Normalizer.Form.NFD)
                .replace(combiningMarks, "")
                .replace('đ', 'd')
                .replace(Regex("\\s+"), " ")
    }
}
