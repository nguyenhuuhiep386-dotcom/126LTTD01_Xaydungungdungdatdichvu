using Microsoft.EntityFrameworkCore;
using Servio.Api.Common;
using Servio.Api.Data;

namespace Servio.Api.Services.Admin;

public sealed record AdminUserRow(
    Guid Id,
    string FullName,
    string PhoneNumber,
    bool IsCustomer,
    bool IsPartner,
    UserStatus Status,
    PartnerVerificationStatus? PartnerStatus,
    decimal? Rating,
    decimal DebtBalance,
    int Orders,
    DateTimeOffset CreatedAt);

/// <summary>AW-03 — users: search, lock and unlock (F-ADM-02). Every change is audited.</summary>
public sealed class AdminUserService(ServioDbContext db, TimeProvider clock)
{
    public const int PageSize = 30;

    /// <summary>Newest first. q matches the name or the phone number (digits only are enough: "0901" finds +84901…).</summary>
    public async Task<(IReadOnlyList<AdminUserRow> Rows, int Total)> ListAsync(string? q, UserRoleType? role, int page, CancellationToken ct)
    {
        var query = db.Users.AsNoTracking().Where(u => u.Status != (byte)UserStatus.Deleted);
        if (!string.IsNullOrWhiteSpace(q))
        {
            var text = q.Trim();
            var digits = new string(text.Where(char.IsDigit).ToArray()).TrimStart('0');
            query = query.Where(u => u.FullName.Contains(text) || (digits.Length > 0 && u.PhoneNumber.Contains(digits)));
        }
        if (role == UserRoleType.Customer) query = query.Where(u => u.CustomerProfile != null);
        if (role == UserRoleType.Partner) query = query.Where(u => u.PartnerProfile != null);

        var total = await query.CountAsync(ct);
        var rows = await query.OrderByDescending(u => u.CreatedAt)
            .Skip((Math.Max(page, 1) - 1) * PageSize).Take(PageSize)
            .Select(u => new AdminUserRow(
                u.Id, u.FullName, u.PhoneNumber, u.CustomerProfile != null, u.PartnerProfile != null, (UserStatus)u.Status,
                u.PartnerProfile != null ? (PartnerVerificationStatus)u.PartnerProfile.VerificationStatus : null,
                u.PartnerProfile != null ? u.PartnerProfile.AverageRating : u.CustomerProfile!.AverageRating,
                u.Wallet != null ? u.Wallet.DebtBalance : 0,
                (u.CustomerProfile != null ? u.CustomerProfile.Orders.Count : 0)
                + (u.PartnerProfile != null ? u.PartnerProfile.OrderAssignments.Count : 0),
                u.CreatedAt))
            .ToListAsync(ct);
        return (rows, total);
    }

    /// <summary>
    /// Lock: SUSPENDED with no end date, every refresh token revoked (the apps are signed out within the 15-minute access
    /// token life, #5 refuses with ACCOUNT_LOCKED) and a partner is taken offline so they leave the feed.
    /// </summary>
    public async Task LockAsync(AdminActor actor, Guid userId, string reason, CancellationToken ct)
    {
        reason = reason.Trim();
        if (reason.Length < 3)
        {
            throw new ApiException(StatusCodes.Status400BadRequest, ErrorCodes.ValidationError, "Hãy ghi lý do khoá", "reason");
        }
        var user = await db.Users.Include(u => u.PartnerProfile).Include(u => u.RefreshTokens.Where(t => t.RevokedAt == null))
                       .FirstOrDefaultAsync(u => u.Id == userId, ct)
                   ?? throw ApiException.NotFound("Không tìm thấy người dùng");
        if (user.Status != (byte)UserStatus.Active)
        {
            throw new ApiException(StatusCodes.Status409Conflict, ErrorCodes.InvalidStatusTransition, "Tài khoản không ở trạng thái hoạt động");
        }
        var now = clock.GetUtcNow();
        user.Status = (byte)UserStatus.Suspended;
        user.SuspendedUntil = null;
        user.UpdatedAt = now;
        foreach (var token in user.RefreshTokens)
        {
            token.RevokedAt = now;
        }
        if (user.PartnerProfile is { IsOnline: true } partner)
        {
            partner.IsOnline = false;
            partner.UpdatedAt = now;
        }
        db.AddAudit(actor, "USER_LOCKED", "User", user.Id, new { Status = nameof(UserStatus.Active) }, new { Status = nameof(UserStatus.Suspended), Reason = reason }, now);
        await db.SaveChangesAsync(ct);
    }

    public async Task UnlockAsync(AdminActor actor, Guid userId, CancellationToken ct)
    {
        var user = await db.Users.FirstOrDefaultAsync(u => u.Id == userId, ct) ?? throw ApiException.NotFound("Không tìm thấy người dùng");
        if (user.Status != (byte)UserStatus.Suspended)
        {
            throw new ApiException(StatusCodes.Status409Conflict, ErrorCodes.InvalidStatusTransition, "Tài khoản không bị khoá");
        }
        var now = clock.GetUtcNow();
        user.Status = (byte)UserStatus.Active;
        user.SuspendedUntil = null;
        user.UpdatedAt = now;
        db.AddAudit(actor, "USER_UNLOCKED", "User", user.Id, new { Status = nameof(UserStatus.Suspended) }, new { Status = nameof(UserStatus.Active) }, now);
        await db.SaveChangesAsync(ct);
    }
}
