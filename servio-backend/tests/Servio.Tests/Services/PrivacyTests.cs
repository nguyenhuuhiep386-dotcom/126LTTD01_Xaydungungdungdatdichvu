using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.EntityFrameworkCore;
using Servio.Api.Common;
using Servio.Api.Data.Entities;
using Servio.Api.Services.Orders;
using Servio.Api.Services.Partners;
using Servio.Api.Services.Quotes;
using Servio.Api.Services.Users;

namespace Servio.Tests.Services;

/// <summary>Luật 91/2025: consent per purpose (Điều 9, 10), account deletion (Điều 14), live location minimisation.</summary>
public sealed class PrivacyTests : IAsyncLifetime
{
    private const string Version = ConsentService.DefaultPolicyVersion;
    private MarketplaceFixture _f = null!;

    public async Task InitializeAsync() => _f = await MarketplaceFixture.CreateAsync();

    public Task DisposeAsync()
    {
        _f.Dispose();
        return Task.CompletedTask;
    }

    private ConsentService Consents() => _f.Ctx.Consents();
    private AccountDeletionService Deletion() => new(_f.Ctx.Db, _f.Ctx.Files(), Consents(), _f.Ctx.Clock, NullLogger<AccountDeletionService>.Instance);
    private PartnerProfileService Profiles() => new(_f.Ctx.Db, _f.Ctx.Files(), _f.Configs(), Consents(), _f.Ctx.Clock);

    [Fact]
    public async Task Posting_NeedsTermsConsent_OnTheCurrentVersion_AndStopsAfterWithdrawal()
    {
        var customer = await _f.Ctx.AddUserAsync(UserRoleType.Customer, "Khách Mới", "+84911111111", consented: false);
        var home = new Address
        {
            Id = Guid.NewGuid(), UserId = customer.Id, Label = "Nhà", ReceiverName = "Khách", ReceiverPhone = "+84911111111",
            FullAddress = "1 Võ Văn Ngân", Latitude = MarketplaceFixture.HomeLat, Longitude = MarketplaceFixture.HomeLng,
            IsDefault = true, CreatedAt = _f.Ctx.Clock.GetUtcNow(), UpdatedAt = _f.Ctx.Clock.GetUtcNow(),
        };
        _f.Ctx.Db.Addresses.Add(home);
        await _f.Ctx.Db.SaveChangesAsync();
        var post = _f.Post() with { AddressId = home.Id };

        var none = await Assert.ThrowsAsync<ApiException>(() => _f.Requests().CreateAsync(customer.Id, post, default));
        var stale = await Assert.ThrowsAsync<ApiException>(() => Consents().RecordAsync(customer.Id, AppFlavor.Customer,
            new ConsentRequest(ConsentPurpose.TermsAndPrivacy, true, "2025-01-01"), null, null, default));
        var wrongApp = await Assert.ThrowsAsync<ApiException>(() => Consents().RecordAsync(customer.Id, AppFlavor.Customer,
            new ConsentRequest(ConsentPurpose.LocationSharing, true, Version), null, null, default));
        var list = await Consents().RecordAsync(customer.Id, AppFlavor.Customer,
            new ConsentRequest(ConsentPurpose.TermsAndPrivacy, true, Version), "10.0.0.5", "Servio/1.0", default);
        await _f.Requests().CreateAsync(customer.Id, post, default);
        await Consents().RecordAsync(customer.Id, AppFlavor.Customer, new ConsentRequest(ConsentPurpose.TermsAndPrivacy, false, Version), null, null, default);
        var withdrawn = await Assert.ThrowsAsync<ApiException>(() => _f.Requests().CreateAsync(customer.Id, post with { Title = "Bài thứ hai sau khi rút" }, default));

        Assert.Equal(ErrorCodes.ConsentRequired, none.Code);
        Assert.Equal(ErrorCodes.ResourceVersionConflict, stale.Code);
        Assert.Equal(ErrorCodes.ValidationError, wrongApp.Code);
        Assert.Equal((ConsentPurpose.TermsAndPrivacy, true, true), (list[0].Purpose, list[0].Granted, list[0].IsCurrentVersion));
        Assert.Equal(ErrorCodes.ConsentRequired, withdrawn.Code);
        var proof = await _f.Ctx.Db.UserConsents.AsNoTracking().FirstAsync(c => c.UserId == customer.Id && c.Granted);
        Assert.Equal(("10.0.0.5", "Servio/1.0", Version), (proof.IpAddress, proof.UserAgent, proof.PolicyVersion));
    }

