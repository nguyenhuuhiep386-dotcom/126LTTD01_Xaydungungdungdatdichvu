using System;
using System.Collections.Generic;

namespace Servio.Api.Data.Entities;

public partial class AuditLog
{
    public Guid Id { get; set; }

    public Guid? ActorId { get; set; }

    public byte ActorType { get; set; }

    public string Action { get; set; } = null!;

    public string EntityType { get; set; } = null!;

    public string? EntityId { get; set; }

    public string? OldValues { get; set; }

    public string? NewValues { get; set; }

    public string? IpAddress { get; set; }

    public string? UserAgent { get; set; }

    public DateTimeOffset CreatedAt { get; set; }
}
