using System;
using System.Collections.Generic;

namespace Servio.Api.Data.Entities;

public partial class WalletTransaction
{
    public Guid Id { get; set; }

    public Guid WalletId { get; set; }

    public Guid? OrderId { get; set; }

    public Guid? OrderAssignmentId { get; set; }

    public Guid? TransactionId { get; set; }

    public Guid? CancellationChargeId { get; set; }

    public byte Type { get; set; }

    public byte Direction { get; set; }

    public byte Bucket { get; set; }

    public byte LiabilityRole { get; set; }

    public decimal Amount { get; set; }

    public decimal BalanceBefore { get; set; }

    public decimal BalanceAfter { get; set; }

    public string Description { get; set; } = null!;

    public string BusinessEventKey { get; set; } = null!;

    public DateTimeOffset CreatedAt { get; set; }

    public virtual CancellationCharge? CancellationCharge { get; set; }

    public virtual Order? Order { get; set; }

    public virtual OrderAssignment? OrderAssignment { get; set; }

    public virtual Transaction? Transaction { get; set; }

    public virtual Wallet Wallet { get; set; } = null!;
}
