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
/// status = order status (what the order screens show); assignmentStatus = this partner's part; changedBy = who did it.
/// </summary>
public sealed record OrderStatusChangedDto(Guid OrderId, Guid AssignmentId, OrderStatus Status, OrderStatus AssignmentStatus, DateTimeOffset ChangedAt,
    ActorType ChangedBy = ActorType.System);

/// <summary>#67 start-moving: where the partner starts from (shown on CS-21 until the first UpdateLocation).</summary>
public sealed record StartMovingRequest(decimal? Latitude, decimal? Longitude, [Range(0, 10_000)] int? Accuracy);

/// <summary>
/// #68 arrive (F-ORD-07). accuracy in metres; capturedAt = time of the GPS fix on the phone; isMock from
/// Location.isMock() / isFromMockProvider. integrityToken is accepted but not checked (integrity.checkin_required=false, 0.2.2 row 11).
/// </summary>
public sealed record ArriveRequest(
    decimal? Latitude,
    decimal? Longitude,
    [Required(ErrorMessage = "Thiếu độ chính xác GPS"), Range(0, 10_000)] int? Accuracy,
    [Required(ErrorMessage = "Thiếu thời điểm lấy vị trí")] DateTimeOffset? CapturedAt,
    bool IsMock,
    string? IntegrityToken);

/// <summary>Result of #68. distanceMeters = distance to the address at check-in.</summary>
public sealed record ArriveResultDto(Guid OrderId, Guid AssignmentId, OrderStatus Status, OrderStatus AssignmentStatus, int DistanceMeters, DateTimeOffset ChangedAt);

/// <summary>#179 respond. confirmed is required: a missing field must not count as a denial.</summary>
public sealed record RespondArrivalRequest([Required(ErrorMessage = "Hãy chọn xác nhận hoặc từ chối")] bool? Confirmed);

/// <summary>
/// #178 result and payload of ArrivalConfirmationRequested (to the customer) / ArrivalConfirmationResolved (to the partner).
/// </summary>
public sealed record ArrivalConfirmationDto(Guid OrderId, Guid AssignmentId, Guid RequestId, ArrivalConfirmationStatus Status, DateTimeOffset ExpiresAt);

/// <summary>Last known position of the partner. capturedAt is the time of the GPS fix.</summary>
public sealed record LocationDto(decimal Latitude, decimal Longitude, int? Accuracy, DateTimeOffset CapturedAt);

/// <summary>
/// One assignment on CS-21 (#76) and payload of PartnerLocationUpdated. location is only sent while the partner is
/// ON_THE_WAY; isStale = the last fix is older than 30 s (show "Chưa cập nhật", do not count the ETA down).
/// ETA = Haversine distance ÷ 25 km/h (0.2.2 row 10).
/// </summary>
public sealed record TrackingDto(
    Guid OrderId,
    Guid AssignmentId,
    Guid PartnerProfileId,
    OrderStatus AssignmentStatus,
    LocationDto? Location,
    double? DistanceKm,
    int? EtaMinutes,
    bool IsStale);

/// <summary>#77 ETA of one assignment for the customer.</summary>
public sealed record EtaDto(Guid AssignmentId, int? EtaMinutes, double? DistanceKm, DateTimeOffset? UpdatedAt, bool IsStale);

/// <summary>#71 confirm-completion. expectedRowVersion = rowVersion from #63.</summary>
public sealed record ConfirmCompletionRequest([Required(ErrorMessage = "Thiếu phiên bản đơn")] string ExpectedRowVersion);

/// <summary>Result of #71. CASH: paymentRequired is always false, the order is COMPLETED (v1.3).</summary>
public sealed record CompletionResultDto(Guid OrderId, OrderStatus Status, bool PaymentRequired, long TotalAmount, long CustomerPayableTotal);

/// <summary>One assignment in #151 (fee by the 2.5.2 table for its current status).</summary>
public sealed record AssignmentFeeDto(Guid AssignmentId, OrderStatus Status, long Fee);

/// <summary>#151 cancel-preview. Send rowVersion back as expectedRowVersion in #72 before expiresAt (60 s).</summary>
public sealed record CancelPreviewDto(long Fee, IReadOnlyList<AssignmentFeeDto> AssignmentFees, string RowVersion, DateTimeOffset ExpiresAt);

/// <summary>#72 customer cancels the whole order.</summary>
public sealed record CancelOrderRequest(
    [Required(ErrorMessage = "Hãy nhập lý do"), StringLength(500, MinimumLength = 3, ErrorMessage = "Lý do dài 3–500 ký tự")] string Reason,
    [Required(ErrorMessage = "Thiếu phiên bản đơn")] string ExpectedRowVersion);

/// <summary>#152 partner cancels their own assignment (ACCEPTED, ON_THE_WAY or ARRIVED).</summary>
public sealed record PartnerCancelRequest(
    [Required(ErrorMessage = "Hãy nhập lý do"), StringLength(500, MinimumLength = 3, ErrorMessage = "Lý do dài 3–500 ký tự")] string Reason);

/// <summary>#180 customer-no-show. note is optional.</summary>
public sealed record CustomerNoShowRequest([StringLength(500)] string? Note);

public sealed record CancellationChargeDto(Guid Id, Guid AssignmentId, long Amount, CancellationReason Reason, CancellationChargeStatus Status);

/// <summary>Result of #72 and #180.</summary>
public sealed record CancelResultDto(Guid OrderId, OrderStatus Status, long CancellationFee, IReadOnlyList<CancellationChargeDto> Charges);
