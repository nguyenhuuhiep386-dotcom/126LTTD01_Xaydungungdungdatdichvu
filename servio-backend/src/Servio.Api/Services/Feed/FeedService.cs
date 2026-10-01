using Microsoft.EntityFrameworkCore;
using Servio.Api.Common;
using Servio.Api.Data;
using Servio.Api.Data.Entities;
using Servio.Api.Services.Partners;
using Servio.Api.Services.Quotes;
using Servio.Api.Services.Requests;

namespace Servio.Api.Services.Feed;

/// <summary>One post in the partner newsfeed (#46) and the payload of the NewPost event.</summary>
public sealed record FeedItemDto(
    Guid RequestId,
    string Code,
    string Title,
    string DescriptionPreview,
    Guid CategoryId,
    string CategoryName,
    string? ThumbnailUrl,
    int ImageCount,
    double DistanceKm,
    string AreaLabel,
    long? BudgetMin,
    long? BudgetMax,
    ScheduleType ScheduleType,
    DateTimeOffset? ScheduledStartAt,
    DateTimeOffset? ScheduledEndAt,
    int QuoteCount,
    bool HasQuoted,
    CustomerBriefDto Customer,
    DateTimeOffset? PublishedAt,
    DateTimeOffset? ExpiresAt);

public enum FeedSort { Distance, Newest }

/// <summary>A partner with what matching needs: approved skills by category, online state and location.</summary>
public sealed record PartnerContext(
    PartnerProfile Profile,
    bool IsOnline,
    (decimal Latitude, decimal Longitude)? Location,
    IReadOnlyDictionary<Guid, PartnerSkill> Skills)
{
    public bool IsApproved => Profile.VerificationStatus == (byte)PartnerVerificationStatus.Approved;
    public bool IsUserActive => Profile.User.Status == (byte)UserStatus.Active;
}

/// <summary>Flat view of a post with everything the feed and the eligibility check need (one SQL query).</summary>
public sealed record RequestRow(
    Guid Id, string Code, string Title, string Description, Guid CategoryId, string CategoryName,
    Guid CustomerUserId, string CustomerName, string? CustomerAvatar, decimal? CustomerRating, int CustomerTotalOrders,
    string AddressSnapshot, decimal Latitude, decimal Longitude, byte ScheduleType, DateTimeOffset? ScheduledStartAt, DateTimeOffset? ScheduledEndAt,
    decimal? BudgetMin, decimal? BudgetMax, int? RequireExperienceYears, bool RequireCertificate, decimal? RequireMinRating, int SearchRadiusKm,
    byte Status, int Revision, int PendingQuotes, DateTimeOffset? PublishedAt, DateTimeOffset? ExpiresAt,
    string? ThumbnailUrl, int ImageCount, bool HasQuoted);

/// <summary>
/// M3 newsfeed (#46), the partner view of a post (#38) and the NewPost recipients (F-FEED-02).
/// SQL pre-filters by status, skill category and a coordinate box; the exact radius and requirements are checked in C#
/// with <see cref="Evaluate"/> (spec 0.2.2 row 3). The feed always re-reads status/expiry, nothing is cached.
/// </summary>
public sealed class FeedService(ServioDbContext db, SystemConfigService configs, TimeProvider clock)
{
    public const int MaxPageSize = 50;

    // ponytail: candidates are capped before the in-memory radius check; fine for a course demo with a few hundred posts.
    private const int CandidateLimit = 500;

