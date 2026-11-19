using Microsoft.EntityFrameworkCore;
using Servio.Api.Common;
using Servio.Api.Data.Entities;
using Servio.Api.Services.Admin;
using Servio.Api.Services.Orders;
using Servio.Api.Services.Quotes;
using Servio.Api.Services.Wallets;

namespace Servio.Tests.Services;

/// <summary>#177 "Khách chưa thanh toán": UNPAID_CASH dispute, reversal, AWAITING_PAYMENT and collection (2.5.3).</summary>
public sealed class UnpaidCashTests : IAsyncLifetime
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
    private ReportUnpaidRequest Report => new("Khách nói chuyển khoản sau nhưng tới giờ chưa nhận được tiền.", null);

    /// <summary>450.000 đ order (commission 67.500) at COMPLETED_BY_PARTNER.</summary>
    private async Task<(Guid OrderId, Guid AssignmentId)> WorkDoneAsync()
    {
        var post = await _f.Requests().CreateAsync(_f.Customer.Id, _f.Post(), default);
        var quote = await _f.Quotes().SubmitAsync(_partner.Id, post.Id, new SubmitQuoteRequest(450_000, 60, Now.AddMinutes(30), null, 1), default);
        var version = (await _f.Ctx.Db.Quotes.AsNoTracking().SingleAsync(q => q.Id == quote.Id)).RowVersion;
        var order = await _f.Orders().AcceptQuoteAsync(_f.Customer.Id, post.Id,
            new AcceptQuotesRequest([quote.Id], new() { [quote.Id] = Convert.ToBase64String(version) }), default);
        var aid = order.Assignments.Single().Id;
        await _f.Assignments().AcceptAsync(_partner.Id, order.Id, aid, default);
        await _f.Tracking().StartMovingAsync(_partner.Id, order.Id, aid, new StartMovingRequest(Lat + 0.02m, Lng, 10), default);
        await _f.Tracking().ArriveAsync(_partner.Id, order.Id, aid, new ArriveRequest(Lat + 0.001m, Lng, 20, Now, false, null), default);
        await _f.Assignments().StartWorkAsync(_partner.Id, order.Id, aid, new StartWorkRequest([await _f.UploadImageAsync(_partner.Id)]), default);
        await _f.Assignments().CompleteAsync(_partner.Id, order.Id, aid, new CompleteWorkRequest([await _f.UploadImageAsync(_partner.Id)], null), default);
        return (order.Id, aid);
    }

    private async Task<(Guid OrderId, Guid AssignmentId)> CompletedAsync()
    {
        var o = await WorkDoneAsync();
        var version = Convert.ToBase64String((await _f.Ctx.Db.Orders.AsNoTracking().SingleAsync(x => x.Id == o.OrderId)).RowVersion);
        await _f.Completion().ConfirmAsync(_f.Customer.Id, o.OrderId, new ConfirmCompletionRequest(version), default);
        return o;
    }

    private Task<Order> OrderAsync(Guid id) => _f.Ctx.Db.Orders.AsNoTracking().Include(o => o.OrderAssignments).SingleAsync(o => o.Id == id);

    private Task<Servio.Api.Data.Entities.Wallet> WalletAsync() => _f.Ctx.Db.Wallets.AsNoTracking().SingleAsync(w => w.UserId == _partner.Id);

    [Fact]
    public async Task ReportAfterCompletion_Upheld_ReversesCash_ThenCollectionCompletesAgain()
    {
        var (orderId, aid) = await CompletedAsync();
        var completedAt = (await OrderAsync(orderId)).CompletedAt;

        var dispute = await _f.Disputes().ReportUnpaidAsync(_partner.Id, orderId, aid, Report, default);
        var twice = await Assert.ThrowsAsync<ApiException>(() => _f.Disputes().ReportUnpaidAsync(_partner.Id, orderId, aid, Report, default));
        await _f.Disputes().ResolveAsync(Admin, dispute.Id, upheld: true, "Khách xác nhận chưa chuyển tiền", default);

        Assert.Equal((DisputeReason.UnpaidCash, ActorType.Partner), (dispute.Reason, dispute.RaisedByType));
        Assert.Equal(ErrorCodes.DisputeAlreadyOpen, twice.Code);
        var waiting = await OrderAsync(orderId);
        Assert.Equal(((byte)OrderStatus.AwaitingPayment, (byte)PaymentStatus.Unpaid), (waiting.Status, waiting.PaymentStatus));
        Assert.Equal((byte)OrderStatus.AwaitingPayment, waiting.OrderAssignments.Single().Status);
        Assert.Equal(0m, (await WalletAsync()).DebtBalance); // 67.500 taken back
        var reversal = await _f.Ctx.Db.Transactions.AsNoTracking().SingleAsync(t => t.Type == (byte)TransactionType.PaymentReversal);
        Assert.Equal(450_000m, reversal.Amount);
        var blocked = await Assert.ThrowsAsync<ApiException>(() => _f.Requests().CreateAsync(_f.Customer.Id, _f.Post(), default));
        Assert.Equal(ErrorCodes.UnpaidOrder, blocked.Code);

        await _f.Completion().ConfirmCollectedAsync(Admin, orderId, default);

        var done = await OrderAsync(orderId);
        Assert.Equal(((byte)OrderStatus.Completed, (byte)PaymentStatus.Paid), (done.Status, done.PaymentStatus));
        Assert.Equal(completedAt, done.CompletedAt); // kept (2.5.3)
        Assert.Equal(67_500m, (await WalletAsync()).DebtBalance);
        var collected = await _f.Ctx.Db.Transactions.AsNoTracking().SingleAsync(t => t.BusinessEventKey == $"cod-collect:{aid}");
        Assert.False(collected.IsPresumed);
        Assert.Equal(1, (await _f.Ctx.Db.PartnerProfiles.AsNoTracking().SingleAsync(p => p.UserId == _partner.Id)).CompletedOrders);
        await _f.Requests().CreateAsync(_f.Customer.Id, _f.Post(), default); // the customer can post again
    }

    [Fact]
    public async Task ReportBeforeConfirmation_Upheld_GoesToAwaitingPaymentWithoutReversal()
    {
        var (orderId, aid) = await WorkDoneAsync();

        var dispute = await _f.Disputes().ReportUnpaidAsync(_partner.Id, orderId, aid, Report, default);
        _f.Ctx.Clock.Advance(TimeSpan.FromHours(30));
        Assert.Equal(0, await _f.Completion().AutoConfirmDueAsync(default)); // paused
        await _f.Disputes().ResolveAsync(Admin, dispute.Id, upheld: true, "Khách chưa trả", default);

        var order = await OrderAsync(orderId);
        Assert.Equal(((byte)OrderStatus.AwaitingPayment, (DateTimeOffset?)null), (order.Status, order.AutoConfirmAt));
        Assert.False(await _f.Ctx.Db.Transactions.AnyAsync(t => t.Type == (byte)TransactionType.PaymentReversal));
        Assert.False(await _f.Ctx.Db.WalletTransactions.AnyAsync()); // never settled, nothing to take back
        Assert.Equal(0, await _f.Completion().AutoConfirmDueAsync(default)); // AWAITING_PAYMENT never auto-completes
    }

    [Fact]
    public async Task Report_IsRefused_BeforeTheWorkIsDone_AndAfterTheWindow()
    {
        var (orderId, aid) = await CompletedAsync();
        _f.Ctx.Clock.Advance(TimeSpan.FromHours(24) + TimeSpan.FromMinutes(1));

        var late = await Assert.ThrowsAsync<ApiException>(() => _f.Disputes().ReportUnpaidAsync(_partner.Id, orderId, aid, Report, default));

        Assert.Equal(ErrorCodes.UnpaidReportWindowClosed, late.Code);
    }

    [Fact]
    public async Task Reversal_RefundsTheCommissionThePartnerAlreadyPaid()
    {
        var (orderId, aid) = await CompletedAsync();
        var payment = await _f.Wallets().DeclarePaymentAsync(_partner.Id, AppFlavor.Partner,
            new DeclareDebtPaymentRequest(null, "FT001", await _f.UploadImageAsync(_partner.Id, FilePurpose.DisputeEvidence)), default);
        await _f.Wallets().ConfirmPaymentAsync(Admin, payment.TransactionId, default); // debt 67.500 → 0

        var dispute = await _f.Disputes().ReportUnpaidAsync(_partner.Id, orderId, aid, Report, default);
        await _f.Disputes().ResolveAsync(Admin, dispute.Id, upheld: true, "Khách chưa trả", default);

        var wallet = await WalletAsync();
        Assert.Equal((0m, 67_500m), (wallet.DebtBalance, wallet.AvailableBalance));
    }

    [Fact]
    public async Task UnpaidReport_Rejected_KeepsTheOrderCompletedAndTheDebt()
    {
        var (orderId, aid) = await CompletedAsync();
        var dispute = await _f.Disputes().ReportUnpaidAsync(_partner.Id, orderId, aid, Report, default);

        await _f.Disputes().ResolveAsync(Admin, dispute.Id, upheld: false, "Khách có ảnh chuyển khoản", default);

        Assert.Equal((byte)OrderStatus.Completed, (await OrderAsync(orderId)).Status);
        Assert.Equal(67_500m, (await WalletAsync()).DebtBalance);
    }
}
