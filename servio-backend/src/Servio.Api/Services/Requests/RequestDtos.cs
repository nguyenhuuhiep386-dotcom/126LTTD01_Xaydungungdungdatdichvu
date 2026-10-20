using System.ComponentModel.DataAnnotations;
using Servio.Api.Common;
using Servio.Api.Services.Quotes;

namespace Servio.Api.Services.Requests;

/// <summary>
/// #36 POST /service-requests (spec 6.3.1, course scope): one partner, POST_AND_QUOTE only.
/// NOW ignores the schedule fields; SCHEDULED needs scheduledStartAt in [now+30 min, now+30 days], the window is 2 hours.
/// Images are URLs returned by POST /files with purpose POST_IMAGE (max 6).
/// </summary>
public sealed record CreateServiceRequestRequest(
    Guid ServiceCategoryId,
    [Required(ErrorMessage = "Nhập tiêu đề"), StringLength(150, MinimumLength = 5, ErrorMessage = "Tiêu đề dài 5–150 ký tự")] string Title,
    [Required(ErrorMessage = "Nhập mô tả"), StringLength(2000, MinimumLength = 20, ErrorMessage = "Mô tả dài 20–2000 ký tự")] string Description,
    Guid AddressId,
    IReadOnlyList<RequestImageInput>? Images,
    ScheduleType ScheduleType,
    DateTimeOffset? ScheduledStartAt,
    long? BudgetMin,
    long? BudgetMax,
    [Range(0, 60)] int? RequireExperienceYears,
    bool RequireCertificate,
    [Range(1.0, 5.0)] decimal? RequireMinRating,
    int? SearchRadiusKm);

public sealed record RequestImageInput([Required] string Url);

/// <summary>#40 POST /service-requests/{id}/cancel</summary>
public sealed record CancelRequestRequest([StringLength(500)] string? Reason);

/// <summary>Row of #37 GET /service-requests/me.</summary>
public sealed record RequestSummaryDto(
    Guid Id,
    string Code,
    string Title,
    Guid CategoryId,
    string CategoryName,
    ServiceRequestStatus Status,
    int QuoteCount,
    string AddressSnapshot,
    ScheduleType ScheduleType,
    DateTimeOffset? ScheduledStartAt,
    DateTimeOffset? ExpiresAt,
    string? ThumbnailUrl,
    DateTimeOffset CreatedAt);

/// <summary>#36 response and #38 for the owner (customer app): full detail including the exact address.</summary>
public sealed record RequestDetailDto(
    Guid Id,
    string Code,
    ServiceRequestStatus Status,
    int Revision,
    Guid CategoryId,
    string CategoryName,
    string Title,
    string Description,
    IReadOnlyList<string> Images,
    Guid AddressId,
    string AddressSnapshot,
    decimal Latitude,
    decimal Longitude,
    ScheduleType ScheduleType,
    DateTimeOffset? ScheduledStartAt,
    DateTimeOffset? ScheduledEndAt,
    long? BudgetMin,
    long? BudgetMax,
    int? RequireExperienceYears,
    bool RequireCertificate,
    decimal? RequireMinRating,
    int SearchRadiusKm,
    int QuoteCount,
    DateTimeOffset? PublishedAt,
    DateTimeOffset? ExpiresAt,
    string? CancelReason,
    DateTimeOffset CreatedAt);

/// <summary>
/// #38 for a partner (partner app, PS-10): no exact address, phone or coordinates before an order is ACCEPTED,
/// only an area label and the distance.
/// </summary>
public sealed record PartnerRequestViewDto(
    Guid Id,
    string Code,
    ServiceRequestStatus Status,
    int Revision,
    Guid CategoryId,
    string CategoryName,
    string Title,
    string Description,
    IReadOnlyList<string> Images,
    string AreaLabel,
    double? DistanceKm,
    ScheduleType ScheduleType,
    DateTimeOffset? ScheduledStartAt,
    DateTimeOffset? ScheduledEndAt,
    long? BudgetMin,
    long? BudgetMax,
    int? RequireExperienceYears,
    bool RequireCertificate,
    decimal? RequireMinRating,
    int QuoteCount,
    CustomerBriefDto Customer,
    PartnerQuoteDto? OwnQuote,
    bool CanQuote,
    DateTimeOffset? PublishedAt,
    DateTimeOffset? ExpiresAt);

/// <summary>What a partner may know about the customer before an order: short name, rating, order count.</summary>
public sealed record CustomerBriefDto(string DisplayName, string? AvatarUrl, decimal? Rating, int TotalOrders);
