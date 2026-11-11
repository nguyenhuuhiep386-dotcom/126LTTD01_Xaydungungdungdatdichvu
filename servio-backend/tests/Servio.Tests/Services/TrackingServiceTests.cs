using Microsoft.AspNetCore.Http;
using Microsoft.EntityFrameworkCore;
using Servio.Api.Common;
using Servio.Api.Data.Entities;
using Servio.Api.Hubs;
using Servio.Api.Services.Orders;
using Servio.Api.Services.Quotes;

namespace Servio.Tests.Services;

/// <summary>BE-5: #67 start moving, UpdateLocation, #76/#77, #68 GPS check-in, #178/#179 customer confirms arrival.</summary>
public sealed class TrackingServiceTests : IAsyncLifetime
{
    private const decimal Lat = MarketplaceFixture.HomeLat;
    private const decimal Lng = MarketplaceFixture.HomeLng;

    private MarketplaceFixture _f = null!;
    private User _partner = null!;
    private Guid _orderId;
    private Guid _assignmentId;

    public async Task InitializeAsync()
    {
        _f = await MarketplaceFixture.CreateAsync();
        _partner = await _f.AddPartnerAsync();
        var post = await _f.Requests().CreateAsync(_f.Customer.Id, _f.Post(), default);
        var quote = await _f.Quotes().SubmitAsync(_partner.Id, post.Id, new SubmitQuoteRequest(450_000, 60, Now.AddMinutes(30), null, 1), default);
        var version = (await _f.Ctx.Db.Quotes.AsNoTracking().SingleAsync(q => q.Id == quote.Id)).RowVersion;
        var order = await _f.Orders().AcceptQuoteAsync(_f.Customer.Id, post.Id,
            new AcceptQuotesRequest([quote.Id], new() { [quote.Id] = Convert.ToBase64String(version) }), default);
        _orderId = order.Id;
        _assignmentId = order.Assignments.Single().Id;
        await _f.Assignments().AcceptAsync(_partner.Id, _orderId, _assignmentId, default);
    }

    public Task DisposeAsync()
    {
        _f.Dispose();
        return Task.CompletedTask;
    }

    private DateTimeOffset Now => _f.Ctx.Clock.GetUtcNow();

    private Task<OrderStatusChangedDto> StartAsync() =>
        _f.Tracking().StartMovingAsync(_partner.Id, _orderId, _assignmentId, new StartMovingRequest(Lat + 0.02m, Lng, 15), default);

    /// <summary>A good fix at <paramref name="latOffset"/> degrees from the address (0.001° ≈ 111 m).</summary>
    private ArriveRequest Fix(decimal latOffset = 0.001m, int accuracy = 20, int ageSeconds = 5, bool mock = false) =>
        new(Lat + latOffset, Lng, accuracy, Now.AddSeconds(-ageSeconds), mock, null);

    private async Task<OrderAssignment> AssignmentAsync() =>
        await _f.Ctx.Db.OrderAssignments.AsNoTracking().SingleAsync(a => a.Id == _assignmentId);

    [Fact]
    public async Task StartMoving_ThenUpdateLocation_PushesPositionAndEtaToTheCustomer()
    {
        var change = await StartAsync();
        _f.Ctx.Clock.Advance(TimeSpan.FromSeconds(10));
        var accepted = await _f.Tracking().UpdateLocationAsync(_partner.Id, _assignmentId, Lat + 0.0225m, Lng, 10, Now, default);
        var late = await _f.Tracking().UpdateLocationAsync(_partner.Id, _assignmentId, Lat + 0.03m, Lng, 10, Now.AddSeconds(-20), default);

        Assert.Equal(OrderStatus.OnTheWay, change.Status);
        Assert.True(accepted);
        Assert.False(late); // an older fix never moves the marker back
        var pushed = Assert.Single(_f.Notifier.OrderEvents, e => e.Event == RealtimeEvents.PartnerLocationUpdated);
        Assert.Equal((AppFlavor.Customer, _f.Customer.Id), Assert.Single(pushed.To));
        var t = Assert.IsType<TrackingDto>(pushed.Payload);
        Assert.Equal(2.5, t.DistanceKm!.Value, 1); // 0.0225° ≈ 2.5 km
        Assert.Equal(6, t.EtaMinutes);              // 2.5 km at 25 km/h
        Assert.False(t.IsStale);

        var customerView = Assert.Single(await _f.Tracking().GetTrackingAsync(_f.Customer.Id, AppFlavor.Customer, _orderId, null, default));
        Assert.Equal(Lat + 0.0225m, customerView.Location!.Latitude);
        _f.Ctx.Clock.Advance(TimeSpan.FromSeconds(31));
        var eta = await _f.Tracking().GetEtaAsync(_f.Customer.Id, _orderId, _assignmentId, default);
        Assert.True(eta.IsStale); // "Chưa cập nhật"
        await Assert.ThrowsAsync<ApiException>(() => _f.Tracking().GetTrackingAsync(Guid.NewGuid(), AppFlavor.Customer, _orderId, null, default));
    }

