using System;
using System.Collections.Generic;

namespace Servio.Api.Data.Entities;

public partial class Dispute
{
    public Guid Id { get; set; }

    public string Code { get; set; } = null!;

    public Guid OrderId { get; set; }

    public Guid RaisedByUserId { get; set; }

    public byte RaisedByType { get; set; }

    public byte Reason { get; set; }

    public string Description { get; set; } = null!;

    public string? EvidenceUrls { get; set; }

    public byte Status { get; set; }

    public string? ResolutionNote { get; set; }

    public Guid? AssignedAdminId { get; set; }

    public byte PreviousOrderStatus { get; set; }

    public int? AutoConfirmRemainingSeconds { get; set; }

    public DateTimeOffset SlaDueAt { get; set; }

    public DateTimeOffset? ResolvedAt { get; set; }

    public DateTimeOffset CreatedAt { get; set; }

    public DateTimeOffset UpdatedAt { get; set; }

    public byte[] RowVersion { get; set; } = null!;

    public virtual AdminUser? AssignedAdmin { get; set; }

    public virtual Order Order { get; set; } = null!;

    public virtual User RaisedByUser { get; set; } = null!;
}
