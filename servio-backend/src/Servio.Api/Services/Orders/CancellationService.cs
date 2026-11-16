using Microsoft.EntityFrameworkCore;
using Servio.Api.Common;
using Servio.Api.Data;
using Servio.Api.Data.Entities;
using Servio.Api.Services.Requests;

namespace Servio.Api.Services.Orders;

/// <summary>
/// Cancellation policy 2.5.2 (course scope 0.2.7 ý 8): #151 preview, #72 the customer cancels the order, #152 the partner
/// cancels their part, #180 "customer not there". A fee is a DUE CancellationCharge: it is not carried to the next
/// order, the customer pays it by bank transfer (#156) and the partner is credited only when the admin confirms.
/// </summary>
public sealed class CancellationService(
    ServioDbContext db,
    OrderWorkflow workflow,
    SystemConfigService configs,
    TimeProvider clock)
{
    public sealed record FeePolicy(long OnTheWay, long ArrivedMin, decimal ArrivedPercent);

    /// <summary>
    /// Customer fee for one assignment: PENDING/ACCEPTED 0; ON_THE_WAY 20.000; ARRIVED max(30.000, round(amount × 20%)).
    /// Null = the customer cannot cancel it any more (work started → dispute).
    /// </summary>
    public static long? Fee(OrderStatus status, long amount, FeePolicy p) => status switch
    {
        OrderStatus.Pending or OrderStatus.Accepted => 0,
        OrderStatus.OnTheWay => p.OnTheWay,
        OrderStatus.Arrived => Math.Max(p.ArrivedMin, (long)Math.Round(amount * p.ArrivedPercent / 100m, MidpointRounding.AwayFromZero)),
        _ => null,
    };

    /// <summary>#151. The real fee is computed again at #72 from the state at that moment.</summary>
    public async Task<CancelPreviewDto> PreviewAsync(Guid userId, Guid orderId, CancellationToken ct)
    {
        var order = await CustomerOrderAsync(userId, orderId, ct);
        var fees = await FeesAsync(order, ct);
        return new CancelPreviewDto(fees.Sum(f => f.Fee), fees.Select(f => new AssignmentFeeDto(f.Assignment.Id, (OrderStatus)f.Assignment.Status, f.Fee)).ToList(),
            Convert.ToBase64String(order.RowVersion), clock.GetUtcNow().AddSeconds(60));
    }

    /// <summary>#72 cancels every live assignment. RESOURCE_VERSION_CONFLICT when the order changed after the preview.</summary>
    public async Task<CancelResultDto> CancelAsync(Guid userId, Guid orderId, CancelOrderRequest input, CancellationToken ct)
    {
        var reason = input.Reason.Trim();
        ContentGuard.EnsureClean(reason, "reason");
        var order = await CustomerOrderAsync(userId, orderId, ct);
        CompletionService.EnsureVersion(order, input.ExpectedRowVersion);
        var fees = await FeesAsync(order, ct);

        var changes = new List<(OrderAssignment, OrderStatusChangedDto)>();
        var charges = new List<CancellationCharge>();
        foreach (var (a, fee) in fees)
        {
            changes.Add((a, await workflow.MoveAsync(order, a, OrderStatus.Cancelled, ActorType.Customer, userId, reason, ct)));
            if (fee > 0)
            {
                charges.Add(AddCharge(order, a, fee, CancellationReason.CustomerCancel));
            }
        }
        await db.SaveChangesAsync(ct);

        var total = fees.Sum(f => f.Fee);
        foreach (var (a, change) in changes)
        {
            await workflow.PublishAsync(order, a, change,
                total > 0 ? $"Khách đã huỷ đơn: {reason}. Phí huỷ {Money.Vnd(total)} sẽ được chuyển cho đối tác khi khách thanh toán." : $"Khách đã huỷ đơn: {reason}", ct);
        }
        return Result(order, charges);
    }

    /// <summary>#152 ACCEPTED/ON_THE_WAY/ARRIVED → CANCELLED by the partner, no fee for the customer (TrustScore is P1).</summary>
    public async Task<OrderStatusChangedDto> PartnerCancelAsync(Guid userId, Guid orderId, Guid assignmentId, PartnerCancelRequest input, CancellationToken ct)
    {
        var reason = input.Reason.Trim();
        ContentGuard.EnsureClean(reason, "reason");
        var (order, assignment) = await workflow.FindPartnerPartAsync(userId, orderId, assignmentId, ct);
        switch ((OrderStatus)assignment.Status)
        {
            case OrderStatus.Pending:
                throw new ApiException(StatusCodes.Status409Conflict, ErrorCodes.InvalidStatusTransition, "Đơn đang chờ xác nhận, hãy dùng nút Từ chối");
            case OrderStatus.Accepted or OrderStatus.OnTheWay or OrderStatus.Arrived:
                break;
            default:
                throw new ApiException(StatusCodes.Status422UnprocessableEntity, ErrorCodes.OrderNotCancellable,
                    "Đã bắt đầu làm việc thì không tự huỷ được, hãy liên hệ hỗ trợ");
        }
        var change = await workflow.MoveAsync(order, assignment, OrderStatus.Cancelled, ActorType.Partner, userId, reason, ct);
        await db.SaveChangesAsync(ct);
        await workflow.PublishAsync(order, assignment, change, $"Đối tác đã huỷ đơn: {reason}. Bạn có thể đăng lại yêu cầu để tìm người khác.", ct);
        return change;
    }

    /// <summary>
    /// #180 the partner arrived by GPS, waited ≥ cancellation.no_show_wait_minutes and messaged the customer after
    /// arriving; the assignment is cancelled and the customer owes the ARRIVED fee. Otherwise 422 NO_SHOW_NOT_ALLOWED.
    /// </summary>
    public async Task<CancelResultDto> CustomerNoShowAsync(Guid userId, Guid orderId, Guid assignmentId, CustomerNoShowRequest input, CancellationToken ct)
    {
        var note = string.IsNullOrWhiteSpace(input.Note) ? null : input.Note.Trim();
        ContentGuard.EnsureClean(note, "note");
        var (order, assignment) = await workflow.FindPartnerPartAsync(userId, orderId, assignmentId, ct);
        if (assignment.Status != (byte)OrderStatus.Arrived || assignment.ArrivedAt is not { } arrivedAt)
        {
            throw NoShow("Chỉ báo khách không có mặt sau khi đã đến nơi");
        }
        if (assignment.ArrivalMethod != (byte)ArrivalMethod.Gps)
        {
            throw NoShow("Khách đã xác nhận bạn đến nơi nên không thể báo khách vắng mặt");
        }
        var wait = await configs.GetIntAsync("cancellation.no_show_wait_minutes", 15, ct);
        var now = clock.GetUtcNow();
        if (now < arrivedAt.AddMinutes(wait))
        {
            throw NoShow($"Hãy chờ khách đủ {wait} phút (đến {OrderWorkflow.LocalTime(arrivedAt.AddMinutes(wait))})");
        }
        var messaged = await db.Messages.AnyAsync(m => m.Conversation.ServiceRequestId == order.ServiceRequestId
                                                       && m.Conversation.PartnerProfileId == assignment.PartnerProfileId
                                                       && m.SenderUserId == userId && m.CreatedAt > arrivedAt, ct);
        if (!messaged)
        {
            throw NoShow("Hãy nhắn tin cho khách trong ứng dụng trước khi báo khách không có mặt");
        }

        var fee = Fee(OrderStatus.Arrived, (long)assignment.Amount, await PolicyAsync(ct))!.Value;
        var change = await workflow.MoveAsync(order, assignment, OrderStatus.Cancelled, ActorType.Partner, userId,
            note is null ? "Khách không có mặt" : $"Khách không có mặt: {note}", ct);
        var charge = AddCharge(order, assignment, fee, CancellationReason.CustomerNoShow);
        await db.SaveChangesAsync(ct);
        await workflow.PublishAsync(order, assignment, change,
            $"Đối tác báo khách không có mặt sau {wait} phút chờ. Phí {Money.Vnd(fee)}; nếu không đúng, hãy liên hệ hỗ trợ trong 24 giờ.", ct);
        return Result(order, [charge]);
    }

    private async Task<Order> CustomerOrderAsync(Guid userId, Guid orderId, CancellationToken ct)
    {
        var order = await workflow.FindAsync(orderId, ct);
        if (order is null || order.Customer.UserId != userId)
        {
            throw ApiException.NotFound("Không tìm thấy đơn");
        }
        OrderWorkflow.EnsureNotDisputed(order);
        return order;
    }

    /// <summary>Fee for every live assignment; the whole cancel is refused as soon as one part has started working.</summary>
    private async Task<List<(OrderAssignment Assignment, long Fee)>> FeesAsync(Order order, CancellationToken ct)
    {
        var policy = await PolicyAsync(ct);
        var live = order.OrderAssignments.Where(a => a.Status != (byte)OrderStatus.Cancelled).ToList();
        if (live.Count == 0)
        {
            throw new ApiException(StatusCodes.Status409Conflict, ErrorCodes.InvalidStatusTransition, "Đơn đã bị huỷ");
        }
        var fees = live.Select(a => (a, Fee((OrderStatus)a.Status, (long)a.Amount, policy))).ToList();
        if (fees.Any(f => f.Item2 is null))
        {
            throw new ApiException(StatusCodes.Status422UnprocessableEntity, ErrorCodes.OrderNotCancellable,
                "Đối tác đã bắt đầu làm việc nên không huỷ được. Nếu có vấn đề, hãy mở khiếu nại");
        }
        return fees.Select(f => (f.a, f.Item2!.Value)).ToList();
    }

    private async Task<FeePolicy> PolicyAsync(CancellationToken ct) => new(
        (long)await configs.GetDecimalAsync("cancellation.fee_after_on_the_way", 20_000, ct),
        (long)await configs.GetDecimalAsync("cancellation.fee_after_arrived_min", 30_000, ct),
        await configs.GetDecimalAsync("cancellation.fee_after_arrived_percent", 20, ct));

    private CancellationCharge AddCharge(Order order, OrderAssignment a, long fee, CancellationReason reason)
    {
        var now = clock.GetUtcNow();
        var charge = new CancellationCharge
        {
            Id = Guid.CreateVersion7(),
            OrderId = order.Id,
            OrderAssignmentId = a.Id,
            CustomerId = order.CustomerId,
            PartnerProfileId = a.PartnerProfileId,
            Amount = fee,
            Reason = (byte)reason,
            Status = (byte)CancellationChargeStatus.Due,
            CreatedAt = now,
            UpdatedAt = now,
        };
        db.CancellationCharges.Add(charge);
        order.CancellationFee += fee;
        return charge;
    }

    private static CancelResultDto Result(Order order, IEnumerable<CancellationCharge> charges) =>
        new(order.Id, (OrderStatus)order.Status, (long)order.CancellationFee,
            charges.Select(c => new CancellationChargeDto(c.Id, c.OrderAssignmentId, (long)c.Amount, (CancellationReason)c.Reason, (CancellationChargeStatus)c.Status)).ToList());

    private static ApiException NoShow(string message) =>
        new(StatusCodes.Status422UnprocessableEntity, ErrorCodes.NoShowNotAllowed, message);
}
