using System;
using System.Collections.Generic;

namespace Servio.Api.Data.Entities;

public partial class CancellationCharge
{
    public Guid Id { get; set; }

    public Guid OrderId { get; set; }

    public Guid OrderAssignmentId { get; set; }

    public Guid CustomerId { get; set; }

    public Guid PartnerProfileId { get; set; }

    public decimal Amount { get; set; }

    public byte Reason { get; set; }

    public byte Status { get; set; }

    public Guid? CollectedTransactionId { get; set; }

    public DateTimeOffset? CompensationCreditedAt { get; set; }

    public DateTimeOffset CreatedAt { get; set; }

    public DateTimeOffset UpdatedAt { get; set; }

    public virtual Transaction? CollectedTransaction { get; set; }

    public virtual CustomerProfile Customer { get; set; } = null!;

    public virtual Order Order { get; set; } = null!;

    public virtual OrderAssignment OrderAssignment { get; set; } = null!;

    public virtual PartnerProfile PartnerProfile { get; set; } = null!;

    public virtual ICollection<WalletTransaction> WalletTransactions { get; set; } = new List<WalletTransaction>();
}
