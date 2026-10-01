using System;
using System.Collections.Generic;

namespace Servio.Api.Data.Entities;

public partial class OrderExtraCharge
{
    public Guid Id { get; set; }

    public Guid OrderId { get; set; }

    public Guid OrderAssignmentId { get; set; }

    public string Description { get; set; } = null!;

    public decimal Amount { get; set; }

    public string? EvidenceImageUrl { get; set; }

    public byte Status { get; set; }

    public DateTimeOffset? RespondedAt { get; set; }

    public Guid? RespondedByUserId { get; set; }

    public string? ResponseNote { get; set; }

    public DateTimeOffset CreatedAt { get; set; }

    public byte[] RowVersion { get; set; } = null!;

    public virtual Order Order { get; set; } = null!;

    public virtual OrderAssignment OrderAssignment { get; set; } = null!;

    public virtual User? RespondedByUser { get; set; }
}
