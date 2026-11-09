package vn.servio.customer.ui.common

import androidx.annotation.DrawableRes
import vn.servio.core.data.model.Category
import vn.servio.customer.R

/**
 * Icon of a level-1 group (CS-06, CS-07). The API has no icon yet (`iconUrl` is null), so groups are matched by
 * slug from the seed (spec 1.2) instead of by id. An unknown slug (a group added later by the admin) gets the grid icon.
 */
object CategoryIcons {

    private val bySlug = mapOf(
        "dien-nuoc" to R.drawable.ic_category_electric_water,
        "dien-lanh" to R.drawable.ic_category_cooling,
        "dien-tu-cntt" to R.drawable.ic_category_electronics,
        "ve-sinh-nha-cua" to R.drawable.ic_category_cleaning,
        "noi-that-xay-dung" to R.drawable.ic_category_furniture,
        "lam-dep-tai-nha" to R.drawable.ic_category_beauty,
        "cham-soc" to R.drawable.ic_category_care,
    )

    @DrawableRes
    fun of(category: Category): Int = bySlug[category.slug] ?: R.drawable.ic_category_other
}
