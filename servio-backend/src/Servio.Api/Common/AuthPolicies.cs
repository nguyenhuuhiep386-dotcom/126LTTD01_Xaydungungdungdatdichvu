namespace Servio.Api.Common;

/// <summary>Use with [Authorize(Policy = AuthPolicies.Customer)] etc.</summary>
public static class AuthPolicies
{
    public const string Customer = "Customer";
    public const string Partner = "Partner";
    public const string Admin = "Admin";

    /// <summary>Admin pages that change partners or the catalog, and KYC images: SUPER_ADMIN and OPERATOR only (F-PROF-04).</summary>
    public const string AdminOperator = "AdminOperator";

    /// <summary>Cookie scheme of the admin Razor Pages (separate from the apps' JWT).</summary>
    public const string AdminScheme = "AdminCookie";
}