    public async Task<PagedResult<FeedItemDto>> GetFeedAsync(
        Guid userId, IReadOnlyCollection<Guid>? categoryIds, double? maxDistanceKm, FeedSort sort, int page, int pageSize, CancellationToken ct)
    {
        var partner = await LoadPartnerAsync(userId, ct);
        if (!partner.IsApproved)
        {
            throw new ApiException(StatusCodes.Status403Forbidden, ErrorCodes.PartnerNotVerified, "Hồ sơ đối tác chưa được duyệt");
        }
        (page, pageSize) = (Math.Max(page, 1), Math.Clamp(pageSize, 1, MaxPageSize));
        if (partner.Location is not { } location || partner.Skills.Count == 0)
        {
            return new PagedResult<FeedItemDto>([], 0, page, pageSize);
        }

        var now = clock.GetUtcNow();
        var skillCategories = partner.Skills.Keys.Where(id => categoryIds is null || categoryIds.Count == 0 || categoryIds.Contains(id)).ToList();
        var box = Matching.Box(location.Latitude, location.Longitude, Math.Min(partner.Profile.ServiceRadiusKm, Matching.MaxRadiusKm));
        var rows = await Rows(db.ServiceRequests.AsNoTracking().Where(r =>
                    r.Status == (byte)ServiceRequestStatus.Open && r.ExpiresAt > now
                    && skillCategories.Contains(r.ServiceCategoryId)
                    && r.Customer.UserId != userId
                    && r.Latitude >= box.MinLat && r.Latitude <= box.MaxLat
                    && r.Longitude >= box.MinLng && r.Longitude <= box.MaxLng),
                partner.Profile.Id)
            .Take(CandidateLimit)
            .ToListAsync(ct);

        var matches = rows
            .Select(row => (Row: row, Distance: Evaluate(partner, row)))
            .Where(x => x.Distance is { } d && (maxDistanceKm is null || d <= maxDistanceKm))
            .Select(x => (x.Row, Distance: x.Distance!.Value));
        var ordered = sort == FeedSort.Newest
            ? matches.OrderByDescending(x => x.Row.PublishedAt).ThenBy(x => x.Distance).ThenBy(x => x.Row.Id)
            : matches.OrderBy(x => x.Distance).ThenByDescending(x => x.Row.PublishedAt).ThenBy(x => x.Row.Id);
        var all = ordered.ToList();

        var items = all.Skip((page - 1) * pageSize).Take(pageSize).Select(x => ToFeedItem(x.Row, x.Distance)).ToList();
        return new PagedResult<FeedItemDto>(items, all.Count, page, pageSize);
    }

    /// <summary>
    /// #38 for a partner (PS-10). Visible when the partner already quoted it, or when it is OPEN and the partner is
    /// eligible (<see cref="Evaluate"/>); otherwise 404. Never returns the exact address or coordinates.
    /// </summary>
    public async Task<PartnerRequestViewDto> GetForPartnerAsync(Guid userId, Guid requestId, CancellationToken ct)
    {
        var partner = await LoadPartnerAsync(userId, ct);
        var row = await LoadRowAsync(requestId, partner.Profile.Id, ct) ?? throw ApiException.NotFound("Không tìm thấy bài đăng");
        var now = clock.GetUtcNow();
        var isOpen = row.Status == (byte)ServiceRequestStatus.Open && row.ExpiresAt > now;
        var eligibleDistance = partner.IsApproved && partner.IsUserActive ? Evaluate(partner, row) : null;

        var ownQuote = await db.Quotes.AsNoTracking()
            .Where(q => q.ServiceRequestId == requestId && q.PartnerProfileId == partner.Profile.Id)
            .OrderByDescending(q => q.RequestRevision).ThenByDescending(q => q.CreatedAt)
            .Select(q => new PartnerQuoteDto(
                q.Id, q.ServiceRequestId, q.ServiceRequest.Code, q.ServiceRequest.Title, q.ServiceRequest.ServiceCategory.Name,
                (ServiceRequestStatus)q.ServiceRequest.Status, (long)q.Amount, q.EstimatedDurationMinutes, q.AvailableFrom, q.EstimatedEndAt,
                q.Note, (QuoteStatus)q.Status,
                db.Conversations.Where(c => c.ServiceRequestId == q.ServiceRequestId && c.PartnerProfileId == q.PartnerProfileId)
                    .Select(c => (Guid?)c.Id).FirstOrDefault(),
                q.CreatedAt))
            .FirstOrDefaultAsync(ct);
        if (ownQuote is null && (!isOpen || eligibleDistance is null))
        {
            throw ApiException.NotFound("Không tìm thấy bài đăng");
        }

        var images = await db.ServiceRequestImages.AsNoTracking()
            .Where(i => i.ServiceRequestId == requestId).OrderBy(i => i.DisplayOrder).Select(i => i.Url).ToListAsync(ct);
        double? distance = partner.Location is { } loc ? Matching.RoundKm(Geo.DistanceKm(loc.Latitude, loc.Longitude, row.Latitude, row.Longitude)) : null;

        return new PartnerRequestViewDto(
            row.Id, row.Code, (ServiceRequestStatus)row.Status, row.Revision, row.CategoryId, row.CategoryName, row.Title, row.Description,
            images, Matching.AreaLabel(row.AddressSnapshot), distance, (ScheduleType)row.ScheduleType, row.ScheduledStartAt, row.ScheduledEndAt,
            (long?)row.BudgetMin, (long?)row.BudgetMax, row.RequireExperienceYears, row.RequireCertificate, row.RequireMinRating,
            row.PendingQuotes, CustomerBrief(row), ownQuote,
            CanQuote: isOpen && eligibleDistance is not null && partner.IsOnline && !row.HasQuoted,
            row.PublishedAt, row.ExpiresAt);
    }

