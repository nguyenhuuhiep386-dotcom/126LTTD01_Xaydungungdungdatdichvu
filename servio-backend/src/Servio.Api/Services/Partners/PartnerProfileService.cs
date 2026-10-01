using Microsoft.EntityFrameworkCore;
using Servio.Api.Common;
using Servio.Api.Data;
using Servio.Api.Data.Entities;
using Servio.Api.Services.Files;
using Servio.Api.Services.Users;

namespace Servio.Api.Services.Partners;

/// <summary>
/// M1 partner profile: #17 me, #18 update, #19 KYC documents, #20 submit for review, #25 online, #26 heartbeat.
/// Signup screens PS-02..PS-06 call these in order, then wait for an admin decision on AW-04.
/// </summary>
public sealed class PartnerProfileService(
    ServioDbContext db,
    FileService files,
    SystemConfigService configs,
    TimeProvider clock)
{
    public static readonly int[] AllowedRadiusKm = [3, 5, 10, 20];

    private static readonly DocumentType[] RequiredDocuments = [DocumentType.IdFront, DocumentType.IdBack, DocumentType.SelfieWithId];

    public async Task<PartnerMeDto> GetMeAsync(Guid userId, CancellationToken ct)
    {
        var partner = await db.PartnerProfiles.AsNoTracking()
            .Include(p => p.User)
            .Include(p => p.PartnerDocuments)
            .Include(p => p.PartnerSkills).ThenInclude(s => s.ServiceCategory).ThenInclude(c => c.Parent)
            .FirstOrDefaultAsync(p => p.UserId == userId, ct)
            ?? throw ApiException.NotFound("Không tìm thấy hồ sơ đối tác");

        var timeout = await configs.GetIntAsync(SystemConfigService.PartnerOfflineTimeoutMinutes, 10, ct);
        return new PartnerMeDto(
            partner.Id,
            partner.UserId,
            partner.User.FullName,
            partner.User.AvatarUrl,
            partner.User.PhoneNumber,
            partner.Bio,
            partner.YearsOfExperience,
            (PartnerVerificationStatus)partner.VerificationStatus,
            partner.VerificationNote,
            IsEffectivelyOnline(partner, clock.GetUtcNow(), timeout),
            partner.LastHeartbeatAt,
            partner.ServiceRadiusKm,
            partner.AnchorLatitude,
            partner.AnchorLongitude,
            partner.AverageRating,
            partner.TotalReviews,
            partner.CompletedOrders,
            partner.CancelledOrders,
            partner.PartnerDocuments.OrderBy(d => d.DocumentType).Select(ToDto).ToList(),
            partner.PartnerSkills.OrderBy(s => s.CreatedAt).Select(PartnerSkillService.ToDto).ToList(),
            MissingForVerification(partner));
    }

    public async Task<PartnerMeDto> UpdateAsync(Guid userId, UpdatePartnerRequest request, CancellationToken ct)
    {
        var partner = await FindAsync(userId, ct);
        if (request.ServiceRadiusKm is { } radius && !AllowedRadiusKm.Contains(radius))
        {
            throw new ApiException(StatusCodes.Status400BadRequest, ErrorCodes.ValidationError, "Bán kính chỉ nhận 3, 5, 10 hoặc 20 km", "serviceRadiusKm");
        }
        if (request.AnchorLatitude is not null || request.AnchorLongitude is not null)
        {
            (partner.AnchorLatitude, partner.AnchorLongitude) = Geo.Require(request.AnchorLatitude, request.AnchorLongitude, "anchorLatitude");
        }

        partner.Bio = request.Bio is null ? partner.Bio : request.Bio.Trim();
        partner.YearsOfExperience = request.YearsOfExperience ?? partner.YearsOfExperience;
        partner.ServiceRadiusKm = request.ServiceRadiusKm ?? partner.ServiceRadiusKm;
        partner.UpdatedAt = clock.GetUtcNow();
        await db.SaveChangesAsync(ct);
        return await GetMeAsync(userId, ct);
    }

    /// <summary>#19: one current document per type; uploading again replaces a PENDING/REJECTED one.</summary>
    public async Task<PartnerDocumentDto> AddDocumentAsync(Guid userId, AddDocumentRequest request, CancellationToken ct)
    {
        if (!Enum.IsDefined(request.DocumentType))
        {
            throw new ApiException(StatusCodes.Status400BadRequest, ErrorCodes.ValidationError, "Loại giấy tờ không hợp lệ", "documentType");
        }
        var partner = await FindAsync(userId, ct, includeDocuments: true);
        EnsureEditable(partner);

        var purposes = request.DocumentType == DocumentType.Certificate
            ? new[] { FilePurpose.Certificate, FilePurpose.Kyc }
            : [FilePurpose.Kyc];
        var file = await files.ResolveOwnedAsync(userId, request.FileUrl, "fileUrl", ct, purposes);

        db.PartnerDocuments.RemoveRange(partner.PartnerDocuments.Where(d =>
            d.DocumentType == (byte)request.DocumentType && d.Status != (byte)ApprovalStatus.Approved));
        var document = new PartnerDocument
        {
            Id = Guid.CreateVersion7(),
            PartnerProfileId = partner.Id,
            DocumentType = (byte)request.DocumentType,
            FileUrl = FileService.UrlOf(file),
            Status = (byte)ApprovalStatus.Pending,
            CreatedAt = clock.GetUtcNow(),
        };
        db.PartnerDocuments.Add(document);
        await db.SaveChangesAsync(ct);
        return ToDto(document);
    }

    /// <summary>#20: NOT_SUBMITTED/REJECTED → PENDING when name, 3 KYC photos, ≥1 skill and the anchor location are present.</summary>
    public async Task<VerificationStatusDto> SubmitAsync(Guid userId, CancellationToken ct)
    {
        var partner = await FindAsync(userId, ct, includeDocuments: true);
        EnsureEditable(partner);
        await db.Entry(partner).Reference(p => p.User).LoadAsync(ct);
        await db.Entry(partner).Collection(p => p.PartnerSkills).LoadAsync(ct);

        var missing = MissingForVerification(partner);
        if (missing.Count > 0)
        {
            throw new ApiException(StatusCodes.Status422UnprocessableEntity, ErrorCodes.ProfileIncomplete,
                "Hồ sơ còn thiếu: " + string.Join(", ", missing));
        }

        partner.VerificationStatus = (byte)PartnerVerificationStatus.Pending;
        partner.VerificationNote = null;
        partner.UpdatedAt = clock.GetUtcNow();
        await db.SaveChangesAsync(ct);
        return new VerificationStatusDto(PartnerVerificationStatus.Pending);
    }

    /// <summary>#25: going online needs an APPROVED profile and a valid location; going offline needs nothing.</summary>
    public async Task<OnlineStatusDto> SetOnlineAsync(Guid userId, OnlineStatusRequest request, CancellationToken ct)
    {
        var partner = await FindAsync(userId, ct);
        var now = clock.GetUtcNow();
        if (request.IsOnline)
        {
            if (partner.VerificationStatus != (byte)PartnerVerificationStatus.Approved)
            {
                throw new ApiException(StatusCodes.Status403Forbidden, ErrorCodes.PartnerNotVerified, "Hồ sơ đối tác chưa được duyệt");
            }
            (partner.CurrentLatitude, partner.CurrentLongitude) = Geo.Require(request.Latitude, request.Longitude);
            partner.LastHeartbeatAt = now;
        }
        partner.IsOnline = request.IsOnline;
        partner.UpdatedAt = now;
        await db.SaveChangesAsync(ct);
        return new OnlineStatusDto(partner.IsOnline);
    }

    /// <summary>#26: sent every partner.heartbeat_seconds while online; refreshes the location used for matching.</summary>
    public async Task HeartbeatAsync(Guid userId, HeartbeatRequest request, CancellationToken ct)
    {
        var (latitude, longitude) = Geo.Require(request.Latitude, request.Longitude);
        var partner = await FindAsync(userId, ct);
        var timeout = await configs.GetIntAsync(SystemConfigService.PartnerOfflineTimeoutMinutes, 10, ct);
        var now = clock.GetUtcNow();
        if (!IsEffectivelyOnline(partner, now, timeout))
        {
            throw new ApiException(StatusCodes.Status422UnprocessableEntity, ErrorCodes.PartnerOffline, "Bạn đang offline, hãy bật Online lại");
        }
        partner.CurrentLatitude = latitude;
        partner.CurrentLongitude = longitude;
        partner.LastHeartbeatAt = now;
        await db.SaveChangesAsync(ct);
    }

    /// <summary>
    /// Online = toggled on and a heartbeat within partner.offline_timeout_minutes (F-PROF-07).
    /// Computed on read instead of a background job; matching (BE-2) must use the same rule.
    /// </summary>
    public static bool IsEffectivelyOnline(PartnerProfile partner, DateTimeOffset now, int timeoutMinutes) =>
        partner.IsOnline && partner.LastHeartbeatAt is { } last && last >= now.AddMinutes(-timeoutMinutes);

    /// <summary>Vietnamese labels of what is still missing before #20 can succeed (shown on PS-06).</summary>
    public static IReadOnlyList<string> MissingForVerification(PartnerProfile partner)
    {
        var missing = new List<string>();
        if (UserService.NeedsProfileCompletion(partner.User.FullName)) missing.Add("họ tên");
        var current = partner.PartnerDocuments.Where(d => d.Status != (byte)ApprovalStatus.Rejected).Select(d => (DocumentType)d.DocumentType).ToHashSet();
        if (!current.Contains(DocumentType.IdFront)) missing.Add("CCCD mặt trước");
        if (!current.Contains(DocumentType.IdBack)) missing.Add("CCCD mặt sau");
        if (!current.Contains(DocumentType.SelfieWithId)) missing.Add("ảnh chân dung cầm CCCD");
        if (partner.PartnerSkills.Count == 0) missing.Add("ít nhất 1 kỹ năng");
        if (partner.AnchorLatitude is null || partner.AnchorLongitude is null) missing.Add("vị trí hoạt động");
        return missing;
    }

    /// <summary>KYC data is frozen while PENDING or APPROVED.</summary>
    private static void EnsureEditable(PartnerProfile partner)
    {
        if (partner.VerificationStatus is (byte)PartnerVerificationStatus.Pending or (byte)PartnerVerificationStatus.Approved)
        {
            throw new ApiException(StatusCodes.Status409Conflict, ErrorCodes.VerificationLocked,
                "Hồ sơ đang chờ duyệt hoặc đã được duyệt, không thể thay đổi giấy tờ");
        }
    }

    private async Task<PartnerProfile> FindAsync(Guid userId, CancellationToken ct, bool includeDocuments = false)
    {
        var query = db.PartnerProfiles.AsQueryable();
        if (includeDocuments)
        {
            query = query.Include(p => p.PartnerDocuments);
        }
        return await query.FirstOrDefaultAsync(p => p.UserId == userId, ct)
               ?? throw ApiException.NotFound("Không tìm thấy hồ sơ đối tác");
    }

    private static PartnerDocumentDto ToDto(PartnerDocument d) =>
        new(d.Id, (DocumentType)d.DocumentType, d.FileUrl, (ApprovalStatus)d.Status, d.RejectReason, d.CreatedAt);
}
