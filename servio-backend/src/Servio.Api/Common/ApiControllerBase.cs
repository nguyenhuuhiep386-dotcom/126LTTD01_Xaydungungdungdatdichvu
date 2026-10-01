using System.Security.Claims;
using Microsoft.IdentityModel.JsonWebTokens;
using Microsoft.AspNetCore.Mvc;
using Servio.Api.Services.Auth;

namespace Servio.Api.Common;

/// <summary>Base class for /api/v1 controllers: envelope helpers and the current user from the JWT.</summary>
[ApiController]
[Produces("application/json")]
public abstract class ApiControllerBase : ControllerBase
{
    protected Guid CurrentUserId =>
        Guid.Parse(User.FindFirstValue(JwtRegisteredClaimNames.Sub)
                   ?? throw new ApiException(StatusCodes.Status401Unauthorized, ErrorCodes.Unauthenticated, "Chưa đăng nhập"));

    protected Guid CurrentSessionId => Guid.Parse(User.FindFirstValue(TokenService.SessionClaim)!);

    protected AppFlavor CurrentAppFlavor => Enum.Parse<AppFlavor>(User.FindFirstValue(TokenService.FlavorClaim)!);

    protected ActionResult<ApiResponse<T>> OkEnvelope<T>(T data, string? message = null) =>
        Ok(ApiResponse.Ok(HttpContext, data, message));

    /// <summary>201 Created with the envelope, for endpoints that create a resource (#36, #50).</summary>
    protected ActionResult<ApiResponse<T>> CreatedEnvelope<T>(T data) =>
        StatusCode(StatusCodes.Status201Created, ApiResponse.Ok(HttpContext, data));
}
