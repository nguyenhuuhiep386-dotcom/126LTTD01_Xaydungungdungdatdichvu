using Microsoft.EntityFrameworkCore;
using Servio.Api.Common;
using Servio.Api.Data.Entities;
using Servio.Api.Services.Admin;

namespace Servio.Tests.Services;

/// <summary>AW-15: values are validated by type, audited, and the cached value changes at once.</summary>
public sealed class SystemConfigTests : IDisposable
{
    private readonly TestContext _ctx = new();
    private static readonly AdminActor Admin = new(Guid.NewGuid(), null, null);

    public void Dispose() => _ctx.Dispose();

    [Fact]
    public async Task Update_ValidatesByType_AuditsAndRefreshesTheCache()
    {
        _ctx.Db.SystemConfigs.Add(new SystemConfig { Key = "order.auto_confirm_hours", Value = "24", DataType = "int", Group = "ORDER", UpdatedAt = _ctx.Clock.GetUtcNow() });
        await _ctx.Db.SaveChangesAsync();
        var configs = _ctx.Configs();
        Assert.Equal(24, await configs.GetIntAsync("order.auto_confirm_hours", 99, default)); // now cached

        var bad = await Assert.ThrowsAsync<ApiException>(() => configs.UpdateAsync(Admin, "order.auto_confirm_hours", "-1", default));
        await configs.UpdateAsync(Admin, "order.auto_confirm_hours", " 0 ", default);

        Assert.Equal(ErrorCodes.ValidationError, bad.Code);
        Assert.Equal(0, await configs.GetIntAsync("order.auto_confirm_hours", 99, default));
        var audit = await _ctx.Db.AuditLogs.SingleAsync();
        Assert.Equal(("SYSTEM_CONFIG_UPDATED", "order.auto_confirm_hours"), (audit.Action, audit.EntityId));
    }
}
