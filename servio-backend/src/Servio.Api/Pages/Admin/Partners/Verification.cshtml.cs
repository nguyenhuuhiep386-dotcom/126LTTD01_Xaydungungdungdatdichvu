using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.RazorPages;
using Servio.Api.Common;
using Servio.Api.Services.Admin;

namespace Servio.Api.Pages.Admin.Partners;

/// <summary>AW-04 — one partner: KYC images, skills, approve/reject (UC-01 step 8).</summary>
[Authorize(Policy = AuthPolicies.AdminOperator)]
public sealed class VerificationModel(PartnerVerificationService verifications) : PageModel
{
    public VerificationDetail Partner { get; private set; } = null!;

    [TempData]
    public string? Flash { get; set; }

    public string? Error { get; private set; }

    public async Task<IActionResult> OnGetAsync(Guid id, CancellationToken ct) => await LoadAsync(id, ct);

    public Task<IActionResult> OnPostApproveAsync(Guid id, bool approveSkills, CancellationToken ct) =>
        RunAsync(id, "Đã duyệt hồ sơ đối tác.", () => verifications.ApproveAsync(Actor, id, approveSkills, ct), ct);

    public Task<IActionResult> OnPostRejectAsync(Guid id, string? reason, Guid[] rejectedDocumentIds, CancellationToken ct) =>
        RunAsync(id, "Đã từ chối hồ sơ và gửi lý do cho đối tác.", () => verifications.RejectAsync(Actor, id, reason, rejectedDocumentIds, ct), ct);

    public Task<IActionResult> OnPostSkillAsync(Guid id, Guid skillId, bool approve, CancellationToken ct) =>
        RunAsync(id, approve ? "Đã duyệt kỹ năng." : "Đã từ chối kỹ năng.", () => verifications.DecideSkillAsync(Actor, id, skillId, approve, ct), ct);

    private AdminActor Actor => AdminActor.From(HttpContext);

    /// <summary>Runs a decision, then redirects (PRG) on success or re-renders with the error message.</summary>
    private async Task<IActionResult> RunAsync(Guid id, string success, Func<Task> action, CancellationToken ct)
    {
        try
        {
            await action();
            Flash = success;
            return RedirectToPage(new { id });
        }
        catch (ApiException e) when (e.StatusCode != StatusCodes.Status404NotFound)
        {
            Error = e.Message;
            return await LoadAsync(id, ct);
        }
    }

    private async Task<IActionResult> LoadAsync(Guid id, CancellationToken ct)
    {
        try
        {
            Partner = await verifications.GetAsync(id, ct);
            return Page();
        }
        catch (ApiException e) when (e.StatusCode == StatusCodes.Status404NotFound)
        {
            return NotFound();
        }
    }
}
