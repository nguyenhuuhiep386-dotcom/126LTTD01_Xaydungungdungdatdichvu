using System.ComponentModel.DataAnnotations;
using Microsoft.EntityFrameworkCore;
using Servio.Api.Common;
using Servio.Api.Data;
using Servio.Api.Data.Entities;

namespace Servio.Api.Services.Users;

/// <summary>
/// POST /users/me/consents. granted=false withdraws (Điều 10 Luật 91/2025). policyVersion = the version the user just read
/// (GET /legal); granting needs the current version.
/// </summary>
public sealed record ConsentRequest(
    ConsentPurpose Purpose,
    [Required(ErrorMessage = "Hãy chọn đồng ý hoặc không đồng ý")] bool? Granted,
    [Required, StringLength(20)] string PolicyVersion);

/// <summary>Current choice for one purpose. isCurrentVersion=false means the policy changed since: ask again.</summary>
public sealed record ConsentDto(ConsentPurpose Purpose, bool Granted, string PolicyVersion, bool IsCurrentVersion, DateTimeOffset At);

/// <summary>GET /legal: what the apps link to and which version a consent must refer to. URLs are relative to BASE_URL.</summary>
public sealed record LegalInfoDto(string PolicyVersion, string PrivacyPolicyUrl, string TermsUrl);

/// <summary>
/// Consent records (Điều 9 Luật 91/2025, Điều 6 NĐ 356/2025): asked separately per purpose, never implied, provable
/// (who, when, which version, from where) and withdrawable. <see cref="EnsureAsync"/> is called where the data is
/// collected: posting a request (terms + policy), KYC photos (sensitive data), sharing location.
/// </summary>
public sealed class ConsentService(ServioDbContext db, SystemConfigService configs, TimeProvider clock)
{
    public const string PolicyVersionKey = "privacy.policy_version";
    public const string DefaultPolicyVersion = "2026-11-15";
    public const string PrivacyPolicyPath = "/chinh-sach-bao-mat";
    public const string TermsPath = "/dieu-khoan-su-dung";

    public async Task<LegalInfoDto> GetLegalInfoAsync(CancellationToken ct) =>
        new(await CurrentVersionAsync(ct), PrivacyPolicyPath, TermsPath);

    public Task<string> CurrentVersionAsync(CancellationToken ct) => configs.GetStringAsync(PolicyVersionKey, DefaultPolicyVersion, ct);

    public async Task<IReadOnlyList<ConsentDto>> ListAsync(Guid userId, CancellationToken ct)
    {
        var current = await CurrentVersionAsync(ct);
        var rows = await db.UserConsents.AsNoTracking().Where(c => c.UserId == userId).ToListAsync(ct);
        return rows.GroupBy(c => c.Purpose)
            .Select(g => g.OrderByDescending(c => c.CreatedAt).ThenByDescending(c => c.Id).First())
            .OrderBy(c => c.Purpose)
            .Select(c => new ConsentDto((ConsentPurpose)c.Purpose, c.Granted, c.PolicyVersion, c.PolicyVersion == current, c.CreatedAt))
            .ToList();
    }

    public async Task<IReadOnlyList<ConsentDto>> RecordAsync(Guid userId, AppFlavor flavor, ConsentRequest input,
        string? ipAddress, string? userAgent, CancellationToken ct)
    {
        if (!Enum.IsDefined(input.Purpose))
        {
            throw new ApiException(StatusCodes.Status400BadRequest, ErrorCodes.ValidationError, "Mục đích không hợp lệ", "purpose");
        }
        if (input.Purpose is ConsentPurpose.KycIdentity or ConsentPurpose.LocationSharing && flavor != AppFlavor.Partner)
        {
            throw new ApiException(StatusCodes.Status400BadRequest, ErrorCodes.ValidationError, "Mục đích này chỉ dành cho app đối tác", "purpose");
        }
        var version = input.PolicyVersion.Trim();
        if (input.Granted == true && version != await CurrentVersionAsync(ct))
        {
            throw new ApiException(StatusCodes.Status409Conflict, ErrorCodes.ResourceVersionConflict,
                "Chính sách vừa được cập nhật, vui lòng đọc lại trước khi đồng ý", "policyVersion");
        }
        Add(userId, flavor, input.Purpose, input.Granted!.Value, version, ipAddress, userAgent);
        await db.SaveChangesAsync(ct);
        return await ListAsync(userId, ct);
    }

    /// <summary>Adds a consent row to the change tracker; the caller saves (used by account deletion and the demo seeder).</summary>
    public void Add(Guid userId, AppFlavor flavor, ConsentPurpose purpose, bool granted, string version, string? ipAddress = null, string? userAgent = null) =>
        db.UserConsents.Add(new UserConsent
        {
            Id = Guid.CreateVersion7(),
            UserId = userId,
            Purpose = (byte)purpose,
            Granted = granted,
            PolicyVersion = version,
            AppFlavor = (byte)flavor,
            IpAddress = ipAddress is { Length: > 45 } ? ipAddress[..45] : ipAddress,
            UserAgent = userAgent is { Length: > 300 } ? userAgent[..300] : userAgent,
            CreatedAt = clock.GetUtcNow(),
        });

    /// <summary>
    /// 422 CONSENT_REQUIRED unless the newest choice for <paramref name="purpose"/> is granted on the current policy version.
    /// privacy.require_consent=false switches the check off (only for an app build without the consent screens).
    /// </summary>
    public async Task EnsureAsync(Guid userId, ConsentPurpose purpose, CancellationToken ct)
    {
        if (!await configs.GetBoolAsync("privacy.require_consent", true, ct))
        {
            return;
        }
        var current = await CurrentVersionAsync(ct);
        var latest = await db.UserConsents.AsNoTracking()
            .Where(c => c.UserId == userId && c.Purpose == (byte)purpose)
            .OrderByDescending(c => c.CreatedAt).ThenByDescending(c => c.Id)
            .Select(c => new { c.Granted, c.PolicyVersion })
            .FirstOrDefaultAsync(ct);
        if (latest is { Granted: true } && latest.PolicyVersion == current)
        {
            return;
        }
        throw new ApiException(StatusCodes.Status422UnprocessableEntity, ErrorCodes.ConsentRequired, purpose switch
        {
            ConsentPurpose.TermsAndPrivacy => "Bạn cần đọc và đồng ý Điều khoản sử dụng và Chính sách bảo mật",
            ConsentPurpose.KycIdentity => "Bạn cần đồng ý cho Servio xử lý ảnh CCCD (dữ liệu cá nhân nhạy cảm) để xác minh hồ sơ",
            _ => "Bạn cần đồng ý chia sẻ vị trí để nhận việc và để khách theo dõi khi bạn di chuyển",
        });
    }
}
