using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.RazorPages;
using Microsoft.EntityFrameworkCore;
using Servio.Api.Data;
using Servio.Api.Common;
using Servio.Api.Data.Entities;
using Servio.Api.Services.Admin;
using Servio.Api.Services.Orders;

namespace Servio.Api.Pages.Admin.Orders;

/// <summary>AW-07 — one order: parties, money (2.6.1), assignments, extra charges, photos, timeline, fees, disputes, COD payment.</summary>
public sealed class DetailModel(ServioDbContext db, CompletionService completion) : PageModel
{
    public Order Order { get; private set; } = null!;
    public IReadOnlyList<Transaction> Payments { get; private set; } = [];
    [TempData] public string? Flash { get; set; }
    public string? Error { get; private set; }

    /// <summary>Finance confirms the customer paid the partner after an UNPAID_CASH dispute (#177).</summary>
    public bool CanConfirmCollected => User.IsInRole(nameof(AdminRole.SuperAdmin)) || User.IsInRole(nameof(AdminRole.Finance));

    public async Task<IActionResult> OnPostConfirmCollectedAsync(Guid id, CancellationToken ct)
    {
        if (!CanConfirmCollected)
        {
            return Forbid();
        }
        try
        {
            await completion.ConfirmCollectedAsync(AdminActor.From(HttpContext), id, ct);
            Flash = "Đã xác nhận khách thanh toán, đơn hoàn tất và công nợ hoa hồng được ghi lại.";
            return RedirectToPage(new { id });
        }
        catch (ApiException e) when (e.StatusCode != StatusCodes.Status404NotFound)
        {
            Error = e.Message;
            return await OnGetAsync(id, ct);
        }
    }

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
