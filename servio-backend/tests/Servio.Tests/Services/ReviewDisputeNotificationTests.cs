using Microsoft.EntityFrameworkCore;
using Servio.Api.Common;
using Servio.Api.Data.Entities;
using Servio.Api.Services.Admin;
using Servio.Api.Services.Notifications;
using Servio.Api.Services.Orders;
using Servio.Api.Services.Quotes;
using Servio.Api.Services.Reviews;

namespace Servio.Tests.Services;

/// <summary>BE-7: #95/#96/#100 reviews, #146–#148 disputes + admin decision, #101–#104 notifications and FCM push.</summary>
public sealed class ReviewDisputeNotificationTests : IAsyncLifetime
{
    private const decimal Lat = MarketplaceFixture.HomeLat;
    private const decimal Lng = MarketplaceFixture.HomeLng;
    private static readonly AdminActor Admin = new(Guid.NewGuid(), null, null);

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

    private async Task<(Guid OrderId, Guid AssignmentId)> AcceptedOrderAsync()
    {
        var post = await _f.Requests().CreateAsync(_f.Customer.Id, _f.Post(), default);
        var quote = await _f.Quotes().SubmitAsync(_partner.Id, post.Id, new SubmitQuoteRequest(450_000, 60, Now.AddMinutes(30), null, 1), default);
        var version = (await _f.Ctx.Db.Quotes.AsNoTracking().SingleAsync(q => q.Id == quote.Id)).RowVersion;
        var order = await _f.Orders().AcceptQuoteAsync(_f.Customer.Id, post.Id,
            new AcceptQuotesRequest([quote.Id], new() { [quote.Id] = Convert.ToBase64String(version) }), default);
        var aid = order.Assignments.Single().Id;
        await _f.Assignments().AcceptAsync(_partner.Id, order.Id, aid, default);
        return (order.Id, aid);
    }

    /// <summary>Accepted → moving → arrived by GPS → working → COMPLETED_BY_PARTNER.</summary>
    private async Task<(Guid OrderId, Guid AssignmentId)> WorkDoneAsync()
    {
        var (orderId, aid) = await AcceptedOrderAsync();
        await _f.Tracking().StartMovingAsync(_partner.Id, orderId, aid, new StartMovingRequest(Lat + 0.02m, Lng, 10), default);
        await _f.Tracking().ArriveAsync(_partner.Id, orderId, aid, new ArriveRequest(Lat + 0.001m, Lng, 20, Now, false, null), default);
        await _f.Assignments().StartWorkAsync(_partner.Id, orderId, aid, new StartWorkRequest([await _f.UploadImageAsync(_partner.Id)]), default);
        await _f.Assignments().CompleteAsync(_partner.Id, orderId, aid, new CompleteWorkRequest([await _f.UploadImageAsync(_partner.Id)], null), default);
        return (orderId, aid);
    }

    private async Task<(Guid OrderId, Guid AssignmentId)> CompletedOrderAsync()
    {
        var (orderId, aid) = await WorkDoneAsync();
        var version = Convert.ToBase64String((await _f.Ctx.Db.Orders.AsNoTracking().SingleAsync(o => o.Id == orderId)).RowVersion);
        await _f.Completion().ConfirmAsync(_f.Customer.Id, orderId, new ConfirmCompletionRequest(version), default);
        return (orderId, aid);
    }

    private Task<Order> OrderAsync(Guid id) => _f.Ctx.Db.Orders.AsNoTracking().SingleAsync(o => o.Id == id);

    private async Task AddDeviceAsync(Guid userId, AppFlavor flavor, string token)
    {
        _f.Ctx.Db.UserDevices.Add(new UserDevice
        {
            Id = Guid.NewGuid(), UserId = userId, DeviceId = token, FcmToken = token, AppFlavor = (byte)flavor,
            Platform = 1, IsActive = true, LastActiveAt = Now,
        });
        await _f.Ctx.Db.SaveChangesAsync();
    }

