package vn.servio.customer.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import vn.servio.core.data.model.Category
import vn.servio.core.data.repository.AuthRepository
import vn.servio.core.data.repository.CategoryRepository
import vn.servio.core.network.ApiResult
import vn.servio.core.utils.UiState
import javax.inject.Inject

/**
 * REFERENCE PATTERN (real API): the ViewModel calls repositories in parallel and exposes one StateFlow.
 * CS-06 full spec (order tracking button, open requests) is work package KH-2.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val categoryRepository: CategoryRepository,
) : ViewModel() {

    data class HomeContent(val firstName: String, val categories: List<Category>)

    private val _state = MutableStateFlow<UiState<HomeContent>>(UiState.Loading)
    val state: StateFlow<UiState<HomeContent>> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            val me = async { authRepository.getMe() }
            val categories = async { categoryRepository.getCategoryTree() }
            val categoriesResult = categories.await()
            _state.value = when (categoriesResult) {
                is ApiResult.Failure -> UiState.Error(categoriesResult.message)
                is ApiResult.Success -> {
                    val fullName = (me.await() as? ApiResult.Success)?.data?.fullName.orEmpty()
                    UiState.Content(HomeContent(fullName.trim().substringAfterLast(' '), categoriesResult.data))
                }
            }
        }
    }
}
