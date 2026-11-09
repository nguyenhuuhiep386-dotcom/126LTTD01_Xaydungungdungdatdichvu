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
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

/**
 * Shared by CS-08 → CS-11 through `navGraphViewModels(R.id.create_request_graph)`: the draft lives as long as the
 * nested graph is on the back stack and is dropped when the customer leaves the flow or posts.
 * The graph argument `categoryId` is the level-2 service picked on CS-07.
 *
 * The service, title, description, address id, schedule and CS-10 choices live in [SavedStateHandle] so they survive
 * process death; the picked images do not (the photo picker's read grant ends with the process), except a camera
 * capture still waiting for its result.
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
        BUDGET_MIN_MISSING, BUDGET_MAX_MISSING, BUDGET_RANGE,
    }

    sealed interface Event {
        data class Posted(val requestId: String) : Event

        /** #36 answered with an error, see [PostFailure.of]. */
        data class Failed(val failure: PostFailure) : Event

        /**
         * The draft is no longer valid on [step]; checked before calling #36, and that step's errors are now shown.
         * [slotExpired]: the chosen slot is now too close, which CS-09 itself tells when it resumes.
         */
        data class Incomplete(val step: Step, val slotExpired: Boolean = false) : Event

        /** The service or the address is still loading (or failed) after process death: nothing to send yet. */
        data object NotReady : Event
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

    /**
     * CS-11: the draft had images before the process died and they could not be restored (the photo picker's read
     * grant ends with the process). Cleared as soon as the customer edits the images on CS-08.
     */
    private val _imagesLost = MutableStateFlow((savedStateHandle.get<Int>(KEY_IMAGE_COUNT) ?: 0) > 0)
    val imagesLost: StateFlow<Boolean> = _imagesLost.asStateFlow()

    /** Snackbar text CS-11 leaves for the step it sends the customer back to; taken once by that step. */
    private var pendingNotice: String? = null

    private val nextImageId = AtomicLong()
    private val uploadJobs = mutableMapOf<Long, Job>()

    /** Images taken with the camera: their temporary file goes away with them. */
    private val capturedUris = mutableSetOf<Uri>()

    /** Set by the first "Tiếp tục" on CS-08 / CS-09 ("Xem lại" on CS-10); from then on every edit re-checks that step. */
    private var describeChecked = false
    private var addressTimeChecked = false
    private var budgetChecked = false

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

    /** Everything typed but the images, as saved before process death; the address is restored by CS-09. */
    private fun restoreDraft() = RequestDraft(
        title = savedStateHandle.get<String>(KEY_TITLE).orEmpty(),
        description = savedStateHandle.get<String>(KEY_DESCRIPTION).orEmpty(),
        scheduleType = savedStateHandle.get<String>(KEY_SCHEDULE_TYPE)
            ?.let { name -> ScheduleType.entries.firstOrNull { it.name == name } }
            ?: ScheduleType.NOW,
        scheduledDate = savedStateHandle.get<String>(KEY_SCHEDULED_DATE)?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
        scheduledHour = savedStateHandle.get<Int>(KEY_SCHEDULED_HOUR),
        quoteByPartner = savedStateHandle.get<Boolean>(KEY_QUOTE_BY_PARTNER) ?: true,
        budgetMin = savedStateHandle.get<Long>(KEY_BUDGET_MIN),
        budgetMax = savedStateHandle.get<Long>(KEY_BUDGET_MAX),
        requireExperience = savedStateHandle.get<Boolean>(KEY_REQUIRE_EXPERIENCE) ?: false,
        requireCertificate = savedStateHandle.get<Boolean>(KEY_REQUIRE_CERTIFICATE) ?: false,
        requireHighRating = savedStateHandle.get<Boolean>(KEY_REQUIRE_HIGH_RATING) ?: false,
        searchRadiusKm = savedStateHandle.get<Int>(KEY_SEARCH_RADIUS)
            ?.takeIf { it in RequestDraft.RADIUS_OPTIONS_KM }
            ?: RequestDraft.DEFAULT_RADIUS_KM,
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
        onImagesEdited()
        added.forEach { upload(it) }
        recheckDescribe()
    }

    /** Remembers how many images were picked, for [imagesLost] after process death. */
    private fun onImagesEdited() {
        savedStateHandle[KEY_IMAGE_COUNT] = _draft.value.images.size
        _imagesLost.value = false
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
        onImagesEdited()
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

    fun setQuoteByPartner(quoteByPartner: Boolean) = updateBudget(_draft.value.copy(quoteByPartner = quoteByPartner))

    /** Parsed "Từ" / "Đến" (null when the box is empty). */
    fun setBudgetMin(amount: Long?) = updateBudget(_draft.value.copy(budgetMin = amount))

    fun setBudgetMax(amount: Long?) = updateBudget(_draft.value.copy(budgetMax = amount))

    fun setRequireExperience(required: Boolean) = updateBudget(_draft.value.copy(requireExperience = required))

    /** Ignored by [certificateFor] while the service requires a certificate, but kept for a service that does not. */
    fun setRequireCertificate(required: Boolean) = updateBudget(_draft.value.copy(requireCertificate = required))

    fun setRequireHighRating(required: Boolean) = updateBudget(_draft.value.copy(requireHighRating = required))

    fun setSearchRadius(km: Int) {
        if (km in RequestDraft.RADIUS_OPTIONS_KM) updateBudget(_draft.value.copy(searchRadiusKm = km))
    }

    private fun updateBudget(draft: RequestDraft) {
        if (draft == _draft.value) return
        savedStateHandle[KEY_QUOTE_BY_PARTNER] = draft.quoteByPartner
        savedStateHandle[KEY_BUDGET_MIN] = draft.budgetMin
        savedStateHandle[KEY_BUDGET_MAX] = draft.budgetMax
        savedStateHandle[KEY_REQUIRE_EXPERIENCE] = draft.requireExperience
        savedStateHandle[KEY_REQUIRE_CERTIFICATE] = draft.requireCertificate
        savedStateHandle[KEY_REQUIRE_HIGH_RATING] = draft.requireHighRating
        savedStateHandle[KEY_SEARCH_RADIUS] = draft.searchRadiusKm
        _draft.value = draft
        recheckBudget()
    }

    /** "Xem lại" on CS-10. Errors are shown from this first tap on, and then follow every change. */
    fun validateBudget(): Boolean {
        budgetChecked = true
        return setErrors(budgetErrors(_draft.value))
    }

    /** Also called when CS-10 is shown again: CS-09 shares [fieldErrors] and may have replaced them. */
    fun recheckBudget() {
        if (budgetChecked) setErrors(budgetErrors(_draft.value))
    }

    /** "Có chứng chỉ nghề" for the service in use; locked (and sent as true) when the service requires it. */
    fun certificateFor(draft: RequestDraft, category: UiState<CategoryChoice>): CertificateState = certificateState(
        categoryRequires = (category as? UiState.Content)?.data?.service?.requiresCertificate == true,
        userChoice = draft.requireCertificate,
    )

    // endregion

    // region CS-11 Xem lại & đăng

    /**
     * CS-11 shows the service and the address from the same loads as CS-08 / CS-09; after process death on CS-11 the
     * address is loaded from here, as CS-09 was never opened.
     */
    fun loadReviewData() = loadAddressIfNeeded()

    /** "Đăng yêu cầu". Ignored while a post is running and once it succeeded. */
    fun post() {
        if (_posting.value) return
        val draft = _draft.value
        val service = (_category.value as? UiState.Content)?.data?.service
        if (service == null || _address.value !is UiState.Content) {
            sendEvent(Event.NotReady)
            return
        }
        // Re-check every step: a slot may have expired while the customer was reviewing.
        val addressTime = addressTimeErrors(draft)
        val firstInvalid = when {
            describeErrors(draft).isNotEmpty() -> Step.DESCRIBE
            addressTime.isNotEmpty() -> Step.ADDRESS_TIME
            budgetErrors(draft).isNotEmpty() -> Step.BUDGET
            else -> null
        }
        val address = draft.address
        if (firstInvalid != null || address == null) {
            val step = firstInvalid ?: Step.ADDRESS_TIME
            markChecked(step)
            val slotExpired = step == Step.ADDRESS_TIME && FieldError.SLOT_REQUIRED in addressTime && draft.scheduledHour != null
            sendEvent(Event.Incomplete(step, slotExpired))
            return
        }
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
            budgetMin = draft.budgetMinToSend,
            budgetMax = draft.budgetMaxToSend,
            requireExperienceYears = if (draft.requireExperience) RequestDraft.EXPERIENCE_YEARS else null,
            requireCertificate = certificateFor(draft, _category.value).checked,
            requireMinRating = if (draft.requireHighRating) RequestDraft.MIN_RATING else null,
            searchRadiusKm = draft.searchRadiusKm,
        )
        val key = idempotencyKeyFor(request)
        _posting.value = true
        viewModelScope.launch {
            when (val result = createRequestRepository.createRequest(request, key)) {
                // posting stays on: the flow is left right away and nothing may be sent twice.
                is ApiResult.Success -> _events.send(Event.Posted(result.data.id))
                is ApiResult.Failure -> {
                    _posting.value = false
                    val failure = PostFailure.of(result.code, result.field, result.message)
                    if (failure is PostFailure.BackToStep) markChecked(failure.step)
                    _events.send(Event.Failed(failure))
                }
            }
        }
    }

    /**
     * The key of the last post when [request] is exactly what was sent then (a retry after a network error), a new
     * one once the draft changed. Kept in SavedStateHandle; the fingerprint is the body's text, stable across process
     * death (an enum's hashCode is not).
     */
    private fun idempotencyKeyFor(request: CreateServiceRequestRequest): String {
        val fingerprint = request.toString().hashCode()
        val saved = savedStateHandle.get<String>(KEY_IDEMPOTENCY_KEY)
        if (saved != null && savedStateHandle.get<Int>(KEY_IDEMPOTENCY_FINGERPRINT) == fingerprint) return saved
        val key = UUID.randomUUID().toString()
        savedStateHandle[KEY_IDEMPOTENCY_KEY] = key
        savedStateHandle[KEY_IDEMPOTENCY_FINGERPRINT] = fingerprint
        return key
    }

    /** Shows [step]'s errors right away when CS-11 sends the customer back to it. */
    private fun markChecked(step: Step) {
        when (step) {
            Step.DESCRIBE -> validateDescribe()
            Step.ADDRESS_TIME -> validateAddressTime()
            Step.BUDGET -> validateBudget()
        }
    }

    fun leaveNotice(text: String) {
        pendingNotice = text
    }

    /** The snackbar text CS-11 left for this step, once. */
    fun takeNotice(): String? = pendingNotice.also { pendingNotice = null }

    private fun sendEvent(event: Event) {
        viewModelScope.launch { _events.send(event) }
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

        /** CS-10 budget errors: none with "Để người làm báo giá", whatever the boxes still hold. */
        fun budgetErrors(draft: RequestDraft): Set<FieldError> = buildSet {
            if (draft.quoteByPartner) return@buildSet
            val min = draft.budgetMin
            val max = draft.budgetMax
            if (min == null) add(FieldError.BUDGET_MIN_MISSING)
            if (max == null) add(FieldError.BUDGET_MAX_MISSING)
            if (min != null && max != null && (min < 0 || min > max)) add(FieldError.BUDGET_RANGE)
        }

        private const val KEY_TITLE = "draftTitle"
        private const val KEY_DESCRIPTION = "draftDescription"
        private const val KEY_PENDING_CAPTURE = "pendingCaptureUri"
        private const val KEY_ADDRESS_ID = "draftAddressId"
        private const val KEY_SCHEDULE_TYPE = "draftScheduleType"
        private const val KEY_SCHEDULED_DATE = "draftScheduledDate"
        private const val KEY_SCHEDULED_HOUR = "draftScheduledHour"
        private const val KEY_QUOTE_BY_PARTNER = "draftQuoteByPartner"
        private const val KEY_BUDGET_MIN = "draftBudgetMin"
        private const val KEY_BUDGET_MAX = "draftBudgetMax"
        private const val KEY_REQUIRE_EXPERIENCE = "draftRequireExperience"
        private const val KEY_REQUIRE_CERTIFICATE = "draftRequireCertificate"
        private const val KEY_REQUIRE_HIGH_RATING = "draftRequireHighRating"
        private const val KEY_SEARCH_RADIUS = "draftSearchRadiusKm"
        private const val KEY_IMAGE_COUNT = "draftImageCount"
        private const val KEY_IDEMPOTENCY_KEY = "postIdempotencyKey"
        private const val KEY_IDEMPOTENCY_FINGERPRINT = "postIdempotencyFingerprint"
    }
}
