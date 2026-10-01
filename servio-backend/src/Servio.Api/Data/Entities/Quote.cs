using System;
using System.Collections.Generic;

namespace Servio.Api.Data.Entities;

public partial class Quote
{
    public Guid Id { get; set; }

    public Guid ServiceRequestId { get; set; }

    public Guid PartnerProfileId { get; set; }

    public decimal Amount { get; set; }

    public int EstimatedDurationMinutes { get; set; }

    public DateTimeOffset AvailableFrom { get; set; }

    public DateTimeOffset EstimatedEndAt { get; set; }

    public string? Note { get; set; }

    public byte Status { get; set; }

    public int RequestRevision { get; set; }

    public DateTimeOffset? RespondedAt { get; set; }

    public DateTimeOffset CreatedAt { get; set; }

    public DateTimeOffset UpdatedAt { get; set; }

    public byte[] RowVersion { get; set; } = null!;

    public virtual OrderAssignment? OrderAssignment { get; set; }

    public virtual PartnerProfile PartnerProfile { get; set; } = null!;

    public virtual ServiceRequest ServiceRequest { get; set; } = null!;
}
