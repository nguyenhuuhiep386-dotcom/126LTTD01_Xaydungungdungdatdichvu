using System;
using System.Collections.Generic;

namespace Servio.Api.Data.Entities;

public partial class OrderImage
{
    public Guid Id { get; set; }

    public Guid OrderAssignmentId { get; set; }

    public string Url { get; set; } = null!;

    public byte Type { get; set; }

    public decimal? Latitude { get; set; }

    public decimal? Longitude { get; set; }

    public DateTimeOffset CreatedAt { get; set; }

    public virtual OrderAssignment OrderAssignment { get; set; } = null!;
}
