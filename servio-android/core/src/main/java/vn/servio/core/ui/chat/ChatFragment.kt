package vn.servio.core.ui.chat

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import vn.servio.core.R
import vn.servio.core.data.model.ConversationStatus
import vn.servio.core.databinding.FragmentChatBinding
import vn.servio.core.databinding.ItemMessageInBinding
import vn.servio.core.databinding.ItemMessageOutBinding
import vn.servio.core.databinding.ItemMessageSystemBinding
import vn.servio.core.ui.chat.ChatViewModel.Item
import vn.servio.core.ui.chat.ChatViewModel.SendState
import vn.servio.core.utils.UiState
import vn.servio.core.utils.collectWhenStarted

/**
 * CS-28 / PS-20a Phòng chat, shared by both apps. Each app's nav graph declares it with id `chat`
 * and a string argument `conversationId` (see [ARG_CONVERSATION_ID]).
 */
@AndroidEntryPoint
class ChatFragment : Fragment(R.layout.fragment_chat) {

    private val viewModel: ChatViewModel by viewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentChatBinding.bind(view)
        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        binding.retryButton.setOnClickListener { viewModel.load() }

        val layoutManager = LinearLayoutManager(requireContext()).apply { stackFromEnd = true }
        val adapter = MessageAdapter(onRetry = viewModel::retry)
        binding.list.layoutManager = layoutManager
        binding.list.adapter = adapter
        binding.list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy < 0 && layoutManager.findFirstVisibleItemPosition() <= LOAD_MORE_THRESHOLD) viewModel.loadMore()
            }
        })

        binding.input.doAfterTextChanged { binding.sendButton.isEnabled = !it.isNullOrBlank() }
        binding.sendButton.isEnabled = false
        binding.sendButton.setOnClickListener {
            viewModel.send(binding.input.text?.toString().orEmpty())
            binding.input.setText("")
        }

        collectWhenStarted(viewModel.state) { state ->
            binding.progress.isVisible = state is UiState.Loading
            binding.errorGroup.isVisible = state is UiState.Error
            binding.list.isVisible = state is UiState.Content
            if (state is UiState.Error) binding.errorText.text = state.message
            if (state !is UiState.Content) return@collectWhenStarted

            val conversation = state.data.conversation
            binding.avatar.text = ChatTexts.initials(conversation.counterpart.displayName)
            binding.name.text = conversation.counterpart.displayName
            binding.subtitle.text = conversation.contextLine
            val readOnly = !conversation.canSend || conversation.status == ConversationStatus.READ_ONLY
            binding.composer.isVisible = !readOnly
            binding.readOnlyBanner.isVisible = readOnly
            binding.privacyNote.isVisible = conversation.orderId == null

            val atBottom = !binding.list.canScrollVertically(1)
            val grew = state.data.items.size > adapter.itemCount
            adapter.submitList(state.data.items) {
                if (grew && (atBottom || state.data.items.lastOrNull()?.mine == true)) {
                    binding.list.scrollToPosition(adapter.itemCount - 1)
                }
            }
        }
        collectWhenStarted(viewModel.events) { message -> Snackbar.make(view, message, Snackbar.LENGTH_LONG).show() }
    }

    private class MessageAdapter(private val onRetry: (Item) -> Unit) : ListAdapter<Item, RecyclerView.ViewHolder>(Diff) {

        class Incoming(val binding: ItemMessageInBinding) : RecyclerView.ViewHolder(binding.root)
        class Outgoing(val binding: ItemMessageOutBinding) : RecyclerView.ViewHolder(binding.root)
        class SystemHolder(val binding: ItemMessageSystemBinding) : RecyclerView.ViewHolder(binding.root)

        override fun getItemViewType(position: Int): Int {
            val item = getItem(position)
            return when {
                item.isSystem -> TYPE_SYSTEM
                item.mine -> TYPE_OUT
                else -> TYPE_IN
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return when (viewType) {
                TYPE_SYSTEM -> SystemHolder(ItemMessageSystemBinding.inflate(inflater, parent, false))
                TYPE_OUT -> Outgoing(ItemMessageOutBinding.inflate(inflater, parent, false))
                else -> Incoming(ItemMessageInBinding.inflate(inflater, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item = getItem(position)
            val context = holder.itemView.context
            val text = textOf(context, item)
            val time = ChatTexts.messageTime(item.message.createdAt)
            when (holder) {
                is SystemHolder -> holder.binding.text.text = text
                is Incoming -> {
                    holder.binding.text.text = text
                    holder.binding.time.text = time
                }
                is Outgoing -> {
                    holder.binding.text.text = text
                    val stateText = when (item.sendState) {
                        SendState.SENDING -> context.getString(R.string.chat_sending)
                        SendState.SENT -> context.getString(R.string.chat_sent)
                        SendState.READ -> context.getString(R.string.chat_read)
                        SendState.FAILED -> context.getString(R.string.chat_failed)
                    }
                    holder.binding.time.text = if (item.sendState == SendState.SENDING) stateText else "$time · $stateText"
                    holder.binding.time.isActivated = item.sendState == SendState.FAILED
                    holder.binding.root.setOnClickListener { onRetry(item) }
                }
            }
        }

        private fun textOf(context: android.content.Context, item: Item): String {
            val message = item.message
            return if (message.attachmentUrl != null && message.content.isBlank()) {
                context.getString(R.string.chat_image)
            } else {
                message.content
            }
        }

        private object Diff : DiffUtil.ItemCallback<Item>() {
            override fun areItemsTheSame(old: Item, new: Item) = old.key == new.key
            override fun areContentsTheSame(old: Item, new: Item) = old == new
        }

        private companion object {
            const val TYPE_IN = 0
            const val TYPE_OUT = 1
            const val TYPE_SYSTEM = 2
        }
    }

    companion object {
        const val ARG_CONVERSATION_ID = "conversationId"
        private const val LOAD_MORE_THRESHOLD = 3
    }
}