    [Fact]
    public async Task Partner_NeedsSeparateConsents_ForKycPhotosAndLocation()
    {
        var partner = await _f.Ctx.AddUserAsync(UserRoleType.Partner, "Thợ Mới", "+84922222222", consented: false);
        partner.PartnerProfile!.VerificationStatus = (byte)PartnerVerificationStatus.Approved; // so only consent is missing
        await _f.Ctx.Db.SaveChangesAsync();

        var kyc = await Assert.ThrowsAsync<ApiException>(() =>
            Profiles().AddDocumentAsync(partner.Id, new AddDocumentRequest(DocumentType.IdFront, "/api/v1/files/x"), default));
        var location = await Assert.ThrowsAsync<ApiException>(() =>
            Profiles().SetOnlineAsync(partner.Id, new OnlineStatusRequest(true, 10.85m, 106.77m), default));

        Assert.Equal(ErrorCodes.ConsentRequired, kyc.Code);
        Assert.Contains("nhạy cảm", kyc.Message); // khoản 4 Điều 6 NĐ 356/2025: say it is sensitive data
        Assert.Equal(ErrorCodes.ConsentRequired, location.Code);
    }

    [Fact]
    public async Task ConsentCheck_CanBeSwitchedOff_ForAnAppWithoutConsentScreens()
    {
        _f.Ctx.Db.SystemConfigs.Add(new SystemConfig { Key = "privacy.require_consent", Value = "false", DataType = "bool", Group = "PRIVACY", UpdatedAt = _f.Ctx.Clock.GetUtcNow() });
        await _f.Ctx.Db.SaveChangesAsync();
        var partner = await _f.Ctx.AddUserAsync(UserRoleType.Partner, "Thợ Cũ", "+84933333333", consented: false);

        await Consents().EnsureAsync(partner.Id, ConsentPurpose.LocationSharing, default);
    }

    [Fact]
    public async Task DeleteAccount_IsBlockedWhileARequestIsOpen_ThenErasesPersonalData()
    {
        var db = _f.Ctx.Db;
        var post = await _f.Requests().CreateAsync(_f.Customer.Id, _f.Post(), default);
        var avatar = await _f.UploadImageAsync(_f.Customer.Id, FilePurpose.Avatar);
        db.UserDevices.Add(new UserDevice { Id = Guid.NewGuid(), UserId = _f.Customer.Id, DeviceId = "phone", FcmToken = "token", AppFlavor = 1, Platform = 1, IsActive = true, LastActiveAt = _f.Ctx.Clock.GetUtcNow() });
        db.RefreshTokens.Add(new RefreshToken { Id = Guid.NewGuid(), UserId = _f.Customer.Id, TokenHash = "h", SessionId = Guid.NewGuid(), DeviceId = "phone", AppFlavor = 1, ExpiresAt = _f.Ctx.Clock.GetUtcNow().AddDays(30), CreatedAt = _f.Ctx.Clock.GetUtcNow() });
        await db.SaveChangesAsync();

        var blocked = await Assert.ThrowsAsync<ApiException>(() => Deletion().DeleteAsync(_f.Customer.Id, AppFlavor.Customer, default));
        await _f.Requests().CancelAsync(_f.Customer.Id, post.Id, "Không cần nữa", default);
        await Deletion().DeleteAsync(_f.Customer.Id, AppFlavor.Customer, default);

        Assert.Equal(ErrorCodes.AccountDeletionBlocked, blocked.Code);
        var user = await db.Users.AsNoTracking().SingleAsync(u => u.Id == _f.Customer.Id);
        Assert.Equal((AccountDeletionService.DeletedName, (byte)UserStatus.Deleted), (user.FullName, user.Status));
        Assert.StartsWith("#", user.PhoneNumber); // the real number is freed for a new sign-up
        Assert.False(await db.UserDevices.AnyAsync(d => d.UserId == _f.Customer.Id));
        Assert.All(await db.RefreshTokens.AsNoTracking().Where(t => t.UserId == _f.Customer.Id).ToListAsync(), t => Assert.NotNull(t.RevokedAt));
        var address = await db.Addresses.AsNoTracking().SingleAsync(a => a.UserId == _f.Customer.Id);
        Assert.Equal(("Đã xoá", 0m), (address.FullAddress, address.Latitude));
        var file = await db.UploadedFiles.AsNoTracking().SingleAsync(x => x.Purpose == (byte)FilePurpose.Avatar);
        Assert.Equal(5, file.Status);
        Assert.False(File.Exists(_f.Ctx.Files().PhysicalPath(file)));
        Assert.Contains(await Consents().ListAsync(_f.Customer.Id, default), c => c.Purpose == ConsentPurpose.TermsAndPrivacy && !c.Granted);
        // The order history of the post stays, the person is gone from it.
        Assert.True(await db.ServiceRequests.AnyAsync(r => r.Id == post.Id));
        _ = avatar;
    }

