package vn.servio.customer

import dagger.hilt.android.AndroidEntryPoint
import vn.servio.core.ui.MainHostActivity

/** Servio (customer). Tabs: CS-06 Trang chủ, CS-14 Yêu cầu, CS-19 Đơn hàng, CS-27 Tin nhắn, CS-29 Tài khoản. */
@AndroidEntryPoint
class MainActivity : MainHostActivity() {
    override val navGraphRes = R.navigation.nav_root
    override val bottomMenuRes = R.menu.bottom_nav
    override val topLevelDestinations = setOf(R.id.cs06, R.id.cs14, R.id.cs19, R.id.cs27, R.id.cs29)
}
