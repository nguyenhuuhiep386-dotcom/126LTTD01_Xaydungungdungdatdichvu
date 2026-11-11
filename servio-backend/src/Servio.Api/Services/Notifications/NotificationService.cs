using System.Text.Json;
using Microsoft.EntityFrameworkCore;
using Servio.Api.Common;
using Servio.Api.Data;
using Servio.Api.Data.Entities;

namespace Servio.Api.Services.Notifications;

/// <summary>Notifications.Type (F-NOTI-03). PROMOTION (9) is out of the course scope.</summary>
public enum NotificationType : byte
{
    NewPostMatch = 1,
    NewQuote = 2,
    QuoteAccepted = 3,
    OrderStatusChanged = 4,
    NewMessage = 5,
    Payment = 6,
    Review = 7,
    KycResult = 8,
    System = 10,
}

/// <summary>Row of #101. data = the JSON payload, e.g. { "orderId": "..." }; deepLink e.g. "orders/{id}".</summary>
public sealed record NotificationDto(Guid Id, NotificationType Type, string Title, string Body, JsonElement? Data, string? DeepLink, bool IsRead, DateTimeOffset CreatedAt);

/// <summary>#104</summary>
public sealed record UnreadCountDto(int Total, IReadOnlyDictionary<NotificationType, int> ByType);

/// <summary>
/// M9 (0.2.7 ý 12): the SQL inbox is the source of truth, FCM is best effort. <see cref="SendAsync"/> is called after the
/// business change is committed: it saves the rows, then pushes to the active devices of the matching app; any failure is
/// logged and never fails the request that triggered it. Dead FCM tokens deactivate their device.
/// </summary>
public sealed class NotificationService(
    ServioDbContext db,
    IPushSender push,
    TimeProvider clock,
    ILogger<NotificationService> logger)
{
    public async Task SendAsync(IReadOnlyList<(AppFlavor Flavor, Guid UserId)> recipients, NotificationType type, string title, string body,
        object? data, string? deepLink, CancellationToken ct)
    {
        try
        {
            var now = clock.GetUtcNow();
            var payload = data is null ? null : JsonSerializer.Serialize(data, JsonSerializerOptions.Web);
            var rows = recipients.Distinct().Select(r => new Notification
            {
                Id = Guid.CreateVersion7(),
                UserId = r.UserId,
                AppFlavor = (byte)r.Flavor,
                Type = (byte)type,
                Title = Clip(title, 200),
                Body = Clip(body, 500),
                DataPayload = payload,
                DeepLink = deepLink,
                CreatedAt = now,
            }).ToList();
            db.Notifications.AddRange(rows);
            await db.SaveChangesAsync(ct);
            await PushAsync(rows, ct);
        }
        catch (Exception e) when (e is not OperationCanceledException)
        {
            logger.LogWarning(e, "Notification {Type} failed", type);
            // Do not let unsaved rows ride along with the caller's next SaveChanges.
            foreach (var entry in db.ChangeTracker.Entries<Notification>().Where(x => x.State == EntityState.Added).ToList())
            {
                entry.State = EntityState.Detached;
            }
        }
    }

    /// <summary>One FCM message per active device of the notification's user and app.</summary>
    private async Task PushAsync(List<Notification> rows, CancellationToken ct)
    {
        var userIds = rows.Select(r => r.UserId).Distinct().ToList();
        var devices = await db.UserDevices
            .Where(d => userIds.Contains(d.UserId) && d.IsActive && d.FcmToken != null)
            .ToListAsync(ct);
        foreach (var row in rows)
        {
            var data = new Dictionary<string, string>
            {
                ["notificationId"] = row.Id.ToString(),
                ["type"] = ((NotificationType)row.Type).ToString(),
                ["payload"] = row.DataPayload ?? "{}",
                ["deepLink"] = row.DeepLink ?? "",
            };
            foreach (var device in devices.Where(d => d.UserId == row.UserId && d.AppFlavor == row.AppFlavor))
            {
                try
                {
                    if (await push.SendAsync(device.FcmToken!, row.Title, row.Body, data, ct))
                    {
                        row.SentViaPush = true;
                    }
                    else
                    {
                        device.IsActive = false;
                    }
                }
                catch (Exception e) when (e is not OperationCanceledException)
                {
                    logger.LogWarning(e, "Push to device {DeviceId} failed", device.Id);
                }
            }
        }
        await db.SaveChangesAsync(ct);
    }

    /// <summary>#101 newest first, only the app of the token.</summary>
    public async Task<PagedResult<NotificationDto>> ListAsync(Guid userId, AppFlavor flavor, bool? isRead, int page, int pageSize, CancellationToken ct)
    {
        page = Math.Max(page, 1);
        pageSize = Math.Clamp(pageSize, 1, 50);
        var query = Mine(userId, flavor);
        if (isRead is { } read)
        {
            query = query.Where(n => n.IsRead == read);
        }
        var total = await query.CountAsync(ct);
        var rows = await query.OrderByDescending(n => n.CreatedAt).ThenByDescending(n => n.Id)
            .Skip((page - 1) * pageSize).Take(pageSize).ToListAsync(ct);
        return new PagedResult<NotificationDto>(rows.Select(n => new NotificationDto(n.Id, (NotificationType)n.Type, n.Title, n.Body,
            n.DataPayload is null ? null : JsonSerializer.Deserialize<JsonElement>(n.DataPayload), n.DeepLink, n.IsRead, n.CreatedAt)).ToList(),
            total, page, pageSize);
    }

    /// <summary>#102 (idempotent).</summary>
    public async Task MarkReadAsync(Guid userId, AppFlavor flavor, Guid id, CancellationToken ct)
    {
        var row = await Mine(userId, flavor).FirstOrDefaultAsync(n => n.Id == id, ct) ?? throw ApiException.NotFound("Không tìm thấy thông báo");
        if (!row.IsRead)
        {
            row.IsRead = true;
            row.ReadAt = clock.GetUtcNow();
            await db.SaveChangesAsync(ct);
        }
    }

    /// <summary>#103</summary>
    public async Task MarkAllReadAsync(Guid userId, AppFlavor flavor, CancellationToken ct)
    {
        var now = clock.GetUtcNow();
        // ponytail: loads the unread rows instead of ExecuteUpdate so the InMemory tests cover it; fine for an inbox.
        foreach (var row in await Mine(userId, flavor).Where(n => !n.IsRead).ToListAsync(ct))
        {
            row.IsRead = true;
            row.ReadAt = now;
        }
        await db.SaveChangesAsync(ct);
    }

    /// <summary>#104 badge.</summary>
    public async Task<UnreadCountDto> UnreadCountAsync(Guid userId, AppFlavor flavor, CancellationToken ct)
    {
        var byType = await Mine(userId, flavor).Where(n => !n.IsRead)
            .GroupBy(n => n.Type).Select(g => new { g.Key, Count = g.Count() }).ToListAsync(ct);
        return new UnreadCountDto(byType.Sum(x => x.Count), byType.ToDictionary(x => (NotificationType)x.Key, x => x.Count));
    }

    private IQueryable<Notification> Mine(Guid userId, AppFlavor flavor) =>
        db.Notifications.Where(n => n.UserId == userId && n.AppFlavor == (byte)flavor);

    private static string Clip(string text, int max) => text.Length <= max ? text : text[..(max - 1)] + "…";
}
