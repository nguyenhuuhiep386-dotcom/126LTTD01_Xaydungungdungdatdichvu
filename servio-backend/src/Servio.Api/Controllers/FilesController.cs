using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Servio.Api.Common;
using Servio.Api.Services.Files;

namespace Servio.Api.Controllers;

/// <summary>File upload (POST /files, replaces #35 in the course scope).</summary>
[Route("api/v1/files")]
[Authorize]
public sealed class FilesController(FileService files) : ApiControllerBase
{
    /// <summary>
    /// Upload one image (multipart/form-data: <c>file</c>, <c>purpose</c>). JPEG/PNG/WebP, max 5 MB.
    /// purpose: POST_IMAGE, AVATAR, KYC, ORDER_IMAGE, CHAT_IMAGE, REVIEW_IMAGE, DISPUTE_EVIDENCE, CERTIFICATE.
    /// KYC/CERTIFICATE/DISPUTE_EVIDENCE are private: the returned url is /api/v1/files/{id} and needs the owner's token.
    /// </summary>
    [HttpPost]
    [Consumes("multipart/form-data")]
    [RequestSizeLimit(6 * 1024 * 1024)]
    public async Task<ActionResult<ApiResponse<UploadedFileDto>>> Upload([FromForm] UploadFileForm form, CancellationToken ct) =>
        OkEnvelope(await files.UploadAsync(CurrentUserId, form.File, EnumText.Parse<FilePurpose>(form.Purpose, "purpose"), ct));

    /// <summary>Download a file you uploaded (used for private files).</summary>
    [HttpGet("{id:guid}")]
    [Produces("image/jpeg", "image/png", "image/webp")]
    public async Task<IActionResult> Download(Guid id, CancellationToken ct)
    {
        var (path, mimeType) = await files.OpenForOwnerAsync(CurrentUserId, id, ct);
        return PhysicalFile(path, mimeType);
    }
}

public sealed class UploadFileForm
{
    public IFormFile? File { get; set; }
    public string? Purpose { get; set; }
}
