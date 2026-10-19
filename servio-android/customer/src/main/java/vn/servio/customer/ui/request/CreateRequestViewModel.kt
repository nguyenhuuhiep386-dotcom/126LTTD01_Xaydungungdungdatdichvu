package vn.servio.customer.ui.request

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import vn.servio.core.data.repository.CategoryRepository
import vn.servio.core.network.ApiResult
import vn.servio.core.network.ErrorMessages
import vn.servio.core.utils.UiState
import vn.servio.customer.data.model.Address
import vn.servio.customer.data.model.CreateServiceRequestRequest
import vn.servio.customer.data.model.RequestImageInput
import vn.servio.customer.data.model.ScheduleType
import vn.servio.customer.data.repository.AddressRepository
import vn.servio.customer.data.repository.CreateRequestRepository
import vn.servio.customer.data.repository.FileRepository
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

/**
 * Shared by CS-08 → CS-11 through `navGraphViewModels(R.id.create_request_graph)`: the draft lives as long as the
 * nested graph is on the back stack and is dropped when the customer leaves the flow or posts.
 * The graph argument `categoryId` is the level-2 service picked on CS-07.
 */
@HiltViewModel
class CreateRequestViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val categoryRepository: CategoryRepository,
    private val addressRepository: AddressRepository,
    private val fileRepository: FileRepository,
    private val createRequestRepository: CreateRequestRepository,
) : ViewModel() {

    enum class Step { DESCRIBE, ADDRESS_TIME, BUDGET }

    enum class FieldError {
        TITLE_LENGTH, TITLE_CONTACT_INFO, DESCRIPTION_LENGTH, DESCRIPTION_CONTACT_INFO, IMAGES_PENDING,
        ADDRESS_REQUIRED, SLOT_REQUIRED,
        BUDGET_INCOMPLETE, BUDGET_RANGE,
    }

    sealed interface Event {
        data class Posted(val requestId: String) : Event

        /** [step] is set when the server error belongs to a field of that step, so CS-11 can send the user back. */
        data class Error(val message: String, val step: Step? = null) : Event

        /** The draft is no longer valid on [step] (e.g. the chosen slot is now too close); checked before calling #36. */
        data class Incomplete(val step: Step) : Event
    }

    private var categoryId: String? = savedStateHandle[ARG_CATEGORY_ID]

    private val _category = MutableStateFlow<UiState<CategoryChoice>>(UiState.Loading)
    val category: StateFlow<UiState<CategoryChoice>> = _category.asStateFlow()

    private val _draft = MutableStateFlow(RequestDraft())
    val draft: StateFlow<RequestDraft> = _draft.asStateFlow()

    /** CS-09 address card: Content(null) means the address book is empty. */
    private val _address = MutableStateFlow<UiState<Address?>>(UiState.Loading)
    val address: StateFlow<UiState<Address?>> = _address.asStateFlow()

    private val _fieldErrors = MutableStateFlow<Set<FieldError>>(emptySet())
    val fieldErrors: StateFlow<Set<FieldError>> = _fieldErrors.asStateFlow()

    private val _posting = MutableStateFlow(false)
    val posting: StateFlow<Boolean> = _posting.asStateFlow()

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = _events.receiveAsFlow()

    private val nextImageId = AtomicLong()
    private var addressLoaded = false

    init {
        loadCategory()
    }

    // region Category (shown on CS-08 and CS-11; reference price on CS-10)

    fun loadCategory() {
        val id = categoryId
        if (id == null) {
            _category.value = UiState.Error(ErrorMessages.forCode(ErrorMessages.UNKNOWN))
            return
        }
        _category.value = UiState.Loading
        viewModelScope.launch {
            _category.value = when (val result = categoryRepository.getCategoryTree()) {
                is ApiResult.Failure -> UiState.Error(result.message)
                is ApiResult.Success -> result.data.firstNotNullOfOrNull { group ->
                    group.children.firstOrNull { it.id == id }?.let { CategoryChoice(it, group) }
                }?.let { UiState.Content(it) } ?: UiState.Error(ErrorMessages.forCode(ErrorMessages.UNKNOWN))
            }
        }
    }

    /** "Đổi" on CS-08: another level-2 service picked on CS-07. The rest of the draft is kept. */
    fun changeCategory(id: String) {
        if (id == categoryId) return
        categoryId = id
        loadCategory()
    }

    // endregion

    // region CS-08 Mô tả

    fun setTitle(title: String) = _draft.update { it.copy(title = title) }

    fun setDescription(description: String) = _draft.update { it.copy(description = description) }

    /** Adds up to the remaining slots and starts uploading each one right away. */
    fun addImages(uris: List<Uri>) {
        val free = RequestDraft.MAX_IMAGES - _draft.value.images.size
        val added = uris.take(free).map { DraftImage(nextImageId.incrementAndGet(), it, DraftImage.Status.UPLOADING) }
        if (added.isEmpty()) return
        _draft.update { it.copy(images = it.images + added) }
        added.forEach { upload(it) }
    }

    fun retryImage(localId: Long) {
        val image = _draft.value.images.firstOrNull { it.localId == localId } ?: return
        if (image.status != DraftImage.Status.FAILED) return
        val retrying = image.copy(status = DraftImage.Status.UPLOADING, errorMessage = null)
        replaceImage(retrying)
        upload(retrying)
    }

    fun removeImage(localId: Long) = _draft.update { draft -> draft.copy(images = draft.images.filter { it.localId != localId }) }

    private fun upload(image: DraftImage) {
        viewModelScope.launch {
            val updated = when (val result = fileRepository.uploadPostImage(image.uri)) {
                is ApiResult.Success -> image.copy(status = DraftImage.Status.UPLOADED, url = result.data.url)
                is ApiResult.Failure -> image.copy(status = DraftImage.Status.FAILED, errorMessage = result.message)
            }
            replaceImage(updated)
        }
    }

    /** No-op when the image was removed while uploading. */
    private fun replaceImage(image: DraftImage) = _draft.update { draft ->
        draft.copy(images = draft.images.map { if (it.localId == image.localId) image else it })
    }

    /** "Tiếp tục" on CS-08. */
    fun validateDescribe(): Boolean = setErrors(describeErrors(_draft.value))

    private fun describeErrors(draft: RequestDraft) = buildSet {
        val title = draft.title.trim()
        val description = draft.description.trim()
        if (title.length !in RequestDraft.TITLE_MIN..RequestDraft.TITLE_MAX) {
            add(FieldError.TITLE_LENGTH)
        } else if (ContactInfoGuard.containsContactInfo(title)) {
            add(FieldError.TITLE_CONTACT_INFO)
        }
        if (description.length !in RequestDraft.DESCRIPTION_MIN..RequestDraft.DESCRIPTION_MAX) {
            add(FieldError.DESCRIPTION_LENGTH)
        } else if (ContactInfoGuard.containsContactInfo(description)) {
            add(FieldError.DESCRIPTION_CONTACT_INFO)
        }
        if (draft.images.any { it.status != DraftImage.Status.UPLOADED }) add(FieldError.IMAGES_PENDING)
    }

    // endregion

    // region CS-09 Địa chỉ & thời gian

    /** First visit: picks the default address. Later visits keep the address the customer chose. */
    fun loadAddressIfNeeded() {
        if (!addressLoaded) loadDefaultAddress()
    }

    fun loadDefaultAddress() {
        _address.value = UiState.Loading
        viewModelScope.launch {
            when (val result = addressRepository.getAddresses()) {
                is ApiResult.Failure -> _address.value = UiState.Error(result.message)
                is ApiResult.Success -> {
                    addressLoaded = true
                    val chosen = result.data.firstOrNull { it.isDefault } ?: result.data.firstOrNull()
                    setAddress(chosen)
                }
            }
        }
    }

    /** Address returned by CS-13 (select mode) or CS-12 (new address). */
    fun selectAddress(id: String) {
        if (_draft.value.address?.id == id) return
        _address.value = UiState.Loading
        viewModelScope.launch {
            when (val result = addressRepository.getAddress(id)) {
                is ApiResult.Failure -> _address.value = UiState.Error(result.message)
                is ApiResult.Success -> {
                    addressLoaded = true
                    setAddress(result.data)
                }
            }
        }
    }

    private fun setAddress(address: Address?) {
        _draft.update { it.copy(address = address) }
        _address.value = UiState.Content(address)
    }

    fun setScheduleNow() = _draft.update { it.copy(scheduleType = ScheduleType.NOW) }

    /** "Hẹn lịch" or another day: keeps the slot only if it is still bookable on that day. */
    fun setScheduledDate(date: LocalDate) = _draft.update { draft ->
        val hour = draft.scheduledHour?.takeIf { ScheduleSlots.isAvailable(date, it, now()) }
        draft.copy(scheduleType = ScheduleType.SCHEDULED, scheduledDate = date, scheduledHour = hour)
    }

    fun setScheduledHour(hour: Int) = _draft.update { it.copy(scheduleType = ScheduleType.SCHEDULED, scheduledHour = hour) }

    /** "Tiếp tục" on CS-09. A slot that became too close (or past) while the screen was open counts as missing. */
    fun validateAddressTime(): Boolean = setErrors(addressTimeErrors(_draft.value))

    private fun addressTimeErrors(draft: RequestDraft) = buildSet {
        if (draft.address == null) add(FieldError.ADDRESS_REQUIRED)
        if (draft.scheduleType == ScheduleType.SCHEDULED) {
            val date = draft.scheduledDate
            val hour = draft.scheduledHour
            if (date == null || hour == null || !ScheduleSlots.isAvailable(date, hour, now())) add(FieldError.SLOT_REQUIRED)
        }
    }

    fun now(): ZonedDateTime = ZonedDateTime.now(ScheduleSlots.ZONE)

    // endregion

    // region CS-10 Ngân sách & yêu cầu

    /**
     * "Xem lại" on CS-10. [budgetMin]/[budgetMax] are the parsed inputs (null when empty); with [letPartnerQuote]
     * both are ignored. Otherwise both must be filled — one filled box alone is an error, never sent.
     */
    fun submitBudget(
        letPartnerQuote: Boolean,
        budgetMin: Long?,
        budgetMax: Long?,
        requireExperience: Boolean,
        requireCertificate: Boolean,
        requireHighRating: Boolean,
        searchRadiusKm: Int,
    ): Boolean {
        val errors = buildSet {
            if (!letPartnerQuote) {
                if (budgetMin == null || budgetMax == null) {
                    add(FieldError.BUDGET_INCOMPLETE)
                } else if (budgetMin < 0 || budgetMin > budgetMax) {
                    add(FieldError.BUDGET_RANGE)
                }
            }
        }
        _draft.update {
            it.copy(
                budgetMin = if (letPartnerQuote) null else budgetMin,
                budgetMax = if (letPartnerQuote) null else budgetMax,
                requireExperience = requireExperience,
                requireCertificate = requireCertificate,
                requireHighRating = requireHighRating,
                searchRadiusKm = searchRadiusKm,
            )
        }
        return setErrors(errors)
    }

    // endregion

    // region CS-11 Xem lại & đăng

    fun post() {
        if (_posting.value) return
        val draft = _draft.value
        val service = (_category.value as? UiState.Content)?.data?.service
        // Re-check every step: a slot may have expired while the customer was reviewing.
        val firstInvalid = when {
            describeErrors(draft).isNotEmpty() || service == null -> Step.DESCRIBE
            addressTimeErrors(draft).isNotEmpty() -> Step.ADDRESS_TIME
            else -> null
        }
        if (firstInvalid != null || service == null) {
            viewModelScope.launch { _events.send(Event.Incomplete(firstInvalid ?: Step.DESCRIBE)) }
            return
        }
        val address = draft.address ?: return
        val scheduled = draft.scheduleType == ScheduleType.SCHEDULED
        val request = CreateServiceRequestRequest(
            serviceCategoryId = service.id,
            title = draft.title.trim(),
            description = draft.description.trim(),
            addressId = address.id,
            images = draft.images.mapNotNull { image -> image.url?.let { RequestImageInput(it) } },
            scheduleType = draft.scheduleType,
            scheduledStartAt = if (scheduled) {
                ScheduleSlots.startOf(draft.scheduledDate!!, draft.scheduledHour!!).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            } else {
                null
            },
            budgetMin = draft.budgetMin,
            budgetMax = draft.budgetMax,
            requireExperienceYears = if (draft.requireExperience) RequestDraft.EXPERIENCE_YEARS else null,
            requireCertificate = draft.requireCertificate,
            requireMinRating = if (draft.requireHighRating) RequestDraft.MIN_RATING else null,
            searchRadiusKm = draft.searchRadiusKm,
        )
        _posting.value = true
        viewModelScope.launch {
            val result = createRequestRepository.createRequest(request)
            _posting.value = false
            _events.send(
                when (result) {
                    is ApiResult.Success -> Event.Posted(result.data.id)
                    is ApiResult.Failure -> Event.Error(result.message, stepOf(result.field))
                },
            )
        }
    }

    /** Maps a server field name of #36 to the step that edits it. */
    private fun stepOf(field: String?): Step? = when {
        field == null -> null
        field == "title" || field == "description" || field == "serviceCategoryId" || field.startsWith("images") -> Step.DESCRIBE
        field == "addressId" || field == "scheduledStartAt" || field == "scheduleType" -> Step.ADDRESS_TIME
        field.startsWith("budget") || field.startsWith("require") || field == "searchRadiusKm" -> Step.BUDGET
        else -> null
    }

    // endregion

    private fun setErrors(errors: Set<FieldError>): Boolean {
        _fieldErrors.value = errors
        return errors.isEmpty()
    }

    companion object {
        const val ARG_CATEGORY_ID = "categoryId"
    }
}
