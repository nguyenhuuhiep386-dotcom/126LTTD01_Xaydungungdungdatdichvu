using System.Security.Claims;
using System.Security.Cryptography;
using System.Text;
using Microsoft.Extensions.Options;
using Microsoft.IdentityModel.JsonWebTokens;
using Microsoft.IdentityModel.Tokens;
using Servio.Api.Common;

namespace Servio.Api.Services.Auth;

/// <summary>Creates JWT access tokens and opaque refresh tokens. Refresh tokens are stored only as SHA-256 hashes.</summary>
public sealed class TokenService(IOptions<JwtOptions> options, TimeProvider clock)
{
    public const string SessionClaim = "sid";
    public const string FlavorClaim = "flavor";
    public const string RoleClaim = "role";

    private readonly JwtOptions _options = options.Value;

    public int AccessTokenSeconds => _options.AccessTokenMinutes * 60;

    public DateTimeOffset RefreshTokenExpiry() => clock.GetUtcNow().AddDays(_options.RefreshTokenDays);

    /// <summary>
    /// The token carries only the role of the app it was issued for: a customer-app token never passes the Partner
    /// policy, even when the same user is also a partner. The caller must ensure the user has this role.
    /// </summary>
    public string CreateAccessToken(Guid userId, Guid sessionId, AppFlavor flavor)
    {
        var claims = new List<Claim>
        {
            new(JwtRegisteredClaimNames.Sub, userId.ToString()),
            new(SessionClaim, sessionId.ToString()),
            new(FlavorClaim, flavor.ToString()),
            new(RoleClaim, RoleFor(flavor).ToString()),
        };

        var now = clock.GetUtcNow().UtcDateTime;
        return new JsonWebTokenHandler().CreateToken(new SecurityTokenDescriptor
        {
            Issuer = _options.Issuer,
            Audience = _options.Audience,
            Subject = new ClaimsIdentity(claims),
            IssuedAt = now,
            NotBefore = now,
            Expires = now.AddMinutes(_options.AccessTokenMinutes),
            SigningCredentials = new SigningCredentials(SigningKey(_options), SecurityAlgorithms.HmacSha256),
        });
    }

    public static UserRoleType RoleFor(AppFlavor flavor) => flavor switch
    {
        AppFlavor.Customer => UserRoleType.Customer,
        AppFlavor.Partner => UserRoleType.Partner,
        _ => throw new ArgumentOutOfRangeException(nameof(flavor), flavor, "Unknown app flavor"),
    };

    public static string CreateRefreshToken() => Base64UrlEncoder.Encode(RandomNumberGenerator.GetBytes(48));

    public static string Hash(string value) => Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(value)));

    public static SymmetricSecurityKey SigningKey(JwtOptions options) => new(Encoding.UTF8.GetBytes(options.SigningKey));
}
