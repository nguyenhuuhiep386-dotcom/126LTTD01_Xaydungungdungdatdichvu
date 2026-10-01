package vn.servio.core.data.repository

import vn.servio.core.data.model.Category
import vn.servio.core.data.remote.CategoryApi
import vn.servio.core.network.ApiResult
import vn.servio.core.network.apiCall
import javax.inject.Inject
import javax.inject.Singleton

/**
 * REFERENCE PATTERN (real API): interface + Remote implementation, bound in [RepositoryModule].
 * Screens depend on the interface only, so a Fake implementation can be swapped in without UI changes.
 */
interface CategoryRepository {
    /** Level-1 groups, each with its level-2 services. */
    suspend fun getCategoryTree(): ApiResult<List<Category>>
}

@Singleton
class RemoteCategoryRepository @Inject constructor(
    private val api: CategoryApi,
) : CategoryRepository {

    // ponytail: in-memory cache for the app session; categories change rarely (server caches 10 minutes).
    private var cache: List<Category>? = null

    override suspend fun getCategoryTree(): ApiResult<List<Category>> {
        cache?.let { return ApiResult.Success(it) }
        val result = apiCall { api.getTree() }
        if (result is ApiResult.Success) cache = result.data
        return result
    }
}
