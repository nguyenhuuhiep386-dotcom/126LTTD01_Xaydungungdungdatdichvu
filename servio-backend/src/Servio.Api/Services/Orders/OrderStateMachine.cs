using Servio.Api.Common;

namespace Servio.Api.Services.Orders;

/// <summary>
/// Pure rules of spec 2.5.1 (state machine) and 2.6.1 (money). No database access, so every rule is unit-tested
/// directly. Order and assignment share the OrderStatus codes; DISPUTED only exists on the order.
/// Course scope (0.2.4): no SEARCHING, AWAITING_PAYMENT is not used for cash orders.
/// </summary>
public static class OrderStateMachine
{
    private static readonly Dictionary<OrderStatus, OrderStatus[]> AssignmentTransitions = new()
    {
        [OrderStatus.Pending] = [OrderStatus.Accepted, OrderStatus.Cancelled],
        [OrderStatus.Accepted] = [OrderStatus.OnTheWay, OrderStatus.Cancelled],
        [OrderStatus.OnTheWay] = [OrderStatus.Arrived, OrderStatus.Cancelled],
        [OrderStatus.Arrived] = [OrderStatus.InProgress, OrderStatus.Cancelled],
        [OrderStatus.InProgress] = [OrderStatus.CompletedByPartner],
        [OrderStatus.CompletedByPartner] = [OrderStatus.Completed],
        [OrderStatus.AwaitingPayment] = [OrderStatus.Completed],
    };

    /// <summary>
    /// Assignment statuses that hold one of the partner's 3 slots (F-FEED-10). AWAITING_PAYMENT is left out: cash orders
    /// only reach it after an UNPAID_CASH dispute, which does not hold a slot (v1.4).
    /// </summary>
    public static readonly OrderStatus[] SlotStatuses =
    [
        OrderStatus.Pending, OrderStatus.Accepted, OrderStatus.OnTheWay, OrderStatus.Arrived,
        OrderStatus.InProgress, OrderStatus.CompletedByPartner,
    ];

    /// <summary>Assignment statuses whose reserved time window still blocks the partner's calendar.</summary>
    public static readonly OrderStatus[] ScheduleStatuses =
    [
        OrderStatus.Pending, OrderStatus.Accepted, OrderStatus.OnTheWay, OrderStatus.Arrived, OrderStatus.InProgress,
    ];

    /// <summary>Statuses listed under "Đang diễn ra" (#62 status=ACTIVE).</summary>
    public static readonly OrderStatus[] ActiveOrderStatuses =
    [
        OrderStatus.Pending, OrderStatus.Accepted, OrderStatus.OnTheWay, OrderStatus.Arrived, OrderStatus.InProgress,
        OrderStatus.CompletedByPartner, OrderStatus.AwaitingPayment, OrderStatus.Disputed,
    ];

    /// <summary>From ACCEPTED until the work ends, both sides may see each other's phone number (F-ORD-06).</summary>
    public static bool ContactVisible(OrderStatus assignmentStatus) => assignmentStatus is
        OrderStatus.Accepted or OrderStatus.OnTheWay or OrderStatus.Arrived or OrderStatus.InProgress or OrderStatus.CompletedByPartner;

    public static bool CanMove(OrderStatus from, OrderStatus to) =>
        AssignmentTransitions.TryGetValue(from, out var next) && next.Contains(to);

    /// <summary>Throws INVALID_STATUS_TRANSITION (409) with a message the app can show.</summary>
    public static void EnsureCanMove(OrderStatus from, OrderStatus to)
    {
        if (!CanMove(from, to))
        {
            throw new ApiException(StatusCodes.Status409Conflict, ErrorCodes.InvalidStatusTransition,
                "Trạng thái đơn đã thay đổi, vui lòng tải lại");
        }
    }

    /// <summary>
    /// Order status from its assignments (spec 2.5.1 "Tổng hợp Order"): cancelled assignments are ignored; none left →
    /// CANCELLED; open dispute → DISPUTED; all done → COMPLETED_BY_PARTNER / COMPLETED; someone working with others not
    /// done → IN_PROGRESS; otherwise the furthest of ARRIVED > ON_THE_WAY > ACCEPTED > PENDING.
    /// </summary>
    public static OrderStatus Aggregate(IEnumerable<OrderStatus> assignmentStatuses, bool hasOpenDispute)
    {
        var live = assignmentStatuses.Where(s => s != OrderStatus.Cancelled).ToList();
        if (live.Count == 0) return OrderStatus.Cancelled;
        if (hasOpenDispute) return OrderStatus.Disputed;
        if (live.All(s => s == OrderStatus.Completed)) return OrderStatus.Completed;
        if (live.All(s => s is OrderStatus.CompletedByPartner or OrderStatus.Completed)) return OrderStatus.CompletedByPartner;
        if (live.Any(s => s is OrderStatus.InProgress or OrderStatus.CompletedByPartner or OrderStatus.Completed)) return OrderStatus.InProgress;
        return live.Max(); // PENDING(1) < ACCEPTED(2) < ON_THE_WAY(3) < ARRIVED(4)
    }

    /// <summary>
    /// Spec 2.6.1 for one assignment, P0 (no platform fee, voucher or adjustment):
    /// G = amount + approved extras; K = round(G × rate / 100, away from zero); E = G − K; customer pays C = G.
    /// Example: 450.000 + 180.000 at 15% → K = 94.500, E = 535.500.
    /// </summary>
    public static Pricing Price(long amount, long approvedExtra, decimal commissionRatePercent)
    {
        var gross = amount + approvedExtra;
        var commission = (long)Math.Round(gross * commissionRatePercent / 100m, MidpointRounding.AwayFromZero);
        return new Pricing(amount, approvedExtra, gross, commission, gross - commission);
    }

    /// <summary>Two time ranges overlap when each starts before the other ends.</summary>
    public static bool Overlaps(DateTimeOffset startA, DateTimeOffset endA, DateTimeOffset startB, DateTimeOffset endB) =>
        startA < endB && startB < endA;
}

public sealed record Pricing(long Amount, long ExtraCharges, long Gross, long Commission, long Earning);
