using Microsoft.AspNetCore.Mvc.RazorPages;
using Microsoft.EntityFrameworkCore;
using Servio.Api.Common;
using Servio.Api.Data;

namespace Servio.Api.Pages.Admin;

/// <summary>AW-02 — dashboard (F-ADM-01, course scope): queues to act on, GMV/commission, orders by status, last 7 days.</summary>
public sealed class IndexModel(ServioDbContext db, TimeProvider clock) : PageModel
{
    public sealed record DayRow(DateOnly Day, int Created, int Completed, decimal Gmv);

    public int Customers { get; private set; }
    public int Partners { get; private set; }
    public int PartnersOnline { get; private set; }
    public int PendingKyc { get; private set; }
    public int OpenRequests { get; private set; }
    public int OpenDisputes { get; private set; }
    public int PendingPayments { get; private set; }
    public decimal Gmv { get; private set; }
    public decimal Commission { get; private set; }
    public decimal CommissionDebt { get; private set; }
    public decimal CancellationFeesDue { get; private set; }
    public double CompletionRate { get; private set; }
    public IReadOnlyList<(OrderStatus Status, int Count)> OrdersByStatus { get; private set; } = [];
    public IReadOnlyList<DayRow> Days { get; private set; } = [];

    public async Task OnGetAsync(CancellationToken ct)
    {
        var now = clock.GetUtcNow();
        Customers = await db.CustomerProfiles.CountAsync(ct);
        Partners = await db.PartnerProfiles.CountAsync(p => p.VerificationStatus == (byte)PartnerVerificationStatus.Approved, ct);
        var onlineSince = now.AddMinutes(-10); // 0.2.7 ý 2: heartbeat within 10 minutes
        PartnersOnline = await db.PartnerProfiles.CountAsync(p => p.IsOnline && p.LastHeartbeatAt >= onlineSince, ct);
        PendingKyc = await db.PartnerProfiles.CountAsync(p => p.VerificationStatus == (byte)PartnerVerificationStatus.Pending, ct);
        OpenRequests = await db.ServiceRequests.CountAsync(r => r.Status == (byte)ServiceRequestStatus.Open, ct);
        OpenDisputes = await db.Disputes.CountAsync(d => d.Status == (byte)DisputeStatus.Open || d.Status == (byte)DisputeStatus.InReview, ct);
        PendingPayments = await db.Transactions.CountAsync(t => t.Type == (byte)TransactionType.DebtPayment
            && t.Provider == (byte)TransactionProvider.BankTransfer && t.Status == (byte)TransactionStatus.Pending, ct);

        var completed = db.Orders.Where(o => o.Status == (byte)OrderStatus.Completed);
        Gmv = await completed.SumAsync(o => (decimal?)o.TotalAmount, ct) ?? 0;
        Commission = await completed.SumAsync(o => (decimal?)o.CommissionAmount, ct) ?? 0;
        CommissionDebt = await db.Wallets.SumAsync(w => (decimal?)w.DebtBalance, ct) ?? 0;
        CancellationFeesDue = await db.CancellationCharges.Where(c => c.Status == (byte)CancellationChargeStatus.Due).SumAsync(c => (decimal?)c.Amount, ct) ?? 0;

        var byStatus = await db.Orders.GroupBy(o => o.Status).Select(g => new { g.Key, Count = g.Count() }).ToListAsync(ct);
        OrdersByStatus = byStatus.OrderBy(x => x.Key).Select(x => ((OrderStatus)x.Key, x.Count)).ToList();
        var done = byStatus.Where(x => x.Key == (byte)OrderStatus.Completed).Sum(x => x.Count);
        var ended = done + byStatus.Where(x => x.Key == (byte)OrderStatus.Cancelled).Sum(x => x.Count);
        CompletionRate = ended == 0 ? 0 : 100.0 * done / ended;

        // Last 7 days by Vietnam date; few rows in a course project, so group in memory.
        var vn = TimeSpan.FromHours(7);
        var today = DateOnly.FromDateTime(now.ToOffset(vn).DateTime);
        var from = new DateTimeOffset(today.AddDays(-6).ToDateTime(TimeOnly.MinValue), vn);
        var recent = await db.Orders.AsNoTracking().Where(o => o.CreatedAt >= from || o.CompletedAt >= from)
            .Select(o => new { o.CreatedAt, o.CompletedAt, o.Status, o.TotalAmount }).ToListAsync(ct);
        DateOnly Day(DateTimeOffset at) => DateOnly.FromDateTime(at.ToOffset(vn).DateTime);
        Days = Enumerable.Range(0, 7).Select(i => today.AddDays(-6 + i)).Select(d => new DayRow(d,
            recent.Count(o => Day(o.CreatedAt) == d),
            recent.Count(o => o.Status == (byte)OrderStatus.Completed && o.CompletedAt is { } c && Day(c) == d),
            recent.Where(o => o.Status == (byte)OrderStatus.Completed && o.CompletedAt is { } c && Day(c) == d).Sum(o => o.TotalAmount))).ToList();
    }
}
