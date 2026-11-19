package vn.servio.customer.ui.wallet

import vn.servio.customer.data.model.BankAccount

/**
 * CS-30 "Khai báo đã chuyển khoản" card: where the customer transfers the cancellation fees (cancellationFeeBank of
 * #107). Shown only when the bank, the account number and the holder are all filled in AW-15; [note] is the transfer
 * note with the customer's phone number, null (row hidden) when it cannot be built.
 */
data class TransferCard(
    val bankName: String,
    val accountNumber: String,
    val accountHolder: String,
    val note: String?,
) {
    companion object {
        const val PHONE_PLACEHOLDER = "{phone}"

        /** Null — no card — while [bank] is unknown or one of its three fields is missing or blank. */
        fun of(bank: BankAccount?, phoneNumber: String?): TransferCard? {
            val name = bank?.bankName?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val number = bank.accountNumber?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val holder = bank.accountHolder?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return TransferCard(name, number, holder, note(bank.transferNoteTemplate, phoneNumber))
        }

        /**
         * "SERVIO {phone}" → "SERVIO 0912345678". Null when there is no template, or it needs a phone number that #9
         * did not give; a template without {phone} is used as it is.
         */
        fun note(template: String?, phoneNumber: String?): String? {
            val text = template?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (PHONE_PLACEHOLDER !in text) return text
            val phone = phoneNumber?.let(::localPhone) ?: return null
            return text.replace(PHONE_PLACEHOLDER, phone)
        }

        /** The phone number as people type it in a bank app: "+84 912 345 678" → "0912345678"; null when blank. */
        fun localPhone(phoneNumber: String): String? {
            val compact = phoneNumber.filterNot { it.isWhitespace() }
            if (compact.isEmpty()) return null
            return if (compact.startsWith("+84")) "0" + compact.removePrefix("+84") else compact
        }
    }
}
