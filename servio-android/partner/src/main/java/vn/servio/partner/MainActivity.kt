package vn.servio.partner

import dagger.hilt.android.AndroidEntryPoint
import vn.servio.core.ui.MainHostActivity

/** ServioPartner. Tabs: PS-07 Newsfeed, PS-12 Báo giá, PS-13 Đơn hàng, PS-20 Tin nhắn, PS-21 Thu nhập. */
@AndroidEntryPoint
class MainActivity : MainHostActivity() {
    override val navGraphRes = R.navigation.nav_root
    override val bottomMenuRes = R.menu.bottom_nav
    override val topLevelDestinations = setOf(R.id.ps07, R.id.ps12, R.id.ps13, R.id.ps20, R.id.ps21)
}
