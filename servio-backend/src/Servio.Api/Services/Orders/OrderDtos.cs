using System.ComponentModel.DataAnnotations;
using Servio.Api.Common;

namespace Servio.Api.Services.Orders;

/// <summary>
/// #43 POST /service-requests/{id}/accept-quotes, course scope 0.2.4: exactly one quote, CASH only.
/// quoteVersions maps quoteId → rowVersion from #42; a changed quote gives 409 RESOURCE_VERSION_CONFLICT.
/// </summary>
public sealed record AcceptQuotesRequest(
    [Required, MinLength(1, ErrorMessage = "Hãy chọn một báo giá")] Guid[] QuoteIds,
    [Required(ErrorMessage = "Thiếu phiên bản báo giá")] Dictionary<Guid, string> QuoteVersions,
    PaymentMethod PaymentMethod = PaymentMethod.Cash);

/// <summary>#66 POST /orders/{id}/assignments/{aid}/reject.</summary>
public sealed record RejectAssignmentRequest(
    [Required(ErrorMessage = "Hãy nhập lý do"), StringLength(500, MinimumLength = 3, ErrorMessage = "Lý do dài 3–500 ký tự")] string Reason);

/// <summary>#69 start-work: 1–10 BEFORE photo URLs from POST /files (purpose ORDER_IMAGE).</summary>
public sealed record StartWorkRequest(
    [Required, MinLength(1, ErrorMessage = "Cần ít nhất 1 ảnh trước khi làm"), MaxLength(10, ErrorMessage = "Tối đa 10 ảnh")] string[] BeforeImages);

/// <summary>#70 complete: 1–10 AFTER photo URLs (purpose ORDER_IMAGE) and an optional note for the customer.</summary>
public sealed record CompleteWorkRequest(
    [Required, MinLength(1, ErrorMessage = "Cần ít nhất 1 ảnh sau khi làm"), MaxLength(10, ErrorMessage = "Tối đa 10 ảnh")] string[] AfterImages,
    [StringLength(500)] string? Note);

/// <summary>#73 POST /orders/{id}/extra-charges. amount in whole VND; evidence = photo URL from POST /files (ORDER_IMAGE).</summary>
public sealed record CreateExtraChargeRequest(
    Guid AssignmentId,
    [Required(ErrorMessage = "Hãy mô tả khoản phát sinh"), StringLength(300, MinimumLength = 3, ErrorMessage = "Mô tả dài 3–300 ký tự")] string Description,
    [Range(1_000, 100_000_000, ErrorMessage = "Số tiền từ 1.000 đ đến 100.000.000 đ")] long Amount,
    [StringLength(500)] string? EvidenceImageUrl);

/// <summary>#74 POST /orders/{id}/extra-charges/{ecId}/respond. approved is required: a missing field must not count as a rejection.</summary>
public sealed record RespondExtraChargeRequest(
    [Required(ErrorMessage = "Hãy chọn duyệt hoặc từ chối")] bool? Approved,
    [StringLength(500)] string? Note);

/// <summary>#62 ?status=: ACTIVE (2.5.1 "Đang diễn ra", includes DISPUTED), COMPLETED or CANCELLED.</summary>
public enum OrderListStatus { Active = 1, Completed = 2, Cancelled = 3 }

/// <summary>
/// The other side of the order. Phone numbers are only shown from ACCEPTED until the work is done (F-ORD-06). Before the
/// partner accepts, they see the customer as "An N." like in the feed.
/// </summary>
public sealed record OrderPartyDto(Guid? PartnerProfileId, string DisplayName, string? AvatarUrl, decimal? Rating, string? PhoneNumber);

/// <summary>Row of #62 (CS-24 / PS-13 tabs). earning is only filled for the partner.</summary>
public sealed record OrderSummaryDto(
    Guid Id,
    string Code,
    OrderStatus Status,
    Guid ServiceRequestId,
    string Title,
    string CategoryName,
    DateTimeOffset? ScheduledStartAt,
    long TotalAmount,
    long? Earning,
    OrderPartyDto Counterpart,
    Guid AssignmentId,
    OrderStatus AssignmentStatus,
    DateTimeOffset CreatedAt,
    DateTimeOffset UpdatedAt);

/// <summary>Money of the order (2.6.1). Commission and earning are only shown to the partner.</summary>
public sealed record OrderPricingDto(long SubTotal, long ExtraChargeTotal, long TotalAmount, decimal? CommissionRate, long? CommissionAmount, long? PartnerEarning);

public sealed record AssignmentDto(
    Guid Id,
    OrderStatus Status,
    bool IsPrimary,
    OrderPartyDto Partner,
    long Amount,
    long ExtraChargeTotal,
    DateTimeOffset AcceptanceDeadlineAt,
    DateTimeOffset? ReservedStartAt,
    DateTimeOffset? ReservedEndAt,
    DateTimeOffset? AcceptedAt,
    DateTimeOffset? OnTheWayAt,
    DateTimeOffset? ArrivedAt,
    DateTimeOffset? StartedAt,
    DateTimeOffset? CompletedAt,
    DateTimeOffset? CancelledAt,
    string? CancelReason,
    IReadOnlyList<string> BeforeImages,
    IReadOnlyList<string> AfterImages);

public sealed record ExtraChargeDto(
    Guid Id,
    Guid OrderId,
    Guid AssignmentId,
    string Description,
    long Amount,
    string? EvidenceImageUrl,
    ExtraChargeStatus Status,
    string? ResponseNote,
    DateTimeOffset? RespondedAt,
    DateTimeOffset CreatedAt);

/// <summary>
/// #63 GET /orders/{id}, also the result of #43. The customer sees the whole order; the partner sees only their own
/// assignment and, until they accept, only the area of the address (no exact position).
/// rowVersion is the expectedRowVersion of the next work packages (#71 confirm, #72 cancel).
/// </summary>
public sealed record OrderDetailDto(
    Guid Id,
    string Code,
    OrderStatus Status,
    PaymentMethod PaymentMethod,
    PaymentStatus PaymentStatus,
    Guid ServiceRequestId,
    string Title,
    string Description,
    Guid ServiceCategoryId,
    string CategoryName,
    string Address,
    decimal? Latitude,
    decimal? Longitude,
    DateTimeOffset? ScheduledStartAt,
    OrderPricingDto Pricing,
    OrderPartyDto Customer,
    IReadOnlyList<AssignmentDto> Assignments,
    IReadOnlyList<ExtraChargeDto> ExtraCharges,
    Guid? ConversationId,
    DateTimeOffset? AcceptedAt,
    DateTimeOffset? StartedAt,
    DateTimeOffset? CompletedByPartnerAt,
    DateTimeOffset? AutoConfirmAt,
    DateTimeOffset? CompletedAt,
    DateTimeOffset? CancelledAt,
    ActorType? CancelledBy,
    string? CancelReason,
    string RowVersion,
    DateTimeOffset CreatedAt,
    DateTimeOffset UpdatedAt);

/// <summary>#64 row. assignmentId is null for order-level rows.</summary>
public sealed record TimelineEntryDto(OrderStatus Status, OrderStatus? FromStatus, Guid? AssignmentId, ActorType ChangedByType, string? Note, DateTimeOffset CreatedAt);

/// <summary>
/// Result of #65/#69/#70 and payload of OrderStatusChanged on /hubs/orders.
/// status = order status (what the order screens show); assignmentStatus = this partner's part.
/// </summary>
public sealed record OrderStatusChangedDto(Guid OrderId, Guid AssignmentId, OrderStatus Status, OrderStatus AssignmentStatus, DateTimeOffset ChangedAt);
