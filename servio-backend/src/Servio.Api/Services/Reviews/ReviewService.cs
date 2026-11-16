using System.ComponentModel.DataAnnotations;
using Microsoft.EntityFrameworkCore;
using Servio.Api.Common;
using Servio.Api.Data;
using Servio.Api.Data.Entities;
using Servio.Api.Services.Notifications;
using Servio.Api.Services.Requests;

namespace Servio.Api.Services.Reviews;

/// <summary>
/// #95 body. The customer rates the partner (overall + 4 optional criteria, F-REV-02); the partner rates the customer
/// (overall + quick tags such as PAID_ON_TIME, ACCURATE_DESCRIPTION, FRIENDLY, F-REV-03). Photos are not stored (no table in scope).
/// </summary>
public sealed record CreateReviewRequest(
    Guid AssignmentId,
    [Range(1, 5, ErrorMessage = "Chọn từ 1 đến 5 sao")] int Rating,
    [Range(1, 5)] int? PunctualityRating,
    [Range(1, 5)] int? QualityRating,
    [Range(1, 5)] int? AttitudeRating,
    [Range(1, 5)] int? PriceRating,
    [StringLength(500, ErrorMessage = "Nhận xét tối đa 500 ký tự")] string? Comment,
    [MaxLength(5, ErrorMessage = "Tối đa 5 thẻ")] string[]? Tags);

/// <summary>reviewerType CUSTOMER = the customer rated the partner; PARTNER = the partner rated the customer.</summary>
public sealed record ReviewDto(
    Guid Id,
    Guid OrderId,
    Guid AssignmentId,
    ActorType ReviewerType,
    string ReviewerName,
    int Rating,
    int? PunctualityRating,
    int? QualityRating,
    int? AttitudeRating,
    int? PriceRating,
    string? Comment,
    IReadOnlyList<string> Tags,
    DateTimeOffset CreatedAt);

/// <summary>#100 row: an order you can still review until deadlineAt.</summary>
public sealed record PendingReviewDto(Guid OrderId, Guid AssignmentId, string OrderCode, string RevieweeName, DateTimeOffset CompletedAt, DateTimeOffset DeadlineAt);

