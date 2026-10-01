using System;
using System.Collections.Generic;

namespace Servio.Api.Data.Entities;

public partial class PartnerProfile
{
    public Guid Id { get; set; }

    public Guid UserId { get; set; }

    public string? Bio { get; set; }

    public int YearsOfExperience { get; set; }

    public byte VerificationStatus { get; set; }

    public string? VerificationNote { get; set; }

    public DateTimeOffset? VerifiedAt { get; set; }

    public Guid? VerifiedByAdminId { get; set; }

    public bool IsOnline { get; set; }

    public DateTimeOffset? LastHeartbeatAt { get; set; }

    public decimal? CurrentLatitude { get; set; }

    public decimal? CurrentLongitude { get; set; }

    public decimal? AnchorLatitude { get; set; }

    public decimal? AnchorLongitude { get; set; }

    public int ServiceRadiusKm { get; set; }

    public decimal? AverageRating { get; set; }

    public int TotalReviews { get; set; }

    public int CompletedOrders { get; set; }

    public int CancelledOrders { get; set; }

    public DateTimeOffset CreatedAt { get; set; }

    public DateTimeOffset UpdatedAt { get; set; }

    public virtual ICollection<CancellationCharge> CancellationCharges { get; set; } = new List<CancellationCharge>();

    public virtual ICollection<Conversation> Conversations { get; set; } = new List<Conversation>();

    public virtual ICollection<OrderAssignment> OrderAssignments { get; set; } = new List<OrderAssignment>();

    public virtual ICollection<PartnerDocument> PartnerDocuments { get; set; } = new List<PartnerDocument>();

    public virtual ICollection<PartnerSkill> PartnerSkills { get; set; } = new List<PartnerSkill>();

    public virtual ICollection<Quote> Quotes { get; set; } = new List<Quote>();

    public virtual User User { get; set; } = null!;

    public virtual AdminUser? VerifiedByAdmin { get; set; }
}
