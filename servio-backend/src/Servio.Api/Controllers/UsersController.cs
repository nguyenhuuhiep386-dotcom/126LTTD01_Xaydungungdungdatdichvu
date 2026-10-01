using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Servio.Api.Common;
using Servio.Api.Services.Users;

namespace Servio.Api.Controllers;

/// <summary>M1 — current user profile (#9, #10).</summary>
[Route("api/v1/users")]
[Authorize]
public sealed class UsersController(UserService users) : ApiControllerBase
{
    /// <summary>#9 Current user with roles and profiles.</summary>
    [HttpGet("me")]
    public async Task<ActionResult<ApiResponse<MeDto>>> GetMe(CancellationToken ct) =>
        OkEnvelope(await users.GetMeAsync(CurrentUserId, ct));

    /// <summary>#10 Update name, avatar, birthday, gender or email. Null fields are left unchanged.</summary>
    [HttpPatch("me")]
    public async Task<ActionResult<ApiResponse<MeDto>>> UpdateMe(UpdateMeRequest request, CancellationToken ct) =>
        OkEnvelope(await users.UpdateMeAsync(CurrentUserId, request, ct));
}
