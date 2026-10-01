using System;
using System.Collections.Generic;

namespace Servio.Api.Data.Entities;

public partial class Order
{
    public Guid Id { get; set; }

    public string Code { get; set; } = null!;

    public Guid ServiceRequestId { get; set; }

    public Guid CustomerId { get; set; }

    public Guid ServiceCategoryId { get; set; }

    public byte Status { get; set; }

    public decimal SubTotal { get; set; }

    public decimal ExtraChargeTotal { get; set; }

    public decimal TotalAmount { get; set; }

    public decimal CommissionAmount { get; set; }

    public decimal PartnerEarning { get; set; }

    public byte PaymentMethod { get; set; }

    public byte PaymentStatus { get; set; }

    public DateTimeOffset? ScheduledStartAt { get; set; }

    public DateTimeOffset? AcceptedAt { get; set; }

    public DateTimeOffset? StartedAt { get; set; }

    public DateTimeOffset? CompletedByPartnerAt { get; set; }

    public DateTimeOffset? AutoConfirmAt { get; set; }

    public DateTimeOffset? ConfirmedAt { get; set; }

    public DateTimeOffset? CompletedAt { get; set; }

    public DateTimeOffset? DisputeDeadlineAt { get; set; }

    public DateTimeOffset? UnpaidReportDeadlineAt { get; set; }

    public DateTimeOffset? CancelledAt { get; set; }

    public byte? CancelledBy { get; set; }

    public string? CancelReason { get; set; }

    public decimal CancellationFee { get; set; }

    public DateTimeOffset CreatedAt { get; set; }

    public DateTimeOffset UpdatedAt { get; set; }

    public byte[] RowVersion { get; set; } = null!;

    public virtual ICollection<CancellationCharge> CancellationCharges { get; set; } = new List<CancellationCharge>();

    public virtual ICollection<Conversation> Conversations { get; set; } = new List<Conversation>();

    public virtual CustomerProfile Customer { get; set; } = null!;

    public virtual ICollection<Dispute> Disputes { get; set; } = new List<Dispute>();

    public virtual ICollection<OrderAssignment> OrderAssignments { get; set; } = new List<OrderAssignment>();

    public virtual ICollection<OrderExtraCharge> OrderExtraCharges { get; set; } = new List<OrderExtraCharge>();

    public virtual ICollection<OrderStatusHistory> OrderStatusHistories { get; set; } = new List<OrderStatusHistory>();

    public virtual ICollection<Review> Reviews { get; set; } = new List<Review>();

    public virtual ServiceCategory ServiceCategory { get; set; } = null!;

    public virtual ServiceRequest ServiceRequest { get; set; } = null!;

    public virtual ICollection<Transaction> Transactions { get; set; } = new List<Transaction>();

    public virtual ICollection<WalletTransaction> WalletTransactions { get; set; } = new List<WalletTransaction>();
}
