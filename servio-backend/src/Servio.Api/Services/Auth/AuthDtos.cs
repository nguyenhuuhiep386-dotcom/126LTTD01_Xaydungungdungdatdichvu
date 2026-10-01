using System.ComponentModel.DataAnnotations;
using Servio.Api.Common;
using Servio.Api.Services.Users;

namespace Servio.Api.Services.Auth;

// #1 POST /auth/otp/request
public sealed record OtpRequest(
    [Required(ErrorMessage = "Vui lòng nhập số điện thoại")] string PhoneNumber,
    OtpPurpose Purpose,
    AppFlavor AppFlavor);

public sealed record OtpRequestResult(Guid OtpId, int ExpiresInSeconds, int ResendAfterSeconds, string MaskedPhone);

// #2 POST /auth/otp/verify
public sealed record OtpVerifyRequest(
    Guid OtpId,
    [Required, StringLength(6, MinimumLength = 6, ErrorMessage = "Mã OTP gồm 6 chữ số")] string Code,
    [Required, StringLength(200)] string DeviceId,
    [StringLength(500)] string? FcmToken,
    AppFlavor AppFlavor);

public sealed record AuthResult(
    string AccessToken,
    string RefreshToken,
    int ExpiresIn,
    MeDto User,
    bool IsNewUser,
    bool IsNewRole,
    bool NeedsProfileCompletion);

// #5 POST /auth/refresh
public sealed record RefreshRequest([Required] string RefreshToken);

public sealed record RefreshResult(string AccessToken, string RefreshToken, int ExpiresIn);

// #6 POST /auth/logout
public sealed record LogoutRequest([Required, StringLength(200)] string DeviceId, AppFlavor AppFlavor);
