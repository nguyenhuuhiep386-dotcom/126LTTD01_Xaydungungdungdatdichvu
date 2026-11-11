using Microsoft.EntityFrameworkCore;
using Servio.Api.Common;
using Servio.Api.Data;
using Servio.Api.Data.Entities;
using Servio.Api.Services.Admin;
using Servio.Api.Services.Files;

namespace Servio.Api.Services.Wallets;

/// <summary>
/// Internal ledger (F-PAY-02, 2.6.1) and debts (0.2.7 ý 8–9). Every balance change is one append-only WalletTransaction
/// with a BusinessEventKey, so replaying the same event hits the unique index instead of counting twice.
/// The partner's commission debt is Wallet.DebtBalance; the customer's debts are DUE CancellationCharges.
/// Helpers only add to the change tracker: the caller saves, so money moves in the same SaveChanges as the order.
/// </summary>
public sealed class WalletService(
    ServioDbContext db,
    FileService files,
    CodeGenerator codes,
    SystemConfigService configs,
    TimeProvider clock)
{
    public async Task<Data.Entities.Wallet> GetOrCreateAsync(Guid userId, CancellationToken ct)
    {
        // Local first: a wallet added earlier in this unit of work is not in the database yet.
        var wallet = db.Wallets.Local.FirstOrDefault(w => w.UserId == userId)
                     ?? await db.Wallets.FirstOrDefaultAsync(w => w.UserId == userId, ct);
        if (wallet is null)
        {
            wallet = new Data.Entities.Wallet { Id = Guid.CreateVersion7(), UserId = userId, Currency = "VND", UpdatedAt = clock.GetUtcNow() };
            db.Wallets.Add(wallet);
        }
        return wallet;
    }

    /// <summary>One ledger line on one bucket. Throws if a debit would make the bucket negative (CK_Wallets_Balances).</summary>
    public WalletTransaction Post(Data.Entities.Wallet wallet, WalletBucket bucket, LedgerDirection direction, WalletTransactionType type,
        decimal amount, string description, string eventKey, Guid? orderId = null, Guid? assignmentId = null,
        Guid? transactionId = null, Guid? chargeId = null)
    {
        var before = bucket == WalletBucket.Debt ? wallet.DebtBalance : wallet.AvailableBalance;
        var after = direction == LedgerDirection.Credit ? before + amount : before - amount;
        if (amount <= 0 || after < 0)
        {
            throw new InvalidOperationException($"Ledger line {eventKey} would leave {bucket} at {after}.");
        }
        if (bucket == WalletBucket.Debt) wallet.DebtBalance = after;
        else wallet.AvailableBalance = after;
        var now = clock.GetUtcNow();
        wallet.UpdatedAt = now;
        var line = new WalletTransaction
        {
            Id = Guid.CreateVersion7(),
            WalletId = wallet.Id,
            OrderId = orderId,
            OrderAssignmentId = assignmentId,
            TransactionId = transactionId,
            CancellationChargeId = chargeId,
            Type = (byte)type,
            Direction = (byte)direction,
            Bucket = (byte)bucket,
            LiabilityRole = (byte)UserRoleType.Partner,
            Amount = amount,
            BalanceBefore = before,
            BalanceAfter = after,
            Description = description,
            BusinessEventKey = eventKey,
            CreatedAt = now,
        };
        db.WalletTransactions.Add(line);
        return line;
    }

    /// <summary>
    /// CREDIT Available, then the automatic offset of 2.6.1 (v1.4): m = min(credit, debt) becomes an INTERNAL_OFFSET
    /// DEBT_PAYMENT, with DEBIT Available m and DEBIT Debt m. Returns the part of the debt that was paid.
    /// </summary>
    public async Task<decimal> CreditPartnerAsync(Guid partnerUserId, decimal amount, WalletTransactionType type, string description,
        string eventKey, Guid? orderId, Guid? chargeId, CancellationToken ct)
    {
        var wallet = await GetOrCreateAsync(partnerUserId, ct);
        var credit = Post(wallet, WalletBucket.Available, LedgerDirection.Credit, type, amount, description, eventKey, orderId, chargeId: chargeId);
        var offset = Math.Min(amount, wallet.DebtBalance);
        if (offset <= 0)
        {
            return 0;
        }
        var now = clock.GetUtcNow();
        var key = $"offset:{credit.Id}";
        var tx = NewTransaction(partnerUserId, TransactionType.DebtPayment, TransactionProvider.InternalOffset, offset,
            TransactionStatus.Success, await codes.NextAsync(CodeGenerator.Transaction, ct), now);
        tx.BusinessEventKey = key;
        tx.PaidAt = now;
        Post(wallet, WalletBucket.Available, LedgerDirection.Debit, WalletTransactionType.DebtPayment, offset, "Tự cấn trừ nợ hoa hồng", key, orderId, transactionId: tx.Id);
        Post(wallet, WalletBucket.Debt, LedgerDirection.Debit, WalletTransactionType.DebtPayment, offset, "Tự cấn trừ nợ hoa hồng", key, orderId, transactionId: tx.Id);
        return offset;
    }

    public Transaction NewTransaction(Guid userId, TransactionType type, TransactionProvider provider, decimal amount,
        TransactionStatus status, string code, DateTimeOffset now)
    {
        var tx = new Transaction
        {
            Id = Guid.CreateVersion7(),
            Code = code,
            UserId = userId,
            Type = (byte)type,
            Provider = (byte)provider,
            Amount = amount,
            Currency = "VND",
            Status = (byte)status,
            CreatedAt = now,
            UpdatedAt = now,
        };
        db.Transactions.Add(tx);
        return tx;
    }

    /// <summary>#87</summary>
    public async Task<WalletDto> GetMineAsync(Guid userId, AppFlavor flavor, CancellationToken ct)
    {
        if (flavor == AppFlavor.Customer)
        {
            var charges = await DueChargesQuery(userId).Select(c => new { c.Amount, Pending = c.CollectedTransactionId != null }).ToListAsync(ct);
            return new WalletDto(0, (long)charges.Sum(c => c.Amount), (long)charges.Where(c => c.Pending).Sum(c => c.Amount),
                (long)await configs.GetDecimalAsync("cancellation.max_outstanding_amount", 100_000, ct));
        }
        var wallet = await db.Wallets.AsNoTracking().FirstOrDefaultAsync(w => w.UserId == userId, ct);
        return new WalletDto((long)(wallet?.AvailableBalance ?? 0), (long)(wallet?.DebtBalance ?? 0),
            (long)await PendingCommissionPaymentAsync(userId, ct),
            (long)await configs.GetDecimalAsync("partner.max_commission_debt", 200_000, ct));
    }

    /// <summary>#88 newest first. The ledger only has partner lines in the course scope; a customer gets an empty list.</summary>
    public async Task<PagedResult<WalletTransactionDto>> ListTransactionsAsync(Guid userId, AppFlavor flavor, int page, int pageSize, CancellationToken ct)
    {
        page = Math.Max(page, 1);
        pageSize = Math.Clamp(pageSize, 1, 50);
        var query = db.WalletTransactions.AsNoTracking()
            .Where(t => t.Wallet.UserId == userId && t.LiabilityRole == (byte)(flavor == AppFlavor.Partner ? UserRoleType.Partner : UserRoleType.Customer));
        var total = await query.CountAsync(ct);
        var items = await query.OrderByDescending(t => t.CreatedAt).ThenByDescending(t => t.Id)
            .Skip((page - 1) * pageSize).Take(pageSize)
            .Select(t => new WalletTransactionDto(t.Id, (WalletTransactionType)t.Type, (LedgerDirection)t.Direction, (WalletBucket)t.Bucket,
                (long)t.Amount, (long)t.BalanceAfter, t.Description, t.OrderId, t.CreatedAt))
            .ToListAsync(ct);
        return new PagedResult<WalletTransactionDto>(items, total, page, pageSize);
    }

    /// <summary>#155</summary>
    public async Task<IReadOnlyList<DebtDto>> ListDebtsAsync(Guid userId, AppFlavor flavor, CancellationToken ct)
    {
        if (flavor == AppFlavor.Customer)
        {
            return await DueChargesQuery(userId).OrderBy(c => c.CreatedAt)
                .Select(c => new DebtDto(c.Id, "CANCELLATION_FEE", (long)c.Amount,
                    c.Reason == (byte)CancellationReason.CustomerNoShow ? "Phí khách không có mặt" : "Phí huỷ đơn",
                    c.Order.Code, c.CollectedTransaction != null ? c.CollectedTransaction.Code : null, c.CreatedAt))
                .ToListAsync(ct);
        }
        var wallet = await db.Wallets.AsNoTracking().FirstOrDefaultAsync(w => w.UserId == userId, ct);
        if (wallet is not { DebtBalance: > 0 })
        {
            return [];
        }
        var pending = await PendingCommissionPayments(userId).OrderByDescending(t => t.CreatedAt).Select(t => t.Code).FirstOrDefaultAsync(ct);
        return [new DebtDto(wallet.Id, "COMMISSION", (long)wallet.DebtBalance, "Nợ hoa hồng đơn tiền mặt", null, pending, wallet.UpdatedAt)];
    }

    /// <summary>
    /// #156 declare a bank transfer → Transaction DEBT_PAYMENT PENDING, confirmed by the admin on AW-18. Debts already in a
    /// pending declaration cannot be declared again, so a double tap fails with NOTHING_TO_PAY.
    /// </summary>
    public async Task<DebtPaymentDto> DeclarePaymentAsync(Guid userId, AppFlavor flavor, DeclareDebtPaymentRequest input, CancellationToken ct)
    {
        var reference = input.BankTransferReference.Trim();
        var evidence = await files.ResolveOwnedAsync(userId, input.EvidenceUrl, "evidenceUrl", ct, FilePurpose.DisputeEvidence);
        var now = clock.GetUtcNow();
        decimal amount;
        List<CancellationCharge> charges = [];
        if (flavor == AppFlavor.Customer)
        {
            var ids = (input.DebtIds ?? []).Distinct().ToList();
            if (ids.Count == 0)
            {
                throw new ApiException(StatusCodes.Status400BadRequest, ErrorCodes.ValidationError, "Hãy chọn khoản phí cần thanh toán", "debtIds");
            }
            charges = await DueChargesQuery(userId).Where(c => ids.Contains(c.Id) && c.CollectedTransactionId == null).ToListAsync(ct);
            if (charges.Count != ids.Count)
            {
                throw new ApiException(StatusCodes.Status422UnprocessableEntity, ErrorCodes.NothingToPay,
                    "Có khoản phí đã thanh toán hoặc đang chờ xác nhận, vui lòng tải lại");
            }
            amount = charges.Sum(c => c.Amount);
        }
        else
        {
            var debt = await db.Wallets.Where(w => w.UserId == userId).Select(w => w.DebtBalance).FirstOrDefaultAsync(ct);
            amount = debt - await PendingCommissionPaymentAsync(userId, ct);
            if (amount <= 0)
            {
                throw new ApiException(StatusCodes.Status422UnprocessableEntity, ErrorCodes.NothingToPay,
                    debt > 0 ? "Khoản nợ đã được khai báo, đang chờ xác nhận" : "Bạn không có khoản nợ nào");
            }
        }

        var tx = NewTransaction(userId, TransactionType.DebtPayment, TransactionProvider.BankTransfer, amount, TransactionStatus.Pending,
            await codes.NextAsync(CodeGenerator.Transaction, ct), now);
        tx.BankTransferReference = reference;
        tx.EvidenceFileId = evidence.Id;
        foreach (var c in charges)
        {
            c.CollectedTransactionId = tx.Id;
            c.UpdatedAt = now;
        }
        await db.SaveChangesAsync(ct);
        return ToDto(tx);
    }

    /// <summary>AW-18 queue: declared transfers waiting for the admin, oldest first.</summary>
    public async Task<IReadOnlyList<DebtPaymentDto>> ListPendingPaymentsAsync(CancellationToken ct) =>
        (await db.Transactions.AsNoTracking()
            .Where(t => t.Type == (byte)TransactionType.DebtPayment && t.Provider == (byte)TransactionProvider.BankTransfer && t.Status == (byte)TransactionStatus.Pending)
            .OrderBy(t => t.CreatedAt).ToListAsync(ct))
        .Select(ToDto).ToList();

    /// <summary>
    /// AW-18 confirm (0.2.7 ý 9). Customer fee payment: charges → PAID and each original partner is credited the fee
    /// (with the automatic offset). Partner payment: DebtBalance decreases. Writes AuditLog in the same save.
    /// </summary>
    public async Task ConfirmPaymentAsync(AdminActor actor, Guid transactionId, CancellationToken ct)
    {
        var tx = await PendingBankTransferAsync(transactionId, ct);
        var now = clock.GetUtcNow();
        var charges = await db.CancellationCharges.Include(c => c.PartnerProfile)
            .Where(c => c.CollectedTransactionId == tx.Id).ToListAsync(ct);
        if (charges.Count > 0)
        {
            foreach (var c in charges)
            {
                c.Status = (byte)CancellationChargeStatus.Paid;
                c.CompensationCreditedAt = now;
                c.UpdatedAt = now;
                await CreditPartnerAsync(c.PartnerProfile.UserId, c.Amount, WalletTransactionType.CancellationFee,
                    "Tiền bù phí huỷ khách đã trả", $"cancel-fee:{c.Id}", c.OrderId, c.Id, ct);
            }
        }
        else
        {
            var wallet = await GetOrCreateAsync(tx.UserId, ct);
            // ponytail: an overpayment (debt already lowered by offsets since the declaration) is settled outside the app.
            var paid = Math.Min(tx.Amount, wallet.DebtBalance);
            if (paid > 0)
            {
                Post(wallet, WalletBucket.Debt, LedgerDirection.Debit, WalletTransactionType.DebtPayment, paid,
                    $"Trả nợ hoa hồng ({tx.Code})", $"debt-payment:{tx.Id}", transactionId: tx.Id);
            }
        }
        tx.Status = (byte)TransactionStatus.Success;
        tx.PaidAt = now;
        tx.ConfirmedByAdminId = actor.AdminId;
        tx.UpdatedAt = now;
        db.AddAudit(actor, "DEBT_PAYMENT_CONFIRM", nameof(Transaction), tx.Id,
            new { Status = nameof(TransactionStatus.Pending) }, new { Status = nameof(TransactionStatus.Success), tx.Amount, ChargeCount = charges.Count }, now);
        await db.SaveChangesAsync(ct);
    }

    /// <summary>AW-18 reject: the declaration fails and the charges can be declared again.</summary>
    public async Task RejectPaymentAsync(AdminActor actor, Guid transactionId, string reason, CancellationToken ct)
    {
        var tx = await PendingBankTransferAsync(transactionId, ct);
        var now = clock.GetUtcNow();
        foreach (var c in await db.CancellationCharges.Where(c => c.CollectedTransactionId == tx.Id).ToListAsync(ct))
        {
            c.CollectedTransactionId = null;
            c.UpdatedAt = now;
        }
        tx.Status = (byte)TransactionStatus.Failed;
        tx.ConfirmedByAdminId = actor.AdminId;
        tx.UpdatedAt = now;
        db.AddAudit(actor, "DEBT_PAYMENT_REJECT", nameof(Transaction), tx.Id,
            new { Status = nameof(TransactionStatus.Pending) }, new { Status = nameof(TransactionStatus.Failed), Reason = reason }, now);
        await db.SaveChangesAsync(ct);
    }

    private async Task<Transaction> PendingBankTransferAsync(Guid transactionId, CancellationToken ct) =>
        await db.Transactions.FirstOrDefaultAsync(t => t.Id == transactionId && t.Type == (byte)TransactionType.DebtPayment
                                                       && t.Provider == (byte)TransactionProvider.BankTransfer, ct) is { } tx
            ? tx.Status == (byte)TransactionStatus.Pending
                ? tx
                : throw new ApiException(StatusCodes.Status409Conflict, ErrorCodes.Conflict, "Giao dịch đã được xử lý")
            : throw ApiException.NotFound("Không tìm thấy giao dịch");

    private IQueryable<CancellationCharge> DueChargesQuery(Guid customerUserId) =>
        db.CancellationCharges.Where(c => c.Customer.UserId == customerUserId && c.Status == (byte)CancellationChargeStatus.Due);

    /// <summary>Partner declarations are the pending bank transfers that no cancellation charge points to.</summary>
    private IQueryable<Transaction> PendingCommissionPayments(Guid userId) =>
        db.Transactions.Where(t => t.UserId == userId && t.Type == (byte)TransactionType.DebtPayment
                                   && t.Provider == (byte)TransactionProvider.BankTransfer && t.Status == (byte)TransactionStatus.Pending
                                   && !t.CancellationCharges.Any());

    private async Task<decimal> PendingCommissionPaymentAsync(Guid userId, CancellationToken ct) =>
        await PendingCommissionPayments(userId).SumAsync(t => (decimal?)t.Amount, ct) ?? 0;

    private static DebtPaymentDto ToDto(Transaction t) =>
        new(t.Id, t.Code, (long)t.Amount, (TransactionStatus)t.Status, t.BankTransferReference ?? "", t.CreatedAt);
}
