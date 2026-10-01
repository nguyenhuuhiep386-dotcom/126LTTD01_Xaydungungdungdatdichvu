using System.ComponentModel.DataAnnotations;
using Microsoft.EntityFrameworkCore;
using Servio.Api.Common;
using Servio.Api.Data;
using Servio.Api.Data.Entities;

namespace Servio.Api.Services.Users;

public sealed record AddressDto(
    Guid Id,
    string Label,
    string ReceiverName,
    string ReceiverPhone,
    string FullAddress,
    decimal Latitude,
    decimal Longitude,
    string? Note,
    bool IsDefault);

/// <summary>#13 / #14 body. Course scope: no province/ward codes, the location comes from the map pin (CS-12).</summary>
public sealed record SaveAddressRequest(
    [Required(ErrorMessage = "Nhập nhãn địa chỉ"), StringLength(50)] string Label,
    [Required(ErrorMessage = "Nhập tên người nhận"), StringLength(100)] string ReceiverName,
    [Required(ErrorMessage = "Nhập số điện thoại")] string ReceiverPhone,
    [Required(ErrorMessage = "Nhập địa chỉ"), StringLength(500)] string FullAddress,
    decimal? Latitude,
    decimal? Longitude,
    [StringLength(300)] string? Note,
    bool IsDefault);

/// <summary>
/// M1 address book (#12–#15, F-PROF-02). Max 10 live addresses; exactly one default while any exist.
/// Deleting is a soft delete because service requests keep a reference to the address.
/// </summary>
public sealed class AddressService(ServioDbContext db, TimeProvider clock)
{
    public const int MaxAddresses = 10;

    public async Task<IReadOnlyList<AddressDto>> ListAsync(Guid userId, CancellationToken ct) =>
        await db.Addresses.AsNoTracking()
            .Where(a => a.UserId == userId && a.DeletedAt == null)
            .OrderByDescending(a => a.IsDefault).ThenByDescending(a => a.CreatedAt)
            .Select(a => new AddressDto(a.Id, a.Label, a.ReceiverName, a.ReceiverPhone, a.FullAddress, a.Latitude, a.Longitude, a.Note, a.IsDefault))
            .ToListAsync(ct);

    public async Task<AddressDto> CreateAsync(Guid userId, SaveAddressRequest request, CancellationToken ct)
    {
        var (phone, latitude, longitude) = Validate(request);
        var liveCount = await db.Addresses.CountAsync(a => a.UserId == userId && a.DeletedAt == null, ct);
        if (liveCount >= MaxAddresses)
        {
            throw new ApiException(StatusCodes.Status422UnprocessableEntity, ErrorCodes.AddressLimitReached,
                $"Sổ địa chỉ tối đa {MaxAddresses} địa chỉ");
        }

        var now = clock.GetUtcNow();
        var address = new Address { Id = Guid.CreateVersion7(), UserId = userId, CreatedAt = now };
        Apply(address, request, phone, latitude, longitude, now);
        address.IsDefault = false;
        db.Addresses.Add(address);

        // The first address is always the default.
        await SaveWithDefaultAsync(userId, address, makeDefault: request.IsDefault || liveCount == 0, ct);
        return ToDto(address);
    }

    public async Task<AddressDto> UpdateAsync(Guid userId, Guid id, SaveAddressRequest request, CancellationToken ct)
    {
        var (phone, latitude, longitude) = Validate(request);
        var address = await FindOwnedAsync(userId, id, ct);
        var wasDefault = address.IsDefault;
        Apply(address, request, phone, latitude, longitude, clock.GetUtcNow());

        // Un-setting the only default is ignored: the user picks another default instead.
        await SaveWithDefaultAsync(userId, address, makeDefault: request.IsDefault || wasDefault, ct);
        return ToDto(address);
    }

    public async Task DeleteAsync(Guid userId, Guid id, CancellationToken ct)
    {
        var address = await FindOwnedAsync(userId, id, ct);
        var now = clock.GetUtcNow();
        await using var tx = await db.Database.BeginTransactionAsync(ct);
        address.DeletedAt = now;
        address.UpdatedAt = now;
        var wasDefault = address.IsDefault;
        address.IsDefault = false;
        await db.SaveChangesAsync(ct);

        if (wasDefault)
        {
            var next = await db.Addresses
                .Where(a => a.UserId == userId && a.DeletedAt == null)
                .OrderByDescending(a => a.CreatedAt)
                .FirstOrDefaultAsync(ct);
            if (next is not null)
            {
                next.IsDefault = true;
                await db.SaveChangesAsync(ct);
            }
        }
        await tx.CommitAsync(ct);
    }

    /// <summary>Two saves in one transaction: clear the old default first, so the filtered unique index never sees two defaults.</summary>
    private async Task SaveWithDefaultAsync(Guid userId, Address address, bool makeDefault, CancellationToken ct)
    {
        await using var tx = await db.Database.BeginTransactionAsync(ct);
        if (makeDefault)
        {
            var others = await db.Addresses
                .Where(a => a.UserId == userId && a.IsDefault && a.Id != address.Id && a.DeletedAt == null)
                .ToListAsync(ct);
            others.ForEach(a => a.IsDefault = false);
            address.IsDefault = false;
            await db.SaveChangesAsync(ct);
        }
        address.IsDefault = makeDefault;
        await db.SaveChangesAsync(ct);
        await tx.CommitAsync(ct);
    }

    private async Task<Address> FindOwnedAsync(Guid userId, Guid id, CancellationToken ct) =>
        await db.Addresses.FirstOrDefaultAsync(a => a.Id == id && a.UserId == userId && a.DeletedAt == null, ct)
        ?? throw ApiException.NotFound("Không tìm thấy địa chỉ");

    private static (string Phone, decimal Latitude, decimal Longitude) Validate(SaveAddressRequest request)
    {
        var phone = PhoneNumber.Normalize(request.ReceiverPhone)
                    ?? throw new ApiException(StatusCodes.Status400BadRequest, ErrorCodes.ValidationError, "Số điện thoại không hợp lệ", "receiverPhone");
        var (latitude, longitude) = Geo.Require(request.Latitude, request.Longitude);
        return (phone, latitude, longitude);
    }

    private static void Apply(Address address, SaveAddressRequest request, string phone, decimal latitude, decimal longitude, DateTimeOffset now)
    {
        address.Label = request.Label.Trim();
        address.ReceiverName = request.ReceiverName.Trim();
        address.ReceiverPhone = phone;
        address.FullAddress = request.FullAddress.Trim();
        address.Latitude = latitude;
        address.Longitude = longitude;
        address.Note = string.IsNullOrWhiteSpace(request.Note) ? null : request.Note.Trim();
        address.UpdatedAt = now;
    }

    private static AddressDto ToDto(Address a) =>
        new(a.Id, a.Label, a.ReceiverName, a.ReceiverPhone, a.FullAddress, a.Latitude, a.Longitude, a.Note, a.IsDefault);
}
