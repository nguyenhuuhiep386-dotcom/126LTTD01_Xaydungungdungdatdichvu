package vn.servio.partner.ui.feed

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.combine
import vn.servio.core.utils.UiState
import vn.servio.core.utils.collectWhenStarted
import vn.servio.partner.R
import vn.servio.partner.data.location.DeviceLocation
import vn.servio.partner.data.model.FeedSort
import vn.servio.partner.databinding.FragmentFeedBinding
import vn.servio.partner.ui.feed.FeedViewModel.Event
import vn.servio.partner.ui.quote.RequestDetailFragment
import javax.inject.Inject
import vn.servio.core.R as CoreR

/** PS-07 Bảng tin. Online is needed to receive NewPost; the list itself (#46) works either way. */
@AndroidEntryPoint
class FeedFragment : Fragment(R.layout.fragment_feed) {

    @Inject lateinit var deviceLocation: DeviceLocation

    private val viewModel: FeedViewModel by viewModels()

    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants.values.any { it }) {
                viewModel.setOnline(true)
            } else {
                showMessage(getString(R.string.feed_online_needs_location))
            }
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentFeedBinding.bind(view)
        val adapter = FeedAdapter(
            onOpen = { item -> openRequest(item.requestId, quote = false) },
            onQuote = { item -> openRequest(item.requestId, quote = true) },
        )
        binding.list.adapter = adapter
        binding.filterButton.setOnClickListener { findNavController().navigate(R.id.ps08) }
        binding.swipeRefresh.setOnRefreshListener { viewModel.load() }
        binding.retryButton.setOnClickListener { viewModel.load() }
        binding.newBanner.setOnClickListener {
            binding.list.smoothScrollToPosition(0)
            viewModel.clearNewCount()
        }
        // The switch shows the ViewModel state; a tap is a request, applied when the server agrees.
        binding.onlineSwitch.setOnClickListener {
            val wanted = binding.onlineSwitch.isChecked
            binding.onlineSwitch.isChecked = viewModel.isOnline.value
            when {
                !wanted -> viewModel.setOnline(false)
                deviceLocation.hasPermission -> viewModel.setOnline(true)
                else -> locationPermission.launch(DeviceLocation.PERMISSIONS)
            }
        }

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
                    binding.message.setText(if (viewModel.isOnline.value) R.string.feed_empty else R.string.feed_empty_offline)
                }
            }
        }
        collectWhenStarted(viewModel.isOnline.combine(viewModel.switching) { online, switching -> online to switching }) { (online, switching) ->
            binding.onlineSwitch.isChecked = online
            binding.onlineSwitch.isEnabled = !switching
            binding.onlineTitle.setText(if (online) R.string.feed_online else R.string.feed_offline)
            binding.onlineSubtitle.setText(
                when {
                    switching -> R.string.feed_online_switching
                    online -> R.string.feed_online_hint
                    else -> R.string.feed_offline_hint
                },
            )
            val dot = if (online) CoreR.color.servio_success else CoreR.color.servio_outline
            binding.onlineDot.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), dot))
        }
        collectWhenStarted(viewModel.newCount) { count ->
            binding.newBanner.isVisible = count > 0
            binding.newBanner.text = getString(R.string.feed_new_posts, count)
        }
        collectWhenStarted(viewModel.filter) { filter ->
            val parts = buildList {
                add(
                    if (filter.categoryIds.isEmpty()) {
                        getString(R.string.feed_filter_all_skills)
                    } else {
                        getString(R.string.feed_filter_skills, filter.categoryIds.size)
                    },
                )
                filter.maxDistanceKm?.let { add(getString(R.string.feed_filter_distance, it)) }
                add(getString(if (filter.sort == FeedSort.DISTANCE) R.string.feed_sort_distance else R.string.feed_sort_newest))
            }
            binding.filterSummary.text = parts.joinToString(" · ")
        }
        collectWhenStarted(viewModel.events) { event ->
            when (event) {
                is Event.Message -> showMessage(event.text)
                Event.LocationUnavailable -> showMessage(getString(R.string.location_unavailable))
            }
        }
    }

    private fun openRequest(requestId: String, quote: Boolean) {
        findNavController().navigate(
            R.id.ps10,
            bundleOf(RequestDetailFragment.ARG_REQUEST_ID to requestId, RequestDetailFragment.ARG_OPEN_QUOTE to quote),
        )
    }

    private fun showMessage(text: String) {
        val view = view ?: return
        Snackbar.make(view, text, Snackbar.LENGTH_LONG).show()
    }
}
