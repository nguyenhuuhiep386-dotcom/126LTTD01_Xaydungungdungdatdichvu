using Microsoft.EntityFrameworkCore;
using Servio.Api.Common;
using Servio.Api.Data;
using Servio.Api.Data.Entities;
using Servio.Api.Services.Files;
using Servio.Api.Services.Orders;
using Servio.Api.Services.Quotes;
using Servio.Api.Services.Requests;
using Servio.Api.Services.Reviews;
using Servio.Api.Services.Wallets;

namespace Servio.Api.Services.Admin;

/// <summary>
/// Demo data for the final presentation (README giai đoạn 5): 3 customers, 3 approved partners and orders in every state
/// the admin pages and apps show. Run once on a fresh database: <c>dotnet run -- --seed-demo</c>.
/// Orders are created through the real services (post → quote → choose → move → check-in → work → confirm), so money,
/// debts, timelines, chats and notifications are exactly what the apps would have produced.
/// All addresses are around HCMUTE (1 Võ Văn Ngân) so the GPS check-in also works in the classroom.
/// </summary>
public sealed class DemoSeeder(
    ServioDbContext db,
    FileService files,
    ServiceRequestService requests,
    QuoteService quotes,
    OrderService orders,
    AssignmentService assignments,
    TrackingService tracking,
    ExtraChargeService extraCharges,
    CompletionService completion,
    CancellationService cancellation,
    ReviewService reviews,
    DisputeService disputes,
    WalletService wallets,
    TimeProvider clock,
    ILogger<DemoSeeder> logger)
{
    public const string MarkerPhone = "+84901000001";

    // HCMUTE gate: the classroom demo address.
    private const decimal UteLat = 10.850632m;
    private const decimal UteLng = 106.771888m;

    // 1×1 PNG: passes the magic-bytes check; real photos are taken live during the demo.
    private static readonly byte[] Png = Convert.FromBase64String(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    private sealed record Person(User User, Address? Home);

    public async Task<bool> RunAsync(CancellationToken ct = default)
    {
        if (await db.Users.AnyAsync(u => u.PhoneNumber == MarkerPhone, ct))
        {
            logger.LogWarning("Demo data already exists ({Phone}), nothing to do", MarkerPhone);
            return false;
        }
        var categories = await db.ServiceCategories.Where(c => c.ParentId != null).ToDictionaryAsync(c => c.Slug, c => c.Id, ct);
        Guid Cat(string slug) => categories.TryGetValue(slug, out var id)
            ? id : throw new InvalidOperationException($"Category {slug} missing: run 02_seed.sql first.");

        // ---------- People ----------
        var lan = await CustomerAsync("+84901000001", "Nguyễn Thị Lan", "Phòng A5-201, Trường ĐH Sư phạm Kỹ thuật TP.HCM, 1 Võ Văn Ngân, Linh Chiểu, Thủ Đức", UteLat, UteLng, ct);
        var minh = await CustomerAsync("+84901000002", "Trần Văn Minh", "45 Đặng Văn Bi, Bình Thọ, Thủ Đức", 10.845500m, 106.764000m, ct);
        var ha = await CustomerAsync("+84901000003", "Lê Thu Hà", "120 Lê Văn Việt, Hiệp Phú, Thủ Đức", 10.846000m, 106.779000m, ct);

        var hung = await PartnerAsync("+84902000001", "Phạm Văn Hùng", "8 năm sửa máy lạnh, tủ lạnh dân dụng.", 8, 10.856000m, 106.770000m,
            [(Cat("ve-sinh-may-lanh"), 8), (Cat("sua-may-lanh"), 8), (Cat("sua-tu-lanh"), 5)], ct);
        var tam = await PartnerAsync("+84902000002", "Võ Thanh Tâm", "Thợ điện nước, nhận sửa chữa nhỏ trong ngày.", 5, 10.848000m, 106.760000m,
            [(Cat("sua-dien-dan-dung"), 5), (Cat("sua-ong-nuoc"), 5), (Cat("lap-thiet-bi-ve-sinh"), 3)], ct);
        var bao = await PartnerAsync("+84902000003", "Đỗ Quốc Bảo", "Kỹ thuật viên máy tính, mạng và camera.", 4, 10.840000m, 106.775000m,
            [(Cat("sua-laptop-pc"), 4), (Cat("mang-wifi"), 4), (Cat("lap-camera"), 3), (Cat("ve-sinh-may-lanh"), 2)], ct);

        // ---------- A: completed, both reviews (Lan ↔ Hùng) ----------
        var a = await OrderAsync(lan, hung, Cat("ve-sinh-may-lanh"), "Vệ sinh 2 máy lạnh phòng ở",
            "Hai máy lạnh treo tường Panasonic 1HP, lâu chưa vệ sinh, chạy yếu và có mùi.", 300_000, ct);
        await WorkAsync(a, hung, extra: null, ct);
        await ConfirmAsync(a, lan, ct);
        await reviews.CreateAsync(lan.User.Id, AppFlavor.Customer, a.OrderId,
            new CreateReviewRequest(a.AssignmentId, 5, 5, 5, 5, 4, "Thợ đến đúng giờ, làm sạch sẽ, giải thích rõ ràng.", null), ct);
        await reviews.CreateAsync(hung.User.Id, AppFlavor.Partner, a.OrderId,
            new CreateReviewRequest(a.AssignmentId, 5, null, null, null, null, null, ["PAID_ON_TIME", "FRIENDLY"]), ct);

        // ---------- B: completed with an extra charge, example 2.6.1 (Minh ↔ Hùng): 450.000 + 180.000 ----------
        var b = await OrderAsync(minh, hung, Cat("sua-may-lanh"), "Máy lạnh chảy nước và kêu to",
            "Máy lạnh Daikin 1.5HP dùng 3 năm, gần đây chảy nước ở dàn lạnh và kêu to khi chạy.", 450_000, ct);
        await WorkAsync(b, hung, extra: ("Thay tụ máy nén 35uF", 180_000), ct);
        await ConfirmAsync(b, minh, ct);
        await reviews.CreateAsync(minh.User.Id, AppFlavor.Customer, b.OrderId,
            new CreateReviewRequest(b.AssignmentId, 4, 4, 5, 4, 4, "Sửa ổn, báo giá phát sinh rõ ràng trước khi làm.", null), ct);
        // Hùng declares paying his commission debt → AW-18 has one transfer to confirm (demo step 10).
        await wallets.DeclarePaymentAsync(hung.User.Id, AppFlavor.Partner,
            new DeclareDebtPaymentRequest(null, "FT26111400123", await UploadAsync(hung.User.Id, FilePurpose.DisputeEvidence, ct)), ct);

        // ---------- C: completed, then the customer opens a dispute (Minh ↔ Tâm) → AW-08 (demo step 10) ----------
        var c = await OrderAsync(minh, tam, Cat("sua-ong-nuoc"), "Ống nước dưới bồn rửa bị rỉ",
            "Ống thoát nước dưới bồn rửa chén bị rỉ, sàn bếp lúc nào cũng ướt.", 250_000, ct);
        await WorkAsync(c, tam, extra: null, ct);
        await ConfirmAsync(c, minh, ct);
        await disputes.CreateAsync(minh.User.Id, AppFlavor.Customer, c.OrderId,
            new CreateDisputeRequest(DisputeReason.WorkQuality, "Sau một ngày ống vẫn rỉ nước ở chỗ nối, đã nhắn thợ nhưng chưa thấy quay lại.",
                [await UploadAsync(minh.User.Id, FilePurpose.DisputeEvidence, ct)]), ct);

        // ---------- D: the customer cancels while the partner is on the way → 20.000 fee DUE (Hà ↔ Tâm) ----------
        var d = await OrderAsync(ha, tam, Cat("sua-dien-dan-dung"), "Ổ cắm phòng khách bị cháy",
            "Một ổ cắm âm tường bị cháy đen, cần thay ổ mới và kiểm tra dây.", 200_000, ct);
        await tracking.StartMovingAsync(tam.User.Id, d.OrderId, d.AssignmentId, new StartMovingRequest(tam.User.PartnerProfile!.CurrentLatitude, tam.User.PartnerProfile.CurrentLongitude, 15), ct);
        var preview = await cancellation.PreviewAsync(ha.User.Id, d.OrderId, ct);
        await cancellation.CancelAsync(ha.User.Id, d.OrderId, new CancelOrderRequest("Người nhà đã tự sửa được", preview.RowVersion), ct);

        // ---------- E: work done, waiting for the customer to confirm (Hà ↔ Bảo) → CS-23 ----------
        var e = await OrderAsync(ha, bao, Cat("sua-laptop-pc"), "Laptop khởi động rất chậm",
            "Laptop Dell Inspiron 15 mở máy mất 5 phút, cần kiểm tra ổ cứng và cài lại hệ điều hành nếu cần.", 350_000, ct);
        await WorkAsync(e, bao, extra: null, ct);

        // ---------- F: accepted, partner comes later (Lan ↔ Bảo) ----------
        await OrderAsync(lan, bao, Cat("mang-wifi"), "Wifi phòng trọ chập chờn",
            "Wifi rớt liên tục ở phòng trong, muốn lắp thêm bộ phát mở rộng sóng.", 200_000, ct, availableInMinutes: 90);

        // ---------- Open posts for the partner feed (scheduled tomorrow 09:00) ----------
        var tomorrow9 = TomorrowAt9();
        var fridge = await requests.CreateAsync(lan.User.Id, Post(Cat("sua-tu-lanh"), "Tủ lạnh không đông đá",
            "Tủ lạnh Toshiba 2 cánh, ngăn đá không lạnh từ hôm qua, ngăn mát vẫn bình thường.", lan.Home!.Id, tomorrow9,
            [await UploadAsync(lan.User.Id, FilePurpose.PostImage, ct)]), ct);
        await OnlineAsync(hung, ct);
        await quotes.SubmitAsync(hung.User.Id, fridge.Id, new SubmitQuoteRequest(400_000, 90, tomorrow9, "Có thể do hết gas hoặc hỏng quạt dàn lạnh.", 1), ct);
        await requests.CreateAsync(minh.User.Id, Post(Cat("lap-camera"), "Lắp 2 camera trước nhà",
            "Cần lắp 2 camera wifi trước cổng và sân, đã mua sẵn thiết bị.", minh.Home!.Id, tomorrow9, null), ct);

        // Partners go online themselves during the demo.
        await db.PartnerProfiles.Where(p => p.User.PhoneNumber.StartsWith("+8490200000"))
            .ForEachAsync(p => p.IsOnline = false, ct);
        await db.SaveChangesAsync(ct);
        logger.LogInformation("Demo data created: 3 customers, 3 partners, 6 orders, 2 open posts");
        return true;
    }

    private async Task<Person> CustomerAsync(string phone, string name, string address, decimal lat, decimal lng, CancellationToken ct)
    {
        var user = NewUser(phone, name, UserRoleType.Customer);
        user.CustomerProfile = new CustomerProfile { Id = Guid.CreateVersion7(), CreatedAt = user.CreatedAt, UpdatedAt = user.CreatedAt };
        var home = new Address
        {
            Id = Guid.CreateVersion7(), UserId = user.Id, Label = "Nhà", ReceiverName = name, ReceiverPhone = phone,
            FullAddress = address, Latitude = lat, Longitude = lng, IsDefault = true, CreatedAt = user.CreatedAt, UpdatedAt = user.CreatedAt,
        };
        db.Users.Add(user);
        db.Addresses.Add(home);
        await db.SaveChangesAsync(ct);
        return new Person(user, home);
    }

    private async Task<Person> PartnerAsync(string phone, string name, string bio, int years, decimal lat, decimal lng,
        (Guid Category, int Years)[] skills, CancellationToken ct)
    {
        var user = NewUser(phone, name, UserRoleType.Partner);
        var now = user.CreatedAt;
        user.PartnerProfile = new PartnerProfile
        {
            Id = Guid.CreateVersion7(), Bio = bio, YearsOfExperience = years, VerificationStatus = (byte)PartnerVerificationStatus.Approved,
            VerifiedAt = now, CurrentLatitude = lat, CurrentLongitude = lng, AnchorLatitude = lat, AnchorLongitude = lng,
            ServiceRadiusKm = 10, CreatedAt = now, UpdatedAt = now,
        };
        foreach (var (category, skillYears) in skills)
        {
            user.PartnerProfile.PartnerSkills.Add(new PartnerSkill
            {
                Id = Guid.CreateVersion7(), ServiceCategoryId = category, YearsOfExperience = skillYears,
                Status = (byte)ApprovalStatus.Approved, CreatedAt = now,
            });
        }
        db.Users.Add(user);
        await db.SaveChangesAsync(ct);
        return new Person(user, null);
    }

    private User NewUser(string phone, string name, UserRoleType role)
    {
        var now = clock.GetUtcNow();
        var user = new User
        {
            Id = Guid.CreateVersion7(), PhoneNumber = phone, FullName = name, IsPhoneVerified = true,
            Status = (byte)UserStatus.Active, CreatedAt = now, UpdatedAt = now,
        };
        user.UserRoles.Add(new UserRole { Id = Guid.CreateVersion7(), Role = (byte)role, CreatedAt = now });
        user.Wallet = new Data.Entities.Wallet { Id = Guid.CreateVersion7(), Currency = "VND", UpdatedAt = now };
        return user;
    }

    /// <summary>NOW post → quote → customer chooses → partner accepts. Returns the order at ACCEPTED.</summary>
    private async Task<(Guid OrderId, Guid AssignmentId)> OrderAsync(Person customer, Person partner, Guid category, string title,
        string description, long amount, CancellationToken ct, int availableInMinutes = 30)
    {
        var post = await requests.CreateAsync(customer.User.Id,
            new CreateServiceRequestRequest(category, title, description, customer.Home!.Id,
                [new RequestImageInput(await UploadAsync(customer.User.Id, FilePurpose.PostImage, ct))],
                ScheduleType.Now, null, null, null, null, false, null, null), ct);
        await OnlineAsync(partner, ct);
        var quote = await quotes.SubmitAsync(partner.User.Id, post.Id,
            new SubmitQuoteRequest(amount, 60, clock.GetUtcNow().AddMinutes(availableInMinutes), null, 1), ct);
        var version = await db.Quotes.AsNoTracking().Where(q => q.Id == quote.Id).Select(q => q.RowVersion).SingleAsync(ct);
        var order = await orders.AcceptQuoteAsync(customer.User.Id, post.Id,
            new AcceptQuotesRequest([quote.Id], new() { [quote.Id] = Convert.ToBase64String(version) }), ct);
        var assignmentId = order.Assignments.Single().Id;
        await assignments.AcceptAsync(partner.User.Id, order.Id, assignmentId, ct);
        return (order.Id, assignmentId);
    }

    /// <summary>ACCEPTED → move → GPS check-in at the address → before photo → optional extra (approved) → after photo.</summary>
    private async Task WorkAsync((Guid OrderId, Guid AssignmentId) o, Person partner, (string Description, long Amount)? extra, CancellationToken ct)
    {
        var p = partner.User.PartnerProfile!;
        await tracking.StartMovingAsync(partner.User.Id, o.OrderId, o.AssignmentId, new StartMovingRequest(p.CurrentLatitude, p.CurrentLongitude, 15), ct);
        var address = await db.Orders.Where(x => x.Id == o.OrderId).Select(x => new { x.ServiceRequest.Latitude, x.ServiceRequest.Longitude }).SingleAsync(ct);
        await tracking.ArriveAsync(partner.User.Id, o.OrderId, o.AssignmentId,
            new ArriveRequest(address.Latitude + 0.0003m, address.Longitude, 12, clock.GetUtcNow(), false, null), ct);
        await assignments.StartWorkAsync(partner.User.Id, o.OrderId, o.AssignmentId,
            new StartWorkRequest([await UploadAsync(partner.User.Id, FilePurpose.OrderImage, ct)]), ct);
        if (extra is { } x)
        {
            var charge = await extraCharges.CreateAsync(partner.User.Id, o.OrderId, new CreateExtraChargeRequest(o.AssignmentId, x.Description, x.Amount, null), ct);
            var customerId = await db.Orders.Where(y => y.Id == o.OrderId).Select(y => y.Customer.UserId).SingleAsync(ct);
            await extraCharges.RespondAsync(customerId, o.OrderId, charge.Id, new RespondExtraChargeRequest(true, null), ct);
        }
        await assignments.CompleteAsync(partner.User.Id, o.OrderId, o.AssignmentId,
            new CompleteWorkRequest([await UploadAsync(partner.User.Id, FilePurpose.OrderImage, ct)], "Đã làm xong, mời anh/chị kiểm tra."), ct);
    }

    private async Task ConfirmAsync((Guid OrderId, Guid AssignmentId) o, Person customer, CancellationToken ct)
    {
        var version = await db.Orders.AsNoTracking().Where(x => x.Id == o.OrderId).Select(x => x.RowVersion).SingleAsync(ct);
        await completion.ConfirmAsync(customer.User.Id, o.OrderId, new ConfirmCompletionRequest(Convert.ToBase64String(version)), ct);
    }

    /// <summary>Quoting needs a heartbeat within the online window (0.2.7 ý 2).</summary>
    private async Task OnlineAsync(Person partner, CancellationToken ct)
    {
        var p = await db.PartnerProfiles.SingleAsync(x => x.UserId == partner.User.Id, ct);
        p.IsOnline = true;
        p.LastHeartbeatAt = clock.GetUtcNow();
        await db.SaveChangesAsync(ct);
    }

    private async Task<string> UploadAsync(Guid userId, FilePurpose purpose, CancellationToken ct)
    {
        var file = new FormFile(new MemoryStream(Png), 0, Png.Length, "file", "anh.png");
        return (await files.UploadAsync(userId, file, purpose, ct)).Url;
    }

    private static CreateServiceRequestRequest Post(Guid category, string title, string description, Guid addressId, DateTimeOffset start,
        IReadOnlyList<string>? images) =>
        new(category, title, description, addressId, images?.Select(u => new RequestImageInput(u)).ToList(),
            ScheduleType.Scheduled, start, null, null, null, false, null, null);

    private DateTimeOffset TomorrowAt9()
    {
        var vn = TimeSpan.FromHours(7);
        var tomorrow = clock.GetUtcNow().ToOffset(vn).Date.AddDays(1).AddHours(9);
        return new DateTimeOffset(tomorrow, vn);
    }
}
