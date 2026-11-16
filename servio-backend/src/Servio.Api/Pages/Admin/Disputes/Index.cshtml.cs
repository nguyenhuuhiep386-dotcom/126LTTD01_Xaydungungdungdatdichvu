using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.RazorPages;
using Microsoft.EntityFrameworkCore;
using Servio.Api.Common;
using Servio.Api.Data;

namespace Servio.Api.Pages.Admin.Disputes;

/// <summary>AW-08 — dispute queue (oldest SLA first) and closed cases.</summary>
[Authorize(Policy = AuthPolicies.AdminSupport)]
public sealed class IndexModel(ServioDbContext db, TimeProvider clock) : PageModel
{
    public sealed record Row(Guid Id, string Code, Guid OrderId, string OrderCode, DisputeReason Reason, ActorType RaisedBy, string RaisedByName,
        DisputeStatus Status, DateTimeOffset SlaDueAt, DateTimeOffset CreatedAt);

    [BindProperty(SupportsGet = true)] public bool Closed { get; set; }

    public IReadOnlyList<Row> Rows { get; private set; } = [];
    public DateTimeOffset Now { get; private set; }

    public async Task OnGetAsync(CancellationToken ct)
    {
        Now = clock.GetUtcNow();
        var open = new[] { (byte)DisputeStatus.Open, (byte)DisputeStatus.InReview };
        var query = db.Disputes.AsNoTracking().Where(d => open.Contains(d.Status) != Closed);
        query = Closed ? query.OrderByDescending(d => d.ResolvedAt) : query.OrderBy(d => d.SlaDueAt);
        Rows = await query.Take(100)
            .Select(d => new Row(d.Id, d.Code, d.OrderId, d.Order.Code, (DisputeReason)d.Reason, (ActorType)d.RaisedByType, d.RaisedByUser.FullName,
                (DisputeStatus)d.Status, d.SlaDueAt, d.CreatedAt))
            .ToListAsync(ct);
    }
}
