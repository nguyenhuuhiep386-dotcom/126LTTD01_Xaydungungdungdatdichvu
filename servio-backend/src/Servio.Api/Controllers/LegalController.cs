using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Servio.Api.Common;
using Servio.Api.Services.Users;

namespace Servio.Api.Controllers;

/// <summary>Legal documents for the apps' consent screens. Public.</summary>
[AllowAnonymous]
[Route("api/v1/legal")]
public sealed class LegalController(ConsentService consents) : ApiControllerBase
{
    /// <summary>
    /// Current policy version (send it back in POST /users/me/consents) and the pages to open in a WebView:
    /// BASE_URL + privacyPolicyUrl (/chinh-sach-bao-mat), BASE_URL + termsUrl (/dieu-khoan-su-dung).
    /// </summary>
    [HttpGet]
    public async Task<ActionResult<ApiResponse<LegalInfoDto>>> Get(CancellationToken ct) =>
        OkEnvelope(await consents.GetLegalInfoAsync(ct));
}
