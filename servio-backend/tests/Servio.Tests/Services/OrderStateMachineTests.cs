using Servio.Api.Common;
using Servio.Api.Services.Orders;

namespace Servio.Tests.Services;

/// <summary>Spec 2.5.1 transitions and order aggregation, 2.6.1 money (acceptance criterion of 0.2.8).</summary>
public sealed class OrderStateMachineTests
{
    [Theory]
    [InlineData(OrderStatus.Pending, OrderStatus.Accepted, true)]
    [InlineData(OrderStatus.Pending, OrderStatus.Cancelled, true)]
    [InlineData(OrderStatus.Accepted, OrderStatus.OnTheWay, true)]
    [InlineData(OrderStatus.OnTheWay, OrderStatus.Arrived, true)]
    [InlineData(OrderStatus.Arrived, OrderStatus.InProgress, true)]
    [InlineData(OrderStatus.Arrived, OrderStatus.Cancelled, true)]
    [InlineData(OrderStatus.InProgress, OrderStatus.CompletedByPartner, true)]
    [InlineData(OrderStatus.CompletedByPartner, OrderStatus.Completed, true)]
    [InlineData(OrderStatus.Pending, OrderStatus.InProgress, false)]       // no skipping steps
    [InlineData(OrderStatus.Accepted, OrderStatus.Arrived, false)]
    [InlineData(OrderStatus.InProgress, OrderStatus.Cancelled, false)]     // work in progress cannot be cancelled
    [InlineData(OrderStatus.Cancelled, OrderStatus.Pending, false)]        // no revival
    [InlineData(OrderStatus.Completed, OrderStatus.Cancelled, false)]
    [InlineData(OrderStatus.Accepted, OrderStatus.Accepted, false)]
    public void CanMove_FollowsTheStateMachine(OrderStatus from, OrderStatus to, bool expected) =>
        Assert.Equal(expected, OrderStateMachine.CanMove(from, to));

    [Fact]
    public void EnsureCanMove_ThrowsInvalidStatusTransition()
    {
        var e = Assert.Throws<ApiException>(() => OrderStateMachine.EnsureCanMove(OrderStatus.Pending, OrderStatus.Completed));
        Assert.Equal(ErrorCodes.InvalidStatusTransition, e.Code);
        Assert.Equal(409, e.StatusCode);
    }

    [Theory]
    [InlineData(new[] { OrderStatus.Cancelled }, false, OrderStatus.Cancelled)]
    [InlineData(new[] { OrderStatus.Pending }, false, OrderStatus.Pending)]
    [InlineData(new[] { OrderStatus.Accepted, OrderStatus.Cancelled }, false, OrderStatus.Accepted)]
    [InlineData(new[] { OrderStatus.Accepted, OrderStatus.Arrived }, false, OrderStatus.Arrived)]
    [InlineData(new[] { OrderStatus.InProgress, OrderStatus.Accepted }, false, OrderStatus.InProgress)]
    [InlineData(new[] { OrderStatus.CompletedByPartner, OrderStatus.Arrived }, false, OrderStatus.InProgress)]
    [InlineData(new[] { OrderStatus.CompletedByPartner, OrderStatus.Cancelled }, false, OrderStatus.CompletedByPartner)]
    [InlineData(new[] { OrderStatus.Completed, OrderStatus.CompletedByPartner }, false, OrderStatus.CompletedByPartner)]
    [InlineData(new[] { OrderStatus.Completed, OrderStatus.Cancelled }, false, OrderStatus.Completed)]
    [InlineData(new[] { OrderStatus.InProgress }, true, OrderStatus.Disputed)]
    [InlineData(new[] { OrderStatus.Cancelled }, true, OrderStatus.Cancelled)]
    public void Aggregate_FollowsSpec251(OrderStatus[] assignments, bool openDispute, OrderStatus expected) =>
        Assert.Equal(expected, OrderStateMachine.Aggregate(assignments, openDispute));

    [Fact]
    public void Price_MatchesTheSpecExample()
    {
        // 2.6.1: quote 450.000 + approved extra 180.000 at 15% → customer pays 630.000, commission 94.500, partner gets 535.500.
        var p = OrderStateMachine.Price(450_000, 180_000, 15m);

        Assert.Equal(new Pricing(450_000, 180_000, 630_000, 94_500, 535_500), p);
        Assert.Equal(new Pricing(450_000, 0, 450_000, 67_500, 382_500), OrderStateMachine.Price(450_000, 0, 15m));
    }

    [Theory]
    [InlineData(333_333, 15, 50_000)]   // 49.999,95 → 50.000
    [InlineData(100_010, 12.5, 12_501)] // 12.501,25 → 12.501
    [InlineData(100_004, 12.5, 12_501)] // 12.500,5 → 12.501 (away from zero, not banker's rounding)
    [InlineData(200_000, 0, 0)]
    public void Price_RoundsCommissionToWholeDong_AwayFromZero(long amount, double rate, long commission)
    {
        var p = OrderStateMachine.Price(amount, 0, (decimal)rate);

        Assert.Equal(commission, p.Commission);
        Assert.Equal(amount - commission, p.Earning);
    }

    [Fact]
    public void Overlaps_TreatsTouchingRangesAsFree()
    {
        var t = new DateTimeOffset(2026, 10, 1, 8, 0, 0, TimeSpan.Zero);

        Assert.True(OrderStateMachine.Overlaps(t, t.AddHours(2), t.AddHours(1), t.AddHours(3)));
        Assert.True(OrderStateMachine.Overlaps(t, t.AddHours(3), t.AddHours(1), t.AddHours(2)));
        Assert.False(OrderStateMachine.Overlaps(t, t.AddHours(1), t.AddHours(1), t.AddHours(2)));
        Assert.False(OrderStateMachine.Overlaps(t.AddHours(2), t.AddHours(3), t, t.AddHours(1)));
    }
}
