using System;
using System.Collections.Generic;

namespace Servio.Api.Data.Entities;

public partial class Transaction
{
    public Guid Id { get; set; }

    public string Code { get; set; } = null!;

    public Guid? OrderId { get; set; }

    public Guid UserId { get; set; }

    public byte Type { get; set; }

    public byte Provider { get; set; }

    public decimal Amount { get; set; }

    public string Currency { get; set; } = null!;

    public byte Status { get; set; }

    public bool IsPresumed { get; set; }

    public string? BankTransferReference { get; set; }

    public Guid? EvidenceFileId { get; set; }

    public Guid? ConfirmedByAdminId { get; set; }

    public string? IdempotencyKey { get; set; }

    public string? BusinessEventKey { get; set; }

    public DateTimeOffset? PaidAt { get; set; }

    public DateTimeOffset CreatedAt { get; set; }

    public DateTimeOffset UpdatedAt { get; set; }

    public virtual ICollection<CancellationCharge> CancellationCharges { get; set; } = new List<CancellationCharge>();

    public virtual AdminUser? ConfirmedByAdmin { get; set; }

    public virtual UploadedFile? EvidenceFile { get; set; }

    public virtual Order? Order { get; set; }

    public virtual User User { get; set; } = null!;

    public virtual ICollection<WalletTransaction> WalletTransactions { get; set; } = new List<WalletTransaction>();
}
