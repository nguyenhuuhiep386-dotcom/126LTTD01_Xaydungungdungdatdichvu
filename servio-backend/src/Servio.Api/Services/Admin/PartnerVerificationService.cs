using System.Text.Json;
using Microsoft.EntityFrameworkCore;
using Servio.Api.Common;
using Servio.Api.Data;
using Servio.Api.Data.Entities;
using Servio.Api.Services.Files;

namespace Servio.Api.Services.Admin;

public enum VerificationQueue { Pending, PendingSkills, Rejected, Approved }

public sealed record VerificationRow(
    Guid PartnerId,
    string FullName,
    string PhoneNumber,
    PartnerVerificationStatus Status,
    int PendingSkills,
    DateTimeOffset UpdatedAt);

public sealed record VerificationDetail(
    Guid PartnerId,
    string FullName,
    string PhoneNumber,
    string? AvatarUrl,
    string? Bio,
    int YearsOfExperience,
    int ServiceRadiusKm,
    decimal? AnchorLatitude,
    decimal? AnchorLongitude,
    PartnerVerificationStatus Status,
    string? VerificationNote,
    DateTimeOffset? VerifiedAt,
    IReadOnlyList<VerificationDocument> Documents,
    IReadOnlyList<VerificationSkill> Skills);

public sealed record VerificationDocument(Guid Id, DocumentType Type, Guid? FileId, ApprovalStatus Status, string? RejectReason);

public sealed record VerificationSkill(Guid Id, string CategoryName, string? GroupName, int YearsOfExperience, Guid? CertificateFileId, ApprovalStatus Status);

/// <summary>
/// AW-04 (F-ADM-03, UC-01 step 8): review partner KYC and skills. Every decision writes an AuditLog entry and an
/// inbox notification (type KYC_RESULT) in the same SaveChanges. FCM push is added with BE-7.
/// </summary>
public sealed class PartnerVerificationService(ServioDbContext db, TimeProvider clock)
{
    public const int MaxReasonLength = 500;

    public async Task<IReadOnlyList<VerificationRow>> ListAsync(VerificationQueue queue, CancellationToken ct)
    {
        var query = db.PartnerProfiles.AsNoTracking();
        query = queue switch
        {
            VerificationQueue.Pending => query.Where(p => p.VerificationStatus == (byte)PartnerVerificationStatus.Pending),
            VerificationQueue.PendingSkills => query.Where(p => p.VerificationStatus == (byte)PartnerVerificationStatus.Approved
                                                                && p.PartnerSkills.Any(s => s.Status == (byte)ApprovalStatus.Pending)),
            VerificationQueue.Rejected => query.Where(p => p.VerificationStatus == (byte)PartnerVerificationStatus.Rejected),
            _ => query.Where(p => p.VerificationStatus == (byte)PartnerVerificationStatus.Approved),
        };

        // ponytail: no paging; the course demo has a handful of partners. Add Skip/Take when the queue grows.
        var rows = await query
            .OrderBy(p => p.UpdatedAt)
            .Select(p => new
            {
                p.Id, p.User.FullName, p.User.PhoneNumber, p.VerificationStatus, p.UpdatedAt,
                PendingSkills = p.PartnerSkills.Count(s => s.Status == (byte)ApprovalStatus.Pending),
            })
            .Take(200)
            .ToListAsync(ct);
        return rows.Select(r => new VerificationRow(r.Id, r.FullName, r.PhoneNumber, (PartnerVerificationStatus)r.VerificationStatus, r.PendingSkills, r.UpdatedAt)).ToList();
    }

