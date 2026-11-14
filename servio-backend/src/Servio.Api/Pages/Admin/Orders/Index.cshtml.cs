using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.RazorPages;
using Microsoft.EntityFrameworkCore;
using Servio.Api.Common;
using Servio.Api.Data;

namespace Servio.Api.Pages.Admin.Orders;

/// <summary>AW-07 — orders: filter by status, search by order code, customer or partner phone. Read-only (F-ADM-05).</summary>
public sealed class IndexModel(ServioDbContext db) : PageModel
{
    public const int PageSize = 30;

    public sealed record Row(Guid Id, string Code, string Title, string Customer, string? Partner, OrderStatus Status,
        decimal Total, DateTimeOffset CreatedAt, DateTimeOffset UpdatedAt);

    public static readonly (OrderStatus? Status, string Label)[] Tabs =
    [
        (null, "Tất cả"),
        (OrderStatus.Pending, "Chờ xác nhận"),
        (OrderStatus.InProgress, "Đang làm"),
        (OrderStatus.CompletedByPartner, "Chờ khách xác nhận"),
        (OrderStatus.Completed, "Hoàn tất"),
        (OrderStatus.Cancelled, "Đã huỷ"),
        (OrderStatus.Disputed, "Khiếu nại"),
    ];

    [BindProperty(SupportsGet = true)] public OrderStatus? Status { get; set; }
    [BindProperty(SupportsGet = true)] public string? Q { get; set; }
    [BindProperty(SupportsGet = true, Name = "p")] public int PageNumber { get; set; } = 1;

    public IReadOnlyList<Row> Rows { get; private set; } = [];
    public int Total { get; private set; }

    public async Task OnGetAsync(CancellationToken ct)
    {
        var query = db.Orders.AsNoTracking();
        if (Status is { } s) query = query.Where(o => o.Status == (byte)s);
        if (!string.IsNullOrWhiteSpace(Q))
        {
            var q = Q.Trim();
            var digits = new string(q.Where(char.IsDigit).ToArray()).TrimStart('0');
            query = query.Where(o => o.Code.Contains(q)
                                     || (digits.Length >= 6 && (o.Customer.User.PhoneNumber.Contains(digits)
                                                                || o.OrderAssignments.Any(a => a.PartnerProfile.User.PhoneNumber.Contains(digits)))));
        }
        Total = await query.CountAsync(ct);
        Rows = await query.OrderByDescending(o => o.UpdatedAt).Skip((Math.Max(PageNumber, 1) - 1) * PageSize).Take(PageSize)
            .Select(o => new Row(o.Id, o.Code, o.ServiceRequest.Title, o.Customer.User.FullName,
                o.OrderAssignments.OrderByDescending(a => a.IsPrimary).Select(a => a.PartnerProfile.User.FullName).FirstOrDefault(),
                (OrderStatus)o.Status, o.TotalAmount, o.CreatedAt, o.UpdatedAt))
            .ToListAsync(ct);
    }
}
