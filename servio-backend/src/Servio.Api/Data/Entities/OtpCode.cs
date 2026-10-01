using System;
using System.Collections.Generic;

namespace Servio.Api.Data.Entities;

public partial class OtpCode
{
    public Guid Id { get; set; }

    public string PhoneNumber { get; set; } = null!;

    public string CodeHash { get; set; } = null!;

    public byte Purpose { get; set; }

    public byte AppFlavor { get; set; }

    public DateTimeOffset ExpiresAt { get; set; }

    public int AttemptCount { get; set; }

    public bool IsUsed { get; set; }

    public DateTimeOffset? ConsumedAt { get; set; }

    public DateTimeOffset CreatedAt { get; set; }
}