    public async Task<VerificationDetail> GetAsync(Guid partnerId, CancellationToken ct)
    {
        var p = await db.PartnerProfiles.AsNoTracking()
                    .Include(x => x.User)
                    .Include(x => x.PartnerDocuments)
                    .Include(x => x.PartnerSkills).ThenInclude(s => s.ServiceCategory).ThenInclude(c => c.Parent)
                    .AsSplitQuery()
                    .FirstOrDefaultAsync(x => x.Id == partnerId, ct)
                ?? throw ApiException.NotFound("Không tìm thấy đối tác");

        return new VerificationDetail(
            p.Id, p.User.FullName, p.User.PhoneNumber, p.User.AvatarUrl, p.Bio, p.YearsOfExperience, p.ServiceRadiusKm,
            p.AnchorLatitude, p.AnchorLongitude, (PartnerVerificationStatus)p.VerificationStatus, p.VerificationNote, p.VerifiedAt,
            p.PartnerDocuments.OrderBy(d => d.DocumentType)
                .Select(d => new VerificationDocument(d.Id, (DocumentType)d.DocumentType, PrivateFileId(d.FileUrl), (ApprovalStatus)d.Status, d.RejectReason))
                .ToList(),
            p.PartnerSkills.OrderBy(s => s.CreatedAt)
                .Select(s => new VerificationSkill(s.Id, s.ServiceCategory.Name, s.ServiceCategory.Parent?.Name, s.YearsOfExperience,
                    s.CertificateUrl is null ? null : PrivateFileId(s.CertificateUrl), (ApprovalStatus)s.Status))
                .ToList());
    }

    /// <summary>PENDING → APPROVED. Non-rejected documents become APPROVED; optionally approves pending skills too.</summary>
    public async Task ApproveAsync(AdminActor actor, Guid partnerId, bool approvePendingSkills, CancellationToken ct)
    {
        var partner = await LoadPendingAsync(partnerId, ct);
        var now = clock.GetUtcNow();

        partner.VerificationStatus = (byte)PartnerVerificationStatus.Approved;
        partner.VerificationNote = null;
        partner.VerifiedAt = now;
        partner.VerifiedByAdminId = actor.AdminId;
        partner.UpdatedAt = now;
        foreach (var document in partner.PartnerDocuments.Where(d => d.Status != (byte)ApprovalStatus.Rejected))
        {
            document.Status = (byte)ApprovalStatus.Approved;
            document.RejectReason = null;
        }
        var approvedSkills = approvePendingSkills
            ? partner.PartnerSkills.Where(s => s.Status == (byte)ApprovalStatus.Pending).ToList()
            : [];
        approvedSkills.ForEach(s => s.Status = (byte)ApprovalStatus.Approved);

        db.AddAudit(actor, "PARTNER_KYC_APPROVED", nameof(PartnerProfile), partner.Id,
            new { Status = "PENDING" },
            new { Status = "APPROVED", ApprovedSkillIds = approvedSkills.Select(s => s.Id) }, now);
        Notify(partner, "Hồ sơ đối tác đã được duyệt", "Bạn có thể bật Online để bắt đầu nhận việc.", now);
        await db.SaveChangesAsync(ct);
    }

    /// <summary>PENDING → REJECTED with a reason. Only the selected documents are marked REJECTED, so the partner redoes only those.</summary>
    public async Task RejectAsync(AdminActor actor, Guid partnerId, string? reason, IReadOnlyCollection<Guid> rejectedDocumentIds, CancellationToken ct)
    {
        var note = RequireReason(reason);
        var partner = await LoadPendingAsync(partnerId, ct);
        var now = clock.GetUtcNow();

        partner.VerificationStatus = (byte)PartnerVerificationStatus.Rejected;
        partner.VerificationNote = note;
        partner.UpdatedAt = now;
        foreach (var document in partner.PartnerDocuments.Where(d => rejectedDocumentIds.Contains(d.Id)))
        {
            document.Status = (byte)ApprovalStatus.Rejected;
            document.RejectReason = note;
        }

        db.AddAudit(actor, "PARTNER_KYC_REJECTED", nameof(PartnerProfile), partner.Id,
            new { Status = "PENDING" },
            new { Status = "REJECTED", Reason = note, RejectedDocumentIds = rejectedDocumentIds }, now);
        Notify(partner, "Hồ sơ đối tác cần bổ sung", note, now);
        await db.SaveChangesAsync(ct);
    }

