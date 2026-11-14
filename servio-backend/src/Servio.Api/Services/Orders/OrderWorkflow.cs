using System.Globalization;
using Microsoft.EntityFrameworkCore;
using Servio.Api.Common;
using Servio.Api.Data;
using Servio.Api.Data.Entities;
using Servio.Api.Hubs;
using Servio.Api.Services.Chat;
using Servio.Api.Services.Notifications;

namespace Servio.Api.Services.Orders;

/// <summary>
/// Shared steps of every order transition (F-ORD-02): move the assignment, stamp its times, write OrderStatusHistory,
/// re-aggregate the order (2.5.1) and, after the caller saved, push OrderStatusChanged plus a system chat message.
/// </summary>
public sealed class OrderWorkflow(
    ServioDbContext db,
    ChatService chat,
    IRealtimeNotifier notifier,
    NotificationService notifications,
    SystemConfigService configs,
    TimeProvider clock,
    ILogger<OrderWorkflow> logger)
{
    /// <summary>Tracked order with everything a transition needs: assignments, their partners and the customer.</summary>
    public Task<Order?> FindAsync(Guid orderId, CancellationToken ct) =>
        db.Orders
            .Include(o => o.Customer)
            .Include(o => o.OrderAssignments).ThenInclude(a => a.PartnerProfile)
            .FirstOrDefaultAsync(o => o.Id == orderId, ct);

    /// <summary>The partner's own assignment of the order (404 otherwise). Disputed orders are frozen (0.2.7 ý 10).</summary>
    public async Task<(Order Order, OrderAssignment Assignment)> FindPartnerPartAsync(
        Guid partnerUserId, Guid orderId, Guid assignmentId, CancellationToken ct)
    {
        var order = await FindAsync(orderId, ct);
        var assignment = order?.OrderAssignments.FirstOrDefault(a => a.Id == assignmentId && a.PartnerProfile.UserId == partnerUserId);
        if (order is null || assignment is null)
        {
            throw ApiException.NotFound("Không tìm thấy đơn");
        }
        EnsureNotDisputed(order);
        return (order, assignment);
    }

    public static void EnsureNotDisputed(Order order)
    {
        if (order.Status == (byte)OrderStatus.Disputed)
        {
            throw new ApiException(StatusCodes.Status409Conflict, ErrorCodes.OrderDisputed, "Đơn đang được xử lý khiếu nại, tạm dừng thao tác");
        }
    }

    /// <summary>
    /// Applies one assignment transition to tracked entities; the caller saves. History: one row for the assignment,
    /// plus an order-level row only when the order lands on a different status than the assignment.
    /// </summary>
    public async Task<OrderStatusChangedDto> MoveAsync(Order order, OrderAssignment assignment, OrderStatus to,
        ActorType by, Guid? userId, string? note, CancellationToken ct)
    {
        var from = (OrderStatus)assignment.Status;
        OrderStateMachine.EnsureCanMove(from, to);
        var now = clock.GetUtcNow();
        StampAssignment(assignment, to, by, note, now);
        AddHistory(order, assignment, from, to, by, userId, note, now);

        var before = (OrderStatus)order.Status;
        var after = OrderStateMachine.Aggregate(order.OrderAssignments.Select(a => (OrderStatus)a.Status), hasOpenDispute: false);
        if (after != before)
        {
            order.Status = (byte)after;
            await StampOrderAsync(order, after, by, note, now, ct);
            if (after != to)
            {
                AddHistory(order, null, before, after, by, userId, note, now);
            }
        }
        order.UpdatedAt = now;
        return new OrderStatusChangedDto(order.Id, assignment.Id, after, to, now, by);
    }

    /// <summary>
    /// Order-level move that does not come from an assignment (dispute opened or closed): writes one history row with
    /// assignmentId null. The returned change carries <paramref name="assignment"/> so the apps can refresh it.
    /// </summary>
    public OrderStatusChangedDto SetOrderStatus(Order order, OrderAssignment assignment, OrderStatus to, ActorType by,
        Guid? userId, Guid? adminId, string? note)
    {
        var now = clock.GetUtcNow();
        var from = (OrderStatus)order.Status;
        AddHistory(order, null, from, to, by, userId, note, now);
        order.OrderStatusHistories.Last().ChangedByAdminId = adminId;
        order.Status = (byte)to;
        order.UpdatedAt = now;
        return new OrderStatusChangedDto(order.Id, assignment.Id, to, (OrderStatus)assignment.Status, now, by);
    }

    /// <summary>
    /// Recomputes money from the live assignments (2.6.1): each assignment from its snapshot rate, the order as the sum.
    /// Called when the order is created and when an extra charge is approved.
    /// </summary>
    public static void Reprice(Order order)
    {
        var live = order.OrderAssignments.Where(a => a.Status != (byte)OrderStatus.Cancelled).ToList();
        foreach (var a in live)
        {
            var price = OrderStateMachine.Price((long)a.Amount, (long)a.ExtraChargeTotal, a.CommissionRateSnapshot);
            a.CommissionAmount = price.Commission;
            a.Earning = price.Earning;
            a.CustomerPayable = price.Gross;
        }
        order.SubTotal = live.Sum(a => a.Amount);
        order.ExtraChargeTotal = live.Sum(a => a.ExtraChargeTotal);
        order.TotalAmount = live.Sum(a => a.CustomerPayable);
        order.CommissionAmount = live.Sum(a => a.CommissionAmount);
        order.PartnerEarning = live.Sum(a => a.Earning);
    }

    /// <summary>
    /// After commit: OrderStatusChanged to both sides, an optional system line in their chat, and an inbox + push
    /// notification (ORDER_STATUS_CHANGED) to the side that did not make the change (both when SYSTEM/ADMIN did).
    /// </summary>
    public async Task PublishAsync(Order order, OrderAssignment assignment, OrderStatusChangedDto change, string? systemMessage, CancellationToken ct)
    {
        (AppFlavor, Guid) customer = (AppFlavor.Customer, order.Customer.UserId);
        (AppFlavor, Guid) partner = (AppFlavor.Partner, assignment.PartnerProfile.UserId);
        await notifier.OrderEventAsync([customer, partner], RealtimeEvents.OrderStatusChanged, change, ct);
        if (systemMessage is not null)
        {
            await SystemMessageAsync(order.ServiceRequestId, assignment.PartnerProfileId, systemMessage, ct);
        }
        IReadOnlyList<(AppFlavor, Guid)> to = change.ChangedBy switch
        {
            ActorType.Customer => [partner],
            ActorType.Partner => [customer],
            _ => [customer, partner],
        };
        await NotifyOrderAsync(order, to, systemMessage ?? "Trạng thái đơn đã thay đổi.", ct);
    }

    /// <summary>Inbox + push about an order: title "Đơn OD…", opens the order screen (deepLink orders/{id}).</summary>
    public Task NotifyOrderAsync(Order order, IReadOnlyList<(AppFlavor Flavor, Guid UserId)> to, string body, CancellationToken ct,
        NotificationType type = NotificationType.OrderStatusChanged) =>
        notifications.SendAsync(to, type, $"Đơn {order.Code}", body, new { orderId = order.Id }, $"orders/{order.Id}", ct);

    /// <summary>The order change is already saved, so a failed chat line is only logged (spec 0.2.7 ý 12).</summary>
    public async Task SystemMessageAsync(Guid serviceRequestId, Guid partnerProfileId, string text, CancellationToken ct)
    {
        try
        {
            await chat.AddSystemMessageAsync(serviceRequestId, partnerProfileId, text, ct);
        }
        catch (Exception e) when (e is not OperationCanceledException)
        {
            logger.LogWarning(e, "System message for request {RequestId} failed", serviceRequestId);
        }
    }

    /// <summary>Vietnam time for messages ("trước 14:30").</summary>
    public static string LocalTime(DateTimeOffset at) => at.ToOffset(TimeSpan.FromHours(7)).ToString("HH:mm dd/MM", CultureInfo.InvariantCulture);

    private static void StampAssignment(OrderAssignment a, OrderStatus to, ActorType by, string? note, DateTimeOffset now)
    {
        a.Status = (byte)to;
        a.UpdatedAt = now;
        switch (to)
        {
            case OrderStatus.Accepted: a.AcceptedAt = now; break;
            case OrderStatus.OnTheWay: a.OnTheWayAt = now; break;
            case OrderStatus.Arrived: a.ArrivedAt = now; break;
            case OrderStatus.InProgress: a.StartedAt = now; break;
            case OrderStatus.CompletedByPartner: a.CompletedAt = now; break;
            case OrderStatus.Cancelled:
                a.CancelledAt = now;
                a.CancelledBy = (byte)by;
                a.CancelReason = note;
                break;
        }
    }

    private async Task StampOrderAsync(Order order, OrderStatus status, ActorType by, string? note, DateTimeOffset now, CancellationToken ct)
    {
        switch (status)
        {
            case OrderStatus.Accepted:
                order.AcceptedAt ??= now;
                break;
            case OrderStatus.InProgress:
                order.StartedAt ??= now;
                break;
            case OrderStatus.CompletedByPartner:
                order.CompletedByPartnerAt = now;
                order.AutoConfirmAt = now.AddHours(await configs.GetIntAsync("order.auto_confirm_hours", 24, ct));
                break;
            case OrderStatus.Cancelled:
                order.CancelledAt = now;
                order.CancelledBy = (byte)by;
                order.CancelReason = note;
                // Same as a cancelled post: nobody can keep chatting about a dead order.
                var conversations = await db.Conversations
                    .Where(c => c.OrderId == order.Id && c.Status == (byte)ConversationStatus.Active).ToListAsync(ct);
                foreach (var c in conversations)
                {
                    c.Status = (byte)ConversationStatus.ReadOnly;
                    c.UpdatedAt = now;
                }
                break;
        }
    }

    private static void AddHistory(Order order, OrderAssignment? assignment, OrderStatus from, OrderStatus to,
        ActorType by, Guid? userId, string? note, DateTimeOffset now) =>
        order.OrderStatusHistories.Add(new OrderStatusHistory
        {
            Id = Guid.CreateVersion7(),
            OrderAssignment = assignment,
            FromStatus = (byte)from,
            ToStatus = (byte)to,
            ChangedByType = (byte)by,
            ChangedByUserId = userId,
            Note = note,
            CreatedAt = now,
        });
}
