package vn.servio.partner.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import vn.servio.partner.data.model.FeedFilter
import javax.inject.Inject
import javax.inject.Singleton

/** The newsfeed filter, shared by PS-07 (applies it) and the PS-08 sheet (edits it). Kept for the app session. */
@Singleton
class FeedFilterStore @Inject constructor() {
    private val _filter = MutableStateFlow(FeedFilter())
    val filter: StateFlow<FeedFilter> = _filter.asStateFlow()

    fun set(filter: FeedFilter) {
        _filter.value = filter
    }
}
