using System.Security.Claims;
using System.Text.Encodings.Web;
using System.Text.Json;
using Servio.Api.Common;
using Servio.Api.Data;
using Servio.Api.Data.Entities;

namespace Servio.Api.Services.Admin;

/// <summary>The signed-in admin and request info, needed for AuditLogs (F-ADM-13).</summary>
public sealed record AdminActor(Guid AdminId, string? IpAddress, string? UserAgent)
{
    public static AdminActor From(HttpContext context) => new(
        Guid.Parse(context.User.FindFirstValue(ClaimTypes.NameIdentifier)
                   ?? throw new InvalidOperationException("Admin page called without an admin cookie.")),
        context.Connection.RemoteIpAddress?.ToString(),
        context.Request.Headers.UserAgent.ToString() is { Length: > 0 } agent ? agent[..Math.Min(agent.Length, 300)] : null);
}

public static class AuditLogExtensions
{
    // Relaxed escaping keeps Vietnamese readable in the database (values are never rendered as raw HTML).
    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web) { Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping };

    /// <summary>
    /// Adds an audit entry to the change tracker. It is saved by the caller's SaveChanges, in the same
    /// transaction as the change it describes (spec 5.2.7: SQL is the audit source of truth).
    /// </summary>
    public static void AddAudit(this ServioDbContext db, AdminActor actor, string action, string entityType, object entityId,
        object? oldValues, object? newValues, DateTimeOffset now)
    {
        db.AuditLogs.Add(new AuditLog
        {
            Id = Guid.CreateVersion7(),
            ActorId = actor.AdminId,
            ActorType = (byte)ActorType.Admin,
            Action = action,
            EntityType = entityType,
            EntityId = entityId.ToString(),
            OldValues = oldValues is null ? null : JsonSerializer.Serialize(oldValues, Json),
            NewValues = newValues is null ? null : JsonSerializer.Serialize(newValues, Json),
            IpAddress = actor.IpAddress,
            UserAgent = actor.UserAgent,
            CreatedAt = now,
        });
    }
}
