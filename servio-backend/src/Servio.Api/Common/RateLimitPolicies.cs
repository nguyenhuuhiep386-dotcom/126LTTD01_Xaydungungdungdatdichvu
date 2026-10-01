namespace Servio.Api.Common;

public static class RateLimitPolicies
{
    /// <summary>OTP endpoints: 10 requests per minute per client IP (per-phone limits live in AuthService).</summary>
    public const string Otp = "otp";

    /// <summary>Admin sign-in (AW-01): 5 attempts per minute per client IP against password guessing.</summary>
    public const string AdminLogin = "admin-login";
}
