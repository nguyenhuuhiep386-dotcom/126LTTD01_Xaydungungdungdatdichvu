package vn.servio.customer.ui.wallet

import vn.servio.customer.data.model.Debt
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Rules of CS-30 that do not need Android: what the screen shows and when "Gửi khai báo" is enabled. */
object WalletForm {

    /** Length rule of bankTransferReference in #156 (the server checks the same, trimmed). */
    const val REFERENCE_MIN = 3
    const val REFERENCE_MAX = 100

    private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM")

    enum class Status {
        /** At least one fee is not declared yet: the declaration form is shown. */
        OWES,

        /** Every fee is declared and waits for the admin. */
        WAITING,

        /** #155 is empty. */
        CLEAR,
    }

    enum class EvidenceStatus { UPLOADING, UPLOADED, FAILED }

    enum class Failure { NOTHING_TO_PAY, VALIDATION, OTHER }

    fun undeclared(debts: List<Debt>): List<Debt> = debts.filter { !it.isDeclared }

    fun status(debts: List<Debt>): Status = when {
        debts.isEmpty() -> Status.CLEAR
        undeclared(debts).isNotEmpty() -> Status.OWES
        else -> Status.WAITING
    }

    fun referenceValid(reference: String): Boolean = reference.trim().length in REFERENCE_MIN..REFERENCE_MAX

    /** The error under the field: only once something was typed, so an empty form is not red. */
    fun referenceError(reference: String): Boolean = reference.isNotBlank() && !referenceValid(reference)

    fun canSubmit(debts: List<Debt>, reference: String, evidence: EvidenceStatus?, sending: Boolean): Boolean =
        !sending && undeclared(debts).isNotEmpty() && referenceValid(reference) && evidence == EvidenceStatus.UPLOADED

    fun failure(code: String): Failure = when (code) {
        "NOTHING_TO_PAY" -> Failure.NOTHING_TO_PAY
        "VALIDATION_ERROR" -> Failure.VALIDATION
        else -> Failure.OTHER
    }

    /** A 400 about the receipt (field evidenceUrl) is shown on the photo; anything else under the reference field. */
    fun isEvidenceField(field: String?): Boolean = field?.startsWith("evidence", ignoreCase = true) == true

    /** "18/11" of a server time in [zone]; null when unreadable. */
    fun day(iso: String, zone: ZoneId = ZoneId.systemDefault()): String? =
        runCatching { DAY.format(OffsetDateTime.parse(iso).atZoneSameInstant(zone)) }.getOrNull()
}
