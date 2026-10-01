using Microsoft.EntityFrameworkCore;
using Servio.Api.Common;
using Servio.Api.Data.Entities;
using Servio.Api.Services.Feed;
using Servio.Api.Services.Quotes;
using Servio.Api.Services.Requests;

namespace Servio.Tests.Services;

/// <summary>BE-2: posting (#36–#40), feed and matching (#46, NewPost), quotes (#42, #50, #52, #53).</summary>
public sealed class MarketplaceTests : IAsyncLifetime
{
    private MarketplaceFixture _f = null!;

    public async Task InitializeAsync() => _f = await MarketplaceFixture.CreateAsync();

    public Task DisposeAsync()
    {
        _f.Dispose();
        return Task.CompletedTask;
    }

    private DateTimeOffset Now => _f.Ctx.Clock.GetUtcNow();

    private SubmitQuoteRequest Quote(long amount = 300_000, int revision = 1, DateTimeOffset? availableFrom = null) =>
        new(amount, 60, availableFrom ?? Now.AddMinutes(30), "Có mặt sau 30 phút", revision);

    [Theory]
    [InlineData("Gọi em 0901234567 nhé", true)]
    [InlineData("Liên hệ +84 90 123 4567", true)]
    [InlineData("Xem zalo.me/abc hoặc www.servio.vn", true)]
    [InlineData("Ngân sách 1.500.000 đồng, máy 1.5HP", false)]
    [InlineData("Lắp ở tầng 3, phòng 302, TP.HCM", false)]
    public void ContentGuard_DetectsPhonesAndLinks_ButNotPrices(string text, bool expected) =>
        Assert.Equal(expected, ContentGuard.ContainsContactInfo(text));

    [Fact]
    public async Task Create_NowPost_IsOpenFor2Hours_AndPushedOnlyToEligibleOnlinePartners()
    {
        var near = await _f.AddPartnerAsync(latOffset: 0.01m);
        var offline = await _f.AddPartnerAsync(latOffset: 0.01m, online: false);
        var far = await _f.AddPartnerAsync(latOffset: 0.2m);                        // ~22 km
        var wrongSkill = await _f.AddPartnerAsync(skillCategoryId: _f.OtherServiceId);
        var unapproved = await _f.AddPartnerAsync(status: PartnerVerificationStatus.Pending);

        var post = await _f.Requests().CreateAsync(_f.Customer.Id, _f.Post(), default);

        Assert.Equal(ServiceRequestStatus.Open, post.Status);
        Assert.StartsWith("SR261001", post.Code);
        Assert.Equal(Now.AddHours(2), post.ExpiresAt);
        var recipient = Assert.Single(_f.Notifier.NewPosts);
        Assert.Equal(near.Id, recipient.PartnerUserId);
        Assert.Equal(1.1, recipient.Item.DistanceKm);
        Assert.Equal("Linh Chiểu, Thủ Đức", recipient.Item.AreaLabel);
        Assert.DoesNotContain(_f.Notifier.NewPosts, r => r.PartnerUserId == offline.Id || r.PartnerUserId == far.Id
                                                         || r.PartnerUserId == wrongSkill.Id || r.PartnerUserId == unapproved.Id);
    }

    [Fact]
    public async Task Create_ScheduledPost_ExpiresAtStartOr24h_AndRejectsTooSoon()
    {
        var requests = _f.Requests();
        var inTwoDays = Now.AddDays(2);
        var soon = await requests.CreateAsync(_f.Customer.Id, _f.Post(schedule: ScheduleType.Scheduled, start: Now.AddHours(3)), default);
        var later = await requests.CreateAsync(_f.Customer.Id, _f.Post(schedule: ScheduleType.Scheduled, start: inTwoDays), default);
        var tooSoon = await Assert.ThrowsAsync<ApiException>(() =>
            requests.CreateAsync(_f.Customer.Id, _f.Post(schedule: ScheduleType.Scheduled, start: Now.AddMinutes(10)), default));

        Assert.Equal(Now.AddHours(3), soon.ExpiresAt);
        Assert.Equal(Now.AddHours(24), later.ExpiresAt);
        Assert.Equal(inTwoDays.AddHours(2), later.ScheduledEndAt);
        Assert.Equal("scheduledStartAt", tooSoon.Field);
    }