/// <summary>
/// M7 course scope (0.2.7 ý 11): two-way reviews within review.window_days after CompletedAt, published at once (blind
/// review is P1); rating = plain average of the visible reviews the person received in that role.
/// </summary>
public sealed class ReviewService(
    ServioDbContext db,
    NotificationService notifications,
    SystemConfigService configs,
    TimeProvider clock)
{
    /// <summary>#95</summary>
    public async Task<ReviewDto> CreateAsync(Guid userId, AppFlavor flavor, Guid orderId, CreateReviewRequest input, CancellationToken ct)
    {
        var comment = string.IsNullOrWhiteSpace(input.Comment) ? null : input.Comment.Trim();
        ContentGuard.EnsureClean(comment, "comment");
        var tags = (input.Tags ?? []).Select(t => t.Trim()).Where(t => t.Length > 0).Distinct().ToList();
        if (tags.Any(t => t.Length > 50 || t.Contains(',')))
        {
            throw new ApiException(StatusCodes.Status400BadRequest, ErrorCodes.ValidationError, "Thẻ không hợp lệ", "tags");
        }

        var a = await db.OrderAssignments
                    .Include(x => x.Order).ThenInclude(o => o.Customer).ThenInclude(c => c.User)
                    .Include(x => x.PartnerProfile).ThenInclude(p => p.User)
                    .FirstOrDefaultAsync(x => x.Id == input.AssignmentId && x.OrderId == orderId, ct);
        var asCustomer = flavor == AppFlavor.Customer;
        if (a is null || (asCustomer ? a.Order.Customer.UserId : a.PartnerProfile.UserId) != userId)
        {
            throw ApiException.NotFound("Không tìm thấy đơn");
        }
        if (a.Status != (byte)OrderStatus.Completed || a.Order.CompletedAt is not { } completedAt)
        {
            throw new ApiException(StatusCodes.Status409Conflict, ErrorCodes.InvalidStatusTransition, "Chỉ đánh giá được sau khi đơn hoàn tất");
        }
        var now = clock.GetUtcNow();
        if (now > completedAt.AddDays(await WindowDaysAsync(ct)))
        {
            throw new ApiException(StatusCodes.Status422UnprocessableEntity, ErrorCodes.ReviewWindowClosed, "Đã quá hạn đánh giá đơn này");
        }
        if (await db.Reviews.AnyAsync(r => r.OrderAssignmentId == a.Id && r.ReviewerId == userId, ct))
        {
            throw AlreadySubmitted();
        }

        var revieweeId = asCustomer ? a.PartnerProfile.UserId : a.Order.Customer.UserId;
        var review = new Review
        {
            Id = Guid.CreateVersion7(),
            OrderId = orderId,
            OrderAssignmentId = a.Id,
            ReviewerId = userId,
            RevieweeId = revieweeId,
            ReviewerType = (byte)(asCustomer ? ActorType.Customer : ActorType.Partner),
            Rating = (byte)input.Rating,
            // The 4 criteria only describe a partner's work.
            PunctualityRating = asCustomer ? (byte?)input.PunctualityRating : null,
            QualityRating = asCustomer ? (byte?)input.QualityRating : null,
            AttitudeRating = asCustomer ? (byte?)input.AttitudeRating : null,
            PriceRating = asCustomer ? (byte?)input.PriceRating : null,
            Comment = comment,
            Tags = tags.Count == 0 ? null : string.Join(',', tags),
            IsVisible = true,
            PublishAt = now,
            CreatedAt = now,
        };
        db.Reviews.Add(review);
        try
        {
            await db.SaveChangesAsync(ct);
        }
        catch (DbUpdateException e) when (e.IsUniqueViolation())
        {
            throw AlreadySubmitted(); // UQ (OrderAssignmentId, ReviewerId): a double tap
        }
        await RefreshRatingAsync(revieweeId, asCustomer ? a.PartnerProfile : null, asCustomer ? null : a.Order.Customer, ct);

        await notifications.SendAsync([(asCustomer ? AppFlavor.Partner : AppFlavor.Customer, revieweeId)], NotificationType.Review,
            "Bạn có đánh giá mới", $"Đơn {a.Order.Code}: {input.Rating} sao{(comment is null ? "" : $" — {comment}")}",
            new { orderId, reviewId = review.Id }, $"orders/{orderId}", ct);
        return ToDto(review, asCustomer ? a.Order.Customer.User.FullName : a.PartnerProfile.User.FullName);
    }

    /// <summary>#96 Reviews of the order: the customer sees all of them, a partner those of their own assignment.</summary>
    public async Task<IReadOnlyList<ReviewDto>> ListForOrderAsync(Guid userId, AppFlavor flavor, Guid orderId, CancellationToken ct)
    {
        var member = flavor == AppFlavor.Customer
            ? await db.Orders.AnyAsync(o => o.Id == orderId && o.Customer.UserId == userId, ct)
            : await db.OrderAssignments.AnyAsync(a => a.OrderId == orderId && a.PartnerProfile.UserId == userId, ct);
        if (!member)
        {
            throw ApiException.NotFound("Không tìm thấy đơn");
        }
        var rows = await db.Reviews.AsNoTracking()
            .Where(r => r.OrderId == orderId && r.IsVisible
                        && (flavor == AppFlavor.Customer || r.OrderAssignment.PartnerProfile.UserId == userId))
            .OrderBy(r => r.CreatedAt)
            .Select(r => new { Review = r, ReviewerName = r.Reviewer.FullName })
            .ToListAsync(ct);
        return rows.Select(x => ToDto(x.Review, x.ReviewerName)).ToList();
    }

    /// <summary>#100 Completed assignments still inside the window that the caller has not reviewed, newest first.</summary>
    public async Task<IReadOnlyList<PendingReviewDto>> PendingAsync(Guid userId, AppFlavor flavor, CancellationToken ct)
    {
        var days = await WindowDaysAsync(ct);
        var since = clock.GetUtcNow().AddDays(-days);
        var query = db.OrderAssignments.AsNoTracking()
            .Where(a => a.Status == (byte)OrderStatus.Completed && a.Order.CompletedAt >= since
                        && !a.Reviews.Any(r => r.ReviewerId == userId));
        query = flavor == AppFlavor.Customer
            ? query.Where(a => a.Order.Customer.UserId == userId)
            : query.Where(a => a.PartnerProfile.UserId == userId);
        var rows = await query.OrderByDescending(a => a.Order.CompletedAt)
            .Select(a => new
            {
                a.OrderId,
                a.Id,
                a.Order.Code,
                Reviewee = flavor == AppFlavor.Customer ? a.PartnerProfile.User.FullName : a.Order.Customer.User.FullName,
                CompletedAt = a.Order.CompletedAt!.Value,
            })
            .ToListAsync(ct);
        return rows.Select(r => new PendingReviewDto(r.OrderId, r.Id, r.Code, r.Reviewee, r.CompletedAt, r.CompletedAt.AddDays(days))).ToList();
    }

    private Task<int> WindowDaysAsync(CancellationToken ct) => configs.GetIntAsync("review.window_days", 5, ct);

    /// <summary>F-REV-05 course scope: plain average of the visible reviews received in that role, 2 decimals.</summary>
    private async Task RefreshRatingAsync(Guid revieweeId, PartnerProfile? partner, CustomerProfile? customer, CancellationToken ct)
    {
        var fromType = (byte)(partner is not null ? ActorType.Customer : ActorType.Partner);
        var ratings = await db.Reviews.Where(r => r.RevieweeId == revieweeId && r.ReviewerType == fromType && r.IsVisible)
            .Select(r => (int)r.Rating).ToListAsync(ct);
        decimal? average = ratings.Count == 0 ? null : Math.Round((decimal)ratings.Average(), 2);
        if (partner is not null)
        {
            partner.AverageRating = average;
            partner.TotalReviews = ratings.Count;
        }
        else if (customer is not null)
        {
            customer.AverageRating = average;
            customer.TotalReviews = ratings.Count;
        }
        // ponytail: recomputed on each review, two reviews saved at the same instant may leave a stale count until the next one.
        await db.SaveChangesAsync(ct);
    }

    private static ApiException AlreadySubmitted() =>
        new(StatusCodes.Status409Conflict, ErrorCodes.ReviewAlreadySubmitted, "Bạn đã đánh giá đơn này");

    private static ReviewDto ToDto(Review r, string reviewerName) => new(
        r.Id, r.OrderId, r.OrderAssignmentId, (ActorType)r.ReviewerType, reviewerName, r.Rating,
        r.PunctualityRating, r.QualityRating, r.AttitudeRating, r.PriceRating, r.Comment,
        r.Tags?.Split(',') ?? [], r.CreatedAt);
}
