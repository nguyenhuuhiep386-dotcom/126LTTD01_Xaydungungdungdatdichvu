package vn.servio.customer.data.repository

import vn.servio.core.network.ApiResult
import vn.servio.customer.data.model.BankAccount
import vn.servio.customer.data.model.PostingRules
import vn.servio.customer.data.model.ServerConfig
import vn.servio.customer.data.model.SupportContact

/** A #107 answer for tests, with the values of the seed data unless a test says otherwise. */
fun serverConfig(
    partnerAcceptTimeoutMinutes: Int = 10,
    cancellationFeeAfterOnTheWay: Long = 20_000,
    cancellationFeeAfterArrivedMin: Long = 30_000,
    cancellationFeeAfterArrivedPercent: Double = 20.0,
    autoConfirmHours: Int = 24,
    disputeWindowHours: Int = 72,
    posting: PostingRules = PostingRules(windowHours = 2, minLeadMinutes = 30, maxLeadDays = 30, expireHours = 24),
    bank: BankAccount = BankAccount(transferNoteTemplate = "SERVIO {phone}"),
    support: SupportContact = SupportContact(hours = "8:00–21:00 hằng ngày"),
) = ServerConfig(
    partnerAcceptTimeoutMinutes = partnerAcceptTimeoutMinutes,
    cancellationFeeAfterOnTheWay = cancellationFeeAfterOnTheWay,
    cancellationFeeAfterArrivedMin = cancellationFeeAfterArrivedMin,
    cancellationFeeAfterArrivedPercent = cancellationFeeAfterArrivedPercent,
    noShowWaitMinutes = 15,
    autoConfirmHours = autoConfirmHours,
    disputeWindowHours = disputeWindowHours,
    disputeResponseHours = 48,
    reviewWindowDays = 5,
    posting = posting,
    cancellationFeeBank = bank,
    support = support,
)

/** #107 answering [answer]; [latest] is what the real repository would keep after a success. */
class FakeServerConfigRepository(var answer: ApiResult<ServerConfig> = ApiResult.Success(serverConfig())) : ServerConfigRepository {
    var calls = 0
    override var latest: ServerConfig? = null

    override suspend fun getConfig(): ApiResult<ServerConfig> {
        calls++
        val result = answer
        if (result is ApiResult.Success) latest = result.data
        return result
    }
}
