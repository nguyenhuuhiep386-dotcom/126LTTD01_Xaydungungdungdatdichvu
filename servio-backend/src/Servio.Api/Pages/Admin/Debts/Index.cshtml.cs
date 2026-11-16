using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.RazorPages;
using Microsoft.EntityFrameworkCore;
using Servio.Api.Common;
using Servio.Api.Data;
using Servio.Api.Services.Admin;
using Servio.Api.Services.Wallets;

namespace Servio.Api.Pages.Admin.Debts;

/// <summary>
/// AW-18 — debts (course scope 0.2.6): confirm or reject declared bank transfers (#156), and see who still owes
/// commission (partners) or cancellation fees (customers).
/// </summary>
[Authorize(Policy = AuthPolicies.AdminFinance)]
public sealed class IndexModel(ServioDbContext db, WalletService wallets, Services.SystemConfigService configs) : PageModel
{
    public enum DebtTab { Pending, Partners, Customers, History }

    public sealed record PaymentRow(Guid Id, string Code, string Payer, string Phone, bool ForCancellationFees, decimal Amount,
        string? Reference, Guid? EvidenceFileId, TransactionStatus Status, DateTimeOffset CreatedAt, int Charges);

    public sealed record PartnerDebtRow(Guid UserId, string Name, string Phone, decimal Debt, decimal Available, DateTimeOffset UpdatedAt);

    public sealed record FeeRow(Guid Id, string Customer, string Phone, string OrderCode, Guid OrderId, string Partner, decimal Amount,
        bool NoShow, bool Declared, DateTimeOffset CreatedAt);

    [BindProperty(SupportsGet = true)] public DebtTab Tab { get; set; } = DebtTab.Pending;

    public IReadOnlyList<PaymentRow> Payments { get; private set; } = [];
    public IReadOnlyList<PartnerDebtRow> PartnerDebts { get; private set; } = [];
    public IReadOnlyList<FeeRow> Fees { get; private set; } = [];
    public decimal MaxPartnerDebt { get; private set; }

    [TempData] public string? Flash { get; set; }
    public string? Error { get; private set; }

    public async Task OnGetAsync(CancellationToken ct)
    {
        switch (Tab)
        {
            case DebtTab.Pending or DebtTab.History:
                var pending = Tab == DebtTab.Pending;
                var payments = db.Transactions.AsNoTracking()
                    .Where(t => t.Type == (byte)TransactionType.DebtPayment && t.Provider == (byte)TransactionProvider.BankTransfer
                                && (t.Status == (byte)TransactionStatus.Pending) == pending);
                // Queue: oldest first. History: last handled first.
                payments = pending ? payments.OrderBy(t => t.CreatedAt) : payments.OrderByDescending(t => t.UpdatedAt);
                Payments = await payments.Take(100)
                    .Select(t => new PaymentRow(t.Id, t.Code, t.User.FullName, t.User.PhoneNumber, t.CancellationCharges.Any(), t.Amount,
                        t.BankTransferReference, t.EvidenceFileId, (TransactionStatus)t.Status, t.CreatedAt, t.CancellationCharges.Count))
                    .ToListAsync(ct);
                break;
            case DebtTab.Partners:
                MaxPartnerDebt = await configs.GetDecimalAsync("partner.max_commission_debt", 200_000, ct);
                PartnerDebts = await db.Wallets.AsNoTracking().Where(w => w.DebtBalance > 0).OrderByDescending(w => w.DebtBalance)
                    .Select(w => new PartnerDebtRow(w.UserId, w.User.FullName, w.User.PhoneNumber, w.DebtBalance, w.AvailableBalance, w.UpdatedAt))
                    .ToListAsync(ct);
                break;
            case DebtTab.Customers:
                Fees = await db.CancellationCharges.AsNoTracking().Where(c => c.Status == (byte)CancellationChargeStatus.Due)
                    .OrderBy(c => c.CreatedAt)
                    .Select(c => new FeeRow(c.Id, c.Customer.User.FullName, c.Customer.User.PhoneNumber, c.Order.Code, c.OrderId,
                        c.PartnerProfile.User.FullName, c.Amount, c.Reason == (byte)CancellationReason.CustomerNoShow,
                        c.CollectedTransactionId != null, c.CreatedAt))
                    .ToListAsync(ct);
                break;
        }
    }

    public Task<IActionResult> OnPostConfirmAsync(Guid id, CancellationToken ct) =>
        RunAsync("Đã xác nhận giao dịch, công nợ đã được cập nhật.", () => wallets.ConfirmPaymentAsync(AdminActor.From(HttpContext), id, ct), ct);

    public Task<IActionResult> OnPostRejectAsync(Guid id, string? reason, CancellationToken ct) =>
        RunAsync("Đã từ chối giao dịch và báo cho người khai báo.",
            () => string.IsNullOrWhiteSpace(reason)
                ? throw new ApiException(StatusCodes.Status400BadRequest, ErrorCodes.ValidationError, "Hãy ghi lý do từ chối", "reason")
                : wallets.RejectPaymentAsync(AdminActor.From(HttpContext), id, reason.Trim(), ct), ct);

    private async Task<IActionResult> RunAsync(string success, Func<Task> action, CancellationToken ct)
    {
        try
        {
            await action();
            Flash = success;
            return RedirectToPage(new { Tab });
        }
        catch (ApiException e)
        {
            Error = e.Message;
            await OnGetAsync(ct);
            return Page();
        }
    }
}
