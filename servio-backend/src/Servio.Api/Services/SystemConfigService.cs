using System.Globalization;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Caching.Memory;
using Servio.Api.Common;
using Servio.Api.Data;
using Servio.Api.Data.Entities;
using Servio.Api.Services.Admin;

namespace Servio.Api.Services;

/// <summary>
/// Reads policy values from SystemConfigs (spec 5.2.7). Cached for one minute, so a value changed in the
/// database (e.g. shortening order.auto_confirm_hours for a demo) applies within a minute without restart.
/// </summary>
public sealed class SystemConfigService(ServioDbContext db, IMemoryCache cache)
{
    public const string PartnerOfflineTimeoutMinutes = "partner.offline_timeout_minutes";
    public const string PartnerHeartbeatSeconds = "partner.heartbeat_seconds";

    private static readonly TimeSpan CacheDuration = TimeSpan.FromMinutes(1);

    public async Task<int> GetIntAsync(string key, int fallback, CancellationToken ct) =>
        int.TryParse(await GetAsync(key, ct), NumberStyles.Integer, CultureInfo.InvariantCulture, out var value) ? value : fallback;

    public async Task<decimal> GetDecimalAsync(string key, decimal fallback, CancellationToken ct) =>
        decimal.TryParse(await GetAsync(key, ct), NumberStyles.Number, CultureInfo.InvariantCulture, out var value) ? value : fallback;

    public async Task<bool> GetBoolAsync(string key, bool fallback, CancellationToken ct) =>
        bool.TryParse(await GetAsync(key, ct), out var value) ? value : fallback;

    /// <summary>
    /// AW-15: change one value. It is checked against its DataType (int/decimal ≥ 0, bool, short string), audited in the same
    /// save, and its cache entry dropped so the new value applies at once on this server.
    /// </summary>
    public async Task UpdateAsync(AdminActor actor, string key, string? value, CancellationToken ct)
    {
        var row = await db.SystemConfigs.FirstOrDefaultAsync(c => c.Key == key, ct)
                  ?? throw ApiException.NotFound("Không tìm thấy cấu hình");
        var text = (value ?? "").Trim();
        var valid = row.DataType switch
        {
            "int" => int.TryParse(text, NumberStyles.Integer, CultureInfo.InvariantCulture, out var i) && i >= 0,
            "decimal" => decimal.TryParse(text, NumberStyles.Number, CultureInfo.InvariantCulture, out var d) && d >= 0,
            "bool" => bool.TryParse(text, out _),
            _ => text.Length is > 0 and <= 100,
        };
        if (!valid)
        {
            throw new ApiException(StatusCodes.Status400BadRequest, ErrorCodes.ValidationError,
                row.DataType switch
                {
                    "int" => $"{key}: nhập số nguyên ≥ 0",
                    "decimal" => $"{key}: nhập số ≥ 0, dùng dấu chấm cho phần thập phân",
                    "bool" => $"{key}: chọn true hoặc false",
                    _ => $"{key}: nhập 1–100 ký tự",
                }, "value");
        }
        if (row.DataType == "bool")
        {
            text = text.ToLowerInvariant();
        }
        if (text == row.Value)
        {
            return;
        }
        var now = DateTimeOffset.UtcNow;
        db.AddAudit(actor, "SYSTEM_CONFIG_UPDATED", nameof(SystemConfig), key, new { row.Value }, new { Value = text }, now);
        row.Value = text;
        row.UpdatedByAdminId = actor.AdminId;
        row.UpdatedAt = now;
        await db.SaveChangesAsync(ct);
        cache.Remove($"config:{key}");
    }

    private async Task<string?> GetAsync(string key, CancellationToken ct) =>
        await cache.GetOrCreateAsync($"config:{key}", async entry =>
        {
            entry.AbsoluteExpirationRelativeToNow = CacheDuration;
            return await db.SystemConfigs.AsNoTracking().Where(c => c.Key == key).Select(c => c.Value).FirstOrDefaultAsync(ct);
        });
}