    /// <summary>
    /// Online, eligible partners for a new post, nearest first, at most matching.max_broadcast_partners (50).
    /// Each gets a FeedItem with their own distance.
    /// </summary>
    public async Task<IReadOnlyList<(Guid PartnerUserId, FeedItemDto Item)>> FindRecipientsAsync(Guid requestId, CancellationToken ct)
    {
        var row = await LoadRowAsync(requestId, Guid.Empty, ct);
        if (row is null || row.Status != (byte)ServiceRequestStatus.Open)
        {
            return [];
        }

        var now = clock.GetUtcNow();
        var timeout = await configs.GetIntAsync(SystemConfigService.PartnerOfflineTimeoutMinutes, 10, ct);
        var limit = await configs.GetIntAsync("matching.max_broadcast_partners", 50, ct);
        var onlineSince = now.AddMinutes(-timeout);
        var box = Matching.Box(row.Latitude, row.Longitude, Matching.MaxRadiusKm);

        var candidates = await db.PartnerProfiles.AsNoTracking()
            .Include(p => p.User)
            .Include(p => p.PartnerSkills.Where(s => s.Status == (byte)ApprovalStatus.Approved))
            .Where(p => p.VerificationStatus == (byte)PartnerVerificationStatus.Approved
                        && p.User.Status == (byte)UserStatus.Active
                        && p.UserId != row.CustomerUserId
                        && p.IsOnline && p.LastHeartbeatAt >= onlineSince
                        && p.CurrentLatitude >= box.MinLat && p.CurrentLatitude <= box.MaxLat
                        && p.CurrentLongitude >= box.MinLng && p.CurrentLongitude <= box.MaxLng
                        && p.PartnerSkills.Any(s => s.ServiceCategoryId == row.CategoryId && s.Status == (byte)ApprovalStatus.Approved))
            .Take(CandidateLimit)
            .ToListAsync(ct);

        return candidates
            .Select(p => (Partner: p, Distance: Evaluate(ToContext(p, isOnline: true), row)))
            .Where(x => x.Distance is not null)
            .OrderBy(x => x.Distance).ThenBy(x => x.Partner.Id)
            .Take(limit)
            .Select(x => (x.Partner.UserId, ToFeedItem(row, x.Distance!.Value)))
            .ToList();
    }

    /// <summary>
    /// Distance in km when the partner may see and quote this post, otherwise null: not own post, approved skill for
    /// the category meeting the post requirements, and within min(service radius, post radius) of the partner location.
    /// Online and post status are checked by the callers.
    /// </summary>
    public static double? Evaluate(PartnerContext partner, RequestRow row)
    {
        if (row.CustomerUserId == partner.Profile.UserId
            || !partner.Skills.TryGetValue(row.CategoryId, out var skill)
            || !Matching.MeetsRequirements(skill, partner.Profile.AverageRating, row.RequireExperienceYears, row.RequireCertificate, row.RequireMinRating)
            || partner.Location is not { } location)
        {
            return null;
        }
        var distance = Geo.DistanceKm(location.Latitude, location.Longitude, row.Latitude, row.Longitude);
        return distance <= Matching.RadiusKm(partner.Profile.ServiceRadiusKm, row.SearchRadiusKm) ? distance : null;
    }

