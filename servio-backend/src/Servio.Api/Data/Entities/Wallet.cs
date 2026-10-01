using System;
using System.Collections.Generic;

namespace Servio.Api.Data.Entities;

public partial class Wallet
{
    public Guid Id { get; set; }

    public Guid UserId { get; set; }

    public decimal AvailableBalance { get; set; }

    public decimal DebtBalance { get; set; }

    public string Currency { get; set; } = null!;

    public bool IsLocked { get; set; }

    public DateTimeOffset UpdatedAt { get; set; }

    public byte[] RowVersion { get; set; } = null!;

    public virtual User User { get; set; } = null!;

    public virtual ICollection<WalletTransaction> WalletTransactions { get; set; } = new List<WalletTransaction>();
}
