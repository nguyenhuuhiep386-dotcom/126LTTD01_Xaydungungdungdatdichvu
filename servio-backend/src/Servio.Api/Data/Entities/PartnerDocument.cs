using System;
using System.Collections.Generic;

namespace Servio.Api.Data.Entities;

public partial class PartnerDocument
{
    public Guid Id { get; set; }

    public Guid PartnerProfileId { get; set; }

    public byte DocumentType { get; set; }

    public string FileUrl { get; set; } = null!;

    public string? DocumentNumber { get; set; }

    public byte Status { get; set; }

    public string? RejectReason { get; set; }

    public DateOnly? ExpiryDate { get; set; }

    public DateTimeOffset CreatedAt { get; set; }

    public virtual PartnerProfile PartnerProfile { get; set; } = null!;
}
