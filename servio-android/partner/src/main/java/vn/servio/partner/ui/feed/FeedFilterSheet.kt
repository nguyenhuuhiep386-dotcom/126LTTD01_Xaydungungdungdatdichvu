package vn.servio.partner.ui.feed

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import vn.servio.core.utils.UiState
import vn.servio.core.utils.collectWhenStarted
import vn.servio.core.utils.toUiState
import vn.servio.partner.R
import vn.servio.partner.data.model.ApprovalStatus
import vn.servio.partner.data.model.FeedFilter
import vn.servio.partner.data.model.FeedSort
import vn.servio.partner.data.model.PartnerMe
import vn.servio.partner.data.repository.FeedFilterStore
import vn.servio.partner.data.repository.PartnerProfileRepository
import vn.servio.partner.databinding.SheetFeedFilterBinding
import javax.inject.Inject

@HiltViewModel
class FeedFilterViewModel @Inject constructor(
    private val profileRepository: PartnerProfileRepository,
    private val filterStore: FeedFilterStore,
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<PartnerMe>>(UiState.Loading)
    val state: StateFlow<UiState<PartnerMe>> = _state.asStateFlow()

    val current: FeedFilter get() = filterStore.filter.value

    init {
        load()
    }

    fun load() {
        _state.value = UiState.Loading
        viewModelScope.launch { _state.value = profileRepository.getMe().toUiState() }
    }

    fun apply(filter: FeedFilter) = filterStore.set(filter)
}

/** PS-08 Bộ lọc bảng tin: approved skills, max distance (never above the profile radius), sort. */
@AndroidEntryPoint
class FeedFilterSheet : BottomSheetDialogFragment() {

    private val viewModel: FeedFilterViewModel by viewModels()
    private var binding: SheetFeedFilterBinding? = null
    private var filled = false

    override fun getTheme() = R.style.ThemeOverlay_Servio_BottomSheetDialog

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        SheetFeedFilterBinding.inflate(inflater, container, false).also { binding = it }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = binding ?: return
        binding.closeButton.setOnClickListener { dismiss() }
        binding.resetButton.setOnClickListener {
            viewModel.apply(FeedFilter())
            dismiss()
        }
        binding.applyButton.setOnClickListener {
            viewModel.apply(readFilter(binding))
            dismiss()
        }
        collectWhenStarted(viewModel.state) { state ->
            binding.progress.isVisible = state is UiState.Loading
            binding.errorText.isVisible = state is UiState.Error
            binding.content.isVisible = state is UiState.Content
            binding.applyButton.isEnabled = state is UiState.Content
            if (state is UiState.Error) binding.errorText.text = state.message
            if (state is UiState.Content && !filled) {
                filled = true
                fill(binding, state.data)
            }
        }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    private fun fill(binding: SheetFeedFilterBinding, me: PartnerMe) {
        val filter = viewModel.current
        val approved = me.skills.filter { it.status == ApprovalStatus.APPROVED }
        binding.noSkills.isVisible = approved.isEmpty()
        binding.skillChips.removeAllViews()
        approved.forEach { skill ->
            val chip = layoutInflater.inflate(R.layout.item_filter_chip, binding.skillChips, false) as Chip
            chip.text = skill.categoryName
            chip.tag = skill.categoryId
            chip.isChecked = skill.categoryId in filter.categoryIds
            binding.skillChips.addView(chip)
        }

        val radius = me.serviceRadiusKm
        binding.distanceHint.text = getString(R.string.filter_distance_hint, radius)
        binding.distanceChips.removeAllViews()
        DISTANCES.filter { it <= radius }.forEach { km ->
            val chip = layoutInflater.inflate(R.layout.item_filter_chip, binding.distanceChips, false) as Chip
            chip.text = getString(R.string.feed_filter_distance, km)
            chip.tag = km
            chip.isChecked = filter.maxDistanceKm == km
            binding.distanceChips.addView(chip)
        }
        binding.sortChips.check(if (filter.sort == FeedSort.NEWEST) R.id.sortNewest else R.id.sortDistance)
    }

    private fun readFilter(binding: SheetFeedFilterBinding): FeedFilter {
        val skills = (0 until binding.skillChips.childCount)
            .map { binding.skillChips.getChildAt(it) as Chip }
            .filter { it.isChecked }
            .map { it.tag as String }
            .toSet()
        val distance = (0 until binding.distanceChips.childCount)
            .map { binding.distanceChips.getChildAt(it) as Chip }
            .firstOrNull { it.isChecked }
            ?.tag as Int?
        val sort = if (binding.sortChips.checkedChipId == R.id.sortNewest) FeedSort.NEWEST else FeedSort.DISTANCE
        return FeedFilter(skills, distance, sort)
    }

    private companion object {
        val DISTANCES = listOf(3, 5, 10, 20)
    }
}
