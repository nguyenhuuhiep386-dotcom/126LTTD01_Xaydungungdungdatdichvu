using Microsoft.EntityFrameworkCore;
using Servio.Api.Common;
using Servio.Api.Services.Users;

namespace Servio.Tests.Services;

public sealed class AddressServiceTests : IDisposable
{
    private readonly TestContext _ctx = new();

    public void Dispose() => _ctx.Dispose();

    private AddressService Service() => new(_ctx.Db, _ctx.Clock);

    private static SaveAddressRequest Request(string label, bool isDefault = false) =>
        new(label, "Nguyễn Văn An", "0901234567", "1 Võ Văn Ngân, Thủ Đức", 10.850000m, 106.771900m, null, isDefault);

    [Fact]
    public async Task Create_FirstAddressBecomesDefault_AndNewDefaultReplacesOld()
    {
        var user = await _ctx.AddUserAsync(UserRoleType.Customer);
        var service = Service();

        var home = await service.CreateAsync(user.Id, Request("Nhà"), default);
        var office = await service.CreateAsync(user.Id, Request("Cơ quan", isDefault: true), default);

        Assert.True(home.IsDefault);
        Assert.True(office.IsDefault);
        var list = await service.ListAsync(user.Id, default);
        Assert.Equal(["Cơ quan", "Nhà"], list.Select(a => a.Label));
        Assert.Single(list, a => a.IsDefault);
        Assert.Equal("+84901234567", list[0].ReceiverPhone);
    }

    [Fact]
    public async Task Delete_DefaultAddress_PromotesNewestRemaining()
    {
        var user = await _ctx.AddUserAsync(UserRoleType.Customer);
        var service = Service();
        var home = await service.CreateAsync(user.Id, Request("Nhà"), default);
        _ctx.Clock.Advance(TimeSpan.FromMinutes(1));
        await service.CreateAsync(user.Id, Request("Cơ quan"), default);

        await service.DeleteAsync(user.Id, home.Id, default);

        var list = await service.ListAsync(user.Id, default);
        Assert.Equal("Cơ quan", Assert.Single(list).Label);
        Assert.True(list[0].IsDefault);
        Assert.NotNull((await _ctx.Db.Addresses.SingleAsync(a => a.Id == home.Id)).DeletedAt);
    }

    [Fact]
    public async Task Create_RejectsEleventhAddress()
    {
        var user = await _ctx.AddUserAsync(UserRoleType.Customer);
        var service = Service();
        for (var i = 0; i < AddressService.MaxAddresses; i++)
        {
            await service.CreateAsync(user.Id, Request($"Địa chỉ {i}"), default);
        }

        var error = await Assert.ThrowsAsync<ApiException>(() => service.CreateAsync(user.Id, Request("Thừa"), default));

        Assert.Equal(ErrorCodes.AddressLimitReached, error.Code);
    }

    [Fact]
    public async Task Update_OtherUsersAddress_IsNotFound()
    {
        var owner = await _ctx.AddUserAsync(UserRoleType.Customer);
        var other = await _ctx.AddUserAsync(UserRoleType.Customer, phone: "+84909999999");
        var service = Service();
        var address = await service.CreateAsync(owner.Id, Request("Nhà"), default);

        var error = await Assert.ThrowsAsync<ApiException>(() => service.UpdateAsync(other.Id, address.Id, Request("Hack"), default));

        Assert.Equal(ErrorCodes.NotFound, error.Code);
    }
}
