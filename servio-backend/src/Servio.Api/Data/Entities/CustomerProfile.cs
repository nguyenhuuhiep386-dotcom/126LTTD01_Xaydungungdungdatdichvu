using System;
using System.Collections.Generic;

namespace Servio.Api.Data.Entities;

public partial class CustomerProfile
{
    public Guid Id { get; set; }

    public Guid UserId { get; set; }

    public decimal? AverageRating { get; set; }

    public int TotalReviews { get; set; }

    public int TotalOrders { get; set; }

    public int CancelledOrders { get; set; }

    public DateTimeOffset CreatedAt { get; set; }

    public DateTimeOffset UpdatedAt { get; set; }

    public virtual ICollection<CancellationCharge> CancellationCharges { get; set; } = new List<CancellationCharge>();

    public virtual ICollection<Conversation> Conversations { get; set; } = new List<Conversation>();

    public virtual ICollection<Order> Orders { get; set; } = new List<Order>();

    public virtual ICollection<ServiceRequest> ServiceRequests { get; set; } = new List<ServiceRequest>();

    public virtual User User { get; set; } = null!;
}