    [Fact]
    public async Task Reviews_TwoWay_UpdateRatingsAndThePendingList()
    {
        var (orderId, aid) = await CompletedOrderAsync();
        var pendingBefore = await _f.Reviews().PendingAsync(_f.Customer.Id, AppFlavor.Customer, default);

        var byCustomer = await _f.Reviews().CreateAsync(_f.Customer.Id, AppFlavor.Customer, orderId,
            new CreateReviewRequest(aid, 5, 5, 4, 5, 4, "Làm nhanh, sạch sẽ", null), default);
        var byPartner = await _f.Reviews().CreateAsync(_partner.Id, AppFlavor.Partner, orderId,
            new CreateReviewRequest(aid, 4, 5, 5, 5, 5, null, ["PAID_ON_TIME", "FRIENDLY"]), default);
        var twice = await Assert.ThrowsAsync<ApiException>(() => _f.Reviews().CreateAsync(_f.Customer.Id, AppFlavor.Customer, orderId,
            new CreateReviewRequest(aid, 1, null, null, null, null, null, null), default));

        var pending = Assert.Single(pendingBefore);
        Assert.Equal((orderId, aid, Now.AddDays(5)), (pending.OrderId, pending.AssignmentId, pending.DeadlineAt));
        Assert.Equal(ActorType.Customer, byCustomer.ReviewerType);
        Assert.Equal(4, byCustomer.QualityRating);
        Assert.Null(byPartner.QualityRating); // criteria only rate a partner's work
        Assert.Equal(["PAID_ON_TIME", "FRIENDLY"], byPartner.Tags);
        Assert.Equal(ErrorCodes.ReviewAlreadySubmitted, twice.Code);

        var partner = await _f.Ctx.Db.PartnerProfiles.AsNoTracking().SingleAsync(p => p.UserId == _partner.Id);
        Assert.Equal((5m, 1, 1), (partner.AverageRating!.Value, partner.TotalReviews, partner.CompletedOrders));
        var customer = await _f.Ctx.Db.CustomerProfiles.AsNoTracking().SingleAsync(c => c.UserId == _f.Customer.Id);
        Assert.Equal((4m, 1), (customer.AverageRating!.Value, customer.TotalReviews));
        Assert.Equal(2, (await _f.Reviews().ListForOrderAsync(_partner.Id, AppFlavor.Partner, orderId, default)).Count);
        Assert.Empty(await _f.Reviews().PendingAsync(_f.Customer.Id, AppFlavor.Customer, default));
        Assert.True(await _f.Ctx.Db.Notifications.AnyAsync(n => n.UserId == _partner.Id && n.Type == (byte)NotificationType.Review));
    }

    [Fact]
    public async Task Review_IsRefused_BeforeCompletion_AndAfterTheWindow()
    {
        var (openId, openAid) = await AcceptedOrderAsync();
        var early = await Assert.ThrowsAsync<ApiException>(() => _f.Reviews().CreateAsync(_f.Customer.Id, AppFlavor.Customer, openId,
            new CreateReviewRequest(openAid, 5, null, null, null, null, null, null), default));
        await _f.Cancellation().PartnerCancelAsync(_partner.Id, openId, openAid, new PartnerCancelRequest("Bận việc gia đình"), default);
        var (orderId, aid) = await CompletedOrderAsync();
        _f.Ctx.Clock.Advance(TimeSpan.FromDays(5) + TimeSpan.FromMinutes(1));

        var late = await Assert.ThrowsAsync<ApiException>(() => _f.Reviews().CreateAsync(_f.Customer.Id, AppFlavor.Customer, orderId,
            new CreateReviewRequest(aid, 5, null, null, null, null, null, null), default));

        Assert.Equal(ErrorCodes.InvalidStatusTransition, early.Code);
        Assert.Equal(ErrorCodes.ReviewWindowClosed, late.Code);
    }

    [Fact]
    public async Task Dispute_PausesTheOrderAndAutoConfirm_AdminRejectResumesWithTheTimeLeft()
    {
        var (orderId, aid) = await WorkDoneAsync();
        _f.Ctx.Clock.Advance(TimeSpan.FromHours(2));
        var evidence = await _f.UploadImageAsync(_f.Customer.Id, FilePurpose.DisputeEvidence);

        var dispute = await _f.Disputes().CreateAsync(_f.Customer.Id, AppFlavor.Customer, orderId,
            new CreateDisputeRequest(DisputeReason.WorkQuality, "Máy lạnh vẫn chảy nước sau khi sửa", [evidence]), default);
        var second = await Assert.ThrowsAsync<ApiException>(() => _f.Disputes().CreateAsync(_partner.Id, AppFlavor.Partner, orderId,
            new CreateDisputeRequest(DisputeReason.Other, "Khách không hợp tác khi kiểm tra", null), default));
        var frozen = await Assert.ThrowsAsync<ApiException>(() => _f.Cancellation().PartnerCancelAsync(_partner.Id, orderId, aid,
            new PartnerCancelRequest("Không làm nữa"), default));
        _f.Ctx.Clock.Advance(TimeSpan.FromHours(30));
        var autoConfirmed = await _f.Completion().AutoConfirmDueAsync(default);
        var paused = await OrderAsync(orderId);

        Assert.StartsWith("DS", dispute.Code);
        Assert.Equal((DisputeStatus.Open, ActorType.Customer), (dispute.Status, dispute.RaisedByType));
        Assert.Single(dispute.EvidenceUrls);
        Assert.Equal(ErrorCodes.DisputeNotAllowed, second.Code);
        Assert.Equal(ErrorCodes.OrderDisputed, frozen.Code);
        Assert.Equal(0, autoConfirmed);
        Assert.Equal(((byte)OrderStatus.Disputed, (DateTimeOffset?)null), (paused.Status, paused.AutoConfirmAt));
        Assert.Equal(22 * 3600, (await _f.Ctx.Db.Disputes.AsNoTracking().SingleAsync()).AutoConfirmRemainingSeconds);

        await _f.Disputes().ResolveAsync(Admin, dispute.Id, upheld: false, "Đã gọi hai bên, máy chạy bình thường", default);

        var resumed = await OrderAsync(orderId);
        Assert.Equal((byte)OrderStatus.CompletedByPartner, resumed.Status);
        Assert.Equal(Now.AddHours(22), resumed.AutoConfirmAt);
        var closed = await _f.Disputes().GetAsync(_partner.Id, AppFlavor.Partner, dispute.Id, default);
        Assert.Equal((DisputeStatus.Rejected, "Đã gọi hai bên, máy chạy bình thường"), (closed.Status, closed.ResolutionNote));
        Assert.True(await _f.Ctx.Db.AuditLogs.AnyAsync(a => a.Action == "DISPUTE_REJECTED"));
        // An admin decision notifies both sides.
        var last = await _f.Ctx.Db.Notifications.AsNoTracking().Where(n => n.Body.Contains(dispute.Code + " đã được xử lý")).ToListAsync();
        Assert.Equal(2, last.Count);
    }

