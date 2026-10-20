using Servio.Api.Common;
using Servio.Api.Data.Entities;
using Servio.Api.Hubs;
using Servio.Api.Services;
using Servio.Api.Services.Feed;
using Servio.Api.Services.Quotes;
using Servio.Api.Services.Requests;

namespace Servio.Tests.Services;

/// <summary>Records realtime events instead of sending them through SignalR.</summary>
public sealed class RecordingNotifier : IRealtimeNotifier
{
    public List<(Guid PartnerUserId, FeedItemDto Item)> NewPosts { get; } = [];
    public List<(Guid CustomerUserId, CustomerQuoteDto Quote)> NewQuotes { get; } = [];
    public List<(Guid CustomerUserId, Guid QuoteId)> Withdrawn { get; } = [];

    public Task NewPostAsync(IReadOnlyList<(Guid PartnerUserId, FeedItemDto Item)> recipients, CancellationToken ct)
    {
        NewPosts.AddRange(recipients);
        return Task.CompletedTask;
    }

    public Task NewQuoteAsync(Guid customerUserId, CustomerQuoteDto quote, CancellationToken ct)
    {
        NewQuotes.Add((customerUserId, quote));
        return Task.CompletedTask;
    }

    public Task QuoteWithdrawnAsync(Guid customerUserId, Guid serviceRequestId, Guid quoteId, CancellationToken ct)
    {
        Withdrawn.Add((customerUserId, quoteId));
        return Task.CompletedTask;
    }
}

/// <summary>
/// Customer with an address in Thủ Đức, a 2-level category, and helpers to add partners around it.
/// Distances: 0.01° latitude ≈ 1.1 km.
/// </summary>
public sealed class MarketplaceFixture : IDisposable
{
    public const decimal HomeLat = 10.850000m;
    public const decimal HomeLng = 106.770000m;

    public TestContext Ctx { get; } = new();
    public RecordingNotifier Notifier { get; } = new();
    public User Customer { get; private set; } = null!;
    public Address Home { get; private set; } = null!;
    public Guid ServiceId { get; private set; }
    public Guid OtherServiceId { get; private set; }
    private int _phone = 10;

    public static async Task<MarketplaceFixture> CreateAsync()
    {
        var f = new MarketplaceFixture();
        var now = f.Ctx.Clock.GetUtcNow();
        var group = new ServiceCategory { Id = Guid.NewGuid(), Name = "Điện lạnh", Slug = "dien-lanh", IsActive = true, CreatedAt = now, UpdatedAt = now };
        var service = new ServiceCategory { Id = Guid.NewGuid(), Parent = group, Name = "Vệ sinh máy lạnh", Slug = "ve-sinh-may-lanh", IsActive = true, CreatedAt = now, UpdatedAt = now };
        var other = new ServiceCategory { Id = Guid.NewGuid(), Parent = group, Name = "Sửa tủ lạnh", Slug = "sua-tu-lanh", IsActive = true, CreatedAt = now, UpdatedAt = now };
        f.Ctx.Db.ServiceCategories.AddRange(group, service, other);
        f.ServiceId = service.Id;
        f.OtherServiceId = other.Id;

        f.Customer = await f.Ctx.AddUserAsync(UserRoleType.Customer, "Nguyễn Văn Khách", "+84900000001");
        f.Home = new Address
        {
            Id = Guid.NewGuid(), UserId = f.Customer.Id, Label = "Nhà", ReceiverName = "Khách", ReceiverPhone = "+84900000001",
            FullAddress = "12 Võ Văn Ngân, Linh Chiểu, Thủ Đức", Latitude = HomeLat, Longitude = HomeLng, IsDefault = true,
            CreatedAt = now, UpdatedAt = now,
        };
        f.Ctx.Db.Addresses.Add(f.Home);
        await f.Ctx.Db.SaveChangesAsync();
        return f;
    }

    /// <summary>Approved partner with an approved skill, online at the given offset (degrees of latitude) from the customer.</summary>
    public async Task<User> AddPartnerAsync(decimal latOffset = 0.01m, bool online = true, Guid? skillCategoryId = null,
        int years = 3, int radiusKm = 10, PartnerVerificationStatus status = PartnerVerificationStatus.Approved)
    {
        var user = await Ctx.AddUserAsync(UserRoleType.Partner, "Trần Thợ", $"+849000000{_phone++}");
        var p = user.PartnerProfile!;
        var now = Ctx.Clock.GetUtcNow();
        p.VerificationStatus = (byte)status;
        p.ServiceRadiusKm = radiusKm;
        p.IsOnline = online;
        p.LastHeartbeatAt = online ? now : null;
        p.CurrentLatitude = HomeLat + latOffset;
        p.CurrentLongitude = HomeLng;
        p.PartnerSkills.Add(new PartnerSkill
        {
            Id = Guid.NewGuid(), ServiceCategoryId = skillCategoryId ?? ServiceId, YearsOfExperience = years,
            Status = (byte)ApprovalStatus.Approved, CreatedAt = now,
        });
        await Ctx.Db.SaveChangesAsync();
        return user;
    }

    public SystemConfigService Configs() => Ctx.Configs();

    public FeedService Feed() => new(Ctx.Db, Configs(), Ctx.Clock);

    public ServiceRequestService Requests() =>
        new(Ctx.Db, Ctx.Files(), Feed(), new CodeGenerator(Ctx.Db, Ctx.Clock), Configs(), Notifier, Ctx.Clock);

    public QuoteService Quotes() => new(Ctx.Db, Feed(), Configs(), Notifier, Ctx.Clock);

    public CreateServiceRequestRequest Post(string title = "Máy lạnh phòng ngủ chảy nước",
        ScheduleType schedule = ScheduleType.Now, DateTimeOffset? start = null, int? requireYears = null, decimal? minRating = null) =>
        new(ServiceId, title, "Máy lạnh Daikin 1.5HP dùng 3 năm, gần đây chảy nước và kêu to.", Home.Id, null,
            schedule, start, 200_000, 400_000, requireYears, false, minRating, null);

    public void Dispose() => Ctx.Dispose();
}