    [Fact]
    public async Task Create_RejectsContactInfo_OtherUsersAddress_GroupCategory_AndUnpaidFees()
    {
        var requests = _f.Requests();
        var phone = await Assert.ThrowsAsync<ApiException>(() => requests.CreateAsync(_f.Customer.Id, _f.Post("Sửa máy lạnh gọi 0901234567"), default));
        var stranger = await _f.Ctx.AddUserAsync(UserRoleType.Customer, "Người Lạ", "+84988888888");
        var foreignAddress = await Assert.ThrowsAsync<ApiException>(() => requests.CreateAsync(stranger.Id, _f.Post(), default));
        var group = await _f.Ctx.Db.ServiceCategories.SingleAsync(c => c.ParentId == null);
        var groupCategory = await Assert.ThrowsAsync<ApiException>(() =>
            requests.CreateAsync(_f.Customer.Id, _f.Post() with { ServiceCategoryId = group.Id }, default));

        Assert.Equal(ErrorCodes.ContactInfoNotAllowed, phone.Code);
        Assert.Equal("addressId", foreignAddress.Field);
        Assert.Equal("serviceCategoryId", groupCategory.Field);
    }

    [Fact]
    public async Task Feed_ShowsEligiblePosts_RespectsRequirementsAndSort()
    {
        var partner = await _f.AddPartnerAsync(latOffset: 0.02m, years: 2);
        var requests = _f.Requests();
        var easy = await requests.CreateAsync(_f.Customer.Id, _f.Post("Vệ sinh máy lạnh phòng khách"), default);
        _f.Ctx.Clock.Advance(TimeSpan.FromMinutes(1));
        await requests.CreateAsync(_f.Customer.Id, _f.Post("Cần thợ trên 5 năm kinh nghiệm", requireYears: 5), default);
        await requests.CreateAsync(_f.Customer.Id, _f.Post("Cần thợ đánh giá từ 4.5 sao", minRating: 4.5m), default);

        var feed = await _f.Feed().GetFeedAsync(partner.Id, null, null, FeedSort.Newest, 1, 20, default);
        var otherCategory = await _f.Feed().GetFeedAsync(partner.Id, [_f.OtherServiceId], null, FeedSort.Distance, 1, 20, default);

        var item = Assert.Single(feed.Items);
        Assert.Equal(easy.Id, item.RequestId);
        Assert.False(item.HasQuoted);
        Assert.Equal("Khách N.", item.Customer.DisplayName);
        Assert.Empty(otherCategory.Items);
    }

    [Fact]
    public async Task SubmitQuote_CreatesConversation_NotifiesCustomer_AndShowsInLists()
    {
        var partner = await _f.AddPartnerAsync();
        var post = await _f.Requests().CreateAsync(_f.Customer.Id, _f.Post(), default);
        var quotes = _f.Quotes();

        var quote = await quotes.SubmitAsync(partner.Id, post.Id, Quote(), default);

        Assert.Equal(QuoteStatus.Pending, quote.Status);
        Assert.NotNull(quote.ConversationId);
        var pushed = Assert.Single(_f.Notifier.NewQuotes);
        Assert.Equal(_f.Customer.Id, pushed.CustomerUserId);
        Assert.Equal(300_000, pushed.Quote.Amount);
        Assert.Equal("Trần Thợ", pushed.Quote.Partner.FullName);
        Assert.NotEmpty(pushed.Quote.RowVersion);
        var customerList = await quotes.ListForCustomerAsync(_f.Customer.Id, post.Id, QuoteSort.Time, 1, 20, default);
        Assert.Equal(quote.Id, Assert.Single(customerList.Items).Id);
        Assert.Equal(1, (await _f.Requests().GetForOwnerAsync(_f.Customer.Id, post.Id, default)).QuoteCount);
        var view = await _f.Feed().GetForPartnerAsync(partner.Id, post.Id, default);
        Assert.Equal(quote.Id, view.OwnQuote?.Id);
        Assert.False(view.CanQuote);

        var again = await Assert.ThrowsAsync<ApiException>(() => quotes.SubmitAsync(partner.Id, post.Id, Quote(), default));
        Assert.Equal(ErrorCodes.QuoteAlreadyExists, again.Code);
    }

