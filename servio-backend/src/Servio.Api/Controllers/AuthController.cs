using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.RateLimiting;
using Servio.Api.Common;
using Servio.Api.Services.Auth;

namespace Servio.Api.Controllers;

/// <summary>M1 — authentication (#1, #2, #5, #6).</summary>
[Route("api/v1/auth")]
public sealed class AuthController(AuthService auth) : ApiControllerBase
{
    /// <summary>#1 Send an OTP. In Development/Demo the code is always Otp:FixedCode (123456).</summary>
    [HttpPost("otp/request")]
    [AllowAnonymous]
    [EnableRateLimiting(RateLimitPolicies.Otp)]
    public async Task<ActionResult<ApiResponse<OtpRequestResult>>> RequestOtp(OtpRequest request, CancellationToken ct) =>
        OkEnvelope(await auth.RequestOtpAsync(request, ct));

    /// <summary>#2 Verify the OTP. Creates the user/role/profile on first login and returns tokens.</summary>
    [HttpPost("otp/verify")]
    [AllowAnonymous]
    [EnableRateLimiting(RateLimitPolicies.Otp)]
    public async Task<ActionResult<ApiResponse<AuthResult>>> VerifyOtp(OtpVerifyRequest request, CancellationToken ct) =>
        OkEnvelope(await auth.VerifyOtpAsync(request, ct));

    /// <summary>#5 Exchange a refresh token for a new pair (the old refresh token is revoked).</summary>
    [HttpPost("refresh")]
    [AllowAnonymous]
    public async Task<ActionResult<ApiResponse<RefreshResult>>> Refresh(RefreshRequest request, CancellationToken ct) =>
        OkEnvelope(await auth.RefreshAsync(request, ct));

    /// <summary>#6 Log out this session on this app.</summary>
    [HttpPost("logout")]
    [Authorize]
    [ProducesResponseType(StatusCodes.Status204NoContent)]
    public async Task<IActionResult> Logout(LogoutRequest request, CancellationToken ct)
    {
        await auth.LogoutAsync(CurrentUserId, CurrentSessionId, CurrentAppFlavor, request, ct);
        return NoContent();
    }
}
