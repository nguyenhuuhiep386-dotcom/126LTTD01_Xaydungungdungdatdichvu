using System.ComponentModel.DataAnnotations;
using Servio.Api.Common;

namespace Servio.Api.Services.Wallets;

/// <summary>
/// #87 GET /wallet/me for the app of the token. Partner: debtBalance = commission debt (COD), availableBalance = credits
/// left after the automatic offset. Customer: debtBalance = unpaid cancellation fees. pendingPayment = declared transfers
/// waiting for the admin (AW-18). maxDebt = limit above which the partner cannot take new work / the customer cannot post.
/// </summary>
public sealed record WalletDto(long AvailableBalance, long DebtBalance, long PendingPayment, long MaxDebt);

/// <summary>#88 row. bucket AVAILABLE or DEBT; balanceAfter is the balance of that bucket after this line.</summary>
public sealed record WalletTransactionDto(
    Guid Id,
    WalletTransactionType Type,
    LedgerDirection Direction,
    WalletBucket Bucket,
    long Amount,
    long BalanceAfter,
    string Description,
    Guid? OrderId,
    DateTimeOffset CreatedAt);

/// <summary>
/// #155 one obligation. Customer: one row per DUE cancellation charge (id = charge id, pass it in debtIds).
/// Partner: a single COMMISSION row for the whole debt (id = wallet id; #156 pays it without debtIds).
/// pendingTransactionCode is set while a declared transfer waits for the admin.
/// </summary>
public sealed record DebtDto(Guid Id, string Kind, long Amount, string Description, string? OrderCode, string? PendingTransactionCode, DateTimeOffset CreatedAt);

/// <summary>
/// #156 declare a bank transfer (course scope 0.2.4 M6). Customer: debtIds = cancellation charges to pay.
/// Partner: debtIds empty, pays the whole commission debt not yet declared. evidenceUrl = photo of the transfer
/// uploaded with POST /files purpose DISPUTE_EVIDENCE. The amount is always computed by the server.
/// </summary>
public sealed record DeclareDebtPaymentRequest(
    Guid[]? DebtIds,
    [Required(ErrorMessage = "Hãy nhập mã giao dịch chuyển khoản"), StringLength(100, MinimumLength = 3, ErrorMessage = "Mã giao dịch dài 3–100 ký tự")] string BankTransferReference,
    [Required(ErrorMessage = "Hãy đính kèm ảnh chứng từ"), StringLength(500)] string EvidenceUrl);

/// <summary>Result of #156 and row of the admin page AW-18.</summary>
public sealed record DebtPaymentDto(Guid TransactionId, string Code, long Amount, TransactionStatus Status, string BankTransferReference, DateTimeOffset CreatedAt);
