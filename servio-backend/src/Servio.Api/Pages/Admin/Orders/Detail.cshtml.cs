using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.RazorPages;
using Microsoft.EntityFrameworkCore;
using Servio.Api.Data;
using Servio.Api.Data.Entities;

namespace Servio.Api.Pages.Admin.Orders;

/// <summary>AW-07 — one order: parties, money (2.6.1), assignments, extra charges, photos, timeline, fees, disputes, COD payment.</summary>
public sealed class DetailModel(ServioDbContext db) : PageModel
{
    public Order Order { get; private set; } = null!;
    public IReadOnlyList<Transaction> Payments { get; private set; } = [];

    public async Task<IActionResult> OnGetAsync(Guid id, CancellationToken ct)
    {
        var order = await db.Orders.AsNoTracking().AsSplitQuery()
            .Include(o => o.ServiceRequest)
            .Include(o => o.ServiceCategory)
            .Include(o => o.Customer).ThenInclude(c => c.User)
            .Include(o => o.OrderAssignments).ThenInclude(a => a.PartnerProfile).ThenInclude(p => p.User)
            .Include(o => o.OrderAssignments).ThenInclude(a => a.OrderImages)
            .Include(o => o.OrderExtraCharges)
            .Include(o => o.OrderStatusHistories)
            .Include(o => o.CancellationCharges)
            .Include(o => o.Disputes)
            .Include(o => o.Reviews)
            .FirstOrDefaultAsync(o => o.Id == id, ct);
        if (order is null)
        {
            return NotFound();
        }
        Order = order;
        Payments = await db.Transactions.AsNoTracking().Where(t => t.OrderId == id).OrderBy(t => t.CreatedAt).ToListAsync(ct);
        return Page();
    }
}
