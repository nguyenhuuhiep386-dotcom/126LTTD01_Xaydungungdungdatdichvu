using Microsoft.EntityFrameworkCore;
using Servio.Api.Common;
using Servio.Api.Data;

namespace Servio.Api.Services.Partners;

/// <summary>
/// #27 public partner profile and #28 reviews (CS-17). Only visible to users related to the partner through
/// a quote, a conversation or an order (spec 6.2); others get 404 so profiles cannot be enumerated.
/// </summary>
public sealed class PartnerPublicService(ServioDbContext db, TimeProvider clock)
{
    public const int MaxPageSize = 50;

    public async Task<PartnerPublicDto> GetAsync(Guid viewerUserId, Guid partnerId, CancellationToken ct)
    {
        await EnsureRelatedAsync(viewerUserId, partnerId, ct);
        var partner = await db.PartnerProfiles.AsNoTracking()
            .Include(p => p.User)
            .Include(p => p.PartnerSkills).ThenInclude(s => s.ServiceCategory)
            .FirstAsync(p => p.Id == partnerId, ct);

        return new PartnerPublicDto(
            partner.Id,
            partner.User.FullName,
            partner.User.AvatarUrl,
            partner.Bio,
            partner.YearsOfExperience,
            partner.AverageRating,
            partner.TotalReviews,
            partner.CompletedOrders,
            partner.PartnerSkills.Where(s => s.Status == (byte)ApprovalStatus.Approved).Select(s => s.ServiceCategory.Name).Order().ToList(),
            partner.CreatedAt);
    }

    public async Task<PagedResult<PartnerReviewDto>> GetReviewsAsync(Guid viewerUserId, Guid partnerId, int page, int pageSize, CancellationToken ct)
    {
        await EnsureRelatedAsync(viewerUserId, partnerId, ct);
        page = Math.Max(page, 1);
        pageSize = Math.Clamp(pageSize, 1, MaxPageSize);
        var now = clock.GetUtcNow();

        var partnerUserId = await db.PartnerProfiles.Where(p => p.Id == partnerId).Select(p => p.UserId).FirstAsync(ct);
        var query = db.Reviews.AsNoTracking().Where(r =>
            r.RevieweeId == partnerUserId && r.ReviewerType == (byte)ActorType.Customer && r.IsVisible && r.PublishAt <= now);

        var total = await query.CountAsync(ct);
        var rows = await query
            .OrderByDescending(r => r.CreatedAt).ThenByDescending(r => r.Id)
            .Skip((page - 1) * pageSize).Take(pageSize)
            .Select(r => new { r.Id, r.Rating, r.Comment, r.Tags, ReviewerName = r.Reviewer.FullName, r.CreatedAt })
            .ToListAsync(ct);

        var items = rows.Select(r => new PartnerReviewDto(
            r.Id,
            r.Rating,
            r.Comment,
            string.IsNullOrWhiteSpace(r.Tags) ? [] : r.Tags.Split(',', StringSplitOptions.TrimEntries | StringSplitOptions.RemoveEmptyEntries),
            ShortName(r.ReviewerName),
            r.CreatedAt)).ToList();
        return new PagedResult<PartnerReviewDto>(items, total, page, pageSize);
    }

    /// <summary>"Nguyễn Văn An" → "An N." so reviewers are not fully identified.</summary>
    public static string ShortName(string fullName)
    {
        var parts = fullName.Split(' ', StringSplitOptions.RemoveEmptyEntries);
        return parts.Length switch
        {
            0 => "Khách hàng",
            1 => parts[0],
            _ => $"{parts[^1]} {parts[0][0]}.",
        };
    }

    private async Task EnsureRelatedAsync(Guid viewerUserId, Guid partnerId, CancellationToken ct)
    {
        var related =
            await db.PartnerProfiles.AnyAsync(p => p.Id == partnerId && p.UserId == viewerUserId, ct) ||
            await db.Quotes.AnyAsync(q => q.PartnerProfileId == partnerId && q.ServiceRequest.Customer.UserId == viewerUserId, ct) ||
            await db.Conversations.AnyAsync(c => c.PartnerProfileId == partnerId && c.Customer.UserId == viewerUserId, ct) ||
            await db.OrderAssignments.AnyAsync(a => a.PartnerProfileId == partnerId && a.Order.Customer.UserId == viewerUserId, ct);
        if (!related)
        {
            throw ApiException.NotFound("Không tìm thấy đối tác");
        }
    }
}
