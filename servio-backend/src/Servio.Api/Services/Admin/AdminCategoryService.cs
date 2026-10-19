using Microsoft.EntityFrameworkCore;
using Servio.Api.Common;
using Servio.Api.Data;
using Servio.Api.Data.Entities;

namespace Servio.Api.Services.Admin;

public sealed record AdminCategoryRow(
    Guid Id,
    Guid? ParentId,
    string Name,
    string Slug,
    long? ReferencePriceMin,
    long? ReferencePriceMax,
    decimal? CommissionRate,
    bool RequiresCertificate,
    int DisplayOrder,
    bool IsActive,
    int PartnerSkillCount);

/// <summary>Form of AW-11 create/edit. Empty slug → generated from the name.</summary>
public sealed class CategoryForm
{
    public Guid? ParentId { get; set; }
    public string Name { get; set; } = "";
    public string? Slug { get; set; }
    public string? Description { get; set; }
    public long? ReferencePriceMin { get; set; }
    public long? ReferencePriceMax { get; set; }
    public decimal? CommissionRate { get; set; }
    public bool RequiresCertificate { get; set; }
    public int DisplayOrder { get; set; }
    public bool IsActive { get; set; } = true;
}

/// <summary>
/// AW-11 (F-ADM-07): two-level catalog. Level-1 groups have no parent; services (level 2) belong to a group and are
/// what customers post and partners register as skills. Categories are never deleted, only deactivated, because
/// requests, orders and skills reference them.
/// </summary>
public sealed class AdminCategoryService(ServioDbContext db, TimeProvider clock)
{
    public async Task<IReadOnlyList<AdminCategoryRow>> ListAsync(CancellationToken ct) =>
        await db.ServiceCategories.AsNoTracking()
            .OrderBy(c => c.DisplayOrder).ThenBy(c => c.Name)
            .Select(c => new AdminCategoryRow(
                c.Id, c.ParentId, c.Name, c.Slug, (long?)c.ReferencePriceMin, (long?)c.ReferencePriceMax, c.CommissionRate,
                c.RequiresCertificate, c.DisplayOrder, c.IsActive, c.PartnerSkills.Count))
            .ToListAsync(ct);

    public async Task<IReadOnlyList<(Guid Id, string Name)>> ListGroupsAsync(Guid? exceptId, CancellationToken ct) =>
        (await db.ServiceCategories.AsNoTracking()
            .Where(c => c.ParentId == null && c.Id != exceptId)
            .OrderBy(c => c.DisplayOrder)
            .Select(c => new { c.Id, c.Name })
            .ToListAsync(ct))
        .Select(c => (c.Id, c.Name))
        .ToList();

    public async Task<CategoryForm> GetFormAsync(Guid id, CancellationToken ct)
    {
        var c = await db.ServiceCategories.AsNoTracking().FirstOrDefaultAsync(x => x.Id == id, ct)
                ?? throw ApiException.NotFound("Không tìm thấy danh mục");
        return new CategoryForm
        {
            ParentId = c.ParentId, Name = c.Name, Slug = c.Slug, Description = c.Description,
            ReferencePriceMin = (long?)c.ReferencePriceMin, ReferencePriceMax = (long?)c.ReferencePriceMax,
            CommissionRate = c.CommissionRate, RequiresCertificate = c.RequiresCertificate,
            DisplayOrder = c.DisplayOrder, IsActive = c.IsActive,
        };
    }

    /// <summary>Creates when <paramref name="id"/> is null, otherwise updates. Returns the category id.</summary>
    public async Task<Guid> SaveAsync(AdminActor actor, Guid? id, CategoryForm form, CancellationToken ct)
    {
        var name = form.Name.Trim();
        if (name.Length is < 2 or > 150)
        {
            throw Invalid("Tên danh mục dài 2–150 ký tự", nameof(CategoryForm.Name));
        }
        var slug = string.IsNullOrWhiteSpace(form.Slug) ? Slug.From(name) : form.Slug.Trim().ToLowerInvariant();
        if (!Slug.IsValid(slug))
        {
            throw Invalid("Slug chỉ gồm chữ thường không dấu, số và dấu gạch ngang", nameof(CategoryForm.Slug));
        }
        if (await db.ServiceCategories.AnyAsync(c => c.Slug == slug && c.Id != id, ct))
        {
            throw Invalid("Slug đã được dùng cho danh mục khác", nameof(CategoryForm.Slug));
        }
        if (form.ReferencePriceMin < 0 || form.ReferencePriceMax < 0 || form.ReferencePriceMin > form.ReferencePriceMax)
        {
            throw Invalid("Giá tham chiếu không hợp lệ (từ ≤ đến, không âm)", nameof(CategoryForm.ReferencePriceMin));
        }
        if (form.CommissionRate is < 0 or > 100)
        {
            throw Invalid("Hoa hồng từ 0 đến 100%", nameof(CategoryForm.CommissionRate));
        }
        await ValidateParentAsync(id, form.ParentId, ct);

        var now = clock.GetUtcNow();
        var category = id is null
            ? new ServiceCategory { Id = Guid.CreateVersion7(), CreatedAt = now }
            : await db.ServiceCategories.FirstOrDefaultAsync(c => c.Id == id, ct) ?? throw ApiException.NotFound("Không tìm thấy danh mục");
        var before = id is null ? null : Snapshot(category);

        category.ParentId = form.ParentId;
        category.Name = name;
        category.Slug = slug;
        category.Description = string.IsNullOrWhiteSpace(form.Description) ? null : form.Description.Trim();
        category.ReferencePriceMin = form.ReferencePriceMin;
        category.ReferencePriceMax = form.ReferencePriceMax;
        category.CommissionRate = form.CommissionRate;
        category.RequiresCertificate = form.RequiresCertificate;
        category.DisplayOrder = form.DisplayOrder;
        category.IsActive = form.IsActive;
        category.UpdatedAt = now;
        if (id is null)
        {
            db.ServiceCategories.Add(category);
        }

        db.AddAudit(actor, id is null ? "CATEGORY_CREATED" : "CATEGORY_UPDATED", nameof(ServiceCategory), category.Id, before, Snapshot(category), now);
        await db.SaveChangesAsync(ct);
        return category.Id;
    }

    /// <summary>Two levels only: a parent must be a group, and a group that has services cannot become a service.</summary>
    private async Task ValidateParentAsync(Guid? id, Guid? parentId, CancellationToken ct)
    {
        if (parentId is null)
        {
            return;
        }
        var parentIsGroup = await db.ServiceCategories.AnyAsync(c => c.Id == parentId && c.ParentId == null && c.Id != id, ct);
        if (!parentIsGroup)
        {
            throw Invalid("Nhóm cha phải là danh mục cấp 1", nameof(CategoryForm.ParentId));
        }
        if (id is not null && await db.ServiceCategories.AnyAsync(c => c.ParentId == id, ct))
        {
            throw Invalid("Nhóm này đang có dịch vụ con nên không thể chuyển thành dịch vụ", nameof(CategoryForm.ParentId));
        }
    }

    private static object Snapshot(ServiceCategory c) => new
    {
        c.ParentId, c.Name, c.Slug, c.ReferencePriceMin, c.ReferencePriceMax, c.CommissionRate, c.RequiresCertificate, c.DisplayOrder, c.IsActive,
    };

    private static ApiException Invalid(string message, string field) =>
        new(StatusCodes.Status400BadRequest, ErrorCodes.ValidationError, message, field);
}
