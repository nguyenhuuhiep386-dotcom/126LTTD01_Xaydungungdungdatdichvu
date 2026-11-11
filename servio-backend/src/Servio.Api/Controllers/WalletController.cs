using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Servio.Api.Common;
using Servio.Api.Services.Wallets;

namespace Servio.Api.Controllers;

/// <summary>
/// M6 — balances and debts (#87, #88, #155, #156) for the app of the token: the partner's commission debt (PS-21) or the
/// customer's unpaid cancellation fees (CS-30). Paying is a declared bank transfer that the admin confirms on AW-18.
/// </summary>
[Authorize]
[Route("api/v1/wallet")]
public sealed class WalletController(WalletService wallets) : ApiControllerBase
{
    /// <summary>#87 Balances: debtBalance, pendingPayment (declared, waiting for the admin) and the blocking limit maxDebt.</summary>
    [HttpGet("me")]
    public async Task<ActionResult<ApiResponse<WalletDto>>> Me(CancellationToken ct) =>
        OkEnvelope(await wallets.GetMineAsync(CurrentUserId, CurrentAppFlavor, ct));

    /// <summary>#88 Ledger lines, newest first (commission, offsets, cancellation compensation, debt payments).</summary>
    [HttpGet("transactions")]
    public async Task<ActionResult<ApiResponse<PagedResult<WalletTransactionDto>>>> Transactions(
        [FromQuery] int page = 1, [FromQuery] int pageSize = 20, CancellationToken ct = default) =>
        OkEnvelope(await wallets.ListTransactionsAsync(CurrentUserId, CurrentAppFlavor, page, pageSize, ct));

    /// <summary>#155 What is still owed. Customer: one row per cancellation fee; partner: one COMMISSION row.</summary>
    [HttpGet("debts")]
    public async Task<ActionResult<ApiResponse<IReadOnlyList<DebtDto>>>> Debts(CancellationToken ct) =>
        OkEnvelope(await wallets.ListDebtsAsync(CurrentUserId, CurrentAppFlavor, ct));

    /// <summary>
    /// #156 Declare a bank transfer with a photo of the receipt (POST /files purpose DISPUTE_EVIDENCE). Customer sends
    /// debtIds; partner sends none and pays the whole debt. The amount is computed by the server. 422 NOTHING_TO_PAY when
    /// everything is already paid or declared.
    /// </summary>
    [HttpPost("debt-payments")]
    [ProducesResponseType(StatusCodes.Status201Created)]
    public async Task<ActionResult<ApiResponse<DebtPaymentDto>>> DeclarePayment(DeclareDebtPaymentRequest request, CancellationToken ct) =>
        CreatedEnvelope(await wallets.DeclarePaymentAsync(CurrentUserId, CurrentAppFlavor, request, ct));
}
