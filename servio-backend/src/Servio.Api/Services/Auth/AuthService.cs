using System.Security.Cryptography;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;
using Servio.Api.Common;
using Servio.Api.Data;
using Servio.Api.Data.Entities;
using Servio.Api.Services.Users;

namespace Servio.Api.Services.Auth;

/// <summary>M1 authentication: OTP login (#1, #2), refresh rotation (#5) and logout (#6).</summary>
public sealed class AuthService(
    ServioDbContext db,
    TokenService tokens,
    UserService users,
    IOptions<OtpOptions> otpOptions,
    TimeProvider clock,
    IHostEnvironment env,
    ILogger<AuthService> logger)
{
    private readonly OtpOptions _otp = otpOptions.Value;

    public async Task<OtpRequestResult> RequestOtpAsync(OtpRequest request, CancellationToken ct)
    {
        EnsureDefined(request.AppFlavor, "appFlavor");
        EnsureDefined(request.Purpose, "purpose");
        var phone = PhoneNumber.Normalize(request.PhoneNumber)
                    ?? throw new ApiException(StatusCodes.Status400BadRequest, ErrorCodes.ValidationError, "Số điện thoại không hợp lệ", "phoneNumber");
        var now = clock.GetUtcNow();

        var recent = await db.OtpCodes
            .Where(o => o.PhoneNumber == phone && o.CreatedAt > now.AddMinutes(-10))
            .Select(o => o.CreatedAt)
            .ToListAsync(ct);
        if (recent.Count >= _otp.MaxRequestsPer10Minutes ||
            recent.Any(createdAt => createdAt > now.AddSeconds(-_otp.ResendAfterSeconds)))
        {
            throw new ApiException(StatusCodes.Status429TooManyRequests, ErrorCodes.OtpRateLimited, "Bạn đã yêu cầu OTP quá nhiều lần, vui lòng thử lại sau");
        }

        var code = string.IsNullOrEmpty(_otp.FixedCode)
            ? RandomNumberGenerator.GetInt32(0, 1_000_000).ToString("D6")
            : _otp.FixedCode;

        var otp = new OtpCode
        {
            Id = Guid.CreateVersion7(),
            PhoneNumber = phone,
            CodeHash = TokenService.Hash($"{phone}:{code}"),
            Purpose = (byte)request.Purpose,
            AppFlavor = (byte)request.AppFlavor,
            ExpiresAt = now.AddSeconds(_otp.ExpirySeconds),
            CreatedAt = now,
        };
        db.OtpCodes.Add(otp);
        await db.SaveChangesAsync(ct);

        // ponytail: no SMS provider yet. Random codes are only visible in the Development log;
        // plug an SMS sender here when the project leaves the fixed-code demo mode.
        if (string.IsNullOrEmpty(_otp.FixedCode) && env.IsDevelopment())
        {
            logger.LogInformation("OTP for {MaskedPhone}: {Code}", PhoneNumber.Mask(phone), code);
        }

        return new OtpRequestResult(otp.Id, _otp.ExpirySeconds, _otp.ResendAfterSeconds, PhoneNumber.Mask(phone));
    }

    public async Task<AuthResult> VerifyOtpAsync(OtpVerifyRequest request, CancellationToken ct)
    {
        EnsureDefined(request.AppFlavor, "appFlavor");
        var now = clock.GetUtcNow();
        var otp = await db.OtpCodes.FirstOrDefaultAsync(o => o.Id == request.OtpId, ct)
                  ?? throw new ApiException(StatusCodes.Status400BadRequest, ErrorCodes.OtpInvalid, "Mã OTP không đúng");

        if (otp.IsUsed || otp.ExpiresAt <= now)
        {
            throw new ApiException(StatusCodes.Status400BadRequest, ErrorCodes.OtpExpired, "Mã OTP đã hết hạn, vui lòng gửi lại");
        }
        if (otp.AttemptCount >= _otp.MaxAttempts)
        {
            throw new ApiException(StatusCodes.Status429TooManyRequests, ErrorCodes.OtpRateLimited, "Nhập sai quá nhiều lần, vui lòng gửi lại mã");
        }
        if (otp.AppFlavor != (byte)request.AppFlavor ||
            !CryptographicOperations.FixedTimeEquals(
                Convert.FromHexString(otp.CodeHash),
                Convert.FromHexString(TokenService.Hash($"{otp.PhoneNumber}:{request.Code}"))))
        {
            otp.AttemptCount++;
            await db.SaveChangesAsync(ct);
            throw new ApiException(StatusCodes.Status400BadRequest, ErrorCodes.OtpInvalid, "Mã OTP không đúng", "code");
        }

        otp.IsUsed = true;
        otp.ConsumedAt = now;

        var (user, isNewUser, isNewRole) = await FindOrCreateUserAsync(otp.PhoneNumber, request.AppFlavor, now, ct);
        EnsureActive(user, now);
        user.LastLoginAt = now;
        await UpsertDeviceAsync(user.Id, request.DeviceId, request.FcmToken, request.AppFlavor, now, ct);

        var sessionId = Guid.CreateVersion7();
        var (refreshToken, _) = AddRefreshToken(user.Id, sessionId, request.DeviceId, request.AppFlavor, now);
        await db.SaveChangesAsync(ct);

        return new AuthResult(
            tokens.CreateAccessToken(user.Id, sessionId, request.AppFlavor),
            refreshToken,
            tokens.AccessTokenSeconds,
            await users.GetMeAsync(user.Id, ct),
            isNewUser,
            isNewRole,
            UserService.NeedsProfileCompletion(user.FullName));
    }

    public async Task<RefreshResult> RefreshAsync(RefreshRequest request, CancellationToken ct)
    {
        var now = clock.GetUtcNow();
        var hash = TokenService.Hash(request.RefreshToken);
        var current = await db.RefreshTokens
            .Include(t => t.User)
            .FirstOrDefaultAsync(t => t.TokenHash == hash, ct);

        // ponytail: rotation only; reuse of an old token is rejected but does not revoke the whole family (spec 0.2.3, F-AUTH-05).
        if (current is null || current.RevokedAt is not null || current.ExpiresAt <= now)
        {
            throw new ApiException(StatusCodes.Status401Unauthorized, ErrorCodes.RefreshTokenInvalid, "Phiên đăng nhập đã hết hạn, vui lòng đăng nhập lại");
        }
        EnsureActive(current.User, now);

        var flavor = (AppFlavor)current.AppFlavor;
        var (newToken, newTokenId) = AddRefreshToken(current.UserId, current.SessionId, current.DeviceId, flavor, now);
        current.RevokedAt = now;
        current.ReplacedByTokenId = newTokenId;
        await db.SaveChangesAsync(ct);

        return new RefreshResult(
            tokens.CreateAccessToken(current.UserId, current.SessionId, flavor),
            newToken,
            tokens.AccessTokenSeconds);
    }

    /// <summary>Revokes this login session (spec #6) and stops push to the device.</summary>
    /// <remarks>ponytail: the access token stays valid until it expires (max 15 minutes); add a session check in
    /// JwtBearerEvents.OnTokenValidated if instant revocation is ever needed.</remarks>
    public async Task LogoutAsync(Guid userId, Guid sessionId, AppFlavor tokenFlavor, LogoutRequest request, CancellationToken ct)
    {
        if (request.AppFlavor != tokenFlavor)
        {
            throw ApiException.Forbidden("Phiên đăng nhập không khớp ứng dụng");
        }

        var now = clock.GetUtcNow();
        await db.RefreshTokens
            .Where(t => t.UserId == userId && t.SessionId == sessionId && t.RevokedAt == null)
            .ExecuteUpdateAsync(s => s.SetProperty(t => t.RevokedAt, now), ct);
        await db.UserDevices
            .Where(d => d.UserId == userId && d.DeviceId == request.DeviceId && d.AppFlavor == (byte)request.AppFlavor)
            .ExecuteUpdateAsync(s => s.SetProperty(d => d.IsActive, false), ct);
    }

    private async Task<(User User, bool IsNewUser, bool IsNewRole)> FindOrCreateUserAsync(
        string phone, AppFlavor flavor, DateTimeOffset now, CancellationToken ct)
    {
        var user = await db.Users
            .Include(u => u.UserRoles)
            .Include(u => u.CustomerProfile)
            .Include(u => u.PartnerProfile)
            .Include(u => u.Wallet)
            .FirstOrDefaultAsync(u => u.PhoneNumber == phone, ct);

        var isNewUser = user is null;
        if (user is null)
        {
            user = new User { Id = Guid.CreateVersion7(), PhoneNumber = phone, FullName = "", CreatedAt = now, UpdatedAt = now };
            db.Users.Add(user);
        }
        user.IsPhoneVerified = true;

        var role = TokenService.RoleFor(flavor);
        var isNewRole = user.UserRoles.All(r => r.Role != (byte)role);
        if (isNewRole)
        {
            user.UserRoles.Add(new UserRole { Id = Guid.CreateVersion7(), Role = (byte)role, CreatedAt = now });
        }

        if (role == UserRoleType.Customer && user.CustomerProfile is null)
        {
            user.CustomerProfile = new CustomerProfile { Id = Guid.CreateVersion7(), CreatedAt = now, UpdatedAt = now };
        }
        if (role == UserRoleType.Partner && user.PartnerProfile is null)
        {
            user.PartnerProfile = new PartnerProfile { Id = Guid.CreateVersion7(), ServiceRadiusKm = 10, CreatedAt = now, UpdatedAt = now };
        }
        user.Wallet ??= new Wallet { Id = Guid.CreateVersion7(), Currency = "VND", UpdatedAt = now };

        return (user, isNewUser, isNewRole);
    }

    /// <summary>JSON enums also accept raw numbers; reject values that are not defined (e.g. appFlavor 0 or 99).</summary>
    private static void EnsureDefined<TEnum>(TEnum value, string field) where TEnum : struct, Enum
    {
        if (!Enum.IsDefined(value))
        {
            throw new ApiException(StatusCodes.Status400BadRequest, ErrorCodes.ValidationError, "Giá trị không hợp lệ", field);
        }
    }

    private static void EnsureActive(User user, DateTimeOffset now)
    {
        var status = (UserStatus)user.Status;
        var suspended = status == UserStatus.Suspended && (user.SuspendedUntil is null || user.SuspendedUntil > now);
        if (status is UserStatus.Banned or UserStatus.Deleted || suspended)
        {
            throw new ApiException(StatusCodes.Status403Forbidden, ErrorCodes.AccountLocked, "Tài khoản đã bị khoá, vui lòng liên hệ hỗ trợ");
        }
    }

    private async Task UpsertDeviceAsync(Guid userId, string deviceId, string? fcmToken, AppFlavor flavor, DateTimeOffset now, CancellationToken ct)
    {
        var device = await db.UserDevices.FirstOrDefaultAsync(
            d => d.UserId == userId && d.DeviceId == deviceId && d.AppFlavor == (byte)flavor, ct);
        if (device is null)
        {
            device = new UserDevice { Id = Guid.CreateVersion7(), UserId = userId, DeviceId = deviceId, AppFlavor = (byte)flavor, Platform = 1 };
            db.UserDevices.Add(device);
        }
        device.FcmToken = fcmToken ?? device.FcmToken;
        device.IsActive = true;
        device.LastActiveAt = now;
    }

    private (string Token, Guid Id) AddRefreshToken(Guid userId, Guid sessionId, string deviceId, AppFlavor flavor, DateTimeOffset now)
    {
        var token = TokenService.CreateRefreshToken();
        var id = Guid.CreateVersion7();
        db.RefreshTokens.Add(new RefreshToken
        {
            Id = id,
            UserId = userId,
            TokenHash = TokenService.Hash(token),
            SessionId = sessionId,
            DeviceId = deviceId,
            AppFlavor = (byte)flavor,
            ExpiresAt = tokens.RefreshTokenExpiry(),
            CreatedAt = now,
        });
        return (token, id);
    }
}
