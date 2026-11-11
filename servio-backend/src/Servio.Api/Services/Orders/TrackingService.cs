using System.Globalization;
using Microsoft.EntityFrameworkCore;
using Servio.Api.Common;
using Servio.Api.Data;
using Servio.Api.Data.Entities;
using Servio.Api.Hubs;

namespace Servio.Api.Services.Orders;

/// <summary>
/// BE-5 (0.2.7 ý 5–6): #67 start moving, hub UpdateLocation, #76/#77 tracking and ETA, #68 GPS check-in and the
/// "customer confirms the partner arrived" path #178/#179. The live position is the Last* columns of OrderAssignments.
/// Course scope: no CheckInAttempts table, so rejected check-ins are not stored and #178 only needs ON_THE_WAY.
/// </summary>
public sealed class TrackingService(
    ServioDbContext db,
    OrderWorkflow workflow,
    IRealtimeNotifier notifier,
    SystemConfigService configs,
    TimeProvider clock)
{
    /// <summary>Older fixes are shown as "Chưa cập nhật" (0.2.7 ý 5).</summary>
    public static readonly TimeSpan StaleAfter = TimeSpan.FromSeconds(30);

    /// <summary>ETA speed of the course scope (0.2.2 row 10).</summary>
    public const double SpeedKmh = 25;

    /// <summary>#67 ACCEPTED → ON_THE_WAY. The start point becomes the first live position.</summary>
    public async Task<OrderStatusChangedDto> StartMovingAsync(Guid userId, Guid orderId, Guid assignmentId, StartMovingRequest input, CancellationToken ct)
    {
        var (lat, lng) = Geo.Require(input.Latitude, input.Longitude);
        var (order, assignment) = await workflow.FindPartnerPartAsync(userId, orderId, assignmentId, ct);
        var change = await workflow.MoveAsync(order, assignment, OrderStatus.OnTheWay, ActorType.Partner, userId, null, ct);
        SetLocation(assignment, lat, lng, input.Accuracy, change.ChangedAt);
        await db.SaveChangesAsync(ct);
        await workflow.PublishAsync(order, assignment, change, "Đối tác đang trên đường đến. Bạn có thể theo dõi vị trí trong màn hình đơn.", ct);
        return change;
    }

    /// <summary>
    /// Hub /hubs/orders UpdateLocation, every ~10 s from the partner's foreground service. Only the partner of an
    /// ON_THE_WAY assignment may send; a fix older than the stored one is ignored so a late packet never moves the
    /// marker back. Sends PartnerLocationUpdated to the customer. Returns false when the point was ignored.
    /// </summary>
    public async Task<bool> UpdateLocationAsync(Guid userId, Guid assignmentId, decimal latitude, decimal longitude,
        int? accuracy, DateTimeOffset capturedAt, CancellationToken ct)
    {
        var (lat, lng) = Geo.Require(latitude, longitude);
        var assignment = await db.OrderAssignments
            .Include(a => a.Order).ThenInclude(o => o.Customer)
            .Include(a => a.Order).ThenInclude(o => o.ServiceRequest)
            .FirstOrDefaultAsync(a => a.Id == assignmentId && a.PartnerProfile.UserId == userId, ct)
            ?? throw ApiException.NotFound("Không tìm thấy đơn");
        if (assignment.Status != (byte)OrderStatus.OnTheWay || assignment.Order.Status == (byte)OrderStatus.Disputed)
        {
            // The app stops its service on this error (ARRIVED, CANCELLED, DISPUTED).
            throw new ApiException(StatusCodes.Status409Conflict, ErrorCodes.InvalidStatusTransition, "Đơn không còn ở bước di chuyển");
        }
        var now = clock.GetUtcNow();
        var at = capturedAt > now ? now : capturedAt; // phone clocks run ahead; never store a fix from the future
        if (assignment.LastLocationAt is { } last && at <= last)
        {
            return false;
        }
        SetLocation(assignment, lat, lng, accuracy, at);
        // ponytail: last write wins; two points racing only matters within one 10 s tick.
        await db.SaveChangesAsync(ct);

        await notifier.OrderEventAsync([(AppFlavor.Customer, assignment.Order.Customer.UserId)], RealtimeEvents.PartnerLocationUpdated,
            ToTracking(assignment, assignment.Order.ServiceRequest, now), ct);
        return true;
    }

    /// <summary>
    /// #68 ON_THE_WAY → ARRIVED by GPS (F-ORD-07, hard block). Checks, in order: mock location (422 MOCK_LOCATION_DETECTED,
    /// no customer path), fix age and accuracy (422 GPS_NOT_RELIABLE), distance to the address (422 TOO_FAR_FROM_ADDRESS).
    /// The last two mean the app offers "Nhờ khách xác nhận" (#178).
    /// </summary>
    public async Task<ArriveResultDto> ArriveAsync(Guid userId, Guid orderId, Guid assignmentId, ArriveRequest input, CancellationToken ct)
    {
        var (lat, lng) = Geo.Require(input.Latitude, input.Longitude);
        var (order, assignment) = await workflow.FindPartnerPartAsync(userId, orderId, assignmentId, ct);
        OrderStateMachine.EnsureCanMove((OrderStatus)assignment.Status, OrderStatus.Arrived);
        var address = await db.ServiceRequests.AsNoTracking().Where(r => r.Id == order.ServiceRequestId)
            .Select(r => new { r.Latitude, r.Longitude }).SingleAsync(ct);

        var now = clock.GetUtcNow();
        var distance = (int)Math.Round(Geo.DistanceKm(lat, lng, address.Latitude, address.Longitude) * 1000);
        var rules = new CheckInRules(
            await configs.GetIntAsync("checkin.max_distance_meters", 200, ct),
            await configs.GetIntAsync("checkin.max_accuracy_meters", 100, ct),
            await configs.GetIntAsync("checkin.max_fix_age_seconds", 60, ct));
        // ponytail: Play Integrity is not verified (integrity.checkin_required=false for sideloaded demo APKs, spec E06).
        CheckIn(rules, input.IsMock, input.Accuracy!.Value, now - input.CapturedAt!.Value, distance);

        var change = await workflow.MoveAsync(order, assignment, OrderStatus.Arrived, ActorType.Partner, userId, null, ct);
        assignment.ArrivalMethod = (byte)ArrivalMethod.Gps;
        assignment.ArrivalLatitude = lat;
        assignment.ArrivalLongitude = lng;
        assignment.ArrivalDistanceMeters = distance;
        assignment.ArrivalAccuracyMeters = input.Accuracy;
        await CancelPendingConfirmationsAsync(assignment.Id, now, ct);
        await db.SaveChangesAsync(ct);
        await workflow.PublishAsync(order, assignment, change, "Đối tác đã đến nơi.", ct);
        return new ArriveResultDto(change.OrderId, change.AssignmentId, change.Status, change.AssignmentStatus, distance, change.ChangedAt);
    }

    public sealed record CheckInRules(int MaxDistanceMeters, int MaxAccuracyMeters, int MaxFixAgeSeconds);

    /// <summary>Pure check of F-ORD-07; throws the 422 the app shows. A fix up to 5 s "in the future" is phone clock drift.</summary>
    public static void CheckIn(CheckInRules rules, bool isMock, int accuracyMeters, TimeSpan fixAge, int distanceMeters)
    {
        if (isMock)
        {
            throw new ApiException(StatusCodes.Status422UnprocessableEntity, ErrorCodes.MockLocationDetected,
                "Phát hiện vị trí giả, hãy tắt ứng dụng giả lập vị trí rồi thử lại");
        }
        if (accuracyMeters > rules.MaxAccuracyMeters || fixAge > TimeSpan.FromSeconds(rules.MaxFixAgeSeconds) || fixAge < TimeSpan.FromSeconds(-5))
        {
            throw new ApiException(StatusCodes.Status422UnprocessableEntity, ErrorCodes.GpsNotReliable,
                "GPS chưa đủ chính xác. Hãy ra chỗ thoáng và thử lại, hoặc nhờ khách xác nhận bạn đã đến");
        }
        if (distanceMeters > rules.MaxDistanceMeters)
        {
            throw new ApiException(StatusCodes.Status422UnprocessableEntity, ErrorCodes.TooFarFromAddress,
                $"Bạn đang cách địa chỉ {FormatDistance(distanceMeters)} (tối đa {rules.MaxDistanceMeters} m). Hãy đến gần hơn hoặc nhờ khách xác nhận");
        }
    }

    /// <summary>
    /// #178 the partner (ON_THE_WAY) asks the customer to confirm the arrival: one PENDING at a time, at most
    /// checkin.customer_confirm_max_requests per assignment, expires after checkin.customer_confirm_ttl_seconds.
    /// </summary>
    public async Task<ArrivalConfirmationDto> RequestConfirmationAsync(Guid userId, Guid orderId, Guid assignmentId, CancellationToken ct)
    {
        var (order, assignment) = await workflow.FindPartnerPartAsync(userId, orderId, assignmentId, ct);
        if (assignment.Status != (byte)OrderStatus.OnTheWay)
        {
            throw new ApiException(StatusCodes.Status409Conflict, ErrorCodes.InvalidStatusTransition, "Chỉ nhờ khách xác nhận khi đang trên đường đến");
        }
        var now = clock.GetUtcNow();
        var requests = await db.ArrivalConfirmationRequests.Where(r => r.OrderAssignmentId == assignment.Id).ToListAsync(ct);
        ExpireOverdue(requests, now);
        var max = await configs.GetIntAsync("checkin.customer_confirm_max_requests", 3, ct);
        if (requests.Any(r => r.Status == (byte)ArrivalConfirmationStatus.Pending))
        {
            throw new ApiException(StatusCodes.Status422UnprocessableEntity, ErrorCodes.ArrivalConfirmationNotAllowed, "Đang chờ khách xác nhận, vui lòng đợi");
        }
        if (requests.Count >= max)
        {
            throw new ApiException(StatusCodes.Status422UnprocessableEntity, ErrorCodes.ArrivalConfirmationNotAllowed,
                $"Đã nhờ khách xác nhận {max} lần, hãy đến đúng địa chỉ và check-in bằng GPS");
        }
        var request = new ArrivalConfirmationRequest
        {
            Id = Guid.CreateVersion7(),
            OrderAssignmentId = assignment.Id,
            RequestedAt = now,
            ExpiresAt = now.AddSeconds(await configs.GetIntAsync("checkin.customer_confirm_ttl_seconds", 300, ct)),
            Status = (byte)ArrivalConfirmationStatus.Pending,
        };
        db.ArrivalConfirmationRequests.Add(request);
        await db.SaveChangesAsync(ct); // the filtered unique index turns a double tap into an error instead of two PENDING rows

        var dto = ToDto(order.Id, request);
        await notifier.OrderEventAsync([(AppFlavor.Customer, order.Customer.UserId)], RealtimeEvents.ArrivalConfirmationRequested, dto, ct);
        await workflow.SystemMessageAsync(order.ServiceRequestId, assignment.PartnerProfileId,
            "Đối tác báo đã đến nơi. Vui lòng xác nhận trong màn hình đơn.", ct);
        await workflow.NotifyOrderAsync(order, [(AppFlavor.Customer, order.Customer.UserId)], "Thợ báo đã đến nơi, bạn xác nhận giúp nhé.", ct);
        return dto;
    }

    /// <summary>#179 the customer answers. Confirmed → ARRIVED with ArrivalMethod=CUSTOMER; denied → the partner is told.</summary>
    public async Task<ArrivalConfirmationDto> RespondConfirmationAsync(Guid userId, Guid orderId, Guid assignmentId, Guid requestId,
        RespondArrivalRequest input, CancellationToken ct)
    {
        var order = await workflow.FindAsync(orderId, ct);
        var assignment = order?.OrderAssignments.FirstOrDefault(a => a.Id == assignmentId);
        if (order is null || assignment is null || order.Customer.UserId != userId)
        {
            throw ApiException.NotFound("Không tìm thấy đơn");
        }
        OrderWorkflow.EnsureNotDisputed(order);
        var request = await db.ArrivalConfirmationRequests.FirstOrDefaultAsync(r => r.Id == requestId && r.OrderAssignmentId == assignmentId, ct)
                      ?? throw ApiException.NotFound("Không tìm thấy yêu cầu xác nhận");
        var now = clock.GetUtcNow();
        ExpireOverdue([request], now);
        if (request.Status != (byte)ArrivalConfirmationStatus.Pending)
        {
            await db.SaveChangesAsync(ct); // keep the EXPIRED mark
            throw new ApiException(StatusCodes.Status409Conflict, ErrorCodes.Conflict,
                request.Status == (byte)ArrivalConfirmationStatus.Expired ? "Yêu cầu xác nhận đã hết hạn" : "Yêu cầu xác nhận đã được xử lý");
        }

        var confirmed = input.Confirmed!.Value;
        request.Status = (byte)(confirmed ? ArrivalConfirmationStatus.Confirmed : ArrivalConfirmationStatus.Denied);
        request.RespondedAt = now;
        OrderStatusChangedDto? change = null;
        if (confirmed)
        {
            change = await workflow.MoveAsync(order, assignment, OrderStatus.Arrived, ActorType.Customer, userId, "Khách xác nhận đối tác đã đến", ct);
            assignment.ArrivalMethod = (byte)ArrivalMethod.Customer;
            assignment.CustomerConfirmedArrivalAt = now;
        }
        await db.SaveChangesAsync(ct);

        var dto = ToDto(order.Id, request);
        await notifier.OrderEventAsync([(AppFlavor.Partner, assignment.PartnerProfile.UserId)], RealtimeEvents.ArrivalConfirmationResolved, dto, ct);
        if (change is not null)
        {
            await workflow.PublishAsync(order, assignment, change, "Khách đã xác nhận đối tác đến nơi.", ct);
        }
        else
        {
            await workflow.SystemMessageAsync(order.ServiceRequestId, assignment.PartnerProfileId, "Khách chưa thấy đối tác đến nơi.", ct);
            await workflow.NotifyOrderAsync(order, [(AppFlavor.Partner, assignment.PartnerProfile.UserId)],
                "Khách chưa thấy bạn đến nơi. Hãy kiểm tra lại địa chỉ hoặc nhắn tin cho khách.", ct);
        }
        return dto;
    }

    /// <summary>
    /// #76 CS-21 / PS-15: the customer sees every live assignment of the order, the partner only their own.
    /// <paramref name="assignmentId"/> narrows the list to one assignment.
    /// </summary>
    public async Task<IReadOnlyList<TrackingDto>> GetTrackingAsync(Guid userId, AppFlavor flavor, Guid orderId, Guid? assignmentId, CancellationToken ct)
    {
        var order = await db.Orders.AsNoTracking()
            .Include(o => o.Customer)
            .Include(o => o.ServiceRequest)
            .Include(o => o.OrderAssignments).ThenInclude(a => a.PartnerProfile)
            .FirstOrDefaultAsync(o => o.Id == orderId, ct);
        var visible = order is null ? [] : flavor == AppFlavor.Customer
            ? order.Customer.UserId == userId ? order.OrderAssignments.ToList() : []
            : order.OrderAssignments.Where(a => a.PartnerProfile.UserId == userId).ToList();
        if (order is null || visible.Count == 0)
        {
            throw ApiException.NotFound("Không tìm thấy đơn");
        }
        var now = clock.GetUtcNow();
        return visible
            .Where(a => a.Status != (byte)OrderStatus.Cancelled && (assignmentId is null || a.Id == assignmentId))
            .Select(a => ToTracking(a, order.ServiceRequest, now))
            .ToList();
    }

    /// <summary>#77 ETA of one assignment for the customer. Null ETA when the partner is not on the way.</summary>
    public async Task<EtaDto> GetEtaAsync(Guid userId, Guid orderId, Guid assignmentId, CancellationToken ct)
    {
        var t = (await GetTrackingAsync(userId, AppFlavor.Customer, orderId, assignmentId, ct)).FirstOrDefault()
                ?? throw ApiException.NotFound("Không tìm thấy phần việc");
        return new EtaDto(t.AssignmentId, t.EtaMinutes, t.DistanceKm, t.Location?.CapturedAt, t.IsStale);
    }

    /// <summary>Distance and ETA from the last fix; position is only shared while ON_THE_WAY (tracking ends at ARRIVED).</summary>
    public static TrackingDto ToTracking(OrderAssignment a, ServiceRequest address, DateTimeOffset now)
    {
        var moving = a.Status == (byte)OrderStatus.OnTheWay;
        if (!moving || a.LastLatitude is not { } lat || a.LastLongitude is not { } lng || a.LastLocationAt is not { } at)
        {
            return new TrackingDto(a.OrderId, a.Id, a.PartnerProfileId, (OrderStatus)a.Status, null, null, null, moving);
        }
        var km = Math.Round(Geo.DistanceKm(lat, lng, address.Latitude, address.Longitude), 2);
        return new TrackingDto(a.OrderId, a.Id, a.PartnerProfileId, (OrderStatus)a.Status,
            new LocationDto(lat, lng, a.LastLocationAccuracy, at), km, EtaMinutes(km), now - at > StaleAfter);
    }

    /// <summary>Haversine km ÷ 25 km/h, rounded up, at least 1 minute.</summary>
    public static int EtaMinutes(double distanceKm) => Math.Max(1, (int)Math.Ceiling(distanceKm / SpeedKmh * 60));

    private static string FormatDistance(int meters) => meters < 1000
        ? $"{meters} m"
        : (meters / 1000.0).ToString("0.#", CultureInfo.GetCultureInfo("vi-VN")) + " km";

    private static void SetLocation(OrderAssignment a, decimal lat, decimal lng, int? accuracy, DateTimeOffset at)
    {
        a.LastLatitude = lat;
        a.LastLongitude = lng;
        a.LastLocationAccuracy = accuracy;
        a.LastLocationAt = at;
    }

    private static void ExpireOverdue(IEnumerable<ArrivalConfirmationRequest> requests, DateTimeOffset now)
    {
        foreach (var r in requests.Where(r => r.Status == (byte)ArrivalConfirmationStatus.Pending && r.ExpiresAt <= now))
        {
            r.Status = (byte)ArrivalConfirmationStatus.Expired;
        }
    }

    /// <summary>Arrived by GPS while the customer had not answered yet: the question no longer applies.</summary>
    private async Task CancelPendingConfirmationsAsync(Guid assignmentId, DateTimeOffset now, CancellationToken ct)
    {
        var pending = await db.ArrivalConfirmationRequests
            .Where(r => r.OrderAssignmentId == assignmentId && r.Status == (byte)ArrivalConfirmationStatus.Pending).ToListAsync(ct);
        foreach (var r in pending)
        {
            r.Status = (byte)ArrivalConfirmationStatus.Cancelled;
            r.RespondedAt = now;
        }
    }

    private static ArrivalConfirmationDto ToDto(Guid orderId, ArrivalConfirmationRequest r) =>
        new(orderId, r.OrderAssignmentId, r.Id, (ArrivalConfirmationStatus)r.Status, r.ExpiresAt);
}
