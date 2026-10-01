using System;
using System.Collections.Generic;

namespace Servio.Api.Data.Entities;

public partial class Notification
{
    public Guid Id { get; set; }

    public Guid UserId { get; set; }

    public byte Type { get; set; }

    public string Title { get; set; } = null!;

    public string Body { get; set; } = null!;

    public string? ImageUrl { get; set; }

    public string? DataPayload { get; set; }

    public byte AppFlavor { get; set; }

    public string? DeepLink { get; set; }

    public bool IsRead { get; set; }

    public DateTimeOffset? ReadAt { get; set; }

    public bool SentViaPush { get; set; }

    public DateTimeOffset CreatedAt { get; set; }

    public virtual User User { get; set; } = null!;
}
