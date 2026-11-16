using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.RazorPages;
using Microsoft.EntityFrameworkCore;
using Servio.Api.Common;
using Servio.Api.Data;
using Servio.Api.Services.Admin;
using Servio.Api.Services.Requests;

namespace Servio.Api.Pages.Admin.Requests;

/// <summary>AW-06 — posts: browse by status, search by code/title, hide a post that breaks the rules (F-ADM-04).</summary>
[Authorize(Policy = AuthPolicies.AdminOperator)]
public sealed class IndexModel(ServioDbContext db, ServiceRequestService requests) : PageModel
{
    public const int PageSize = 30;

    public sealed record Row(Guid Id, string Code, string Title, string Description, string Category, string Customer, string Phone,
        ServiceRequestStatus Status, int Quotes, string? Note, Guid? OrderId, DateTimeOffset CreatedAt);

    public static readonly (ServiceRequestStatus? Status, string Label)[] Tabs =
    [
        (ServiceRequestStatus.Open, "Đang mở"),
        (ServiceRequestStatus.Matched, "Đã chọn đối tác"),
        (ServiceRequestStatus.RejectedByModeration, "Đã ẩn"),
        (null, "Tất cả"),
    ];

    [BindProperty(SupportsGet = true)] public ServiceRequestStatus? Status { get; set; } = ServiceRequestStatus.Open;
    [BindProperty(SupportsGet = true)] public string? Q { get; set; }
    [BindProperty(SupportsGet = true, Name = "p")] public int PageNumber { get; set; } = 1;

    public IReadOnlyList<Row> Rows { get; private set; } = [];
    public int Total { get; private set; }
    [TempData] public string? Flash { get; set; }
    public string? Error { get; private set; }

    public async Task OnGetAsync(CancellationToken ct)
    {
        var query = db.ServiceRequests.AsNoTracking().Where(r => r.Status != (byte)ServiceRequestStatus.Draft);
        if (Status is { } s) query = query.Where(r => r.Status == (byte)s);
        if (!string.IsNullOrWhiteSpace(Q))
        {
            var q = Q.Trim();
            query = query.Where(r => r.Code.Contains(q) || r.Title.Contains(q));
        }
        Total = await query.CountAsync(ct);
        Rows = await query.OrderByDescending(r => r.CreatedAt).Skip((Math.Max(PageNumber, 1) - 1) * PageSize).Take(PageSize)
            .Select(r => new Row(r.Id, r.Code, r.Title, r.Description, r.ServiceCategory.Name, r.Customer.User.FullName, r.Customer.User.PhoneNumber,
                (ServiceRequestStatus)r.Status, r.Quotes.Count, r.ModerationNote ?? r.CancelReason,
                r.Order != null ? r.Order.Id : null, r.CreatedAt))
            .ToListAsync(ct);
    }

    public async Task<IActionResult> OnPostHideAsync(Guid id, string? reason, CancellationToken ct)
    {
        try
        {
            await requests.HideAsync(AdminActor.From(HttpContext), id, reason ?? "", ct);
            Flash = "Đã ẩn bài đăng, các báo giá đang chờ đã đóng.";
            return RedirectToPage(new { Status, Q, p = PageNumber });
        }
        catch (ApiException e)
        {
            Error = e.Message;
            await OnGetAsync(ct);
            return Page();
        }
    }
}
