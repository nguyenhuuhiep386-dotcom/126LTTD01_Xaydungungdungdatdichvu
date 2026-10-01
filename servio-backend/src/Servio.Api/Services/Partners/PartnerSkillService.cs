using Microsoft.EntityFrameworkCore;
using Servio.Api.Common;
using Servio.Api.Data;
using Servio.Api.Data.Entities;
using Servio.Api.Services.Files;

namespace Servio.Api.Services.Partners;

/// <summary>M1 partner skills (#21–#23, F-PROF-05): up to 5 level-2 categories, each approved separately on AW-04.</summary>
public sealed class PartnerSkillService(ServioDbContext db, FileService files, TimeProvider clock)
{
    public const int MaxSkills = 5;

    public async Task<IReadOnlyList<PartnerSkillDto>> ListAsync(Guid userId, CancellationToken ct) =>
        (await db.PartnerSkills.AsNoTracking()
            .Include(s => s.ServiceCategory).ThenInclude(c => c.Parent)
            .Where(s => s.PartnerProfile.UserId == userId)
            .OrderBy(s => s.CreatedAt)
            .ToListAsync(ct))
        .Select(ToDto)
        .ToList();

    public async Task<PartnerSkillDto> AddAsync(Guid userId, AddSkillRequest request, CancellationToken ct)
    {
        var partner = await db.PartnerProfiles.Include(p => p.PartnerSkills).FirstOrDefaultAsync(p => p.UserId == userId, ct)
                      ?? throw ApiException.NotFound("Không tìm thấy hồ sơ đối tác");
        var category = await db.ServiceCategories.Include(c => c.Parent)
                           .FirstOrDefaultAsync(c => c.Id == request.ServiceCategoryId && c.IsActive && c.ParentId != null, ct)
                       ?? throw new ApiException(StatusCodes.Status400BadRequest, ErrorCodes.ValidationError,
                           "Hãy chọn một dịch vụ cụ thể (danh mục cấp 2)", "serviceCategoryId");

        if (partner.PartnerSkills.Any(s => s.ServiceCategoryId == category.Id))
        {
            throw new ApiException(StatusCodes.Status409Conflict, ErrorCodes.SkillAlreadyExists, "Bạn đã đăng ký kỹ năng này");
        }
        if (partner.PartnerSkills.Count >= MaxSkills)
        {
            throw new ApiException(StatusCodes.Status422UnprocessableEntity, ErrorCodes.SkillLimitReached, $"Tối đa {MaxSkills} kỹ năng");
        }

        string? certificateUrl = null;
        if (!string.IsNullOrWhiteSpace(request.CertificateUrl))
        {
            var file = await files.ResolveOwnedAsync(userId, request.CertificateUrl, "certificateUrl", ct, FilePurpose.Certificate, FilePurpose.Kyc);
            certificateUrl = FileService.UrlOf(file);
        }
        else if (category.RequiresCertificate)
        {
            throw new ApiException(StatusCodes.Status422UnprocessableEntity, ErrorCodes.CertificateRequired,
                "Dịch vụ này cần chứng chỉ nghề", "certificateUrl");
        }

        var skill = new PartnerSkill
        {
            Id = Guid.CreateVersion7(),
            PartnerProfileId = partner.Id,
            ServiceCategoryId = category.Id,
            ServiceCategory = category,
            YearsOfExperience = request.YearsOfExperience,
            CertificateUrl = certificateUrl,
            Status = (byte)ApprovalStatus.Pending,
            CreatedAt = clock.GetUtcNow(),
        };
        db.PartnerSkills.Add(skill);
        await db.SaveChangesAsync(ct);
        return ToDto(skill);
    }

    public async Task DeleteAsync(Guid userId, Guid skillId, CancellationToken ct)
    {
        var skill = await db.PartnerSkills.FirstOrDefaultAsync(s => s.Id == skillId && s.PartnerProfile.UserId == userId, ct)
                    ?? throw ApiException.NotFound("Không tìm thấy kỹ năng");
        db.PartnerSkills.Remove(skill);
        await db.SaveChangesAsync(ct);
    }

    internal static PartnerSkillDto ToDto(PartnerSkill s) => new(
        s.Id,
        s.ServiceCategoryId,
        s.ServiceCategory.Name,
        s.ServiceCategory.Parent?.Name,
        (ApprovalStatus)s.Status,
        s.YearsOfExperience,
        s.CertificateUrl);
}
