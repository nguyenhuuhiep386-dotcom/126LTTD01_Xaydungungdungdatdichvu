namespace Servio.Api.Services.Auth;

public sealed class JwtOptions
{
    public const string Section = "Jwt";

    public string Issuer { get; set; } = "servio-api";
    public string Audience { get; set; } = "servio-apps";

    /// <summary>HMAC key, at least 32 characters. Set via environment variable Jwt__SigningKey outside Development/Demo.</summary>
    public string SigningKey { get; set; } = "";

    public int AccessTokenMinutes { get; set; } = 15;
    public int RefreshTokenDays { get; set; } = 30;
}

public sealed class OtpOptions
{
    public const string Section = "Otp";

    /// <summary>Fixed OTP (e.g. 123456). Only allowed in Development/Demo; startup fails if set elsewhere.</summary>
    public string? FixedCode { get; set; }

    public int ExpirySeconds { get; set; } = 300;
    public int ResendAfterSeconds { get; set; } = 60;
    public int MaxAttempts { get; set; } = 5;

    /// <summary>Max OTP requests per phone number in a 10-minute window.</summary>
    public int MaxRequestsPer10Minutes { get; set; } = 5;
}
