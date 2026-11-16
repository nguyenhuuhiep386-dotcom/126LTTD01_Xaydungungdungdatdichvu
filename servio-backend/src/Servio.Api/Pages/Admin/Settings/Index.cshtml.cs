using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.RazorPages;
using Microsoft.EntityFrameworkCore;
using Servio.Api.Common;
using Servio.Api.Data;
using Servio.Api.Data.Entities;
using Servio.Api.Services;
using Servio.Api.Services.Admin;

namespace Servio.Api.Pages.Admin.Settings;

/// <summary>AW-15 — system parameters (SystemConfigs, spec 5.2.7): commission, fees, timeouts, check-in limits. SUPER_ADMIN only.</summary>
[Authorize(Policy = AuthPolicies.AdminSuper)]
public sealed class IndexModel(ServioDbContext db, SystemConfigService configs) : PageModel
{
    public IReadOnlyList<IGrouping<string, SystemConfig>> Groups { get; private set; } = [];
    [TempData] public string? Flash { get; set; }
    public string? Error { get; private set; }

    public static string GroupLabel(string group) => group switch
    {
        "ORDER" => "Đơn hàng",
        "PAYMENT" => "Tiền và công nợ",
        "MATCHING" => "Ghép đối tác",
        "APP" => "Ứng dụng",
        "PRIVACY" => "Dữ liệu cá nhân",
        _ => group,
    };

    public async Task OnGetAsync(CancellationToken ct) =>
        Groups = (await db.SystemConfigs.AsNoTracking().OrderBy(c => c.Group).ThenBy(c => c.Key).ToListAsync(ct))
            .GroupBy(c => c.Group).ToList();

    public async Task<IActionResult> OnPostAsync(string key, string? value, CancellationToken ct)
    {
        try
        {
            await configs.UpdateAsync(AdminActor.From(HttpContext), key, value, ct);
            Flash = $"Đã lưu {key} = {value?.Trim()}.";
            return RedirectToPage();
        }
        catch (ApiException e)
        {
            Error = e.Message;
            await OnGetAsync(ct);
            return Page();
        }
    }
}
