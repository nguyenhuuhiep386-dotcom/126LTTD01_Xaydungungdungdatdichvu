using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.RazorPages;
using Servio.Api.Common;
using Servio.Api.Services.Admin;

namespace Servio.Api.Pages.Admin.Partners;

/// <summary>AW-04 — queue of partner profiles and skills waiting for review.</summary>
[Authorize(Policy = AuthPolicies.AdminOperator)]
public sealed class VerificationsModel(PartnerVerificationService verifications) : PageModel
{
    [BindProperty(SupportsGet = true)]
    public VerificationQueue Queue { get; set; } = VerificationQueue.Pending;

    public IReadOnlyList<VerificationRow> Rows { get; private set; } = [];

    public static readonly (VerificationQueue Queue, string Label)[] Tabs =
    [
        (VerificationQueue.Pending, "Hồ sơ chờ duyệt"),
        (VerificationQueue.PendingSkills, "Kỹ năng mới chờ duyệt"),
        (VerificationQueue.Rejected, "Đã từ chối"),
        (VerificationQueue.Approved, "Đã duyệt"),
    ];

    public async Task OnGetAsync(CancellationToken ct) => Rows = await verifications.ListAsync(Queue, ct);
}