    public async Task<PartnerContext> LoadPartnerAsync(Guid userId, CancellationToken ct)
    {
        var profile = await db.PartnerProfiles.AsNoTracking()
                          .Include(p => p.User)
                          .Include(p => p.PartnerSkills.Where(s => s.Status == (byte)ApprovalStatus.Approved))
                          .FirstOrDefaultAsync(p => p.UserId == userId, ct)
                      ?? throw ApiException.NotFound("Không tìm thấy hồ sơ đối tác");
        var timeout = await configs.GetIntAsync(SystemConfigService.PartnerOfflineTimeoutMinutes, 10, ct);
        return ToContext(profile, PartnerProfileService.IsEffectivelyOnline(profile, clock.GetUtcNow(), timeout));
    }

    /// <summary>Loads one post as a <see cref="RequestRow"/>; HasQuoted refers to <paramref name="partnerProfileId"/> in the current revision.</summary>
    public Task<RequestRow?> LoadRowAsync(Guid requestId, Guid partnerProfileId, CancellationToken ct) =>
        Rows(db.ServiceRequests.AsNoTracking().Where(r => r.Id == requestId), partnerProfileId).FirstOrDefaultAsync(ct);

    public static FeedItemDto ToFeedItem(RequestRow r, double distanceKm) => new(
        r.Id,
        r.Code,
        r.Title,
        r.Description.Length <= 140 ? r.Description : r.Description[..140] + "…",
        r.CategoryId,
        r.CategoryName,
        r.ThumbnailUrl,
        r.ImageCount,
        Matching.RoundKm(distanceKm),
        Matching.AreaLabel(r.AddressSnapshot),
        (long?)r.BudgetMin,
        (long?)r.BudgetMax,
        (ScheduleType)r.ScheduleType,
        r.ScheduledStartAt,
        r.ScheduledEndAt,
        r.PendingQuotes,
        r.HasQuoted,
        CustomerBrief(r),
        r.PublishedAt,
        r.ExpiresAt);

    public static CustomerBriefDto CustomerBrief(RequestRow r) =>
        new(PartnerPublicService.ShortName(r.CustomerName), r.CustomerAvatar, r.CustomerRating, r.CustomerTotalOrders);

    private static PartnerContext ToContext(PartnerProfile profile, bool isOnline) => new(
        profile,
        isOnline,
        Matching.LocationOf(profile, isOnline),
        profile.PartnerSkills.Where(s => s.Status == (byte)ApprovalStatus.Approved).ToDictionary(s => s.ServiceCategoryId));

    /// <summary>
    /// Projection used by every feed query. PendingQuotes is counted live (PENDING quotes of the current revision)
    /// instead of reading the denormalized ServiceRequests.QuoteCount, so concurrent quotes never race on the post row.
    /// </summary>
    private static IQueryable<RequestRow> Rows(IQueryable<ServiceRequest> query, Guid partnerProfileId) =>
        query.Select(r => new RequestRow(
            r.Id, r.Code, r.Title, r.Description, r.ServiceCategoryId, r.ServiceCategory.Name,
            r.Customer.UserId, r.Customer.User.FullName, r.Customer.User.AvatarUrl, r.Customer.AverageRating, r.Customer.TotalOrders,
            r.AddressSnapshot, r.Latitude, r.Longitude, r.ScheduleType, r.ScheduledStartAt, r.ScheduledEndAt,
            r.BudgetMin, r.BudgetMax, r.RequireExperienceYears, r.RequireCertificate, r.RequireMinRating, r.SearchRadiusKm,
            r.Status, r.Revision,
            r.Quotes.Count(q => q.Status == (byte)QuoteStatus.Pending && q.RequestRevision == r.Revision),
            r.PublishedAt, r.ExpiresAt,
            r.ServiceRequestImages.OrderBy(i => i.DisplayOrder).Select(i => i.Url).FirstOrDefault(),
            r.ServiceRequestImages.Count,
            r.Quotes.Any(q => q.PartnerProfileId == partnerProfileId && q.RequestRevision == r.Revision)));
}