    [Fact]
    public async Task Dispute_IsRefused_PastTheWindowAfterCompletion()
    {
        var (orderId, _) = await CompletedOrderAsync();
        _f.Ctx.Clock.Advance(TimeSpan.FromHours(73));

        var e = await Assert.ThrowsAsync<ApiException>(() => _f.Disputes().CreateAsync(_f.Customer.Id, AppFlavor.Customer, orderId,
            new CreateDisputeRequest(DisputeReason.Damage, "Phát hiện vết nứt trên dàn lạnh", null), default));

        Assert.Equal(ErrorCodes.DisputeNotAllowed, e.Code);
    }

    [Fact]
    public async Task Notifications_GoToTheOtherSide_ArePushedPerApp_AndDeadTokensAreDropped()
    {
        await AddDeviceAsync(_f.Customer.Id, AppFlavor.Customer, "customer-phone");
        await AddDeviceAsync(_f.Customer.Id, AppFlavor.Partner, "same-user-partner-app"); // must not get customer pushes
        await AddDeviceAsync(_partner.Id, AppFlavor.Partner, "dead-token");
        _f.Push.Dead.Add("dead-token");

        var (orderId, _) = await AcceptedOrderAsync(); // NEW_QUOTE → customer, QUOTE_ACCEPTED → partner, "đã xác nhận" → customer

        var customerInbox = await _f.Notifications().ListAsync(_f.Customer.Id, AppFlavor.Customer, null, 1, 20, default);
        var partnerInbox = await _f.Notifications().ListAsync(_partner.Id, AppFlavor.Partner, null, 1, 20, default);
        Assert.Contains(customerInbox.Items, n => n.Type == NotificationType.NewQuote);
        Assert.Contains(customerInbox.Items, n => n.Type == NotificationType.OrderStatusChanged && n.Body == "Đối tác đã xác nhận đơn.");
        Assert.Equal(NotificationType.QuoteAccepted, Assert.Single(partnerInbox.Items).Type); // not told about their own accept
        Assert.Equal($"orders/{orderId}", partnerInbox.Items[0].DeepLink);
        Assert.All(_f.Push.Sent.Where(p => p.Token != "dead-token"), p => Assert.Equal("customer-phone", p.Token));
        Assert.False((await _f.Ctx.Db.UserDevices.AsNoTracking().SingleAsync(d => d.DeviceId == "dead-token")).IsActive);

        var unread = await _f.Notifications().UnreadCountAsync(_f.Customer.Id, AppFlavor.Customer, default);
        await _f.Notifications().MarkReadAsync(_f.Customer.Id, AppFlavor.Customer, customerInbox.Items[0].Id, default);
        var afterOne = await _f.Notifications().UnreadCountAsync(_f.Customer.Id, AppFlavor.Customer, default);
        await _f.Notifications().MarkAllReadAsync(_f.Customer.Id, AppFlavor.Customer, default);

        Assert.Equal(customerInbox.TotalCount, unread.Total);
        Assert.Equal(unread.Total - 1, afterOne.Total);
        Assert.Equal(0, (await _f.Notifications().UnreadCountAsync(_f.Customer.Id, AppFlavor.Customer, default)).Total);
    }
}
