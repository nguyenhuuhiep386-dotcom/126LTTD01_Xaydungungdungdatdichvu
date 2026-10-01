using System;
using System.Collections.Generic;

namespace Servio.Api.Data.Entities;

public partial class OrderAssignment
{
    public Guid Id { get; set; }

    public Guid OrderId { get; set; }

    public Guid PartnerProfileId { get; set; }

    public Guid? QuoteId { get; set; }

    public byte Status { get; set; }

    public bool IsPrimary { get; set; }

    public decimal Amount { get; set; }

    public decimal ExtraChargeTotal { get; set; }

    public decimal CommissionRateSnapshot { get; set; }

    public decimal CommissionAmount { get; set; }

    public decimal Earning { get; set; }

    public decimal CustomerPayable { get; set; }

    public byte PaymentStatus { get; set; }

    public DateTimeOffset AcceptanceDeadlineAt { get; set; }

    public DateTimeOffset? ReservedStartAt { get; set; }

    public DateTimeOffset? ReservedEndAt { get; set; }

    public DateTimeOffset? AcceptedAt { get; set; }

    public DateTimeOffset? OnTheWayAt { get; set; }

    public DateTimeOffset? ArrivedAt { get; set; }

    public DateTimeOffset? StartedAt { get; set; }

    public DateTimeOffset? CompletedAt { get; set; }

    public DateTimeOffset? PaidAt { get; set; }

    public byte? ArrivalMethod { get; set; }

    public decimal? ArrivalLatitude { get; set; }

    public decimal? ArrivalLongitude { get; set; }

    public int? ArrivalDistanceMeters { get; set; }

    public int? ArrivalAccuracyMeters { get; set; }

    public DateTimeOffset? CustomerConfirmedArrivalAt { get; set; }

    public decimal? LastLatitude { get; set; }

    public decimal? LastLongitude { get; set; }

    public int? LastLocationAccuracy { get; set; }

    public DateTimeOffset? LastLocationAt { get; set; }

    public DateTimeOffset? CancelledAt { get; set; }

    public byte? CancelledBy { get; set; }

    public string? CancelReason { get; set; }

    public DateTimeOffset CreatedAt { get; set; }

    public DateTimeOffset UpdatedAt { get; set; }

    public byte[] RowVersion { get; set; } = null!;

    public virtual ICollection<ArrivalConfirmationRequest> ArrivalConfirmationRequests { get; set; } = new List<ArrivalConfirmationRequest>();

    public virtual CancellationCharge? CancellationCharge { get; set; }

    public virtual Order Order { get; set; } = null!;

    public virtual ICollection<OrderExtraCharge> OrderExtraCharges { get; set; } = new List<OrderExtraCharge>();

    public virtual ICollection<OrderImage> OrderImages { get; set; } = new List<OrderImage>();

    public virtual ICollection<OrderStatusHistory> OrderStatusHistories { get; set; } = new List<OrderStatusHistory>();

    public virtual PartnerProfile PartnerProfile { get; set; } = null!;

    public virtual Quote? Quote { get; set; }

    public virtual ICollection<Review> Reviews { get; set; } = new List<Review>();

    public virtual ICollection<WalletTransaction> WalletTransactions { get; set; } = new List<WalletTransaction>();
}
