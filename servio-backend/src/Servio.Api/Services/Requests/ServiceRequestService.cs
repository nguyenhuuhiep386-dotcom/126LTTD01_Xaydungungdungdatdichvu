using Microsoft.EntityFrameworkCore;
using Servio.Api.Common;
using Servio.Api.Data;
using Servio.Api.Data.Entities;
using Servio.Api.Hubs;
using Servio.Api.Services.Feed;
using Servio.Api.Services.Files;
using Servio.Api.Services.Users;

namespace Servio.Api.Services.Requests;

/// <summary>
/// M2 posts: #36 create, #37 my posts, #38 owner view, #40 cancel, automatic expiry (F-REQ-01..11, spec 0.2.7 ý 1).
/// Course scope: no manual moderation queue — a post without phone numbers/links goes straight to OPEN and is
/// broadcast to matching online partners (NewPost). Editing a post (#39) is out of scope, so Revision stays 1.
/// </summary>
public sealed class ServiceRequestService(
    ServioDbContext db,
    FileService files,
    FeedService feed,
    CodeGenerator codes,
    SystemConfigService configs,
    IRealtimeNotifier notifier,
    TimeProvider clock)
{
    public const int MaxImages = 6;
    public const int MaxPageSize = 50;
    public const int MinSearchRadiusKm = 3;

    private static readonly TimeSpan Window = TimeSpan.FromHours(2);
    private static readonly TimeSpan MinLeadTime = TimeSpan.FromMinutes(30);
    private static readonly TimeSpan MaxLeadTime = TimeSpan.FromDays(30);

    public async Task<RequestDetailDto> CreateAsync(Guid userId, CreateServiceRequestRequest input, CancellationToken ct)
    {
        var now = clock.GetUtcNow();
        var customer = await db.CustomerProfiles.Include(c => c.User).FirstOrDefaultAsync(c => c.UserId == userId, ct)
                       ?? throw ApiException.NotFound("Không tìm thấy hồ sơ khách hàng");
        await EnsureCanPostAsync(customer, now, ct);

        var title = input.Title.Trim();
        var description = input.Description.Trim();
        if (title.Length < 5) throw Invalid("Tiêu đề dài 5–150 ký tự", "title");
        if (description.Length < 20) throw Invalid("Mô tả dài 20–2000 ký tự", "description");
        ContentGuard.EnsureClean(title, "title");
        ContentGuard.EnsureClean(description, "description");

        var category = await db.ServiceCategories.AsNoTracking()
                           .FirstOrDefaultAsync(c => c.Id == input.ServiceCategoryId && c.IsActive && c.ParentId != null, ct)
                       ?? throw Invalid("Hãy chọn một dịch vụ cụ thể (danh mục cấp 2)", "serviceCategoryId");
        var address = await db.Addresses.AsNoTracking()
                          .FirstOrDefaultAsync(a => a.Id == input.AddressId && a.UserId == userId && a.DeletedAt == null, ct)
                      ?? throw Invalid("Địa chỉ không hợp lệ", "addressId");

        if (input.BudgetMin is null != input.BudgetMax is null || input.BudgetMin < 0 || input.BudgetMin > input.BudgetMax)
        {
            throw Invalid("Ngân sách: nhập cả hai mức từ ≤ đến, hoặc để trống để đối tác báo giá", "budgetMin");
        }
        var radius = input.SearchRadiusKm ?? await configs.GetIntAsync("matching.default_radius_km", 10, ct);
        if (radius is < MinSearchRadiusKm or > Matching.MaxRadiusKm)
        {
            throw Invalid($"Bán kính tìm từ {MinSearchRadiusKm} đến {Matching.MaxRadiusKm} km", "searchRadiusKm");
        }
        var (start, end) = Schedule(input, now);
        var images = await ResolveImagesAsync(userId, input.Images, ct);

        var expireHours = await configs.GetIntAsync("post.expire_hours", 24, ct);
        var request = new ServiceRequest
        {
            Id = Guid.CreateVersion7(),
            Code = await codes.NextAsync(CodeGenerator.ServiceRequest, ct),
            CustomerId = customer.Id,
            ServiceCategoryId = category.Id,
            Title = title,
            Description = description,
            AddressId = address.Id,
            AddressSnapshot = address.FullAddress,
            Latitude = address.Latitude,
            Longitude = address.Longitude,
            ScheduleType = (byte)input.ScheduleType,
            ScheduledStartAt = start,
            ScheduledEndAt = end,
            BudgetMin = input.BudgetMin,
            BudgetMax = input.BudgetMax,
            RequireExperienceYears = input.RequireExperienceYears,
            RequireCertificate = input.RequireCertificate,
            RequireMinRating = input.RequireMinRating,
            SearchRadiusKm = radius,
            Status = (byte)ServiceRequestStatus.Open,
            Revision = 1,
            PublishedAt = now,
            // F-REQ-11: NOW → end of the 2h window; SCHEDULED → min(published + post.expire_hours, start).
            ExpiresAt = input.ScheduleType == ScheduleType.Now ? end : Min(now.AddHours(expireHours), start),
            CreatedAt = now,
            UpdatedAt = now,
        };
        for (var i = 0; i < images.Count; i++)
        {
            request.ServiceRequestImages.Add(new ServiceRequestImage { Id = Guid.CreateVersion7(), Url = images[i], MediaType = 1, DisplayOrder = i });
        }
        db.ServiceRequests.Add(request);
        await db.SaveChangesAsync(ct);

        await notifier.NewPostAsync(await feed.FindRecipientsAsync(request.Id, ct), ct);
        return await GetForOwnerAsync(userId, request.Id, ct);
    }

    /// <summary>#37: own posts, newest first. <paramref name="statuses"/> empty = all (CS-14 tabs pass OPEN / MATCHED / the rest).</summary>
    public async Task<PagedResult<RequestSummaryDto>> ListMineAsync(
        Guid userId, IReadOnlyCollection<ServiceRequestStatus> statuses, int page, int pageSize, CancellationToken ct)
    {
        (page, pageSize) = (Math.Max(page, 1), Math.Clamp(pageSize, 1, MaxPageSize));
        var codes = statuses.Select(s => (byte)s).ToList();
        var query = db.ServiceRequests.AsNoTracking().Where(r => r.Customer.UserId == userId && (codes.Count == 0 || codes.Contains(r.Status)));
        var total = await query.CountAsync(ct);
        var items = await query
            .OrderByDescending(r => r.CreatedAt).ThenByDescending(r => r.Id)
            .Skip((page - 1) * pageSize).Take(pageSize)
            .Select(r => new RequestSummaryDto(
                r.Id, r.Code, r.Title, r.ServiceCategoryId, r.ServiceCategory.Name, (ServiceRequestStatus)r.Status,
                r.Quotes.Count(q => q.Status == (byte)QuoteStatus.Pending && q.RequestRevision == r.Revision),
                r.AddressSnapshot, (ScheduleType)r.ScheduleType, r.ScheduledStartAt, r.ExpiresAt,
                r.ServiceRequestImages.OrderBy(i => i.DisplayOrder).Select(i => i.Url).FirstOrDefault(),
                r.CreatedAt))
            .ToListAsync(ct);
        return new PagedResult<RequestSummaryDto>(items, total, page, pageSize);
    }

    /// <summary>#38 for the owner. Others get 404.</summary>
    public async Task<RequestDetailDto> GetForOwnerAsync(Guid userId, Guid requestId, CancellationToken ct)
    {
        var r = await db.ServiceRequests.AsNoTracking()
                    .Where(x => x.Id == requestId && x.Customer.UserId == userId)
                    .Select(x => new
                    {
                        Request = x,
                        CategoryName = x.ServiceCategory.Name,
                        Images = x.ServiceRequestImages.OrderBy(i => i.DisplayOrder).Select(i => i.Url).ToList(),
                        PendingQuotes = x.Quotes.Count(q => q.Status == (byte)QuoteStatus.Pending && q.RequestRevision == x.Revision),
                    })
                    .FirstOrDefaultAsync(ct)
                ?? throw ApiException.NotFound("Không tìm thấy yêu cầu");
        var s = r.Request;
        return new RequestDetailDto(
            s.Id, s.Code, (ServiceRequestStatus)s.Status, s.Revision, s.ServiceCategoryId, r.CategoryName, s.Title, s.Description, r.Images,
            s.AddressId, s.AddressSnapshot, s.Latitude, s.Longitude, (ScheduleType)s.ScheduleType, s.ScheduledStartAt, s.ScheduledEndAt,
            (long?)s.BudgetMin, (long?)s.BudgetMax, s.RequireExperienceYears, s.RequireCertificate, s.RequireMinRating, s.SearchRadiusKm,
            r.PendingQuotes, s.PublishedAt, s.ExpiresAt, s.CancelReason, s.CreatedAt);
    }

    /// <summary>
    /// #40: OPEN/DRAFT → CANCELLED; pending quotes expire. More than 5 cancellations in 7 days restricts posting
    /// for 24 hours (F-REQ-10).
    /// </summary>
    public async Task CancelAsync(Guid userId, Guid requestId, string? reason, CancellationToken ct)
    {
        var request = await db.ServiceRequests
                          .Include(r => r.Customer).ThenInclude(c => c.User)
                          .Include(r => r.Quotes.Where(q => q.Status == (byte)QuoteStatus.Pending))
                          .FirstOrDefaultAsync(r => r.Id == requestId && r.Customer.UserId == userId, ct)
                      ?? throw ApiException.NotFound("Không tìm thấy yêu cầu");
        if (request.Status is not ((byte)ServiceRequestStatus.Open or (byte)ServiceRequestStatus.Draft))
        {
            var message = request.Status == (byte)ServiceRequestStatus.Matched
                ? "Yêu cầu đã chọn đối tác, hãy huỷ đơn hàng thay vì huỷ bài"
                : "Yêu cầu đã kết thúc";
            throw new ApiException(StatusCodes.Status409Conflict, ErrorCodes.InvalidStatusTransition, message);
        }

        var now = clock.GetUtcNow();
        request.Status = (byte)ServiceRequestStatus.Cancelled;
        request.CancelReason = string.IsNullOrWhiteSpace(reason) ? null : reason.Trim();
        request.UpdatedAt = now;
        ExpireQuotes(request.Quotes, now);

        var recentCancels = await db.ServiceRequests.CountAsync(r =>
            r.CustomerId == request.CustomerId && r.Status == (byte)ServiceRequestStatus.Cancelled && r.UpdatedAt >= now.AddDays(-7) && r.Id != request.Id, ct);
        if (recentCancels + 1 > 5)
        {
            request.Customer.User.PostingRestrictedUntil = now.AddHours(24);
        }
        await db.SaveChangesAsync(ct);
    }

    /// <summary>
    /// Called by RequestExpiryJob every 30 s: OPEN posts past ExpiresAt → EXPIRED and their pending quotes → EXPIRED.
    /// Returns how many posts expired.
    /// </summary>
    public async Task<int> ExpireDueAsync(CancellationToken ct)
    {
        var now = clock.GetUtcNow();
        var due = await db.ServiceRequests
            .Include(r => r.Quotes.Where(q => q.Status == (byte)QuoteStatus.Pending))
            .Where(r => r.Status == (byte)ServiceRequestStatus.Open && r.ExpiresAt <= now)
            .OrderBy(r => r.ExpiresAt)
            .Take(200)
            .ToListAsync(ct);
        foreach (var request in due)
        {
            request.Status = (byte)ServiceRequestStatus.Expired;
            request.UpdatedAt = now;
            ExpireQuotes(request.Quotes, now);
        }
        await db.SaveChangesAsync(ct);
        return due.Count;
    }

    private async Task EnsureCanPostAsync(CustomerProfile customer, DateTimeOffset now, CancellationToken ct)
    {
        if (UserService.NeedsProfileCompletion(customer.User.FullName))
        {
            throw new ApiException(StatusCodes.Status422UnprocessableEntity, ErrorCodes.ProfileIncomplete, "Hãy cập nhật họ tên trước khi đăng yêu cầu");
        }
        if (customer.User.PostingRestrictedUntil > now)
        {
            throw new ApiException(StatusCodes.Status403Forbidden, ErrorCodes.PostingRestricted,
                $"Bạn huỷ bài quá nhiều lần, có thể đăng lại sau {customer.User.PostingRestrictedUntil.Value.ToOffset(TimeSpan.FromHours(7)):HH:mm dd/MM}");
        }

        // Spec 0.2.7 ý 8: unpaid cancellation fees above the limit block new posts.
        var maxDebt = await configs.GetDecimalAsync("cancellation.max_outstanding_amount", 100_000, ct);
        var due = await db.CancellationCharges.Where(c => c.CustomerId == customer.Id && c.Status == 1).SumAsync(c => (decimal?)c.Amount, ct) ?? 0;
        if (due > maxDebt)
        {
            throw new ApiException(StatusCodes.Status422UnprocessableEntity, ErrorCodes.OutstandingDebt,
                $"Bạn còn {due:N0} đ phí huỷ chưa thanh toán, hãy thanh toán để đăng yêu cầu mới");
        }
    }

    /// <summary>NOW: [now, now+2h]. SCHEDULED: start in [now+30 min, now+30 days], end = start + 2h.</summary>
    private static (DateTimeOffset Start, DateTimeOffset End) Schedule(CreateServiceRequestRequest input, DateTimeOffset now)
    {
        switch (input.ScheduleType)
        {
            case ScheduleType.Now:
                return (now, now + Window);
            case ScheduleType.Scheduled when input.ScheduledStartAt is { } start && start >= now + MinLeadTime && start <= now + MaxLeadTime:
                return (start, start + Window);
            case ScheduleType.Scheduled:
                throw Invalid("Giờ hẹn phải sau ít nhất 30 phút và không quá 30 ngày", "scheduledStartAt");
            default:
                throw Invalid("Giá trị không hợp lệ", "scheduleType");
        }
    }

    private async Task<IReadOnlyList<string>> ResolveImagesAsync(Guid userId, IReadOnlyList<RequestImageInput>? images, CancellationToken ct)
    {
        var urls = (images ?? []).Select(i => i.Url).Distinct().ToList();
        if (urls.Count > MaxImages)
        {
            throw Invalid($"Tối đa {MaxImages} ảnh", "images");
        }
        var resolved = new List<string>(urls.Count);
        for (var i = 0; i < urls.Count; i++)
        {
            var file = await files.ResolveOwnedAsync(userId, urls[i], $"images[{i}].url", ct, FilePurpose.PostImage);
            resolved.Add(FileService.UrlOf(file));
        }
        return resolved;
    }

    private static void ExpireQuotes(IEnumerable<Quote> quotes, DateTimeOffset now)
    {
        foreach (var quote in quotes.Where(q => q.Status == (byte)QuoteStatus.Pending))
        {
            quote.Status = (byte)QuoteStatus.Expired;
            quote.UpdatedAt = now;
        }
    }

    private static DateTimeOffset Min(DateTimeOffset a, DateTimeOffset b) => a < b ? a : b;

    private static ApiException Invalid(string message, string field) =>
        new(StatusCodes.Status400BadRequest, ErrorCodes.ValidationError, message, field);
}
