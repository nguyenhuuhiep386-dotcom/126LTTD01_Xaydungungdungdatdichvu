using Microsoft.EntityFrameworkCore;
using Servio.Api.Common;
using Servio.Api.Data.Entities;
using Servio.Api.Hubs;
using Servio.Api.Services.Orders;
using Servio.Api.Services.Quotes;

namespace Servio.Tests.Services;

/// <summary>BE-4: #43 accept a quote, #62–#66, #69, #70, #73, #74 and the acceptance timeout.</summary>
public sealed class OrderServiceTests : IAsyncLifetime
{
    private MarketplaceFixture _f = null!;
    private User _partner = null!;

    public async Task InitializeAsync()
    {
        _f = await MarketplaceFixture.CreateAsync();
        _partner = await _f.AddPartnerAsync();
    }

    public Task DisposeAsync()
    {
        _f.Dispose();
        return Task.CompletedTask;
    }

    private DateTimeOffset Now => _f.Ctx.Clock.GetUtcNow();

    /// <summary>A NOW post by the customer and a quote from <paramref name="partner"/> starting in 30 minutes.</summary>
    private async Task<(Guid RequestId, PartnerQuoteDto Quote)> PostAndQuoteAsync(User partner, long amount = 450_000, int minutesFromNow = 30)
    {
        var post = await _f.Requests().CreateAsync(_f.Customer.Id, _f.Post(), default);
        var quote = await _f.Quotes().SubmitAsync(partner.Id, post.Id,
            new SubmitQuoteRequest(amount, 60, Now.AddMinutes(minutesFromNow), null, 1), default);
        return (post.Id, quote);
    }

    private async Task<AcceptQuotesRequest> AcceptBodyAsync(Guid quoteId)
    {
        var version = (await _f.Ctx.Db.Quotes.AsNoTracking().SingleAsync(q => q.Id == quoteId)).RowVersion;
        return new AcceptQuotesRequest([quoteId], new() { [quoteId] = Convert.ToBase64String(version) });
    }

    private async Task<OrderDetailDto> OrderAsync(User? partner = null, long amount = 450_000)
    {
        var (requestId, quote) = await PostAndQuoteAsync(partner ?? _partner, amount);
        return await _f.Orders().AcceptQuoteAsync(_f.Customer.Id, requestId, await AcceptBodyAsync(quote.Id), default);
    }

    /// <summary>Stands in for #67/#68 (BE-5): puts the assignment at ARRIVED.</summary>
    private async Task ArriveAsync(Guid assignmentId)
    {
        var a = await _f.Ctx.Db.OrderAssignments.SingleAsync(x => x.Id == assignmentId);
        a.Status = (byte)OrderStatus.Arrived;
        await _f.Ctx.Db.SaveChangesAsync();
    }

