using System.Globalization;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Caching.Memory;
using Servio.Api.Data;

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

    private async Task<string?> GetAsync(string key, CancellationToken ct) =>
        await cache.GetOrCreateAsync($"config:{key}", async entry =>
        {
            entry.AbsoluteExpirationRelativeToNow = CacheDuration;
            return await db.SystemConfigs.AsNoTracking().Where(c => c.Key == key).Select(c => c.Value).FirstOrDefaultAsync(ct);
        });
}
