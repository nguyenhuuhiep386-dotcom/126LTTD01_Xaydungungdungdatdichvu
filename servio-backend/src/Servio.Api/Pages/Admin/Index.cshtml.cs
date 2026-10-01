using Microsoft.AspNetCore.Mvc.RazorPages;
using Microsoft.EntityFrameworkCore;
using Servio.Api.Common;
using Servio.Api.Data;

namespace Servio.Api.Pages.Admin;

/// <summary>AW-02 — dashboard. Basic counts only; charts belong to work package QT-2.</summary>
public sealed class IndexModel(ServioDbContext db) : PageModel
{
    public int Customers { get; private set; }
    public int Partners { get; private set; }
    public int PendingKyc { get; private set; }
    public int OpenRequests { get; private set; }
    public int ActiveOrders { get; private set; }

    public async Task OnGetAsync(CancellationToken ct)
    {
        Customers = await db.CustomerProfiles.CountAsync(ct);
        Partners = await db.PartnerProfiles.CountAsync(ct);
        PendingKyc = await db.PartnerProfiles.CountAsync(p => p.VerificationStatus == (byte)PartnerVerificationStatus.Pending, ct);
        OpenRequests = await db.ServiceRequests.CountAsync(r => r.Status == (byte)ServiceRequestStatus.Open, ct);
        ActiveOrders = await db.Orders.CountAsync(o => o.Status < (byte)OrderStatus.Completed || o.Status == (byte)OrderStatus.Disputed, ct);
    }
}
