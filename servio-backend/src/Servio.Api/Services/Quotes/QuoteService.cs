using Microsoft.EntityFrameworkCore;
using Servio.Api.Common;
using Servio.Api.Data;
using Servio.Api.Data.Entities;
using Servio.Api.Hubs;
using Servio.Api.Services.Feed;
using Servio.Api.Services.Requests;
using Servio.Api.Services.Users;

namespace Servio.Api.Services.Quotes;

/// <summary>
/// M3 quotes: #50 submit, #52 withdraw, #53 partner list, #42 customer list (spec 3.3.2 steps 1–2, F-FEED-08..10).
/// Accepting a quote (#43) belongs to BE-4.
/// </summary>
public sealed class QuoteService(
    ServioDbContext db,
    FeedService feed,
    SystemConfigService configs,
    IRealtimeNotifier notifier,
    TimeProvider clock)
{
    public const int MaxPageSize = 50;

    /// <summary>Tolerance for availableFrom slightly in the past on NOW posts (phone clock drift).</summary>
    private static readonly TimeSpan ClockTolerance = TimeSpan.FromMinutes(5);

    public async Task<PartnerQuoteDto> SubmitAsync(Guid userId, Guid requestId, SubmitQuoteRequest input, CancellationToken ct)
    {
        ContentGuard.EnsureClean(input.Note, "note");
        var partner = await feed.LoadPartnerAsync(userId, ct);
        await EnsureCanQuoteAsync(partner, ct);

        var request = await db.ServiceRequests.AsNoTracking()
                          .Include(r => r.Customer)
                          .Include(r => r.ServiceCategory)
                          .FirstOrDefaultAsync(r => r.Id == requestId, ct)
                      ?? throw ApiException.NotFound("Không tìm thấy bài đăng");
        var now = clock.GetUtcNow();
        EnsureOpen(request, now);
        if (input.RequestRevision != request.Revision)
        {
            throw new ApiException(StatusCodes.Status409Conflict, ErrorCodes.ResourceVersionConflict, "Bài đăng đã thay đổi, vui lòng tải lại");
        }

        var row = (await feed.LoadRowAsync(requestId, partner.Profile.Id, ct))!;
        var distance = FeedService.Evaluate(partner, row)
                       ?? throw new ApiException(StatusCodes.Status403Forbidden, ErrorCodes.PartnerNotEligible,
                           "Bạn không đủ điều kiện báo giá bài này (kỹ năng, khoảng cách hoặc yêu cầu của khách)");
        if (row.HasQuoted)
        {
            throw new ApiException(StatusCodes.Status409Conflict, ErrorCodes.QuoteAlreadyExists, "Bạn đã báo giá cho bài này");
        }
        EnsureInWindow(request, input.AvailableFrom, now);

        var maxPending = await configs.GetIntAsync("quote.max_pending_per_partner", 5, ct);
        // ponytail: count-then-insert; two parallel quotes from one partner can exceed the limit by one. Lock the partner row if it matters.
        if (await db.Quotes.CountAsync(q => q.PartnerProfileId == partner.Profile.Id && q.Status == (byte)QuoteStatus.Pending, ct) >= maxPending)
        {
            throw new ApiException(StatusCodes.Status422UnprocessableEntity, ErrorCodes.QuoteLimitExceeded,
                $"Bạn đã có {maxPending} báo giá đang chờ, hãy chờ khách phản hồi hoặc rút bớt");
        }

        var quote = new Quote
        {
            Id = Guid.CreateVersion7(),
            ServiceRequestId = request.Id,
            PartnerProfileId = partner.Profile.Id,
            Amount = input.Amount,
            EstimatedDurationMinutes = input.EstimatedDurationMinutes,
            AvailableFrom = input.AvailableFrom,
            EstimatedEndAt = input.AvailableFrom.AddMinutes(input.EstimatedDurationMinutes),
            Note = string.IsNullOrWhiteSpace(input.Note) ? null : input.Note.Trim(),
            Status = (byte)QuoteStatus.Pending,
            RequestRevision = request.Revision,
            CreatedAt = now,
            UpdatedAt = now,
        };
        db.Quotes.Add(quote);
        var conversation = await UpsertConversationAsync(request, partner.Profile.Id, now, ct);
        try
        {
            await db.SaveChangesAsync(ct);
        }
        catch (DbUpdateException e) when (e is not DbUpdateConcurrencyException)
        {
            // Unique (ServiceRequestId, PartnerProfileId, RequestRevision): a double tap that slipped past the check above.
            throw new ApiException(StatusCodes.Status409Conflict, ErrorCodes.QuoteAlreadyExists, "Bạn đã báo giá cho bài này");
        }

        var skillYears = partner.Skills[request.ServiceCategoryId].YearsOfExperience;
        await notifier.NewQuoteAsync(request.Customer.UserId,
            ToCustomerDto(quote, conversation.Id, partner.Profile, skillYears, Matching.RoundKm(distance)), ct);
        return ToPartnerDto(quote, request, conversation.Id);
    }

    public async Task WithdrawAsync(Guid userId, Guid quoteId, CancellationToken ct)
    {
        var quote = await db.Quotes
                        .Include(q => q.ServiceRequest).ThenInclude(r => r.Customer)
                        .FirstOrDefaultAsync(q => q.Id == quoteId && q.PartnerProfile.UserId == userId, ct)
                    ?? throw ApiException.NotFound("Không tìm thấy báo giá");
        if (quote.Status != (byte)QuoteStatus.Pending)
        {
            throw new ApiException(StatusCodes.Status409Conflict, ErrorCodes.InvalidStatusTransition, "Chỉ rút được báo giá đang chờ");
        }

        var now = clock.GetUtcNow();
        quote.Status = (byte)QuoteStatus.Withdrawn;
        quote.RespondedAt = now;
        quote.UpdatedAt = now;
        await db.SaveChangesAsync(ct);
        await notifier.QuoteWithdrawnAsync(quote.ServiceRequest.Customer.UserId, quote.ServiceRequestId, quote.Id, ct);
    }

    /// <summary>#53: the partner's quotes, newest first (PS-12 tabs filter by status).</summary>
    public async Task<PagedResult<PartnerQuoteDto>> ListMineAsync(Guid userId, QuoteStatus? status, int page, int pageSize, CancellationToken ct)
    {
        (page, pageSize) = (Math.Max(page, 1), Math.Clamp(pageSize, 1, MaxPageSize));
        var query = db.Quotes.AsNoTracking().Where(q => q.PartnerProfile.UserId == userId && (status == null || q.Status == (byte)status));
        var total = await query.CountAsync(ct);
        var items = await query
            .OrderByDescending(q => q.CreatedAt).ThenByDescending(q => q.Id)
            .Skip((page - 1) * pageSize).Take(pageSize)
            .Select(q => new PartnerQuoteDto(
                q.Id, q.ServiceRequestId, q.ServiceRequest.Code, q.ServiceRequest.Title, q.ServiceRequest.ServiceCategory.Name,
                (ServiceRequestStatus)q.ServiceRequest.Status, (long)q.Amount, q.EstimatedDurationMinutes, q.AvailableFrom, q.EstimatedEndAt,
                q.Note, (QuoteStatus)q.Status,
                db.Conversations.Where(c => c.ServiceRequestId == q.ServiceRequestId && c.PartnerProfileId == q.PartnerProfileId)
                    .Select(c => (Guid?)c.Id).FirstOrDefault(),
                q.CreatedAt))
            .ToListAsync(ct);
        return new PagedResult<PartnerQuoteDto>(items, total, page, pageSize);
    }

    /// <summary>
    /// #42 (CS-15): PENDING and ACCEPTED quotes of the current revision. Default sort = earliest availableFrom;
    /// price = cheapest first; rating = best rated first (partners without reviews last).
    /// </summary>
    public async Task<PagedResult<CustomerQuoteDto>> ListForCustomerAsync(Guid userId, Guid requestId, QuoteSort sort, int page, int pageSize, CancellationToken ct)
    {
        (page, pageSize) = (Math.Max(page, 1), Math.Clamp(pageSize, 1, MaxPageSize));
        var request = await db.ServiceRequests.AsNoTracking()
                          .Where(r => r.Id == requestId && r.Customer.UserId == userId)
                          .Select(r => new { r.Id, r.Revision, r.ServiceCategoryId, r.Latitude, r.Longitude })
                          .FirstOrDefaultAsync(ct)
                      ?? throw ApiException.NotFound("Không tìm thấy yêu cầu");

        var rows = await db.Quotes.AsNoTracking()
            .Where(q => q.ServiceRequestId == request.Id && q.RequestRevision == request.Revision
                        && (q.Status == (byte)QuoteStatus.Pending || q.Status == (byte)QuoteStatus.Accepted))
            .Select(q => new
            {
                Quote = q,
                q.PartnerProfile,
                PartnerUser = q.PartnerProfile.User,
                SkillYears = q.PartnerProfile.PartnerSkills.Where(s => s.ServiceCategoryId == request.ServiceCategoryId)
                    .Select(s => s.YearsOfExperience).FirstOrDefault(),
                ConversationId = db.Conversations.Where(c => c.ServiceRequestId == q.ServiceRequestId && c.PartnerProfileId == q.PartnerProfileId)
                    .Select(c => (Guid?)c.Id).FirstOrDefault(),
            })
            .ToListAsync(ct);

        var dtos = rows.Select(x =>
        {
            x.PartnerProfile.User = x.PartnerUser;
            double? distance = x.PartnerProfile is { CurrentLatitude: { } lat, CurrentLongitude: { } lng }
                ? Matching.RoundKm(Geo.DistanceKm(lat, lng, request.Latitude, request.Longitude))
                : null;
            return ToCustomerDto(x.Quote, x.ConversationId, x.PartnerProfile, x.SkillYears, distance);
        });
        var ordered = sort switch
        {
            QuoteSort.Price => dtos.OrderBy(q => q.Amount).ThenBy(q => q.AvailableFrom),
            QuoteSort.Rating => dtos.OrderByDescending(q => q.Partner.AverageRating ?? -1).ThenByDescending(q => q.Partner.TotalReviews),
            _ => dtos.OrderBy(q => q.AvailableFrom),
        };
        var all = ordered.ThenBy(q => q.Id).ToList();
        return new PagedResult<CustomerQuoteDto>(all.Skip((page - 1) * pageSize).Take(pageSize).ToList(), all.Count, page, pageSize);
    }

    /// <summary>Request must be OPEN and not past ExpiresAt (the expiry job may not have run yet).</summary>
    public static void EnsureOpen(ServiceRequest request, DateTimeOffset now)
    {
        switch ((ServiceRequestStatus)request.Status)
        {
            case ServiceRequestStatus.Matched:
                throw new ApiException(StatusCodes.Status409Conflict, ErrorCodes.QuoteAlreadyAccepted, "Khách đã chọn đối tác khác");
            case ServiceRequestStatus.Expired:
            case ServiceRequestStatus.Open when request.ExpiresAt <= now:
                throw new ApiException(StatusCodes.Status422UnprocessableEntity, ErrorCodes.RequestExpired, "Bài đăng đã hết hạn");
            case ServiceRequestStatus.Open:
                return;
            default:
                throw new ApiException(StatusCodes.Status409Conflict, ErrorCodes.RequestNotOpen, "Bài đăng không còn nhận báo giá");
        }
    }

    private async Task EnsureCanQuoteAsync(PartnerContext partner, CancellationToken ct)
    {
        if (UserService.NeedsProfileCompletion(partner.Profile.User.FullName))
        {
            throw new ApiException(StatusCodes.Status422UnprocessableEntity, ErrorCodes.ProfileIncomplete, "Hãy cập nhật họ tên trước khi báo giá");
        }
        if (!partner.IsUserActive)
        {
            throw new ApiException(StatusCodes.Status403Forbidden, ErrorCodes.AccountLocked, "Tài khoản đã bị khoá");
        }
        if (!partner.IsApproved)
        {
            throw new ApiException(StatusCodes.Status403Forbidden, ErrorCodes.PartnerNotVerified, "Hồ sơ đối tác chưa được duyệt");
        }
        if (!partner.IsOnline)
        {
            throw new ApiException(StatusCodes.Status422UnprocessableEntity, ErrorCodes.PartnerOffline, "Bạn cần bật Online để báo giá");
        }

        var maxDebt = await configs.GetDecimalAsync("partner.max_commission_debt", 200_000, ct);
        var debt = await db.Wallets.Where(w => w.UserId == partner.Profile.UserId).Select(w => w.DebtBalance).FirstOrDefaultAsync(ct);
        if (debt > maxDebt)
        {
            throw new ApiException(StatusCodes.Status422UnprocessableEntity, ErrorCodes.OutstandingDebt,
                $"Nợ hoa hồng vượt {maxDebt:N0} đ, hãy thanh toán để tiếp tục nhận việc");
        }
    }

    /// <summary>availableFrom must be inside the post window: NOW = [now, end], SCHEDULED = [start, end].</summary>
    private static void EnsureInWindow(ServiceRequest request, DateTimeOffset availableFrom, DateTimeOffset now)
    {
        var start = request.ScheduleType == (byte)ScheduleType.Now ? now - ClockTolerance : request.ScheduledStartAt;
        if (availableFrom < start || availableFrom > request.ScheduledEndAt)
        {
            throw new ApiException(StatusCodes.Status400BadRequest, ErrorCodes.ValidationError,
                "Thời gian có mặt phải nằm trong khung giờ khách chọn", "availableFrom");
        }
    }

    /// <summary>One conversation per (post, partner), created with the first quote (spec 3.3.2 step 2).</summary>
    private async Task<Conversation> UpsertConversationAsync(ServiceRequest request, Guid partnerProfileId, DateTimeOffset now, CancellationToken ct)
    {
        var existing = await db.Conversations.FirstOrDefaultAsync(c => c.ServiceRequestId == request.Id && c.PartnerProfileId == partnerProfileId, ct);
        if (existing is not null)
        {
            return existing;
        }
        var conversation = new Conversation
        {
            Id = Guid.CreateVersion7(),
            ServiceRequestId = request.Id,
            CustomerId = request.CustomerId,
            PartnerProfileId = partnerProfileId,
            Status = (byte)ConversationStatus.Active,
            CreatedAt = now,
            UpdatedAt = now,
        };
        db.Conversations.Add(conversation);
        return conversation;
    }

    private static PartnerQuoteDto ToPartnerDto(Quote q, ServiceRequest r, Guid? conversationId) => new(
        q.Id, q.ServiceRequestId, r.Code, r.Title, r.ServiceCategory.Name, (ServiceRequestStatus)r.Status,
        (long)q.Amount, q.EstimatedDurationMinutes, q.AvailableFrom, q.EstimatedEndAt, q.Note, (QuoteStatus)q.Status, conversationId, q.CreatedAt);

    private static CustomerQuoteDto ToCustomerDto(Quote q, Guid? conversationId, PartnerProfile p, int skillYears, double? distanceKm) => new(
        q.Id, q.ServiceRequestId, (long)q.Amount, q.EstimatedDurationMinutes, q.AvailableFrom, q.EstimatedEndAt, q.Note, (QuoteStatus)q.Status,
        q.RowVersion is { Length: > 0 } version ? Convert.ToBase64String(version) : "",
        conversationId,
        new QuotePartnerDto(p.Id, p.User.FullName, p.User.AvatarUrl, p.AverageRating, p.TotalReviews, p.CompletedOrders, skillYears, distanceKm),
        q.CreatedAt);
}