    [Fact]
    public async Task AcceptQuote_CreatesPendingOrder_AndClosesTheOtherQuotes()
    {
        var other = await _f.AddPartnerAsync();
        var (requestId, quote) = await PostAndQuoteAsync(_partner);
        var otherQuote = await _f.Quotes().SubmitAsync(other.Id, requestId, new SubmitQuoteRequest(400_000, 60, Now.AddMinutes(20), null, 1), default);

        var order = await _f.Orders().AcceptQuoteAsync(_f.Customer.Id, requestId, await AcceptBodyAsync(quote.Id), default);

        Assert.StartsWith("OD261001", order.Code);
        Assert.Equal(OrderStatus.Pending, order.Status);
        Assert.Equal(new OrderPricingDto(450_000, 0, 450_000, null, null, null), order.Pricing);
        var assignment = Assert.Single(order.Assignments);
        Assert.Equal(Now.AddMinutes(10), assignment.AcceptanceDeadlineAt);
        Assert.Equal(quote.AvailableFrom, assignment.ReservedStartAt);
        Assert.Null(assignment.Partner.PhoneNumber); // not before ACCEPTED (F-ORD-06)
        Assert.Equal(quote.ConversationId, order.ConversationId);

        var db = _f.Ctx.Db;
        Assert.Equal((byte)ServiceRequestStatus.Matched, (await db.ServiceRequests.AsNoTracking().SingleAsync(r => r.Id == requestId)).Status);
        Assert.Equal((byte)QuoteStatus.Accepted, (await db.Quotes.AsNoTracking().SingleAsync(q => q.Id == quote.Id)).Status);
        Assert.Equal((byte)QuoteStatus.Rejected, (await db.Quotes.AsNoTracking().SingleAsync(q => q.Id == otherQuote.Id)).Status);
        var chosenChat = await db.Conversations.AsNoTracking().SingleAsync(c => c.Id == quote.ConversationId);
        var otherChat = await db.Conversations.AsNoTracking().SingleAsync(c => c.Id == otherQuote.ConversationId);
        Assert.Equal(order.Id, chosenChat.OrderId);
        Assert.Equal((byte)ConversationStatus.Active, chosenChat.Status);
        Assert.Equal((byte)ConversationStatus.ReadOnly, otherChat.Status);

        var accepted = Assert.Single(_f.Notifier.OrderEvents, e => e.Event == RealtimeEvents.QuoteAccepted);
        Assert.Equal((AppFlavor.Partner, _partner.Id), Assert.Single(accepted.To));
        var rejected = Assert.Single(_f.Notifier.OrderEvents, e => e.Event == RealtimeEvents.QuoteRejected);
        Assert.Equal((AppFlavor.Partner, other.Id), Assert.Single(rejected.To));
        Assert.Contains(_f.Notifier.Messages, m => m.SenderRole == ActorType.System && m.Content.Contains(order.Code));

        var commission = await db.OrderAssignments.AsNoTracking().Where(a => a.OrderId == order.Id).Select(a => a.CommissionAmount).SingleAsync();
        Assert.Equal(67_500m, commission);
        var timeline = await _f.Orders().TimelineAsync(_f.Customer.Id, AppFlavor.Customer, order.Id, default);
        Assert.Equal(OrderStatus.Pending, Assert.Single(timeline).Status);
    }

    [Fact]
    public async Task AcceptQuote_IsIdempotent_ButRejectsAnotherQuoteOrAStaleVersion()
    {
        var other = await _f.AddPartnerAsync();
        var (requestId, quote) = await PostAndQuoteAsync(_partner);
        var otherQuote = await _f.Quotes().SubmitAsync(other.Id, requestId, new SubmitQuoteRequest(400_000, 60, Now.AddMinutes(20), null, 1), default);
        var stale = await Assert.ThrowsAsync<ApiException>(() => _f.Orders().AcceptQuoteAsync(_f.Customer.Id, requestId,
            new AcceptQuotesRequest([quote.Id], new() { [quote.Id] = "AAAAAAAAAAA=" }), default));
        var body = await AcceptBodyAsync(quote.Id);

        var first = await _f.Orders().AcceptQuoteAsync(_f.Customer.Id, requestId, body, default);
        var again = await _f.Orders().AcceptQuoteAsync(_f.Customer.Id, requestId, body, default);
        var switchQuote = await Assert.ThrowsAsync<ApiException>(async () =>
            await _f.Orders().AcceptQuoteAsync(_f.Customer.Id, requestId, await AcceptBodyAsync(otherQuote.Id), default));
        var twoQuotes = await Assert.ThrowsAsync<ApiException>(() => _f.Orders().AcceptQuoteAsync(_f.Customer.Id, requestId,
            new AcceptQuotesRequest([quote.Id, otherQuote.Id], body.QuoteVersions), default));

        Assert.Equal(ErrorCodes.ResourceVersionConflict, stale.Code);
        Assert.Equal(first.Id, again.Id);
        Assert.Equal(1, await _f.Ctx.Db.Orders.CountAsync());
        Assert.Equal(ErrorCodes.QuoteAlreadyAccepted, switchQuote.Code);
        Assert.Equal(ErrorCodes.ValidationError, twoQuotes.Code);
    }

