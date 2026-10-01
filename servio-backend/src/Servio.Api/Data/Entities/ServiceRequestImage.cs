using System;
using System.Collections.Generic;

namespace Servio.Api.Data.Entities;

public partial class ServiceRequestImage
{
    public Guid Id { get; set; }

    public Guid ServiceRequestId { get; set; }

    public string Url { get; set; } = null!;

    public string? ThumbnailUrl { get; set; }

    public byte MediaType { get; set; }

    public int DisplayOrder { get; set; }

    public virtual ServiceRequest ServiceRequest { get; set; } = null!;
}
