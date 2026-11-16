using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.RazorPages;
using Servio.Api.Common;
using Servio.Api.Services.Admin;

namespace Servio.Api.Pages.Admin.Users;

/// <summary>AW-03 — users: search by name/phone, filter by role, lock and unlock.</summary>
[Authorize(Policy = AuthPolicies.AdminOperator)]
public sealed class IndexModel(AdminUserService users) : PageModel
{
    [BindProperty(SupportsGet = true)] public string? Q { get; set; }
    [BindProperty(SupportsGet = true)] public UserRoleType? Role { get; set; }
    [BindProperty(SupportsGet = true, Name = "p")] public int PageNumber { get; set; } = 1;

    public IReadOnlyList<AdminUserRow> Rows { get; private set; } = [];
    public int Total { get; private set; }
    public int Pages => (int)Math.Ceiling(Total / (double)AdminUserService.PageSize);

    [TempData] public string? Flash { get; set; }
    public string? Error { get; private set; }

    public async Task OnGetAsync(CancellationToken ct) => (Rows, Total) = await users.ListAsync(Q, Role, PageNumber, ct);

    public Task<IActionResult> OnPostLockAsync(Guid id, string? reason, CancellationToken ct) =>
        RunAsync("Đã khoá tài khoản.", () => users.LockAsync(AdminActor.From(HttpContext), id, reason ?? "", ct), ct);

    public Task<IActionResult> OnPostUnlockAsync(Guid id, CancellationToken ct) =>
        RunAsync("Đã mở khoá tài khoản.", () => users.UnlockAsync(AdminActor.From(HttpContext), id, ct), ct);

    private async Task<IActionResult> RunAsync(string success, Func<Task> action, CancellationToken ct)
    {
        try
        {
            await action();
            Flash = success;
            return RedirectToPage(new { Q, Role, p = PageNumber });
        }
        catch (ApiException e)
        {
            Error = e.Message;
            await OnGetAsync(ct);
            return Page();
        }
    }
}