    [Fact]
    public async Task SubmitQuote_RejectsOffline_Ineligible_WrongRevision_OutOfWindow_AndDebt()
    {
        var offline = await _f.AddPartnerAsync(online: false);
        var otherSkill = await _f.AddPartnerAsync(skillCategoryId: _f.OtherServiceId);
        var ok = await _f.AddPartnerAsync();
        var post = await _f.Requests().CreateAsync(_f.Customer.Id, _f.Post(), default);
        var quotes = _f.Quotes();

        var e1 = await Assert.ThrowsAsync<ApiException>(() => quotes.SubmitAsync(offline.Id, post.Id, Quote(), default));
        var e2 = await Assert.ThrowsAsync<ApiException>(() => quotes.SubmitAsync(otherSkill.Id, post.Id, Quote(), default));
        var e3 = await Assert.ThrowsAsync<ApiException>(() => quotes.SubmitAsync(ok.Id, post.Id, Quote(revision: 2), default));
        var e4 = await Assert.ThrowsAsync<ApiException>(() => quotes.SubmitAsync(ok.Id, post.Id, Quote(availableFrom: Now.AddHours(5)), default));
        _f.Ctx.Db.Wallets.Add(new Wallet { Id = Guid.NewGuid(), UserId = ok.Id, Currency = "VND", DebtBalance = 250_000, UpdatedAt = Now });
        await _f.Ctx.Db.SaveChangesAsync();
        var e5 = await Assert.ThrowsAsync<ApiException>(() => quotes.SubmitAsync(ok.Id, post.Id, Quote(), default));

        Assert.Equal(ErrorCodes.PartnerOffline, e1.Code);
        Assert.Equal(ErrorCodes.PartnerNotEligible, e2.Code);
        Assert.Equal(ErrorCodes.ResourceVersionConflict, e3.Code);
        Assert.Equal("availableFrom", e4.Field);
        Assert.Equal(ErrorCodes.OutstandingDebt, e5.Code);
    }

    [Fact]
    public async Task SubmitQuote_LimitsPendingQuotesToFive()
    {
        var partner = await _f.AddPartnerAsync();
        var requests = _f.Requests();
        var quotes = _f.Quotes();
        for (var i = 0; i < 5; i++)
        {
            var post = await requests.CreateAsync(_f.Customer.Id, _f.Post($"Vệ sinh máy lạnh số {i + 1}"), default);
            await quotes.SubmitAsync(partner.Id, post.Id, Quote(), default);
        }
        var sixth = await requests.CreateAsync(_f.Customer.Id, _f.Post("Vệ sinh máy lạnh số 6"), default);

        var error = await Assert.ThrowsAsync<ApiException>(() => quotes.SubmitAsync(partner.Id, sixth.Id, Quote(), default));

        Assert.Equal(ErrorCodes.QuoteLimitExceeded, error.Code);
        Assert.Equal(5, (await quotes.ListMineAsync(partner.Id, QuoteStatus.Pending, 1, 20, default)).TotalCount);
    }

