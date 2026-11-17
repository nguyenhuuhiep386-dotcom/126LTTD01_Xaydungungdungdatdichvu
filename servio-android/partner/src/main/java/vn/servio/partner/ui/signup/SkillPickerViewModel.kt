package vn.servio.partner.ui.signup

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import vn.servio.core.data.model.Category
import vn.servio.core.data.repository.CategoryRepository
import vn.servio.core.network.ApiResult
import vn.servio.core.utils.UiState
import vn.servio.partner.data.model.AddSkillRequest
import vn.servio.partner.data.model.FilePurpose
import vn.servio.partner.data.model.PartnerSkill
import vn.servio.partner.data.repository.FileRepository
import vn.servio.partner.data.repository.PartnerProfileRepository
import javax.inject.Inject

/**
 * Skill picker shared by PS-03 (signup step 2) and PS-24 (Quản lý kỹ năng): up to 5 level-2 services (#21–#23),
 * plus one optional certificate photo (POST /files CERTIFICATE) attached to the skills added in this session.
 */
@HiltViewModel
class SkillPickerViewModel @Inject constructor(
    private val profileRepository: PartnerProfileRepository,
    private val categoryRepository: CategoryRepository,
    private val fileRepository: FileRepository,
) : ViewModel() {

    data class Content(
        val groups: List<Category>,
        val existing: List<PartnerSkill>,
        val selectedIds: Set<String>,
        val expandedGroupIds: Set<String>,
        val yearsOfExperience: Int,
    ) {
        val rows: List<SkillRow> = groups.flatMap { group ->
            val expanded = group.id in expandedGroupIds
            val header = SkillRow.Group(group, expanded, group.children.count { it.id in selectedIds })
            if (!expanded) {
                listOf(header)
            } else {
                listOf(header) + group.children.map { service ->
                    SkillRow.Service(service, service.id in selectedIds, existing.firstOrNull { it.categoryId == service.id })
                }
            }
        }
    }

    data class Certificate(val localUri: Uri? = null, val url: String? = null, val uploading: Boolean = false)

    sealed interface Event {
        data object Saved : Event
        data class Message(val text: String) : Event
        data class LimitReached(val max: Int) : Event
        data class CertificateMissing(val serviceName: String) : Event
        data object NoSkill : Event
    }

    private val _state = MutableStateFlow<UiState<Content>>(UiState.Loading)
    val state: StateFlow<UiState<Content>> = _state.asStateFlow()

    private val _certificate = MutableStateFlow(Certificate())
    val certificate: StateFlow<Certificate> = _certificate.asStateFlow()

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = _events.receiveAsFlow()

    init {
        load()
    }

    fun load() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            val me = async { profileRepository.getMe() }
            val tree = async { categoryRepository.getCategoryTree() }
            val meResult = me.await()
            val treeResult = tree.await()
            _state.value = when {
                meResult is ApiResult.Failure -> UiState.Error(meResult.message)
                treeResult is ApiResult.Failure -> UiState.Error(treeResult.message)
                else -> {
                    val partner = (meResult as ApiResult.Success).data
                    val groups = (treeResult as ApiResult.Success).data.filter { it.children.isNotEmpty() }
                    val selected = partner.skills.map { it.categoryId }.toSet()
                    // Open the groups that already hold a skill, or the first group for a new partner.
                    val expanded = groups.filter { g -> g.children.any { it.id in selected } }.map { it.id }.toSet()
                        .ifEmpty { setOfNotNull(groups.firstOrNull()?.id) }
                    UiState.Content(Content(groups, partner.skills, selected, expanded, partner.yearsOfExperience))
                }
            }
        }
    }

    fun toggleGroup(groupId: String) = updateContent { content ->
        val expanded = content.expandedGroupIds.toMutableSet()
        if (!expanded.add(groupId)) expanded.remove(groupId)
        content.copy(expandedGroupIds = expanded)
    }

    fun toggleService(serviceId: String) {
        val content = (_state.value as? UiState.Content)?.data ?: return
        if (serviceId !in content.selectedIds && content.selectedIds.size >= MAX_SKILLS) {
            _events.trySend(Event.LimitReached(MAX_SKILLS))
            return
        }
        updateContent { current ->
            val selected = current.selectedIds.toMutableSet()
            if (!selected.add(serviceId)) selected.remove(serviceId)
            current.copy(selectedIds = selected)
        }
    }

    fun onCertificatePicked(uri: Uri) {
        _certificate.value = Certificate(localUri = uri, uploading = true)
        viewModelScope.launch {
            _certificate.value = when (val result = fileRepository.uploadImage(uri, FilePurpose.CERTIFICATE)) {
                is ApiResult.Success -> Certificate(localUri = uri, url = result.data.url)
                is ApiResult.Failure -> {
                    _events.send(Event.Message(result.message))
                    Certificate()
                }
            }
        }
    }

    /** Applies the selection: deletes unchecked skills (#23), then adds the new ones (#22). */
    fun save(requireAtLeastOne: Boolean) {
        val content = (_state.value as? UiState.Content)?.data ?: return
        if (_saving.value || _certificate.value.uploading) return
        if (requireAtLeastOne && content.selectedIds.isEmpty()) {
            _events.trySend(Event.NoSkill)
            return
        }
        val existingIds = content.existing.map { it.categoryId }.toSet()
        val services = content.groups.flatMap { it.children }.associateBy { it.id }
        val toAdd = content.selectedIds.filter { it !in existingIds }.mapNotNull { services[it] }
        val toDelete = content.existing.filter { it.categoryId !in content.selectedIds }
        val certificateUrl = _certificate.value.url
        toAdd.firstOrNull { it.requiresCertificate && certificateUrl == null }?.let {
            _events.trySend(Event.CertificateMissing(it.name))
            return
        }

        _saving.value = true
        viewModelScope.launch {
            for (skill in toDelete) {
                val result = profileRepository.deleteSkill(skill.id)
                if (result is ApiResult.Failure) return@launch fail(result.message)
            }
            for (service in toAdd) {
                val request = AddSkillRequest(service.id, content.yearsOfExperience, certificateUrl)
                val result = profileRepository.addSkill(request)
                if (result is ApiResult.Failure) return@launch fail(result.message)
            }
            _saving.value = false
            _events.send(Event.Saved)
        }
    }

    private suspend fun fail(message: String) {
        _saving.value = false
        // Part of the changes may already be saved: reload so the list shows the server state.
        _events.send(Event.Message(message))
        load()
    }

    private fun updateContent(transform: (Content) -> Content) {
        _state.update { state -> if (state is UiState.Content) UiState.Content(transform(state.data)) else state }
    }

    companion object {
        const val MAX_SKILLS = 5
    }
}

/** One row of the skill list: a level-1 group (expandable) or one of its level-2 services. */
sealed interface SkillRow {
    data class Group(val category: Category, val expanded: Boolean, val selectedCount: Int) : SkillRow
    data class Service(val category: Category, val checked: Boolean, val skill: PartnerSkill?) : SkillRow
}