    [Fact]
    public async Task AcceptQuote_RefusesBusyPartner_ButNotForUnpaidCashDisputes()
    {
        var orders = new List<OrderDetailDto>();
        for (var i = 0; i < 3; i++)
        {
            // Back-to-back 60-minute windows (08:00, 09:00, 10:00) so only the capacity rule applies.
            var (requestId, quote) = await PostAndQuoteAsync(_partner, minutesFromNow: i * 60);
            orders.Add(await _f.Orders().AcceptQuoteAsync(_f.Customer.Id, requestId, await AcceptBodyAsync(quote.Id), default));
        }
        // Overlaps the first order, which stops counting once it has an open UNPAID_CASH dispute.
        var (fourthRequest, fourthQuote) = await PostAndQuoteAsync(_partner, minutesFromNow: 0);

        var full = await Assert.ThrowsAsync<ApiException>(async () =>
            await _f.Orders().AcceptQuoteAsync(_f.Customer.Id, fourthRequest, await AcceptBodyAsync(fourthQuote.Id), default));

        _f.Ctx.Db.Disputes.Add(new Dispute
        {
            Id = Guid.NewGuid(), Code = "DS1", OrderId = orders[0].Id, RaisedByUserId = _partner.Id, RaisedByType = (byte)ActorType.Partner,
            Reason = (byte)DisputeReason.UnpaidCash, Description = "Khách chưa trả tiền mặt", Status = (byte)DisputeStatus.Open,
            PreviousOrderStatus = (byte)OrderStatus.Completed, SlaDueAt = Now.AddDays(1), CreatedAt = Now, UpdatedAt = Now,
        });
        await _f.Ctx.Db.SaveChangesAsync();
        var freed = await _f.Orders().AcceptQuoteAsync(_f.Customer.Id, fourthRequest, await AcceptBodyAsync(fourthQuote.Id), default);

        Assert.Equal(ErrorCodes.PartnerCapacityExceeded, full.Code);
        Assert.Equal(OrderStatus.Pending, freed.Status);
    }

    [Fact]
    public async Task AcceptQuote_RefusesOverlappingSchedule()
    {
        await OrderAsync();                                                              // 08:30–09:30
        var (requestId, quote) = await PostAndQuoteAsync(_partner, minutesFromNow: 60);  // 09:00–10:00

        var e = await Assert.ThrowsAsync<ApiException>(async () =>
            await _f.Orders().AcceptQuoteAsync(_f.Customer.Id, requestId, await AcceptBodyAsync(quote.Id), default));

        Assert.Equal(ErrorCodes.ScheduleConflict, e.Code);
    }

    [Fact]
    public async Task FullFlow_ExtraChargeIsPricedWithTheSnapshotRate_AndCompleteWaitsForTheCustomer()
    {
        var order = await OrderAsync();
        var aid = order.Assignments[0].Id;
        var assignments = _f.Assignments();

        var accepted = await assignments.AcceptAsync(_partner.Id, order.Id, aid, default);
        await ArriveAsync(aid);
        var noPhotos = await Assert.ThrowsAsync<ApiException>(() =>
            assignments.StartWorkAsync(_partner.Id, order.Id, aid, new StartWorkRequest(["/uploads/x.png"]), default));
        var blank = await Assert.ThrowsAsync<ApiException>(() =>
            assignments.StartWorkAsync(_partner.Id, order.Id, aid, new StartWorkRequest([" "]), default));
        var started = await assignments.StartWorkAsync(_partner.Id, order.Id, aid,
            new StartWorkRequest([await _f.UploadImageAsync(_partner.Id)]), default);
        var charge = await _f.ExtraCharges().CreateAsync(_partner.Id, order.Id,
            new CreateExtraChargeRequest(aid, "Thay tụ máy lạnh 25uF", 180_000, null), default);
        var afterPhoto = await _f.UploadImageAsync(_partner.Id);
        var blocked = await Assert.ThrowsAsync<ApiException>(() =>
            assignments.CompleteAsync(_partner.Id, order.Id, aid, new CompleteWorkRequest([afterPhoto], null), default));
        var approved = await _f.ExtraCharges().RespondAsync(_f.Customer.Id, order.Id, charge.Id, new RespondExtraChargeRequest(true, null), default);
        var twice = await Assert.ThrowsAsync<ApiException>(() =>
            _f.ExtraCharges().RespondAsync(_f.Customer.Id, order.Id, charge.Id, new RespondExtraChargeRequest(false, null), default));
        var done = await assignments.CompleteAsync(_partner.Id, order.Id, aid, new CompleteWorkRequest([afterPhoto], "Đã vệ sinh và thay tụ"), default);

        Assert.Equal(OrderStatus.Accepted, accepted.Status);
        Assert.Equal(ErrorCodes.FileNotReady, noPhotos.Code);
        Assert.Equal(ErrorCodes.ValidationError, blank.Code);
        Assert.Equal(OrderStatus.InProgress, started.Status);
        Assert.Equal(ErrorCodes.ExtraChargePending, blocked.Code);
        Assert.Equal(ExtraChargeStatus.Approved, approved.Status);
        Assert.Equal(ErrorCodes.InvalidStatusTransition, twice.Code);
        Assert.Equal(OrderStatus.CompletedByPartner, done.Status);

        // 2.6.1 example: 450.000 + 180.000 at 15% → 630.000, commission 94.500, partner earns 535.500.
        var partnerView = await _f.Orders().GetAsync(_partner.Id, AppFlavor.Partner, order.Id, default);
        Assert.Equal(new OrderPricingDto(450_000, 180_000, 630_000, 15m, 94_500, 535_500), partnerView.Pricing);
        var customerView = await _f.Orders().GetAsync(_f.Customer.Id, AppFlavor.Customer, order.Id, default);
        Assert.Equal(new OrderPricingDto(450_000, 180_000, 630_000, null, null, null), customerView.Pricing);
        Assert.Equal(Now.AddHours(24), customerView.AutoConfirmAt);
        Assert.Single(customerView.Assignments[0].BeforeImages);
        Assert.Equal(new[] { afterPhoto }, customerView.Assignments[0].AfterImages);
        Assert.Contains(_f.Notifier.Messages, m => m.Content.Contains("phát sinh 180.000 đ: Thay tụ máy lạnh 25uF"));
        Assert.Contains(_f.Notifier.OrderEvents, e => e.Event == RealtimeEvents.ExtraChargeRequested && e.To[0] == (AppFlavor.Customer, _f.Customer.Id));
        Assert.Contains(_f.Notifier.OrderEvents, e => e.Event == RealtimeEvents.ExtraChargeResponded && e.To[0] == (AppFlavor.Partner, _partner.Id));

        var timeline = await _f.Orders().TimelineAsync(_f.Customer.Id, AppFlavor.Customer, order.Id, default);
        Assert.Equal(new[] { OrderStatus.Pending, OrderStatus.Accepted, OrderStatus.InProgress, OrderStatus.CompletedByPartner },
            timeline.Select(t => t.Status));
        Assert.Equal("Đã vệ sinh và thay tụ", timeline[^1].Note);
    }