    [Fact]
    public async Task UpdateLocation_IsRefused_OnceThePartnerHasArrived()
    {
        await StartAsync();
        await _f.Tracking().ArriveAsync(_partner.Id, _orderId, _assignmentId, Fix(), default);

        var e = await Assert.ThrowsAsync<ApiException>(() =>
            _f.Tracking().UpdateLocationAsync(_partner.Id, _assignmentId, Lat, Lng, 10, Now, default));
        var view = Assert.Single(await _f.Tracking().GetTrackingAsync(_f.Customer.Id, AppFlavor.Customer, _orderId, null, default));

        Assert.Equal(ErrorCodes.InvalidStatusTransition, e.Code);
        Assert.Null(view.Location); // tracking ends at ARRIVED
    }

    [Fact]
    public async Task Arrive_ByGps_WithinTheRules_MovesToArrived()
    {
        await StartAsync();

        var result = await _f.Tracking().ArriveAsync(_partner.Id, _orderId, _assignmentId, Fix(0.001m), default);

        Assert.Equal(OrderStatus.Arrived, result.Status);
        Assert.InRange(result.DistanceMeters, 100, 120);
        var a = await AssignmentAsync();
        Assert.Equal((byte)ArrivalMethod.Gps, a.ArrivalMethod);
        Assert.Equal(20, a.ArrivalAccuracyMeters);
        Assert.NotNull(a.ArrivedAt);
    }

    [Theory]
    [InlineData(0.001, 20, 5, true, ErrorCodes.MockLocationDetected)]
    [InlineData(0.001, 150, 5, false, ErrorCodes.GpsNotReliable)]   // accuracy > 100 m
    [InlineData(0.001, 20, 90, false, ErrorCodes.GpsNotReliable)]   // fix older than 60 s
    [InlineData(0.011, 20, 5, false, ErrorCodes.TooFarFromAddress)] // ≈ 1.2 km
    public async Task Arrive_IsBlocked_WhenAGpsRuleFails(double latOffset, int accuracy, int ageSeconds, bool mock, string code)
    {
        await StartAsync();

        var e = await Assert.ThrowsAsync<ApiException>(() =>
            _f.Tracking().ArriveAsync(_partner.Id, _orderId, _assignmentId, Fix((decimal)latOffset, accuracy, ageSeconds, mock), default));

        Assert.Equal(code, e.Code);
        Assert.Equal(StatusCodes.Status422UnprocessableEntity, e.StatusCode);
        Assert.Equal((byte)OrderStatus.OnTheWay, (await AssignmentAsync()).Status);
    }