    /// <summary>Decide one skill (allowed at any profile status, e.g. a skill added after approval).</summary>
    public async Task DecideSkillAsync(AdminActor actor, Guid partnerId, Guid skillId, bool approve, CancellationToken ct)
    {
        var skill = await db.PartnerSkills
                        .Include(s => s.PartnerProfile)
                        .Include(s => s.ServiceCategory)
                        .FirstOrDefaultAsync(s => s.Id == skillId && s.PartnerProfileId == partnerId, ct)
                    ?? throw ApiException.NotFound("Không tìm thấy kỹ năng");
        if (skill.Status != (byte)ApprovalStatus.Pending)
        {
            throw new ApiException(StatusCodes.Status409Conflict, ErrorCodes.InvalidStatusTransition, "Kỹ năng này đã được xử lý");
        }

        var now = clock.GetUtcNow();
        skill.Status = (byte)(approve ? ApprovalStatus.Approved : ApprovalStatus.Rejected);
        db.AddAudit(actor, approve ? "PARTNER_SKILL_APPROVED" : "PARTNER_SKILL_REJECTED", nameof(PartnerSkill), skill.Id,
            new { Status = "PENDING" }, new { Status = approve ? "APPROVED" : "REJECTED", Category = skill.ServiceCategory.Name }, now);
        Notify(skill.PartnerProfile,
            approve ? "Kỹ năng đã được duyệt" : "Kỹ năng chưa được duyệt",
            approve ? $"Bạn có thể nhận việc {skill.ServiceCategory.Name}." : $"Kỹ năng {skill.ServiceCategory.Name} chưa đạt, hãy bổ sung chứng chỉ rồi đăng ký lại.",
            now);
        await db.SaveChangesAsync(ct);
    }

    /// <summary>KYC images are viewed through /admin/files/{id}; each view is audited (F-PROF-04).</summary>
    public async Task<UploadedFile> GetFileForReviewAsync(AdminActor actor, Guid fileId, CancellationToken ct)
    {
        var file = await db.UploadedFiles.FirstOrDefaultAsync(f => f.Id == fileId, ct) ?? throw ApiException.NotFound("Không tìm thấy file");
        if (file.IsPrivate)
        {
            db.AddAudit(actor, "PRIVATE_FILE_VIEWED", nameof(UploadedFile), file.Id, null, new { file.OwnerUserId, file.Purpose }, clock.GetUtcNow());
            await db.SaveChangesAsync(ct);
        }
        return file;
    }

    /// <summary>"/api/v1/files/{id}" → id. Documents always store the private URL returned by POST /files.</summary>
    public static Guid? PrivateFileId(string url) =>
        url.StartsWith(FileService.PrivateUrlPrefix, StringComparison.Ordinal) && Guid.TryParse(url[FileService.PrivateUrlPrefix.Length..], out var id)
            ? id
            : null;

    private static string RequireReason(string? reason)
    {
        var note = reason?.Trim();
        if (string.IsNullOrEmpty(note) || note.Length > MaxReasonLength)
        {
            throw new ApiException(StatusCodes.Status400BadRequest, ErrorCodes.ValidationError, $"Nhập lý do từ chối (tối đa {MaxReasonLength} ký tự)", "reason");
        }
        return note;
    }

    private async Task<PartnerProfile> LoadPendingAsync(Guid partnerId, CancellationToken ct)
    {
        var partner = await db.PartnerProfiles
                          .Include(p => p.PartnerDocuments)
                          .Include(p => p.PartnerSkills)
                          .AsSplitQuery()
                          .FirstOrDefaultAsync(p => p.Id == partnerId, ct)
                      ?? throw ApiException.NotFound("Không tìm thấy đối tác");
        if (partner.VerificationStatus != (byte)PartnerVerificationStatus.Pending)
        {
            throw new ApiException(StatusCodes.Status409Conflict, ErrorCodes.InvalidStatusTransition, "Hồ sơ không ở trạng thái chờ duyệt");
        }
        return partner;
    }

    private void Notify(PartnerProfile partner, string title, string body, DateTimeOffset now) =>
        db.Notifications.Add(new Notification
        {
            Id = Guid.CreateVersion7(),
            UserId = partner.UserId,
            Type = 8, // KYC_RESULT
            Title = title,
            Body = body,
            AppFlavor = (byte)AppFlavor.Partner,
            DataPayload = JsonSerializer.Serialize(new { partnerId = partner.Id }),
            CreatedAt = now,
        });
}
