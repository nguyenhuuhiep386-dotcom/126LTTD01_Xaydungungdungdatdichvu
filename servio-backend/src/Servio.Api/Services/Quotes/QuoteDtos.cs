using System.ComponentModel.DataAnnotations;
using Servio.Api.Common;

namespace Servio.Api.Services.Quotes;

/// <summary>
/// #50 POST /service-requests/{id}/quotes. amount in whole VND (> 0). availableFrom must fall inside the post's
/// time window. requestRevision = the revision the partner saw (from #38/#46), otherwise 409 RESOURCE_VERSION_CONFLICT.
/// </summary>
public sealed record SubmitQuoteRequest(
    [Range(1, 1_000_000_000, ErrorMessage = "Giá báo phải lớn hơn 0")] long Amount,
    [Range(15, 1440, ErrorMessage = "Thời gian làm từ 15 phút đến 24 giờ")] int EstimatedDurationMinutes,
    DateTimeOffset AvailableFrom,
    [StringLength(500)] string? Note,
    int RequestRevision);

/// <summary>#52 POST /quotes/{id}/withdraw. The reason is optional and not stored in the course scope.</summary>
public sealed record WithdrawQuoteRequest([StringLength(500)] string? Reason);

/// <summary>A quote as its partner sees it (#50 response, #53, own quote in #38).</summary>
public sealed record PartnerQuoteDto(
    Guid Id,
    Guid ServiceRequestId,
    string RequestCode,
    string RequestTitle,
    string CategoryName,
    ServiceRequestStatus RequestStatus,
    long Amount,
    int EstimatedDurationMinutes,
    DateTimeOffset AvailableFrom,
    DateTimeOffset EstimatedEndAt,
    string? Note,
    QuoteStatus Status,
    Guid? ConversationId,
    DateTimeOffset CreatedAt);

/// <summary>
/// A quote as the customer sees it (#42, NewQuote event). rowVersion is sent back when accepting (#43, BE-4).
/// </summary>
public sealed record CustomerQuoteDto(
    Guid Id,
    Guid ServiceRequestId,
    long Amount,
    int EstimatedDurationMinutes,
    DateTimeOffset AvailableFrom,
    DateTimeOffset EstimatedEndAt,
    string? Note,
    QuoteStatus Status,
    string RowVersion,
    Guid? ConversationId,
    QuotePartnerDto Partner,
    DateTimeOffset CreatedAt);

/// <summary>Partner info shown on a quote card (CS-15/CS-16). Phone and exact location are never included.</summary>
public sealed record QuotePartnerDto(
    Guid Id,
    string FullName,
    string? AvatarUrl,
    decimal? AverageRating,
    int TotalReviews,
    int CompletedOrders,
    int YearsOfExperience,
    double? DistanceKm);

public enum QuoteSort { Time, Price, Rating }