    [Fact]
    public async Task CustomerConfirmation_DenyThenConfirm_MovesToArrivedByCustomer()
    {
        await StartAsync();
        var first = await _f.Tracking().RequestConfirmationAsync(_partner.Id, _orderId, _assignmentId, default);
        var duplicate = await Assert.ThrowsAsync<ApiException>(() => _f.Tracking().RequestConfirmationAsync(_partner.Id, _orderId, _assignmentId, default));

        var denied = await _f.Tracking().RespondConfirmationAsync(_f.Customer.Id, _orderId, _assignmentId, first.RequestId, new RespondArrivalRequest(false), default);
        var second = await _f.Tracking().RequestConfirmationAsync(_partner.Id, _orderId, _assignmentId, default);
        var confirmed = await _f.Tracking().RespondConfirmationAsync(_f.Customer.Id, _orderId, _assignmentId, second.RequestId, new RespondArrivalRequest(true), default);

        Assert.Equal(Now.AddSeconds(300), first.ExpiresAt);
        Assert.Equal(ErrorCodes.ArrivalConfirmationNotAllowed, duplicate.Code);
        Assert.Equal(ArrivalConfirmationStatus.Denied, denied.Status);
        Assert.Equal(ArrivalConfirmationStatus.Confirmed, confirmed.Status);
        var a = await AssignmentAsync();
        Assert.Equal((byte)OrderStatus.Arrived, a.Status);
        Assert.Equal((byte)ArrivalMethod.Customer, a.ArrivalMethod);
        Assert.NotNull(a.CustomerConfirmedArrivalAt);

        var asked = _f.Notifier.OrderEvents.Where(e => e.Event == RealtimeEvents.ArrivalConfirmationRequested).ToList();
        Assert.Equal(2, asked.Count);
        Assert.All(asked, e => Assert.Equal((AppFlavor.Customer, _f.Customer.Id), Assert.Single(e.To)));
        var resolved = _f.Notifier.OrderEvents.Where(e => e.Event == RealtimeEvents.ArrivalConfirmationResolved).ToList();
        Assert.All(resolved, e => Assert.Equal((AppFlavor.Partner, _partner.Id), Assert.Single(e.To)));
        Assert.Contains(_f.Notifier.OrderEvents, e => e.Event == RealtimeEvents.OrderStatusChanged
            && ((OrderStatusChangedDto)e.Payload).AssignmentStatus == OrderStatus.Arrived);
    }

    [Fact]
    public async Task CustomerConfirmation_ExpiresAfterTtl_AndStopsAfterThreeRequests()
    {
        await StartAsync();
        var first = await _f.Tracking().RequestConfirmationAsync(_partner.Id, _orderId, _assignmentId, default);
        _f.Ctx.Clock.Advance(TimeSpan.FromSeconds(301));

        var expired = await Assert.ThrowsAsync<ApiException>(() =>
            _f.Tracking().RespondConfirmationAsync(_f.Customer.Id, _orderId, _assignmentId, first.RequestId, new RespondArrivalRequest(true), default));
        await _f.Tracking().RequestConfirmationAsync(_partner.Id, _orderId, _assignmentId, default); // 2nd: the 1st expired
        _f.Ctx.Clock.Advance(TimeSpan.FromSeconds(301));
        await _f.Tracking().RequestConfirmationAsync(_partner.Id, _orderId, _assignmentId, default); // 3rd
        _f.Ctx.Clock.Advance(TimeSpan.FromSeconds(301));
        var fourth = await Assert.ThrowsAsync<ApiException>(() => _f.Tracking().RequestConfirmationAsync(_partner.Id, _orderId, _assignmentId, default));

        Assert.Equal(StatusCodes.Status409Conflict, expired.StatusCode);
        Assert.Equal(ErrorCodes.ArrivalConfirmationNotAllowed, fourth.Code);
        Assert.Equal((byte)OrderStatus.OnTheWay, (await AssignmentAsync()).Status);
    }

    [Fact]
    public async Task ArriveByGps_CancelsAPendingConfirmation()
    {
        await StartAsync();
        var pending = await _f.Tracking().RequestConfirmationAsync(_partner.Id, _orderId, _assignmentId, default);

        await _f.Tracking().ArriveAsync(_partner.Id, _orderId, _assignmentId, Fix(), default);

        var row = await _f.Ctx.Db.ArrivalConfirmationRequests.AsNoTracking().SingleAsync(r => r.Id == pending.RequestId);
        Assert.Equal((byte)ArrivalConfirmationStatus.Cancelled, row.Status);
    }

    [Theory]
    [InlineData(0.1, 1)]
    [InlineData(2.5, 6)]
    [InlineData(10, 24)]
    public void Eta_IsHaversineAt25KmH_RoundedUp(double km, int minutes) =>
        Assert.Equal(minutes, TrackingService.EtaMinutes(km));
}
