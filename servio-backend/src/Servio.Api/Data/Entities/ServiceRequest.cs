using System;
using System.Collections.Generic;

namespace Servio.Api.Data.Entities;

public partial class ServiceRequest
{
    public Guid Id { get; set; }

    public string Code { get; set; } = null!;

    public Guid CustomerId { get; set; }

    public Guid ServiceCategoryId { get; set; }

    public string Title { get; set; } = null!;

    public string Description { get; set; } = null!;

    public Guid AddressId { get; set; }

    public string AddressSnapshot { get; set; } = null!;

    public decimal Latitude { get; set; }

    public decimal Longitude { get; set; }

    public byte ScheduleType { get; set; }

    public DateTimeOffset? ScheduledStartAt { get; set; }

    public DateTimeOffset? ScheduledEndAt { get; set; }

    public decimal? BudgetMin { get; set; }

    public decimal? BudgetMax { get; set; }

    public int? RequireExperienceYears { get; set; }

    public bool RequireCertificate { get; set; }

    public decimal? RequireMinRating { get; set; }

    public int SearchRadiusKm { get; set; }

    public byte Status { get; set; }

    public int QuoteCount { get; set; }

    public int ViewCount { get; set; }

    public int Revision { get; set; }

    public DateTimeOffset? ExpiresAt { get; set; }

    public DateTimeOffset? PublishedAt { get; set; }

    public string? ModerationNote { get; set; }

    public string? CancelReason { get; set; }

    public DateTimeOffset CreatedAt { get; set; }

    public DateTimeOffset UpdatedAt { get; set; }

    public byte[] RowVersion { get; set; } = null!;

    public virtual Address Address { get; set; } = null!;

    public virtual ICollection<Conversation> Conversations { get; set; } = new List<Conversation>();

    public virtual CustomerProfile Customer { get; set; } = null!;

    public virtual Order? Order { get; set; }

    public virtual ICollection<Quote> Quotes { get; set; } = new List<Quote>();

    public virtual ServiceCategory ServiceCategory { get; set; } = null!;

    public virtual ICollection<ServiceRequestImage> ServiceRequestImages { get; set; } = new List<ServiceRequestImage>();
}
