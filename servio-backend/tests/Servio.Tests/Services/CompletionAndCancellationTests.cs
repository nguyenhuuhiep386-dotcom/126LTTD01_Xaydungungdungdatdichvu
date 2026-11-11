using Microsoft.AspNetCore.Http;
using Microsoft.EntityFrameworkCore;
using Servio.Api.Common;
using Servio.Api.Data.Entities;
using Servio.Api.Services.Admin;
using Servio.Api.Services.Orders;
using Servio.Api.Services.Quotes;
using Servio.Api.Services.Wallets;

namespace Servio.Tests.Services;

/// <summary>BE-6: #71 COD completion + auto-confirm, #151/#72/#152/#180 cancellation, #87/#155/#156 debts and AW-18 confirm.</summary>
public sealed class CompletionAndCancellationTests : IAsyncLifetime
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

    /// <summary>A new order for the customer and _partner, accepted by the partner.</summary>
    private async Task<(Guid OrderId, Guid AssignmentId)> AcceptedOrderAsync(long amount = 450_000)
    {
        var post = await _f.Requests().CreateAsync(_f.Customer.Id, _f.Post(), default);
        var quote = await _f.Quotes().SubmitAsync(_partner.Id, post.Id, new SubmitQuoteRequest(amount, 60, Now.AddMinutes(30), null, 1), default);
        var version = (await _f.Ctx.Db.Quotes.AsNoTracking().SingleAsync(q => q.Id == quote.Id)).RowVersion;
        var order = await _f.Orders().AcceptQuoteAsync(_f.Customer.Id, post.Id,
            new AcceptQuotesRequest([quote.Id], new() { [quote.Id] = Convert.ToBase64String(version) }), default);
        var aid = order.Assignments.Single().Id;
        await _f.Assignments().AcceptAsync(_partner.Id, order.Id, aid, default);
        return (order.Id, aid);
    }

    private async Task MoveAsync(Guid orderId, Guid aid) =>
        await _f.Tracking().StartMovingAsync(_partner.Id, orderId, aid, new StartMovingRequest(Lat + 0.02m, Lng, 10), default);

    private async Task ArriveAsync(Guid orderId, Guid aid)
    {
        await MoveAsync(orderId, aid);
        await _f.Tracking().ArriveAsync(_partner.Id, orderId, aid, new ArriveRequest(Lat + 0.001m, Lng, 20, Now, false, null), default);
    }

    /// <summary>Demo step 4–6: move, arrive, before photo, extra 180.000 approved, after photo → COMPLETED_BY_PARTNER.</summary>
    private async Task WorkDoneAsync(Guid orderId, Guid aid, long extra = 180_000)
    {
        await ArriveAsync(orderId, aid);
        await _f.Assignments().StartWorkAsync(_partner.Id, orderId, aid, new StartWorkRequest([await _f.UploadImageAsync(_partner.Id)]), default);
        if (extra > 0)
        {
            var charge = await _f.ExtraCharges().CreateAsync(_partner.Id, orderId, new CreateExtraChargeRequest(aid, "Thay block máy nén", extra, null), default);
            await _f.ExtraCharges().RespondAsync(_f.Customer.Id, orderId, charge.Id, new RespondExtraChargeRequest(true, null), default);
        }
        await _f.Assignments().CompleteAsync(_partner.Id, orderId, aid, new CompleteWorkRequest([await _f.UploadImageAsync(_partner.Id)], null), default);
    }

    private async Task<string> VersionAsync(Guid orderId) =>
        Convert.ToBase64String((await _f.Ctx.Db.Orders.AsNoTracking().SingleAsync(o => o.Id == orderId)).RowVersion);

    private async Task<decimal> PartnerDebtAsync() =>
        await _f.Ctx.Db.Wallets.AsNoTracking().Where(w => w.UserId == _partner.Id).Select(w => w.DebtBalance).SingleOrDefaultAsync();

    private async Task<CancelResultDto> CancelOnTheWayAsync()
    {
        var (orderId, aid) = await AcceptedOrderAsync();
        await MoveAsync(orderId, aid);
        return await _f.Cancellation().CancelAsync(_f.Customer.Id, orderId, new CancelOrderRequest("Tôi bận việc đột xuất", await VersionAsync(orderId)), default);
    }

    [Fact]
    public async Task ConfirmCompletion_Cod_RecordsPresumedCashAndCommissionDebt_LikeExample261()
    {
        var (orderId, aid) = await AcceptedOrderAsync();
        await WorkDoneAsync(orderId, aid);
        var stale = await Assert.ThrowsAsync<ApiException>(() =>
            _f.Completion().ConfirmAsync(_f.Customer.Id, orderId, new ConfirmCompletionRequest("AAAAAAAAAAA="), default));

        var result = await _f.Completion().ConfirmAsync(_f.Customer.Id, orderId, new ConfirmCompletionRequest(await VersionAsync(orderId)), default);
        var again = await Assert.ThrowsAsync<ApiException>(async () =>
            await _f.Completion().ConfirmAsync(_f.Customer.Id, orderId, new ConfirmCompletionRequest(await VersionAsync(orderId)), default));

        Assert.Equal(ErrorCodes.ResourceVersionConflict, stale.Code);
        Assert.Equal(new CompletionResultDto(orderId, OrderStatus.Completed, false, 630_000, 630_000), result);
        Assert.Equal(ErrorCodes.InvalidStatusTransition, again.Code);

        var db = _f.Ctx.Db;
        var cash = await db.Transactions.AsNoTracking().SingleAsync(t => t.BusinessEventKey == $"cod-settle:{aid}");
        Assert.Equal(630_000m, cash.Amount);
        Assert.True(cash.IsPresumed);
        Assert.Equal((byte)TransactionStatus.Success, cash.Status);
        Assert.Equal(94_500m, await PartnerDebtAsync()); // demo step 7
        var line = await db.WalletTransactions.AsNoTracking().SingleAsync();
        Assert.Equal((byte)WalletTransactionType.Commission, line.Type);
        Assert.Equal(94_500m, line.BalanceAfter);
        var order = await db.Orders.AsNoTracking().SingleAsync(o => o.Id == orderId);
        Assert.Equal((byte)PaymentStatus.Paid, order.PaymentStatus);
        Assert.Equal(Now.AddHours(72), order.DisputeDeadlineAt);
        Assert.NotNull(order.ConfirmedAt);
    }

    [Fact]
    public async Task AutoConfirm_CompletesOrdersPastAutoConfirmAt()
    {
        var (orderId, aid) = await AcceptedOrderAsync();
        await WorkDoneAsync(orderId, aid, extra: 0);

        Assert.Equal(0, await _f.Completion().AutoConfirmDueAsync(default));
        _f.Ctx.Clock.Advance(TimeSpan.FromHours(24));
        Assert.Equal(1, await _f.Completion().AutoConfirmDueAsync(default));

        var order = await _f.Ctx.Db.Orders.AsNoTracking().SingleAsync(o => o.Id == orderId);
        Assert.Equal((byte)OrderStatus.Completed, order.Status);
        Assert.Null(order.ConfirmedAt);
        Assert.Equal(67_500m, await PartnerDebtAsync()); // 15% of 450.000
    }

    [Theory]
    [InlineData(OrderStatus.Pending, 450_000, 0L)]
    [InlineData(OrderStatus.Accepted, 450_000, 0L)]
    [InlineData(OrderStatus.OnTheWay, 450_000, 20_000L)]
    [InlineData(OrderStatus.Arrived, 450_000, 90_000L)] // 20%
    [InlineData(OrderStatus.Arrived, 100_000, 30_000L)] // minimum
    [InlineData(OrderStatus.InProgress, 450_000, null)]
    public void Fee_FollowsPolicy252(OrderStatus status, long amount, long? fee) =>
        Assert.Equal(fee, CancellationService.Fee(status, amount, new CancellationService.FeePolicy(20_000, 30_000, 20)));

    [Fact]
    public async Task CustomerCancel_OnTheWay_CreatesDueCharge_LikeDemoStep9()
    {
        var (orderId, aid) = await AcceptedOrderAsync();
        await MoveAsync(orderId, aid);
        var preview = await _f.Cancellation().PreviewAsync(_f.Customer.Id, orderId, default);

        var result = await _f.Cancellation().CancelAsync(_f.Customer.Id, orderId, new CancelOrderRequest("Tôi bận việc đột xuất", preview.RowVersion), default);

        Assert.Equal(20_000, preview.Fee);
        Assert.Equal(OrderStatus.Cancelled, result.Status);
        Assert.Equal(20_000, result.CancellationFee);
        var charge = Assert.Single(result.Charges);
        Assert.Equal((CancellationReason.CustomerCancel, CancellationChargeStatus.Due, 20_000L), (charge.Reason, charge.Status, charge.Amount));
        var wallet = await _f.Wallets().GetMineAsync(_f.Customer.Id, AppFlavor.Customer, default);
        Assert.Equal(20_000, wallet.DebtBalance);
    }

    [Fact]
    public async Task CustomerCancel_IsRefused_OnceWorkStarted()
    {
        var (orderId, aid) = await AcceptedOrderAsync();
        await ArriveAsync(orderId, aid);
        await _f.Assignments().StartWorkAsync(_partner.Id, orderId, aid, new StartWorkRequest([await _f.UploadImageAsync(_partner.Id)]), default);

        var e = await Assert.ThrowsAsync<ApiException>(() => _f.Cancellation().PreviewAsync(_f.Customer.Id, orderId, default));

        Assert.Equal(ErrorCodes.OrderNotCancellable, e.Code);
    }

    [Fact]
    public async Task PartnerCancel_AfterAccepting_IsFreeForTheCustomer()
    {
        var (orderId, aid) = await AcceptedOrderAsync();

        var change = await _f.Cancellation().PartnerCancelAsync(_partner.Id, orderId, aid, new PartnerCancelRequest("Xe hỏng giữa đường"), default);

        Assert.Equal(OrderStatus.Cancelled, change.Status);
        Assert.False(await _f.Ctx.Db.CancellationCharges.AnyAsync());
        var order = await _f.Ctx.Db.Orders.AsNoTracking().SingleAsync(o => o.Id == orderId);
        Assert.Equal((byte)ActorType.Partner, order.CancelledBy);
    }

    [Fact]
    public async Task CustomerNoShow_NeedsWaitAndAMessage_ThenChargesTheArrivedFee()
    {
        var (orderId, aid) = await AcceptedOrderAsync();
        await ArriveAsync(orderId, aid);
        var tooEarly = await Assert.ThrowsAsync<ApiException>(() =>
            _f.Cancellation().CustomerNoShowAsync(_partner.Id, orderId, aid, new CustomerNoShowRequest(null), default));
        _f.Ctx.Clock.Advance(TimeSpan.FromMinutes(16));
        var noMessage = await Assert.ThrowsAsync<ApiException>(() =>
            _f.Cancellation().CustomerNoShowAsync(_partner.Id, orderId, aid, new CustomerNoShowRequest(null), default));
        var conversation = await _f.Ctx.Db.Conversations.AsNoTracking().SingleAsync(c => c.OrderId == orderId);
        _f.Ctx.Db.Messages.Add(new Message
        {
            Id = Guid.NewGuid(), ConversationId = conversation.Id, SenderUserId = _partner.Id, ClientMessageId = Guid.NewGuid(),
            Type = 1, Content = "Em đến rồi, anh/chị ở nhà không ạ?", CreatedAt = Now,
        });
        await _f.Ctx.Db.SaveChangesAsync();

        var result = await _f.Cancellation().CustomerNoShowAsync(_partner.Id, orderId, aid, new CustomerNoShowRequest("Gọi không bắt máy"), default);

        Assert.Equal(ErrorCodes.NoShowNotAllowed, tooEarly.Code);
        Assert.Equal(ErrorCodes.NoShowNotAllowed, noMessage.Code);
        Assert.Equal(OrderStatus.Cancelled, result.Status);
        var charge = Assert.Single(result.Charges);
        Assert.Equal((CancellationReason.CustomerNoShow, 90_000L), (charge.Reason, charge.Amount));
    }

    [Fact]
    public async Task CustomerPaysCancellationFee_AdminConfirms_PartnerCreditOffsetsCommissionDebt()
    {
        // Partner owes 94.500 from a COD order, then the customer cancels another order on the way (20.000).
        var (orderId, aid) = await AcceptedOrderAsync();
        await WorkDoneAsync(orderId, aid);
        await _f.Completion().ConfirmAsync(_f.Customer.Id, orderId, new ConfirmCompletionRequest(await VersionAsync(orderId)), default);
        var cancelled = await CancelOnTheWayAsync();
        var evidence = await _f.UploadImageAsync(_f.Customer.Id, FilePurpose.DisputeEvidence);
        var chargeId = cancelled.Charges.Single().Id;

        var declared = await _f.Wallets().DeclarePaymentAsync(_f.Customer.Id, AppFlavor.Customer,
            new DeclareDebtPaymentRequest([chargeId], "FT26111100001", evidence), default);
        var twice = await Assert.ThrowsAsync<ApiException>(() => _f.Wallets().DeclarePaymentAsync(_f.Customer.Id, AppFlavor.Customer,
            new DeclareDebtPaymentRequest([chargeId], "FT26111100001", evidence), default));
        var waiting = await _f.Wallets().GetMineAsync(_f.Customer.Id, AppFlavor.Customer, default);
        await _f.Wallets().ConfirmPaymentAsync(Admin, declared.TransactionId, default);

        Assert.Equal((20_000L, TransactionStatus.Pending), (declared.Amount, declared.Status));
        Assert.Equal(ErrorCodes.NothingToPay, twice.Code);
        Assert.Equal(20_000, waiting.PendingPayment);
        var db = _f.Ctx.Db;
        var charge = await db.CancellationCharges.AsNoTracking().SingleAsync(c => c.Id == chargeId);
        Assert.Equal((byte)CancellationChargeStatus.Paid, charge.Status);
        Assert.NotNull(charge.CompensationCreditedAt);
        // Example 2.6.1 (v1.4): the 20.000 credit pays the debt down to 74.500, Available stays 0.
        var wallet = await db.Wallets.AsNoTracking().SingleAsync(w => w.UserId == _partner.Id);
        Assert.Equal((74_500m, 0m), (wallet.DebtBalance, wallet.AvailableBalance));
        var offset = await db.Transactions.AsNoTracking().SingleAsync(t => t.Provider == (byte)TransactionProvider.InternalOffset);
        Assert.Equal(20_000m, offset.Amount);
        Assert.Equal(0, (await _f.Wallets().GetMineAsync(_f.Customer.Id, AppFlavor.Customer, default)).DebtBalance);
        Assert.True(await db.AuditLogs.AnyAsync(a => a.Action == "DEBT_PAYMENT_CONFIRM"));
    }

    [Fact]
    public async Task PartnerPaysCommissionDebt_RejectThenConfirm()
    {
        var (orderId, aid) = await AcceptedOrderAsync();
        await WorkDoneAsync(orderId, aid);
        await _f.Completion().ConfirmAsync(_f.Customer.Id, orderId, new ConfirmCompletionRequest(await VersionAsync(orderId)), default);
        var evidence = await _f.UploadImageAsync(_partner.Id, FilePurpose.DisputeEvidence);
        var body = new DeclareDebtPaymentRequest(null, "FT26111100002", evidence);

        var first = await _f.Wallets().DeclarePaymentAsync(_partner.Id, AppFlavor.Partner, body, default);
        var debts = await _f.Wallets().ListDebtsAsync(_partner.Id, AppFlavor.Partner, default);
        await _f.Wallets().RejectPaymentAsync(Admin, first.TransactionId, "Không thấy tiền về", default);
        var second = await _f.Wallets().DeclarePaymentAsync(_partner.Id, AppFlavor.Partner, body, default);
        await _f.Wallets().ConfirmPaymentAsync(Admin, second.TransactionId, default);
        var nothing = await Assert.ThrowsAsync<ApiException>(() => _f.Wallets().DeclarePaymentAsync(_partner.Id, AppFlavor.Partner, body, default));
        var confirmedTwice = await Assert.ThrowsAsync<ApiException>(() => _f.Wallets().ConfirmPaymentAsync(Admin, second.TransactionId, default));

        Assert.Equal(94_500, first.Amount);
        Assert.Equal(first.Code, Assert.Single(debts).PendingTransactionCode);
        Assert.Equal(94_500, second.Amount);
        Assert.Equal(0m, await PartnerDebtAsync());
        Assert.Equal(ErrorCodes.NothingToPay, nothing.Code);
        Assert.Equal(StatusCodes.Status409Conflict, confirmedTwice.StatusCode);
        Assert.Empty(await _f.Wallets().ListDebtsAsync(_partner.Id, AppFlavor.Partner, default));
    }
}
