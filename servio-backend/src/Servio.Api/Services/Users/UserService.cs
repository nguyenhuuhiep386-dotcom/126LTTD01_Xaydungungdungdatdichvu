using System.ComponentModel.DataAnnotations;
using Microsoft.EntityFrameworkCore;
using Servio.Api.Common;
using Servio.Api.Data;

namespace Servio.Api.Services.Users;

// #9 GET /users/me
public sealed record MeDto(
    Guid Id,
    string PhoneNumber,
    string FullName,
    string? AvatarUrl,
    string? Email,
    DateOnly? DateOfBirth,
    Gender? Gender,
    IReadOnlyList<UserRoleType> Roles,
    CustomerProfileDto? CustomerProfile,
    PartnerProfileSummaryDto? PartnerProfile);

public sealed record CustomerProfileDto(Guid Id, decimal? AverageRating, int TotalReviews, int TotalOrders);

public sealed record PartnerProfileSummaryDto(
    Guid Id,
    PartnerVerificationStatus VerificationStatus,
    string? VerificationNote,
    bool IsOnline,
    int ServiceRadiusKm,
    decimal? AverageRating,
    int TotalReviews);

// #10 PATCH /users/me — only non-null fields are changed
public sealed record UpdateMeRequest(
    [StringLength(100, MinimumLength = 2, ErrorMessage = "Họ tên dài 2–100 ký tự")] string? FullName,
    [StringLength(500)] string? AvatarUrl,
    DateOnly? DateOfBirth,
    Gender? Gender,
    [EmailAddress(ErrorMessage = "Email không hợp lệ"), StringLength(256)] string? Email);

public sealed class UserService(ServioDbContext db, TimeProvider clock)
{
    public const int MinFullNameLength = 2;

    public async Task<MeDto> GetMeAsync(Guid userId, CancellationToken ct)
    {
        var user = await db.Users
            .AsNoTracking()
            .Include(u => u.UserRoles)
            .Include(u => u.CustomerProfile)
            .Include(u => u.PartnerProfile)
            .FirstOrDefaultAsync(u => u.Id == userId, ct)
            ?? throw ApiException.NotFound("Không tìm thấy tài khoản");

        var customer = user.CustomerProfile;
        var partner = user.PartnerProfile;
        return new MeDto(
            user.Id,
            user.PhoneNumber,
            user.FullName,
            user.AvatarUrl,
            user.Email,
            user.DateOfBirth,
            (Gender?)user.Gender,
            user.UserRoles.Select(r => (UserRoleType)r.Role).OrderBy(r => r).ToList(),
            customer is null ? null : new CustomerProfileDto(customer.Id, customer.AverageRating, customer.TotalReviews, customer.TotalOrders),
            partner is null ? null : new PartnerProfileSummaryDto(
                partner.Id,
                (PartnerVerificationStatus)partner.VerificationStatus,
                partner.VerificationNote,
                partner.IsOnline,
                partner.ServiceRadiusKm,
                partner.AverageRating,
                partner.TotalReviews));
    }

    public async Task<MeDto> UpdateMeAsync(Guid userId, UpdateMeRequest request, CancellationToken ct)
    {
        var user = await db.Users.FirstOrDefaultAsync(u => u.Id == userId, ct)
                   ?? throw ApiException.NotFound("Không tìm thấy tài khoản");

        var fullName = request.FullName?.Trim();
        if (fullName is not null && fullName.Length < MinFullNameLength)
        {
            throw new ApiException(StatusCodes.Status400BadRequest, ErrorCodes.ValidationError, "Họ tên dài 2–100 ký tự", "fullName");
        }
        if (request.Gender is { } gender && !Enum.IsDefined(gender))
        {
            throw new ApiException(StatusCodes.Status400BadRequest, ErrorCodes.ValidationError, "Giá trị không hợp lệ", "gender");
        }

        // Avatar must be a file served by this API (POST /files returns /uploads/...). Never trust arbitrary URLs.
        if (request.AvatarUrl is not null && !request.AvatarUrl.StartsWith("/uploads/", StringComparison.Ordinal))
        {
            throw new ApiException(StatusCodes.Status400BadRequest, ErrorCodes.ValidationError, "Ảnh đại diện không hợp lệ", "avatarUrl");
        }

        if (request.Email is not null &&
            await db.Users.AnyAsync(u => u.Email == request.Email && u.Id != userId, ct))
        {
            throw new ApiException(StatusCodes.Status409Conflict, ErrorCodes.Conflict, "Email đã được sử dụng", "email");
        }

        user.FullName = fullName ?? user.FullName;
        user.AvatarUrl = request.AvatarUrl ?? user.AvatarUrl;
        user.DateOfBirth = request.DateOfBirth ?? user.DateOfBirth;
        user.Gender = request.Gender is null ? user.Gender : (byte)request.Gender;
        user.Email = request.Email ?? user.Email;
        user.UpdatedAt = clock.GetUtcNow();
        await db.SaveChangesAsync(ct);

        return await GetMeAsync(userId, ct);
    }

    public static bool NeedsProfileCompletion(string fullName) => fullName.Trim().Length < MinFullNameLength;
}