    [Fact]
    public async Task Detail_HidesContactAndExactAddressFromThePartnerUntilAccepted()
    {
        var order = await OrderAsync();
        var aid = order.Assignments[0].Id;
        var stranger = await _f.AddPartnerAsync();

        var before = await _f.Orders().GetAsync(_partner.Id, AppFlavor.Partner, order.Id, default);
        await _f.Assignments().AcceptAsync(_partner.Id, order.Id, aid, default);
        var after = await _f.Orders().GetAsync(_partner.Id, AppFlavor.Partner, order.Id, default);
        var customerView = await _f.Orders().GetAsync(_f.Customer.Id, AppFlavor.Customer, order.Id, default);
        var hidden = await Assert.ThrowsAsync<ApiException>(() => _f.Orders().GetAsync(stranger.Id, AppFlavor.Partner, order.Id, default));

        Assert.Equal("Linh Chiểu, Thủ Đức", before.Address);
        Assert.Null(before.Latitude);
        Assert.Null(before.Customer.PhoneNumber);
        Assert.Equal("Khách N.", before.Customer.DisplayName);
        Assert.Equal(_f.Home.FullAddress, after.Address);
        Assert.Equal(_f.Customer.PhoneNumber, after.Customer.PhoneNumber);
        Assert.Equal(_f.Customer.FullName, after.Customer.DisplayName);
        Assert.Equal(_partner.PhoneNumber, customerView.Assignments[0].Partner.PhoneNumber);
        Assert.Equal(404, hidden.StatusCode);
    }

    [Fact]
    public async Task Reject_CancelsTheOrder_FreezesTheChat_AndKeepsThePostMatched()
    {
        var order = await OrderAsync();
        var aid = order.Assignments[0].Id;

        await _f.Assignments().RejectAsync(_partner.Id, order.Id, aid, new RejectAssignmentRequest("Bận việc đột xuất"), default);
        var acceptLater = await Assert.ThrowsAsync<ApiException>(() => _f.Assignments().AcceptAsync(_partner.Id, order.Id, aid, default));

        var detail = await _f.Orders().GetAsync(_f.Customer.Id, AppFlavor.Customer, order.Id, default);
        Assert.Equal(OrderStatus.Cancelled, detail.Status);
        Assert.Equal(ActorType.Partner, detail.CancelledBy);
        Assert.Equal("Bận việc đột xuất", detail.CancelReason);
        Assert.Equal(ErrorCodes.InvalidStatusTransition, acceptLater.Code);
        Assert.Equal((byte)ConversationStatus.ReadOnly, (await _f.Ctx.Db.Conversations.AsNoTracking().SingleAsync(c => c.Id == order.ConversationId)).Status);
        Assert.Equal((byte)ServiceRequestStatus.Matched, (await _f.Ctx.Db.ServiceRequests.AsNoTracking().SingleAsync(r => r.Id == order.ServiceRequestId)).Status);
        var changed = Assert.Single(_f.Notifier.OrderEvents, e => e.Event == RealtimeEvents.OrderStatusChanged);
        Assert.Equal(OrderStatus.Cancelled, ((OrderStatusChangedDto)changed.Payload).Status);
    }

