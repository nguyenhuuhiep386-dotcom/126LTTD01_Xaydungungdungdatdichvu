package vn.servio.partner.ui.profile

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import vn.servio.core.ui.chat.ChatFragment
import vn.servio.core.utils.UiState
import vn.servio.core.utils.collectWhenStarted
import vn.servio.core.utils.toUiState
import vn.servio.partner.R
import vn.servio.partner.data.model.AppNotification
import vn.servio.partner.data.repository.AccountRepository
import vn.servio.partner.databinding.FragmentNotificationsBinding
import vn.servio.partner.databinding.ItemNotificationBinding
import vn.servio.partner.ui.common.Texts
import vn.servio.partner.ui.order.OrderDetailFragment
import vn.servio.partner.ui.quote.RequestDetailFragment
import javax.inject.Inject

@HiltViewModel
class NotificationsViewModel @Inject constructor(
    private val repository: AccountRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<List<AppNotification>>>(UiState.Loading)
    val state: StateFlow<UiState<List<AppNotification>>> = _state.asStateFlow()

    init {
        load()
    }

    fun load(showSpinner: Boolean = true) {
        if (showSpinner) _state.value = UiState.Loading
        viewModelScope.launch { _state.value = repository.notifications().toUiState() }
    }

    fun markRead(notification: AppNotification) {
        if (notification.isRead) return
        setRead { it.id == notification.id }
        viewModelScope.launch { repository.markRead(notification.id) }
    }

    fun markAllRead() {
        setRead { true }
        viewModelScope.launch { repository.markAllRead() }
    }

    private fun setRead(predicate: (AppNotification) -> Boolean) {
        _state.update { state ->
            if (state is UiState.Content) UiState.Content(state.data.map { if (predicate(it)) it.copy(isRead = true) else it }) else state
        }
    }
}

/** PS-28 Thông báo (#101–#103). A tap marks it read and follows its deepLink (order, post or chat). */
@AndroidEntryPoint
class NotificationsFragment : Fragment(R.layout.fragment_notifications) {

    private val viewModel: NotificationsViewModel by viewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentNotificationsBinding.bind(view)
        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        binding.toolbar.inflateMenu(R.menu.notifications_actions)
        binding.toolbar.setOnMenuItemClickListener {
            viewModel.markAllRead()
            true
        }
        val adapter = NotificationAdapter { notification ->
            viewModel.markRead(notification)
            open(notification.deepLink)
        }
        binding.list.adapter = adapter
        binding.swipeRefresh.setOnRefreshListener { viewModel.load(showSpinner = false) }

        collectWhenStarted(viewModel.state) { state ->
            binding.swipeRefresh.isRefreshing = state is UiState.Loading
            val empty = state is UiState.Content && state.data.isEmpty()
            binding.message.isVisible = state is UiState.Error || empty
            binding.message.text = if (state is UiState.Error) state.message else getString(R.string.notifications_empty)
            adapter.submitList((state as? UiState.Content)?.data.orEmpty())
        }
    }

    /** deepLink: `orders/{id}`, `service-requests/{id}` or `conversations/{id}`. */
    private fun open(deepLink: String?) {
        val parts = deepLink?.trim('/')?.split('/') ?: return
        if (parts.size < 2) return
        val id = parts[1]
        val nav = findNavController()
        when (parts[0]) {
            "orders" -> nav.navigate(R.id.ps14, bundleOf(OrderDetailFragment.ARG_ORDER_ID to id))
            "service-requests" -> nav.navigate(R.id.ps10, bundleOf(RequestDetailFragment.ARG_REQUEST_ID to id))
            "conversations" -> nav.navigate(R.id.chat, bundleOf(ChatFragment.ARG_CONVERSATION_ID to id))
        }
    }

    private class NotificationAdapter(private val onOpen: (AppNotification) -> Unit) :
        ListAdapter<AppNotification, NotificationAdapter.Holder>(Diff) {

        class Holder(val binding: ItemNotificationBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemNotificationBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = getItem(position)
            val context = holder.binding.root.context
            with(holder.binding) {
                title.text = item.title
                body.text = item.body
                time.text = Texts.ago(context, item.createdAt)
                unreadDot.isVisible = !item.isRead
                root.isActivated = !item.isRead
                root.setOnClickListener { onOpen(item) }
            }
        }

        private object Diff : DiffUtil.ItemCallback<AppNotification>() {
            override fun areItemsTheSame(old: AppNotification, new: AppNotification) = old.id == new.id
            override fun areContentsTheSame(old: AppNotification, new: AppNotification) = old == new
        }
    }
}