    [Fact]
    public async Task Withdraw_RemovesQuoteFromCustomerList_AndNotifies()
    {
        var partner = await _f.AddPartnerAsync();
        var post = await _f.Requests().CreateAsync(_f.Customer.Id, _f.Post(), default);
        var quotes = _f.Quotes();
        var quote = await quotes.SubmitAsync(partner.Id, post.Id, Quote(), default);

        await quotes.WithdrawAsync(partner.Id, quote.Id, default);

        Assert.Empty((await quotes.ListForCustomerAsync(_f.Customer.Id, post.Id, QuoteSort.Time, 1, 20, default)).Items);
        Assert.Equal(quote.Id, Assert.Single(_f.Notifier.Withdrawn).QuoteId);
        var twice = await Assert.ThrowsAsync<ApiException>(() => quotes.WithdrawAsync(partner.Id, quote.Id, default));
        Assert.Equal(ErrorCodes.InvalidStatusTransition, twice.Code);
    }

    [Fact]
    public async Task CustomerQuotes_SortByPrice()
    {
        var a = await _f.AddPartnerAsync();
        var b = await _f.AddPartnerAsync();
        var post = await _f.Requests().CreateAsync(_f.Customer.Id, _f.Post(), default);
        var quotes = _f.Quotes();
        await quotes.SubmitAsync(a.Id, post.Id, Quote(450_000), default);
        await quotes.SubmitAsync(b.Id, post.Id, Quote(350_000), default);

        var byPrice = await quotes.ListForCustomerAsync(_f.Customer.Id, post.Id, QuoteSort.Price, 1, 20, default);

        Assert.Equal([350_000L, 450_000L], byPrice.Items.Select(q => q.Amount));
    }

    [Fact]
    public async Task Cancel_ExpiresPendingQuotes_AndBlocksQuoting()
    {
        var partner = await _f.AddPartnerAsync();
        var requests = _f.Requests();
        var post = await requests.CreateAsync(_f.Customer.Id, _f.Post(), default);
        await _f.Quotes().SubmitAsync(partner.Id, post.Id, Quote(), default);

        await requests.CancelAsync(_f.Customer.Id, post.Id, "Đã tự sửa được", default);

        Assert.Equal((byte)QuoteStatus.Expired, (await _f.Ctx.Db.Quotes.SingleAsync()).Status);
        var again = await Assert.ThrowsAsync<ApiException>(() => requests.CancelAsync(_f.Customer.Id, post.Id, null, default));
        Assert.Equal(ErrorCodes.InvalidStatusTransition, again.Code);
    }

    [Fact]
    public async Task Cancel_MoreThanFiveTimesInAWeek_RestrictsPosting()
    {
        var requests = _f.Requests();
        for (var i = 0; i < 6; i++)
        {
            var post = await requests.CreateAsync(_f.Customer.Id, _f.Post($"Vệ sinh máy lạnh lần {i + 1}"), default);
            await requests.CancelAsync(_f.Customer.Id, post.Id, null, default);
        }

        var error = await Assert.ThrowsAsync<ApiException>(() => requests.CreateAsync(_f.Customer.Id, _f.Post(), default));

        Assert.Equal(ErrorCodes.PostingRestricted, error.Code);
    }

    [Fact]
    public async Task ExpireDue_ClosesOverduePosts_AndTheirQuotes()
    {
        var partner = await _f.AddPartnerAsync();
        var requests = _f.Requests();
        var post = await requests.CreateAsync(_f.Customer.Id, _f.Post(), default);
        await _f.Quotes().SubmitAsync(partner.Id, post.Id, Quote(), default);

        Assert.Equal(0, await requests.ExpireDueAsync(default));
        _f.Ctx.Clock.Advance(TimeSpan.FromHours(2));
        Assert.Equal(1, await requests.ExpireDueAsync(default));

        Assert.Equal((byte)ServiceRequestStatus.Expired, (await _f.Ctx.Db.ServiceRequests.SingleAsync()).Status);
        Assert.Equal((byte)QuoteStatus.Expired, (await _f.Ctx.Db.Quotes.SingleAsync()).Status);
        Assert.Empty((await _f.Feed().GetFeedAsync(partner.Id, null, null, FeedSort.Distance, 1, 20, default)).Items);
    }
}
