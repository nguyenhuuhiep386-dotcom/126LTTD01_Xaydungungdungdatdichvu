using Microsoft.EntityFrameworkCore;
using Servio.Api.Common;
using Servio.Api.Data;
using Servio.Api.Data.Entities;

namespace Servio.Api.Services.Catalog;

public sealed record CategoryDto(
    Guid Id,
    Guid? ParentId,
    string Name,
    string Slug,
    string? Description,
    string? IconUrl,
    long? ReferencePriceMin,
    long? ReferencePriceMax,
    bool RequiresCertificate,
    int DisplayOrder,
    IReadOnlyList<CategoryDto> Children);

/// <summary>M2 catalog: #29 category tree, #30 category detail. Public, active categories only.</summary>
public sealed class CategoryService(ServioDbContext db)
{
    /// <summary>Without parentId: level-1 categories with their level-2 children. With parentId: children of that parent.</summary>
    public async Task<IReadOnlyList<CategoryDto>> GetTreeAsync(Guid? parentId, CancellationToken ct)
    {
        var all = await db.ServiceCategories
            .AsNoTracking()
            .Where(c => c.IsActive)
            .OrderBy(c => c.DisplayOrder).ThenBy(c => c.Name)
            .ToListAsync(ct);

        var byParent = all.ToLookup(c => c.ParentId);
        return byParent[parentId].Select(c => ToDto(c, byParent)).ToList();
    }

    public async Task<CategoryDto> GetByIdAsync(Guid id, CancellationToken ct)
    {
        var category = await db.ServiceCategories.AsNoTracking().FirstOrDefaultAsync(c => c.Id == id && c.IsActive, ct)
                       ?? throw ApiException.NotFound("Không tìm thấy danh mục");
        var children = await db.ServiceCategories.AsNoTracking()
            .Where(c => c.ParentId == id && c.IsActive)
            .OrderBy(c => c.DisplayOrder)
            .ToListAsync(ct);

        return ToDto(category, children.ToLookup(c => (Guid?)id));
    }

    private static CategoryDto ToDto(ServiceCategory c, ILookup<Guid?, ServiceCategory> byParent) => new(
        c.Id,
        c.ParentId,
        c.Name,
        c.Slug,
        c.Description,
        c.IconUrl,
        (long?)c.ReferencePriceMin,
        (long?)c.ReferencePriceMax,
        c.RequiresCertificate,
        c.DisplayOrder,
        byParent[c.Id].Select(child => ToDto(child, byParent)).ToList());
}
