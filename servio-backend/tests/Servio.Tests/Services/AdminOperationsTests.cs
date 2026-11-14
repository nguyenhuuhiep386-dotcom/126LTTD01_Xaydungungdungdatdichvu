using Microsoft.EntityFrameworkCore;
using Servio.Api.Common;
using Servio.Api.Data.Entities;
using Servio.Api.Services.Admin;
using Servio.Api.Services.Quotes;

namespace Servio.Tests.Services;

/// <summary>QT-2: AW-03 lock/unlock users, AW-06 hide a post.</summary>
public sealed class AdminOperationsTests : IAsyncLifetime
{
    private static readonly AdminActor Admin = new(Guid.NewGuid(), "127.0.0.1", "test");
    private MarketplaceFixture _f = null!;

    public async Task InitializeAsync() => _f = await MarketplaceFixture.CreateAsync();

    public Task DisposeAsync()
    {
        _f.Dispose();
        return Task.CompletedTask;
    }

    private AdminUserService Users() => new(_f.Ctx.Db, _f.Ctx.Clock);

    [Fact]
    public async Task Lock_RevokesSessions_TakesPartnerOffline_ThenUnlock()
    {
        var partner = await _f.AddPartnerAsync(online: true);
        _f.Ctx.Db.RefreshTokens.Add(new RefreshToken
        {
            Id = Guid.NewGuid(), UserId = partner.Id, TokenHash = "hash", SessionId = Guid.NewGuid(), DeviceId = "phone",
            AppFlavor = (byte)AppFlavor.Partner, ExpiresAt = _f.Ctx.Clock.GetUtcNow().AddDays(30), CreatedAt = _f.Ctx.Clock.GetUtcNow(),
        });
        await _f.Ctx.Db.SaveChangesAsync();

        await Users().LockAsync(Admin, partner.Id, "Gian lận đánh giá", default);
        var twice = await Assert.ThrowsAsync<ApiException>(() => Users().LockAsync(Admin, partner.Id, "Lần nữa", default));

        var db = _f.Ctx.Db;
        var user = await db.Users.AsNoTracking().Include(u => u.PartnerProfile).SingleAsync(u => u.Id == partner.Id);
        Assert.Equal((byte)UserStatus.Suspended, user.Status);
        Assert.False(user.PartnerProfile!.IsOnline);
        Assert.NotNull((await db.RefreshTokens.AsNoTracking().SingleAsync()).RevokedAt);
        Assert.Equal(ErrorCodes.InvalidStatusTransition, twice.Code);

        await Users().UnlockAsync(Admin, partner.Id, default);

        Assert.Equal((byte)UserStatus.Active, (await db.Users.AsNoTracking().SingleAsync(u => u.Id == partner.Id)).Status);
        Assert.Equal(["USER_LOCKED", "USER_UNLOCKED"], await db.AuditLogs.OrderBy(a => a.CreatedAt).Select(a => a.Action).ToListAsync());
        var (rows, total) = await Users().ListAsync("0900000010", null, 1, default); // the partner's phone, typed with a leading 0
        Assert.Equal(1, total);
        Assert.True(Assert.Single(rows).IsPartner);
    }

    [Fact]
    public async Task HidePost_ClosesPendingQuotes_AndOnlyWorksOnOpenPosts()
    {
        var partner = await _f.AddPartnerAsync();
        var post = await _f.Requests().CreateAsync(_f.Customer.Id, _f.Post(), default);
        var quote = await _f.Quotes().SubmitAsync(partner.Id, post.Id,
            new SubmitQuoteRequest(300_000, 60, _f.Ctx.Clock.GetUtcNow().AddMinutes(30), null, 1), default);

        await _f.Requests().HideAsync(Admin, post.Id, "Nội dung quảng cáo", default);
        var again = await Assert.ThrowsAsync<ApiException>(() => _f.Requests().HideAsync(Admin, post.Id, "Lần nữa", default));

        var db = _f.Ctx.Db;
        var request = await db.ServiceRequests.AsNoTracking().SingleAsync(r => r.Id == post.Id);
        Assert.Equal(((byte)ServiceRequestStatus.RejectedByModeration, "Nội dung quảng cáo"), (request.Status, request.ModerationNote));
        Assert.Equal((byte)QuoteStatus.Expired, (await db.Quotes.AsNoTracking().SingleAsync(q => q.Id == quote.Id)).Status);
        Assert.Equal((byte)ConversationStatus.ReadOnly, (await db.Conversations.AsNoTracking().SingleAsync(c => c.Id == quote.ConversationId)).Status);
        Assert.Equal(ErrorCodes.InvalidStatusTransition, again.Code);
        Assert.True(await db.AuditLogs.AnyAsync(a => a.Action == "SERVICE_REQUEST_HIDDEN"));
    }
}
