using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.RazorPages;
using Servio.Api.Common;
using Servio.Api.Services.Admin;
using Servio.Api.Services.Files;

namespace Servio.Api.Pages.Admin;

/// <summary>/admin/files/{id}: serves uploaded files (incl. private KYC images) to SUPER_ADMIN/OPERATOR, audited.</summary>
[Authorize(Policy = AuthPolicies.AdminOperator)]
public sealed class FilesModel(PartnerVerificationService verifications, FileService files) : PageModel
{
    public async Task<IActionResult> OnGetAsync(Guid id, CancellationToken ct)
    {
        try
        {
            var file = await verifications.GetFileForReviewAsync(AdminActor.From(HttpContext), id, ct);
            var path = files.PhysicalPath(file);
            Response.Headers.CacheControl = "private, no-store";
            return System.IO.File.Exists(path) ? PhysicalFile(path, file.MimeType) : NotFound();
        }
        catch (ApiException e) when (e.StatusCode == StatusCodes.Status404NotFound)
        {
            return NotFound();
        }
    }
}