    [Fact]
    public async Task Timeout_CancelsUnacceptedAssignments_AndLateAcceptFails()
    {
        var order = await OrderAsync();
        var aid = order.Assignments[0].Id;
        var accepted = await OrderAsync(await _f.AddPartnerAsync());
        var otherPartner = await _f.Ctx.Db.OrderAssignments.AsNoTracking().Where(a => a.OrderId == accepted.Id)
            .Select(a => a.PartnerProfile.UserId).SingleAsync();
        await _f.Assignments().AcceptAsync(otherPartner, accepted.Id, accepted.Assignments[0].Id, default);

        _f.Ctx.Clock.Advance(TimeSpan.FromMinutes(10));
        var late = await Assert.ThrowsAsync<ApiException>(() => _f.Assignments().AcceptAsync(_partner.Id, order.Id, aid, default));
        var expired = await _f.Assignments().ExpireUnacceptedAsync(default);
        var again = await _f.Assignments().ExpireUnacceptedAsync(default);

        Assert.Equal(ErrorCodes.InvalidStatusTransition, late.Code);
        Assert.Equal(1, expired);
        Assert.Equal(0, again);
        var detail = await _f.Orders().GetAsync(_f.Customer.Id, AppFlavor.Customer, order.Id, default);
        Assert.Equal(OrderStatus.Cancelled, detail.Status);
        Assert.Equal(ActorType.System, detail.CancelledBy);
        Assert.Equal(AssignmentService.TimeoutReason, detail.Assignments[0].CancelReason);
        Assert.Equal(OrderStatus.Accepted, (await _f.Orders().GetAsync(_f.Customer.Id, AppFlavor.Customer, accepted.Id, default)).Status);
    }

    [Fact]
    public async Task ListMine_SplitsByTabAndApp()
    {
        var active = await OrderAsync();
        var cancelled = await OrderAsync(await _f.AddPartnerAsync());
        var cancelledPartner = await _f.Ctx.Db.OrderAssignments.AsNoTracking().Where(a => a.OrderId == cancelled.Id)
            .Select(a => a.PartnerProfile.UserId).SingleAsync();
        await _f.Assignments().RejectAsync(cancelledPartner, cancelled.Id, cancelled.Assignments[0].Id, new RejectAssignmentRequest("Không đi được"), default);
        var orders = _f.Orders();

        var customerActive = await orders.ListMineAsync(_f.Customer.Id, AppFlavor.Customer, OrderListStatus.Active, 1, 20, default);
        var customerCancelled = await orders.ListMineAsync(_f.Customer.Id, AppFlavor.Customer, OrderListStatus.Cancelled, 1, 20, default);
        var partnerAll = await orders.ListMineAsync(_partner.Id, AppFlavor.Partner, null, 1, 20, default);
        var partnerCompleted = await orders.ListMineAsync(_partner.Id, AppFlavor.Partner, OrderListStatus.Completed, 1, 20, default);
        var customerAsPartner = await orders.ListMineAsync(_f.Customer.Id, AppFlavor.Partner, null, 1, 20, default);

        Assert.Equal(active.Id, Assert.Single(customerActive.Items).Id);
        Assert.Equal(cancelled.Id, Assert.Single(customerCancelled.Items).Id);
        var mine = Assert.Single(partnerAll.Items);
        Assert.Equal(active.Id, mine.Id);
        Assert.Equal(382_500, mine.Earning);
        Assert.Equal("Khách N.", mine.Counterpart.DisplayName);
        Assert.Empty(partnerCompleted.Items);
        Assert.Empty(customerAsPartner.Items);
    }
}
