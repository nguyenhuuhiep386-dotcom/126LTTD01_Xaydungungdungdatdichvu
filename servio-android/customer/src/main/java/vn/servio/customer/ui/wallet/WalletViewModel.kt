package vn.servio.customer.ui.wallet

import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import vn.servio.core.network.ApiResult
import vn.servio.customer.R
import vn.servio.customer.data.image.CaptureFiles
import vn.servio.customer.data.model.Debt
import vn.servio.customer.data.model.DebtPaymentBody
import vn.servio.customer.data.model.Wallet
import vn.servio.customer.data.repository.FileRepository
import vn.servio.customer.data.repository.WalletRepository
import vn.servio.customer.ui.wallet.WalletForm.EvidenceStatus
import javax.inject.Inject

/**
 * CS-30 Phí huỷ và giao dịch: #87 (total, pending part, limit) and #155 (the fees), then #156 for every fee not
 * declared yet, with the bank reference and one receipt photo (POST /files purpose DISPUTE_EVIDENCE).
 *
 * The reference and the photo (local uri, uploaded url, camera flag) are kept in [SavedStateHandle]; the ViewModel
 * itself survives a rotation, so nothing is uploaded again. The preview always uses the local uri: the uploaded file
 * is private.
 */
@HiltViewModel
class WalletViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val walletRepository: WalletRepository,
    private val fileRepository: FileRepository,
    private val captureFiles: CaptureFiles,
) : ViewModel() {

    /** The receipt: [uri] is the local image (as text), [url] the uploaded file once [status] is UPLOADED. */
    data class Evidence(
        val uri: String,
        val status: EvidenceStatus,
        val url: String? = null,
        val error: String? = null,
        val captured: Boolean = false,
    )

    /**
     * [wallet] and [debts] stay null until the first load answered; [error] is the full-screen error of that load.
     * [referenceServerError] is a 400 of #156 shown under the field until the text changes.
     */
    data class State(
        val loading: Boolean = true,
        val error: String? = null,
        val wallet: Wallet? = null,
        val debts: List<Debt>? = null,
        val reference: String = "",
        val referenceServerError: String? = null,
        val evidence: Evidence? = null,
        val sending: Boolean = false,
    ) {
        val loaded: Boolean get() = wallet != null && debts != null
        val status: WalletForm.Status get() = WalletForm.status(debts.orEmpty())
        val undeclared: List<Debt> get() = WalletForm.undeclared(debts.orEmpty())
        val referenceTooShort: Boolean get() = WalletForm.referenceError(reference)
        val canSubmit: Boolean get() = loaded && WalletForm.canSubmit(debts.orEmpty(), reference, evidence?.status, sending)
    }

    sealed interface Event {
        /** #156 accepted: "Đã gửi khai báo X đ, chờ quản trị viên xác nhận" with the server's amount. */
        data class Sent(val amount: Long) : Event

        /** A snackbar: a text of the app ([res]) or the server's [text]. */
        data class Message(@StringRes val res: Int? = null, val text: String? = null) : Event
    }

    private val _state = MutableStateFlow(State(reference = savedStateHandle[KEY_REFERENCE] ?: "", evidence = restoredEvidence()))
    val state: StateFlow<State> = _state.asStateFlow()

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = _events.receiveAsFlow()

    private var loadJob: Job? = null
    private var uploadJob: Job? = null

    init {
        load()
    }

    /** #87 and #155 together. A failed reload over data already shown keeps it and says why in a snackbar. */
    fun load() {
        loadJob?.cancel()
        _state.update { it.copy(loading = true, error = if (it.loaded) null else it.error) }
        loadJob = viewModelScope.launch {
            val walletCall = async { walletRepository.getWallet() }
            val debts = walletRepository.getDebts()
            val wallet = walletCall.await()
            if (wallet is ApiResult.Success && debts is ApiResult.Success) {
                _state.update { it.copy(loading = false, error = null, wallet = wallet.data, debts = debts.data) }
                return@launch
            }
            val message = (wallet as? ApiResult.Failure ?: debts as ApiResult.Failure).message
            val shown = _state.value.loaded
            _state.update { it.copy(loading = false, error = if (shown) null else message) }
            if (shown) _events.send(Event.Message(text = message))
        }
    }

    fun setReference(text: String) {
        if (text == _state.value.reference) return
        savedStateHandle[KEY_REFERENCE] = text
        _state.update { it.copy(reference = text, referenceServerError = null) }
    }

    /** A picked (or taken) receipt replaces the previous one and is uploaded right away. */
    fun pickEvidence(uri: Uri, captured: Boolean = false) {
        discardEvidence()
        val evidence = Evidence(uri.toString(), EvidenceStatus.UPLOADING, captured = captured)
        saveEvidence(evidence)
        upload(evidence, uri)
    }

    fun retryEvidence() {
        val evidence = _state.value.evidence ?: return
        if (evidence.status != EvidenceStatus.FAILED) return
        val retrying = evidence.copy(status = EvidenceStatus.UPLOADING, url = null, error = null)
        saveEvidence(retrying)
        upload(retrying, Uri.parse(retrying.uri))
    }

    fun removeEvidence() = discardEvidence()

    /** A new temporary file for the camera, kept in [SavedStateHandle] while the camera app is open. */
    fun startCapture(): Uri = captureFiles.create().also { savedStateHandle[KEY_PENDING_CAPTURE] = it }

    /** Result of TakePicture for the uri of [startCapture]: an empty or cancelled capture is deleted. */
    fun onCaptureResult(success: Boolean) {
        val uri = savedStateHandle.remove<Uri>(KEY_PENDING_CAPTURE) ?: return
        if (!success || !captureFiles.hasContent(uri)) {
            captureFiles.delete(uri)
            return
        }
        pickEvidence(uri, captured = true)
    }

    /** #156 with every fee that has no pending declaration yet. */
    fun submit() {
        val current = _state.value
        if (!current.canSubmit) return
        val url = current.evidence?.url ?: return
        _state.update { it.copy(sending = true, referenceServerError = null) }
        viewModelScope.launch {
            val body = DebtPaymentBody(current.undeclared.map { it.id }, current.reference.trim(), url)
            when (val result = walletRepository.declarePayment(body)) {
                is ApiResult.Success -> {
                    discardEvidence()
                    setReference("")
                    _state.update { it.copy(sending = false) }
                    _events.send(Event.Sent(result.data.amount))
                    load()
                }
                is ApiResult.Failure -> {
                    _state.update { it.copy(sending = false) }
                    when (WalletForm.failure(result.code)) {
                        WalletForm.Failure.NOTHING_TO_PAY -> {
                            _events.send(Event.Message(res = R.string.wallet_nothing_to_pay))
                            load()
                        }
                        WalletForm.Failure.VALIDATION -> if (WalletForm.isEvidenceField(result.field)) {
                            _state.value.evidence?.let { saveEvidence(it.copy(status = EvidenceStatus.FAILED, url = null, error = result.message)) }
                        } else {
                            _state.update { it.copy(referenceServerError = result.message) }
                        }
                        WalletForm.Failure.OTHER -> _events.send(Event.Message(text = result.message))
                    }
                }
            }
        }
    }

    private fun upload(evidence: Evidence, uri: Uri) {
        uploadJob?.cancel()
        uploadJob = viewModelScope.launch {
            val updated = when (val result = fileRepository.uploadEvidence(uri)) {
                is ApiResult.Success -> evidence.copy(status = EvidenceStatus.UPLOADED, url = result.data.url)
                is ApiResult.Failure -> evidence.copy(status = EvidenceStatus.FAILED, error = result.message)
            }
            // A newer photo may have replaced this one meanwhile.
            if (_state.value.evidence?.uri == evidence.uri) saveEvidence(updated)
        }
    }

    /** Stops its upload and deletes its file if it was taken with the camera. */
    private fun discardEvidence() {
        uploadJob?.cancel()
        uploadJob = null
        val evidence = _state.value.evidence ?: return
        if (evidence.captured) captureFiles.delete(Uri.parse(evidence.uri))
        saveEvidence(null)
    }

    private fun saveEvidence(evidence: Evidence?) {
        savedStateHandle[KEY_EVIDENCE_URI] = evidence?.uri
        savedStateHandle[KEY_EVIDENCE_URL] = evidence?.url
        savedStateHandle[KEY_EVIDENCE_CAPTURED] = evidence?.captured
        _state.update { it.copy(evidence = evidence) }
    }

    /** After process death: an uploaded photo is still usable (its url), one that was uploading has to be retried. */
    private fun restoredEvidence(): Evidence? {
        val uri = savedStateHandle.get<String>(KEY_EVIDENCE_URI) ?: return null
        val url = savedStateHandle.get<String>(KEY_EVIDENCE_URL)
        val captured = savedStateHandle.get<Boolean>(KEY_EVIDENCE_CAPTURED) ?: false
        return if (url != null) {
            Evidence(uri, EvidenceStatus.UPLOADED, url = url, captured = captured)
        } else {
            Evidence(uri, EvidenceStatus.FAILED, captured = captured)
        }
    }

    override fun onCleared() {
        // Leaving the screen drops the draft: a camera file of an unsent receipt is deleted.
        _state.value.evidence?.takeIf { it.captured }?.let { captureFiles.delete(Uri.parse(it.uri)) }
        savedStateHandle.get<Uri>(KEY_PENDING_CAPTURE)?.let(captureFiles::delete)
    }

    companion object {
        private const val KEY_REFERENCE = "reference"
        private const val KEY_EVIDENCE_URI = "evidenceUri"
        private const val KEY_EVIDENCE_URL = "evidenceUrl"
        private const val KEY_EVIDENCE_CAPTURED = "evidenceCaptured"
        private const val KEY_PENDING_CAPTURE = "pendingCapture"
    }
}
