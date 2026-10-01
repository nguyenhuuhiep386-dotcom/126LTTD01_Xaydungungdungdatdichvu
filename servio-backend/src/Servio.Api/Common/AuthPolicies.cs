namespace Servio.Api.Common;

/// <summary>Use with [Authorize(Policy = AuthPolicies.Customer)] etc.</summary>
public static class AuthPolicies
{
    public const string Customer = "Customer";
    public const string Partner = "Partner";
    public const string Admin = "Admin";

    /// <summary>Cookie scheme of the admin Razor Pages (separate from the apps' JWT).</summary>
    public const string AdminScheme = "AdminCookie";
}
