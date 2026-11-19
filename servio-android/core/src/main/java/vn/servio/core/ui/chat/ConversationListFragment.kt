package vn.servio.core.ui.chat

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
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import vn.servio.core.R
import vn.servio.core.data.model.Conversation
import vn.servio.core.data.model.ConversationStatus
import vn.servio.core.data.repository.ChatRepository
import vn.servio.core.databinding.FragmentConversationListBinding
import vn.servio.core.databinding.ItemConversationBinding
import vn.servio.core.utils.UiState
import vn.servio.core.utils.collectWhenStarted
import vn.servio.core.utils.toUiState
import javax.inject.Inject

@HiltViewModel
class ConversationListViewModel @Inject constructor(
    private val repository: ChatRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<List<Conversation>>>(UiState.Loading)
    val state: StateFlow<UiState<List<Conversation>>> = _state.asStateFlow()

    init {
        load()
        // A new message or a reconnect changes the order, previews and unread counts: reload the list (#54).
        viewModelScope.launch { merge(repository.incoming(), repository.reconnected()).collect { load(showSpinner = false) } }
    }

    fun load(showSpinner: Boolean = true) {
        if (showSpinner) _state.value = UiState.Loading
        viewModelScope.launch { _state.value = repository.conversations().toUiState() }
    }
}

/** CS-27 / PS-20 Tin nhắn, shared by both apps. Opens the `chat` destination of the app's nav graph. */
@AndroidEntryPoint
class ConversationListFragment : Fragment(R.layout.fragment_conversation_list) {

    private val viewModel: ConversationListViewModel by viewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentConversationListBinding.bind(view)
        val adapter = ConversationAdapter { conversation ->
            findNavController().navigate(R.id.chat, bundleOf(ChatFragment.ARG_CONVERSATION_ID to conversation.id))
        }
        binding.list.adapter = adapter
        binding.swipeRefresh.setOnRefreshListener { viewModel.load() }
        binding.retryButton.setOnClickListener { viewModel.load() }

        collectWhenStarted(viewModel.state) { state ->
            binding.swipeRefresh.isRefreshing = state is UiState.Loading
            val empty = state is UiState.Content && state.data.isEmpty()
            binding.messageGroup.isVisible = state is UiState.Error || empty
            binding.retryButton.isVisible = state is UiState.Error
            when (state) {
                UiState.Loading -> Unit
                is UiState.Error -> binding.message.text = state.message
                is UiState.Content -> {
                    adapter.submitList(state.data)
                    binding.message.setText(R.string.chat_list_empty)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Coming back from a conversation: its unread count is now 0.
        viewModel.load(showSpinner = false)
    }

    private class ConversationAdapter(
        private val onOpen: (Conversation) -> Unit,
    ) : ListAdapter<Conversation, ConversationAdapter.Holder>(Diff) {

        class Holder(val binding: ItemConversationBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemConversationBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val conversation = getItem(position)
            val context = holder.binding.root.context
            with(holder.binding) {
                avatar.text = ChatTexts.initials(conversation.counterpart.displayName)
                name.text = conversation.counterpart.displayName
                time.text = ChatTexts.listTime(context, conversation.lastMessage?.at ?: conversation.createdAt)
                requestLine.text = conversation.contextLine
                preview.text = conversation.lastMessage?.preview.orEmpty()
                readOnly.isVisible = conversation.status == ConversationStatus.READ_ONLY || !conversation.canSend
                unread.isVisible = conversation.unreadCount > 0
                unread.text = conversation.unreadCount.toString()
                root.setOnClickListener { onOpen(conversation) }
            }
        }

        private object Diff : DiffUtil.ItemCallback<Conversation>() {
            override fun areItemsTheSame(old: Conversation, new: Conversation) = old.id == new.id
            override fun areContentsTheSame(old: Conversation, new: Conversation) = old == new
        }
    }
}
