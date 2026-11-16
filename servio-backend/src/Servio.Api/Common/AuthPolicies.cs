namespace Servio.Api.Common;

/// <summary>Use with [Authorize(Policy = AuthPolicies.Customer)] etc.</summary>
public static class AuthPolicies
{
    public const string Customer = "Customer";
    public const string Partner = "Partner";
    public const string Admin = "Admin";

    /// <summary>Admin pages that change partners or the catalog, and KYC images: SUPER_ADMIN and OPERATOR only (F-PROF-04).</summary>
    public const string AdminOperator = "AdminOperator";

    /// <summary>AW-08 disputes: SUPER_ADMIN, OPERATOR and SUPPORT.</summary>
    public const string AdminSupport = "AdminSupport";

    /// <summary>AW-18 debt payments: SUPER_ADMIN and FINANCE.</summary>
    public const string AdminFinance = "AdminFinance";

    /// <summary>AW-15 system configuration: SUPER_ADMIN only.</summary>
    public const string AdminSuper = "AdminSuper";

    /// <summary>Cookie scheme of the admin Razor Pages (separate from the apps' JWT).</summary>
    public const string AdminScheme = "AdminCookie";
}
