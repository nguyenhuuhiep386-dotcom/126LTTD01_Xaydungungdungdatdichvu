package vn.servio.customer.ui.request

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import vn.servio.core.data.repository.CategoryRepository
import vn.servio.core.network.ApiResult
import vn.servio.core.network.ErrorMessages
import vn.servio.core.network.map
import vn.servio.core.utils.UiState
import vn.servio.customer.data.image.CameraCaptureFiles
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
 *
 * The service, title, description, address id and schedule live in [SavedStateHandle] so they survive process death;
 * the picked images do not (the photo picker's read grant ends with the process), except a camera capture still
 * waiting for its result.
 */
@HiltViewModel
class CreateRequestViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val categoryRepository: CategoryRepository,
    private val addressRepository: AddressRepository,
    private val fileRepository: FileRepository,
    private val createRequestRepository: CreateRequestRepository,
    private val cameraFiles: CameraCaptureFiles,
) : ViewModel() {

    enum class Step { DESCRIBE, ADDRESS_TIME, BUDGET }

    enum class FieldError {
        TITLE_LENGTH, TITLE_CONTACT_INFO, DESCRIPTION_LENGTH, DESCRIPTION_CONTACT_INFO, IMAGES_UPLOADING, IMAGES_FAILED,
        ADDRESS_REQUIRED, ADDRESS_NOT_READY, SLOT_REQUIRED,
        BUDGET_INCOMPLETE, BUDGET_RANGE,
    }

    sealed interface Event {
        data class Posted(val requestId: String) : Event

        /** [step] is set when the server error belongs to a field of that step, so CS-11 can send the user back. */
        data class Error(val message: String, val step: Step? = null) : Event

        /** The draft is no longer valid on [step] (e.g. the chosen slot is now too close); checked before calling #36. */
        data class Incomplete(val step: Step) : Event
    }

    /** CS-09 day chips and slot grid, as plain data: the screen builds the labels. */
    data class ScheduleUi(val days: List<DayOption>, val slots: List<SlotOption>, val selectedDayIndex: Int) {
        data class DayOption(val date: LocalDate, val enabled: Boolean, val selected: Boolean)
        data class SlotOption(val startHour: Int, val enabled: Boolean, val selected: Boolean)
    }

    /** Starts as the graph argument; "Đổi" overwrites it in place so the new service survives process death. */
    private var categoryId: String?
        get() = savedStateHandle[ARG_CATEGORY_ID]
        set(value) {
            savedStateHandle[ARG_CATEGORY_ID] = value
        }

    private val _category = MutableStateFlow<UiState<CategoryChoice>>(UiState.Loading)
    val category: StateFlow<UiState<CategoryChoice>> = _category.asStateFlow()

    private val _draft = MutableStateFlow(restoreDraft())
    val draft: StateFlow<RequestDraft> = _draft.asStateFlow()

    /** "Now" the CS-09 slots were last checked against; moved forward on every refresh. */
    private val clock = MutableStateFlow(now())

    val schedule: StateFlow<ScheduleUi> = combine(_draft, clock, ::scheduleUi)
        .stateIn(viewModelScope, SharingStarted.Eagerly, scheduleUi(_draft.value, clock.value))

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
    private val uploadJobs = mutableMapOf<Long, Job>()

    /** Images taken with the camera: their temporary file goes away with them. */
    private val capturedUris = mutableSetOf<Uri>()

    /** Set by the first "Tiếp tục" on CS-08 / CS-09; from then on every edit re-checks that step. */
    private var describeChecked = false
    private var addressTimeChecked = false

    private var addressLoaded = false
    private var addressJob: Job? = null

    /**
     * Id of the last address load, run again by [retryAddress]: the saved id being restored, or the one returned by
     * CS-12 / CS-13. Null means the default address.
     */
    private var lastRequestedAddressId: String? = savedStateHandle[KEY_ADDRESS_ID]

    /** A restored slot that had already passed; reported by the next [refreshSchedule]. */
    private var slotDroppedOnRestore = false

    init {
        // Files of a draft lost with the process are of no use now, except a capture whose result is still coming.
        cameraFiles.deleteAllExcept(savedStateHandle.get<Uri>(KEY_PENDING_CAPTURE))
        loadCategory()
        // A slot restored after process death may have passed in the meantime.
        val restored = _draft.value
        val fresh = freshSchedule(restored, clock.value)
        if (fresh != restored) {
            slotDroppedOnRestore = droppedSlot(restored, fresh)
            updateSchedule(fresh)
        }
    }

    /** Title, description and schedule as saved before process death; the address is restored by CS-09. */
    private fun restoreDraft() = RequestDraft(
        title = savedStateHandle.get<String>(KEY_TITLE).orEmpty(),
        description = savedStateHandle.get<String>(KEY_DESCRIPTION).orEmpty(),
        scheduleType = savedStateHandle.get<String>(KEY_SCHEDULE_TYPE)
            ?.let { name -> ScheduleType.entries.firstOrNull { it.name == name } }
            ?: ScheduleType.NOW,
        scheduledDate = savedStateHandle.get<String>(KEY_SCHEDULED_DATE)?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
        scheduledHour = savedStateHandle.get<Int>(KEY_SCHEDULED_HOUR),
    )

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

    /** Whether leaving CS-08 would throw something away ("Bỏ yêu cầu đang soạn?"). */
    val hasDescribeContent: Boolean
        get() = _draft.value.let { it.title.isNotBlank() || it.description.isNotBlank() || it.images.isNotEmpty() }

    fun setTitle(title: String) {
        if (title == _draft.value.title) return
        savedStateHandle[KEY_TITLE] = title
        _draft.update { it.copy(title = title) }
        recheckDescribe()
    }

    fun setDescription(description: String) {
        if (description == _draft.value.description) return
        savedStateHandle[KEY_DESCRIPTION] = description
        _draft.update { it.copy(description = description) }
        recheckDescribe()
    }

    /** Free slots; a failed image keeps its slot until it is removed. */
    val freeImageSlots: Int get() = RequestDraft.MAX_IMAGES - _draft.value.images.size

    /** Adds up to the remaining slots and starts uploading each one right away. */
    fun addImages(uris: List<Uri>) {
        val added = uris.take(freeImageSlots).map { DraftImage(nextImageId.incrementAndGet(), it, DraftImage.Status.UPLOADING) }
        if (added.isEmpty()) return
        _draft.update { it.copy(images = it.images + added) }
        added.forEach { upload(it) }
        recheckDescribe()
    }

    /** A new temporary file for the camera. Kept in SavedStateHandle: the app may be killed while the camera is open. */
    fun startCapture(): Uri = cameraFiles.create().also { savedStateHandle[KEY_PENDING_CAPTURE] = it }

    /** Result of TakePicture for the uri given by [startCapture]; a cancelled or empty capture is deleted. */
    fun onCaptureResult(success: Boolean) {
        val uri = savedStateHandle.remove<Uri>(KEY_PENDING_CAPTURE) ?: return
        if (!success || !cameraFiles.hasContent(uri) || freeImageSlots <= 0) {
            cameraFiles.delete(uri)
            return
        }
        capturedUris += uri
        addImages(listOf(uri))
    }

    fun retryImage(localId: Long) {
        val image = _draft.value.images.firstOrNull { it.localId == localId } ?: return
        if (image.status != DraftImage.Status.FAILED) return
        val retrying = image.copy(status = DraftImage.Status.UPLOADING, errorMessage = null)
        replaceImage(retrying)
        upload(retrying)
        recheckDescribe()
    }

    /** Cancels its upload if still running and deletes its file if it was taken with the camera. */
    fun removeImage(localId: Long) {
        val image = _draft.value.images.firstOrNull { it.localId == localId } ?: return
        uploadJobs.remove(localId)?.cancel()
        _draft.update { draft -> draft.copy(images = draft.images.filter { it.localId != localId }) }
        if (capturedUris.remove(image.uri)) cameraFiles.delete(image.uri)
        recheckDescribe()
    }

    private fun upload(image: DraftImage) {
        uploadJobs[image.localId] = viewModelScope.launch {
            val updated = when (val result = fileRepository.uploadPostImage(image.uri)) {
                is ApiResult.Success -> image.copy(status = DraftImage.Status.UPLOADED, url = result.data.url)
                is ApiResult.Failure -> image.copy(status = DraftImage.Status.FAILED, errorMessage = result.message)
            }
            uploadJobs.remove(image.localId)
            replaceImage(updated)
            recheckDescribe()
        }
    }

    /** No-op when the image was removed while uploading. */
    private fun replaceImage(image: DraftImage) = _draft.update { draft ->
        draft.copy(images = draft.images.map { if (it.localId == image.localId) image else it })
    }

    /** "Tiếp tục" on CS-08. Errors are shown from this first tap on, and then follow every edit. */
    fun validateDescribe(): Boolean {
        describeChecked = true
        return setErrors(describeErrors(_draft.value))
    }

    private fun recheckDescribe() {
        if (describeChecked) setErrors(describeErrors(_draft.value))
    }

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
        if (draft.images.any { it.status == DraftImage.Status.UPLOADING }) add(FieldError.IMAGES_UPLOADING)
        if (draft.images.any { it.status == DraftImage.Status.FAILED }) add(FieldError.IMAGES_FAILED)
    }

    // endregion

    // region CS-09 Địa chỉ & thời gian

    /**
     * The only entry point of CS-09 (every time its view is created): restores the saved address id after process
     * death, otherwise picks the default address. Does nothing once an address is loaded or while a load runs.
     */
    fun loadAddressIfNeeded() {
        if (addressLoaded || addressJob?.isActive == true) return
        loadAddress(lastRequestedAddressId)
    }

    /** "Thử lại" on the address card: runs the failed load again. */
    fun retryAddress() = loadAddress(lastRequestedAddressId)

    /** Address returned by CS-13 (select mode) or CS-12 (new address); reloaded even when it is the same id. */
    fun selectAddress(id: String) = loadAddress(id)

    /** [id] null = the default address. A newer load cancels the running one so the last request always wins. */
    private fun loadAddress(id: String?) {
        lastRequestedAddressId = id
        addressJob?.cancel()
        _address.value = UiState.Loading
        recheckAddressTime()
        addressJob = viewModelScope.launch {
            when (val result = fetchAddress(id)) {
                is ApiResult.Failure -> {
                    _address.value = UiState.Error(result.message)
                    recheckAddressTime()
                }
                is ApiResult.Success -> {
                    addressLoaded = true
                    setAddress(result.data)
                }
            }
        }
    }

    /** An id that is no longer in the book (deleted meanwhile) falls back to the default address. */
    private suspend fun fetchAddress(id: String?): ApiResult<Address?> {
        if (id != null) {
            val result = addressRepository.getAddress(id)
            if (result !is ApiResult.Failure || result.code != AddressRepository.NOT_FOUND) return result
            lastRequestedAddressId = null
        }
        return addressRepository.getAddresses().map { list -> list.firstOrNull { it.isDefault } ?: list.firstOrNull() }
    }

    private fun setAddress(address: Address?) {
        if (address != null) savedStateHandle[KEY_ADDRESS_ID] = address.id else savedStateHandle.remove<String>(KEY_ADDRESS_ID)
        _draft.update { it.copy(address = address) }
        _address.value = UiState.Content(address)
        recheckAddressTime()
    }

    fun setScheduleNow() = updateSchedule(_draft.value.copy(scheduleType = ScheduleType.NOW))

    /**
     * "Hẹn lịch": comes back to the day and slot picked before if they can still be booked; the first time (or when
     * that day is over) picks the first day with a bookable slot, with no slot yet.
     */
    fun chooseScheduled() {
        val now = now()
        clock.value = now
        val draft = freshSchedule(_draft.value, now)
        val date = draft.scheduledDate ?: ScheduleSlots.firstAvailableDay(now)
        updateSchedule(draft.copy(scheduleType = ScheduleType.SCHEDULED, scheduledDate = date))
    }

    /** Another day: keeps the slot only if it is still bookable on that day. */
    fun setScheduledDate(date: LocalDate) {
        val draft = _draft.value
        val hour = draft.scheduledHour?.takeIf { ScheduleSlots.isAvailable(date, it, now()) }
        updateSchedule(draft.copy(scheduleType = ScheduleType.SCHEDULED, scheduledDate = date, scheduledHour = hour))
    }

    fun setScheduledHour(hour: Int) =
        updateSchedule(_draft.value.copy(scheduleType = ScheduleType.SCHEDULED, scheduledHour = hour))

    /**
     * onResume of CS-09 and "Tiếp tục": checks the slots against the current time again. Returns true when the slot
     * the customer had picked was dropped (now or on restore), so the screen tells them once.
     */
    fun refreshSchedule(): Boolean {
        val now = now()
        clock.value = now
        val before = _draft.value
        val after = freshSchedule(before, now)
        if (after != before) updateSchedule(after)
        val dropped = droppedSlot(before, after) || slotDroppedOnRestore
        slotDroppedOnRestore = false
        return dropped
    }

    /** Drops a slot that can no longer be booked and moves off a day that has no bookable slot left. */
    private fun freshSchedule(draft: RequestDraft, now: ZonedDateTime): RequestDraft {
        val date = draft.scheduledDate ?: return draft
        val day = if (ScheduleSlots.hasAvailableSlot(date, now)) date else ScheduleSlots.firstAvailableDay(now)
        val hour = draft.scheduledHour?.takeIf { day == date && ScheduleSlots.isAvailable(date, it, now) }
        return draft.copy(scheduledDate = day, scheduledHour = hour)
    }

    /** Only a slot dropped while "Hẹn lịch" is chosen is worth telling the customer about. */
    private fun droppedSlot(before: RequestDraft, after: RequestDraft) =
        before.scheduleType == ScheduleType.SCHEDULED && before.scheduledHour != null && after.scheduledHour == null

    private fun updateSchedule(draft: RequestDraft) {
        savedStateHandle[KEY_SCHEDULE_TYPE] = draft.scheduleType.name
        savedStateHandle[KEY_SCHEDULED_DATE] = draft.scheduledDate?.toString()
        savedStateHandle[KEY_SCHEDULED_HOUR] = draft.scheduledHour
        _draft.value = draft
        recheckAddressTime()
    }

    private fun scheduleUi(draft: RequestDraft, now: ZonedDateTime): ScheduleUi {
        val date = draft.scheduledDate
        val days = ScheduleSlots.days(now).map { day ->
            ScheduleUi.DayOption(day, enabled = ScheduleSlots.hasAvailableSlot(day, now), selected = day == date)
        }
        val slots = ScheduleSlots.START_HOURS.map { hour ->
            ScheduleUi.SlotOption(
                startHour = hour,
                enabled = date != null && ScheduleSlots.isAvailable(date, hour, now),
                selected = date != null && hour == draft.scheduledHour,
            )
        }
        return ScheduleUi(days, slots, selectedDayIndex = days.indexOfFirst { it.selected })
    }

    /** "Tiếp tục" on CS-09. Errors are shown from this first tap on, and then follow every change. */
    fun validateAddressTime(): Boolean {
        addressTimeChecked = true
        return setErrors(addressTimeErrors(_draft.value))
    }

    private fun recheckAddressTime() {
        if (addressTimeChecked) setErrors(addressTimeErrors(_draft.value))
    }

    /** A slot that became too close (or past) while the screen was open counts as missing. */
    private fun addressTimeErrors(draft: RequestDraft) = buildSet {
        addAll(addressErrors(draft.address, _address.value))
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

    /** The customer left the flow or posted: drop every camera file, including one still waiting for its result. */
    override fun onCleared() {
        cameraFiles.deleteAllExcept(keep = null)
    }

    companion object {
        const val ARG_CATEGORY_ID = "categoryId"

        /**
         * CS-09 address errors. While the card is loading or shows an error the draft may still hold the previous
         * address, which the customer cannot see: that blocks "Tiếp tục" as [FieldError.ADDRESS_NOT_READY].
         */
        fun addressErrors(draftAddress: Address?, card: UiState<Address?>): Set<FieldError> = when {
            card !is UiState.Content -> setOf(FieldError.ADDRESS_NOT_READY)
            draftAddress == null || card.data == null -> setOf(FieldError.ADDRESS_REQUIRED)
            else -> emptySet()
        }
        private const val KEY_TITLE = "draftTitle"
        private const val KEY_DESCRIPTION = "draftDescription"
        private const val KEY_PENDING_CAPTURE = "pendingCaptureUri"
        private const val KEY_ADDRESS_ID = "draftAddressId"
        private const val KEY_SCHEDULE_TYPE = "draftScheduleType"
        private const val KEY_SCHEDULED_DATE = "draftScheduledDate"
        private const val KEY_SCHEDULED_HOUR = "draftScheduledHour"
    }
}