    [Fact]
    public async Task DeleteAccount_IsBlockedWhileCommissionDebtIsOwed()
    {
        var partner = await _f.AddPartnerAsync();
        _f.Ctx.Db.Wallets.Add(new Servio.Api.Data.Entities.Wallet { Id = Guid.NewGuid(), UserId = partner.Id, DebtBalance = 67_500, Currency = "VND", UpdatedAt = _f.Ctx.Clock.GetUtcNow() });
        await _f.Ctx.Db.SaveChangesAsync();

        var e = await Assert.ThrowsAsync<ApiException>(() => Deletion().DeleteAsync(partner.Id, AppFlavor.Partner, default));

        Assert.Equal(ErrorCodes.AccountDeletionBlocked, e.Code);
        Assert.Contains("67.500", e.Message);
    }

    [Fact]
    public async Task LivePosition_IsDroppedWhenTheTripEnds()
    {
        var partner = await _f.AddPartnerAsync();
        var post = await _f.Requests().CreateAsync(_f.Customer.Id, _f.Post(), default);
        var quote = await _f.Quotes().SubmitAsync(partner.Id, post.Id, new SubmitQuoteRequest(300_000, 60, _f.Ctx.Clock.GetUtcNow().AddMinutes(30), null, 1), default);
        var version = (await _f.Ctx.Db.Quotes.AsNoTracking().SingleAsync(q => q.Id == quote.Id)).RowVersion;
        var order = await _f.Orders().AcceptQuoteAsync(_f.Customer.Id, post.Id,
            new AcceptQuotesRequest([quote.Id], new() { [quote.Id] = Convert.ToBase64String(version) }), default);
        var aid = order.Assignments.Single().Id;
        await _f.Assignments().AcceptAsync(partner.Id, order.Id, aid, default);
        await _f.Tracking().StartMovingAsync(partner.Id, order.Id, aid, new StartMovingRequest(MarketplaceFixture.HomeLat + 0.02m, MarketplaceFixture.HomeLng, 10), default);

        await _f.Tracking().ArriveAsync(partner.Id, order.Id, aid,
            new ArriveRequest(MarketplaceFixture.HomeLat + 0.001m, MarketplaceFixture.HomeLng, 20, _f.Ctx.Clock.GetUtcNow(), false, null), default);

        var a = await _f.Ctx.Db.OrderAssignments.AsNoTracking().SingleAsync(x => x.Id == aid);
        Assert.Null(a.LastLatitude);
        Assert.Null(a.LastLocationAt);
        Assert.NotNull(a.ArrivalLatitude); // the check-in point is kept as evidence
    }
}
