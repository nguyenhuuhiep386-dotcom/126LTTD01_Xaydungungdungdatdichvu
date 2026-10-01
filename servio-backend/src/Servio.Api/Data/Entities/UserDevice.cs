using System;
using System.Collections.Generic;

namespace Servio.Api.Data.Entities;

public partial class UserDevice
{
    public Guid Id { get; set; }

    public Guid UserId { get; set; }

    public string DeviceId { get; set; } = null!;

    public string? FcmToken { get; set; }

    public byte Platform { get; set; }

    public string? AppVersion { get; set; }

    public byte AppFlavor { get; set; }

    public bool IsActive { get; set; }

    public DateTimeOffset LastActiveAt { get; set; }

    public virtual User User { get; set; } = null!;
}
