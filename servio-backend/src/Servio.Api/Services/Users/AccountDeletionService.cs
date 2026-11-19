using Microsoft.EntityFrameworkCore;
using Servio.Api.Common;
using Servio.Api.Data;
using Servio.Api.Services.Files;
using Servio.Api.Services.Orders;

namespace Servio.Api.Services.Users;

/// <summary>
/// DELETE /users/me (Điều 14 Luật 91/2025, deadline Điều 5 NĐ 356/2025): done at once, for both apps of the user.
/// Refused while a contract is still running or money is still owed either way (khoản 2 Điều 14 with Điều 19), with
/// the reason. Otherwise profile, addresses, devices, sessions, notifications, KYC/avatar files and live location are
/// erased or anonymised. Orders, payments, reviews, chats and disputes stay as records of finished contracts, now under
/// the name "Người dùng đã xoá"; consent rows stay as proof, with a withdrawal row added.
/// </summary>
public sealed class AccountDeletionService(
    ServioDbContext db,
    FileService files,
    ConsentService consents,
    TimeProvider clock,
    ILogger<AccountDeletionService> logger)
{
    public const string DeletedName = "Người dùng đã xoá";

    private static readonly byte[] ActiveOrderStatuses = OrderStateMachine.ActiveOrderStatuses.Select(s => (byte)s).ToArray();
    private static readonly byte[] PersonalFilePurposes = [(byte)FilePurpose.Avatar, (byte)FilePurpose.Kyc, (byte)FilePurpose.Certificate];

    public async Task DeleteAsync(Guid userId, AppFlavor flavor, CancellationToken ct)
    {
        var user = await db.Users
                       .Include(u => u.CustomerProfile)
                       .Include(u => u.PartnerProfile).ThenInclude(p => p!.PartnerDocuments)
                       .Include(u => u.PartnerProfile).ThenInclude(p => p!.PartnerSkills)
                       .Include(u => u.Wallet)
                       .AsSplitQuery()
                       .Where(u => u.Id == userId && u.Status != (byte)UserStatus.Deleted)
                       .OrderBy(u => u.Id) // split queries need a stable order even for one row (EF warning 10102)
                       .FirstOrDefaultAsync(ct)
                   ?? throw ApiException.NotFound("Không tìm thấy tài khoản");
        await EnsureNothingOpenAsync(user.Id, user.CustomerProfile?.Id, user.PartnerProfile?.Id, user.Wallet, ct);

        var now = clock.GetUtcNow();
        var phone = user.PhoneNumber;
        user.FullName = DeletedName;
        user.PhoneNumber = "#" + user.Id.ToString("N")[..14]; // unique placeholder, frees the number for a new sign-up
        user.Email = null;
        user.AvatarUrl = null;
        user.DateOfBirth = null;
        user.Gender = null;
        user.Status = (byte)UserStatus.Deleted;
        user.DeletedAt = now;
        user.UpdatedAt = now;

        foreach (var a in await db.Addresses.Where(a => a.UserId == userId).ToListAsync(ct))
        {
            a.Label = "Đã xoá";
            a.ReceiverName = DeletedName;
            a.ReceiverPhone = "";
            a.FullAddress = "Đã xoá";
            a.Latitude = 0;
            a.Longitude = 0;
            a.Note = null;
            a.IsDefault = false;
            a.DeletedAt ??= now;
            a.UpdatedAt = now;
        }
        if (user.PartnerProfile is { } partner)
        {
            partner.IsOnline = false;
            partner.Bio = null;
            partner.CurrentLatitude = partner.CurrentLongitude = null;
            partner.AnchorLatitude = partner.AnchorLongitude = null;
            partner.UpdatedAt = now;
            foreach (var d in partner.PartnerDocuments)
            {
                d.FileUrl = "";
                d.DocumentNumber = null;
            }
            foreach (var s in partner.PartnerSkills)
            {
                s.CertificateUrl = null;
            }
        }
        foreach (var t in await db.RefreshTokens.Where(t => t.UserId == userId && t.RevokedAt == null).ToListAsync(ct))
        {
            t.RevokedAt = now;
        }
        db.UserDevices.RemoveRange(await db.UserDevices.Where(d => d.UserId == userId).ToListAsync(ct));
        db.Notifications.RemoveRange(await db.Notifications.Where(n => n.UserId == userId).ToListAsync(ct));
        db.OtpCodes.RemoveRange(await db.OtpCodes.Where(o => o.PhoneNumber == phone).ToListAsync(ct));

        // Withdraw every granted purpose, so the history shows when processing stopped.
        foreach (var c in (await consents.ListAsync(userId, ct)).Where(c => c.Granted))
        {
            consents.Add(userId, flavor, c.Purpose, granted: false, c.PolicyVersion);
        }

        var personalFiles = await db.UploadedFiles.Where(f => f.OwnerUserId == userId && PersonalFilePurposes.Contains(f.Purpose)).ToListAsync(ct);
        foreach (var f in personalFiles)
        {
            f.Status = 5; // DELETED: the bytes are removed below
        }
        await db.SaveChangesAsync(ct);

        // After the commit: a file left on disk by an I/O error is logged, the account is already gone.
        foreach (var f in personalFiles)
        {
            try
            {
                File.Delete(files.PhysicalPath(f));
            }
            catch (Exception e) when (e is IOException or UnauthorizedAccessException)
            {
                logger.LogError(e, "Could not delete file {FileId} of deleted user {UserId}", f.Id, userId);
            }
        }
    }

    /// <summary>The reasons a deletion is refused (khoản 2 Điều 14): each tells the user what to finish first.</summary>
    private async Task EnsureNothingOpenAsync(Guid userId, Guid? customerId, Guid? partnerId, Data.Entities.Wallet? wallet, CancellationToken ct)
    {
        if (customerId is { } c)
        {
            if (await db.Orders.AnyAsync(o => o.CustomerId == c && ActiveOrderStatuses.Contains(o.Status), ct))
                throw Blocked("Bạn còn đơn hàng chưa kết thúc. Hãy hoàn tất hoặc huỷ đơn trước khi xoá tài khoản.");
            if (await db.ServiceRequests.AnyAsync(r => r.CustomerId == c
                    && (r.Status == (byte)ServiceRequestStatus.Open || r.Status == (byte)ServiceRequestStatus.Draft), ct))
                throw Blocked("Bạn còn yêu cầu đang mở. Hãy huỷ yêu cầu trước khi xoá tài khoản.");
            if (await db.CancellationCharges.AnyAsync(x => x.CustomerId == c && x.Status == (byte)CancellationChargeStatus.Due, ct))
                throw Blocked("Bạn còn phí huỷ chưa thanh toán. Hãy thanh toán trước khi xoá tài khoản.");
        }
        if (partnerId is { } p)
        {
            if (await db.OrderAssignments.AnyAsync(a => a.PartnerProfileId == p && a.Status != (byte)OrderStatus.Cancelled
                    && ActiveOrderStatuses.Contains(a.Order.Status), ct))
                throw Blocked("Bạn còn đơn đang nhận chưa kết thúc. Hãy hoàn tất hoặc huỷ trước khi xoá tài khoản.");
            if (await db.Quotes.AnyAsync(q => q.PartnerProfileId == p && q.Status == (byte)QuoteStatus.Pending, ct))
                throw Blocked("Bạn còn báo giá đang chờ. Hãy rút báo giá trước khi xoá tài khoản.");
        }
        if (wallet is { DebtBalance: > 0 })
            throw Blocked($"Bạn còn nợ hoa hồng {Money.Vnd(wallet.DebtBalance)}. Hãy thanh toán trước khi xoá tài khoản.");
        if (wallet is { AvailableBalance: > 0 })
            throw Blocked($"Bạn còn {Money.Vnd(wallet.AvailableBalance)} trong số dư. Hãy liên hệ hỗ trợ để nhận lại trước khi xoá tài khoản.");
        if (await db.Transactions.AnyAsync(t => t.UserId == userId && t.Type == (byte)TransactionType.DebtPayment
                && t.Status == (byte)TransactionStatus.Pending, ct))
            throw Blocked("Bạn còn khoản chuyển khoản đang chờ xác nhận. Hãy đợi xác nhận rồi xoá tài khoản.");
    }

    private static ApiException Blocked(string message) =>
        new(StatusCodes.Status409Conflict, ErrorCodes.AccountDeletionBlocked, message);
}
