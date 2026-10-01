using System;
using System.Collections.Generic;

namespace Servio.Api.Data.Entities;

public partial class AdminUser
{
    public Guid Id { get; set; }

    public string Email { get; set; } = null!;

    public string PasswordHash { get; set; } = null!;

    public string FullName { get; set; } = null!;

    public byte Role { get; set; }

    public bool IsActive { get; set; }

    public DateTimeOffset? LastLoginAt { get; set; }

    public bool MustChangePassword { get; set; }

    public DateTimeOffset CreatedAt { get; set; }

    public DateTimeOffset UpdatedAt { get; set; }

    public virtual ICollection<Dispute> Disputes { get; set; } = new List<Dispute>();

    public virtual ICollection<OrderStatusHistory> OrderStatusHistories { get; set; } = new List<OrderStatusHistory>();

    public virtual ICollection<PartnerProfile> PartnerProfiles { get; set; } = new List<PartnerProfile>();

    public virtual ICollection<SystemConfig> SystemConfigs { get; set; } = new List<SystemConfig>();

    public virtual ICollection<Transaction> Transactions { get; set; } = new List<Transaction>();
}
